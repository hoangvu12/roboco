//! Workspace lifecycle types shared by the engine and its clients.

use serde::{Deserialize, Serialize};
use ts_rs::TS;

/// Protocol features are advertised explicitly because personal/integration
/// builds may share a semver with upstream while exposing a different RPC and
/// document surface.
pub mod capabilities {
    /// The host decodes durable composer references at the harness boundary.
    pub const COMPOSER_REFERENCES_V1: &str = "composer-references-v1";
    pub const MESSAGE_QUEUE_V1: &str = "message-queue-v1";
    pub const MESSAGE_QUEUE_ACTIONS_V1: &str = "message-queue-actions-v1";
    pub const MESSAGE_QUEUE_ATTACHMENTS_V1: &str = "message-queue-attachments-v1";
    pub const MESSAGE_QUEUE_CLEAN_ATTACHMENT_TEXT_V1: &str =
        "message-queue-clean-attachment-text-v1";
    pub const MESSAGE_QUEUE_EDIT_LEASE_V1: &str = "message-queue-edit-lease-v1";
    /// The engine serves the device-local agent-CLI update lifecycle
    /// (harness-updates watch/check/apply/cancel/policy RPCs).
    pub const HARNESS_UPDATES_V1: &str = "harness-updates-v1";
    /// The engine serves the browser web client (ADR 0006): embedded pages at
    /// the remote listener root, CORS-open pairing redeem, and first-frame
    /// `Auth` WebSocket authentication.
    pub const WEB_CLIENT: &str = "web-client";
    /// The engine accepts `digests` / `resume` on `WatchDocMessages` and
    /// attaches engine-computed entry digests to transcript frames, so a
    /// reconnecting client can catch up with a delta instead of a reset.
    pub const TRANSCRIPT_RESUME_V1: &str = "transcript-resume-v1";
    /// The host creates a projectless chat's scratch folder
    /// (`crate::projectless`) when it runs; older hosts only know `~`.
    pub const PROJECTLESS_SCRATCH_V1: &str = "projectless-scratch-v1";
    /// The engine accepts whole-file uploads up to 100 MB (`Uploads::MAX_BYTES`).
    /// Older engines enforce 32 MB server-side; clients use 100 MB only when
    /// the engine advertises this string, clamping to the legacy 24 MB client
    /// cap otherwise.
    pub const UPLOADS_100MB_V1: &str = "uploads-100mb-v1";
    /// The engine serves the room surface (ADR 0011): the room store, the
    /// `/room/present` listener route, and the book RPCs. Rooms share
    /// reachability, never data.
    pub const ROOMS_V1: &str = "rooms-v1";

    pub const CURRENT: &[&str] = &[
        COMPOSER_REFERENCES_V1,
        MESSAGE_QUEUE_V1,
        MESSAGE_QUEUE_ACTIONS_V1,
        MESSAGE_QUEUE_ATTACHMENTS_V1,
        MESSAGE_QUEUE_CLEAN_ATTACHMENT_TEXT_V1,
        MESSAGE_QUEUE_EDIT_LEASE_V1,
        HARNESS_UPDATES_V1,
        WEB_CLIENT,
        TRANSCRIPT_RESUME_V1,
        PROJECTLESS_SCRATCH_V1,
        UPLOADS_100MB_V1,
        ROOMS_V1,
    ];

    pub fn current() -> Vec<String> {
        CURRENT.iter().map(|value| (*value).to_string()).collect()
    }
}

/// The fixed data boundary selected when an engine runtime is assembled.
///
/// Authentication can change while a runtime is alive, but its workspace scope
/// cannot. Switching scopes requires assembling a new runtime.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum WorkspaceScope {
    Local,
    Synced,
    Development,
}

/// The wire protocol generation this build speaks. Bumped only for an
/// incompatible wire change (a removed or renamed method or field, a changed
/// frame or auth meaning). ADR 0005 freezes wire names, so this is expected
/// to stay 1 for a long time.
pub const PROTOCOL_VERSION: u32 = 1;

/// The oldest client protocol generation this engine still serves correctly.
/// Raised only together with a `PROTOCOL_VERSION` bump that drops the old
/// generation.
pub const MIN_CLIENT_PROTOCOL_VERSION: u32 = 1;

/// Stable information about the engine runtime reached by a client.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct EngineInfo {
    pub device_id: String,
    pub workspace_scope: WorkspaceScope,
    /// SDK selected by the owning engine, absent on older versions.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor_sdk_version: Option<String>,
    /// Supported protocol/document features. Missing on older engines.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub capabilities: Vec<String>,
    /// The Roboco release that built this engine (`CARGO_PKG_VERSION`).
    /// For display and the newer-engine notice only — never gate features on
    /// it. Absent on engines older than the field; the client may fall back
    /// to the engine's `Device.version` row.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    /// The wire protocol generation. Absent on pre-field engines; readers
    /// treat it as 1.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub protocol_version: Option<u32>,
    /// The oldest client protocol generation this engine still serves
    /// correctly. Absent on pre-field engines; readers treat it as 1.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub min_client_protocol_version: Option<u32>,
}

impl EngineInfo {
    /// This build's own identity: version, protocol generation and
    /// capabilities filled in. Every engine-side construction site uses this
    /// so the served identity cannot drift from the build.
    pub fn current(
        device_id: String,
        workspace_scope: WorkspaceScope,
        cursor_sdk_version: Option<String>,
    ) -> Self {
        Self {
            device_id,
            workspace_scope,
            cursor_sdk_version,
            capabilities: capabilities::current(),
            version: Some(env!("CARGO_PKG_VERSION").into()),
            protocol_version: Some(PROTOCOL_VERSION),
            min_client_protocol_version: Some(MIN_CLIENT_PROTOCOL_VERSION),
        }
    }

    /// [`Self::protocol_version`] or 1 for engines older than the field.
    pub fn protocol(&self) -> u32 {
        self.protocol_version.unwrap_or(1)
    }

    /// [`Self::min_client_protocol_version`] or 1 for engines older than the
    /// field.
    pub fn min_client_protocol(&self) -> u32 {
        self.min_client_protocol_version.unwrap_or(1)
    }

    pub fn supports(&self, capability: &str) -> bool {
        self.capabilities.iter().any(|value| value == capability)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn workspace_scope_uses_wire_safe_names() {
        for (scope, encoded) in [
            (WorkspaceScope::Local, "\"local\""),
            (WorkspaceScope::Synced, "\"synced\""),
            (WorkspaceScope::Development, "\"development\""),
        ] {
            assert_eq!(serde_json::to_string(&scope).unwrap(), encoded);
            assert_eq!(
                serde_json::from_str::<WorkspaceScope>(encoded).unwrap(),
                scope
            );
        }
    }

    #[test]
    fn engine_info_uses_camel_case_fields() {
        let info = EngineInfo::current(
            "device-1".into(),
            WorkspaceScope::Local,
            Some("1.0.31".into()),
        );
        assert_eq!(
            serde_json::to_value(&info).unwrap(),
            serde_json::json!({
                "deviceId": "device-1",
                "workspaceScope": "local",
                "cursorSdkVersion": "1.0.31",
                "capabilities": [
                    "composer-references-v1",
                    "message-queue-v1",
                    "message-queue-actions-v1",
                    "message-queue-attachments-v1",
                    "message-queue-clean-attachment-text-v1",
                    "message-queue-edit-lease-v1",
                    "harness-updates-v1",
                    "web-client",
                    "transcript-resume-v1",
                    "projectless-scratch-v1",
                    "uploads-100mb-v1",
                    "rooms-v1"
                ],
                "version": env!("CARGO_PKG_VERSION"),
                "protocolVersion": 1,
                "minClientProtocolVersion": 1,
            })
        );
    }

    #[test]
    fn transcript_resume_is_advertised() {
        assert!(capabilities::CURRENT.contains(&capabilities::TRANSCRIPT_RESUME_V1));
        assert!(capabilities::current().contains(&capabilities::TRANSCRIPT_RESUME_V1.to_string()));
    }

    #[test]
    fn uploads_100mb_is_advertised() {
        assert_eq!(capabilities::UPLOADS_100MB_V1, "uploads-100mb-v1");
        assert!(capabilities::CURRENT.contains(&capabilities::UPLOADS_100MB_V1));
        let info = EngineInfo::current("device-1".into(), WorkspaceScope::Local, None);
        assert!(info.supports(capabilities::UPLOADS_100MB_V1));
    }

    #[test]
    fn rooms_is_advertised() {
        assert!(capabilities::CURRENT.contains(&capabilities::ROOMS_V1));
        assert!(capabilities::current().contains(&capabilities::ROOMS_V1.to_string()));
        let info = EngineInfo::current("device-1".into(), WorkspaceScope::Local, None);
        assert!(info.supports(capabilities::ROOMS_V1));
    }

    #[test]
    fn old_engine_info_defaults_to_no_capabilities() {
        let info: EngineInfo = serde_json::from_value(serde_json::json!({
            "deviceId": "old",
            "workspaceScope": "synced"
        }))
        .unwrap();
        assert!(info.capabilities.is_empty());
        assert!(info.cursor_sdk_version.is_none());
        assert!(info.version.is_none());
        assert!(info.protocol_version.is_none());
        assert!(info.min_client_protocol_version.is_none());
        // Absent fields read as the pre-field protocol: generation 1.
        assert_eq!(info.protocol(), 1);
        assert_eq!(info.min_client_protocol(), 1);
        assert!(!info.supports(capabilities::MESSAGE_QUEUE_V1));
        assert!(!info.supports(capabilities::COMPOSER_REFERENCES_V1));
    }

    #[test]
    fn current_build_identity_carries_version_and_protocol() {
        let info = EngineInfo::current("device-1".into(), WorkspaceScope::Local, None);
        assert_eq!(info.version.as_deref(), Some(env!("CARGO_PKG_VERSION")));
        assert_eq!(info.protocol_version, Some(PROTOCOL_VERSION));
        assert_eq!(
            info.min_client_protocol_version,
            Some(MIN_CLIENT_PROTOCOL_VERSION)
        );
        assert_eq!(info.protocol(), PROTOCOL_VERSION);
        assert_eq!(info.min_client_protocol(), MIN_CLIENT_PROTOCOL_VERSION);
        assert_eq!(info.capabilities, capabilities::current());
    }
}
