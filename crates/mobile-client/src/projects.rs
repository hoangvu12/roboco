//! Project operations and host-side browsing (ticket 09): the New Project
//! folder browser's data (`list_folders`, `list_drives`), the manual-path
//! flow (`prepare_space_path` + `create_project`'s create-if-missing
//! follow-up), refs and worktrees.
//!
//! Every method takes `device_id` in the form 07's `DeviceView.id` /
//! `ProjectView.device_id` carry — the **scoped** engine device id (a
//! bare engine device id or a raw engine id also resolves, mirroring 06's
//! `resolve`) — and routes the call to the owning engine. A device that
//! cannot host sessions (`ios`/`android`/`ipados`) is refused with
//! `InvalidArgument` before anything is written (`uc:730-734`).
//!
//! Paths are the engine's own strings — a Windows engine answers with
//! `C:\…` and expects the same form back: never normalize separators
//! client-side ([`crate::host_paths`] computes in the host's style). The
//! calls are independent round trips: the browser UI must drop replies
//! that no longer match the device or folder it is showing (iOS guards
//! the same staleness in `NewProjectViewController.swift:98-143`).

use std::sync::Arc;
use std::time::Duration;

use roboco_engine_client::registry::{EngineKey, ScopedId};
use serde::Deserialize;

use crate::engines::EngineHandle;
use crate::{Client, ClientError, ClientInner, lock};

/// `create_project` waits for the engine's own space row this long before
/// returning the id anyway (the web's 100 ms × 50, `uc:976-990`): the
/// caller can open a session in it right away.
const SPACE_POLL_EVERY: Duration = Duration::from_millis(100);
const SPACE_POLL_TRIES: usize = 50;

/// The execution-host gate: phones and tablets never run sessions
/// (`uc:730-734`), the one predicate 07's `DeviceView` also applies.
/// `None` (WatchDevices not delivered yet) never hides a host.
pub(crate) fn refuse_non_host(platform: Option<&str>) -> Result<(), ClientError> {
    if platform.is_some_and(|platform| {
        !crate::workspace::view::is_execution_host(platform)
    }) {
        return Err(ClientError::InvalidArgument {
            message: "that device can't host sessions".into(),
        });
    }
    Ok(())
}

/// A device the client resolved to its owning engine.
pub(crate) struct HostDevice {
    /// A handle to the owning engine.
    pub(crate) handle: EngineHandle,
    /// The engine id (the `EngineKey` string).
    pub(crate) engine_id: String,
    /// The engine's own device id, wire (raw) form — what `createSpace`'s
    /// `deviceId` and a projectless `createChat`'s `deviceId` carry.
    pub(crate) raw_device_id: String,
    /// The scoped device id — 07's `DeviceView.id` form.
    pub(crate) scoped_device_id: String,
}

/// Resolve the `device_id` the platform holds to its engine and its raw
/// wire id: a scoped id names its engine, a bare engine device id matches
/// the engine pinned to it, and a raw engine id works directly (06's
/// `resolve`, extended with the raw device id). The platform gate refuses
/// non-hosts.
pub(crate) fn resolve_host_device(
    inner: &Arc<ClientInner>,
    device_id: &str,
) -> Result<HostDevice, ClientError> {
    // Everything the engine table knows, in one lock pass.
    let (engine_id, raw_device_id, platform) = {
        if ScopedId::is_scoped(device_id) {
            let scoped = ScopedId::parse(device_id).map_err(|_| ClientError::NotFound {
                message: format!("Unknown device: {device_id}"),
            })?;
            let state = lock(&inner.engines);
            let entry = state
                .entries
                .iter()
                .find(|entry| entry.paired.engine_id == scoped.engine.0)
                .ok_or_else(|| ClientError::NotFound {
                    message: format!("Unknown device: {device_id}"),
                })?;
            let platform = entry
                .rows
                .devices
                .iter()
                .find(|device| device.id == scoped.raw_id)
                .map(|device| device.platform.clone());
            (scoped.engine.0, scoped.raw_id, platform)
        } else {
            let state = lock(&inner.engines);
            let entry = state
                .entries
                .iter()
                .find(|entry| {
                    entry
                        .info
                        .as_ref()
                        .map(|info| info.device_id.as_str())
                        .or(entry.paired.device_id.as_deref())
                        == Some(device_id)
                })
                .or_else(|| {
                    state
                        .entries
                        .iter()
                        .find(|entry| entry.paired.engine_id == device_id)
                })
                .ok_or_else(|| ClientError::NotFound {
                    message: format!("Unknown device: {device_id}"),
                })?;
            let raw_device_id = entry
                .info
                .as_ref()
                .map(|info| info.device_id.clone())
                .or_else(|| entry.paired.device_id.clone())
                .ok_or_else(|| ClientError::NotFound {
                    message: format!("Unknown device: {device_id}"),
                })?;
            let platform = entry
                .rows
                .devices
                .iter()
                .find(|device| device.id == raw_device_id)
                .map(|device| device.platform.clone());
            (entry.paired.engine_id.clone(), raw_device_id, platform)
        }
    };
    refuse_non_host(platform.as_deref())?;
    let handle = crate::engine_handle_of(inner, &engine_id)?;
    let scoped_device_id = ScopedId::encode(&EngineKey(engine_id.clone()), &raw_device_id);
    Ok(HostDevice {
        handle,
        engine_id,
        raw_device_id,
        scoped_device_id,
    })
}

/// The engine's answer to `PrepareSpacePath`: the engine-normalized path
/// (`~` expanded on the engine, its own separators), whether it exists,
/// and whether it is a git work tree. Errors come back verbatim as
/// `HostError` ("enter a folder path", "the path is a file, not a
/// folder", …).
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PreparedPath {
    pub path: String,
    pub exists: bool,
    pub git_detected: bool,
}

/// Decode an engine reply, mapping a decode failure to `HostError`.
fn decode<T: serde::de::DeserializeOwned>(value: serde_json::Value) -> Result<T, ClientError> {
    serde_json::from_value(value).map_err(|error| ClientError::HostError {
        message: error.to_string(),
    })
}

impl Client {
    /// Browse one folder level on an engine device. `path: None` (or
    /// blank) browses the engine user's home folder. `show_hidden` also
    /// lists dot-folders — the engine's only switch for them (`query:
    /// "."`). The listing is sorted engine-side (directories first,
    /// name-sorted); `truncated` reports the entry cap.
    ///
    /// Each call is an independent round trip: a device or folder switch
    /// mid-flight makes an earlier reply stale, and the UI must drop it
    /// (the reply carries no sequence stamp).
    pub async fn list_folders(
        &self,
        device_id: &str,
        path: Option<&str>,
        show_hidden: bool,
    ) -> Result<roboco_proto::FolderListing, ClientError> {
        let handle = resolve_host_device(&self.inner_arc(), device_id)?.handle;
        let mut params = serde_json::Map::new();
        if let Some(path) = path.filter(|path| !path.trim().is_empty()) {
            params.insert("path".into(), path.into());
        }
        // `query` only toggles hidden entries when it starts with `.`;
        // any other value is ignored by the engine, so send "" / ".".
        params.insert(
            "query".into(),
            if show_hidden { "." } else { "" }.into(),
        );
        let value = handle
            .call(
                roboco_rpc::methods::LIST_FOLDERS,
                serde_json::Value::Object(params),
            )
            .await?;
        decode(value)
    }

    /// The engine device's browse roots beyond home: mounted drives /
    /// volumes (Linux reads `/proc/mounts`, macOS `/Volumes`, Windows the
    /// drive letters). Feeds the folder browser's locations rail.
    pub async fn list_drives(
        &self,
        device_id: &str,
    ) -> Result<Vec<roboco_proto::DriveEntry>, ClientError> {
        let handle = resolve_host_device(&self.inner_arc(), device_id)?.handle;
        let value = handle
            .call(roboco_rpc::methods::LIST_DRIVES, serde_json::json!({}))
            .await?;
        let listing: roboco_proto::DriveListing = decode(value)?;
        Ok(listing.drives)
    }

    /// Interpret a manually typed project path **on the engine** (Roboco
    /// remote-access story 20): `~` expands there, Windows-ness is checked
    /// there, and `create_if_missing` runs `create_dir_all`. Always pass
    /// `prepared.path` on to [`Client::create_project`] — never the raw
    /// input. Error strings ("enter an absolute folder path for this
    /// engine", …) surface verbatim as `HostError`.
    pub async fn prepare_space_path(
        &self,
        device_id: &str,
        path: &str,
        create_if_missing: bool,
    ) -> Result<PreparedPath, ClientError> {
        let handle = resolve_host_device(&self.inner_arc(), device_id)?.handle;
        let value = handle
            .call(
                roboco_rpc::methods::PREPARE_SPACE_PATH,
                serde_json::json!({
                    "path": path,
                    "createIfMissing": create_if_missing,
                }),
            )
            .await?;
        decode(value)
    }

    /// Create (or find) the project for `path` on the engine device
    /// (`Mutate createSpace`) — the New Project flow's commit. Returns
    /// the **scoped** space id.
    ///
    /// A space on the same device in the same folder dedupes to its id
    /// without a second write (case- and separator-insensitive on
    /// Windows-style paths). `git_detected` is the picker's flag (the
    /// folder's `is_repo`, or `prepare_space_path`'s); the engine's
    /// SpacesSync re-verifies. A disconnected engine refuses with
    /// `HostUnavailable` — no local row is written (ADR 0004: the engine
    /// owns the row).
    pub async fn create_project(
        &self,
        device_id: &str,
        path: &str,
        git_detected: bool,
    ) -> Result<String, ClientError> {
        let host = resolve_host_device(&self.inner_arc(), device_id)?;
        // 1. Same-folder dedupe (#621): an existing space on this device
        //    wins without a write.
        if let Some(existing) = self.workspace().projects.iter().find(|project| {
            project.device_id == host.scoped_device_id
                && crate::host_paths::same_folder(&project.path, path)
        }) {
            return Ok(existing.id.clone());
        }
        // 2. The engine writes the row; `name` is omitted — the engine
        //    derives it from the basename.
        crate::workspace::ensure_writable(&host.handle)?;
        let raw_space_id = uuid::Uuid::new_v4().to_string();
        host.handle
            .call(
                roboco_rpc::methods::MUTATE,
                serde_json::json!({
                    "op": "createSpace",
                    "spaceId": raw_space_id,
                    "deviceId": host.raw_device_id,
                    "path": path,
                    "gitDetected": git_detected,
                }),
            )
            .await?;
        // 3. Poll (briefly) for the engine's row so the caller can start a
        //    session in the project right away (uc:976-990); the id is
        //    returned either way.
        let scoped = ScopedId::encode(&EngineKey(host.engine_id.clone()), &raw_space_id);
        for _ in 0..SPACE_POLL_TRIES {
            if self.workspace().project(&scoped).is_some() {
                break;
            }
            tokio::time::sleep(SPACE_POLL_EVERY).await;
        }
        Ok(scoped)
    }

    /// The repository's refs on an engine device, the current branch
    /// first (iOS sorts it that way — `AppModel.swift:612-616`); the
    /// engine's own order is kept otherwise.
    pub async fn list_refs(
        &self,
        device_id: &str,
        repo_path: &str,
    ) -> Result<Vec<roboco_proto::RepoRef>, ClientError> {
        let handle = resolve_host_device(&self.inner_arc(), device_id)?.handle;
        let value = handle
            .call(
                roboco_rpc::methods::LIST_REFS,
                serde_json::json!({ "repoPath": repo_path }),
            )
            .await?;
        let mut refs: Vec<roboco_proto::RepoRef> = decode(value)?;
        // A stable partition: current refs ahead of the rest.
        refs.sort_by_key(|r| !r.current);
        Ok(refs)
    }

    /// Create a linked worktree off `base` under the engine's worktrees
    /// root and return its path. Kept for FFI parity — new-session
    /// worktrees ride the RunRequest's `WorktreeSpec` and the engine
    /// materializes them on run; neither client calls this today.
    ///
    /// `space_id` (scoped or raw — the wire layer decodes scoped ids)
    /// ties the worktree to a space so its setup Action, if any, runs in
    /// the fresh checkout; `None` creates a bare worktree.
    pub async fn create_worktree(
        &self,
        device_id: &str,
        space_id: Option<&str>,
        repo_path: &str,
        base: &str,
    ) -> Result<String, ClientError> {
        let host = resolve_host_device(&self.inner_arc(), device_id)?;
        let mut params = serde_json::Map::new();
        params.insert("repoPath".into(), repo_path.into());
        params.insert("branch".into(), base.into());
        if let Some(space_id) = space_id.filter(|id| !id.is_empty()) {
            params.insert("spaceId".into(), space_id.into());
        }
        let value = host
            .handle
            .call(
                roboco_rpc::methods::CREATE_WORKTREE,
                serde_json::Value::Object(params),
            )
            .await?;
        // The flattened `CreateWorktreeOutcome` carries the worktree's
        // `path` at the top level.
        value
            .get("path")
            .and_then(serde_json::Value::as_str)
            .map(str::to_owned)
            .ok_or_else(|| ClientError::HostError {
                message: "CreateWorktree returned no path".into(),
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{Rig, git, git_available, init_repo};

    fn skip_without_git() -> bool {
        if git_available() {
            return false;
        }
        eprintln!("skipping: git is not on PATH");
        true
    }

    /// `list_folders`: home when `None`, one folder level, `is_repo` for
    /// a `.git`-carrying child, and the hidden switch.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn list_folders_walks_home_children_and_hidden() {
        let rig = Rig::start().await;
        let device = rig.device_id();
        // Home first: the engine's own home path (its platform order:
        // USERPROFILE wins on Windows, HOME elsewhere).
        let home = rig
            .client
            .list_folders(&device, None, false)
            .await
            .expect("home listing");
        #[cfg(windows)]
        let expected = std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME"));
        #[cfg(not(windows))]
        let expected = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"));
        let expected_home = expected
            .map(std::path::PathBuf::from)
            .expect("a home on the test host");
        assert_eq!(
            home.path,
            expected_home.to_string_lossy().to_string(),
            "None browses the engine user's home"
        );
        // A temp folder: children, is_repo, hidden.
        let folder = rig.temp_folder("browse");
        std::fs::create_dir_all(folder.join("child")).unwrap();
        std::fs::create_dir_all(folder.join("child").join(".git")).unwrap();
        std::fs::create_dir_all(folder.join(".hidden")).unwrap();
        std::fs::write(folder.join("file.txt"), "x").unwrap();
        let listing = rig
            .client
            .list_folders(&device, Some(&folder.to_string_lossy()), false)
            .await
            .expect("folder listing");
        assert_eq!(listing.path, folder.to_string_lossy().to_string());
        let names: Vec<&str> = listing.entries.iter().map(|e| e.name.as_str()).collect();
        assert!(names.contains(&"child"));
        assert!(names.contains(&"file.txt"));
        assert!(!names.contains(&".hidden"), "dot-folders are hidden by default");
        let child = listing.entries.iter().find(|e| e.name == "child").unwrap();
        assert!(child.is_dir);
        assert!(child.is_repo, "a .git dir marks a repo");
        let file = listing.entries.iter().find(|e| e.name == "file.txt").unwrap();
        assert!(!file.is_dir && !file.is_repo);
        assert!(!listing.truncated);
        // show_hidden lists the dot-folder.
        let shown = rig
            .client
            .list_folders(&device, Some(&folder.to_string_lossy()), true)
            .await
            .expect("hidden listing");
        assert!(
            shown.entries.iter().any(|e| e.name == ".hidden"),
            "show_hidden lists dot-folders"
        );
        // A blank path is home, exactly like None.
        let blank = rig
            .client
            .list_folders(&device, Some("  "), false)
            .await
            .expect("blank listing");
        assert_eq!(blank.path, home.path);
        rig.stop().await;
    }

    /// `list_folders` passes the engine's truncation flag through (the
    /// cap is 500 entries).
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn list_folders_reports_truncation() {
        let rig = Rig::start().await;
        let device = rig.device_id();
        let folder = rig.temp_folder("truncated");
        for i in 0..501 {
            std::fs::write(folder.join(format!("f{i:03}.txt")), "x").unwrap();
        }
        let listing = rig
            .client
            .list_folders(&device, Some(&folder.to_string_lossy()), false)
            .await
            .expect("folder listing");
        assert!(listing.truncated, "501 entries hit the cap");
        assert_eq!(listing.entries.len(), 500);
        rig.stop().await;
    }

    /// `list_drives` reports at least one root on Windows and Linux.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn list_drives_reports_at_least_one_root() {
        let rig = Rig::start().await;
        let device = rig.device_id();
        let drives = rig.client.list_drives(&device).await.expect("drives");
        assert!(
            !drives.is_empty(),
            "every supported host reports at least one drive"
        );
        for drive in &drives {
            assert!(!drive.name.is_empty());
            assert!(std::path::Path::new(&drive.path).is_absolute(), "{}", drive.path);
        }
        rig.stop().await;
    }

    /// `prepare_space_path`: create-if-missing both ways, the relative and
    /// file error strings verbatim, and git detection.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn prepare_space_path_creates_and_validates() {
        let rig = Rig::start().await;
        let device = rig.device_id();
        let base = rig.temp_folder("prepare");
        // Missing + no create: exists false, nothing on disk.
        let missing = base.join("gone");
        let prepared = rig
            .client
            .prepare_space_path(&device, &missing.to_string_lossy(), false)
            .await
            .expect("prepare");
        assert!(!prepared.exists);
        assert!(!prepared.git_detected);
        assert!(!missing.exists());
        assert_eq!(prepared.path, missing.to_string_lossy().to_string());
        // Missing + create: the folder appears.
        let created = rig
            .client
            .prepare_space_path(&device, &missing.to_string_lossy(), true)
            .await
            .expect("prepare create");
        assert!(created.exists);
        assert!(missing.is_dir());
        // A relative path is refused, verbatim.
        let error = rig
            .client
            .prepare_space_path(&device, "relative/path", false)
            .await
            .expect_err("relative refused");
        assert_eq!(
            error,
            ClientError::HostError {
                message: "enter an absolute folder path for this engine".into()
            }
        );
        // A file is refused, verbatim.
        let file = base.join("file.txt");
        std::fs::write(&file, "x").unwrap();
        let error = rig
            .client
            .prepare_space_path(&device, &file.to_string_lossy(), false)
            .await
            .expect_err("file refused");
        assert_eq!(
            error,
            ClientError::HostError {
                message: "the path is a file, not a folder".into()
            }
        );
        // Git detection: a `.git` dir marks the folder.
        let repo = base.join("repo");
        std::fs::create_dir_all(repo.join(".git")).unwrap();
        let prepared = rig
            .client
            .prepare_space_path(&device, &repo.to_string_lossy(), false)
            .await
            .expect("prepare repo");
        assert!(prepared.exists && prepared.git_detected);
        rig.stop().await;
    }

    /// `create_project`: creates the space, returns its scoped id, the
    /// project appears in `projects()`, `git_detected` passes through, and
    /// a second call in the same folder dedupes without a second space —
    /// case- and separator-insensitive on Windows-style paths, trailing
    /// separators on both styles (#621).
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn create_project_creates_and_dedupes() {
        let rig = Rig::start().await;
        let device = rig.device_id();
        let folder = rig.temp_folder("project");
        let id = rig
            .client
            .create_project(&device, &folder.to_string_lossy(), true)
            .await
            .expect("create_project");
        assert!(ScopedId::is_scoped(&id));
        // The space appears in the merged workspace.
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
        loop {
            let found = rig
                .client
                .workspace()
                .project(&id)
                .is_some_and(|project| project.git_detected);
            if found {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the project never appeared"
            );
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
        let workspace = rig.client.workspace();
        let project = workspace.project(&id).unwrap();
        assert_eq!(project.path, folder.to_string_lossy().to_string());
        assert_eq!(project.device_id, device);
        // A trailing-separator variant of the same folder dedupes.
        let variant = format!("{}/", folder.to_string_lossy().trim_end_matches(['/', '\\']));
        let again = rig
            .client
            .create_project(&device, &variant, false)
            .await
            .expect("dedupe");
        assert_eq!(again, id);
        // The Windows case-insensitive rule, exercised on any host: the
        // engine stores the path verbatim, so a Windows-style space dedupes
        // against its case-folded twin.
        let windows = "C:\\Repo\\Alpha";
        let windows_id = rig
            .client
            .create_project(&device, windows, false)
            .await
            .expect("windows-style create");
        let folded = rig
            .client
            .create_project(&device, "c:/repo/ALPHA\\", false)
            .await
            .expect("windows-style dedupe");
        assert_eq!(folded, windows_id);
        // Exactly the two spaces the engine was asked for.
        let spaces = rig.core.workspace.read_spaces().expect("spaces");
        assert_eq!(spaces.len(), 2, "no extra createSpace: {spaces:?}");
        rig.stop().await;
    }

    /// `create_project` offline: `HostUnavailable`, no space anywhere.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn create_project_offline_refuses() {
        let mut rig = Rig::start().await;
        let device = rig.device_id();
        rig.disconnect().await;
        let folder = rig.temp_folder("offline-project");
        let error = rig
            .client
            .create_project(&device, &folder.to_string_lossy(), false)
            .await
            .expect_err("offline refuses");
        assert!(matches!(error, ClientError::HostUnavailable { .. }), "{error:?}");
        assert!(rig.client.workspace().projects.is_empty());
        rig.stop().await;
    }

    /// `list_refs`: the current branch sorts first on a two-branch repo.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn list_refs_current_branch_first() {
        if skip_without_git() {
            return;
        }
        let rig = Rig::start().await;
        let device = rig.device_id();
        let repo = rig.temp_folder("refs-repo");
        init_repo(&repo, &["feature"]);
        let refs = rig
            .client
            .list_refs(&device, &repo.to_string_lossy())
            .await
            .expect("list_refs");
        let names: Vec<&str> = refs.iter().map(|r| r.name.as_str()).collect();
        assert!(names.contains(&"main") && names.contains(&"feature"), "{names:?}");
        assert_eq!(refs[0].name, "main", "the current branch is first");
        assert!(refs[0].current);
        assert!(!refs.iter().skip(1).any(|r| r.current));
        assert!(refs.iter().all(|r| r.worktree_path.is_none()));
        rig.stop().await;
    }

    /// `create_worktree` returns an existing directory under the engine's
    /// worktrees root, on a fresh `roboco/<name>` branch.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn create_worktree_returns_an_existing_folder() {
        if skip_without_git() {
            return;
        }
        let rig = Rig::start().await;
        let device = rig.device_id();
        let repo = rig.temp_folder("worktree-repo");
        init_repo(&repo, &[]);
        let path = rig
            .client
            .create_worktree(&device, None, &repo.to_string_lossy(), "HEAD")
            .await
            .expect("create_worktree");
        assert!(
            std::path::Path::new(&path).is_dir(),
            "the worktree exists: {path}"
        );
        // It is a checkout of the same repository, on a roboco/ branch.
        git(std::path::Path::new(&path), &["rev-parse", "--abbrev-ref", "HEAD"]);
        let branch = String::from_utf8(
            std::process::Command::new("git")
                .args(["rev-parse", "--abbrev-ref", "HEAD"])
                .current_dir(&path)
                .output()
                .expect("git runs")
                .stdout,
        )
        .expect("utf8");
        assert!(branch.trim().starts_with("roboco/"), "{branch}");
        // Unless the test host pins a worktrees root, it is the default
        // `~/.roboco/worktrees`.
        if std::env::var_os("ROBOCO_WORKTREES_DIR").is_none() {
            let home = std::env::var_os("HOME")
                .or_else(|| std::env::var_os("USERPROFILE"))
                .map(std::path::PathBuf::from)
                .expect("a home on the test host");
            assert!(
                std::path::Path::new(&path).starts_with(home.join(".roboco").join("worktrees")),
                "{path}"
            );
        }
        rig.stop().await;
    }
}
