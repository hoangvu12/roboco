//! Device-local discovery metadata and the browser's live preview list.
use serde::{Deserialize, Serialize};

pub const PREVIEW_PROXY_PORT: u16 = 7331;

/// The *client-side* preview proxy's port (Phase C): a client relaying a
/// remote engine's previews binds `127.0.0.1:7332` — unclaimed; the engine
/// owns 7331 and the browser helper 7333 — only while a remote preview is
/// being viewed. The engine never listens here.
pub const PREVIEW_CLIENT_PROXY_PORT: u16 = 7332;

/// Stamped by the engine into the environment of every process it launches:
/// [`chat_origin`] for agent runs, [`terminal_origin`] for its terminals
/// (project actions run in one). Children inherit it, through `nohup`,
/// daemonizing and an engine restart alike, so preview discovery lists
/// exactly the servers Roboco started and knows which chat started them. One
/// variable, so the nearest Roboco launch always overwrites an outer one.
pub const ORIGIN_ENV: &str = "ROBOCO_ORIGIN";

pub fn chat_origin(chat_id: &str) -> String {
    format!("chat:{chat_id}")
}

pub fn terminal_origin(terminal_id: &str) -> String {
    format!("terminal:{terminal_id}")
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PreviewService {
    /// Persisted identities; neither includes a listening port or process ID.
    pub id: String,
    pub project_id: String,
    pub project_name: String,
    pub project_cwd: String,
    pub device_id: String,
    pub device_name: String,
    pub hostname: String,
    pub name: String,
    pub port: u16,
    pub pid: u32,
    pub cwd: String,
    /// Process creation time, milliseconds since the Unix epoch.
    pub started_at: u64,
    pub roboco_owned: bool,
    /// The chat whose agent or terminal started it (see [`ORIGIN_ENV`]).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub chat_id: Option<String>,
}

impl PreviewService {
    pub fn url(&self, proxy_port: u16) -> String {
        format!("http://{}:{proxy_port}", self.hostname)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PreviewSnapshot {
    pub services: Vec<PreviewService>,
    pub proxy_port: u16,
    pub error: Option<String>,
    #[serde(default)]
    pub project_name: Option<String>,
    #[serde(default)]
    pub remote: bool,
}

impl Default for PreviewSnapshot {
    fn default() -> Self {
        Self {
            services: Vec::new(),
            proxy_port: PREVIEW_PROXY_PORT,
            error: None,
            project_name: None,
            remote: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WatchPreviewsParams {
    pub chat_id: String,
}
