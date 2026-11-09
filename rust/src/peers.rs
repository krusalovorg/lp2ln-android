use std::collections::HashSet;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use lp2ln_core_v2::node::options::BootstrapNode;
use lp2ln_core_v2::node::NodeRuntime;
use lp2ln_core_v2::topology::{parse_observed_addr_line, verify_descriptor, NodeDescriptor};
use lp2ln_core_v2::types::PeerId;
use serde_json::{json, Value};

const MAX_PEERS: usize = 128;
const MAX_ADDRESSES: usize = 256;
const MAX_AGE_MS: u64 = 30 * 24 * 60 * 60 * 1000;

pub struct PeerAddressBook {
    path: PathBuf,
    descriptors: Vec<NodeDescriptor>,
    dirty: bool,
    pub error: Option<String>,
}

impl PeerAddressBook {
    pub fn open(directory: &Path, own_peer: &str) -> Self {
        let path = directory.join("known-peers.json");
        let loaded = (|| -> Result<Vec<NodeDescriptor>> {
            if !path.exists() { return Ok(Vec::new()); }
            anyhow::ensure!(path.metadata()?.len() <= 2 * 1024 * 1024, "Peer cache too large");
            Ok(serde_json::from_slice(&std::fs::read(&path)?)?)
        })();
        let (descriptors, error) = match loaded {
            Ok(entries) => (entries, None),
            Err(error) => (Vec::new(), Some(format!("Не удалось прочитать адреса узлов: {error}"))),
        };
        let mut book = Self { path, descriptors: Vec::new(), dirty: false, error };
        book.merge(descriptors, own_peer, lp2ln_core_v2::topology::now_ms());
        book
    }

    fn merge(&mut self, incoming: Vec<NodeDescriptor>, own_peer: &str, now: u64) -> bool {
        let previous: Vec<_> = self.descriptors.iter().map(|d| d.signature.clone()).collect();
        let mut entries = std::mem::take(&mut self.descriptors);
        for descriptor in incoming {
            if descriptor.peer_id == own_peer || descriptor.observed_addrs.len() > 32
                || descriptor.timestamp_ms > now.saturating_add(5 * 60 * 1000)
                || now.saturating_sub(descriptor.timestamp_ms) > MAX_AGE_MS
                || descriptor.observed_addrs.iter().all(|line| usable_address(line).is_none())
            { continue; }
            if entries.iter().any(|d| d.signature == descriptor.signature) { continue; }
            if verify_descriptor(&descriptor).is_ok() { entries.push(descriptor); }
        }
        entries.retain(|d| d.peer_id != own_peer
            && now.saturating_sub(d.timestamp_ms) <= MAX_AGE_MS);
        // A restarted peer may publish a lower version; its newer signed address
        // is still a useful connection hint. It does not restore expired status.
        entries.sort_by(|a, b| b.timestamp_ms.cmp(&a.timestamp_ms)
            .then_with(|| b.version.cmp(&a.version)).then_with(|| a.peer_id.cmp(&b.peer_id)));
        let mut seen = HashSet::new();
        entries.retain(|d| seen.insert(d.peer_id.clone()));
        entries.truncate(MAX_PEERS);
        let changed = previous != entries.iter().map(|d| d.signature.clone()).collect::<Vec<_>>();
        self.descriptors = entries;
        self.dirty |= changed;
        changed
    }

    pub fn remember(&mut self, incoming: Vec<NodeDescriptor>, own_peer: &str) -> Result<()> {
        self.merge(incoming, own_peer, lp2ln_core_v2::topology::now_ms());
        if self.dirty {
            let temp = self.path.with_extension("tmp");
            std::fs::write(&temp, serde_json::to_vec(&self.descriptors)?)?;
            std::fs::rename(&temp, &self.path).context("Сохранение адресов узлов")?;
            self.error = None;
            self.dirty = false;
        }
        Ok(())
    }

    pub fn targets(&self) -> Vec<BootstrapNode> {
        let mut seen = HashSet::new();
        let mut targets = Vec::new();
        // TCP first: many mobile networks cannot accept incoming UDP traffic.
        for protocol in ["tcp", "udp"] {
            for descriptor in &self.descriptors {
                for line in &descriptor.observed_addrs {
                    if let Some((transport, addr)) = usable_address(line) {
                        if transport == protocol && seen.insert((transport.clone(), addr)) {
                            targets.push(BootstrapNode::new(addr, [transport],
                                Some(PeerId::from(descriptor.peer_id.as_str()))));
                            if targets.len() == MAX_ADDRESSES { return targets; }
                        }
                    }
                }
            }
        }
        targets
    }

    pub fn snapshot(&self) -> Value {
        let targets = self.targets();
        json!({
            "addresses": targets.iter().map(|t| json!({
                "peerId": t.peer_id_hint.as_ref().map(|p| p.as_str()),
                "address": t.addr.to_string(), "protocol": t.protocols[0],
            })).collect::<Vec<_>>(),
            "error": self.error,
        })
    }
}

fn usable_address(line: &str) -> Option<(String, SocketAddr)> {
    let (protocol, addr) = parse_observed_addr_line(line)?;
    let ip = addr.ip();
    if !matches!(protocol.as_str(), "tcp" | "udp") || !ip.is_ipv4()
        || addr.port() == 0 || ip.is_unspecified() || ip.is_loopback()
        || ip.is_multicast() || addr.ip().to_string() == "255.255.255.255"
    { return None; }
    Some((protocol, addr))
}

pub fn add_primary(targets: &mut Vec<BootstrapNode>, primary: SocketAddr) {
    if !targets.iter().any(|t| t.addr == primary && t.protocols.iter().any(|p| p == "tcp")) {
        targets.insert(0, BootstrapNode::new(primary, ["tcp"], None));
    }
}

pub async fn maintain(node: Arc<NodeRuntime>, book: Arc<Mutex<PeerAddressBook>>) {
    let mut cursor = 0usize;
    let mut delay = Duration::from_secs(5);
    let mut retry_at = Instant::now() + Duration::from_secs(10);
    loop {
        tokio::time::sleep(Duration::from_secs(5)).await;
        let targets = {
            let Ok(mut book) = book.lock() else { return; };
            if let Err(error) = book.remember(node.peer_catalog().descriptors(), node.peer_id()) {
                book.error = Some(format!("Не удалось сохранить адреса узлов: {error}"));
            }
            book.targets()
        };
        for target in &targets {
            if let Some(peer) = &target.peer_id_hint {
                node.register_known_peer_addr(peer.clone(), target.protocols[0].clone(), target.addr);
            }
        }
        if node.active_peer_count() > 0 {
            delay = Duration::from_secs(5);
            retry_at = Instant::now() + Duration::from_secs(10);
            continue;
        }
        if targets.is_empty() || Instant::now() < retry_at { continue; }
        let target = &targets[cursor % targets.len()];
        cursor = cursor.wrapping_add(1);
        let _ = tokio::time::timeout(Duration::from_secs(5), async {
            let session = node.connect(&target.protocols[0], target.addr).await?;
            // Cached addresses are hints. Require the advertised identity to
            // authenticate again instead of trusting a possibly reassigned IP.
            for _ in 0..20 {
                let current = node.debug_sessions().into_iter()
                    .find(|s| s.session_id == session.as_str());
                if let Some(peer) = current.and_then(|s| s.peer_id) {
                    if target.peer_id_hint.as_ref().is_some_and(|p| p.as_str() == peer) {
                        return Ok::<_, anyhow::Error>(());
                    }
                    break;
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
            node.disconnect_session(session.as_str()).await?;
            anyhow::bail!("Cached peer did not authenticate")
        }).await;
        retry_at = Instant::now() + delay;
        delay = (delay * 2).min(Duration::from_secs(60));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lp2ln_core_v2::crypto::NodeKeypair;
    use lp2ln_core_v2::topology::{sign_descriptor, NodeCapabilities, NodeDynamicStatus};

    fn descriptor(key: &NodeKeypair, addresses: &[&str], timestamp: u64) -> NodeDescriptor {
        let mut d = NodeDescriptor::new_unsigned(key.peer_id(), NodeCapabilities::default(),
            NodeDynamicStatus::default(), addresses.iter().map(|a| a.to_string()).collect(), 1, 1);
        d.timestamp_ms = timestamp;
        sign_descriptor(&mut d, key.signing_key()).unwrap();
        d
    }

    #[test]
    fn signed_addresses_survive_restart_and_expired_descriptor_ttl() {
        let directory = std::env::temp_dir().join(format!("lp2ln-peers-{}", NodeKeypair::generate().peer_id()));
        std::fs::create_dir(&directory).unwrap();
        let key = NodeKeypair::generate();
        let d = descriptor(&key, &["tcp:192.0.2.10:18080", "tcp:192.0.2.10:18080", "udp:192.0.2.10:18180"],
            lp2ln_core_v2::topology::now_ms() - 60_000);
        let mut book = PeerAddressBook::open(&directory, "self");
        book.remember(vec![d.clone(), d], "self").unwrap();
        let reopened = PeerAddressBook::open(&directory, "self");
        assert_eq!(reopened.targets().len(), 2);
        assert_eq!(reopened.targets()[0].peer_id_hint.as_ref().unwrap().as_str(), key.peer_id());
        assert_eq!(reopened.targets()[0].protocols, vec!["tcp"]);
        std::fs::remove_file(directory.join("known-peers.json")).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn rejects_forged_old_self_and_unusable_addresses() {
        let now = lp2ln_core_v2::topology::now_ms();
        let key = NodeKeypair::generate();
        let mut forged = descriptor(&key, &["tcp:192.0.2.10:80"], now);
        forged.observed_addrs = vec!["tcp:192.0.2.11:80".into()];
        let old = descriptor(&key, &["tcp:192.0.2.10:80"], now - MAX_AGE_MS - 1);
        let own = NodeKeypair::generate();
        let own_descriptor = descriptor(&own, &["tcp:192.0.2.12:80"], now);
        let mut book = PeerAddressBook { path: PathBuf::new(), descriptors: Vec::new(), dirty: false, error: None };
        book.merge(vec![forged, old, own_descriptor], own.peer_id(), now);
        assert!(book.targets().is_empty());
        for line in ["tcp:0.0.0.0:80", "tcp:127.0.0.1:80", "tcp:192.0.2.1:0", "udp:224.0.0.1:80", "bad"] {
            assert!(usable_address(line).is_none(), "{line}");
        }
    }

    #[test]
    fn primary_does_not_duplicate_discovered_address() {
        let key = NodeKeypair::generate();
        let d = descriptor(&key, &["tcp:192.0.2.10:18080"], lp2ln_core_v2::topology::now_ms());
        let mut book = PeerAddressBook { path: PathBuf::new(), descriptors: Vec::new(), dirty: false, error: None };
        book.merge(vec![d], "self", lp2ln_core_v2::topology::now_ms());
        let mut targets = book.targets();
        add_primary(&mut targets, "192.0.2.10:18080".parse().unwrap());
        assert_eq!(targets.len(), 1);
        assert!(targets[0].peer_id_hint.is_some());
        add_primary(&mut targets, "192.0.2.20:18080".parse().unwrap());
        assert_eq!(targets.len(), 2);
    }

    #[test]
    #[ignore = "requires LP2LN_TEST_BOOTSTRAP; connects to a live network"]
    fn learned_nodes_reconnect_when_bootstrap_dns_is_unavailable() {
        let bootstrap = std::env::var("LP2LN_TEST_BOOTSTRAP").unwrap();
        let directory = std::env::temp_dir().join(format!("lp2ln-discovery-test-{}", NodeKeypair::generate().peer_id()));
        std::fs::create_dir(&directory).unwrap();
        let first = crate::start_node(directory.to_string_lossy().into_owned(), bootstrap).unwrap();
        let own_id = first["peerId"].as_str().unwrap().to_owned();
        let native = crate::files::get_native().unwrap();
        native.runtime.block_on(async {
            for _ in 0..120 {
                if native.known_peers.lock().unwrap().targets().len() >= 4 { return; }
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
            panic!("The live network did not announce both nodes");
        });
        crate::stop_node().unwrap();
        drop(native);
        let restarted = crate::start_node(directory.to_string_lossy().into_owned(), "missing.invalid:18080".into()).unwrap();
        assert_eq!(restarted["peerId"], own_id);
        assert!(restarted["bootstrapWarning"].is_string());
        let native = crate::files::get_native().unwrap();
        native.runtime.block_on(async {
            for _ in 0..60 {
                if native.node.active_peer_count() > 0 { return; }
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
            panic!("Cached peers did not restore connectivity without bootstrap DNS");
        });
        crate::stop_node().unwrap();
        drop(native);
        assert!(directory.canonicalize().unwrap().starts_with(std::env::temp_dir().canonicalize().unwrap()));
        std::fs::remove_dir_all(directory).unwrap();
    }
}
