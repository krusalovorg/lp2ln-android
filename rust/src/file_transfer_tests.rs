use super::file_transfer_benchmark::connect;
use super::tests::{stop_test_node, test_node_with_storage};
use super::*;
use lp2ln_core_v2::storage::hash_bytes;
use std::time::Instant;

fn content_id(value: &Value) -> ContentId {
    content_id_from_hex(value.as_str().unwrap()).unwrap()
}

#[test]
fn retry_after_partial_replica_preserves_ack_progress_and_preview() {
    let root =
        std::env::temp_dir().join(format!("lp2ln-retry-{}", content_id_hex(&generate_key())));
    fs::create_dir(&root).unwrap();
    let (sender, _) = test_node_with_storage(&root.join("sender"), true, true, true, 0);
    let replica_dir = root.join("replica");
    let (replica, address) = test_node_with_storage(&replica_dir, true, true, true, 1);
    connect(&sender, &replica, address);
    let source = [42u8; 5 * 1024].repeat(256);
    let path = root.join("retry.bin");
    let preview = [0xff, 0xd8, 1, 2, 0xff, 0xd9];
    fs::write(&path, &source).unwrap();
    fs::write(path.with_extension("bin.preview"), preview).unwrap();
    let entry = import(&sender, path.to_str().unwrap(), "retry.bin").unwrap();
    let block_ids: Vec<_> = entry["blocks"]
        .as_array()
        .unwrap()
        .iter()
        .chain(entry["imageBlocks"].as_array().unwrap())
        .chain(std::iter::once(&entry["id"]))
        .map(content_id)
        .collect();
    let before_failure = Instant::now();
    assert!(publish_measured(&sender, entry.clone(), || panic!("incomplete replica")).is_err());
    let failed_seconds = before_failure.elapsed().as_secs_f64();
    let local = read_index(&sender).unwrap().remove(0);
    assert_eq!(local["id"], entry["id"]);
    assert_eq!(local["status"], "local");
    assert_eq!(local["replicas"], 0);
    let service = replica.node.block_transfer_service().unwrap();
    let saved = block_ids
        .iter()
        .filter(|id| service.store.get(id).unwrap().is_some())
        .count();
    assert!((1..block_ids.len()).contains(&saved));
    assert!(service
        .store
        .get(block_ids.last().unwrap())
        .unwrap()
        .is_none());
    drop(service);
    stop_test_node(&replica);
    drop(replica);

    // Reuse the storage directory so the retry encounters the same blocks.
    let (replica, address) = test_node_with_storage(&replica_dir, true, true, true, 0);
    let before_retry = Instant::now();
    connect(&sender, &replica, address);
    let service = sender.node.block_transfer_service().unwrap();
    let uploaded = publish(&sender, local).unwrap();
    let retry_seconds = before_retry.elapsed().as_secs_f64();
    assert_eq!(uploaded["status"], "uploaded");
    assert_eq!(uploaded["peer"], replica.node.peer_id());
    assert_eq!(uploaded["replicas"], 1);
    assert_eq!(uploaded["id"], entry["id"]);
    let mut progress = Vec::new();
    sender
        .runtime
        .block_on(push_replica(
            &service,
            replica.node.peer_id(),
            &block_ids,
            UPLOAD_CONCURRENCY,
            |done| progress.push(done),
        ))
        .unwrap();
    assert_eq!(progress, (0..=block_ids.len()).collect::<Vec<_>>());

    let (receiver, _) = test_node_with_storage(&root.join("receiver"), true, true, true, 0);
    connect(&receiver, &replica, address);
    let id = uploaded["id"].as_str().unwrap();
    let mut invitation = share_capability(&sender, id).unwrap();
    assert_eq!(invitation["replicaPeer"], replica.node.peer_id());
    invitation["status"] = json!("offered");
    receiver.file_index.save(invitation, true).unwrap();
    stop_test_node(&sender);
    download(&receiver, id).unwrap();
    let restored = root.join("restored.bin");
    export_from(&receiver, id, restored.to_str().unwrap()).unwrap();
    assert_eq!(
        hash_bytes(&fs::read(restored).unwrap()),
        hash_bytes(&source)
    );
    let image = root.join("restored.jpg");
    crate::image_preview::fetch(&receiver, id, image.to_str().unwrap()).unwrap();
    assert_eq!(fs::read(image).unwrap(), preview);
    println!("TRANSFER_RETRY {{\"partial_blocks\":{saved},\"total_blocks\":{},\"failed_s\":{failed_seconds},\"retry_s\":{retry_seconds},\"sha256_ok\":true}}", block_ids.len());
    stop_test_node(&receiver);
    stop_test_node(&replica);
    drop((service, sender, receiver, replica));
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn disjoint_placement_requires_both_storage_nodes() {
    let root =
        std::env::temp_dir().join(format!("lp2ln-split-{}", content_id_hex(&generate_key())));
    fs::create_dir(&root).unwrap();
    let (sender, _) = test_node_with_storage(&root.join("sender"), true, true, false, 0);
    let (left, left_addr) = test_node_with_storage(&root.join("left"), true, true, false, 0);
    let (right, right_addr) = test_node_with_storage(&root.join("right"), true, true, false, 0);
    let source = vec![19; CHUNK_SIZE + 71];
    let path = root.join("split.bin");
    fs::write(&path, &source).unwrap();
    let entry = import(&sender, path.to_str().unwrap(), "split.bin").unwrap();
    let manifest = &entry["id"];
    let blocks = entry["blocks"].as_array().unwrap();
    let service = sender.node.block_transfer_service().unwrap();
    for (node, ids) in [
        (&left, &[manifest, &blocks[0]][..]),
        (&right, &[&blocks[1]][..]),
    ] {
        let store = node.node.block_transfer_service().unwrap();
        for id in ids {
            store
                .store
                .put(&service.store.get(&content_id(id)).unwrap().unwrap())
                .unwrap();
        }
    }
    stop_test_node(&sender);
    let providers = [
        left.node.peer_id().to_owned(),
        right.node.peer_id().to_owned(),
    ];
    let offered = json!({"id":manifest,"key":entry["key"],"name":entry["name"],"size":entry["size"],
        "status":"offered","providers":providers});
    let (receiver, _) = test_node_with_storage(&root.join("both"), true, true, false, 0);
    connect(&receiver, &left, left_addr);
    connect(&receiver, &right, right_addr);
    receiver.file_index.save(offered.clone(), true).unwrap();
    download(&receiver, manifest.as_str().unwrap()).unwrap();
    let output = root.join("restored.bin");
    export_from(
        &receiver,
        manifest.as_str().unwrap(),
        output.to_str().unwrap(),
    )
    .unwrap();
    assert_eq!(hash_bytes(&fs::read(output).unwrap()), hash_bytes(&source));
    stop_test_node(&receiver);
    drop(receiver);
    let assert_incomplete = |survivor: &NativeNode, address, label, offered: Value| {
        let (receiver, _) = test_node_with_storage(&root.join(label), true, true, false, 0);
        connect(&receiver, survivor, address);
        receiver.file_index.save(offered, true).unwrap();
        assert!(download(&receiver, manifest.as_str().unwrap()).is_err());
        stop_test_node(&receiver);
    };
    stop_test_node(&right);
    drop(right);
    assert_incomplete(&left, left_addr, "left-only", offered.clone());
    stop_test_node(&left);

    let (right, right_addr) = test_node_with_storage(&root.join("right"), true, true, false, 0);
    let mut offered = offered;
    offered["providers"][1] = json!(right.node.peer_id());
    assert_incomplete(&right, right_addr, "right-only", offered);
    println!("TRANSFER_PLACEMENT {{\"both_sha256_ok\":true,\"left_only_recovered\":false,\"right_only_recovered\":false}}");
    stop_test_node(&right);
    drop((service, sender, left, right));
    fs::remove_dir_all(root).unwrap();
}
