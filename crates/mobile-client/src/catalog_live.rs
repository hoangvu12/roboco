//! The live catalog: `ListHarnesses` / `ListModels` per engine, a disk cache
//! under the engine's cache directory, and the mid-turn-steering memory the
//! composer reads.
//!
//! The static half (the curated fallback lists, the label functions) is
//! ticket 07's `catalog.rs` — this module carries a minimal static fallback
//! so the live layer works before 07 lands; when both are merged the
//! fallbacks here should delegate to `crate::catalog` (the shapes are
//! identical by construction — both port `um:crates/client/src/catalog.rs`).
//!
//! Decoding is deliberately lenient: `id`, `steeringMode` and
//! `reasoningLevels` are plain strings, so a harness this app version has
//! never heard of survives the round trip (`HarnessDescriptor` lives in
//! `roboco-engine`, which mobile must not link).

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};

use crate::engines::EngineHandle;
use crate::{ClientError, lock};

use crate::ClientInner;

// ── records (wire-string ids; `um:crates/client/src/catalog.rs` shapes) ────

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HarnessInfo {
    /// Wire id (`claude-code`).
    pub id: String,
    /// Display name.
    #[serde(alias = "name")]
    pub label: String,
    #[serde(default)]
    pub supports_steering: Option<bool>,
    /// `step-boundary` / `turn-boundary`.
    #[serde(default)]
    pub steering_mode: Option<String>,
    #[serde(default)]
    pub reasoning_levels: Vec<String>,
    /// CLI present on the listing device (absent on old engines ⇒ true).
    #[serde(default = "default_true")]
    pub installed: bool,
    /// Offered by the listing device (Settings → Providers). `None` ⇒ legacy.
    #[serde(default)]
    pub enabled: Option<bool>,
}

fn default_true() -> bool {
    true
}

impl HarnessInfo {
    /// Mid-turn steering is available (known only from a live catalog).
    pub fn mid_turn_steering(&self) -> Option<bool> {
        Some(self.supports_steering? && self.steering_mode.as_deref()? == "step-boundary")
    }

    /// Listed by the device and usable (installed, not disabled).
    pub fn offered(&self) -> bool {
        self.installed && self.enabled != Some(false)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelOptionChoice {
    pub id: String,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelOption {
    pub id: String,
    pub label: String,
    pub choices: Vec<ModelOptionChoice>,
    pub default_choice: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelInfo {
    pub id: String,
    pub label: String,
    #[serde(default)]
    pub description: Option<String>,
    /// Lowercase wire values (`low`…`ultrathink`); empty = no effort ladder.
    #[serde(default)]
    pub reasoning_levels: Vec<String>,
    #[serde(default)]
    pub options: Vec<ModelOption>,
}

// ── the client-side catalog state ──────────────────────────────────────────

/// Per-client live catalog: the engines' harness lists (for
/// `mid_turn_steering`) and the labels learned from live model lists.
#[derive(Default)]
pub(crate) struct Catalog {
    pub(crate) state: Mutex<CatalogState>,
}

#[derive(Default)]
pub(crate) struct CatalogState {
    /// Engine id → its harness list.
    pub(crate) harnesses: HashMap<String, Vec<HarnessInfo>>,
    /// Labels learned from engines' live model catalogs, keyed
    /// `(harness, model id)`: harnesses without a curated list still get
    /// the engine's human label instead of the raw model id.
    pub(crate) learned: HashMap<(String, String), String>,
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

/// Learn labels from a live model list (rows and chips pick them up on the
/// next derive).
pub(crate) fn learn_labels(inner: &Arc<ClientInner>, harness: &str, models: &[ModelInfo]) {
    let mut state = lock(&inner.catalog.state);
    for model in models {
        if !model.label.trim().is_empty() && model.label != model.id {
            state
                .learned
                .insert((harness.to_owned(), model.id.clone()), model.label.clone());
        }
    }
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
            Ok(mut list) => {
                list.retain(|h| h.id != "mock" || inner.config.show_mock_harness);
                if inner.config.show_mock_harness {
                    // The engine marks the mock not-offered (a test rig must
                    // never displace a real agent); the debug flag re-offers
                    // it — "debug builds and tests set it so the mock
                    // harness is offered".
                    for harness in list.iter_mut().filter(|h| h.id == "mock") {
                        harness.enabled = Some(true);
                    }
                }
                write_catalog_cache(&inner, &engine_id, "harnesses.json", &list);
                lock(&inner.catalog.state).harnesses.insert(engine_id, list.clone());
                // Composers recompute their mid-turn-steering flags.
                crate::session::recompute_all_composers(&inner);
                list
            }
            Err(_) => {
                let cached = read_catalog_cache::<Vec<HarnessInfo>>(&inner, &engine_id, "harnesses.json")
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
                learn_labels(&inner, harness, &models);
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

async fn list_models_live(handle: &EngineHandle, harness: &str) -> Result<Vec<ModelInfo>, ClientError> {
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

fn write_catalog_cache<T: serde::Serialize>(inner: &Arc<ClientInner>, engine_id: &str, file: &str, value: &T) {
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
        let staging = dir.join(format!("{}.tmp", path.file_name().and_then(|n| n.to_str()).unwrap_or("catalog")));
        if std::fs::write(&staging, &bytes).is_ok() {
            let _ = std::fs::rename(&staging, &path);
        }
    });
}

// ── static fallback + normalization (07's `catalog.rs` is canonical) ───────

/// Static fallback = the engine's `default_enabled()` pair. ACP agents
/// appear only through a device's live catalog (opt-in per device).
pub fn fallback_harnesses() -> Vec<HarnessInfo> {
    vec![
        harness("claude-code", "Claude Code"),
        harness("codex", "Codex"),
    ]
}

fn harness(id: &str, label: &str) -> HarnessInfo {
    HarnessInfo {
        id: id.into(),
        label: label.into(),
        supports_steering: None,
        steering_mode: None,
        reasoning_levels: Vec::new(),
        installed: true,
        enabled: None,
    }
}

fn model(
    id: &str,
    label: &str,
    description: &str,
    ladder: &[&str],
    options: Vec<ModelOption>,
) -> ModelInfo {
    ModelInfo {
        id: id.into(),
        label: label.into(),
        description: Some(description.into()),
        reasoning_levels: ladder.iter().map(|s| (*s).to_owned()).collect(),
        options,
    }
}

fn option(id: &str, label: &str, choices: &[(&str, &str)], default: &str) -> ModelOption {
    ModelOption {
        id: id.into(),
        label: label.into(),
        choices: choices
            .iter()
            .map(|(id, label)| ModelOptionChoice {
                id: (*id).into(),
                label: (*label).into(),
            })
            .collect(),
        default_choice: default.into(),
    }
}

fn toggle(id: &str, label: &str) -> ModelOption {
    option(id, label, &[("off", "Off"), ("on", "On")], "off")
}

fn context_window(default: &str) -> ModelOption {
    option(
        "contextWindow",
        "Context Window",
        &[("200k", "200K"), ("1m", "1M")],
        default,
    )
}

const FULL_LADDER: &[&str] = &[
    "low",
    "medium",
    "high",
    "xhigh",
    "max",
    "ultracode",
    "ultrathink",
];
const CLAUDE_XHIGH: &[&str] = &["low", "medium", "high", "xhigh", "max", "ultrathink"];
const CODEX_XHIGH: &[&str] = &["low", "medium", "high", "xhigh"];

/// Curated static models for a harness (first row = default). A trimmed
/// port of the upstream catalog; 07's `catalog.rs` carries the full set.
pub fn fallback_models(harness: &str) -> Vec<ModelInfo> {
    let service_tier = || {
        vec![option(
            "serviceTier",
            "Service Tier",
            &[("default", "Standard"), ("fast", "Fast")],
            "default",
        )]
    };
    match harness {
        "mock" => vec![
            model("mock-1", "Mock 1", "The scripted mock model", &["medium"], vec![]),
            model(
                "mock-fable-5",
                "Fable 5",
                "Claude-mirroring demo model",
                &["low", "medium", "high", "xhigh"],
                vec![],
            ),
        ],
        "codex" => vec![
            model(
                "gpt-6-astra",
                "GPT-6-Astra",
                "Our most capable model for complex, demanding work.",
                CODEX_XHIGH,
                service_tier(),
            ),
            model(
                "gpt-5.6-sol",
                "GPT-5.6-Sol",
                "Frontier reasoning flagship",
                CODEX_XHIGH,
                service_tier(),
            ),
            model(
                "gpt-5.5",
                "GPT-5.5",
                "Previous generation flagship",
                CODEX_XHIGH,
                service_tier(),
            ),
            model(
                "gpt-5.4-mini",
                "GPT-5.4-Mini",
                "Small, fast and capable",
                CODEX_XHIGH,
                service_tier(),
            ),
        ],
        // claude-code
        _ => vec![
            model(
                "claude-fable-5-1",
                "Fable 5.1",
                "Most intelligent model for building agents",
                FULL_LADDER,
                vec![context_window("200k")],
            ),
            model(
                "claude-fable-5",
                "Fable 5",
                "Previous generation Fable",
                FULL_LADDER,
                vec![context_window("200k")],
            ),
            model(
                "claude-opus-5",
                "Opus 5",
                "Powerful model for complex work",
                FULL_LADDER,
                vec![context_window("200k"), toggle("fastMode", "Fast Mode")],
            ),
            model(
                "claude-sonnet-5",
                "Sonnet 5",
                "Balanced speed and intelligence",
                CLAUDE_XHIGH,
                vec![context_window("200k")],
            ),
            model(
                "claude-haiku-4-5",
                "Haiku 4.5",
                "Fastest model for everyday tasks",
                &[],
                vec![toggle("thinking", "Thinking")],
            ),
        ],
    }
}

/// pickers.rs — High when available, then Medium, then the first level.
pub fn default_reasoning(model: &ModelInfo) -> Option<String> {
    let levels = &model.reasoning_levels;
    if levels.is_empty() {
        return None;
    }
    for preferred in ["high", "medium"] {
        if levels.iter().any(|l| l == preferred) {
            return Some(preferred.into());
        }
    }
    levels.first().cloned()
}

fn stripped_1m(id: &str) -> Option<&str> {
    id.strip_suffix("[1m]").or_else(|| id.strip_suffix("-1m"))
}

/// Clean a live `ListModels` reply: drop a `default` placeholder when real
/// rows exist, fold `[1m]`/`-1m` variants into a Context Window option, and
/// prefer curated labels.
pub fn normalize_models(harness: &str, models: Vec<ModelInfo>) -> Vec<ModelInfo> {
    let ids: Vec<String> = models.iter().map(|m| m.id.clone()).collect();
    let has_real = ids.iter().any(|id| !id.eq_ignore_ascii_case("default"));
    models
        .into_iter()
        .filter_map(|mut row| {
            if has_real && row.id.eq_ignore_ascii_case("default") {
                return None;
            }
            if let Some(base) = stripped_1m(&row.id).map(str::to_owned) {
                if ids.contains(&base) {
                    return None;
                }
                row.id = base;
                if let Some(start) = row.label.rfind('(')
                    && row.label.ends_with(')')
                    && start > 0
                {
                    row.label = row.label[..start].trim().to_owned();
                }
                if !row.options.iter().any(|o| o.id == "contextWindow") {
                    row.options.push(context_window("1m"));
                }
            }
            if let Some(label) = curated_label(&row.id, harness) {
                row.label = label;
            }
            Some(row)
        })
        .collect()
}

fn curated_label(id: &str, harness: &str) -> Option<String> {
    fallback_models(harness)
        .iter()
        .find(|m| m.id == id)
        .map(|m| m.label.clone())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mid_turn_steering_needs_step_boundary_and_support() {
        let step = harness("mock", "Mock");
        let mut step = step;
        step.supports_steering = Some(true);
        step.steering_mode = Some("step-boundary".into());
        assert_eq!(step.mid_turn_steering(), Some(true));
        step.steering_mode = Some("turn-boundary".into());
        assert_eq!(step.mid_turn_steering(), Some(false));
        step.supports_steering = None;
        assert_eq!(step.mid_turn_steering(), None);
        // offered: installed and not disabled.
        let mut off = harness("codex", "Codex");
        off.enabled = Some(false);
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
    fn mock_models_and_claude_share_the_curated_list() {
        let mock = fallback_models("mock");
        assert!(mock.iter().any(|m| m.id == "mock-1"));
        let claude = fallback_models("claude-code");
        assert!(claude.iter().any(|m| m.id == "claude-fable-5-1"));
        assert!(!claude.is_empty());
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
