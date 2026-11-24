use super::{ensure_file_path, NativeNode};
use anyhow::Result;
use lp2ln_core_v2::storage::{BlockTransferService, ContentId};
use serde_json::Value;
use std::{collections::HashSet, sync::Arc, sync::Mutex, time::Duration};
use tokio::task::JoinSet;

const SOURCE_HEAD_START: Duration = Duration::from_millis(750);

pub(super) fn replica_peer(entry: &Value) -> Option<&str> {
    if entry["status"] == "uploaded" && entry["replicas"] == 1 {
        entry["peer"].as_str()
    } else {
        entry["replicaPeer"].as_str()
    }
}

pub(crate) struct FileSources {
    providers: Vec<String>,
    // Only older invitations without a confirmed replica use round-robin.
    rotate_count: usize,
    slow: Mutex<HashSet<String>>,
}

impl FileSources {
    pub(crate) async fn open(native: &NativeNode, entry: &Value) -> Result<Self> {
        let replica = replica_peer(entry);
        let mut providers = Vec::new();
        let known = entry["providers"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_str);
        for peer in replica
            .into_iter()
            .chain(known)
            .chain(entry["peer"].as_str())
        {
            if peer != native.node.peer_id() && !providers.iter().any(|p| p == peer) {
                providers.push(peer.to_owned());
            }
        }
        ensure_file_path(native, &providers).await?;
        let sessions = native.node.debug_sessions();
        let connected = |peer: &str| {
            sessions.iter().any(|s| {
                s.is_active
                    && s.peer_id.as_deref() == Some(peer)
                    && matches!(s.protocol.as_str(), "tcp" | "quic" | "tunnel_tcp" | "relay")
            })
        };
        providers.sort_by_key(|p| (Some(p.as_str()) != replica, !connected(p)));
        let rotate_count = if replica.is_none() {
            providers.iter().take_while(|p| connected(p)).count()
        } else {
            0
        };
        Ok(Self {
            providers,
            rotate_count,
            slow: Mutex::new(HashSet::new()),
        })
    }

    pub(crate) async fn fetch(
        &self,
        service: &Arc<BlockTransferService>,
        id: ContentId,
        index: usize,
    ) -> Result<Vec<u8>> {
        if let Ok(Some(data)) = service.store.get(&id) {
            return Ok(data);
        }
        let mut providers = self.providers.clone();
        if self.rotate_count > 1 {
            providers[..self.rotate_count].rotate_left(index % self.rotate_count);
        }
        let mut attempts = JoinSet::new();
        // A slow source stays eligible when other sources lack this block.
        {
            let slow = self.slow.lock().unwrap();
            providers.sort_by_key(|peer| slow.contains(peer));
        }
        let mut providers = providers.into_iter().peekable();
        while let Some(peer) = providers.next() {
            let service = service.clone();
            let target = peer.clone();
            let attempt = attempts.spawn(async move { service.fetch_from(&target, id).await });
            // Give each new source a head start while still accepting replies
            // from earlier sources. A missing block advances immediately.
            let wait = async {
                while let Some(result) = attempts.join_next_with_id().await {
                    match result {
                        Ok((_, Ok(data))) => return Some(data),
                        Ok((id, Err(_))) if id == attempt.id() => break,
                        Err(error) if error.id() == attempt.id() => break,
                        _ => {}
                    }
                }
                None
            };
            if providers.peek().is_none() {
                break;
            }
            match tokio::time::timeout(SOURCE_HEAD_START, wait).await {
                Ok(Some(data)) => {
                    // The core removes pending reply entries on reply or its
                    // timeout. Dropping an active fetch would bypass cleanup.
                    attempts.detach_all();
                    return Ok(data);
                }
                Ok(None) => {}
                Err(_) => {
                    self.slow.lock().unwrap().insert(peer);
                }
            }
        }
        while let Some(result) = attempts.join_next().await {
            if let Ok(Ok(data)) = result {
                attempts.detach_all();
                return Ok(data);
            }
        }
        anyhow::bail!("Не удалось получить блок ни из одного источника")
    }
}
