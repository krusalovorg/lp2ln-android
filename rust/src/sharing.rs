use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use lp2ln_core_v2::crypto::peer_cache::PeerCryptoCache;
use lp2ln_core_v2::node::NodeRuntime;
use lp2ln_core_v2::packet::Packet;
use lp2ln_core_v2::storage::{content_id_from_hex, content_id_hex, encrypt::generate_key};
use serde_json::{json, Value};
use tokio::sync::broadcast::error::RecvError;

use crate::files::FileIndex;

const PROTOCOL: u16 = 0x4c46;

pub(crate) fn valid_peer(peer: &str) -> bool {
    peer.len() == 66 && PeerCryptoCache::new().verifying_key(peer).is_ok()
}

async fn send_packet(node: &NodeRuntime, recipient: &str, payload: Value, attempt: usize) -> Result<()> {
    let router = node.router().context("Узел не запущен")?;
    let packet = Packet {
        signature: None, sender: node.peer_id().to_owned(), receiver: recipient.to_owned(),
        data: serde_json::to_vec(&payload)?, nodes: Vec::new(), max_hops: 8,
        request_id: None, protocol_id: Some(PROTOCOL), chunk_stream_id: None,
        chunk_index: None, total_chunks: None,
    };
    // A UDP session may exist after LAN discovery/hole punching while its NAT
    // path silently drops data. Prefer an actual reliable session, then rotate
    // reliable neighbors on retries instead of choosing that same UDP path.
    let sessions = node.debug_sessions();
    let mut reliable: Vec<_> = sessions.iter().filter(|s| s.is_active && s.peer_id.is_some()
        && matches!(s.protocol.as_str(), "tcp" | "quic" | "tunnel_tcp" | "relay")).collect();
    reliable.sort_by(|a, b| a.session_id.cmp(&b.session_id));
    let direct = reliable.iter().find(|s| s.peer_id.as_deref() == Some(recipient));
    let selected = if attempt == 0 { direct.copied() }
        else if reliable.is_empty() { None } else { Some(reliable[(attempt - 1) % reliable.len()]) };
    let result = if let Some(session) = selected {
        tokio::time::timeout(Duration::from_secs(3),
            router.send_to_session(session.session_id.clone().into(), packet)).await
    } else {
        tokio::time::timeout(Duration::from_secs(3), router.send_to_peer(recipient.into(), packet, None)).await
    };
    result.context("Не удалось отправить запрос по доступному соединению")??;
    Ok(())
}

fn received_entry(message: &Value, sender: &str) -> Result<Value> {
    anyhow::ensure!(message["v"] == 1, "Неизвестная версия приглашения");
    let id = message["file"]["id"].as_str().context("Нет ID файла")?;
    let key = message["file"]["key"].as_str().context("Нет ключа файла")?;
    content_id_from_hex(id).context("Неверный ID файла")?;
    content_id_from_hex(key).context("Неверный ключ файла")?;
    let size = message["file"]["size"].as_u64().context("Неверный размер файла")?;
    anyhow::ensure!(size <= 100 * 1024 * 1024, "Файл слишком большой");
    let providers = message["file"]["providers"].as_array().context("Нет источников")?;
    anyhow::ensure!(providers.len() <= 8, "Слишком много источников");
    let mut sources: Vec<String> = providers.iter().filter_map(|v| v.as_str())
        .filter(|peer| valid_peer(peer)).map(str::to_owned).collect();
    if !sources.iter().any(|peer| peer == sender) { sources.push(sender.to_owned()); }
    sources.dedup();
    let name = message["file"]["name"].as_str().context("Нет имени файла")?
        .chars().filter(|c| !c.is_control()).take(255).collect::<String>();
    let image = &message["file"]["image"];
    if !image.is_null() { crate::image_preview::validate_capability(image)?; }
    Ok(json!({"id":id,"key":key,"name":name,"size":size,"providers":sources,
        "receivedFrom":sender,"status":"offered","replicas":0,"blocks":[],"image":image}))
}

pub(crate) async fn listen(node: Arc<NodeRuntime>, index: Arc<FileIndex>) {
    let Some(router) = node.router() else { return; };
    // Only consume packets that passed signature checks AND decryption. The
    // general router subscription also contains unauthenticated raw packets.
    let mut incoming = router.subscribe_authenticated();
    loop {
        let packet = match incoming.recv().await {
            Ok(packet) => packet,
            Err(RecvError::Lagged(_)) => continue,
            Err(RecvError::Closed) => return,
        };
        let packet = &packet.packet;
        if packet.protocol_id != Some(PROTOCOL) || packet.receiver != node.peer_id()
            || packet.data.len() > 8192 || packet.sender == node.peer_id()
        { continue; }
        let Ok(message) = serde_json::from_slice::<Value>(&packet.data) else { continue; };
        if message["kind"] != "offer" { continue; }
        let Some(nonce) = message["nonce"].as_str() else { continue; };
        if content_id_from_hex(nonce).is_none() { continue; }
        let Ok(entry) = received_entry(&message, &packet.sender) else { continue; };
        let id = entry["id"].clone();
        if index.save(entry, true).is_err() { continue; }
        let _ = send_packet(&node, &packet.sender, json!({"kind":"ack","v":1,
            "nonce":nonce,"id":id,
            "path":if packet.nodes.is_empty() { "direct" } else { "relay" }}), 1).await;
    }
}

pub(crate) async fn send(node: &NodeRuntime, recipient: &str, capability: Value) -> Result<Value> {
    anyhow::ensure!(valid_peer(recipient), "Неверный Peer ID получателя");
    anyhow::ensure!(recipient != node.peer_id(), "Это ваш собственный QR");
    anyhow::ensure!(node.active_peer_count() > 0, "Сначала подключитесь к сети");
    let mut incoming = node.router().context("Узел не запущен")?.subscribe_authenticated();
    let nonce = content_id_hex(&generate_key());
    let id = capability["id"].clone();
    let offer = json!({"kind":"offer","v":1,"nonce":nonce,"file":capability});
    let mut retry = tokio::time::interval(Duration::from_secs(3));
    retry.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut attempt = 0;
    let result = tokio::time::timeout(Duration::from_secs(24), async {
        loop {
            let received = tokio::select! {
                _ = retry.tick() => {
                    // Each retry gets a fresh secure sequence, but keeps its
                    // logical nonce. Duplicate offers are acknowledged safely.
                    let _ = send_packet(node, recipient, offer.clone(), attempt).await;
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
            if packet.protocol_id != Some(PROTOCOL) || packet.sender != recipient
                || packet.receiver != node.peer_id() || packet.data.len() > 8192
            { continue; }
            let Ok(ack) = serde_json::from_slice::<Value>(&packet.data) else { continue; };
            if ack["kind"] == "ack" && ack["v"] == 1 && ack["nonce"] == nonce && ack["id"] == id {
                return Ok(json!({"ok":true,"path":ack["path"]}));
            }
        }
    }).await;
    match result {
        Ok(result) => result,
        Err(_) => anyhow::bail!("Телефон друга не подтвердил получение ключа после повторных попыток. Оставьте оба приложения открытыми и повторите отправку."),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lp2ln_core_v2::crypto::NodeKeypair;

    #[test]
    fn validates_incoming_capability_without_exposing_or_fetching_contents() {
        let sender = NodeKeypair::generate();
        let mut message = json!({"v":1,"file":{"id":content_id_hex(&generate_key()),
            "key":content_id_hex(&generate_key()),"name":"hello.txt","size":42,"providers":[]}});
        let entry = received_entry(&message, sender.peer_id()).unwrap();
        assert_eq!(entry["status"], "offered");
        assert_eq!(entry["receivedFrom"], sender.peer_id());
        assert_eq!(entry["providers"][0], sender.peer_id());
        message["file"]["size"] = json!(101 * 1024 * 1024);
        assert!(received_entry(&message, sender.peer_id()).is_err());
        message["file"]["size"] = json!(42);
        message["file"]["key"] = json!("bad-key");
        assert!(received_entry(&message, sender.peer_id()).is_err());
    }
}
