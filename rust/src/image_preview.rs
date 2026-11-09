//! Small JPEGs encrypted with their own key; originals keep their existing format.
use anyhow::{Context, Result};
use lp2ln_core_v2::storage::{content_id_from_hex, content_id_hex,
    encrypt::{decrypt_chunk, encrypt_chunk, generate_key}};
use serde_json::{json, Value};
use std::{fs, path::Path};
use crate::NativeNode;

pub(crate) const MAX_THUMBNAIL: usize = 24 * 1024;

pub(crate) fn validate_capability(value: &Value) -> Result<()> {
    for field in ["id", "key"] {
        content_id_from_hex(value[field].as_str().context("Invalid thumbnail capability")?)
            .context("Invalid thumbnail capability")?;
    }
    Ok(())
}

fn jpeg(bytes: &[u8]) -> Result<()> {
    anyhow::ensure!(bytes.len() <= MAX_THUMBNAIL && bytes.starts_with(&[0xff, 0xd8])
        && bytes.ends_with(&[0xff, 0xd9]), "Invalid thumbnail");
    Ok(())
}

// The optional private staging sidecar is deleted together with the source.
pub(crate) fn import(native: &NativeNode, path: &str, entry: &mut Value) -> Result<()> {
    let sidecar = format!("{path}.preview");
    if !Path::new(&sidecar).exists() { return Ok(()); }
    anyhow::ensure!(fs::metadata(&sidecar)?.len() <= MAX_THUMBNAIL as u64, "Thumbnail too large");
    let bytes = fs::read(sidecar)?;
    jpeg(&bytes)?;
    let service = native.node.block_transfer_service().context("Storage unavailable")?;
    let key = generate_key();
    let id = service.store.put(&encrypt_chunk(&key, 0, &bytes)?)?;
    entry["image"] = json!({"id":content_id_hex(&id),"key":content_id_hex(&key)});
    entry["imageBlocks"] = json!([content_id_hex(&id)]);
    Ok(())
}

pub(crate) fn fetch(native: &NativeNode, id: &str, destination: &str) -> Result<Value> {
    let entry = native.file_index.read()?.into_iter().find(|e| e["id"] == id).context("File not found")?;
    let capability = &entry["image"];
    validate_capability(capability)?;
    let mut providers: Vec<String> = entry["providers"].as_array().into_iter().flatten()
        .filter_map(|v| v.as_str().map(str::to_owned)).collect();
    if let Some(peer) = entry["peer"].as_str() { providers.insert(0, peer.to_owned()); }
    native.runtime.block_on(async {
        let service = native.node.block_transfer_service().context("Storage unavailable")?;
        let id = content_id_from_hex(capability["id"].as_str().unwrap()).unwrap();
        let key = content_id_from_hex(capability["key"].as_str().unwrap()).unwrap();
        let sealed = service.fetch_from_providers(id, &providers).await?;
        anyhow::ensure!(sealed.len() <= MAX_THUMBNAIL + 64, "Thumbnail too large");
        let bytes = decrypt_chunk(&key, 0, &sealed)?;
        jpeg(&bytes)?;
        service.store.put(&sealed)?;
        let temporary = format!("{destination}.tmp");
        fs::write(&temporary, bytes)?;
        fs::rename(temporary, destination)?;
        Ok(json!({"ok":true}))
    })
}
