use super::*;
use jni::objects::JShortArray;
use lp2ln_core_v2::flow::{FlowControl, FlowId};
use lp2ln_rtc::{AudioStats, CallDialog, Capture, Playout, Signal, FRAME, PROTOCOL};

pub(super) struct Session {
    id: FlowId,
    protocol: u16,
    control: FlowControl,
    dialog: OnceLock<Mutex<CallDialog>>,
    media: OnceLock<(Mutex<Capture>, Mutex<Playout>)>,
}

impl Session {
    pub(super) fn new(flow: &Flow) -> Self {
        Self {
            id: flow.id(),
            protocol: flow.protocol(),
            control: flow.control(),
            dialog: OnceLock::new(),
            media: OnceLock::new(),
        }
    }
    fn media(&self) -> Result<&(Mutex<Capture>, Mutex<Playout>)> {
        self.media.get().context("audio has not been started")
    }
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_VoiceBridge_begin(
    mut env: JNIEnv,
    _: JObject,
    id: jlong,
    caller: jni::sys::jboolean,
) -> jstring {
    return_json(&mut env, || {
        let h = handle(id)?;
        ensure!(h.voice.protocol == PROTOCOL, "wrong protocol");
        ensure!(
            h.voice
                .dialog
                .set(Mutex::new(CallDialog::new(caller != 0, Instant::now())))
                .is_ok(),
            "duplicate call"
        );
        Ok(json!({"ok":true}))
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_VoiceBridge_decide(
    mut env: JNIEnv,
    _: JObject,
    id: jlong,
    accept: jni::sys::jboolean,
) -> jstring {
    return_json(&mut env, || {
        let h = handle(id)?;
        let mut dialog = h
            .voice
            .dialog
            .get()
            .context("call not initialized")?
            .lock()
            .unwrap();
        if accept != 0 {
            dialog.consent()
        } else {
            dialog.end()
        }
        Ok(json!({"ok":true}))
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_VoiceBridge_step(
    mut env: JNIEnv,
    _: JObject,
    id: jlong,
) -> jstring {
    return_json(&mut env, || {
        let h = handle(id)?;
        let dialog = h.voice.dialog.get().context("call not initialized")?;
        let sent = dialog.lock().unwrap().next(Instant::now());
        let remote = get_native()?.runtime.block_on(lp2ln_rtc::exchange(
            &h.voice.control,
            h.voice.id,
            sent,
        ))?;
        let mut dialog = dialog.lock().unwrap();
        let finished = dialog.observe(sent, remote)?;
        Ok(
            json!({"ok":true,"state":format!("{:?}",dialog.state),"incoming":dialog.incoming(),"finished":finished}),
        )
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_VoiceBridge_startMedia(
    mut env: JNIEnv,
    _: JObject,
    id: jlong,
) -> jstring {
    return_json(&mut env, || {
        let h = handle(id)?;
        ensure!(h.voice.protocol == PROTOCOL, "wrong protocol");
        ensure!(
            h.voice
                .dialog
                .get()
                .is_some_and(|d| d.lock().unwrap().state == Signal::Active),
            "call not active"
        );
        ensure!(
            h.voice
                .media
                .set((Mutex::new(Capture::new()?), Mutex::new(Playout::new()?)))
                .is_ok(),
            "already started"
        );
        Ok(json!({"ok":true}))
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_VoiceBridge_capture(
    env: JNIEnv,
    _: JObject,
    id: jlong,
    pcm: JShortArray,
    age_ms: jint,
) -> jint {
    let result = (|| -> Result<()> {
        let h = handle(id)?;
        if !(0..140).contains(&age_ms) {
            return Err(FlowError::Expired.into());
        }
        let now = Instant::now() - Duration::from_millis(age_ms as u64);
        ensure!(env.get_array_length(&pcm)? == FRAME as i32, "frame size");
        let mut samples = [0; FRAME];
        env.get_short_array_region(&pcm, 0, &mut samples)?;
        let result = h
            .voice
            .media()?
            .0
            .lock()
            .unwrap()
            .offer(&h.tx, &samples, now);
        result
    })();
    match result {
        Ok(()) => 0,
        Err(e) => match e.downcast_ref::<FlowError>() {
            Some(FlowError::Expired | FlowError::QueueFull | FlowError::Paced) => 1,
            _ => -1,
        },
    }
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_VoiceBridge_ingress(
    mut env: JNIEnv,
    _: JObject,
    id: jlong,
) -> jint {
    let result = (|| -> Result<()> {
        let h = handle(id)?;
        let native = get_native()?;
        native.runtime.block_on(async {
            if let Ok(packet) = tokio::time::timeout(Duration::from_millis(100), async {
                h.rx.lock().await.recv_with_deadline().await
            })
            .await
            {
                let (packet, deadline) = packet?;
                h.voice
                    .media()?
                    .1
                    .lock()
                    .unwrap()
                    .push(&packet, Instant::now(), deadline)?;
            }
            Ok(())
        })
    })();
    if let Err(e) = result {
        let _ = env.throw_new("java/io/IOException", e.to_string());
        return -1;
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_VoiceBridge_play(
    env: JNIEnv,
    _: JObject,
    id: jlong,
    pcm: JShortArray,
) -> jint {
    let result = (|| -> Result<()> {
        let h = handle(id)?;
        ensure!(env.get_array_length(&pcm)? == FRAME as i32, "frame size");
        let mut samples = [0; FRAME];
        h.voice
            .media()?
            .1
            .lock()
            .unwrap()
            .render(Instant::now(), &mut samples)?;
        env.set_short_array_region(&pcm, 0, &samples)?;
        Ok(())
    })();
    if result.is_ok() {
        0
    } else {
        -1
    }
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_VoiceBridge_rotate(
    mut env: JNIEnv,
    _: JObject,
    id: jlong,
) -> jstring {
    return_json(&mut env, || {
        let h = handle(id)?;
        get_native()?
            .runtime
            .block_on(h.voice.control.rotate_keys())?;
        Ok(json!({"ok":true}))
    })
}

#[no_mangle]
pub extern "system" fn Java_lp2ln_1android_krusalov_org_network_VoiceBridge_stats(
    mut env: JNIEnv,
    _: JObject,
    id: jlong,
) -> jstring {
    return_json(&mut env, || {
        let h = handle(id)?;
        let s = h.tx.metrics();
        let a = h
            .voice
            .media
            .get()
            .map(|m| m.1.lock().unwrap().stats())
            .unwrap_or_else(AudioStats::default);
        Ok(
            json!({"ok":true,"rttMs":h.tx.rtt().as_secs_f64()*1000.,"jitterMs":a.jitter_us/1000.,
          "plc":a.gaps,"played":a.played,"received":a.received,"missingEstimate":if a.received==0 {0} else {(a.last_seq+1).saturating_sub(a.received)},"encodedBytes":a.bytes,"buffer":a.depth,"target":a.target,
          "late":s.expired+a.late,"invalid":s.invalid,"replay":s.replay,"overload":s.overloaded,"queueMaxUs":s.queue_age_us_max}),
        )
    })
}
