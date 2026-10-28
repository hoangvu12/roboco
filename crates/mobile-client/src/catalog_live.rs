//! The live catalog: `ListHarnesses` / `ListModels` per engine, a disk cache
//! under the engine's cache directory, and the mid-turn-steering memory the
//! composer reads.
//!
//! The static half (the curated fallback lists, `normalize_models`,
//! `default_reasoning`, the label functions and the record shapes) is
//! ticket 07's `catalog.rs` — this module re-exports those and adds the
//! live layer on top, exactly as ticket 08's plan ("they sit on top of
//! 07's static `catalog.rs`") describes.
//!
//! Decoding is deliberately lenient: `id`, `steeringMode` and
//! `reasoningLevels` are plain strings, so a harness this app version has
//! never heard of survives the round trip (`HarnessDescriptor` lives in
//! `roboco-engine`, which mobile must not link).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use crate::engines::EngineHandle;
use crate::{ClientError, lock};

use crate::ClientInner;

// The static catalog and its record shapes are 07's; one implementation.
pub use crate::catalog::{
    HarnessInfo, ModelInfo, ModelOption, ModelOptionChoice, default_reasoning, fallback_harnesses,
    fallback_models, learn_labels, normalize_models,
};

// ── the client-side catalog state ──────────────────────────────────────────

/// Per-client live catalog: the engines' harness lists (for
/// `mid_turn_steering`). Labels learned from live model lists live in
/// 07's global `LEARNED_LABELS` (rows pick them up on the next derive).
#[derive(Default)]
pub(crate) struct Catalog {
    pub(crate) state: Mutex<CatalogState>,
}

#[derive(Default)]
pub(crate) struct CatalogState {
    /// Engine id → its harness list.
    pub(crate) harnesses: HashMap<String, Vec<HarnessInfo>>,
}

/// The chat harness's mid-turn steering flag: known once the engine's live
/// harness list has been fetched (`None` until then).
pub(crate) fn mid_turn_steering(
    inner: &Arc<ClientInner>,
    engine_id: &str,
    harness: &str,
) -> Option<bool> {
    let state = lock(&inner.catalog.state);
    state
        .harnesses
        .get(engine_id)?
        .iter()
        .find(|h| h.id == harness)
        .and_then(HarnessInfo::mid_turn_steering)
}

// ── device-id resolution ───────────────────────────────────────────────────

/// Resolve the `device_id` the platform holds (07's `DeviceView` /
/// `SessionRow` field) to its engine: a scoped id names its engine, a bare
/// engine device id matches the engine pinned to it, and a raw engine id
/// works directly.
pub(crate) fn resolve_engine(
    inner: &Arc<ClientInner>,
    device_id: &str,
) -> Result<EngineHandle, ClientError> {
    let client = crate::Client::from_inner(inner.clone());
    if let Ok(scoped) = roboco_engine_client::registry::ScopedId::parse(device_id) {
        if let Ok(handle) = client.engine_handle(&scoped.engine.0) {
            return Ok(handle);
        }
    }
    let by_device: Vec<String> = {
        let state = lock(&inner.engines);
        state
            .entries
            .iter()
            .filter(|entry| {
                entry
                    .info
                    .as_ref()
                    .map(|info| info.device_id.as_str())
                    .or(entry.paired.device_id.as_deref())
                    == Some(device_id)
            })
            .map(|entry| entry.paired.engine_id.clone())
            .collect()
    };
    for engine_id in by_device {
        if let Ok(handle) = client.engine_handle(&engine_id) {
            return Ok(handle);
        }
    }
    client.engine_handle(device_id)
}

// ── the live fetches ───────────────────────────────────────────────────────

impl crate::Client {
    /// The engine's harness catalog: live → disk cache → the static list;
    /// never errors. `mock` is dropped unless the client was built with
    /// `show_mock_harness`.
    pub async fn list_harnesses(&self, device_id: &str) -> Vec<HarnessInfo> {
        let inner = self.inner_arc();
        let Ok(handle) = resolve_engine(&inner, device_id) else {
            return fallback_harnesses();
        };
        let engine_id = handle.engine_id().to_owned();
        match list_harnesses_live(&handle).await {
            Ok(list) => store_harnesses(&inner, engine_id, list),
            Err(_) => {
                let cached = read_catalog_cache::<Vec<HarnessInfo>>(
                    &inner,
                    &engine_id,
                    "harnesses.json",
                )
                .unwrap_or_default();
                if !cached.is_empty() {
                    lock(&inner.catalog.state)
                        .harnesses
                        .entry(engine_id)
                        .or_insert_with(|| cached.clone());
                    cached
                } else {
                    fallback_harnesses()
                }
            }
        }
    }

    /// Offer `harness` on `device_id`'s engine or not (`SetHarnessEnabled`,
    /// wave-10 40 — upstream #966's `set_harness_enabled`). The engine
    /// refuses the last enabled agent and one that isn't installed; its
    /// reply is the fresh catalog, stored like a live list so New session
    /// and the pickers offer the change at once.
    pub async fn set_harness_enabled(
        &self,
        device_id: &str,
        harness: &str,
        enabled: bool,
    ) -> Result<Vec<HarnessInfo>, ClientError> {
        let inner = self.inner_arc();
        let handle = resolve_engine(&inner, device_id)?;
        let engine_id = handle.engine_id().to_owned();
        let value = handle
            .call(
                roboco_rpc::methods::SET_HARNESS_ENABLED,
                serde_json::json!({ "harness": harness, "enabled": enabled }),
            )
            .await?;
        let list: Vec<HarnessInfo> = serde_json::from_value(value).map_err(|error| ClientError::HostError {
            message: format!("undecodable harness catalog: {error}"),
        })?;
        Ok(store_harnesses(&inner, engine_id, list))
    }

    /// The engine's model catalog for `harness`: live (normalized, labels
    /// learned) → disk cache → the static list; never errors.
    pub async fn list_models(&self, device_id: &str, harness: &str) -> Vec<ModelInfo> {
        let inner = self.inner_arc();
        let Ok(handle) = resolve_engine(&inner, device_id) else {
            return fallback_models(harness);
        };
        let engine_id = handle.engine_id().to_owned();
        let file = format!("models-{harness}.json");
        match list_models_live(&handle, harness).await {
            Ok(models) if !models.is_empty() => {
                let models = normalize_models(harness, models);
                learn_labels(harness, &models);
                write_catalog_cache(&inner, &engine_id, &file, &models);
                models
            }
            _ => {
                let cached = read_catalog_cache::<Vec<ModelInfo>>(&inner, &engine_id, &file)
                    .unwrap_or_default();
                if !cached.is_empty() {
                    cached
                } else {
                    fallback_models(harness)
                }
            }
        }
    }
}

/// A live catalog in: the mock filtered (or re-offered under
/// `show_mock_harness`), the disk cache and the in-memory catalog
/// refreshed, the composers' steering flags recomputed.
fn store_harnesses(
    inner: &Arc<ClientInner>,
    engine_id: String,
    mut list: Vec<HarnessInfo>,
) -> Vec<HarnessInfo> {
    list.retain(|h| h.id != "mock" || inner.config.show_mock_harness);
    if inner.config.show_mock_harness {
        // The engine marks the mock not-offered (a test rig must never
        // displace a real agent); the debug flag re-offers it — "debug
        // builds and tests set it so the mock harness is offered".
        for harness in list.iter_mut().filter(|h| h.id == "mock") {
            harness.enabled = Some(true);
        }
    }
    write_catalog_cache(inner, &engine_id, "harnesses.json", &list);
    lock(&inner.catalog.state).harnesses.insert(engine_id, list.clone());
    // Composers recompute their mid-turn-steering flags.
    crate::session::recompute_all_composers(inner);
    list
}

async fn list_harnesses_live(handle: &EngineHandle) -> Result<Vec<HarnessInfo>, ClientError> {
    let value = handle
        .call(roboco_rpc::methods::LIST_HARNESSES, serde_json::Value::Null)
        .await?;
    let list: Vec<HarnessInfo> = serde_json::from_value(value).map_err(|error| {
        ClientError::HostError {
            message: format!("undecodable harness catalog: {error}"),
        }
    })?;
    Ok(list)
}

async fn list_models_live(
    handle: &EngineHandle,
    harness: &str,
) -> Result<Vec<ModelInfo>, ClientError> {
    let value = handle
        .call(
            roboco_rpc::methods::LIST_MODELS,
            serde_json::json!({ "harness": harness }),
        )
        .await?;
    let models: Vec<ModelInfo> = serde_json::from_value(value).map_err(|error| {
        ClientError::HostError {
            message: format!("undecodable model catalog: {error}"),
        }
    })?;
    Ok(models)
}

// ── the disk cache (`<engine cache>/catalog/*.json`) ───────────────────────

fn catalog_dir(inner: &Arc<ClientInner>, engine_id: &str) -> Option<PathBuf> {
    let state = lock(&inner.engines);
    let entry = state
        .entries
        .iter()
        .find(|entry| entry.paired.engine_id == engine_id)?;
    Some(entry.cache.engine_dir().join("catalog"))
}

fn read_catalog_cache<T: serde::de::DeserializeOwned>(
    inner: &Arc<ClientInner>,
    engine_id: &str,
    file: &str,
) -> Option<T> {
    let dir = catalog_dir(inner, engine_id)?;
    serde_json::from_slice(&std::fs::read(dir.join(file)).ok()?).ok()
}

fn write_catalog_cache<T: serde::Serialize>(
    inner: &Arc<ClientInner>,
    engine_id: &str,
    file: &str,
    value: &T,
) {
    let Some(dir) = catalog_dir(inner, engine_id) else {
        return;
    };
    let path = dir.join(file);
    let bytes = serde_json::to_vec(value).ok();
    crate::runtime::shared().spawn_blocking(move || {
        let Some(bytes) = bytes else {
            return;
        };
        if std::fs::create_dir_all(&dir).is_err() {
            return;
        }
        // Atomic: a partial file would poison the fallback path.
        let staging = dir.join(format!(
            "{}.tmp",
            path.file_name().and_then(|n| n.to_str()).unwrap_or("catalog")
        ));
        if std::fs::write(&staging, &bytes).is_ok() {
            let _ = std::fs::rename(&staging, &path);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mid_turn_steering_needs_step_boundary_and_support() {
        let mut step = HarnessInfo {
            id: "mock".into(),
            label: "Mock".into(),
            supports_steering: Some(true),
            steering_mode: Some("step-boundary".into()),
            reasoning_levels: Vec::new(),
            installed: true,
            enabled: None,
        };
        assert_eq!(step.mid_turn_steering(), Some(true));
        step.steering_mode = Some("turn-boundary".into());
        assert_eq!(step.mid_turn_steering(), Some(false));
        step.supports_steering = None;
        assert_eq!(step.mid_turn_steering(), None);
        // offered: installed and not disabled.
        let mut off = HarnessInfo {
            id: "codex".into(),
            label: "Codex".into(),
            supports_steering: None,
            steering_mode: None,
            reasoning_levels: Vec::new(),
            installed: true,
            enabled: Some(false),
        };
        assert!(!off.offered());
        off.enabled = None;
        assert!(off.offered());
    }

    #[test]
    fn the_static_fallback_is_the_default_enabled_pair() {
        let harnesses = fallback_harnesses();
        assert_eq!(harnesses.len(), 2);
        assert_eq!(harnesses[0].id, "claude-code");
        assert_eq!(harnesses[1].id, "codex");
    }

    #[test]
    fn mock_models_share_the_curated_claude_list() {
        // 07's catalog: "claude-code (mock shares it)".
        let mock = fallback_models("mock");
        let claude = fallback_models("claude-code");
        assert!(!mock.is_empty());
        assert_eq!(mock, claude);
        assert!(claude.iter().any(|m| m.id == "claude-fable-5-1"));
    }

    #[test]
    fn default_reasoning_prefers_high_then_medium() {
        let m = |levels: &[&str]| ModelInfo {
            id: "m".into(),
            label: "M".into(),
            description: None,
            reasoning_levels: levels.iter().map(|s| (*s).to_owned()).collect(),
            options: vec![],
        };
        assert_eq!(default_reasoning(&m(&["low", "high"])), Some("high".into()));
        assert_eq!(
            default_reasoning(&m(&["low", "medium"])),
            Some("medium".into())
        );
        assert_eq!(default_reasoning(&m(&["minimal"])), Some("minimal".into()));
        assert_eq!(default_reasoning(&m(&[])), None);
    }

    #[test]
    fn normalize_drops_the_default_placeholder_and_folds_1m_variants() {
        let live = vec![
            ModelInfo {
                id: "default".into(),
                label: "Default".into(),
                description: None,
                reasoning_levels: vec![],
                options: vec![],
            },
            ModelInfo {
                id: "claude-fable-5-1-1m".into(),
                label: "Fable 5.1 (1M)".into(),
                description: None,
                reasoning_levels: vec![],
                options: vec![],
            },
            ModelInfo {
                id: "future-model".into(),
                label: "Future".into(),
                description: None,
                reasoning_levels: vec![],
                options: vec![],
            },
        ];
        let normalized = normalize_models("claude-code", live);
        let ids: Vec<&str> = normalized.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, vec!["claude-fable-5-1", "future-model"]);
        let fable = &normalized[0];
        assert_eq!(fable.label, "Fable 5.1", "the (1M) suffix folds away");
        assert!(fable.options.iter().any(|o| o.id == "contextWindow"));
        // The curated label wins over the engine's.
        assert_eq!(
            normalized[1].label,
            "Future",
            "an unknown model keeps the engine's label"
        );
    }
}
