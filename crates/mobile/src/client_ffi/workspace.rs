//! The workspace FFI surface (ticket 07): reads of the derived snapshot
//! (front page, sections, pins, archive, search, devices, projects), the
//! workspace mutations routed to the owning engine, and the static
//! catalog / label helpers. Port of upstream `mob:client_ffi/mod.rs`
//! 121-310 (sync, `CoreResult`-returning) plus PR #874's groups.
//!
//! Every method is safe from any thread and never blocks on the network:
//! reads clone `Arc`s; mutations validate and fail fast, then spawn the
//! RPC in the background.

use roboco_mobile_client as mc;

use super::types::{
    ChatConfig, DeviceView, FrontPage, ProjectView, PullRequestGroups, SearchHit, SessionRow,
    WorkspaceSnapshot, rows,
};
use super::{CoreClient, CoreResult};

#[uniffi::export]
impl CoreClient {
    // ── workspace reads ─────────────────────────────────────────────────

    /// Current workspace revision (cheap; compare before pulling).
    pub fn workspace_revision(&self) -> u64 {
        self.client.workspace_revision()
    }

    /// The whole derived workspace.
    pub fn workspace(&self) -> WorkspaceSnapshot {
        (&*self.client.workspace()).into()
    }

    /// Sessions page: pinned, sections, recent (+ groups).
    pub fn front_page(&self) -> FrontPage {
        (&self.client.workspace().front).into()
    }

    pub fn projects(&self) -> Vec<ProjectView> {
        self.client
            .workspace()
            .projects
            .iter()
            .map(Into::into)
            .collect()
    }

    pub fn project(&self, space_id: String) -> Option<ProjectView> {
        self.client.workspace().project(&space_id).map(Into::into)
    }

    /// Active sessions without a project.
    pub fn projectless_sessions(&self) -> Vec<SessionRow> {
        rows(&self.client.workspace().projectless)
    }

    pub fn pull_requests(&self) -> PullRequestGroups {
        (&self.client.workspace().pull_requests).into()
    }

    pub fn archived_sessions(&self) -> Vec<SessionRow> {
        rows(&self.client.workspace().archived)
    }

    pub fn devices(&self) -> Vec<DeviceView> {
        self.client
            .workspace()
            .devices
            .iter()
            .map(Into::into)
            .collect()
    }

    /// Devices that can run sessions (new-session / new-project pickers);
    /// offline ones included.
    pub fn execution_devices(&self) -> Vec<DeviceView> {
        self.client
            .workspace()
            .execution_devices()
            .iter()
            .map(Into::into)
            .collect()
    }

    /// Any chat row (archived, child and dangling-space rows included).
    pub fn session_row(&self, chat_id: String) -> Option<SessionRow> {
        self.client
            .workspace()
            .session(&chat_id)
            .map(|r| (&**r).into())
    }

    /// Side chats / subagent chats of `parent_id`, recency order.
    pub fn child_sessions(&self, parent_id: String) -> Vec<SessionRow> {
        rows(self.client.workspace().children(&parent_id))
    }

    pub fn session_config(&self, chat_id: String) -> Option<ChatConfig> {
        self.client
            .session_config(&chat_id)
            .as_ref()
            .map(Into::into)
    }

    /// Case-insensitive client-side search over the cached merged rows
    /// (Roboco has no search RPC — ADR 0004). `limit` ≈ 60 for the list.
    pub fn search(&self, query: String, limit: u32) -> Vec<SearchHit> {
        self.client
            .search(&query, limit as usize)
            .iter()
            .map(Into::into)
            .collect()
    }

    // ── workspace writes (fail fast; the RPC is spawned) ────────────────

    pub fn archive_session(&self, chat_id: String) -> CoreResult<()> {
        Ok(self.client.archive_session(&chat_id)?)
    }

    pub fn unarchive_session(&self, chat_id: String) -> CoreResult<()> {
        Ok(self.client.unarchive_session(&chat_id)?)
    }

    /// A blank (whitespace-only) title is a no-op returning `Ok`.
    pub fn rename_session(&self, chat_id: String, title: String) -> CoreResult<()> {
        Ok(self.client.rename_session(&chat_id, &title)?)
    }

    /// Fire-and-forget: never fails, never rolls back.
    pub fn mark_seen(&self, chat_id: String) {
        self.client.mark_seen(&chat_id);
    }

    pub fn delete_session(&self, chat_id: String) -> CoreResult<()> {
        Ok(self.client.delete_session(&chat_id)?)
    }

    pub fn pin_session(&self, chat_id: String) -> CoreResult<()> {
        Ok(self.client.pin_session(&chat_id)?)
    }

    pub fn unpin_session(&self, chat_id: String) -> CoreResult<()> {
        Ok(self.client.unpin_session(&chat_id)?)
    }

    /// Reorder a pin between neighbours (`None` at either end). Anchors
    /// are scoped ids; anchors from other engines count as absent.
    pub fn move_pin(
        &self,
        chat_id: String,
        after: Option<String>,
        before: Option<String>,
    ) -> CoreResult<()> {
        Ok(self
            .client
            .move_pin(&chat_id, after.as_deref(), before.as_deref())?)
    }

    /// Create a section; `engine_id: None` picks the first connected
    /// engine. Returns the **scoped** section id.
    pub fn create_section(&self, name: String, engine_id: Option<String>) -> CoreResult<String> {
        Ok(self.client.create_section(&name, engine_id.as_deref())?)
    }

    pub fn rename_section(&self, section_id: String, name: String) -> CoreResult<()> {
        Ok(self.client.rename_section(&section_id, &name)?)
    }

    /// Deleting a section drops membership only; its sessions return to
    /// Recent.
    pub fn delete_section(&self, section_id: String) -> CoreResult<()> {
        Ok(self.client.delete_section(&section_id)?)
    }

    /// The fold is engine-synced and shared with the desktop and web.
    pub fn set_section_collapsed(&self, section_id: String, collapsed: bool) -> CoreResult<()> {
        Ok(self
            .client
            .set_section_collapsed(&section_id, collapsed)?)
    }

    /// Pin between neighbours (`None` at either end), as a drop into
    /// Pinned does (wave-10 41, upstream #909). Leaves every section.
    pub fn pin_session_at(
        &self,
        chat_id: String,
        after: Option<String>,
        before: Option<String>,
    ) -> CoreResult<()> {
        Ok(self
            .client
            .pin_session_at(&chat_id, after.as_deref(), before.as_deref())?)
    }

    /// Move a session into a section between its members (`None` at either
    /// end; `section_id: None` = back to Recent), as a drop into the
    /// section does (wave-10 41, upstream #909).
    pub fn place_session(
        &self,
        chat_id: String,
        section_id: Option<String>,
        after: Option<String>,
        before: Option<String>,
    ) -> CoreResult<()> {
        Ok(self.client.place_session(
            &chat_id,
            section_id.as_deref(),
            after.as_deref(),
            before.as_deref(),
        )?)
    }

    /// Move a chat into a section (`None` = back to Recent). Refused when
    /// the section and chat belong to different engines.
    pub fn assign_section(&self, chat_id: String, section_id: Option<String>) -> CoreResult<()> {
        Ok(self
            .client
            .assign_section(&chat_id, section_id.as_deref())?)
    }
}

// ── static helpers (catalog labels, time, colors) ─────────────────────────

/// Static harness catalog (the device-unreachable fallback).
#[uniffi::export]
pub fn fallback_harnesses() -> Vec<super::types::HarnessInfo> {
    mc::catalog::fallback_harnesses()
        .into_iter()
        .map(|h| (&h).into())
        .collect()
}

/// Curated models for a harness (first = default).
#[uniffi::export]
pub fn fallback_models(harness: String) -> Vec<super::types::ModelInfo> {
    mc::catalog::fallback_models(&harness)
        .into_iter()
        .map(|m| (&m).into())
        .collect()
}

#[uniffi::export]
pub fn harness_label(harness: String) -> String {
    mc::catalog::harness_label(&harness)
}

#[uniffi::export]
pub fn model_label(harness: String, model: String) -> String {
    mc::catalog::model_label(&harness, &model)
}

#[uniffi::export]
pub fn reasoning_label(level: String) -> String {
    mc::catalog::reasoning_label(&level)
}

/// `now` / `34m` / `4h` / `2d`.
#[uniffi::export]
pub fn relative_time_label(at_ms: i64, now_ms: i64) -> String {
    mc::relative_time_label(at_ms, now_ms)
}

/// Stable palette slot for a project path (`"home"` without a project).
#[uniffi::export]
pub fn project_color_index(space_path: String) -> u32 {
    mc::project_color_index(&space_path)
}

/// Size of the project palette `project_color_index` indexes.
#[uniffi::export]
pub fn project_color_count() -> u32 {
    mc::workspace::view::project_color_count()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[derive(Default)]
    struct Recorder(std::sync::Mutex<Vec<super::super::ClientEvent>>);

    impl super::super::ClientListener for Recorder {
        fn on_event(&self, event: super::super::ClientEvent) {
            self.0.lock().unwrap().push(event);
        }
    }

    /// The workspace reads cross the boundary: an empty client returns an
    /// empty front page and no search hits, and the static helpers
    /// resolve.
    #[test]
    fn front_page_and_search_cross_the_ffi_boundary() {
        let dir = tempfile::tempdir().unwrap();
        let client = CoreClient::new(
            super::super::CoreConfig {
                data_dir: dir.path().to_string_lossy().to_string(),
                device_name: "Pixel 9".into(),
                platform: "android".into(),
                app_version: "0.9.2".into(),
                show_mock_harness: true,
                native_lib_dir: None,
            },
            Vec::new(),
            Vec::new(),
            Arc::new(Recorder::default()),
        )
        .unwrap();
        let front = client.front_page();
        assert!(front.pinned.is_empty());
        assert!(front.sections.is_empty());
        assert!(front.recent.is_empty());
        assert!(front.groups.is_empty());
        let snapshot = client.workspace();
        assert_eq!(snapshot.revision, client.workspace_revision());
        assert!(snapshot.projects.is_empty());
        assert!(snapshot.devices.is_empty());
        assert!(!snapshot.pins_ready);
        assert!(client.session_row("nope".into()).is_none());
        assert!(client.child_sessions("nope".into()).is_empty());
        assert!(client.session_config("nope".into()).is_none());
        let hits = client.search("anything".into(), 60);
        assert!(hits.is_empty());
        // Static label helpers.
        assert_eq!(relative_time_label(0, 59_000), "now");
        assert_eq!(relative_time_label(0, 60_000), "1m");
        assert_eq!(project_color_index("home".into()), 6);
        assert_eq!(project_color_count(), 8);
        assert_eq!(harness_label("claude-code".into()), "Claude Code");
        assert_eq!(
            model_label("claude-code".into(), "claude-opus-5".into()),
            "Opus 5"
        );
        assert_eq!(reasoning_label("xhigh".into()), "X-High");
        assert_eq!(fallback_harnesses().len(), 2);
        assert!(!fallback_models("claude-code".into()).is_empty());
        client.shutdown();
    }

    /// Mutations of an unknown id fail fast with `NotFound` through the
    /// FFI error surface.
    #[test]
    fn workspace_mutations_map_their_errors() {
        let dir = tempfile::tempdir().unwrap();
        let client = CoreClient::new(
            super::super::CoreConfig {
                data_dir: dir.path().to_string_lossy().to_string(),
                device_name: "Pixel 9".into(),
                platform: "android".into(),
                app_version: "0.9.2".into(),
                show_mock_harness: true,
                native_lib_dir: None,
            },
            Vec::new(),
            Vec::new(),
            Arc::new(Recorder::default()),
        )
        .unwrap();
        assert!(matches!(
            client.archive_session("engine:v1:bm9wZQ".into()),
            Err(super::super::CoreError::NotFound { .. })
        ));
        // A blank rename is a no-op — `Ok` before any id validation.
        client.rename_session("engine:v1:bm9wZQ".into(), "  ".into()).unwrap();
        assert!(matches!(
            client.pin_session("engine:v1:bm9wZQ".into()),
            Err(super::super::CoreError::NotFound { .. })
        ));
        // Section creation with no connected engine: HostUnavailable.
        assert!(matches!(
            client.create_section("Work".into(), None),
            Err(super::super::CoreError::HostUnavailable { .. })
        ));
        // mark_seen never fails.
        client.mark_seen("engine:v1:bm9wZQ".into());
        client.shutdown();
    }
}
