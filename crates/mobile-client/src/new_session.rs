//! The New Session canvas's data plane (ticket 09): the project groups
//! (#799, project-first, then device), chat creation on the owning engine
//! (`Mutate createChat` + `renameChat`), and the optimistic pending-chat
//! overlay that shows the row before `WatchChats` echoes it.
//!
//! Roboco is engine-local (ADR 0004): the chat row lives on the engine, so
//! unlike upstream's registry write nothing is stored locally — the
//! pending overlay is a derived extra row source, not a replica. A write
//! to a disconnected engine is refused (`HostUnavailable`), never queued
//! (remote-access story 24).
//!
//! The first send stays three Kotlin-driven calls (create → open → send,
//! exactly as iOS does — `ios:App/AppModel.swift:570-594`): no combined
//! helper here. 08's `send` reads the pending chat's `cwd`/`config`.

use std::collections::HashMap;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use roboco_engine_client::registry::{EngineKey, ScopedId};
use roboco_proto::ChatIndicator;

use crate::workspace::ProjectView;
use crate::{Client, ClientError, ClientInner};

/// How long a pending chat stays in the workspace before the engine's
/// `WatchChats` echo is assumed lost: the overlay drops after this, and
/// the engine's own frame restores the row when it lands.
pub const PENDING_CHAT_TTL_MS: i64 = 30_000;

/// Where a new session runs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionTarget {
    /// In a project (host = its device, cwd = its folder or `cwd` override).
    Project { space_id: String },
    /// No project: the chosen host's home folder.
    Projectless { device_id: String },
}

/// The New Session canvas's commit payload (FFI record of the same name).
#[derive(Debug, Clone, PartialEq)]
pub struct NewSession {
    pub target: SessionTarget,
    /// The picked run configuration; the caller sets
    /// `sandbox: workspace-write` (iOS always does).
    pub config: Option<roboco_proto::ChatConfig>,
    /// The picked ref, named on the row from the first frame. Sent only
    /// when non-empty; iOS passes one only when *not* creating a worktree.
    pub branch: Option<String>,
    /// Cwd override (an existing worktree's path); the engine defaults to
    /// the space folder.
    pub cwd: Option<String>,
    /// A non-blank title rides a `renameChat` after the create.
    pub title: Option<String>,
    /// A side chat of this chat (upstream #899): `target`, `cwd` and
    /// `branch` are ignored — it runs in its parent's checkout on its
    /// parent's host, and lists under the parent. A side chat's own side
    /// chats hang off the same root instead (siblings).
    pub parent_chat_id: Option<String>,
}

/// One chat created but not yet echoed by `WatchChats` — the optimistic
/// overlay 07's derive renders as a synthetic row and 08's `send` reads
/// for the RunRequest's `cwd`/`config`. Ids are the scoped forms the
/// workspace speaks; the engine sees only `raw_chat_id`.
#[derive(Debug, Clone)]
pub struct PendingChat {
    /// The owning engine (the `EngineKey` string).
    pub engine_id: String,
    /// The scoped chat id (`engine:v1:…`) — the overlay's key.
    pub chat_id: String,
    /// The engine's raw chat id — the WatchChats echo match.
    pub raw_chat_id: String,
    /// The scoped space id (`None` = projectless).
    pub space_id: Option<String>,
    /// The scoped device id of the execution host.
    pub device_id: String,
    /// The run cwd: the `NewSession.cwd` override, else the space path,
    /// else `~`.
    pub cwd: String,
    /// The chat's run configuration.
    pub config: Option<roboco_proto::ChatConfig>,
    pub branch: Option<String>,
    /// The parent a side chat hangs off (scoped); `None` for a top-level
    /// chat. The synthetic row lists under its parent, never the front
    /// page.
    pub parent_chat_id: Option<String>,
    pub created_at: DateTime<Utc>,
}

impl PendingChat {
    /// Whether the overlay is finished — the engine's frame carries the
    /// row (the echo), or the 30 s TTL ran out (the row is restored by the
    /// frame when it arrives). Pure — the store's recompute calls it on
    /// every pass.
    pub(crate) fn finished(&self, echoed: bool, now_ms: i64) -> bool {
        echoed
            || now_ms.saturating_sub(self.created_at.timestamp_millis())
                >= PENDING_CHAT_TTL_MS
    }

    /// The derive's synthetic row: title `None`, `archived = false`, the
    /// created config/cwd/branch — scoped ids, shaped exactly like a
    /// merged engine row so `build_row` needs no special case.
    pub(crate) fn synthetic_chat(&self, chat_id: &str) -> roboco_proto::Chat {
        roboco_proto::Chat {
            agent_origin: None,
            id: chat_id.to_owned(),
            device_id: self.device_id.clone(),
            title: None,
            archived: false,
            cwd: Some(self.cwd.clone()),
            branch: self.branch.clone(),
            checkout_id: None,
            source_context: None,
            config: self.config.clone(),
            last_message_preview: None,
            last_message_at: None,
            created_at: self.created_at,
            harness_session_id: None,
            harness_session_cwd: None,
            space_id: self.space_id.clone(),
            last_seen_at: None,
            room_gen: None,
            parent_chat_id: self.parent_chat_id.clone(),
        }
    }
}

// ── project groups (#799) ──────────────────────────────────────────────────

/// One repository's checkouts, as the New Session project menu shows them:
/// one entry per `group_key`, named for the representative (oldest)
/// checkout, its hosts in the subtitle. Clones of one repository on
/// **different engines** fall in one group — the merged workspace groups
/// across the fleet.
#[derive(Debug, Clone, PartialEq)]
pub struct ProjectGroup {
    /// `"repo:<repositoryId>"`, else the scoped space id.
    pub group_key: String,
    /// The shared `group_name` (the representative checkout's).
    pub name: String,
    /// The shared palette slot.
    pub color_index: u32,
    /// Any checkout of the repository carries git.
    pub git_detected: bool,
    /// Most urgent indicator across the checkouts' sessions (Idle when
    /// none).
    pub indicator: ChatIndicator,
    /// The checkouts: online first, then device name (lowercased), then
    /// path.
    pub checkouts: Vec<ProjectView>,
}

/// Group projects by `group_key` (#799). Groups sort by lowercased name,
/// then `group_key`; checkouts sort online first, then device name
/// (lowercased), then path. Pure.
pub fn group_projects(projects: &[ProjectView]) -> Vec<ProjectGroup> {
    let mut by_key: HashMap<&str, Vec<&ProjectView>> = HashMap::new();
    for project in projects {
        by_key.entry(project.group_key.as_str()).or_default().push(project);
    }
    let mut groups: Vec<ProjectGroup> = by_key
        .into_iter()
        .map(|(group_key, mut members)| {
            // One representative: the group_name/color_index are shared by
            // construction — read them off the oldest checkout so the
            // grouping is deterministic.
            members.sort_by(|a, b| {
                a.created_at_ms
                    .cmp(&b.created_at_ms)
                    .then_with(|| a.id.cmp(&b.id))
            });
            let representative = members[0];
            let mut checkouts: Vec<ProjectView> = members.into_iter().cloned().collect();
            checkouts.sort_by(|a, b| {
                b.device_online
                    .cmp(&a.device_online)
                    .then_with(|| {
                        a.device_name
                            .as_deref()
                            .unwrap_or_default()
                            .to_lowercase()
                            .cmp(&b.device_name.as_deref().unwrap_or_default().to_lowercase())
                    })
                    .then_with(|| a.path.cmp(&b.path))
                    .then_with(|| a.id.cmp(&b.id))
            });
            let indicator = checkouts
                .iter()
                .map(|project| project.indicator)
                .min_by_key(|indicator| roboco_proto::view::attention_rank(*indicator))
                .unwrap_or(ChatIndicator::Idle);
            ProjectGroup {
                group_key: group_key.to_owned(),
                name: representative.group_name.clone(),
                color_index: representative.color_index,
                git_detected: checkouts.iter().any(|project| project.git_detected),
                indicator,
                checkouts,
            }
        })
        .collect();
    groups.sort_by(|a, b| {
        a.name
            .to_lowercase()
            .cmp(&b.name.to_lowercase())
            .then_with(|| a.group_key.cmp(&b.group_key))
    });
    groups
}

// ── create_session ─────────────────────────────────────────────────────────

/// The resolved [`NewSession`] target: what the wire call and the pending
/// overlay need.
struct ResolvedTarget {
    /// A handle to the engine that owns the row.
    handle: crate::engines::EngineHandle,
    /// The scoped space id (`None` = projectless).
    space_id: Option<String>,
    /// The engine's raw space id (wire form); `None` for a projectless
    /// chat, which sends `deviceId` instead.
    raw_space_id: Option<String>,
    /// The engine's raw device id (wire form, projectless only).
    raw_device_id: Option<String>,
    /// The scoped device id of the host.
    device_id: String,
    /// The pending overlay's cwd: override, else the space path, else `~`.
    cwd: String,
}

impl Client {
    /// The project menu's groups (#799, project-first then device): 07's
    /// `ProjectView`s grouped by repository across every engine, one group
    /// per `group_key`, sorted by name. Without a project the host menu is
    /// 07's `execution_devices()`.
    pub fn project_groups(&self) -> Vec<ProjectGroup> {
        group_projects(&self.workspace().projects)
    }

    /// Create a chat on the target's engine (`Mutate createChat`) — the
    /// New Session canvas's commit. Returns the **scoped** chat id.
    ///
    /// The row appears in the workspace at once through a pending-chat
    /// overlay (07's derive renders a synthetic row); the engine's
    /// `WatchChats` frame replaces it, or the overlay drops after 30 s.
    /// A non-blank `title` rides a `renameChat` right after the create.
    /// An engine that is not `Connected` refuses with `HostUnavailable`
    /// (nothing is queued — story 24); a device that cannot host sessions
    /// (`ios`/`android`/`ipados`) refuses with `InvalidArgument`.
    ///
    /// The first send stays the caller's three steps — `create_session`,
    /// `open_session`, `SessionHandle::send` — exactly as iOS drives them;
    /// when the draft asks for a new worktree, `send` carries the
    /// `WorktreeSpec` and the engine materializes it on run.
    pub async fn create_session(&self, new: NewSession) -> Result<String, ClientError> {
        let inner = self.inner_arc();
        // A side chat of an open chat (upstream #899): everything comes
        // from the parent's row — target, cwd and branch are ignored.
        if let Some(parent_id) = new.parent_chat_id.as_deref() {
            return self
                .create_side_chat(&inner, parent_id, new.config, new.title)
                .await;
        }
        // 1. Resolve the target: the project (its engine owns the row, the
        //    default cwd is its folder) or an execution host.
        let target = match new.target {
            SessionTarget::Project { space_id } => {
                let project = self
                    .workspace()
                    .project(&space_id)
                    .cloned()
                    .ok_or_else(|| ClientError::NotFound {
                        message: format!("Unknown project: {space_id}"),
                    })?;
                // The workspace only holds scoped ids, so this parse is
                // the raw wire form of a space the merge scoped.
                let raw_space_id = ScopedId::parse(&space_id)
                    .map_err(|_| ClientError::InvalidArgument {
                        message: format!("Unknown project: {space_id}"),
                    })?
                    .raw_id;
                let handle = self.engine_handle(&project.engine_id)?;
                ResolvedTarget {
                    handle,
                    space_id: Some(space_id),
                    raw_space_id: Some(raw_space_id),
                    raw_device_id: None,
                    device_id: project.device_id.clone(),
                    cwd: new.cwd.clone().unwrap_or_else(|| project.path.clone()),
                }
            }
            SessionTarget::Projectless { device_id } => {
                // The device must be an execution host: phones and tablets
                // never run sessions (`uc:730-734`).
                let host = crate::projects::resolve_host_device(&inner, &device_id)?;
                ResolvedTarget {
                    handle: host.handle,
                    space_id: None,
                    raw_space_id: None,
                    raw_device_id: Some(host.raw_device_id),
                    device_id: host.scoped_device_id,
                    cwd: new.cwd.clone().unwrap_or_else(|| "~".to_owned()),
                }
            }
        };
        // 2. The engine must be connected: nothing is queued (story 24).
        crate::workspace::ensure_writable(&target.handle)?;
        // 3. Mint the id (the web's `mintId`: a client-side UUID v4; the
        //    engine's createChat is idempotent by it).
        let raw_chat_id = uuid::Uuid::new_v4().to_string();
        // 4. Create the row. `spaceId` for a project (the engine ignores
        //    `deviceId` when it is set); `deviceId` for a projectless chat.
        //    The engine applies `branch` only when non-empty and `cwd` only
        //    when present (default = the space folder).
        let mut params = serde_json::Map::new();
        params.insert("op".into(), "createChat".into());
        params.insert("chatId".into(), raw_chat_id.clone().into());
        if let Some(space_id) = target.raw_space_id.as_deref() {
            params.insert("spaceId".into(), space_id.into());
        } else if let Some(device_id) = target.raw_device_id.as_deref() {
            params.insert("deviceId".into(), device_id.into());
        }
        if let Some(config) = &new.config {
            let wire = serde_json::to_value(config).map_err(|error| ClientError::Internal {
                message: error.to_string(),
            })?;
            params.insert("config".into(), wire);
        }
        if let Some(branch) = new.branch.as_deref().filter(|branch| !branch.is_empty()) {
            params.insert("branch".into(), branch.into());
        }
        // `cwd` only as an override (an existing worktree path): the
        // engine defaults the row to the space folder.
        if let Some(cwd) = new.cwd.as_deref() {
            params.insert("cwd".into(), cwd.into());
        }
        let params = serde_json::Value::Object(params);
        target
            .handle
            .call(roboco_rpc::methods::MUTATE, params)
            .await?;
        // 5. The title: `createChat` has no title field, so a non-blank one
        //    rides a rename. A failed rename surfaces as an error while the
        //    chat itself stands — the engine's frame restores the row.
        if let Some(title) = new.title.as_deref().map(str::trim).filter(|t| !t.is_empty()) {
            target
                .handle
                .call(
                    roboco_rpc::methods::MUTATE,
                    serde_json::json!({
                        "op": "renameChat",
                        "chatId": raw_chat_id,
                        "title": title,
                    }),
                )
                .await?;
        }
        // 6. The optimistic overlay: registered after the writes succeeded
        //    (a failed create leaves nothing behind). The recompute drops
        //    it on the first WatchChats row with this id, or after 30 s.
        let engine_id = target.handle.engine_id().to_owned();
        let scoped_chat_id = ScopedId::encode(&EngineKey(engine_id.clone()), &raw_chat_id);
        if let Some(store) = inner.workspace.get() {
            store.register_pending_chat(PendingChat {
                engine_id,
                chat_id: scoped_chat_id.clone(),
                raw_chat_id,
                space_id: target.space_id,
                device_id: target.device_id,
                cwd: target.cwd,
                config: new.config.clone(),
                branch: new.branch.clone().filter(|b| !b.is_empty()),
                parent_chat_id: None,
                created_at: Utc::now(),
            });
        }
        // 7. The scoped id: what every other client surface speaks.
        Ok(scoped_chat_id)
    }

    /// An empty side chat of `parent_id` (upstream #899's "New Side
    /// Chat"): the parent's host, project and checkout with a fresh
    /// identity, created through `Mutate createChat` with `parentChatId`.
    /// A side chat of a side chat hangs off the same root — a sibling.
    /// The run config is the caller's pick, but the sandbox stays the
    /// parent's (the checkout is the parent's). Returns the scoped id.
    async fn create_side_chat(
        &self,
        inner: &Arc<ClientInner>,
        parent_id: &str,
        config: Option<roboco_proto::ChatConfig>,
        title: Option<String>,
    ) -> Result<String, ClientError> {
        // 1. The parent's row, and the root the new chat hangs off (the
        //    parent, or the parent's parent when the parent is itself a
        //    side chat — a sibling, like the fork button).
        let workspace = self.workspace();
        let Some(parent) = workspace.session(parent_id) else {
            return Err(ClientError::NotFound {
                message: format!("Unknown session: {parent_id}"),
            });
        };
        let root = parent
            .parent_chat_id
            .as_deref()
            .and_then(|grand| workspace.session(grand))
            .unwrap_or(parent);
        // 2. The engine that owns the row — must be connected, like any
        //    create (nothing is queued; story 24).
        let handle = self.engine_handle(&root.engine_id)?;
        crate::workspace::ensure_writable(&handle)?;
        // 3. Mint the id (the engine's createChat is idempotent by it).
        let raw_chat_id = uuid::Uuid::new_v4().to_string();
        // 4. Create the row in the parent's checkout: `spaceId` when the
        //    root has a project, else the root's host device; `cwd` the
        //    root's checkout (its own override, else the project folder —
        //    the pending overlay's cwd must match what the row will say,
        //    so 08's first send runs in the parent's folder).
        let root_space = root.project.as_ref().and_then(|project| {
            let raw = ScopedId::parse(&project.id).ok()?.raw_id;
            workspace
                .project(&project.id)
                .map(|space| (raw, space.path.clone()))
        });
        let root_raw_device = ScopedId::parse(&root.device_id)
            .ok()
            .map(|scoped| scoped.raw_id)
            .ok_or_else(|| ClientError::NotFound {
                message: format!("Unknown session: {parent_id}"),
            })?;
        let cwd = root
            .cwd
            .clone()
            .or_else(|| root_space.as_ref().map(|(_, path)| path.clone()))
            .unwrap_or_else(|| "~".to_owned());
        // 5. The sandbox stays the parent's (the run config is the pick).
        let config = config.map(|mut config| {
            let parent_sandbox = inner
                .workspace
                .get()
                .and_then(|store| store.session_config(&root.id))
                .map(|wire| wire.sandbox);
            if let Some(sandbox) = parent_sandbox {
                config.sandbox = sandbox;
            }
            config
        });
        let mut params = serde_json::Map::new();
        params.insert("op".into(), "createChat".into());
        params.insert("chatId".into(), raw_chat_id.clone().into());
        params.insert("parentChatId".into(), root.id.clone().into());
        if let Some((raw_space, _)) = root_space {
            params.insert("spaceId".into(), raw_space.into());
        } else {
            params.insert("deviceId".into(), root_raw_device.into());
        }
        if let Some(config) = &config {
            let wire = serde_json::to_value(config).map_err(|error| ClientError::Internal {
                message: error.to_string(),
            })?;
            params.insert("config".into(), wire);
        }
        params.insert("cwd".into(), cwd.clone().into());
        handle
            .call(
                roboco_rpc::methods::MUTATE,
                serde_json::Value::Object(params),
            )
            .await?;
        // 6. The title rides a rename, as on the New Session canvas.
        if let Some(title) = title.as_deref().map(str::trim).filter(|t| !t.is_empty()) {
            handle
                .call(
                    roboco_rpc::methods::MUTATE,
                    serde_json::json!({
                        "op": "renameChat",
                        "chatId": raw_chat_id,
                        "title": title,
                    }),
                )
                .await?;
        }
        // 7. The optimistic overlay: the row (under its parent, never the
        //    front page) until the engine's `WatchChats` echo replaces
        //    it; 08's send reads its cwd.
        let engine_id = handle.engine_id().to_owned();
        let scoped_chat_id = ScopedId::encode(&EngineKey(engine_id.clone()), &raw_chat_id);
        if let Some(store) = inner.workspace.get() {
            store.register_pending_chat(PendingChat {
                engine_id,
                chat_id: scoped_chat_id.clone(),
                raw_chat_id,
                space_id: root.project.as_ref().map(|p| p.id.clone()),
                device_id: root.device_id.clone(),
                cwd,
                config: config.clone(),
                branch: None,
                parent_chat_id: Some(root.id.clone()),
                created_at: Utc::now(),
            });
        }
        Ok(scoped_chat_id)
    }

    /// Fork `source_id` through its latest completed response into a side
    /// chat (upstream #899's "Fork into Side Chat"): the engine copies
    /// the history, appends the fork seam and publishes the row. A side
    /// chat's fork lists as its sibling (the source's parent becomes the
    /// fork's parent). Fails with the engine's reason when nothing is
    /// completed yet. Returns the scoped id of the fork.
    pub async fn fork_side_chat(&self, source_id: &str) -> Result<String, ClientError> {
        let handle = self.resolve(source_id)?.0;
        // The parent the fork hangs under: the source, or the source's
        // parent when the source is a side chat (a sibling).
        let workspace = self.workspace();
        let parent = workspace
            .session(source_id)
            .and_then(|row| row.parent_chat_id.clone())
            .filter(|parent| !parent.is_empty())
            .and_then(|parent| {
                // A dangling parent (the row left) falls back to the source.
                workspace.session(&parent).map(|_| parent)
            })
            .unwrap_or_else(|| source_id.to_owned());
        let raw_chat_id = uuid::Uuid::new_v4().to_string();
        let reply = handle
            .call(
                roboco_rpc::methods::FORK_SIDE_CHAT,
                serde_json::json!({
                    "chatId": raw_chat_id,
                    "sourceChatId": source_id,
                    "parentChatId": parent,
                }),
            )
            .await?;
        let chat: roboco_proto::Chat = serde_json::from_value(reply).map_err(|error| {
            ClientError::Internal {
                message: format!("unexpected fork reply: {error}"),
            }
        })?;
        // The engine published the row; this client's `WatchChats` echo
        // replaces nothing here (the id is fresh) and the caller can open
        // the fork at once — a scoped id naming a paired engine opens
        // before the echo lands.
        let scoped = ScopedId::encode(&EngineKey(handle.engine_id().to_owned()), &chat.id);
        Ok(scoped)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::Rig;
    use crate::workspace::ProjectView;

    /// One `ProjectView` for the pure grouping tests.
    fn view(
        id: &str,
        engine_id: &str,
        path: &str,
        device_name: Option<&str>,
        online: bool,
        group_key: &str,
        group_name: &str,
        created_at_ms: i64,
    ) -> ProjectView {
        ProjectView {
            id: id.into(),
            engine_id: engine_id.into(),
            name: group_name.into(),
            path: path.into(),
            color_index: crate::project_color_index(path),
            device_id: format!("{engine_id}:dev"),
            device_name: device_name.map(str::to_owned),
            device_online: online,
            git_detected: true,
            created_at_ms,
            group_key: group_key.into(),
            group_name: group_name.into(),
            indicator: ChatIndicator::Idle,
            unseen_count: 0,
            sessions: Vec::new(),
        }
    }

    /// #799's pure grouping: clones of one repository on different engines
    /// share one group, named for the older checkout, online checkouts
    /// first; un-grouped projects stand alone; groups sort by name.
    #[test]
    fn group_projects_groups_by_repository_across_engines() {
        let projects = vec![
            // Two checkouts of one repository on two engines; the older one
            // (created first) speaks for the group.
            view("s-b", "e2", "/beta", Some("Laptop"), true, "repo:abc", "alpha", 2_000),
            // A standalone project sorts after the group by name.
            view("s-c", "e1", "/zeta", Some("Studio"), true, "s-c", "zeta", 500),
            view("s-a", "e1", "/alpha", Some("Studio"), false, "repo:abc", "alpha", 1_000),
        ];
        let groups = group_projects(&projects);
        assert_eq!(groups.len(), 2);
        // Group order: lowercased name ("alpha" < "zeta").
        assert_eq!(groups[0].name, "alpha");
        assert_eq!(groups[0].group_key, "repo:abc");
        assert_eq!(groups[0].color_index, crate::project_color_index("/alpha"));
        assert!(groups[0].git_detected);
        // Online first, then device name, then path.
        let checkouts: Vec<&str> = groups[0].checkouts.iter().map(|c| c.id.as_str()).collect();
        assert_eq!(checkouts, ["s-b", "s-a"], "the online checkout leads");
        // The representative is the older space, whatever sorts first.
        assert_eq!(groups[0].name, "alpha");
        assert_eq!(groups[1].name, "zeta");
        assert_eq!(groups[1].checkouts.len(), 1);
    }

    /// The group's indicator is the most urgent across its checkouts
    /// (attention rank: `AwaitingInput` outranks `Working`), and
    /// `git_detected` is "any checkout".
    #[test]
    fn group_indicator_and_git_flags_fold_across_checkouts() {
        let mut working = view("s-a", "e1", "/alpha", None, true, "repo:abc", "alpha", 1_000);
        working.indicator = ChatIndicator::Working;
        working.git_detected = false;
        let mut asking = view("s-b", "e2", "/beta", None, true, "repo:abc", "alpha", 2_000);
        asking.indicator = ChatIndicator::AwaitingInput;
        asking.git_detected = true;
        let groups = group_projects(&[working, asking]);
        assert_eq!(groups[0].indicator, ChatIndicator::AwaitingInput);
        assert!(groups[0].git_detected);
    }

    /// The 30 s TTL: echoed rows are done at once; fresh ones are not; a
    /// 29.999 s old one is not; a 30 s old one is.
    #[test]
    fn pending_chats_finish_on_echo_or_ttl() {
        let pending = PendingChat {
            engine_id: "e1".into(),
            chat_id: "engine:v1:xyz".into(),
            raw_chat_id: "raw".into(),
            space_id: None,
            device_id: "engine:v1:dev".into(),
            cwd: "~".into(),
            config: None,
            branch: None,
            parent_chat_id: None,
            created_at: DateTime::<Utc>::from_timestamp_millis(10_000).unwrap(),
        };
        assert!(pending.finished(true, 10_050));
        assert!(!pending.finished(false, 10_050));
        assert!(!pending.finished(false, 10_000 + PENDING_CHAT_TTL_MS - 1));
        assert!(pending.finished(false, 10_000 + PENDING_CHAT_TTL_MS));
        // A clock behind the create never finishes it early.
        assert!(!pending.finished(false, 9_000));
    }

    /// The synthetic chat is exactly a merged engine row's shape: title
    /// `None`, not archived, the created config/cwd/branch/space.
    #[test]
    fn the_synthetic_chat_is_a_plain_row() {
        let pending = PendingChat {
            engine_id: "e1".into(),
            chat_id: "engine:v1:xyz".into(),
            raw_chat_id: "raw".into(),
            space_id: Some("engine:v1:space".into()),
            device_id: "engine:v1:dev".into(),
            cwd: "/repo".into(),
            config: Some(roboco_proto::ChatConfig {
                harness: roboco_proto::HarnessId::Mock,
                model: Some("mock-1".into()),
                reasoning: None,
                model_options: Default::default(),
                sandbox: roboco_proto::SandboxLevel::WorkspaceWrite,
            }),
            branch: Some("main".into()),
            parent_chat_id: None,
            created_at: DateTime::<Utc>::from_timestamp_millis(10_000).unwrap(),
        };
        let chat = pending.synthetic_chat("engine:v1:xyz");
        assert_eq!(chat.id, "engine:v1:xyz");
        assert_eq!(chat.device_id, "engine:v1:dev");
        assert_eq!(chat.space_id.as_deref(), Some("engine:v1:space"));
        assert_eq!(chat.cwd.as_deref(), Some("/repo"));
        assert_eq!(chat.branch.as_deref(), Some("main"));
        assert!(chat.title.is_none());
        assert!(!chat.archived);
        assert!(chat.config.is_some());
        assert_eq!(chat.created_at.timestamp_millis(), 10_000);
    }

    /// A device that cannot host sessions is refused with the upstream
    /// message, before anything is written.
    #[test]
    fn phone_platforms_never_host_sessions() {
        for platform in ["ios", "android", "ipados"] {
            assert_eq!(
                crate::projects::refuse_non_host(Some(platform)).unwrap_err(),
                ClientError::InvalidArgument {
                    message: "that device can't host sessions".into()
                }
            );
        }
        for platform in ["windows", "macos", "linux", ""] {
            assert!(crate::projects::refuse_non_host(Some(platform)).is_ok());
        }
        // Unknown platform (WatchDevices pending): never hides a host.
        assert!(crate::projects::refuse_non_host(None).is_ok());
    }

    /// `create_session(Project)` on a real engine returns a scoped id, the
    /// engine row carries `spaceId`/`config`/`branch`/`cwd`, and the
    /// workspace shows the chat.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn create_session_in_a_project_writes_the_engine_row() {
        let rig = Rig::start().await;
        let repo = rig.temp_folder("repo");
        let space_id = rig.create_space(&repo, "create-session-space", false).await;
        let chat_id = rig
            .client
            .create_session(NewSession {
                target: SessionTarget::Project {
                    space_id: space_id.clone(),
                },
                config: Some(roboco_proto::ChatConfig {
                    harness: roboco_proto::HarnessId::Mock,
                    model: Some("mock-1".into()),
                    reasoning: None,
                    model_options: Default::default(),
                    sandbox: roboco_proto::SandboxLevel::WorkspaceWrite,
                }),
                branch: Some("main".into()),
                cwd: None,
                title: Some("  My chat  ".into()),
                parent_chat_id: None,
            })
            .await
            .expect("create_session");
        // Scoped id.
        assert!(ScopedId::is_scoped(&chat_id));
        let raw = ScopedId::parse(&chat_id).expect("scoped id parses").raw_id;
        // The engine row: space, config sandbox, branch, title, cwd.
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
        let chat = loop {
            if let Ok(Some(chat)) = rig.core.workspace.chat(&raw) {
                break chat;
            }
            assert!(std::time::Instant::now() < deadline, "engine row never appeared");
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        };
        assert_eq!(
            chat.space_id.as_deref(),
            Some(ScopedId::parse(&space_id).expect("scoped id parses").raw_id.as_str())
        );
        let config = chat.config.expect("config stamped");
        assert_eq!(config.sandbox, roboco_proto::SandboxLevel::WorkspaceWrite);
        assert_eq!(config.harness, roboco_proto::HarnessId::Mock);
        assert_eq!(chat.branch.as_deref(), Some("main"));
        assert_eq!(chat.title.as_deref(), Some("My chat"));
        assert_eq!(
            chat.cwd.as_deref(),
            Some(repo.to_string_lossy().to_string().as_str())
        );
        // The workspace row is available (the pending overlay first, then
        // the engine echo) and the overlay is gone once echoed.
        let row = rig.wait_for_session_row(&chat_id).await;
        assert_eq!(row.project.as_ref().expect("project ref").id, space_id);
        assert!(!row.archived);
        assert!(rig.pending_chat_gone(&chat_id).await, "overlay dropped after echo");
        rig.stop().await;
    }

    /// A projectless chat lands on the host device with `~` as its cwd.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn create_session_projectless_runs_on_the_host() {
        let rig = Rig::start().await;
        let device_id = rig.device_id();
        let chat_id = rig
            .client
            .create_session(NewSession {
                target: SessionTarget::Projectless { device_id },
                config: None,
                branch: None,
                cwd: None,
                title: None,
                parent_chat_id: None,
            })
            .await
            .expect("create_session");
        let raw = ScopedId::parse(&chat_id).expect("scoped id parses").raw_id;
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
        let chat = loop {
            if let Ok(Some(chat)) = rig.core.workspace.chat(&raw) {
                break chat;
            }
            assert!(std::time::Instant::now() < deadline, "engine row never appeared");
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        };
        assert_eq!(chat.space_id, None);
        assert_eq!(chat.cwd.as_deref(), Some("~"));
        let raw_device = rig.raw_device_id();
        assert_eq!(chat.device_id, raw_device);
        let row = rig.wait_for_session_row(&chat_id).await;
        assert!(row.project.is_none());
        rig.stop().await;
    }

    /// A device whose row reports a phone platform is refused with
    /// `InvalidArgument`, before any chat is created.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn create_session_refuses_a_phone_device() {
        let rig = Rig::start().await;
        // The engine's own device row, rewritten as a phone on the engine.
        // (Patching the client's cached row raced: the next WatchDevices
        // frame restored the real platform between the patch and the call -
        // a flake on CI. A foreign "phone-device" row never reaches the
        // client at all: engine-local data, ADR 0004, publishes only the
        // engine's own device.) The engine writes its own row only at boot,
        // so every later frame keeps it a phone.
        // Both ids before the flip: execution_devices() hides a phone.
        let device_id = rig.device_id();
        let raw_device = rig.raw_device_id();
        let mut own = rig
            .core
            .workspace
            .read_devices()
            .unwrap()
            .into_iter()
            .find(|d| d.id == raw_device)
            .expect("the engine's own device row");
        own.platform = "android".into();
        rig.core.workspace.upsert_device_row(&own);
        let inner = rig.client.inner_arc();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
        while !crate::lock(&inner.engines).entries.iter().any(|entry| {
            entry
                .rows
                .devices
                .iter()
                .any(|d| d.id == raw_device && d.platform == "android")
        }) {
            assert!(std::time::Instant::now() < deadline, "the phone platform never arrived");
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
        let error = rig
            .client
            .create_session(NewSession {
                target: SessionTarget::Projectless { device_id },
                config: None,
                branch: None,
                cwd: None,
                title: None,
                parent_chat_id: None,
            })
            .await
            .expect_err("phones never host sessions");
        assert_eq!(
            error,
            ClientError::InvalidArgument {
                message: "that device can't host sessions".into()
            }
        );
        rig.stop().await;
    }

    /// With the engine stopped, `create_session` refuses with
    /// `HostUnavailable` and leaves no pending overlay.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn create_session_offline_leaves_no_overlay() {
        let mut rig = Rig::start().await;
        let device_id = rig.device_id();
        rig.disconnect().await;
        let error = rig
            .client
            .create_session(NewSession {
                target: SessionTarget::Projectless { device_id },
                config: None,
                branch: None,
                cwd: None,
                title: None,
                parent_chat_id: None,
            })
            .await
            .expect_err("offline refuses");
        assert!(matches!(error, ClientError::HostUnavailable { .. }), "{error:?}");
        assert!(
            rig.client.workspace().front.recent.is_empty(),
            "no overlay row appeared"
        );
        rig.stop().await;
    }

    /// The first send on a just-created chat (the handoff's data path):
    /// create → open → send produces `Started{message_id}`, the echo is
    /// present right away, the mock reply arrives, and the chat's cwd is
    /// the space path (08's send read the pending chat's cwd/config).
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_first_send_runs_in_the_new_chat() {
        let rig = Rig::start().await;
        let repo = rig.temp_folder("first-send");
        let space_id = rig.create_space(&repo, "first-send-space", false).await;
        let chat_id = rig
            .client
            .create_session(NewSession {
                target: SessionTarget::Project {
                    space_id: space_id.clone(),
                },
                config: Some(roboco_proto::ChatConfig {
                    harness: roboco_proto::HarnessId::Mock,
                    model: Some("mock-1".into()),
                    reasoning: None,
                    model_options: Default::default(),
                    sandbox: roboco_proto::SandboxLevel::WorkspaceWrite,
                }),
                branch: None,
                cwd: None,
                title: None,
                parent_chat_id: None,
            })
            .await
            .expect("create_session");
        let handle = rig.client.open_session(&chat_id).expect("open_session");
        let outcome = handle
            .send(crate::SendRequest::text("hello there"))
            .await
            .expect("send");
        assert!(matches!(outcome, crate::SendOutcome::Started { .. }), "{outcome:?}");
        // The pending echo is present right away.
        assert_eq!(handle.snapshot().pending.len(), 1);
        // The mock reply arrives.
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
        loop {
            let snapshot = handle.snapshot();
            let answered = snapshot
                .entries
                .iter()
                .any(|entry| entry.role == roboco_doc::MessageRole::Assistant);
            if answered {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the mock reply never arrived"
            );
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
        // The chat's cwd equals the space path.
        let row = rig.wait_for_session_row(&chat_id).await;
        assert_eq!(
            row.cwd.as_deref(),
            Some(repo.to_string_lossy().to_string().as_str())
        );
        rig.stop().await;
    }

    /// The new-worktree first send (the handoff's worktree arm): a send
    /// carrying `WorktreeSpec{repo_path, base: "HEAD", space_id}` makes the
    /// engine materialize the checkout on run — the chat row's cwd moves
    /// under the engine's worktrees root with a `roboco/<name>` branch
    /// (prior art: `crates/engine/tests/worktree_on_run.rs`).
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_new_worktree_send_materializes_the_checkout() {
        if !crate::test_support::git_available() {
            eprintln!("skipping: git is not on PATH");
            return;
        }
        let rig = Rig::start().await;
        let repo = rig.temp_folder("worktree-run-repo");
        crate::test_support::init_repo(&repo, &[]);
        let space_id = rig.create_space(&repo, "worktree-run-space", true).await;
        // A worktree draft sends no branch (iOS passes one only when NOT
        // creating a worktree).
        let chat_id = rig
            .client
            .create_session(NewSession {
                target: SessionTarget::Project {
                    space_id: space_id.clone(),
                },
                config: None,
                branch: None,
                cwd: None,
                title: None,
                parent_chat_id: None,
            })
            .await
            .expect("create_session");
        let handle = rig.client.open_session(&chat_id).expect("open_session");
        let outcome = handle
            .send(crate::SendRequest {
                text: "isolated please".into(),
                attachments: Vec::new(),
                worktree: Some(roboco_proto::WorktreeSpec {
                    repo_path: repo.to_string_lossy().to_string(),
                    base: "HEAD".into(),
                    space_id: Some(space_id.clone()),
                }),
                busy: crate::BusyPolicy::Queue,
            })
            .await
            .expect("send");
        assert!(matches!(outcome, crate::SendOutcome::Started { .. }), "{outcome:?}");
        // The engine stamps the chat row's cwd and `roboco/<name>` branch
        // while draining the Run.
        let raw = ScopedId::parse(&chat_id).expect("scoped id parses").raw_id;
        let repo_path = repo.to_string_lossy().to_string();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
        let cwd = loop {
            let stamped = rig
                .core
                .workspace
                .chat(&raw)
                .ok()
                .flatten()
                .and_then(|chat| {
                    chat.branch
                        .as_deref()
                        .filter(|branch| branch.starts_with("roboco/"))
                        .and(chat.cwd.clone().filter(|cwd| *cwd != repo_path))
                });
            if let Some(cwd) = stamped {
                break cwd;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the worktree never materialized"
            );
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        };
        assert!(std::path::Path::new(&cwd).is_dir(), "the checkout exists: {cwd}");
        if std::env::var_os("ROBOCO_WORKTREES_DIR").is_none() {
            let home = std::env::var_os("HOME")
                .or_else(|| std::env::var_os("USERPROFILE"))
                .map(std::path::PathBuf::from)
                .expect("a home on the test host");
            assert!(
                std::path::Path::new(&cwd).starts_with(home.join(".roboco").join("worktrees")),
                "under the engine's worktrees root: {cwd}"
            );
        }
        rig.stop().await;
    }

    /// `project_groups` with real engines: two engines each hold a space
    /// with the same `repositoryId` — one group, two checkouts, named
    /// after the older space; the offline checkout sorts last.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn project_groups_merge_checkouts_across_engines() {
        let mut rig = Rig::start().await;
        let second = rig.add_engine().await;
        // Two spaces of one repository. Created with `git_detected = true`
        // so the engine's own SpacesSync initial check is observable (it
        // re-stamps the row and `git_checked_at` appears); the manual
        // repository stamp lands after it, so nothing overwrites it inside
        // the sync's 120 s repair window. The first engine's space is older.
        let alpha = rig.temp_folder("alpha");
        let beta = rig.temp_folder("beta");
        let space_a = rig.create_space(&alpha, "space-a", true).await;
        let space_b = rig.create_space_on(&second, &beta, "space-b", true).await;
        for (core, raw) in [(&rig.core, "space-a"), (rig.core_of(&second), "space-b")] {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
            loop {
                let checked = core
                    .workspace
                    .space(raw)
                    .ok()
                    .flatten()
                    .is_some_and(|space| space.git_checked_at.is_some());
                if checked {
                    break;
                }
                assert!(
                    std::time::Instant::now() < deadline,
                    "the spaces sync never checked the folder"
                );
                tokio::time::sleep(std::time::Duration::from_millis(20)).await;
            }
        }
        rig.core
            .workspace
            .set_space_git("space-a", true, Some("co-a"), Some("repo-abc"))
            .expect("stamp space-a");
        rig.core_of(&second)
            .workspace
            .set_space_git("space-b", true, Some("co-b"), Some("repo-abc"))
            .expect("stamp space-b");
        // Both spaces reached the workspace carrying the repository stamp
        // (their `group_key` is `repo:<id>`).
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
        loop {
            let stamped = [&space_a, &space_b].into_iter().all(|id| {
                rig.client
                    .workspace()
                    .project(id)
                    .is_some_and(|project| project.group_key == "repo:repo-abc")
            });
            if stamped {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the repository stamp never reached the workspace"
            );
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
        let groups = rig.client.project_groups();
        assert_eq!(groups.len(), 1, "one repository, one group");
        let group = &groups[0];
        assert_eq!(group.group_key, "repo:repo-abc");
        assert_eq!(group.name, "alpha", "named after the older checkout");
        assert!(group.git_detected);
        assert_eq!(group.checkouts.len(), 2);
        // Both engines online: order falls to device name then path.
        let ids: Vec<&str> = group.checkouts.iter().map(|c| c.id.as_str()).collect();
        assert_eq!(ids.len(), 2);
        assert!(ids.contains(&space_a.as_str()) && ids.contains(&space_b.as_str()));
        // The second engine goes offline: its checkout sorts last.
        rig.disconnect_engine(&second).await;
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
        loop {
            let groups = rig.client.project_groups();
            let online_first = groups.len() == 1
                && groups[0].checkouts.len() == 2
                && groups[0].checkouts[0].id == space_a
                && !groups[0].checkouts[1].device_online;
            if online_first {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the offline checkout never sorted last"
            );
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
        rig.stop().await;
    }
}
