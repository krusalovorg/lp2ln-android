use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use lp2ln_core_v2::node::NodeRuntime;
use lp2ln_core_v2::storage::{content_id_from_hex, content_id_hex, encrypt::generate_key};
use serde_json::{json, Value};
use tokio::sync::broadcast::error::RecvError;

use crate::{sharing, NativeNode};

const MAX_MESSAGES: usize = 500;
const MAX_TEXT_BYTES: usize = 4000;

pub(crate) struct ChatStore {
    path: PathBuf,
    lock: Mutex<()>,
    jobs: Mutex<HashMap<String, tokio::task::JoinHandle<()>>>,
    probes: Mutex<HashMap<String, tokio::task::JoinHandle<()>>>,
    presence: Mutex<HashMap<String, Value>>,
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

fn validate_text(text: &str) -> Result<()> {
    anyhow::ensure!(!text.trim().is_empty(), "Введите сообщение");
    anyhow::ensure!(text.len() <= MAX_TEXT_BYTES, "Сообщение слишком длинное");
    anyhow::ensure!(!text.chars().any(|c| c.is_control() && c != '\n' && c != '\t'), "Недопустимые символы в сообщении");
    Ok(())
}

impl ChatStore {
    pub(crate) fn new(directory: &Path) -> Self {
        let store = Self { path: directory.join("android-chat.json"), lock: Mutex::new(()), jobs: Mutex::new(HashMap::new()),
            probes: Mutex::new(HashMap::new()), presence: Mutex::new(HashMap::new()) };
        let _ = store.edit(|messages| {
            for message in messages {
                if message["status"] == "sending" {
                    message["status"] = json!("failed");
                    message["error"] = json!("Отправка прервана. Нажмите, чтобы повторить.");
                }
            }
            Ok(())
        });
        store
    }

    fn read_unlocked(&self) -> Result<Vec<Value>> {
        if !self.path.exists() { return Ok(Vec::new()); }
        anyhow::ensure!(self.path.metadata()?.len() <= 4 * 1024 * 1024, "История сообщений слишком большая");
        Ok(serde_json::from_slice(&fs::read(&self.path)?)?)
    }

    pub(crate) fn read(&self) -> Result<Vec<Value>> {
        let _guard = self.lock.lock().map_err(|_| anyhow::anyhow!("История сообщений недоступна"))?;
        self.read_unlocked()
    }

    fn edit<T>(&self, update: impl FnOnce(&mut Vec<Value>) -> Result<T>) -> Result<T> {
        let _guard = self.lock.lock().map_err(|_| anyhow::anyhow!("История сообщений недоступна"))?;
        let mut messages = self.read_unlocked()?;
        let result = update(&mut messages)?;
        while messages.len() > MAX_MESSAGES {
            let oldest = messages.iter().position(|m| m["status"] != "sending").unwrap_or(0);
            messages.remove(oldest);
        }
        let temp = self.path.with_extension("tmp");
        let mut file = fs::File::create(&temp)?;
        file.write_all(&serde_json::to_vec(&messages)?)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temp, &self.path)?;
        #[cfg(unix)]
        fs::File::open(self.path.parent().context("Нет каталога чата")?)?.sync_all()?;
        Ok(result)
    }

    pub(crate) fn snapshot(&self) -> Value {
        let presence = self.presence.lock().map(|states| states.values().cloned().map(|mut state| {
            if state["status"] == "online" && now_ms().saturating_sub(state["lastSeen"].as_u64().unwrap_or(0)) > 45_000 {
                state["status"] = json!("unknown");
            }
            state
        }).collect::<Vec<_>>()).unwrap_or_default();
        match self.read() {
            Ok(messages) => json!({"messages":messages,"presence":presence}),
            Err(error) => json!({"messages":[],"presence":presence,"error":error.to_string()}),
        }
    }

    fn mark_online(&self, peer: &str) {
        if let Ok(mut states) = self.presence.lock() {
            states.insert(peer.to_owned(), json!({"peerId":peer,"status":"online","lastSeen":now_ms(),"checkedAt":now_ms()}));
            if states.len() > 128 {
                let oldest = states.iter().min_by_key(|(_, v)| v["lastSeen"].as_u64().unwrap_or(0)).map(|(p, _)| p.clone());
                if let Some(oldest) = oldest { states.remove(&oldest); }
            }
        }
    }

    pub(crate) fn mark_read(&self, peer: &str) -> Result<()> {
        self.edit(|messages| {
            for message in messages {
                if message["peerId"] == peer && message["outgoing"] == false { message["read"] = json!(true); }
            }
            Ok(())
        })
    }

    fn receive(&self, message: &Value, peer: &str) -> Result<()> {
        anyhow::ensure!(message["v"] == 1 && message["kind"] == "chat", "Неизвестный формат сообщения");
        let id = message["id"].as_str().context("Нет ID сообщения")?;
        anyhow::ensure!(content_id_from_hex(id).is_some(), "Неверный ID сообщения");
        let text = message["text"].as_str().context("Нет текста сообщения")?;
        validate_text(text)?;
        self.edit(|messages| {
            if let Some(existing) = messages.iter().find(|m| m["id"] == id && m["peerId"] == peer && m["outgoing"] == false) {
                anyhow::ensure!(existing["text"] == text, "ID сообщения уже использован");
                return Ok(());
            }
            messages.push(json!({"id":id,"peerId":peer,"text":text,"createdAt":now_ms(),
                "outgoing":false,"status":"received","read":false,
                "name":message["name"].as_str().filter(|s| s.chars().count() <= 60 && !s.chars().any(char::is_control))}));
            Ok(())
        })
    }

    fn set_delivery(&self, id: &str, delivered: bool, error: Option<String>) -> Result<()> {
        self.edit(|messages| {
            if let Some(message) = messages.iter_mut().find(|m| m["id"] == id && m["outgoing"] == true) {
                message["status"] = json!(if delivered { "delivered" } else { "failed" });
                message["error"] = json!(error);
            }
            Ok(())
        })
    }

    pub(crate) fn stop(&self) {
        if let Ok(mut jobs) = self.jobs.lock() { for (_, job) in jobs.drain() { job.abort(); } }
        if let Ok(mut probes) = self.probes.lock() { for (_, job) in probes.drain() { job.abort(); } }
        if let Ok(mut presence) = self.presence.lock() { presence.clear(); }
        let _ = self.edit(|messages| {
            for message in messages {
                if message["status"] == "sending" {
                    message["status"] = json!("failed");
                    message["error"] = json!("Соединение прервано. Нажмите, чтобы повторить.");
                }
            }
            Ok(())
        });
    }
}

pub(crate) async fn listen(node: Arc<NodeRuntime>, store: Arc<ChatStore>) {
    let Some(router) = node.router() else { return; };
    let mut incoming = router.subscribe_authenticated();
    loop {
        let received = match incoming.recv().await {
            Ok(packet) => packet,
            Err(RecvError::Lagged(_)) => continue,
            Err(RecvError::Closed) => return,
        };
        let packet = &received.packet;
        if packet.protocol_id != Some(sharing::PROTOCOL) || packet.receiver != node.peer_id()
            || packet.sender == node.peer_id() || packet.data.len() > 8192 { continue; }
        let Ok(message) = serde_json::from_slice::<Value>(&packet.data) else { continue; };
        if message["kind"] == "presence_probe" {
            if message["v"] == 1 && message["id"].as_str().is_some_and(|id| content_id_from_hex(id).is_some()) {
                store.mark_online(&packet.sender);
                let _ = sharing::send_packet(&node, &packet.sender,
                    json!({"kind":"presence_ack","v":1,"id":message["id"]}), 1).await;
            }
            continue;
        }
        if message["kind"] != "chat" || store.receive(&message, &packet.sender).is_err() { continue; }
        store.mark_online(&packet.sender);
        // Save before acknowledging. A retry with the same ID is acknowledged
        // again without creating another bubble in the conversation.
        let _ = sharing::send_packet(&node, &packet.sender,
            json!({"kind":"chat_ack","v":1,"id":message["id"]}), 1).await;
    }
}

async fn deliver(node: &NodeRuntime, peer: &str, message: Value, ack_kind: &str, timeout: Duration) -> Result<()> {
    let mut incoming = node.router().context("Узел не запущен")?.subscribe_authenticated();
    let mut retry = tokio::time::interval(Duration::from_secs(3));
    retry.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut attempt = 0;
    tokio::time::timeout(timeout, async {
        loop {
            let received = tokio::select! {
                _ = retry.tick() => {
                    let _ = sharing::send_packet(node, peer, message.clone(), attempt).await;
                    attempt += 1;
                    continue;
                }
                received = incoming.recv() => received,
            };
            let received = match received {
                Ok(packet) => packet,
                Err(RecvError::Lagged(_)) => continue,
                Err(error) => return Err(anyhow::Error::from(error)),
            };
            let packet = &received.packet;
            if packet.protocol_id != Some(sharing::PROTOCOL) || packet.sender != peer
                || packet.receiver != node.peer_id() || packet.data.len() > 8192 { continue; }
            let Ok(ack) = serde_json::from_slice::<Value>(&packet.data) else { continue; };
            if ack["kind"] == ack_kind && ack["v"] == 1 && ack["id"] == message["id"] { return Ok(()); }
        }
    }).await.context("Получатель не подтвердил сообщение. Откройте оба приложения и повторите отправку.")?
}

pub(crate) fn enqueue(native: &NativeNode, peer: &str, text: &str, retry_id: &str) -> Result<Value> {
    anyhow::ensure!(sharing::valid_peer(peer) && peer != native.node.peer_id(), "Нужен адрес другого узла");
    validate_text(text)?;
    let mut jobs = native.chat_store.jobs.lock().map_err(|_| anyhow::anyhow!("Отправка сообщений недоступна"))?;
    jobs.retain(|_, job| !job.is_finished());
    anyhow::ensure!(jobs.len() < 24, "Дождитесь отправки предыдущих сообщений");
    let id = if retry_id.is_empty() { content_id_hex(&generate_key()) } else { retry_id.to_owned() };
    anyhow::ensure!(!jobs.contains_key(&id), "Сообщение уже отправляется");
    let name = std::fs::read_to_string(std::path::Path::new(&native.data_directory).join("profile-name")).unwrap_or_default();
    let payload = json!({"kind":"chat","v":1,"id":id,"text":text,"name":name});
    anyhow::ensure!(serde_json::to_vec(&payload)?.len() <= 8192, "Сообщение слишком длинное");
    let entry = native.chat_store.edit(|messages| {
        if !retry_id.is_empty() {
            let existing = messages.iter_mut().find(|m| m["id"] == id && m["peerId"] == peer && m["outgoing"] == true)
                .context("Сообщение не найдено")?;
            anyhow::ensure!(existing["status"] == "failed" && existing["text"] == text, "Сообщение нельзя повторить");
            existing["status"] = json!("sending");
            existing["error"] = Value::Null;
            return Ok(existing.clone());
        }
        let message = json!({"id":id,"peerId":peer,"text":text,"createdAt":now_ms(),
            "outgoing":true,"status":"sending","read":true});
        messages.push(message.clone());
        Ok(message)
    })?;
    let node = native.node.clone();
    let store = native.chat_store.clone();
    let peer = peer.to_owned();
    let message_id = id.clone();
    let job = native.runtime.spawn(async move {
        let result = deliver(&node, &peer, payload, "chat_ack", Duration::from_secs(24)).await;
        if result.is_ok() { store.mark_online(&peer); }
        let _ = store.set_delivery(&message_id, result.is_ok(), result.err().map(|error| error.to_string()));
    });
    jobs.insert(id, job);
    Ok(json!({"ok":true,"message":entry}))
}

pub(crate) fn probe(native: &NativeNode, peer: &str) -> Result<()> {
    anyhow::ensure!(sharing::valid_peer(peer) && peer != native.node.peer_id(), "Неверный адрес узла");
    let mut probes = native.chat_store.probes.lock().map_err(|_| anyhow::anyhow!("Проверка доступности недоступна"))?;
    probes.retain(|_, job| !job.is_finished());
    if probes.contains_key(peer) || probes.len() >= 16 { return Ok(()); }
    let started = now_ms();
    {
        let mut states = native.chat_store.presence.lock().map_err(|_| anyhow::anyhow!("Статус узла недоступен"))?;
        if !states.contains_key(peer) && states.len() >= 128 {
            let oldest = states.iter().min_by_key(|(_, v)| v["checkedAt"].as_u64().unwrap_or(0)).map(|(p, _)| p.clone());
            if let Some(oldest) = oldest { states.remove(&oldest); }
        }
        if states.get(peer).is_some_and(|v| started.saturating_sub(v["checkedAt"].as_u64().unwrap_or(0)) < 12_000) {
            return Ok(());
        }
        let state = states.entry(peer.to_owned()).or_insert_with(|| json!({"peerId":peer,"lastSeen":0}));
        state["checkedAt"] = json!(started);
        if started.saturating_sub(state["lastSeen"].as_u64().unwrap_or(0)) > 45_000 { state["status"] = json!("checking"); }
    }
    let peer = peer.to_owned();
    let target = peer.clone();
    let node = native.node.clone();
    let store = native.chat_store.clone();
    probes.insert(peer, native.runtime.spawn(async move {
        let payload = json!({"kind":"presence_probe","v":1,"id":content_id_hex(&generate_key())});
        let online = deliver(&node, &target, payload, "presence_ack", Duration::from_secs(8)).await.is_ok();
        if online { store.mark_online(&target); }
        else if let Ok(mut states) = store.presence.lock() {
            if let Some(state) = states.get_mut(&target) {
                // A real message received during a probe is stronger evidence
                // than the probe timing out on a different route.
                if state["lastSeen"].as_u64().unwrap_or(0) < started { state["status"] = json!("offline"); }
            }
        }
    }));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_are_validated_deduplicated_and_persisted_before_acknowledgment() {
        let root = std::env::temp_dir().join(format!("lp2ln-chat-store-{}", content_id_hex(&generate_key())));
        fs::create_dir(&root).unwrap();
        let store = ChatStore::new(&root);
        let peer = lp2ln_core_v2::crypto::NodeKeypair::generate();
        let mut message = json!({"kind":"chat","v":1,"id":content_id_hex(&generate_key()),"text":"Привет 👋\nКак дела?"});
        store.receive(&message, peer.peer_id()).unwrap();
        store.mark_read(peer.peer_id()).unwrap();
        store.receive(&message, peer.peer_id()).unwrap();
        let history = store.read().unwrap();
        assert_eq!(history.len(), 1);
        assert_eq!(history[0]["read"], true);
        store.mark_online(peer.peer_id());
        assert_eq!(store.snapshot()["presence"][0]["status"], "online");
        store.presence.lock().unwrap().get_mut(peer.peer_id()).unwrap()["lastSeen"] = json!(now_ms() - 60_000);
        assert_eq!(store.snapshot()["presence"][0]["status"], "unknown", "An old contact must not stay online indefinitely");
        message["text"] = json!("different contents with the same message ID");
        assert!(store.receive(&message, peer.peer_id()).is_err());
        message["id"] = json!("invalid-id");
        assert!(store.receive(&message, peer.peer_id()).is_err());
        assert!(validate_text(" \n ").is_err());
        assert!(validate_text("hello\0world").is_err());
        assert!(validate_text(&"x".repeat(MAX_TEXT_BYTES + 1)).is_err());
        store.edit(|messages| {
            messages.push(json!({"id":"pending","outgoing":true,"status":"sending","text":"pending message"}));
            Ok(())
        }).unwrap();
        drop(store);
        let reopened = ChatStore::new(&root);
        assert_eq!(reopened.read().unwrap()[1]["status"], "failed");
        assert_eq!(reopened.read().unwrap()[0]["text"], "Привет 👋\nКак дела?");
        drop(reopened);
        assert!(root.canonicalize().unwrap().starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(root).unwrap();
    }
}
