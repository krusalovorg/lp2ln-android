use std::fs;
use std::io::Read;
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use lp2ln_core_v2::storage::{
    content_id_from_hex, content_id_hex, encrypt::{decrypt_chunk, encrypt_chunk, generate_key},
    manifest::{FileManifest, CODEC_RAW}, ContentId,
};
use serde_json::{json, Value};
use crate::{node_slot, NativeNode};

const MAX_FILE_SIZE: u64 = 100 * 1024 * 1024;
// Small blocks also work through peers with conservative packet limits.
const CHUNK_SIZE: usize = 256 * 1024;
static TRANSFER: OnceLock<Mutex<Value>> = OnceLock::new();
fn transfer_slot() -> &'static Mutex<Value> {
    TRANSFER.get_or_init(|| Mutex::new(json!({"busy": false})))
}
fn progress(value: Value) {
    if let Ok(mut state) = transfer_slot().lock() { *state = value; }
}
pub fn state(native: &NativeNode) -> Value {
    json!({
        "files": read_index(native).unwrap_or_default(),
        "transfer": transfer_slot().lock().map(|v| v.clone()).unwrap_or_default()
    })
}
fn index_path(native: &NativeNode) -> std::path::PathBuf {
    Path::new(&native.data_directory).join("android-files.json")
}
fn read_index(native: &NativeNode) -> Result<Vec<Value>> {
    let path = index_path(native);
    if !path.exists() { return Ok(vec![]); }
    Ok(serde_json::from_slice(&fs::read(path)?)?)
}
fn save_entry(native: &NativeNode, entry: Value) -> Result<()> {
    let mut entries = read_index(native)?;
    entries.retain(|v| v["id"] != entry["id"]);
    entries.insert(0, entry);
    let path = index_path(native);
    let temp = path.with_extension("tmp");
    fs::write(&temp, serde_json::to_vec(&entries)?)?;
    fs::rename(temp, path)?;
    Ok(())
}
fn get_native() -> Result<Arc<NativeNode>> {
    node_slot().lock().map_err(|_| anyhow::anyhow!("Node lock unavailable"))?
        .as_ref().cloned().context("Сначала подключитесь к сети")
}
pub fn begin(path: String, name: String, retry_id: String) -> Result<Value> {
    let native = get_native()?;
    {
        let mut state = transfer_slot().lock().map_err(|_| anyhow::anyhow!("Transfer lock unavailable"))?;
        anyhow::ensure!(!state["busy"].as_bool().unwrap_or(false), "Передача уже выполняется");
        *state = json!({"busy":true, "name":name, "stage":"Подготовка файла", "done":0, "total":0});
    }
    std::thread::spawn(move || {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let entry = if retry_id.is_empty() { import(&native, &path, &name)? } else {
                read_index(&native)?.into_iter().find(|v| v["id"] == retry_id)
                    .context("Файл не найден")?
            };
            publish(&native, entry)
        }));
        // Only delete our own SAF staging files, never an arbitrary caller path.
        let staging = Path::new(&native.data_directory).join("imports");
        if Path::new(&path).parent() == Some(staging.as_path()) { let _ = fs::remove_file(&path); }
        match result {
            Ok(Ok(entry)) => progress(json!({"busy":false, "name":entry["name"], "stage":"Файл сохранён на другом узле", "done":1, "total":1})),
            Ok(Err(error)) => progress(json!({"busy":false, "name":name, "error":format!("{error:#}")})),
            Err(_) => progress(json!({"busy":false, "name":name, "error":"Ошибка обработки файла"})),
        }
    });
    Ok(json!({"ok":true}))
}
fn import(native: &NativeNode, path: &str, name: &str) -> Result<Value> {
    let service = native.node.block_transfer_service().context("Хранилище недоступно")?;
    let mut file = fs::File::open(path)?;
    let size = file.metadata()?.len();
    anyhow::ensure!(size <= MAX_FILE_SIZE, "Максимальный размер файла — 100 МБ");
    let key = generate_key();
    let mut ids = vec![];
    let mut buffer = vec![0; CHUNK_SIZE];
    let mut done = 0u64;
    loop {
        let mut count = 0;
        while count < buffer.len() {
            let n = file.read(&mut buffer[count..])?;
            if n == 0 { break; }
            count += n;
        }
        if count == 0 { break; }
        done += count as u64;
        anyhow::ensure!(done <= MAX_FILE_SIZE, "Максимальный размер файла — 100 МБ");
        let encrypted = encrypt_chunk(&key, ids.len() as u32, &buffer[..count])?;
        ids.push(service.store.put(&encrypted)?);
        progress(json!({"busy":true, "name":name, "stage":"Шифрование", "done":done, "total":size}));
    }
    let now = SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs();
    let manifest = FileManifest {
        original_name:name.to_owned(), file_size:done, chunk_ids:ids.clone(), file_key:key,
        created_at:now, modified_at:now, codec:CODEC_RAW,
    };
    // The key-bearing manifest is encrypted too; only the private local index holds its key.
    let manifest_key = generate_key();
    let sealed = encrypt_chunk(&manifest_key, 0, &postcard::to_allocvec(&manifest)?)?;
    let id = service.store.put(&sealed)?;
    let entry = json!({
        "id":content_id_hex(&id), "name":name, "size":done, "createdAt":now,
        "key":content_id_hex(&manifest_key), "blocks":ids.iter().map(content_id_hex).collect::<Vec<_>>(),
        "status":"local", "replicas":0
    });
    save_entry(native, entry.clone())?;
    Ok(entry)
}
fn publish(native: &NativeNode, mut entry: Value) -> Result<Value> {
    let result = native.runtime.block_on(async {
        let service = native.node.block_transfer_service().context("Хранилище недоступно")?;
        let peers = native.node.connected_peers();
        anyhow::ensure!(!peers.is_empty(), "Нет подключённых узлов. Файл сохранён на телефоне — повторите отправку после подключения.");
        let mut ids: Vec<ContentId> = entry["blocks"].as_array().context("Invalid file index")?
            .iter().map(|v| content_id_from_hex(v.as_str().unwrap_or_default()).context("Invalid block ID"))
            .collect::<std::result::Result<_, _>>()?;
        ids.push(content_id_from_hex(entry["id"].as_str().context("Missing ID")?).context("Invalid manifest ID")?);
        let mut last_error = String::new();
        // A replica means one peer acknowledged EVERY block, including the encrypted manifest.
        for peer in peers.iter().take(3) {
            let mut complete = true;
            for (index, id) in ids.iter().enumerate() {
                progress(json!({"busy":true,"name":entry["name"],"stage":"Передача в сеть","done":index,"total":ids.len()}));
                let data = service.store.get(id)?.context("Локальный блок не найден")?;
                if let Err(error) = service.push_to(peer.as_str(), *id, data).await {
                    last_error = error.to_string();
                    complete = false;
                    break;
                }
            }
            if complete {
                if let Some(dht) = native.node.dht_service() {
                    for id in &ids { let _ = dht.announce(*id).await; }
                }
                return Ok(peer.to_string());
            }
        }
        anyhow::bail!("Узлы не подтвердили сохранение. Локальная копия доступна. {last_error}")
    });
    match result {
        Ok(peer) => {
            entry["status"] = json!("uploaded");
            entry["replicas"] = json!(1);
            entry["peer"] = json!(peer);
            entry["error"] = Value::Null;
            save_entry(native, entry.clone())?;
            Ok(entry)
        }
        Err(error) => {
            entry["status"] = json!("local");
            entry["error"] = json!(error.to_string());
            save_entry(native, entry)?;
            Err(error)
        }
    }
}
pub fn export(id: String, destination: String) -> Result<Value> {
    let native = get_native()?;
    let entry = read_index(&native)?.into_iter().find(|v| v["id"] == id).context("Файл не найден")?;
    let service = native.node.block_transfer_service().context("Хранилище недоступно")?;
    let manifest_id = content_id_from_hex(&id).context("Invalid manifest ID")?;
    let key = content_id_from_hex(entry["key"].as_str().context("Missing key")?).context("Invalid file key")?;
    let bytes = service.store.get(&manifest_id)?.context("Манифест не найден")?;
    let manifest = FileManifest::decode(&decrypt_chunk(&key, 0, &bytes)?)?;
    let mut output = fs::File::create(destination)?;
    use std::io::Write;
    let mut written = 0u64;
    for (index, id) in manifest.chunk_ids.iter().enumerate() {
        let encrypted = service.store.get(id)?.context("Блок не найден")?;
        let plain = decrypt_chunk(&manifest.file_key, index as u32, &encrypted)?;
        output.write_all(&plain)?;
        written += plain.len() as u64;
    }
    anyhow::ensure!(written == manifest.file_size, "Размер восстановленного файла не совпадает");
    output.sync_all()?;
    Ok(json!({"ok":true}))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn encrypted_manifest_and_multiple_chunks_round_trip() {
        let store = lp2ln_core_v2::storage::MemoryBlockStore::new();
        use lp2ln_core_v2::storage::BlockStoreTrait;
        let source = vec![42u8; CHUNK_SIZE + 17];
        let key = generate_key();
        let ids = source.chunks(CHUNK_SIZE).enumerate().map(|(i, b)| {
            store.put(&encrypt_chunk(&key, i as u32, b).unwrap()).unwrap()
        }).collect::<Vec<_>>();
        let manifest = FileManifest { original_name:"test.bin".into(),file_size:source.len() as u64,
            chunk_ids:ids,file_key:key,created_at:0,modified_at:0,codec:CODEC_RAW };
        let capability = generate_key();
        let sealed = encrypt_chunk(&capability,0,&postcard::to_allocvec(&manifest).unwrap()).unwrap();
        assert!(decrypt_chunk(&generate_key(),0,&sealed).is_err());
        let restored = FileManifest::decode(&decrypt_chunk(&capability,0,&sealed).unwrap()).unwrap();
        let mut bytes = vec![];
        for (i,id) in restored.chunk_ids.iter().enumerate() {
            bytes.extend(decrypt_chunk(&restored.file_key,i as u32,&store.get(id).unwrap().unwrap()).unwrap());
        }
        assert_eq!(bytes, source);
    }

    #[test]
    fn file_is_only_uploaded_after_remote_ack_and_can_be_restored() {
        use lp2ln_core_v2::{db::P2PDatabase, node::{NodeBuilder, NodeOptions}, transport::tcp::TcpTransport};
        use std::net::TcpListener;
        let root = std::env::temp_dir().join(format!("lp2ln-android-test-{}", content_id_hex(&generate_key())));
        fs::create_dir_all(&root).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        drop(listener);
        let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
        let make_node = |dir: &Path, listen| {
            let mut options = NodeOptions::empty().with_listen("tcp", listen);
            options.logger_options = None;
            options.lan_discovery.enabled = false;
            options.debug_server.enabled = false;
            options.ipc_tcp.enabled = false;
            options.experimental.content = true;
            options.experimental.dht = true;
            NodeBuilder::new().db(Arc::new(P2PDatabase::new(dir.to_str().unwrap()).unwrap()))
                .add_transport(Arc::new(TcpTransport::new())).build(options).unwrap()
        };
        let mut receiver = make_node(&root.join("receiver"), addr);
        let mut sender = make_node(&root.join("sender"), "127.0.0.1:0".parse().unwrap());
        runtime.block_on(async { receiver.start().await.unwrap(); sender.start().await.unwrap(); });
        let native = NativeNode { runtime, node: sender, started_at: std::time::Instant::now(),
            data_directory: root.join("sender").to_string_lossy().into_owned() };
        let original = (0..CHUNK_SIZE + 73).map(|i| (i % 251) as u8).collect::<Vec<_>>();
        let path = root.join("source.bin");
        fs::write(&path, &original).unwrap();
        let entry = import(&native, path.to_str().unwrap(), "source.bin").unwrap();
        assert!(publish(&native, entry.clone()).is_err());
        assert_eq!(read_index(&native).unwrap()[0]["status"], "local");
        native.runtime.block_on(async {
            native.node.connect("tcp", addr).await.unwrap();
            for _ in 0..100 {
                if native.node.active_peer_count() > 0 && receiver.active_peer_count() > 0 { break; }
                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            }
        });
        let uploaded = publish(&native, entry).unwrap();
        assert_eq!(uploaded["status"], "uploaded");
        let remote = receiver.block_transfer_service().unwrap();
        let id = content_id_from_hex(uploaded["id"].as_str().unwrap()).unwrap();
        let key = content_id_from_hex(uploaded["key"].as_str().unwrap()).unwrap();
        let manifest = FileManifest::decode(&decrypt_chunk(&key, 0, &remote.store.get(&id).unwrap().unwrap()).unwrap()).unwrap();
        let mut restored = vec![];
        for (i, id) in manifest.chunk_ids.iter().enumerate() {
            restored.extend(decrypt_chunk(&manifest.file_key, i as u32, &remote.store.get(id).unwrap().unwrap()).unwrap());
        }
        assert_eq!(restored, original);
        native.runtime.block_on(async { native.node.stop().await.unwrap(); receiver.stop().await.unwrap(); });
        drop(receiver);
        drop(native);
        fs::remove_dir_all(root).unwrap();
    }

    // Opt-in live deployment check. The endpoint is supplied locally, never committed.
    #[test]
    #[ignore = "requires LP2LN_TEST_BOOTSTRAP and uploads a generated test file"]
    fn remote_node_upload_and_restore() {
        let bootstrap = std::env::var("LP2LN_TEST_BOOTSTRAP").expect("Set LP2LN_TEST_BOOTSTRAP");
        let root = std::env::temp_dir().join(format!("lp2ln-remote-test-{}", content_id_hex(&generate_key())));
        fs::create_dir_all(&root).unwrap();
        crate::start_node(root.join("node").to_string_lossy().into_owned(), bootstrap).unwrap();
        let native = get_native().unwrap();
        native.runtime.block_on(async {
            for _ in 0..120 {
                if native.node.active_peer_count() > 0 { return; }
                tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            }
            panic!("Remote bootstrap did not establish a peer session");
        });
        let source = (0..CHUNK_SIZE + 73).map(|i| (i % 251) as u8).collect::<Vec<_>>();
        let path = root.join("deployment-check.bin");
        fs::write(&path, &source).unwrap();
        let entry = import(&native, path.to_str().unwrap(), "deployment-check.bin").unwrap();
        let uploaded = publish(&native, entry).unwrap();
        let id = content_id_from_hex(uploaded["id"].as_str().unwrap()).unwrap();
        let key = content_id_from_hex(uploaded["key"].as_str().unwrap()).unwrap();
        let peer = uploaded["peer"].as_str().unwrap();
        let service = native.node.block_transfer_service().unwrap();
        let restored = native.runtime.block_on(async {
            // Explicitly fetch from the remote peer, bypassing the local block store.
            let encrypted = service.fetch_from(peer, id).await.unwrap();
            let manifest = FileManifest::decode(&decrypt_chunk(&key, 0, &encrypted).unwrap()).unwrap();
            let mut restored = vec![];
            for (index, id) in manifest.chunk_ids.iter().enumerate() {
                let block = service.fetch_from(peer, *id).await.unwrap();
                restored.extend(decrypt_chunk(&manifest.file_key, index as u32, &block).unwrap());
            }
            restored
        });
        assert_eq!(restored, source);
        let output = root.join("restored.bin");
        export(uploaded["id"].as_str().unwrap().to_owned(), output.to_string_lossy().into_owned()).unwrap();
        assert_eq!(fs::read(output).unwrap(), source);
        println!("Remote upload, remote fetch and local export verified: {} bytes", source.len());
        crate::stop_node().unwrap();
        drop(service);
        drop(native);
        fs::remove_dir_all(root).unwrap();
    }

}
