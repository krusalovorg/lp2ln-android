mod files;
mod image_preview;
mod peers;
mod sharing;
use std::net::SocketAddr;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use lp2ln_core_v2::db::P2PDatabase;
use lp2ln_core_v2::node::{NodeBuilder, NodeLifecycleState, NodeOptions, NodeRuntime, RuntimeMode};
use lp2ln_core_v2::peer_score::PeerConnectionPolicy;
use lp2ln_core_v2::transport::{tcp::TcpTransport, udp::UdpTransport};
use serde_json::{json, Value};
use tokio::runtime::{Builder, Runtime};

struct NativeNode {
    runtime: Runtime,
    node: Arc<NodeRuntime>,
    started_at: Instant,
    data_directory: String,
    known_peers: Arc<Mutex<peers::PeerAddressBook>>,
    discovery_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    bootstrap_warning: Option<String>,
    file_index: Arc<files::FileIndex>,
    sharing_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

static NODE: OnceLock<Mutex<Option<Arc<NativeNode>>>> = OnceLock::new();

fn node_slot() -> &'static Mutex<Option<Arc<NativeNode>>> {
    NODE.get_or_init(|| Mutex::new(None))
}

fn start_node(data_directory: String, bootstrap_address: String) -> Result<Value> {
    let mut slot = node_slot()
        .lock()
        .map_err(|_| anyhow::anyhow!("LP2LN state lock is poisoned"))?;

    if let Some(existing) = slot.as_ref() {
        return Ok(snapshot(existing));
    }

    let database = Arc::new(
        P2PDatabase::new(&data_directory).context("failed to open LP2LN database")?,
    );
    let own_peer = database.get_or_create_node_keypair()?.peer_id().to_owned();
    let book = peers::PeerAddressBook::open(std::path::Path::new(&data_directory), &own_peer);
    let mut targets = book.targets();
    let runtime = Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("lp2ln")
        .build()
        .context("failed to create LP2LN runtime")?;
    let bootstrap_warning = if bootstrap_address.trim().is_empty() {
        None
    } else {
        let resolved = runtime.block_on(async {
            let addresses = tokio::time::timeout(Duration::from_secs(3),
                tokio::net::lookup_host(bootstrap_address.as_str())).await
                .context("Bootstrap DNS lookup timed out")??;
            addresses.into_iter().find(SocketAddr::is_ipv4).context("Bootstrap has no IPv4 address")
        });
        match resolved {
            Ok(address) => { peers::add_primary(&mut targets, address); None }
            Err(error) => Some(format!("Bootstrap недоступен: {error}. Используются сохранённые адреса и поиск в локальной сети.")),
        }
    };
    let mut options = NodeOptions::empty()
        .with_listen("tcp", "0.0.0.0:0".parse()?)
        .with_listen("udp", "0.0.0.0:0".parse()?)
        .with_bootstrap_nodes(targets)
        .with_peer_connection_policy(PeerConnectionPolicy {
            min_active_peers: 1,
            target_active_peers: 4,
            max_active_peers: 8,
        })
        .allow_unsigned_packets(false);
    options.experimental.content = true;
    options.database_dir = Some(std::path::PathBuf::from(&data_directory));
    options.signature_format = lp2ln_core_v2::crypto::signature::SignatureFormat::V4Hash;
    options.experimental.dht = true;
    options.logger_options = None;
    options.lan_discovery.enabled = true;
    options.debug_server.enabled = false;
    options.ipc_tcp.enabled = false;

    let mut node = NodeBuilder::new()
        .db(database)
        .add_transport(Arc::new(TcpTransport::new()))
        .add_transport(Arc::new(UdpTransport::new()))
        .build(options)
        .context("failed to build LP2LN node")?;
    runtime
        .block_on(node.start())
        .context("failed to start LP2LN node")?;

    let node = Arc::new(node);
    let known_peers = Arc::new(Mutex::new(book));
    let discovery_task = runtime.spawn(peers::maintain(node.clone(), known_peers.clone()));
    let file_index = Arc::new(files::FileIndex::new(&data_directory));
    let sharing_task = runtime.spawn(sharing::listen(node.clone(), file_index.clone()));
    let native = Arc::new(NativeNode {
        runtime,
        node,
        started_at: Instant::now(),
        data_directory,
        known_peers,
        discovery_task: Mutex::new(Some(discovery_task)),
        bootstrap_warning,
        file_index,
        sharing_task: Mutex::new(Some(sharing_task)),
    });
    let result = snapshot(&native);
    *slot = Some(native);
    Ok(result)
}

fn snapshot(native: &NativeNode) -> Value {
    let sessions = native.node.debug_sessions();
    let bytes_sent = sessions.iter().map(|session| session.bytes_sent).sum::<u64>();
    let bytes_received = sessions
        .iter()
        .map(|session| session.bytes_received)
        .sum::<u64>();
    let active_connections = sessions.iter().filter(|session| session.is_active).count();
    let health = native.node.health_snapshot();
    let lifecycle = match native.node.lifecycle_state() {
        NodeLifecycleState::Created => "created",
        NodeLifecycleState::Running => "running",
        NodeLifecycleState::Stopping => "stopping",
        NodeLifecycleState::Stopped => "stopped",
    };
    let session_values = sessions
        .into_iter()
        .map(|session| {
            json!({
                "peerId": session.peer_id.unwrap_or_else(|| "Handshake".to_string()),
                "protocol": session.protocol,
                "active": session.is_active,
                "bytesSent": session.bytes_sent,
                "bytesReceived": session.bytes_received,
                "lastActivitySeconds": session.last_activity_secs_ago,
            })
        })
        .collect::<Vec<_>>();

    json!({
        "ok": true,
        "lifecycle": lifecycle,
        "peerId": native.node.peer_id(),
        "activePeers": native.node.active_peer_count(),
        "activeConnections": active_connections,
        "bytesSent": bytes_sent,
        "bytesReceived": bytes_received,
        "uptimeSeconds": native.started_at.elapsed().as_secs(),
        "degraded": health.mode == RuntimeMode::Degraded,
        "lastError": health.last_error.unwrap_or_default(),
        "sessions": session_values,
        "storage": files::state(native),
        "discovery": native.known_peers.lock().map(|book| book.snapshot()).unwrap_or_default(),
        "bootstrapWarning": native.bootstrap_warning,
    })
}

fn stop_node() -> Result<Value> {
    let mut slot = node_slot()
        .lock()
        .map_err(|_| anyhow::anyhow!("LP2LN state lock is poisoned"))?;
    if let Some(native) = slot.take() {
        for task in [&native.discovery_task, &native.sharing_task] {
            if let Some(task) = task.lock().ok().and_then(|mut task| task.take()) {
                task.abort();
                let _ = native.runtime.block_on(task);
            }
        }
        native
            .runtime
            .block_on(native.node.stop())
            .context("failed to stop LP2LN node")?;
    }
    Ok(json!({ "ok": true, "lifecycle": "stopped" }))
}

fn current_snapshot() -> Result<Value> {
    let slot = node_slot()
        .lock()
        .map_err(|_| anyhow::anyhow!("LP2LN state lock is poisoned"))?;
    Ok(match slot.as_ref() {
        Some(native) => snapshot(native),
        None => json!({ "ok": true, "lifecycle": "stopped" }),
    })
}

fn read_string(env: &mut JNIEnv, input: JString) -> Result<String> {
    Ok(env
        .get_string(&input)
        .context("invalid Java string")?
        .into())
}

fn return_json(env: &mut JNIEnv, operation: impl FnOnce() -> Result<Value>) -> jstring {
    let payload = match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({ "ok": false, "error": format!("{error:#}") }),
        Err(_) => json!({ "ok": false, "error": "LP2LN native panic" }),
    }
    .to_string();
    env.new_string(payload)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnNativeBridge_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    data_directory: JString,
    bootstrap_address: JString,
) -> jstring {
    let args: Result<(String, String)> = (|| {
        Ok((
            read_string(&mut env, data_directory)?,
            read_string(&mut env, bootstrap_address)?,
        ))
    })();
    return_json(&mut env, || {
        let (data_directory, bootstrap_address) = args?;
        start_node(data_directory, bootstrap_address)
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnNativeBridge_nativeSnapshot(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    return_json(&mut env, current_snapshot)
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnNativeBridge_nativeStop(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    return_json(&mut env, stop_node)
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnNativeBridge_nativeUpload(
    mut env: JNIEnv, _class: JClass, path: JString, name: JString, retry_id: JString,
) -> jstring {
    let args: Result<_> = (|| Ok((read_string(&mut env, path)?, read_string(&mut env, name)?, read_string(&mut env, retry_id)?)))();
    return_json(&mut env, || { let (path, name, retry_id) = args?; files::begin(path, name, retry_id) })
}
#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnNativeBridge_nativeExport(
    mut env: JNIEnv, _class: JClass, id: JString, destination: JString,
) -> jstring {
    let args: Result<_> = (|| Ok((read_string(&mut env, id)?, read_string(&mut env, destination)?)))();
    return_json(&mut env, || { let (id, destination) = args?; files::export(id, destination) })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnNativeBridge_nativeShare(
    mut env: JNIEnv, _class: JClass, id: JString, recipient: JString,
) -> jstring {
    let args: Result<_> = (|| Ok((read_string(&mut env, id)?, read_string(&mut env, recipient)?)))();
    return_json(&mut env, || {
        let (id, recipient) = args?;
        let native = files::get_native()?;
        let capability = files::share_capability(&native, &id)?;
        files::progress(json!({"busy":true,"name":capability["name"],"stage":"Отправка ключа","done":0,"total":0}));
        let result = native.runtime.block_on(sharing::send(&native.node, &recipient, capability));
        files::progress(json!({"busy":false}));
        result
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnNativeBridge_nativeDownload(
    mut env: JNIEnv, _class: JClass, id: JString,
) -> jstring {
    let id = read_string(&mut env, id);
    return_json(&mut env, || {
        let native = files::get_native()?;
        files::download(&native, &id?)
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnNativeBridge_nativeThumbnail(
    mut env: JNIEnv, _class: JClass, id: JString, destination: JString,
) -> jstring {
    let args: Result<_> = (|| Ok((read_string(&mut env, id)?, read_string(&mut env, destination)?)))();
    return_json(&mut env, || {
        let (id, destination) = args?;
        let native = files::get_native()?;
        image_preview::fetch(&native, &id, &destination)
    })
}
