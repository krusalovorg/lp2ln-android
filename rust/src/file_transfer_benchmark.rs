use super::tests::{stop_test_node, test_node_with_storage};
use super::*;
use lp2ln_core_v2::storage::{
    block_transfer::BLOCK_PROTOCOL_ID, hash_bytes, BlockTransferService, FsBlockStore,
};
use std::time::{Duration, Instant};

fn node(dir: &Path, content: bool, quota: usize) -> (NativeNode, std::net::SocketAddr) {
    test_node_with_storage(dir, true, content, true, quota)
}

pub(super) fn connect(client: &NativeNode, peer: &NativeNode, address: std::net::SocketAddr) {
    client.runtime.block_on(async {
        client.node.connect("tcp", address).await.unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            while !client
                .node
                .connected_peers()
                .iter()
                .any(|p| p.as_str() == peer.node.peer_id())
                || !peer
                    .node
                    .connected_peers()
                    .iter()
                    .any(|p| p.as_str() == client.node.peer_id())
            {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
    });
}

// Use the real block handler and disk store, but delay incoming requests
// before processing. Each request has its own timer.
fn delayed_storage(native: &NativeNode, delay: Duration) -> Arc<BlockTransferService> {
    let router = native.node.router().unwrap();
    let service = BlockTransferService::new(
        FsBlockStore::new(&Path::new(&native.data_directory).join("bench-blocks")).unwrap(),
        router.clone(),
        native.node.peer_id(),
        0,
    );
    let mut incoming = router.subscribe();
    let worker = service.clone();
    native.runtime.spawn(async move {
        while let Ok(packet) = incoming.recv().await {
            if packet.packet.protocol_id != Some(BLOCK_PROTOCOL_ID) {
                continue;
            }
            let worker = worker.clone();
            tokio::spawn(async move {
                tokio::time::sleep(delay).await;
                worker.handle_raw(&packet.packet.sender, &packet.packet.data);
            });
        }
    });
    service
}

fn id(value: &Value) -> ContentId {
    content_id_from_hex(value.as_str().unwrap()).unwrap()
}

fn measure(
    case: &str,
    mib: usize,
    run: usize,
    delay_ms: u64,
    root: &Path,
) -> Result<(f64, f64, f64)> {
    fs::create_dir(root)?;
    let (sender, sender_addr) = node(&root.join("sender"), true, 0);
    let (replica, replica_addr) = node(&root.join("replica"), delay_ms == 0, 0);
    let replica_store = if delay_ms == 0 {
        replica.node.block_transfer_service().unwrap()
    } else {
        delayed_storage(&replica, Duration::from_millis(delay_ms))
    };
    connect(&sender, &replica, replica_addr);
    assert!(sender.node.dht_service().is_some());
    let source = (0..mib * 1024 * 1024)
        .map(|i| ((i * 17 + (i >> 7) + run) % 251) as u8)
        .collect::<Vec<_>>();
    let expected = hash_bytes(&source); // SHA-256, same primitive as block content IDs.
    let path = root.join("generated.bin");
    fs::write(&path, source)?;
    let entry = import(&sender, path.to_str().unwrap(), "generated.bin")?;
    let start = Instant::now();
    let mut ack = None;
    let uploaded = publish_measured(&sender, entry, || ack = Some(start.elapsed().as_secs_f64()))?;
    let uploaded_seconds = start.elapsed().as_secs_f64();
    assert_eq!(uploaded["status"], "uploaded");
    assert_eq!(uploaded["replicas"], 1);
    assert_eq!(uploaded["peer"], replica.node.peer_id());
    assert!(replica_store.store.get(&id(&uploaded["id"]))?.is_some());

    let (receiver, _) = node(&root.join("receiver"), true, 0);
    connect(&receiver, &replica, replica_addr);
    let mut fault = None;
    let mut providers = vec![replica.node.peer_id().to_owned()];
    match case {
        "normal" => providers.push(sender.node.peer_id().to_owned()),
        "offline" => {
            connect(&receiver, &sender, sender_addr);
            providers.insert(0, sender.node.peer_id().to_owned());
            stop_test_node(&sender);
        }
        "silent" | "slow" => {
            let (peer, addr) = node(&root.join("fault"), false, 0);
            if case == "slow" {
                let slow = delayed_storage(&peer, Duration::from_millis(1200));
                for value in
                    std::iter::once(&uploaded["id"]).chain(uploaded["blocks"].as_array().unwrap())
                {
                    slow.store
                        .put(&replica_store.store.get(&id(value))?.unwrap())?;
                }
            }
            connect(&receiver, &peer, addr);
            providers.insert(0, peer.node.peer_id().to_owned());
            fault = Some(peer);
        }
        _ => anyhow::bail!("Unknown benchmark case"),
    }
    let replica_hint = if case == "silent" || case == "slow" {
        providers[0].as_str()
    } else {
        replica.node.peer_id()
    };
    let offered = json!({"id":uploaded["id"], "key":uploaded["key"], "name":uploaded["name"],
        "size":uploaded["size"], "status":"offered", "providers":providers, "replicaPeer":replica_hint});
    receiver.file_index.save(offered, true)?;
    let receiver_store = receiver.node.block_transfer_service().unwrap();
    for value in std::iter::once(&uploaded["id"]).chain(uploaded["blocks"].as_array().unwrap()) {
        assert!(receiver_store.store.get(&id(value))?.is_none());
    }
    let manifest = uploaded["id"].as_str().unwrap();
    let start = Instant::now();
    download(&receiver, manifest)?;
    let download_seconds = start.elapsed().as_secs_f64();
    let restored = root.join("restored.bin");
    export_from(&receiver, manifest, restored.to_str().unwrap())?;
    assert_eq!(hash_bytes(&fs::read(restored)?), expected);
    stop_test_node(&receiver);
    if let Some(fault) = fault {
        stop_test_node(&fault);
    }
    if case != "offline" {
        stop_test_node(&sender);
    }
    stop_test_node(&replica);
    Ok((ack.unwrap(), uploaded_seconds, download_seconds))
}

#[test]
#[ignore = "runs the localhost file transfer benchmark"]
fn measure_full_replica_transfer() {
    let delay = std::env::var("LP2LN_BENCH_DELAY_MS")
        .map(|v| v.parse().unwrap())
        .unwrap_or(25);
    let case = std::env::var("LP2LN_BENCH_CASE").unwrap_or_else(|_| "normal".into());
    let (sizes, runs) = if case == "normal" {
        let sizes = match std::env::var("LP2LN_BENCH_SIZE_MIB") {
            Ok(value) => vec![value.parse::<usize>().unwrap()],
            Err(_) => vec![1, 8, 32],
        };
        (sizes, 5)
    } else {
        (vec![1], 1)
    };
    let root = std::env::temp_dir().join(format!(
        "lp2ln-transfer-{}",
        content_id_hex(&generate_key())
    ));
    fs::create_dir(&root).unwrap();
    let mut errors = 0;
    for mib in sizes {
        for run in 1..=runs {
            let trial = root.join(format!("{mib}-{run}"));
            let metrics = match measure(&case, mib, run, delay, &trial) {
                Ok((ack, uploaded, download)) => json!({
                    "ack_s":ack, "uploaded_s":uploaded, "post_ack_s":uploaded-ack,
                    "download_s":download, "sha256_ok":true,
                }),
                Err(_) => {
                    errors += 1;
                    json!({"error":true})
                }
            };
            println!("TRANSFER_SAMPLE {case} {delay} {mib} {run} {metrics}");
        }
    }
    fs::remove_dir_all(root).unwrap();
    assert_eq!(errors, 0);
}
