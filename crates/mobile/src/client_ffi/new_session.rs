//! The New Session / New Project FFI surface (ticket 09): project groups
//! (#799), chat creation on the owning engine, folder browsing, drives,
//! manual paths with create-if-missing, refs, and worktrees — plus the
//! host-path helpers the Kotlin folder browser shares.
//!
//! Every method takes `device_id` in 07's `DeviceView.id` /
//! `ProjectView.device_id` form (the scoped engine device id; a bare
//! engine device id or raw engine id also resolves). Reads never block
//! the FFI thread; the async calls run on the client's own runtime and an
//! engine that is not connected fails fast — `HostUnavailable`, never a
//! queue (remote-access story 24).
//!
//! **Out-of-order guard.** Each browse call (`list_folders`,
//! `list_drives`) is an independent round trip; the replies carry no
//! sequence stamps. A device or folder switch mid-flight makes an earlier
//! reply stale — drop it UI-side (iOS guards the same staleness in
//! `NewProjectViewController.swift:98-143`).

use roboco_mobile_client as mc;

use super::types::{
    DriveEntry, FolderListing, NewSession, PreparedPath, ProjectGroup, RepoRef,
};
use super::{CoreClient, CoreResult, on_runtime};

#[uniffi::export]
impl CoreClient {
    /// The project menu's groups (#799, project-first then device): the
    /// workspace's projects grouped by repository across every engine,
    /// sorted by name; `checkouts` carries `device_name` /
    /// `device_online` for the subtitle and the "Run on" host menu
    /// (several checkouts on one device show the path instead — the UI
    /// decides). Without a project, the host menu is
    /// `execution_devices()` with Online/Offline.
    pub fn project_groups(&self) -> Vec<ProjectGroup> {
        self.client
            .project_groups()
            .iter()
            .map(Into::into)
            .collect()
    }

    /// Create a chat on the target's engine (`Mutate createChat`, plus a
    /// `renameChat` for a non-blank title). Returns the **scoped** chat
    /// id; the row shows in the workspace at once (an optimistic pending
    /// row) and the engine's `WatchChats` frame replaces it. The first
    /// send stays the caller's three steps — `create_session`,
    /// `open_session`, `SessionHandle.send` — exactly as iOS drives them
    /// (`AppModel.swift:570-594`); a new-worktree draft sends its
    /// `WorktreeSpec` and the engine materializes it on run.
    ///
    /// Offline engine → `HostUnavailable` (nothing is queued); a device
    /// that can't host sessions (`ios`/`android`/`ipados`) →
    /// `InvalidArgument`.
    pub async fn create_session(&self, new_session: NewSession) -> CoreResult<String> {
        // The config's wire strings validate before anything is sent.
        let new_session: mc::NewSession = new_session.try_into()?;
        let client = self.client.clone();
        on_runtime(async move { client.create_session(new_session).await }).await
    }

    /// Fork `source_id` through its last completed reply into a side chat
    /// (upstream #899's "Fork into Side Chat"): the engine copies the
    /// history and publishes the row; a side chat's fork lists as its
    /// sibling. Returns the new **scoped** chat id. An engine without
    /// the RPC answers `Unsupported` (hide the entry point); nothing
    /// completed yet → `HostError` with the engine's reason.
    pub async fn fork_side_chat(&self, source_id: String) -> CoreResult<String> {
        let client = self.client.clone();
        on_runtime(async move { client.fork_side_chat(&source_id).await }).await
    }

    /// Create (or find) the project for `path` on an engine device
    /// (`Mutate createSpace`); returns the **scoped** space id. A space
    /// on the same device in the same folder dedupes to its id (case- and
    /// separator-insensitive on Windows-style paths). `git_detected` is
    /// the picker's flag; the engine re-verifies. Offline →
    /// `HostUnavailable` (no local row — ADR 0004).
    ///
    /// The manual-path flow drives this after
    /// `prepare_space_path(device, typed, createIfMissing)`: always pass
    /// the engine-normalized `prepared.path`, never the raw input.
    pub async fn create_project(
        &self,
        device_id: String,
        path: String,
        git_detected: bool,
    ) -> CoreResult<String> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .create_project(&device_id, &path, git_detected)
                .await
        })
        .await
    }

    /// Browse one folder level on an engine device; `path: None` (or
    /// blank) is the engine user's home folder. `show_hidden` also lists
    /// dot-folders. Entries are directories first, name-sorted
    /// engine-side; `truncated` reports the entry cap. The reply can
    /// arrive out of order (see the module docs).
    pub async fn list_folders(
        &self,
        device_id: String,
        path: Option<String>,
        show_hidden: bool,
    ) -> CoreResult<FolderListing> {
        let client = self.client.clone();
        let listing = on_runtime(async move {
            client
                .list_folders(&device_id, path.as_deref(), show_hidden)
                .await
        })
        .await?;
        Ok(listing.into())
    }

    /// The device's browse roots beyond home: mounted drives / volumes
    /// (Windows drive letters, macOS `/Volumes`, Linux mounts). Feeds the
    /// folder browser's locations rail. Replies can arrive out of order.
    pub async fn list_drives(&self, device_id: String) -> CoreResult<Vec<DriveEntry>> {
        let client = self.client.clone();
        let drives = on_runtime(async move { client.list_drives(&device_id).await }).await?;
        Ok(drives.into_iter().map(Into::into).collect())
    }

    /// Interpret a typed project path on the engine: `~` expands there,
    /// Windows-ness is checked there, and `create_if_missing` runs
    /// `create_dir_all` there. Error strings ("enter a folder path",
    /// "the path is a file, not a folder", …) surface verbatim as
    /// `HostError`.
    pub async fn prepare_space_path(
        &self,
        device_id: String,
        path: String,
        create_if_missing: bool,
    ) -> CoreResult<PreparedPath> {
        let client = self.client.clone();
        let prepared = on_runtime(async move {
            client
                .prepare_space_path(&device_id, &path, create_if_missing)
                .await
        })
        .await?;
        Ok(prepared.into())
    }

    /// The repository's refs on an engine device, the current branch
    /// first (the engine's own order otherwise).
    pub async fn list_refs(
        &self,
        device_id: String,
        repo_path: String,
    ) -> CoreResult<Vec<RepoRef>> {
        let client = self.client.clone();
        let refs = on_runtime(async move { client.list_refs(&device_id, &repo_path).await }).await?;
        Ok(refs.into_iter().map(Into::into).collect())
    }

    /// Create a linked worktree off `base` and return its host path.
    /// Kept for FFI parity — new-session worktrees ride the send's
    /// `WorktreeSpec` and the engine materializes them on run; the UI
    /// never calls this. `space_id` (the project's id) ties the worktree
    /// to the space's setup Action; `None` creates a bare worktree.
    pub async fn create_worktree(
        &self,
        device_id: String,
        space_id: Option<String>,
        repo_path: String,
        base: String,
    ) -> CoreResult<String> {
        let client = self.client.clone();
        on_runtime(async move {
            client
                .create_worktree(&device_id, space_id.as_deref(), &repo_path, &base)
                .await
        })
        .await
    }
}

// ── host paths (a port of PR #621's FolderBrowser.kt HostPaths) ─────────────

/// One level up, or `None` at a root (`C:\Users` → `C:\`, `/` → `None`).
#[uniffi::export]
pub fn host_path_parent(path: String) -> Option<String> {
    mc::host_paths::parent(&path)
}

/// Join `name` under `base` in the base's own separator style (`\` on a
/// Windows base, else `/`); no separator when the base ends in one.
#[uniffi::export]
pub fn host_path_join(base: String, name: String) -> String {
    mc::host_paths::join(&base, &name)
}

/// The last component, or the path itself for a root (`C:\`, `/`).
#[uniffi::export]
pub fn host_path_name(path: String) -> String {
    mc::host_paths::name(&path)
}

/// A drive root (`C:`, `C:\`, `C:/`), a share root (`\\server\share`), or
/// `/` — the level "Parent Folder" hides at.
#[uniffi::export]
pub fn host_path_is_root(path: String) -> bool {
    mc::host_paths::is_root(&path)
}

/// A Windows-style path: `C:`, `C:\…`, `C:/…`, or a UNC `\\server\share`.
#[uniffi::export]
pub fn host_path_is_windows(path: String) -> bool {
    mc::host_paths::is_windows(&path)
}

/// Same folder, for `create_project`'s dedupe: Windows style (either
/// side) is case- and separator-insensitive; otherwise exact after
/// trimming trailing `/`.
#[uniffi::export]
pub fn host_path_same_folder(a: String, b: String) -> bool {
    mc::host_paths::same_folder(&a, &b)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client_ffi::types::{CoreConfig, SessionTarget};
    use std::sync::Arc;

    #[derive(Default)]
    struct Recorder(std::sync::Mutex<Vec<crate::client_ffi::ClientEvent>>);

    impl crate::client_ffi::ClientListener for Recorder {
        fn on_event(&self, event: crate::client_ffi::ClientEvent) {
            self.0.lock().unwrap().push(event);
        }
    }

    /// The ticket's FFI smoke: `project_groups()` and `host_path_parent()`
    /// through the FFI types (a no-engine client is silent and empty).
    #[test]
    fn project_groups_and_host_paths_cross_the_boundary() {
        let dir = tempfile::tempdir().unwrap();
        let client = CoreClient::new(
            CoreConfig {
                data_dir: dir.path().to_string_lossy().to_string(),
                device_name: "Pixel 9".into(),
                platform: "android".into(),
                app_version: "0.9.2".into(),
                show_mock_harness: true,
                native_lib_dir: None,
            },
            Vec::new(),
            Arc::new(Recorder::default()),
        )
        .unwrap();
        assert!(client.project_groups().is_empty());
        // The host-path helpers, through the FFI names.
        assert_eq!(host_path_parent("C:\\Users".into()), Some("C:\\".into()));
        assert_eq!(host_path_parent("/".into()), None);
        assert_eq!(host_path_join("/home".into(), "vu".into()), String::from("/home/vu"));
        assert_eq!(host_path_name("C:\\Users\\me".into()), String::from("me"));
        assert!(host_path_is_root("C:".into()));
        assert!(!host_path_is_root("C:\\Users".into()));
        assert!(host_path_is_windows("\\\\srv\\share".into()));
        assert!(!host_path_is_windows("/home".into()));
        assert!(host_path_same_folder("C:\\Repo\\".into(), "c:/repo".into()));
        assert!(!host_path_same_folder("/home/vu".into(), "/home/VU".into()));
        client.shutdown();
    }

    /// The FFI `NewSession` maps onto the client record, and an unknown
    /// harness string is refused at the boundary.
    #[test]
    fn new_session_maps_and_validates_its_config() {
        use crate::client_ffi::types::ChatConfig;
        let ok: mc::NewSession = NewSession {
            target: SessionTarget::Project {
                space_id: "engine:v1:xyz".into(),
            },
            config: Some(ChatConfig {
                harness: "mock".into(),
                model: Some("mock-1".into()),
                reasoning: None,
                model_options: Default::default(),
                sandbox: crate::client_ffi::types::SandboxLevel::WorkspaceWrite,
            }),
            branch: Some("main".into()),
            cwd: None,
            title: Some("My chat".into()),
            parent_chat_id: None,
        }
        .try_into()
        .unwrap();
        assert_eq!(
            ok.target,
            mc::SessionTarget::Project {
                space_id: "engine:v1:xyz".into()
            }
        );
        assert!(ok.config.is_some());
        assert_eq!(ok.branch.as_deref(), Some("main"));
        assert_eq!(ok.parent_chat_id, None);
        let side: mc::NewSession = NewSession {
            target: SessionTarget::Projectless {
                device_id: "engine:v1:dev".into(),
            },
            config: None,
            branch: None,
            cwd: None,
            title: None,
            parent_chat_id: Some("engine:v1:chat".into()),
        }
        .try_into()
        .unwrap();
        assert_eq!(side.parent_chat_id.as_deref(), Some("engine:v1:chat"));
        assert!(matches!(side.target, mc::SessionTarget::Projectless { .. }));
        let bad = NewSession {
            target: SessionTarget::Projectless {
                device_id: "engine:v1:dev".into(),
            },
            config: Some(ChatConfig {
                harness: "no-such-harness".into(),
                model: None,
                reasoning: None,
                model_options: Default::default(),
                sandbox: crate::client_ffi::types::SandboxLevel::WorkspaceWrite,
            }),
            branch: None,
            cwd: None,
            title: None,
            parent_chat_id: None,
        };
        let error = TryInto::<mc::NewSession>::try_into(bad).unwrap_err();
        assert!(matches!(error, crate::client_ffi::CoreError::InvalidArgument { .. }));
    }
}
