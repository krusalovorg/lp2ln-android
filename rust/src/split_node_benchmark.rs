//! Opt-in measurement against two real nodes; never runs during ordinary tests.
use super::tests::{stop_test_node, test_node};
use crate::files::{download, export_from, import, CHUNK_SIZE, DOWNLOAD_CONCURRENCY};
use lp2ln_core_v2::storage::{content_id_from_hex, content_id_hex, encrypt::generate_key, hash_bytes};
use serde_json::{json, Value};
use std::{fs, path::Path, time::{Duration, Instant}};

fn connect(native: &crate::NativeNode, nodes: &[Value]) {
    native.runtime.block_on(async {
        for node in nodes {
            native.node.connect("tcp", node["address"].as_str().unwrap().parse().unwrap()).await.unwrap();
        }
        for _ in 0..100 {
            let sessions = native.node.debug_sessions();
            if nodes.iter().all(|n| sessions.iter().any(|s|
                s.peer_id.as_deref() == n["peerId"].as_str() && s.protocol == "tcp" && s.is_active)) { return; }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        panic!("Both expected benchmark peers must authenticate over TCP");
    });
}

fn offered(entry: &Value, providers: &[String]) -> Value {
    json!({"id":entry["id"],"key":entry["key"],"name":entry["name"],"size":entry["size"],
        "providers":providers,"status":"offered","blocks":[],"replicas":0})
}

#[test]
#[ignore = "requires LP2LN_BENCH_NODES and LP2LN_BENCH_REPORT; uploads generated encrypted test blocks"]
fn measure_disjoint_blocks_on_two_live_nodes() {
    let nodes: Vec<Value> = serde_json::from_str(&std::env::var("LP2LN_BENCH_NODES").unwrap()).unwrap();
    assert_eq!(nodes.len(), 2);
    assert_ne!(nodes[0]["peerId"], nodes[1]["peerId"]);
    let report = std::path::PathBuf::from(std::env::var("LP2LN_BENCH_REPORT").unwrap());
    let count = 32usize;
    let source = (0..CHUNK_SIZE * count).map(|i| ((i * 17 + (i >> 7)) % 251) as u8).collect::<Vec<_>>();
    let expected_hash = content_id_hex(&hash_bytes(&source));
    let reuse = std::env::var_os("LP2LN_BENCH_FIXTURE").map(std::path::PathBuf::from);
    let root = reuse.clone().unwrap_or_else(|| std::env::temp_dir().join(
        format!("lp2ln-split-benchmark-{}", content_id_hex(&generate_key()))));
    if reuse.is_none() { fs::create_dir(&root).unwrap(); }
    assert!(root.canonicalize().unwrap().starts_with(std::env::temp_dir().canonicalize().unwrap()));
    assert!(root.file_name().unwrap().to_string_lossy().starts_with("lp2ln-split-benchmark-"));
    let (uploader, _) = test_node(&root.join("uploader"), true);
    connect(&uploader, &nodes);
    let source_path = root.join("generated-benchmark.bin");
    fs::write(&source_path, &source).unwrap();
    let existing = uploader.file_index.read().unwrap();
    let baseline = if reuse.is_some() { existing.iter().find(|e| e["name"] == "baseline.bin").unwrap().clone() }
        else { import(&uploader, source_path.to_str().unwrap(), "baseline.bin").unwrap() };
    let split = if reuse.is_some() { existing.iter().find(|e| e["name"] == "split.bin").unwrap().clone() }
        else { import(&uploader, source_path.to_str().unwrap(), "split.bin").unwrap() };
    let entries = [baseline, split];
    let owned_blocks: Vec<String> = entries.iter().flat_map(|entry|
        std::iter::once(entry["id"].as_str().unwrap().to_owned()).chain(
            entry["blocks"].as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_owned())))
        .collect();
    let mut result = json!({"fileBytes":source.len(),"chunkBytes":CHUNK_SIZE,"chunks":count,
        "runsPerScenario":3,"downloadConcurrency":DOWNLOAD_CONCURRENCY,"nodes":nodes,"ownedBlocks":owned_blocks,"samples":[],
        "client":"desktop Rust debug build, production Android download function",
        "timing":"authenticated connections already open; empty client block store; manifest, transfer, decryption and durable storage included; export/check excluded",
        "placement":"baseline all chunks on node 1; split even chunks on node 1, odd chunks on node 2; manifests on node 1"});
    fs::write(&report, serde_json::to_vec_pretty(&result).unwrap()).unwrap();

    uploader.runtime.block_on(async {
        let service = uploader.node.block_transfer_service().unwrap();
        for (scenario, entry) in entries.iter().enumerate().filter(|_| reuse.is_none()) {
            for (index, block) in entry["blocks"].as_array().unwrap().iter().enumerate() {
                let owner = if scenario == 0 { 0 } else { index % 2 };
                let id = content_id_from_hex(block.as_str().unwrap()).unwrap();
                let sealed = service.store.get(&id).unwrap().unwrap();
                service.push_to(nodes[owner]["peerId"].as_str().unwrap(), id, sealed).await.unwrap();
            }
            let id = content_id_from_hex(entry["id"].as_str().unwrap()).unwrap();
            service.push_to(nodes[0]["peerId"].as_str().unwrap(), id,
                service.store.get(&id).unwrap().unwrap()).await.unwrap();
        }
        // Prove the split is genuine: every chunk is absent from the opposite
        // node. An explicit not-found reply, rather than a timeout, is required.
        for (index, block) in entries[1]["blocks"].as_array().unwrap().iter().enumerate() {
            let id = content_id_from_hex(block.as_str().unwrap()).unwrap();
            let wrong_peer = nodes[1 - index % 2]["peerId"].as_str().unwrap();
            let error = service.fetch_from(wrong_peer, id).await.unwrap_err();
            assert!(error.to_string().contains("does not have block"), "{error:#}");
        }
    });
    result["disjointPlacementVerified"] = json!(true);
    fs::write(&report, serde_json::to_vec_pretty(&result).unwrap()).unwrap();
    stop_test_node(&uploader);
    drop(uploader); // No source/uploader can serve the timed download.

    let mut samples = vec![];
    let run_id = content_id_hex(&generate_key());
    for repetition in 0..3 {
        // Alternate order to reduce systematic server/cache/order bias.
        for scenario in if repetition % 2 == 0 { [0, 1] } else { [1, 0] } {
            let receiver_directory = root.join(format!("receiver-{run_id}-{repetition}-{scenario}"));
            let (receiver, _) = test_node(&receiver_directory, true);
            connect(&receiver, &nodes);
            let entry = &entries[scenario];
            let providers: Vec<String> = nodes.iter().take(if scenario == 0 { 1 } else { 2 })
                .map(|v| v["peerId"].as_str().unwrap().to_owned()).collect();
            receiver.file_index.save(offered(entry, &providers), true).unwrap();
            let service = receiver.node.block_transfer_service().unwrap();
            for block in entry["blocks"].as_array().unwrap() {
                assert!(service.store.get(&content_id_from_hex(block.as_str().unwrap()).unwrap()).unwrap().is_none());
            }
            assert!(service.store.get(&content_id_from_hex(entry["id"].as_str().unwrap()).unwrap()).unwrap().is_none());
            let started = Instant::now();
            download(&receiver, entry["id"].as_str().unwrap()).unwrap();
            let seconds = started.elapsed().as_secs_f64();
            let restored = root.join("restored.bin");
            export_from(&receiver, entry["id"].as_str().unwrap(), restored.to_str().unwrap()).unwrap();
            assert_eq!(content_id_hex(&hash_bytes(&fs::read(&restored).unwrap())), expected_hash);
            samples.push(json!({"scenario":if scenario == 0 { "single_node" } else { "split_two_nodes" },
                "run":repetition+1,"seconds":seconds,"MiB_per_second":source.len() as f64 / 1048576.0 / seconds,
                "Mbps":source.len() as f64 * 8.0 / 1000000.0 / seconds,"integrityVerified":true}));
            println!("BENCH_SAMPLE {}", samples.last().unwrap());
            result["samples"] = json!(samples);
            fs::write(&report, serde_json::to_vec_pretty(&result).unwrap()).unwrap();
            stop_test_node(&receiver);
            drop(service);
            drop(receiver);
            assert!(receiver_directory.canonicalize().unwrap().starts_with(root.canonicalize().unwrap()));
            fs::remove_dir_all(receiver_directory).unwrap();
        }
    }
    result["complete"] = json!(true);
    fs::write(&report, serde_json::to_vec_pretty(&result).unwrap()).unwrap();
    assert!(root.canonicalize().unwrap().starts_with(Path::new(&std::env::temp_dir()).canonicalize().unwrap()));
    fs::remove_dir_all(root).unwrap();
}
