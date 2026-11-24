use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use anyhow::{ensure, Context, Result};
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use lp2ln_core_v2::flow::{Flow, FlowError, FlowSender};
use lp2ln_core_v2::PeerId;
use serde_json::{json, Value};
use tokio::sync::Mutex as AsyncMutex;

use crate::{files::get_native, node_slot, read_string, return_json, NativeNode};

const HANDLE_LIMIT: usize = 32;
const MAX_PAYLOAD: usize = 4096;
const TIMEOUT: Duration = Duration::from_secs(12);

#[cfg(feature = "voice-preview")]
#[path = "voice.rs"]
mod voice;

struct Handle {
    #[cfg(feature = "voice-preview")]
    voice: voice::Session,
    tx: FlowSender,
    rx: AsyncMutex<Flow>,
}

static NEXT: AtomicI64 = AtomicI64::new(1);
static FLOWS: OnceLock<Mutex<HashMap<i64, Arc<Handle>>>> = OnceLock::new();

fn flows() -> &'static Mutex<HashMap<i64, Arc<Handle>>> {
    FLOWS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn clear_handles() {
    flows().lock().unwrap().clear();
}

fn handle(id: i64) -> Result<Arc<Handle>> {
    flows()
        .lock()
        .unwrap()
        .get(&id)
        .cloned()
        .context("unknown Flow handle")
}

fn expose(native: &Arc<NativeNode>, flow: Flow) -> Result<Value> {
    let owner = node_slot().lock().unwrap();
    ensure!(
        owner
            .as_ref()
            .is_some_and(|active| Arc::ptr_eq(active, native)),
        "node stopped"
    );
    let mut active = flows().lock().unwrap();
    ensure!(active.len() < HANDLE_LIMIT, "too many open Flow handles");
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    ensure!(id > 0, "Flow handles exhausted");
    let tx = flow.sender();
    #[cfg(feature = "voice-preview")]
    let voice = voice::Session::new(&flow);
    let value = json!({
        "ok": true, "handle": id, "maxPayload": tx.max_payload(), "flowId": flow.id().0,
        "peerId": flow.link().peer.as_str(), "protocol": flow.protocol(),
    });
    active.insert(
        id,
        Arc::new(Handle {
            #[cfg(feature = "voice-preview")]
            voice,
            tx,
            rx: AsyncMutex::new(flow),
        }),
    );
    Ok(value)
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnRealtimeBridge_nativeStatus(
    mut env: JNIEnv,
    _object: JObject,
) -> jstring {
    return_json(&mut env, || {
        let n = get_native()?;
        let addr = n
            .node
            .bound_transport_addr("quic")
            .context("QUIC listener is starting")?;
        Ok(json!({ "ok": true, "peerId": n.node.peer_id(), "port": addr.port() }))
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnRealtimeBridge_nativeAuthorize(
    mut env: JNIEnv,
    _object: JObject,
    peer: JString,
    protocol: jint,
) -> jstring {
    let peer = read_string(&mut env, peer);
    return_json(&mut env, || {
        get_native()?
            .node
            .authorize_realtime_peer(&PeerId::from(peer?), protocol.try_into()?)?;
        Ok(json!({ "ok": true }))
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnRealtimeBridge_nativeOpen(
    mut env: JNIEnv,
    _object: JObject,
    peer: JString,
    addr: JString,
    protocol: jint,
) -> jstring {
    let peer = read_string(&mut env, peer);
    let addr = read_string(&mut env, addr);
    return_json(&mut env, || {
        let native = get_native()?;
        let peer = PeerId::from(peer?);
        let addr = addr?.parse()?;
        let protocol = protocol.try_into()?;
        let flow = native.runtime.block_on(async {
            tokio::time::timeout(
                TIMEOUT,
                native.node.open_realtime_flow(&peer, addr, protocol),
            )
            .await?
        })?;
        expose(&native, flow)
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnRealtimeBridge_nativeAccept(
    mut env: JNIEnv,
    _object: JObject,
    timeout_ms: jint,
) -> jstring {
    return_json(&mut env, || {
        let native = get_native()?;
        let timeout = Duration::from_millis(timeout_ms.clamp(1, 120_000) as u64);
        let flow = native.runtime.block_on(async {
            tokio::time::timeout(timeout, native.node.accept_realtime_flow()).await?
        })?;
        expose(&native, flow)
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnRealtimeBridge_nativeOffer(
    env: JNIEnv,
    _object: JObject,
    id: jlong,
    data: JByteArray,
    ttl_ms: jint,
) -> jint {
    let Ok(handle) = handle(id) else { return 1 };
    let Ok(len) = env.get_array_length(&data) else {
        return 1;
    };
    if len <= 0 || len as usize > MAX_PAYLOAD.min(handle.tx.max_payload()) {
        return 4;
    }
    if !(1..=2000).contains(&ttl_ms) {
        return 2;
    }
    let Ok(data) = env.convert_byte_array(data) else {
        return 1;
    };
    match handle
        .tx
        .try_send(&data, Instant::now() + Duration::from_millis(ttl_ms as u64))
    {
        Ok(()) => 0,
        Err(FlowError::Closed) => 1,
        Err(FlowError::Expired) => 2,
        Err(FlowError::ClockUncertain) => 3,
        Err(FlowError::TooLarge) => 4,
        Err(FlowError::QueueFull) => 5,
        Err(FlowError::Paced) => 6,
        Err(FlowError::Transport | FlowError::Unsupported) => 7,
    }
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnRealtimeBridge_nativeReceive(
    mut env: JNIEnv,
    _object: JObject,
    id: jlong,
    timeout_ms: jint,
) -> jbyteArray {
    let received = (|| -> Result<Option<Vec<u8>>> {
        let native = get_native()?;
        let handle = handle(id)?;
        let timeout = Duration::from_millis(timeout_ms.clamp(1, 2000) as u64);
        native.runtime.block_on(async {
            match tokio::time::timeout(timeout, async { handle.rx.lock().await.recv().await }).await
            {
                Ok(Ok(bytes)) => Ok(Some(bytes.to_vec())),
                Ok(Err(err)) => Err(err.into()),
                Err(_) => Ok(None),
            }
        })
    })();
    match received {
        Ok(Some(bytes)) => env
            .byte_array_from_slice(&bytes)
            .map(|data| data.into_raw())
            .unwrap_or_default(),
        Ok(None) => std::ptr::null_mut(),
        Err(err) => {
            let _ = env.throw_new("java/lang/IllegalStateException", err.to_string());
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnRealtimeBridge_nativeClose(
    mut env: JNIEnv,
    _object: JObject,
    id: jlong,
) -> jstring {
    return_json(&mut env, || {
        let handle = flows().lock().unwrap().remove(&id);
        if let Some(handle) = handle {
            get_native()?.runtime.block_on(async {
                handle.rx.lock().await.close();
            });
        }
        Ok(json!({ "ok": true }))
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_Lp2lnRealtimeBridge_nativeRevoke(
    mut env: JNIEnv,
    _: JObject,
    peer: JString,
    protocol: jint,
) -> jstring {
    let peer = read_string(&mut env, peer);
    return_json(&mut env, || {
        get_native()?
            .node
            .revoke_realtime_peer(&PeerId::from(peer?), protocol.try_into()?)?;
        Ok(json!({"ok":true}))
    })
}
