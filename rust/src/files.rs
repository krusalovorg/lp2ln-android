use std::fs;
use std::io::Read;
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use lp2ln_core_v2::storage::{
    content_id_from_hex, content_id_hex, encrypt::{decrypt_chunk, encrypt_chunk, generate_key},
    manifest::{FileManifest, CODEC_RAW}, BlockTransferService, ContentId,
};
use serde_json::{json, Value};
use crate::{node_slot, NativeNode};

const MAX_FILE_SIZE: u64 = 100 * 1024 * 1024;
// Small blocks also work through peers with conservative packet limits.
const CHUNK_SIZE: usize = 256 * 1024;
const DOWNLOAD_CONCURRENCY: usize = 4;
const UPLOAD_CONCURRENCY: usize = 4;

#[path = "file_sources.rs"]
pub(crate) mod file_sources;
use file_sources::{replica_peer, FileSources};
static TRANSFER: OnceLock<Mutex<Value>> = OnceLock::new();
fn transfer_slot() -> &'static Mutex<Value> {
    TRANSFER.get_or_init(|| Mutex::new(json!({"busy": false})))
}
pub(crate) fn progress(value: Value) {
    if let Ok(mut state) = transfer_slot().lock() { *state = value; }
}
pub fn state(native: &NativeNode) -> Value {
    json!({
        "files": read_index(native).unwrap_or_default(),
        "transfer": transfer_slot().lock().map(|v| v.clone()).unwrap_or_default()
    })
}
pub(crate) struct FileIndex {
    directory: std::path::PathBuf,
    lock: Mutex<()>,
}

impl FileIndex {
    pub(crate) fn new(directory: impl Into<std::path::PathBuf>) -> Self {
        Self { directory: directory.into(), lock: Mutex::new(()) }
    }

    pub(crate) fn read(&self) -> Result<Vec<Value>> {
        let path = self.directory.join("android-files.json");
        if !path.exists() { return Ok(vec![]); }
        Ok(serde_json::from_slice(&fs::read(path)?)?)
    }

    pub(crate) fn save(&self, entry: Value, incoming: bool) -> Result<()> {
        use std::io::Write;
        let _guard = self.lock.lock().map_err(|_| anyhow::anyhow!("File index lock unavailable"))?;
        let mut entries = self.read()?;
        if incoming && entries.iter().any(|v| v["id"] == entry["id"]) { return Ok(()); }
        if incoming {
            anyhow::ensure!(entries.iter().filter(|v| v["status"] == "offered").count() < 128,
                "Слишком много ожидающих файлов");
        }
        entries.retain(|v| v["id"] != entry["id"]);
        entries.insert(0, entry);
        let path = self.directory.join("android-files.json");
        let temp = path.with_extension("tmp");
        let mut file = fs::File::create(&temp)?;
        file.write_all(&serde_json::to_vec(&entries)?)?;
        file.sync_all()?;
        drop(file);
        fs::rename(temp, path)?;
        #[cfg(unix)]
        fs::File::open(&self.directory)?.sync_all()?;
        Ok(())
    }
}
fn read_index(native: &NativeNode) -> Result<Vec<Value>> {
    native.file_index.read()
}
fn save_entry(native: &NativeNode, entry: Value) -> Result<()> {
    native.file_index.save(entry, false)
}
pub(crate) fn get_native() -> Result<Arc<NativeNode>> {
    node_slot().lock().map_err(|_| anyhow::anyhow!("Node lock unavailable"))?
        .as_ref().cloned().context("Сначала подключитесь к сети")
}

// Keep protocol selection automatic. UDP discovery may be healthy while no TCP
// data link exists; open one on demand using authenticated, cached addresses.
pub(crate) async fn ensure_file_path(native: &NativeNode, providers: &[String]) -> Result<()> {
    let reliable = |protocol: &str| matches!(protocol, "tcp" | "quic" | "tunnel_tcp" | "relay");
    let sessions = native.node.debug_sessions();
    if sessions.iter().any(|s| s.is_active && reliable(&s.protocol)
        && s.peer_id.as_ref().is_some_and(|peer| providers.contains(peer))) { return Ok(()); }
    let mut targets = {
        let mut book = native.known_peers.lock().map_err(|_| anyhow::anyhow!("Peer cache unavailable"))?;
        let _ = book.remember(native.node.peer_catalog().descriptors(), native.node.peer_id());
        book.targets().into_iter().filter(|t| t.protocols.iter().any(|p| p == "tcp")).collect::<Vec<_>>()
    };
    targets.sort_by_key(|t| t.peer_id_hint.as_ref()
        .and_then(|peer| providers.iter().position(|p| p == peer.as_str()))
        .unwrap_or(providers.len()));
    for target in targets.into_iter().take(4) {
        let Some(expected) = target.peer_id_hint else { continue; };
        if sessions.iter().any(|s| s.is_active && reliable(&s.protocol) && s.peer_id.as_deref() == Some(expected.as_str())) {
            return Ok(());
        }
        let Ok(Ok(session)) = tokio::time::timeout(std::time::Duration::from_secs(3),
            native.node.connect("tcp", target.addr)).await else { continue; };
        for _ in 0..15 {
            if let Some(connected) = native.node.debug_sessions().into_iter().find(|s| s.session_id == session.as_str()) {
                if let Some(peer) = connected.peer_id {
                    if peer == expected.as_str() && connected.is_active { return Ok(()); }
                    break;
                }
            }
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        }
        let _ = native.node.disconnect_session(session.as_str()).await;
    }
    anyhow::ensure!(native.node.debug_sessions().iter().any(|s| s.is_active && reliable(&s.protocol)),
        "Не удалось открыть канал для файла. Проверьте сеть и повторите попытку.");
    Ok(())
}

pub(crate) fn share_capability(native: &NativeNode, id: &str) -> Result<Value> {
    let entry = read_index(native)?.into_iter().find(|v| v["id"] == id).context("Файл не найден")?;
    anyhow::ensure!(entry["status"] != "offered", "Сначала скачайте файл");
    let mut providers = Vec::new();
    let replica = replica_peer(&entry);
    let local = native.node.peer_id();
    let known = entry["providers"].as_array().into_iter().flatten().filter_map(Value::as_str);
    for peer in replica.into_iter().chain(known).chain(entry["peer"].as_str()) {
        if providers.len() == 7 { break; }
        if peer != local && !providers.iter().any(|p| p == peer) {
            providers.push(peer.to_owned());
        }
    }
    providers.push(local.to_owned());
    Ok(json!({"id":entry["id"], "key":entry["key"], "name":entry["name"],
        "size":entry["size"], "providers":providers,"image":entry["image"],"replicaPeer":replica}))
}

pub(crate) fn download(native: &NativeNode, id: &str) -> Result<Value> {
    let mut entry = read_index(native)?.into_iter().find(|v| v["id"] == id).context("Файл не найден")?;
    let result = native.runtime.block_on(async {
        let service = native.node.block_transfer_service().context("Хранилище недоступно")?;
        let manifest_id = content_id_from_hex(id).context("Неверный ID файла")?;
        let key = content_id_from_hex(entry["key"].as_str().context("Нет ключа файла")?).context("Неверный ключ")?;
        entry["providers"].as_array().context("Нет источников файла")?;
        progress(json!({"busy":true,"fileId":id,"name":entry["name"],"stage":"Получение файла","done":0,"total":0}));
        let sources = Arc::new(FileSources::open(native, &entry).await?);
        let sealed = sources.fetch(&service, manifest_id, 0).await?;
        let manifest = FileManifest::decode(&decrypt_chunk(&key, 0, &sealed)?)?;
        anyhow::ensure!(manifest.file_size <= MAX_FILE_SIZE && manifest.chunk_ids.len() <= 400,
            "Файл превышает допустимый размер");
        anyhow::ensure!(entry["size"].as_u64() == Some(manifest.file_size), "Размер файла отличается от приглашения");
        service.store.put(&sealed)?;
        progress(json!({"busy":true,"fileId":id,"name":entry["name"],"stage":"Получение файла",
            "done":0,"total":manifest.chunk_ids.len()}));
        let mut size = 0u64;
        let mut completed = 0usize;
        let mut next = 0usize;
        let mut failure: Option<anyhow::Error> = None;
        let mut pending = tokio::task::JoinSet::new();
        // Keep a bounded window full. A slow chunk does not stop requests for
        // other chunks. Their original indices determine authentication and
        // the manifest determines export order, regardless of arrival order.
        loop {
            while failure.is_none() && next < manifest.chunk_ids.len() && pending.len() < DOWNLOAD_CONCURRENCY {
                let index = next;
                let block = manifest.chunk_ids[index];
                let key = manifest.file_key;
                let service = service.clone();
                let sources = sources.clone();
                pending.spawn(async move {
                    let encrypted = sources.fetch(&service, block, index).await?;
                    let plain = decrypt_chunk(&key, index as u32, &encrypted)?;
                    anyhow::ensure!(plain.len() <= CHUNK_SIZE, "Часть файла превышает допустимый размер");
                    Ok::<_, anyhow::Error>((encrypted, plain.len() as u64))
                });
                next += 1;
            }
            let Some(result) = pending.join_next().await else { break; };
            let result = result.map_err(anyhow::Error::from).and_then(|r| r).and_then(|(encrypted, length)| {
                size += length;
                anyhow::ensure!(size <= MAX_FILE_SIZE, "Файл превышает допустимый размер");
                service.store.put(&encrypted)?;
                completed += 1;
                progress(json!({"busy":true,"fileId":id,"name":entry["name"],"stage":"Получение файла",
                    "done":completed,"total":manifest.chunk_ids.len()}));
                Ok(())
            });
            if let Err(error) = result { if failure.is_none() { failure = Some(error); } }
            // On failure, stop creating requests but drain those already sent.
            // This lets the block service release its pending reply entries.
        }
        if let Some(error) = failure { return Err(error); }
        anyhow::ensure!(size == manifest.file_size, "Размер файла не совпадает");
        entry["name"] = json!(manifest.original_name.chars().take(255).collect::<String>());
        entry["size"] = json!(size);
        entry["blocks"] = json!(manifest.chunk_ids.iter().map(content_id_hex).collect::<Vec<_>>());
        entry["status"] = json!("received");
        entry["error"] = Value::Null;
        save_entry(native, entry.clone())?;
        Ok::<_, anyhow::Error>(json!({"ok":true}))
    });
    match &result {
        Ok(_) => progress(json!({"busy":false,"stage":"Файл получен"})),
        Err(error) => progress(json!({"busy":false,"fileId":id,"name":entry["name"],"error":format!("{error:#}")})),
    }
    result
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
        if Path::new(&path).parent() == Some(staging.as_path()) {
            let _ = fs::remove_file(&path);
            let _ = fs::remove_file(format!("{path}.preview"));
        }
        match result {
            Ok(Ok(entry)) => {
                let total = ["blocks", "imageBlocks"].iter().filter_map(|field| entry[*field].as_array())
                    .map(Vec::len).sum::<usize>() + 1;
                progress(json!({"busy":false, "name":entry["name"], "fileId":entry["id"],
                    "stage":"Файл сохранён на другом узле", "done":total, "total":total}));
            }
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
    let mut entry = json!({
        "id":content_id_hex(&id), "name":name, "size":done, "createdAt":now,
        "key":content_id_hex(&manifest_key), "blocks":ids.iter().map(content_id_hex).collect::<Vec<_>>(),
        "status":"local", "replicas":0
    });
    crate::image_preview::import(native, path, &mut entry)?;
    save_entry(native, entry.clone())?;
    Ok(entry)
}
fn publish(native: &NativeNode, entry: Value) -> Result<Value> {
    publish_measured(native, entry, || {})
}

async fn push_replica(
    service: &Arc<BlockTransferService>,
    peer: &str,
    ids: &[ContentId],
    window: usize,
    mut report: impl FnMut(usize),
) -> Result<()> {
    let mut remaining = ids.iter().copied();
    let mut pending = tokio::task::JoinSet::new();
    let mut failure = None;
    let mut acknowledged = 0;
    report(acknowledged);
    loop {
        while failure.is_none() && pending.len() < window {
            let Some(id) = remaining.next() else { break; };
            match service.store.get(&id).context("Не удалось прочитать локальный блок")
                .and_then(|data| data.context("Локальный блок не найден")) {
                Ok(data) => {
                    let service = service.clone();
                    let peer = peer.to_owned();
                    pending.spawn(async move { service.push_to(&peer, id, data).await });
                }
                Err(error) => failure = Some(error),
            }
        }

        let Some(result) = pending.join_next().await else { break; };
        match result.map_err(anyhow::Error::from).and_then(|result| result) {
            Ok(()) => {
                acknowledged += 1;
                report(acknowledged);
            }
            Err(error) => { failure.get_or_insert(error); }
        }
        // The block service removes pending replies when each send finishes.
        // Drain the window before trying the next peer.
    }
    failure.map_or(Ok(()), Err)
}

fn publish_measured(native: &NativeNode, mut entry: Value, all_acked: impl FnOnce()) -> Result<Value> {
    let window = UPLOAD_CONCURRENCY;
    #[cfg(test)]
    let window = std::env::var("LP2LN_BENCH_WINDOW").map_or(window, |value| {
        let value = value.parse().expect("Invalid upload window");
        assert!(matches!(value, 1 | 2 | 4));
        value
    });
    let result = native.runtime.block_on(async {
        let service = native.node.block_transfer_service().context("Хранилище недоступно")?;
        let peers = native.node.connected_peers();
        anyhow::ensure!(!peers.is_empty(), "Нет подключённых узлов. Файл сохранён на телефоне — повторите отправку после подключения.");
        ensure_file_path(native, &peers.iter().map(|p| p.to_string()).collect::<Vec<_>>()).await?;
        let mut ids: Vec<ContentId> = entry["blocks"].as_array().context("Invalid file index")?
            .iter().map(|v| content_id_from_hex(v.as_str().unwrap_or_default()).context("Invalid block ID"))
            .collect::<std::result::Result<_, _>>()?;
        if let Some(image_blocks) = entry["imageBlocks"].as_array() {
            for block in image_blocks {
                ids.push(content_id_from_hex(block.as_str().context("Invalid image block")?)
                    .context("Invalid image block")?);
            }
        }
        ids.push(content_id_from_hex(entry["id"].as_str().context("Missing ID")?).context("Invalid manifest ID")?);
        let mut last_error = String::new();
        // A replica means one peer acknowledged EVERY block, including the encrypted manifest.
        for (index, peer) in peers.iter().take(3).enumerate() {
            let stage = if index == 0 { "Передача в сеть".to_owned() }
                else { format!("Передача в сеть (попытка {})", index + 1) };
            match push_replica(&service, peer.as_str(), &ids, window, |done| {
                progress(json!({"busy":true,"name":entry["name"],"stage":stage,"done":done,"total":ids.len()}));
            }).await {
                Ok(()) => {
                    all_acked();
                    if let Some(dht) = native.node.dht_service() {
                        progress(json!({"busy":true,"name":entry["name"],"stage":"Публикация в DHT",
                            "done":ids.len(),"total":ids.len()}));
                        for id in &ids { let _ = dht.announce(*id).await; }
                    }
                    return Ok(peer.to_string());
                }
                Err(error) => last_error = error.to_string(),
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
            entry["replicas"] = json!(0);
            entry["peer"] = Value::Null;
            entry["error"] = json!(error.to_string());
            save_entry(native, entry)?;
            Err(error)
        }
    }
}
pub fn export(id: String, destination: String) -> Result<Value> {
    let native = get_native()?;
    export_from(&native, &id, &destination)
}

fn export_from(native: &NativeNode, id: &str, destination: &str) -> Result<Value> {
    let entry = read_index(&native)?.into_iter().find(|v| v["id"] == id).context("Файл не найден")?;
    let service = native.node.block_transfer_service().context("Хранилище недоступно")?;
    let manifest_id = content_id_from_hex(id).context("Invalid manifest ID")?;
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
#[path = "split_node_benchmark.rs"]
mod split_node_benchmark;

#[cfg(test)]
#[path = "file_transfer_benchmark.rs"]
mod file_transfer_benchmark;

#[cfg(test)]
#[path = "file_transfer_tests.rs"]
mod file_transfer_tests;

#[cfg(test)]
mod tests {
    use super::*;

    // Models an apparently connected LAN/NAT UDP path that drops every frame.
    struct BlackholeSession { peer: String }
    #[async_trait::async_trait]
    impl lp2ln_core_v2::sessions::Session for BlackholeSession {
        fn id(&self) -> &str { "test-blackhole" }
        fn peer_id(&self) -> Option<&str> { Some(&self.peer) }
        fn kind(&self) -> lp2ln_core_v2::sessions::LinkKind { lp2ln_core_v2::sessions::LinkKind::DirectUdp }
        async fn send(&self, _: lp2ln_core_v2::packet::Packet) -> Result<u64> { Ok(1) }
        async fn send_wire(&self, _: Vec<u8>) -> Result<u64> { Ok(1) }
        async fn close(&self) -> Result<()> { Ok(()) }
        fn spawn_reader(self: Arc<Self>, _: tokio::sync::mpsc::Sender<lp2ln_core_v2::sessions::session::IncomingPacket>) {}
    }

    pub(super) fn test_node(directory: &Path, routed_signatures: bool) -> (NativeNode, std::net::SocketAddr) {
        test_node_with_storage(directory, routed_signatures, true, true, 0)
    }

    pub(super) fn test_node_with_storage(
        directory: &Path, routed_signatures: bool, content: bool, dht: bool, quota: usize,
    ) -> (NativeNode, std::net::SocketAddr) {
        use lp2ln_core_v2::{
            db::P2PDatabase,
            event_core::prelude::{CoreEvent, TransportEvent},
            node::{NodeBuilder, NodeOptions},
            transport::tcp::TcpTransport,
        };
        let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
        let mut options = NodeOptions::empty().with_listen("tcp", "127.0.0.1:0".parse().unwrap());
        options.logger_options = None;
        options.lan_discovery.enabled = false;
        options.debug_server.enabled = false;
        options.ipc_tcp.enabled = false;
        options.experimental.content = content;
        options.experimental.content_max_local_blocks = quota;
        options.experimental.dht = dht;
        options.database_dir = Some(directory.to_path_buf());
        if routed_signatures {
            options.signature_format = lp2ln_core_v2::crypto::signature::SignatureFormat::V4Hash;
        }
        let mut node = NodeBuilder::new().db(Arc::new(P2PDatabase::new(directory.to_str().unwrap()).unwrap()))
            .add_transport(Arc::new(TcpTransport::new())).build(options).unwrap();
        let mut events = node.core_bus().subscribe_events();
        let address = runtime.block_on(async {
            node.start().await.unwrap();
            tokio::time::timeout(std::time::Duration::from_secs(5), async {
                loop {
                    if let CoreEvent::Transport(TransportEvent::Ready(ready)) = events.recv().await.unwrap() {
                        if let Some(address) = ready.listen_addr {
                            break address;
                        }
                    }
                }
            }).await.expect("test TCP listener must be ready")
        });
        let node = Arc::new(node);
        let known_peers = Arc::new(Mutex::new(crate::peers::PeerAddressBook::open(directory, node.peer_id())));
        let file_index = Arc::new(FileIndex::new(directory));
        let task = runtime.spawn(crate::sharing::listen(node.clone(), file_index.clone()));
        let chat_store = Arc::new(crate::chat::ChatStore::new(directory));
        let chat_task = runtime.spawn(crate::chat::listen(node.clone(), chat_store.clone()));
        (NativeNode { runtime, node, data_directory: directory.to_string_lossy().into_owned(),
            started_at: std::time::Instant::now(), known_peers, discovery_task: Mutex::new(None),
            sharing_task: Mutex::new(Some(task)), bootstrap_warning: None, file_index,
            chat_store, chat_task: Mutex::new(Some(chat_task)) }, address)
    }

    pub(super) fn stop_test_node(native: &NativeNode) {
        for task in [&native.discovery_task, &native.sharing_task, &native.chat_task] {
            if let Some(task) = task.lock().unwrap().take() {
                task.abort();
                let _ = native.runtime.block_on(task);
            }
        }
        native.chat_store.stop();
        native.runtime.block_on(native.node.stop()).unwrap();
    }

    #[test]
    fn key_delivery_and_file_download_work_through_two_relays_with_sender_offline() {
        let root = std::env::temp_dir().join(format!("lp2ln-relay-test-{}", content_id_hex(&generate_key())));
        fs::create_dir(&root).unwrap();
        let (relay_one, addr_one) = test_node(&root.join("relay-one"), false);
        let (relay_two, addr_two) = test_node(&root.join("relay-two"), false);
        let (sender, _) = test_node(&root.join("sender"), true);
        let (receiver, _) = test_node(&root.join("receiver"), true);
        relay_one.runtime.block_on(relay_one.node.connect("tcp", addr_two)).unwrap();
        sender.runtime.block_on(sender.node.connect("tcp", addr_one)).unwrap();
        receiver.runtime.block_on(receiver.node.connect("tcp", addr_two)).unwrap();
        sender.runtime.block_on(async {
            for _ in 0..100 {
                if sender.node.active_peer_count() == 1 && receiver.node.active_peer_count() == 1
                    && relay_one.node.active_peer_count() == 2 && relay_two.node.active_peer_count() == 2
                { return; }
                tokio::time::sleep(std::time::Duration::from_millis(50)).await;
            }
            panic!("Four-node relay topology did not connect");
        });
        let original = (0..CHUNK_SIZE * 5 + 73).map(|i| (i % 251) as u8).collect::<Vec<_>>();
        let source = root.join("source.bin");
        fs::write(&source, &original).unwrap();
        let thumbnail = [0xff, 0xd8, 1, 2, 3, 4, 0xff, 0xd9];
        fs::write(format!("{}.preview", source.display()), thumbnail).unwrap();
        let entry = import(&sender, source.to_str().unwrap(), "shared.bin").unwrap();
        let uploaded = publish(&sender, entry).unwrap();
        let id = uploaded["id"].as_str().unwrap();
        let capability = share_capability(&sender, id).unwrap();
        // The real direct path is unusable. Key delivery and its acknowledgment
        // must use reliable neighbors across both relays instead.
        for (from, to) in [(&sender, &receiver), (&receiver, &sender)] {
            let _runtime_guard = from.runtime.enter();
            from.node.router().unwrap().register_session(to.node.peer_id().into(), "test-blackhole".into(),
                Arc::new(BlackholeSession { peer: to.node.peer_id().to_owned() }));
        }
        let delivered = sender.runtime.block_on(crate::sharing::send(&sender.node, receiver.node.peer_id(), capability)).unwrap();
        assert_eq!(delivered["path"], "relay");
        let inbox = receiver.file_index.read().unwrap();
        assert_eq!(inbox.len(), 1);
        assert_eq!(inbox[0]["status"], "offered");
        assert_eq!(inbox[0]["receivedFrom"], sender.node.peer_id());
        assert!(receiver.node.block_transfer_service().unwrap().store.get(
            &content_id_from_hex(id).unwrap()).unwrap().is_none());
        // The sender can close its app after the key is delivered: the remote
        // replica supplies encrypted blocks across the two relay hops.
        stop_test_node(&sender);
        let preview = root.join("thumbnail.jpg");
        let mut wrong_key = receiver.file_index.read().unwrap()[0].clone();
        let correct_key = wrong_key["image"]["key"].clone();
        wrong_key["image"]["key"] = json!(content_id_hex(&generate_key()));
        receiver.file_index.save(wrong_key.clone(), false).unwrap();
        assert!(crate::image_preview::fetch(&receiver, id, preview.to_str().unwrap()).is_err());
        assert!(!preview.exists());
        wrong_key["image"]["key"] = correct_key;
        receiver.file_index.save(wrong_key, false).unwrap();
        crate::image_preview::fetch(&receiver, id, preview.to_str().unwrap()).unwrap();
        assert_eq!(fs::read(preview).unwrap(), thumbnail);
        assert_eq!(receiver.file_index.read().unwrap()[0]["status"], "offered");
        let receiver_store = &receiver.node.block_transfer_service().unwrap().store;
        // Viewing a thumbnail must not fetch the original encrypted manifest or
        // any full-sized content block.
        for original_id in std::iter::once(&uploaded["id"]).chain(uploaded["blocks"].as_array().unwrap()) {
            assert!(receiver_store.get(&content_id_from_hex(original_id.as_str().unwrap()).unwrap()).unwrap().is_none());
        }
        download(&receiver, id).unwrap();
        let output = root.join("received.bin");
        export_from(&receiver, id, output.to_str().unwrap()).unwrap();
        assert_eq!(fs::read(output).unwrap(), original);
        assert_eq!(receiver.file_index.read().unwrap()[0]["status"], "received");
        stop_test_node(&receiver);
        stop_test_node(&relay_one);
        stop_test_node(&relay_two);
        drop((sender, receiver, relay_one, relay_two));
        assert!(root.canonicalize().unwrap().starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(root).unwrap();
    }
    // Sends only generated capabilities; no file contents are uploaded to the
    // live nodes. Separate bootstraps also exercise the hop between two relays.
    #[test]
    #[ignore = "requires LP2LN_TEST_BOOTSTRAP; sends generated keys through a live network"]
    fn key_delivery_and_acknowledgment_through_live_relays() {
        let bootstrap = std::env::var("LP2LN_TEST_BOOTSTRAP").expect("Set LP2LN_TEST_BOOTSTRAP");
        let second = std::env::var("LP2LN_TEST_SECOND_BOOTSTRAP").unwrap_or_else(|_| bootstrap.clone());
        let root = std::env::temp_dir().join(format!("lp2ln-live-sharing-{}", content_id_hex(&generate_key())));
        fs::create_dir(&root).unwrap();
        let (sender, _) = test_node(&root.join("sender"), true);
        let (receiver, _) = test_node(&root.join("receiver"), true);
        let result: Result<()> = sender.runtime.block_on(async {
            sender.node.connect("tcp", bootstrap.parse()?).await?;
            receiver.node.connect("tcp", second.parse()?).await?;
            tokio::time::timeout(std::time::Duration::from_secs(60), async {
                while sender.node.active_peer_count() == 0 || receiver.node.active_peer_count() == 0 {
                    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                }
            }).await.context("Live relay connections did not become active")?;
            for round in 0..3 {
                for (from, to) in [(&sender, &receiver), (&receiver, &sender)] {
                    let id = content_id_hex(&generate_key());
                    let key = content_id_hex(&generate_key());
                    let capability = json!({"id":id,"key":key,"name":"delivery-check.txt",
                        "size":0,"providers":[]});
                    println!("Live key delivery round {round}: {} -> {}", from.node.peer_id(), to.node.peer_id());
                    let ack = crate::sharing::send(&from.node, to.node.peer_id(), capability).await?;
                    anyhow::ensure!(ack["path"] == "relay", "Delivery bypassed the live relay");
                    let inbox = to.file_index.read()?;
                    let entry = inbox.iter().find(|entry| entry["id"] == id).context("Key missing from inbox")?;
                    anyhow::ensure!(entry["key"] == key && entry["receivedFrom"] == from.node.peer_id(),
                        "Received capability does not match sender");
                }
            }
            Ok(())
        });
        stop_test_node(&sender);
        stop_test_node(&receiver);
        drop((sender, receiver));
        assert!(root.canonicalize().unwrap().starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(root).unwrap();
        result.unwrap();
    }

    #[test]
    #[ignore = "requires LP2LN_TEST_BOOTSTRAP and LP2LN_TEST_RECIPIENT; sends a small test file to a real recipient"]
    fn send_test_file_to_live_recipient() {
        let bootstrap = std::env::var("LP2LN_TEST_BOOTSTRAP").expect("Set LP2LN_TEST_BOOTSTRAP");
        let recipient = std::env::var("LP2LN_TEST_RECIPIENT").expect("Set LP2LN_TEST_RECIPIENT");
        let key_only = std::env::var("LP2LN_TEST_KEY_ONLY").as_deref() == Ok("1");
        assert!(crate::sharing::valid_peer(&recipient), "Invalid recipient Peer ID");
        let root = std::env::temp_dir().join(format!("lp2ln-live-recipient-{}", content_id_hex(&generate_key())));
        fs::create_dir(&root).unwrap();
        crate::start_node(root.join("node").to_string_lossy().into_owned(), bootstrap).unwrap();
        let native = get_native().unwrap();
        let result = (|| -> Result<()> {
            println!("Test sender: {}; recipient: {recipient}", native.node.peer_id());
            native.runtime.block_on(async {
                if let Ok(address) = std::env::var("LP2LN_TEST_UDP_BOOTSTRAP") {
                    native.node.connect("udp", address.parse()?).await?;
                }
                tokio::time::timeout(std::time::Duration::from_secs(60), async {
                    while native.node.active_peer_count() == 0 {
                        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                    }
                }).await.context("No authenticated session with the live network")?;
                Ok::<_, anyhow::Error>(())
            })?;
            let source = root.join("delivery-check.txt");
            fs::write(&source, format!("LP2LN key delivery check.\nGenerated at Unix time {}.\n",
                SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs()))?;
            let name = if key_only { "lp2ln-key-check.txt" } else { "lp2ln-delivery-check.txt" };
            let entry = import(&native, source.to_str().context("Invalid test directory")?, name)?;
            let uploaded = if key_only { entry } else { publish(&native, entry)? };
            let id = uploaded["id"].as_str().context("Missing test file ID")?;
            println!("Sending test key; file replicated: {}", !key_only);
            let capability = share_capability(&native, id)?;
            let ack = native.runtime.block_on(async {
                let _ = ensure_file_path(&native, &[recipient.clone()]).await;
                crate::sharing::send(&native.node, &recipient, capability).await
            })?;
            println!("Recipient acknowledged test key: {ack}");
            Ok(())
        })();
        if result.is_err() {
            println!("Sender sessions: {:?}", native.node.debug_sessions());
        }
        crate::stop_node().unwrap();
        drop(native);
        assert!(root.canonicalize().unwrap().starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(root).unwrap();
        result.unwrap();
    }

    #[test]
    #[ignore = "requires LP2LN_TEST_BOOTSTRAP; waits for an invitation from a live sender"]
    fn receive_test_key_from_live_sender() {
        let bootstrap = std::env::var("LP2LN_TEST_BOOTSTRAP").expect("Set LP2LN_TEST_BOOTSTRAP");
        let sender = std::env::var("LP2LN_TEST_RECIPIENT").expect("Set LP2LN_TEST_RECIPIENT to the sender Peer ID");
        let root = std::env::temp_dir().join(format!("lp2ln-live-inbox-{}", content_id_hex(&generate_key())));
        fs::create_dir(&root).unwrap();
        let (native, _) = test_node(&root.join("node"), true);
        let result = native.runtime.block_on(async {
            native.node.connect("tcp", bootstrap.parse()?).await?;
            tokio::time::timeout(std::time::Duration::from_secs(60), async {
                while native.node.active_peer_count() == 0 {
                    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                }
            }).await.context("No authenticated session with the live network")?;
            println!("RECEIVE_INVITATION lp2ln://peer/{}", native.node.peer_id());
            tokio::time::timeout(std::time::Duration::from_secs(300), async {
                loop {
                    if let Some(entry) = native.file_index.read()?.into_iter()
                        .find(|entry| entry["receivedFrom"] == sender) {
                        println!("Received test key from the expected sender: name={}, size={}",
                            entry["name"], entry["size"]);
                        // Give the listener time to send the delivery acknowledgment.
                        tokio::time::sleep(std::time::Duration::from_secs(5)).await;
                        return Ok::<_, anyhow::Error>(());
                    }
                    tokio::time::sleep(std::time::Duration::from_millis(250)).await;
                }
            }).await.context("No invitation received from the expected sender")?
        });
        if result.is_err() { println!("Receiver sessions: {:?}", native.node.debug_sessions()); }
        stop_test_node(&native);
        drop(native);
        assert!(root.canonicalize().unwrap().starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(root).unwrap();
        result.unwrap();
    }

    #[test]
    fn chat_messages_and_acknowledgments_cross_a_relay_and_retries_do_not_duplicate_history() {
        let root = std::env::temp_dir().join(format!("lp2ln-chat-relay-{}", content_id_hex(&generate_key())));
        fs::create_dir(&root).unwrap();
        let (relay, address) = test_node(&root.join("relay"), false);
        let (sender, _) = test_node(&root.join("sender"), true);
        let (receiver, _) = test_node(&root.join("receiver"), true);
        sender.runtime.block_on(async {
            sender.node.connect("tcp", address).await.unwrap();
            receiver.node.connect("tcp", address).await.unwrap();
            tokio::time::timeout(std::time::Duration::from_secs(5), async {
                while sender.node.active_peer_count() == 0 || receiver.node.active_peer_count() == 0 {
                    tokio::time::sleep(std::time::Duration::from_millis(25)).await;
                }
            }).await.unwrap();
        });
        crate::chat::probe(&sender, receiver.node.peer_id()).unwrap();
        sender.runtime.block_on(async {
            tokio::time::timeout(std::time::Duration::from_secs(10), async {
                while !sender.chat_store.snapshot()["presence"].as_array().unwrap().iter()
                    .any(|p| p["peerId"] == receiver.node.peer_id() && p["status"] == "online") {
                    tokio::time::sleep(std::time::Duration::from_millis(25)).await;
                }
            }).await.unwrap();
        });
        for (from, to, text) in [(&sender, &receiver, "Привет с другого узла 👋"), (&receiver, &sender, "Сообщение получено!")] {
            let queued = crate::chat::enqueue(from, to.node.peer_id(), text, "").unwrap();
            let id = queued["message"]["id"].as_str().unwrap();
            from.runtime.block_on(async {
                tokio::time::timeout(std::time::Duration::from_secs(10), async {
                    while from.chat_store.read().unwrap().iter().find(|m| m["id"] == id).unwrap()["status"] != "delivered" {
                        tokio::time::sleep(std::time::Duration::from_millis(25)).await;
                    }
                }).await.unwrap();
                crate::sharing::send_packet(&from.node, to.node.peer_id(), json!({"kind":"chat","v":1,"id":id,"text":text}), 1).await.unwrap();
                tokio::time::sleep(std::time::Duration::from_millis(150)).await;
            });
            let inbox = to.chat_store.read().unwrap();
            assert_eq!(inbox.iter().filter(|m| m["id"] == id && m["outgoing"] == false).count(), 1);
            assert_eq!(inbox.iter().find(|m| m["id"] == id).unwrap()["text"], text);
            assert!(to.file_index.read().unwrap().is_empty(), "Chat messages must not create file invitations");
        }
        let offline = lp2ln_core_v2::crypto::NodeKeypair::generate();
        crate::chat::probe(&sender, offline.peer_id()).unwrap();
        sender.runtime.block_on(async {
            tokio::time::timeout(std::time::Duration::from_secs(11), async {
                while !sender.chat_store.snapshot()["presence"].as_array().unwrap().iter()
                    .any(|p| p["peerId"] == offline.peer_id() && p["status"] == "offline") {
                    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                }
            }).await.unwrap();
        });
        for node in [&sender, &receiver, &relay] { stop_test_node(node); }
        drop((sender, receiver, relay));
        assert!(root.canonicalize().unwrap().starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    #[ignore = "requires LP2LN_TEST_BOOTSTRAP and LP2LN_TEST_RECIPIENT; exchanges chat messages with a real phone"]
    fn chat_round_trip_with_live_phone() {
        let bootstrap = std::env::var("LP2LN_TEST_BOOTSTRAP").expect("Set LP2LN_TEST_BOOTSTRAP");
        let recipient = std::env::var("LP2LN_TEST_RECIPIENT").expect("Set LP2LN_TEST_RECIPIENT");
        let root = std::env::temp_dir().join(format!("lp2ln-live-chat-{}", content_id_hex(&generate_key())));
        fs::create_dir(&root).unwrap();
        crate::start_node(root.join("node").to_string_lossy().into_owned(), bootstrap).unwrap();
        let native = get_native().unwrap();
        let result: Result<()> = (|| {
            println!("CHAT_PEER lp2ln://peer/{}", native.node.peer_id());
            native.runtime.block_on(async {
                tokio::time::timeout(std::time::Duration::from_secs(60), async {
                    while native.node.active_peer_count() == 0 {
                        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                    }
                }).await?;
                Ok::<_, anyhow::Error>(())
            })?;
            crate::chat::probe(&native, &recipient)?;
            native.runtime.block_on(async {
                tokio::time::timeout(std::time::Duration::from_secs(12), async {
                    while !native.chat_store.snapshot()["presence"].as_array().unwrap().iter()
                        .any(|p| p["peerId"] == recipient && p["status"] == "online") {
                        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                    }
                }).await.context("Phone did not respond to the presence probe")
            })?;
            println!("PHONE_PRESENCE_ONLINE");
            let queued = crate::chat::enqueue(&native, &recipient, "Привет 👋 P2P-чат работает. Ответь на это сообщение, чтобы проверить обе стороны.", "")?;
            let id = queued["message"]["id"].as_str().context("Missing message ID")?;
            native.runtime.block_on(async {
                tokio::time::timeout(std::time::Duration::from_secs(35), async {
                    loop {
                        let history = native.chat_store.read()?;
                        let sent = history.iter().find(|m| m["id"] == id).context("Missing outgoing message")?;
                        if sent["status"] == "delivered" { break; }
                        anyhow::ensure!(sent["status"] != "failed", "Phone did not acknowledge the chat message");
                        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                    }
                    println!("PHONE_CHAT_DELIVERED");
                    Ok::<_, anyhow::Error>(())
                }).await??;
                tokio::time::timeout(std::time::Duration::from_secs(240), async {
                    loop {
                        if native.chat_store.read()?.iter().any(|m| m["peerId"] == recipient && m["outgoing"] == false) {
                            println!("PHONE_CHAT_REPLY_RECEIVED");
                            tokio::time::sleep(std::time::Duration::from_secs(5)).await;
                            break;
                        }
                        tokio::time::sleep(std::time::Duration::from_millis(150)).await;
                    }
                    Ok::<_, anyhow::Error>(())
                }).await??;
                Ok(())
            })
        })();
        crate::stop_node().unwrap();
        drop(native);
        assert!(root.canonicalize().unwrap().starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(root).unwrap();
        result.unwrap();
    }

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
        let known_peers = Arc::new(Mutex::new(crate::peers::PeerAddressBook::open(
            &root.join("sender"), sender.peer_id())));
        let native = NativeNode { runtime, node: Arc::new(sender), started_at: std::time::Instant::now(),
            file_index: Arc::new(FileIndex::new(root.join("sender"))), sharing_task: Mutex::new(None),
            chat_store: Arc::new(crate::chat::ChatStore::new(&root.join("sender"))), chat_task: Mutex::new(None),
            known_peers, discovery_task: Mutex::new(None), bootstrap_warning: None,
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
    #[ignore = "requires LP2LN_TEST_BOOTSTRAP, LP2LN_TEST_PROVIDER and LP2LN_TEST_BLOCK_ID"]
    fn fetch_existing_encrypted_block_through_live_network() {
        let root = std::env::temp_dir().join(format!("lp2ln-live-fetch-{}", content_id_hex(&generate_key())));
        fs::create_dir(&root).unwrap();
        let bootstrap = std::env::var("LP2LN_TEST_BOOTSTRAP").unwrap();
        let provider = std::env::var("LP2LN_TEST_PROVIDER").unwrap();
        let id = content_id_from_hex(&std::env::var("LP2LN_TEST_BLOCK_ID").unwrap()).unwrap();
        crate::start_node(root.to_string_lossy().into_owned(), bootstrap).unwrap();
        let native = get_native().unwrap();
        let encrypted = native.runtime.block_on(async {
            for _ in 0..100 {
                if native.node.active_peer_count() > 0 { break; }
                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            }
            native.node.block_transfer_service().unwrap().fetch_from(&provider, id).await.unwrap()
        });
        assert!(!encrypted.is_empty());
        println!("Fetched and hash-verified {} encrypted bytes from the live provider", encrypted.len());
        crate::stop_node().unwrap();
        drop(native);
        fs::remove_dir_all(root).unwrap();
    }

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
