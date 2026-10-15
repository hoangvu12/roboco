//! Workspace view models — a multi-engine port of upstream zeron's
//! `crates/client/src/workspace/view.rs` (`derive`, `build_row`, `search`).
//! Plain data, rebuilt from the merged engine streams plus the optimistic
//! overlays, cheap to diff (every row carries a content-hash `revision`,
//! and unchanged rows keep their `Arc` across snapshots).
//!
//! Ordering mirrors the desktop sidebar: pinned sessions per engine in
//! pairing order then stored order, the user's sections (per engine, stored
//! order, each in recency order), then everything else by recency.
//! Roboco's precedence: a chat both pinned and sectioned shows **in its
//! section** and its pin is hidden (section-wins, matching the desktop and
//! web that share this engine state). Archived and child (side/subagent)
//! chats never appear in the active lists; a chat pointing at a missing
//! space is hidden from the front page but stays in `archived` once
//! archived.

use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::sync::Arc;

use chrono::{DateTime, Utc};
use roboco_engine_client::cache::CachedRows;
use roboco_engine_client::registry::{EngineKey, ScopedId};
use roboco_proto::view::{attention_rank, display_status, project_key, representative_space};
use roboco_proto::{
    ChangeRequestState, ChangeRequestSummary, Chat, ChatIndicator, Device, EngineInfo, Session,
    SidebarSection, Space,
};

use crate::catalog;
use crate::new_session::PendingChat;

/// Size of the project color palette [`ProjectRef::color_index`] indexes —
/// the desktop's monogram palette (`ui/src/shell/project_icon.rs`).
pub const PROJECT_COLOR_COUNT: u32 = 8;

/// Stable palette slot for a project, exactly as the desktop picks its
/// monogram tone: 32-bit FNV-1a of the project's path UTF-8 bytes
/// (`"home"` without a project), so a project has the same color on every
/// device. (The web's UTF-16 variant differs for non-ASCII paths; the
/// desktop's is the reference.)
pub fn project_color_index(space_path: &str) -> u32 {
    let hash = space_path.bytes().fold(2_166_136_261u32, |h, b| {
        (h ^ u32::from(b)).wrapping_mul(16_777_619)
    });
    hash % PROJECT_COLOR_COUNT
}

/// Compact age label for list rows: `now`, `34m`, `4h`, `2d` (legacy
/// `relativeTime`). Future timestamps read as `now`.
pub fn relative_time_label(at_ms: i64, now_ms: i64) -> String {
    let secs = (now_ms - at_ms).max(0) / 1000;
    match secs {
        0..60 => "now".to_owned(),
        60..3_600 => format!("{}m", secs / 60),
        3_600..86_400 => format!("{}h", secs / 3_600),
        _ => format!("{}d", secs / 86_400),
    }
}

/// Size of the project palette [`project_color_index`] indexes.
pub fn project_color_count() -> u32 {
    PROJECT_COLOR_COUNT
}

/// The state of this device's oldest unadopted outgoing send for a chat.
/// Rows carry it now; ticket 08's pending-send machinery fills it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SendState {
    Sending,
    /// Durable, waiting on a degraded path.
    Queued,
    /// "Not delivered — retry".
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ProjectRef {
    pub id: String,
    pub name: String,
    pub color_index: u32,
}

/// One session row, as every list renders it.
#[derive(Debug, Clone, PartialEq)]
pub struct SessionRow {
    pub id: String,
    /// The owning engine (the `EngineKey` string).
    pub engine_id: String,
    /// Content hash of every field below — equal ⇒ nothing to redraw.
    pub revision: u64,
    /// Display title ("New session" when untitled).
    pub title: String,
    pub has_title: bool,
    pub preview: Option<String>,
    /// `None` = projectless session (runs in the host's home folder).
    pub project: Option<ProjectRef>,
    pub device_id: String,
    pub device_name: Option<String>,
    /// The owning engine is `Connected` (Roboco's registry-backed device
    /// online rule: WatchDevices only carries the engine's own device).
    pub device_online: bool,
    pub engine_online: bool,
    pub harness: Option<String>,
    pub harness_label: Option<String>,
    pub model: Option<String>,
    pub model_label: Option<String>,
    pub reasoning: Option<String>,
    pub branch: Option<String>,
    pub cwd: Option<String>,
    /// Host-reported status (45 s staleness-gated). No in-flight-send
    /// override (upstream #777 removed it; Roboco's web still carries one,
    /// do not copy it): an outgoing send is not evidence of a running turn.
    pub indicator: ChatIndicator,
    pub host_indicator: ChatIndicator,
    /// Run start of the live turn while Working/AwaitingInput.
    pub working_since_ms: Option<i64>,
    /// Agents running under this chat right now — its open transcript's
    /// running subagents plus its working side chats (upstream #899's
    /// "● N" pill; the follow-up folds the side chats in). Counts only
    /// while a session is open (the chip state lives in the transcript).
    pub running_subagents: u32,
    /// `last_message_at`, falling back to `created_at` (the sort key).
    pub last_activity_ms: i64,
    /// [`relative_time_label`] of `last_activity_ms` at derive time (the 1 Hz
    /// re-derive refreshes it; it changes at most once a minute).
    pub time_label: String,
    pub created_at_ms: i64,
    pub unseen: bool,
    pub archived: bool,
    pub pinned: bool,
    pub section_id: Option<String>,
    pub pull_request: Option<ChangeRequestSummary>,
    /// Oldest unadopted send from this device, if any (ticket 08).
    pub send_state: Option<SendState>,
    pub parent_chat_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SectionView {
    pub id: String,
    pub engine_id: String,
    pub name: String,
    pub collapsed: bool,
    /// Recency order.
    pub sessions: Vec<Arc<SessionRow>>,
}

/// A "Group by Project" bucket over `recent` (PR #874): clones of one
/// repository share a `project:repo:<id>` group named for the oldest
/// checkout; projectless sessions group per host device. Ordered by first
/// appearance (the most recent session's group first); rows keep their
/// recency order. Pinned and user sections are untouched.
#[derive(Debug, Clone, PartialEq)]
pub struct SessionGroup {
    /// `"project:<groupKey>"` or `"home:<deviceId>"`.
    pub id: String,
    pub name: String,
    pub sessions: Vec<Arc<SessionRow>>,
}

/// The "Sessions" page — the desktop sidebar order.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct FrontPage {
    /// Manual pin order: engine pairing order, then stored order.
    pub pinned: Vec<Arc<SessionRow>>,
    /// User sections ("folders"), each in recency order.
    pub sections: Vec<SectionView>,
    /// Everything else, by recency.
    pub recent: Vec<Arc<SessionRow>>,
    /// `recent` bucketed by project / host device.
    pub groups: Vec<SessionGroup>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ProjectView {
    pub id: String,
    pub engine_id: String,
    pub name: String,
    pub path: String,
    pub color_index: u32,
    pub device_id: String,
    pub device_name: Option<String>,
    pub device_online: bool,
    pub git_detected: bool,
    pub created_at_ms: i64,
    /// The repository project this checkout belongs to
    /// (`roboco_proto::view::project_key`): clones and worktrees of one
    /// repository, on any device — or any engine — share it (#799).
    pub group_key: String,
    /// The group's name — its representative checkout's — shared by every
    /// member, like `color_index`.
    pub group_name: String,
    /// Most urgent indicator among its active sessions (Idle when none).
    pub indicator: ChatIndicator,
    pub unseen_count: u32,
    /// Active sessions, recency order (the desktop's by-project grouping).
    pub sessions: Vec<Arc<SessionRow>>,
}

/// Sessions with a change request, by provider state. (The proto carries no
/// draft flag yet — drafts read as Open.)
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PullRequestGroups {
    pub open: Vec<Arc<SessionRow>>,
    pub merged: Vec<Arc<SessionRow>>,
    pub closed: Vec<Arc<SessionRow>>,
}

/// One engine's device as the workspace shows it: the engine's own row —
/// the only device a phone's fleet ever contains.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceView {
    pub id: String,
    pub engine_id: String,
    pub name: String,
    pub platform: String,
    pub online: bool,
    pub last_seen_ms: Option<i64>,
    pub version: Option<String>,
    pub capabilities: Vec<String>,
    /// Can run sessions (desktop/server engines — not phones).
    pub is_execution_host: bool,
    /// Always `false`: a phone is never an engine device.
    pub is_self: bool,
    pub session_count: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchField {
    Title,
    Project,
    Branch,
    Preview,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SearchHit {
    pub session: Arc<SessionRow>,
    pub score: u32,
    /// The strongest field that matched.
    pub field: SearchField,
}

/// Everything the workspace screens render. Immutable; replaced wholesale.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct WorkspaceSnapshot {
    /// Bumped only when content changed.
    pub revision: u64,
    pub computed_at_ms: i64,
    /// Pin/section edits are allowed: every *connected* engine's sidebar
    /// state is known (live frame or cache) and at least one engine is
    /// connected. Fresh installs and all-offline fleets read `false` — pin
    /// ops then fail fast.
    pub pins_ready: bool,
    /// Any engine delivered a live frame this process.
    pub synced: bool,
    pub front: FrontPage,
    pub projects: Vec<ProjectView>,
    /// Active sessions without a project.
    pub projectless: Vec<Arc<SessionRow>>,
    pub pull_requests: PullRequestGroups,
    pub archived: Vec<Arc<SessionRow>>,
    pub devices: Vec<DeviceView>,
    /// Every chat row (archived and children included), by scoped id.
    pub sessions: HashMap<String, Arc<SessionRow>>,
    children: HashMap<String, Vec<Arc<SessionRow>>>,
    content_hash: u64,
}

impl WorkspaceSnapshot {
    pub fn session(&self, chat_id: &str) -> Option<&Arc<SessionRow>> {
        self.sessions.get(chat_id)
    }

    pub fn project(&self, space_id: &str) -> Option<&ProjectView> {
        self.projects.iter().find(|p| p.id == space_id)
    }

    /// Side chats / subagent chats hanging off `parent_id`, recency order.
    pub fn children(&self, parent_id: &str) -> &[Arc<SessionRow>] {
        self.children.get(parent_id).map_or(&[], Vec::as_slice)
    }

    /// Execution hosts (new-session / new-project pickers) — offline ones
    /// included: the UI shows them as offline rather than dropping them.
    pub fn execution_devices(&self) -> Vec<DeviceView> {
        self.devices
            .iter()
            .filter(|d| d.is_execution_host)
            .cloned()
            .collect()
    }

    pub(crate) fn content_hash(&self) -> u64 {
        self.content_hash
    }

    /// Case-insensitive search over titles, project names, branches and
    /// previews. Every whitespace-separated term must match some field.
    /// Archived rows score at half; ties break by recency.
    pub fn search(&self, query: &str, limit: usize) -> Vec<SearchHit> {
        let terms: Vec<String> = query.split_whitespace().map(|t| t.to_lowercase()).collect();
        if terms.is_empty() {
            return Vec::new();
        }
        let mut hits: Vec<SearchHit> = self
            .sessions
            .values()
            .filter(|row| row.parent_chat_id.is_none())
            .filter_map(|row| score_row(row, &terms).map(|(score, field)| (row, score, field)))
            .map(|(row, score, field)| SearchHit {
                session: row.clone(),
                score: if row.archived { score / 2 } else { score },
                field,
            })
            .collect();
        hits.sort_by(|a, b| {
            b.score
                .cmp(&a.score)
                .then(b.session.last_activity_ms.cmp(&a.session.last_activity_ms))
                .then(a.session.id.cmp(&b.session.id))
        });
        hits.truncate(limit);
        hits
    }
}

fn score_row(row: &SessionRow, terms: &[String]) -> Option<(u32, SearchField)> {
    let title = row.title.to_lowercase();
    let project = row
        .project
        .as_ref()
        .map(|p| p.name.to_lowercase())
        .unwrap_or_default();
    let branch = row.branch.as_deref().unwrap_or("").to_lowercase();
    let preview = row.preview.as_deref().unwrap_or("").to_lowercase();
    let mut total = 0u32;
    let mut best: Option<(u32, SearchField)> = None;
    for term in terms {
        let candidates = [
            (&title, SearchField::Title, 100u32),
            (&project, SearchField::Project, 40),
            (&branch, SearchField::Branch, 30),
            (&preview, SearchField::Preview, 20),
        ];
        let mut term_best: Option<(u32, SearchField)> = None;
        for (haystack, field, weight) in candidates {
            if let Some(at) = haystack.find(term.as_str()) {
                let prefix_bonus =
                    if at == 0 || !haystack.as_bytes()[at - 1].is_ascii_alphanumeric() {
                        weight / 2
                    } else {
                        0
                    };
                let score = weight + prefix_bonus;
                if term_best.is_none_or(|(s, _)| score > s) {
                    term_best = Some((score, field));
                }
            }
        }
        let (score, field) = term_best?;
        total += score;
        if best.is_none_or(|(s, _)| score > s) {
            best = Some((score, field));
        }
    }
    best.map(|(_, field)| (total, field))
}

// ── derive inputs ─────────────────────────────────────────────────────────

/// One engine's contribution to the merged derive: rows carry RAW engine
/// ids (`derive` scopes them); `pins`/`sections` are the effective state —
/// the pending overlay, else the last confirmed frame (or the cache).
#[derive(Debug, Clone, Default)]
pub struct EngineRows {
    pub engine_id: String,
    /// The `EngineView` display name (device-row name → paired label →
    /// host) — the synthesized device row when WatchDevices is pending.
    pub display_name: String,
    /// The pinned `EngineInfo.deviceId` (else the paired record's).
    pub device_id: Option<String>,
    pub info: Option<EngineInfo>,
    pub online: bool,
    /// No live feed frame has arrived yet this process: rows come from the
    /// offline cache (parked/offline engines keep their cached rows; their
    /// rows read `engine_online = false`).
    pub from_cache: bool,
    /// A confirmed sidebar snapshot exists (live frame or `sidebar.json`).
    pub sidebar_present: bool,
    /// Rows as the engine sent them (raw ids).
    pub rows: CachedRows,
    /// The workspace profile key (`local`, `synced:<deviceId>`, …); `None`
    /// until `EngineInfo` arrives.
    pub profile_key: Option<String>,
    /// Effective pin list (raw chat ids), stored order.
    pub pins: Vec<String>,
    /// Effective sections (raw chat ids inside), stored order.
    pub sections: Vec<SidebarSection>,
}

/// Optimistic overlays over the merged engine streams, keyed by scoped id
/// (pins/sections live per engine in the store and arrive pre-merged into
/// [`EngineRows`]).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Overlays {
    pub archived: HashMap<String, bool>,
    pub title: HashMap<String, String>,
    /// Local seen stamps (epoch ms); never rolled back.
    pub seen_at: HashMap<String, i64>,
    /// Chats pending deletion: hidden until the frame drops them.
    pub deleted: HashSet<String>,
    /// Ticket 08's optimistic config overrides (empty until 08).
    pub configs: HashMap<String, SessionConfig>,
}

/// A chat's run configuration, wire-string form (harness kebab-case,
/// reasoning lowercase, sandbox kebab-case).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionConfig {
    /// `claude-code`, `codex`, …
    pub harness: String,
    pub model: Option<String>,
    /// `low` … `ultrathink`.
    pub reasoning: Option<String>,
    /// Model option id → choice id (`contextWindow` → `1m`).
    pub model_options: HashMap<String, String>,
    pub sandbox: roboco_proto::SandboxLevel,
}

impl SessionConfig {
    /// Map a chat row's config to the wire-string form.
    pub fn of_chat(config: &roboco_proto::ChatConfig) -> Self {
        fn wire<T: serde::Serialize>(value: &T) -> String {
            serde_json::to_value(value)
                .ok()
                .and_then(|v| v.as_str().map(str::to_owned))
                .unwrap_or_default()
        }
        Self {
            harness: wire(&config.harness),
            model: config.model.clone(),
            reasoning: config.reasoning.as_ref().map(wire),
            model_options: config
                .model_options
                .iter()
                .map(|(k, v)| {
                    let value = v.as_str().map_or_else(|| v.to_string(), str::to_owned);
                    (k.clone(), value)
                })
                .collect(),
            sandbox: config.sandbox,
        }
    }
}

/// Resolved change requests: `(engine id, scoped device id, cwd, branch)`
/// → the latest successful resolution (`None` = a successful lookup with no
/// match; a failed resolution is simply absent and the previous value
/// stays).
pub type ChangeRequests = HashMap<(String, String, String, String), Option<ChangeRequestSummary>>;

pub struct DeriveContext<'a> {
    pub engines: &'a [EngineRows],
    pub now: DateTime<Utc>,
    pub overlays: &'a Overlays,
    pub change_requests: &'a ChangeRequests,
    /// Pending-send state per scoped chat id (ticket 08; empty before).
    pub send_states: &'a HashMap<String, SendState>,
    /// Ticket 09's pending chats (keyed by scoped chat id): chats created
    /// on an engine whose `WatchChats` echo has not landed — the derive
    /// renders them as synthetic rows so the Sessions list shows a new
    /// chat at once. An engine row with the same id wins.
    pub pending_chats: &'a HashMap<String, PendingChat>,
    /// Running subagents per scoped chat id (upstream #899): the open
    /// sessions' spawn-chip state, written by the session tasks. The
    /// derive folds each row's working side chats on top.
    pub running_subagents: &'a HashMap<String, u32>,
    pub previous: Option<&'a WorkspaceSnapshot>,
}

/// A device's display name, or `None` when it has none worth showing
/// (blank, or the engine's legacy `unknown-device` sentinel). Never an id.
pub(crate) fn device_display_name(device: &Device) -> Option<String> {
    let name = device.name.trim();
    (!name.is_empty() && name != "unknown-device").then(|| name.to_owned())
}

/// The execution-host gate shared with ticket 09's device resolution
/// (`refuse_non_host`): phones and tablets never run sessions.
pub(crate) fn is_execution_host(platform: &str) -> bool {
    !matches!(platform, "ios" | "android" | "ipados")
}

fn sort_key(chat: &Chat) -> DateTime<Utc> {
    chat.last_message_at.unwrap_or(chat.created_at)
}

fn sort_recency(rows: &mut [&Chat]) {
    rows.sort_by(|a, b| sort_key(b).cmp(&sort_key(a)).then_with(|| a.id.cmp(&b.id)));
}

fn hash_row(row: &SessionRow) -> u64 {
    let mut h = std::collections::hash_map::DefaultHasher::new();
    row.id.hash(&mut h);
    row.engine_id.hash(&mut h);
    row.title.hash(&mut h);
    row.has_title.hash(&mut h);
    row.preview.hash(&mut h);
    row.project.hash(&mut h);
    row.device_id.hash(&mut h);
    row.device_name.hash(&mut h);
    row.device_online.hash(&mut h);
    row.engine_online.hash(&mut h);
    row.harness.hash(&mut h);
    row.model.hash(&mut h);
    row.reasoning.hash(&mut h);
    row.branch.hash(&mut h);
    row.cwd.hash(&mut h);
    attention_rank(row.indicator).hash(&mut h);
    attention_rank(row.host_indicator).hash(&mut h);
    row.working_since_ms.hash(&mut h);
    row.running_subagents.hash(&mut h);
    row.last_activity_ms.hash(&mut h);
    row.time_label.hash(&mut h);
    row.created_at_ms.hash(&mut h);
    row.unseen.hash(&mut h);
    row.archived.hash(&mut h);
    row.pinned.hash(&mut h);
    row.section_id.hash(&mut h);
    if let Some(pr) = &row.pull_request {
        pr.number.hash(&mut h);
        pr.url.hash(&mut h);
        pr.title.hash(&mut h);
        (pr.state as u8).hash(&mut h);
    }
    row.send_state.hash(&mut h);
    row.parent_chat_id.hash(&mut h);
    h.finish()
}

/// The merged, scoped workspace: every engine's rows with ids scoped to
/// their engine and the row-level overlays applied. Ids never collide
/// across engines (the engine key is unique), so the merge is a
/// concatenation (the desktop registry's projection rule, extended to
/// `parent_chat_id`).
#[derive(Debug, Clone, Default)]
struct MergedState {
    chats: HashMap<String, Chat>,
    spaces: Vec<Space>,
    devices: Vec<Device>,
    sessions: Vec<Session>,
}

fn merge_engine(engine: &EngineRows, overlays: &Overlays, out: &mut MergedState) {
    let key = EngineKey(engine.engine_id.clone());
    let scope = |id: &str| ScopedId::encode(&key, id);
    for mut chat in engine.rows.chats.clone() {
        let scoped = scope(&chat.id);
        if overlays.deleted.contains(&scoped) {
            continue;
        }
        if let Some(archived) = overlays.archived.get(&scoped) {
            chat.archived = *archived;
        }
        if let Some(title) = overlays.title.get(&scoped) {
            chat.title = Some(title.clone());
        }
        if let Some(seen_at) = overlays.seen_at.get(&scoped) {
            let stamp = DateTime::<Utc>::from_timestamp_millis(*seen_at);
            if stamp.is_some_and(|stamp| chat.last_seen_at.is_none_or(|seen| stamp > seen)) {
                chat.last_seen_at = stamp;
            }
        }
        chat.id = scoped;
        chat.device_id = scope(&chat.device_id);
        chat.space_id = chat.space_id.as_deref().map(&scope);
        chat.checkout_id = chat.checkout_id.as_deref().map(&scope);
        if let Some(context) = &mut chat.source_context {
            context.checkout_id = scope(&context.checkout_id);
        }
        chat.parent_chat_id = chat.parent_chat_id.as_deref().map(&scope);
        out.chats.insert(chat.id.clone(), chat);
    }
    for mut space in engine.rows.spaces.clone() {
        space.id = scope(&space.id);
        space.device_id = scope(&space.device_id);
        space.checkout_id = space.checkout_id.as_deref().map(&scope);
        out.spaces.push(space);
    }
    for mut device in engine.rows.devices.clone() {
        device.id = scope(&device.id);
        out.devices.push(device);
    }
    for mut session in engine.rows.sessions.clone() {
        session.chat_id = scope(&session.chat_id);
        session.device_id = scope(&session.device_id);
        out.sessions.push(session);
    }
}

struct RowContext<'a> {
    engine_id: &'a str,
    engine_online: bool,
    all_spaces: &'a [Space],
    spaces: HashMap<&'a str, &'a Space>,
    devices: HashMap<&'a str, &'a Device>,
    sessions: HashMap<&'a str, &'a Session>,
    pinned: &'a HashSet<String>,
    section_of: &'a HashMap<String, String>,
    /// Working (unarchived) side chats per parent (scoped) — the #899
    /// follow-up's half of the "● N" pill.
    working_children: &'a HashMap<String, u32>,
}

fn build_row(chat: &Chat, rc: &RowContext<'_>, cx: &DeriveContext<'_>) -> Arc<SessionRow> {
    let now_ms = cx.now.timestamp_millis();
    let session = rc.sessions.get(chat.id.as_str()).copied();
    let send_state = cx.send_states.get(&chat.id).copied();
    // The host-reported status, staleness-gated — no in-flight-send
    // override (upstream #777 removed it; Roboco's web still carries one,
    // do not copy it).
    let host_indicator = display_status(chat, session, cx.now);
    let indicator = host_indicator;
    let working_since_ms = match indicator {
        ChatIndicator::Working | ChatIndicator::AwaitingInput => session
            .and_then(|s| s.started_at)
            .map(|t| t.timestamp_millis()),
        _ => None,
    };
    // The "● N" pill (upstream #899): the open transcript's running
    // subagents plus the working side chats folded in by the derive.
    let running_subagents = cx.running_subagents.get(&chat.id).copied().unwrap_or(0)
        + rc.working_children.get(chat.id.as_str()).copied().unwrap_or(0);
    let project = chat
        .space_id
        .as_deref()
        .and_then(|id| rc.spaces.get(id))
        .map(|space| ProjectRef {
            id: space.id.clone(),
            name: space.display_name().to_owned(),
            // Clones of one repository share their representative's color.
            color_index: project_color_index(&representative_space(rc.all_spaces, space).path),
        });
    let device = rc.devices.get(chat.device_id.as_str());
    let config = chat.config.as_ref();
    let harness = config.and_then(|c| {
        serde_json::to_value(c.harness)
            .ok()?
            .as_str()
            .map(str::to_owned)
    });
    let model = config.and_then(|c| c.model.clone());
    let reasoning = config
        .and_then(|c| c.reasoning)
        .and_then(|r| serde_json::to_value(r).ok()?.as_str().map(str::to_owned));
    let title = chat
        .title
        .as_deref()
        .map(str::trim)
        .filter(|t| !t.is_empty());
    let mut row = SessionRow {
        id: chat.id.clone(),
        engine_id: rc.engine_id.to_owned(),
        revision: 0,
        title: title.unwrap_or("New session").to_owned(),
        has_title: title.is_some(),
        preview: chat
            .last_message_preview
            .as_deref()
            .map(roboco_proto::view::single_line)
            .filter(|p| !p.is_empty()),
        project,
        device_id: chat.device_id.clone(),
        device_name: device.and_then(|d| device_display_name(d)),
        device_online: rc.engine_online,
        engine_online: rc.engine_online,
        harness_label: harness.as_deref().map(catalog::harness_label),
        model_label: match (harness.as_deref(), model.as_deref()) {
            (Some(h), Some(m)) => Some(catalog::model_label(h, m)),
            (None, Some(m)) => Some(m.to_owned()),
            _ => None,
        },
        harness,
        model,
        reasoning,
        branch: chat
            .source_context
            .as_ref()
            .map(|s| s.branch.clone())
            .or_else(|| chat.branch.clone())
            .filter(|b| !b.trim().is_empty()),
        cwd: chat.cwd.clone(),
        indicator,
        host_indicator,
        working_since_ms,
        running_subagents,
        last_activity_ms: sort_key(chat).timestamp_millis(),
        time_label: relative_time_label(sort_key(chat).timestamp_millis(), now_ms),
        created_at_ms: chat.created_at.timestamp_millis(),
        unseen: chat.unseen(),
        archived: chat.archived,
        pinned: rc.pinned.contains(chat.id.as_str()),
        section_id: rc.section_of.get(chat.id.as_str()).cloned(),
        pull_request: change_request_for_chat(chat, rc, cx.change_requests),
        send_state,
        parent_chat_id: chat.parent_chat_id.clone(),
    };
    row.revision = hash_row(&row);
    // Unchanged rows keep their Arc across snapshots (pointer-equal diffing).
    if let Some(previous) = cx.previous.and_then(|p| p.sessions.get(&chat.id))
        && previous.revision == row.revision
        && **previous == row
    {
        return previous.clone();
    }
    Arc::new(row)
}

/// The latest successful resolution for the chat's own (engine, device,
/// repo root, branch): a `null` change request clears the badge, a failed
/// resolution keeps the previous snapshot (the statuses map only records
/// successful lookups).
fn change_request_for_chat(
    chat: &Chat,
    rc: &RowContext<'_>,
    statuses: &ChangeRequests,
) -> Option<ChangeRequestSummary> {
    let source = chat.source_context.as_ref()?;
    let branch = source.branch.trim();
    if branch.is_empty() {
        return None;
    }
    statuses
        .get(&(
            rc.engine_id.to_owned(),
            chat.device_id.clone(),
            source.repo_root.clone(),
            branch.to_owned(),
        ))
        .and_then(|pr| pr.clone())
}

/// Build the whole snapshot. Pure given its inputs.
pub fn derive(cx: &DeriveContext<'_>) -> WorkspaceSnapshot {
    let now_ms = cx.now.timestamp_millis();
    // 1. Merge: scoped rows per engine in pairing order, overlays applied.
    let mut merged = MergedState::default();
    for engine in cx.engines {
        merge_engine(engine, cx.overlays, &mut merged);
    }
    // 1b. Ticket 09's pending chats: synthetic rows (scoped ids) for chats
    //     created on an engine whose WatchChats echo has not landed — the
    //     engine's own row wins if it arrives first. A pending chat whose
    //     engine is not in the fleet renders nothing (the store's TTL
    //     clears it): every merged chat must have a row below.
    let engine_ids: HashSet<&str> = cx.engines.iter().map(|e| e.engine_id.as_str()).collect();
    for (scoped, pending) in cx.pending_chats {
        if engine_ids.contains(pending.engine_id.as_str()) {
            merged
                .chats
                .entry(scoped.clone())
                .or_insert_with(|| pending.synthetic_chat(scoped));
        }
    }
    let spaces: HashMap<&str, &Space> =
        merged.spaces.iter().map(|s| (s.id.as_str(), s)).collect();
    let devices: HashMap<&str, &Device> =
        merged.devices.iter().map(|d| (d.id.as_str(), d)).collect();
    let sessions: HashMap<&str, &Session> = merged
        .sessions
        .iter()
        .map(|s| (s.chat_id.as_str(), s))
        .collect();

    // 2. Sidebar context per engine, scoped. Scoped ids never collide
    //    across engines, so the maps merge into one.
    let mut pinned_order: Vec<String> = Vec::new();
    let mut pinned: HashSet<String> = HashSet::new();
    let mut section_of: HashMap<String, String> = HashMap::new();
    // `(engine id, scoped section id, section)` in stored order.
    let mut sections: Vec<(String, String, SidebarSection)> = Vec::new();
    for engine in cx.engines {
        let key = EngineKey(engine.engine_id.clone());
        for raw in &engine.pins {
            let scoped = ScopedId::encode(&key, raw);
            if pinned.insert(scoped.clone()) {
                pinned_order.push(scoped);
            }
        }
        for section in &engine.sections {
            let scoped_section = ScopedId::encode(&key, &section.id);
            sections.push((
                engine.engine_id.clone(),
                scoped_section.clone(),
                section.clone(),
            ));
            for raw in &section.session_ids {
                let scoped = ScopedId::encode(&key, raw);
                section_of
                    .entry(scoped)
                    .or_insert_with(|| scoped_section.clone());
            }
        }
    }

    // 3. Rows, one context per engine. First the working-children pre-pass
    //    (the #899 follow-up): a parent's working side chats count into its
    //    "● N" pill, so the map is built before any row.
    let working_children: HashMap<String, u32> = merged
        .chats
        .values()
        .filter(|chat| chat.parent_chat_id.is_some() && !chat.archived)
        .filter_map(|chat| {
            let parent = chat.parent_chat_id.as_deref()?;
            let session = sessions.get(chat.id.as_str()).copied();
            (display_status(chat, session, cx.now) == ChatIndicator::Working)
                .then(|| parent.to_owned())
        })
        .fold(HashMap::new(), |mut acc, parent| {
            *acc.entry(parent).or_insert(0) += 1;
            acc
        });
    let mut rows: HashMap<String, Arc<SessionRow>> = HashMap::new();
    // Pending chats grouped by their engine — they render through their
    // engine's own row context (device names, engine online state).
    let mut pending_by_engine: HashMap<&str, Vec<&str>> = HashMap::new();
    for (scoped, pending) in cx.pending_chats {
        pending_by_engine
            .entry(pending.engine_id.as_str())
            .or_default()
            .push(scoped.as_str());
    }
    for engine in cx.engines {
        let key = EngineKey(engine.engine_id.clone());
        let rc = RowContext {
            engine_id: &engine.engine_id,
            engine_online: engine.online,
            all_spaces: &merged.spaces,
            spaces: spaces.clone(),
            devices: devices.clone(),
            sessions: sessions.clone(),
            pinned: &pinned,
            section_of: &section_of,
            working_children: &working_children,
        };
        for raw_chat in &engine.rows.chats {
            let scoped = ScopedId::encode(&key, &raw_chat.id);
            if let Some(chat) = merged.chats.get(&scoped) {
                let row = build_row(chat, &rc, cx);
                rows.insert(scoped, row);
            }
        }
        // Ticket 09: the pending chats of this engine (skipped when the
        // engine's own row already won in the merge above).
        if let Some(pending) = pending_by_engine.remove(engine.engine_id.as_str()) {
            for scoped in pending {
                if !rows.contains_key(scoped)
                    && let Some(chat) = merged.chats.get(scoped)
                {
                    let row = build_row(chat, &rc, cx);
                    rows.insert(scoped.to_owned(), row);
                }
            }
        }
    }
    let row = |chat: &Chat| rows[&chat.id].clone();

    // 4. Active = not archived, top-level, project alive (or projectless).
    //    Roboco has no voice orchestrator: "top-level" is
    //    `parent_chat_id.is_none()`.
    let mut active: Vec<&Chat> = merged
        .chats
        .values()
        .filter(|c| !c.archived && c.parent_chat_id.is_none())
        .filter(|c| {
            c.space_id
                .as_deref()
                .is_none_or(|id| spaces.contains_key(id))
        })
        .collect();
    sort_recency(&mut active);

    // 5. Front page. Roboco precedence: a chat both pinned and sectioned
    //    shows in its section; its pin stays in the stored list (so a later
    //    unpin works) but is hidden.
    let active_ids: HashSet<&str> = active.iter().map(|c| c.id.as_str()).collect();
    let pinned_rows: Vec<Arc<SessionRow>> = pinned_order
        .iter()
        .filter(|id| {
            active_ids.contains(id.as_str())
                && !section_of.contains_key(id.as_str())
                && rows.contains_key(id.as_str())
        })
        .map(|id| rows[id].clone())
        .collect();
    let section_views: Vec<SectionView> = sections
        .iter()
        .map(|(engine_id, scoped_section, section)| SectionView {
            id: scoped_section.clone(),
            engine_id: engine_id.clone(),
            name: section.name.clone(),
            collapsed: section.collapsed,
            // Empty sections are kept (the "Drop sessions here" target).
            sessions: active
                .iter()
                .filter(|c| {
                    section_of.get(c.id.as_str()).map(String::as_str)
                        == Some(scoped_section.as_str())
                })
                .map(|c| row(c))
                .collect(),
        })
        .collect();
    let recent: Vec<Arc<SessionRow>> = active
        .iter()
        .filter(|c| !pinned.contains(c.id.as_str()) && !section_of.contains_key(c.id.as_str()))
        .map(|c| row(c))
        .collect();

    // 6. Projects (creation order), sessions by recency.
    let mut ordered_spaces: Vec<&Space> = merged.spaces.iter().collect();
    ordered_spaces.sort_by(|a, b| {
        a.created_at
            .cmp(&b.created_at)
            .then_with(|| a.id.cmp(&b.id))
    });
    let engine_index: HashMap<&str, usize> = cx
        .engines
        .iter()
        .enumerate()
        .map(|(index, engine)| (engine.engine_id.as_str(), index))
        .collect();
    let engine_of_scoped = |scoped: &str| -> String {
        ScopedId::parse(scoped)
            .ok()
            .and_then(|s| engine_index.get(s.engine.0.as_str()).copied())
            .map(|index| cx.engines[index].engine_id.clone())
            .unwrap_or_default()
    };
    let online_of_scoped = |scoped: &str| -> bool {
        ScopedId::parse(scoped)
            .ok()
            .and_then(|s| engine_index.get(s.engine.0.as_str()).copied())
            .is_some_and(|index| cx.engines[index].online)
    };
    let projects: Vec<ProjectView> = ordered_spaces
        .iter()
        .map(|space| {
            let sessions: Vec<Arc<SessionRow>> = active
                .iter()
                .filter(|c| c.space_id.as_deref() == Some(space.id.as_str()))
                .map(|c| row(c))
                .collect();
            let indicator = sessions
                .iter()
                .map(|r| r.indicator)
                .min_by_key(|i| attention_rank(*i))
                .unwrap_or(ChatIndicator::Idle);
            let representative = representative_space(&merged.spaces, space);
            ProjectView {
                id: space.id.clone(),
                engine_id: engine_of_scoped(&space.id),
                name: space.display_name().to_owned(),
                path: space.path.clone(),
                color_index: project_color_index(&representative.path),
                device_id: space.device_id.clone(),
                device_name: devices
                    .get(space.device_id.as_str())
                    .and_then(|d| device_display_name(d)),
                device_online: online_of_scoped(&space.device_id),
                git_detected: space.git_detected,
                created_at_ms: space.created_at.timestamp_millis(),
                group_key: project_key(space),
                group_name: representative.display_name().to_owned(),
                indicator,
                unseen_count: sessions.iter().filter(|r| r.unseen).count() as u32,
                sessions,
            }
        })
        .collect();

    // 7. Groups (PR #874): `recent` bucketed by project / host device,
    //    ordered by first appearance.
    let groups = project_groups(&recent, &projects);
    let projectless: Vec<Arc<SessionRow>> = active
        .iter()
        .filter(|c| c.space_id.is_none())
        .map(|c| row(c))
        .collect();

    let mut pull_requests = PullRequestGroups::default();
    for chat in &active {
        let row = row(chat);
        match row.pull_request.as_ref().map(|pr| pr.state) {
            Some(ChangeRequestState::Open) => pull_requests.open.push(row),
            Some(ChangeRequestState::Merged) => pull_requests.merged.push(row),
            Some(ChangeRequestState::Closed) => pull_requests.closed.push(row),
            None => {}
        }
    }

    // 8. Archived: recency order, no dangling-space filter (a chat whose
    //    space vanished still lists here once archived).
    let mut archived_chats: Vec<&Chat> = merged
        .chats
        .values()
        .filter(|c| c.archived && c.parent_chat_id.is_none())
        .collect();
    sort_recency(&mut archived_chats);
    let archived: Vec<Arc<SessionRow>> = archived_chats.iter().map(|c| row(c)).collect();

    // 9. Children: keyed by parent, recency order, archived included.
    let mut children: HashMap<String, Vec<Arc<SessionRow>>> = HashMap::new();
    let mut child_chats: Vec<&Chat> = merged
        .chats
        .values()
        .filter(|c| c.parent_chat_id.is_some())
        .collect();
    sort_recency(&mut child_chats);
    for chat in child_chats {
        if let Some(parent) = &chat.parent_chat_id {
            children.entry(parent.clone()).or_default().push(row(chat));
        }
    }

    // 10. Devices: one per engine — its own device row, synthesized from
    //     the view when WatchDevices has not delivered.
    let mut device_views: Vec<DeviceView> = Vec::new();
    for engine in cx.engines {
        let device = engine
            .device_id
            .as_deref()
            .and_then(|id| engine.rows.devices.iter().find(|d| d.id == id));
        let raw_device_id = engine.device_id.clone().unwrap_or_default();
        let scoped = ScopedId::encode(&EngineKey(engine.engine_id.clone()), &raw_device_id);
        let session_count = active
            .iter()
            .filter(|c| c.device_id == scoped)
            .count() as u32;
        let view = match device {
            Some(device) => DeviceView {
                id: scoped,
                engine_id: engine.engine_id.clone(),
                name: device_display_name(device)
                    .unwrap_or_else(|| "Unknown device".to_owned()),
                platform: device.platform.clone(),
                online: engine.online,
                // Stale cached timestamps must never imply a live
                // connection: only a connected engine reports last-seen.
                last_seen_ms: engine
                    .online
                    .then(|| device.last_seen_at.map(|t| t.timestamp_millis()))
                    .flatten(),
                version: device
                    .version
                    .clone()
                    .or_else(|| engine.info.as_ref().and_then(|i| i.version.clone())),
                capabilities: device.capabilities.clone(),
                is_execution_host: is_execution_host(&device.platform),
                is_self: false,
                session_count,
            },
            None => DeviceView {
                id: scoped,
                engine_id: engine.engine_id.clone(),
                name: engine.display_name.clone(),
                platform: device.map(|d| d.platform.clone()).unwrap_or_default(),
                online: engine.online,
                last_seen_ms: None,
                version: engine.info.as_ref().and_then(|i| i.version.clone()),
                capabilities: engine
                    .info
                    .as_ref()
                    .map(|i| i.capabilities.clone())
                    .unwrap_or_default(),
                // Unknown platform never hides an engine from the
                // execution picker: show it, the UI marks it offline.
                is_execution_host: true,
                is_self: false,
                session_count,
            },
        };
        device_views.push(view);
    }
    device_views.sort_by(|a, b| {
        b.is_execution_host
            .cmp(&a.is_execution_host)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
            .then_with(|| a.id.cmp(&b.id))
    });

    // 11. Readiness: `pins_ready` = every *connected* engine is
    //     sidebar-ready (the ticket's rule), and at least one engine is
    //     connected — an all-offline fleet cannot edit pins, matching the
    //     offline acceptance (pins show from cache, `pins_ready` false).
    //     `synced` = a live frame reached this process.
    let pins_ready = !cx.engines.is_empty()
        && cx.engines.iter().any(|engine| engine.online)
        && cx
            .engines
            .iter()
            .filter(|engine| engine.online)
            .all(|engine| engine.profile_key.is_some() && engine.sidebar_present);
    let synced = cx.engines.iter().any(|engine| !engine.from_cache);

    let mut snapshot = WorkspaceSnapshot {
        revision: 0,
        computed_at_ms: now_ms,
        pins_ready,
        synced,
        front: FrontPage {
            pinned: pinned_rows,
            sections: section_views,
            recent: recent.clone(),
            groups,
        },
        projects,
        projectless,
        pull_requests,
        archived,
        devices: device_views,
        sessions: rows,
        children,
        content_hash: 0,
    };
    snapshot.content_hash = snapshot_hash(&snapshot);
    snapshot
}

/// PR #874's `projectGroups`: checkouts of one repository share a group
/// (named for its representative), projectless sessions group per host
/// device. Groups and rows keep the incoming recency order.
fn project_groups(recent: &[Arc<SessionRow>], projects: &[ProjectView]) -> Vec<SessionGroup> {
    let by_id: HashMap<&str, &ProjectView> =
        projects.iter().map(|p| (p.id.as_str(), p)).collect();
    let mut groups: Vec<SessionGroup> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    for row in recent {
        let (id, name) = match row.project.as_ref().and_then(|p| by_id.get(p.id.as_str())) {
            Some(project) => (
                format!("project:{}", project.group_key),
                project.group_name.clone(),
            ),
            None => match &row.project {
                // A project that is not in the view (dangling — the active
                // filter usually prevents this): fall back to the ref.
                Some(project_ref) => (
                    format!("project:{}", project_ref.id),
                    project_ref.name.clone(),
                ),
                None => (
                    format!("home:{}", row.device_id),
                    row.device_name
                        .as_deref()
                        .map(|name| format!("Home · {name}"))
                        .unwrap_or_else(|| "Home".to_owned()),
                ),
            },
        };
        match index.get(&id) {
            Some(&at) => groups[at].sessions.push(row.clone()),
            None => {
                index.insert(id.clone(), groups.len());
                groups.push(SessionGroup {
                    id,
                    name,
                    sessions: vec![row.clone()],
                });
            }
        }
    }
    groups
}

fn snapshot_hash(s: &WorkspaceSnapshot) -> u64 {
    let mut h = std::collections::hash_map::DefaultHasher::new();
    let ids = |h: &mut std::collections::hash_map::DefaultHasher, rows: &[Arc<SessionRow>]| {
        rows.len().hash(h);
        for row in rows {
            row.revision.hash(h);
        }
    };
    s.pins_ready.hash(&mut h);
    s.synced.hash(&mut h);
    ids(&mut h, &s.front.pinned);
    for section in &s.front.sections {
        section.id.hash(&mut h);
        section.engine_id.hash(&mut h);
        section.name.hash(&mut h);
        section.collapsed.hash(&mut h);
        ids(&mut h, &section.sessions);
    }
    ids(&mut h, &s.front.recent);
    for group in &s.front.groups {
        group.id.hash(&mut h);
        group.name.hash(&mut h);
        ids(&mut h, &group.sessions);
    }
    for project in &s.projects {
        project.id.hash(&mut h);
        project.engine_id.hash(&mut h);
        project.name.hash(&mut h);
        project.path.hash(&mut h);
        project.color_index.hash(&mut h);
        project.group_key.hash(&mut h);
        project.group_name.hash(&mut h);
        project.device_name.hash(&mut h);
        project.device_online.hash(&mut h);
        project.git_detected.hash(&mut h);
        ids(&mut h, &project.sessions);
    }
    ids(&mut h, &s.projectless);
    ids(&mut h, &s.archived);
    for device in &s.devices {
        device.hash(&mut h);
    }
    let mut all: Vec<(&String, u64)> = s.sessions.iter().map(|(k, v)| (k, v.revision)).collect();
    all.sort();
    all.hash(&mut h);
    h.finish()
}

impl Hash for DeviceView {
    fn hash<H: Hasher>(&self, h: &mut H) {
        self.id.hash(h);
        self.engine_id.hash(h);
        self.name.hash(h);
        self.platform.hash(h);
        self.online.hash(h);
        self.version.hash(h);
        self.capabilities.hash(h);
        self.is_execution_host.hash(h);
        self.session_count.hash(h);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn at(secs: i64) -> DateTime<Utc> {
        Utc.timestamp_opt(secs, 0).unwrap()
    }

    /// A fixed derive clock: 10 000 s past the epoch.
    const NOW_SECS: i64 = 10_000;

    fn chat(id: &str) -> Chat {
        Chat {
            id: id.into(),
            device_id: "dev-1".into(),
            title: None,
            archived: false,
            cwd: None,
            branch: None,
            checkout_id: None,
            source_context: None,
            config: None,
            last_message_preview: None,
            last_message_at: None,
            created_at: at(0),
            harness_session_id: None,
            harness_session_cwd: None,
            space_id: None,
            last_seen_at: None,
            room_gen: None,
            parent_chat_id: None,
        }
    }

    fn space(id: &str, path: &str, repository_id: Option<&str>, created: i64) -> Space {
        Space {
            id: id.into(),
            device_id: "dev-1".into(),
            path: path.into(),
            name: None,
            git_detected: repository_id.is_some(),
            git_checked_at: None,
            checkout_id: repository_id.map(|_| format!("co-{id}")),
            repository_id: repository_id.map(str::to_owned),
            created_at: at(created),
        }
    }

    fn session(chat_id: &str, status: roboco_proto::SessionStatus, updated: i64) -> Session {
        Session {
            last_completed_turn: None,
            chat_id: chat_id.into(),
            device_id: "dev-1".into(),
            status,
            started_at: Some(at(updated)),
            updated_at: at(updated),
        }
    }

    fn device(id: &str, name: &str) -> Device {
        Device {
            id: id.into(),
            name: name.into(),
            platform: "windows".into(),
            last_seen_at: Some(at(9_999)),
            created_at: Some(at(0)),
            version: Some("0.9.2".into()),
            cursor_sdk_version: None,
            capabilities: Vec::new(),
        }
    }

    fn engine(
        engine_id: &str,
        chats: Vec<Chat>,
        spaces: Vec<Space>,
        sessions: Vec<Session>,
    ) -> EngineRows {
        EngineRows {
            engine_id: engine_id.into(),
            display_name: format!("Engine {engine_id}"),
            device_id: Some("dev-1".into()),
            info: None,
            online: true,
            from_cache: false,
            sidebar_present: true,
            rows: CachedRows {
                chats,
                spaces,
                devices: vec![device("dev-1", "Studio")],
                sessions,
            },
            profile_key: Some("local".into()),
            pins: Vec::new(),
            sections: Vec::new(),
        }
    }

    fn derive_of(engines: &[EngineRows]) -> WorkspaceSnapshot {
        derive(&DeriveContext {
            engines,
            now: at(NOW_SECS),
            overlays: &Overlays::default(),
            change_requests: &HashMap::new(),
            send_states: &HashMap::new(),
            pending_chats: &HashMap::new(),
            running_subagents: &HashMap::new(),
            previous: None,
        })
    }

    /// Recency stamps: `last_message_at` seconds past the epoch.
    fn activity(chat: &mut Chat, last_message_at: i64) {
        chat.last_message_at = Some(at(last_message_at));
    }

    fn ids(rows: &[Arc<SessionRow>]) -> Vec<String> {
        rows.iter().map(|row| row.id.clone()).collect()
    }

    fn row_id(engine: &str, raw: &str) -> String {
        ScopedId::encode(&EngineKey(engine.into()), raw)
    }

    // ── labels ──────────────────────────────────────────────────────────

    #[test]
    fn time_label_boundaries() {
        let now = NOW_SECS * 1000;
        assert_eq!(relative_time_label(now, now), "now"); // 0 s
        assert_eq!(relative_time_label(now - 59_000, now), "now");
        assert_eq!(relative_time_label(now - 60_000, now), "1m");
        assert_eq!(relative_time_label(now - 3_599_000, now), "59m");
        assert_eq!(relative_time_label(now - 3_600_000, now), "1h");
        assert_eq!(relative_time_label(now - 86_400_000, now), "1d");
        assert_eq!(relative_time_label(now + 5_000, now), "now"); // future
        // No weeks: 30 days reads in days.
        assert_eq!(relative_time_label(now - 30 * 86_400_000, now), "30d");
    }

    /// Goldens match the desktop `project_icon.rs` FNV-1a (UTF-8 bytes):
    /// `hash % 8`. The web's UTF-16 variant differs for non-ASCII paths.
    #[test]
    fn project_color_index_goldens_match_the_desktop() {
        assert_eq!(project_color_index("home"), 6);
        assert_eq!(project_color_index("/Users/ada/Übung"), 3);
        assert_eq!(project_color_index("/home/用户/项目"), 0);
        assert_eq!(project_color_count(), 8);
        // A non-ASCII path and its UTF-16-flavored variant differ.
        let utf16_hash: u32 = "/home/用户/项目"
            .encode_utf16()
            .fold(2_166_136_261u32, |h, u| {
                (h ^ u32::from(u)).wrapping_mul(16_777_619)
            });
        assert_ne!(utf16_hash % 8, project_color_index("/home/用户/项目"));
    }

    // ── the front page ──────────────────────────────────────────────────

    #[test]
    fn front_page_orders_pinned_sections_recent() {
        let mut a = chat("a");
        activity(&mut a, 100);
        let mut b = chat("b");
        activity(&mut b, 200);
        let mut c = chat("c");
        activity(&mut c, 300);
        let mut d = chat("d");
        activity(&mut d, 400);
        let mut engine = engine("e1", vec![a, b, c, d], Vec::new(), Vec::new());
        engine.pins = vec!["b".into(), "a".into()]; // stored order
        engine.sections = vec![SidebarSection {
            id: "sec-1".into(),
            name: "Work".into(),
            session_ids: vec!["c".into()],
            collapsed: false,
        }];
        let snapshot = derive_of(&[engine]);
        let front = &snapshot.front;
        // Pinned in stored order.
        assert_eq!(ids(&front.pinned), [row_id("e1", "b"), row_id("e1", "a")]);
        // The section holds its chat in recency order.
        assert_eq!(front.sections.len(), 1);
        assert_eq!(front.sections[0].name, "Work");
        assert_eq!(ids(&front.sections[0].sessions), [row_id("e1", "c")]);
        // Recent: everything else by recency.
        assert_eq!(ids(&front.recent), [row_id("e1", "d")]);
        // Rows carry the flags.
        assert!(snapshot.session(&row_id("e1", "b")).unwrap().pinned);
        assert_eq!(
            snapshot.session(&row_id("e1", "c")).unwrap().section_id,
            Some(row_id("e1", "sec-1"))
        );
    }

    #[test]
    fn a_chat_both_pinned_and_sectioned_shows_only_in_its_section() {
        let mut pinned_sectioned = chat("both");
        activity(&mut pinned_sectioned, 100);
        let mut other = chat("other");
        activity(&mut other, 50);
        let mut engine = engine("e1", vec![pinned_sectioned, other], Vec::new(), Vec::new());
        engine.pins = vec!["both".into()];
        engine.sections = vec![SidebarSection {
            id: "sec".into(),
            name: "S".into(),
            session_ids: vec!["both".into()],
            collapsed: false,
        }];
        let snapshot = derive_of(&[engine]);
        // Section-wins (the Roboco precedence): the pin is hidden…
        assert!(snapshot.front.pinned.is_empty());
        // …the chat shows in its section…
        assert_eq!(
            ids(&snapshot.front.sections[0].sessions),
            [row_id("e1", "both")]
        );
        // …and stays out of recent.
        assert_eq!(ids(&snapshot.front.recent), [row_id("e1", "other")]);
        // The raw pin list keeps it (a later unpin works).
        assert!(snapshot.session(&row_id("e1", "both")).unwrap().pinned);
    }

    #[test]
    fn empty_sections_are_kept() {
        let mut engine = engine("e1", vec![chat("a")], Vec::new(), Vec::new());
        engine.sections = vec![SidebarSection {
            id: "empty".into(),
            name: "Drop sessions here".into(),
            session_ids: vec![],
            collapsed: false,
        }];
        let snapshot = derive_of(&[engine]);
        assert_eq!(snapshot.front.sections.len(), 1);
        assert!(snapshot.front.sections[0].sessions.is_empty());
    }

    #[test]
    fn archived_chats_leave_the_front_page_and_list_in_archived() {
        let mut active = chat("active");
        activity(&mut active, 100);
        let mut archived = chat("archived");
        activity(&mut archived, 300);
        archived.archived = true;
        let snapshot = derive_of(&[engine("e1", vec![active, archived], Vec::new(), Vec::new())]);
        assert_eq!(ids(&snapshot.front.recent), [row_id("e1", "active")]);
        assert_eq!(ids(&snapshot.archived), [row_id("e1", "archived")]);
        assert!(snapshot.session(&row_id("e1", "archived")).unwrap().archived);
    }

    #[test]
    fn children_never_reach_the_front_page_but_list_under_their_parent() {
        let mut parent = chat("parent");
        activity(&mut parent, 100);
        let mut child = chat("child");
        activity(&mut child, 500);
        child.parent_chat_id = Some("parent".into());
        let mut grandchild = chat("grandchild");
        activity(&mut grandchild, 600);
        grandchild.parent_chat_id = Some("child".into());
        let snapshot = derive_of(&[engine(
            "e1",
            vec![parent, child, grandchild],
            Vec::new(),
            Vec::new(),
        )]);
        assert_eq!(ids(&snapshot.front.recent), [row_id("e1", "parent")]);
        // Children list under their parent, recency order, archived included.
        assert_eq!(
            ids(snapshot.children(&row_id("e1", "parent"))),
            [row_id("e1", "child")]
        );
        assert_eq!(
            ids(snapshot.children(&row_id("e1", "child"))),
            [row_id("e1", "grandchild")]
        );
    }

    #[test]
    fn a_dangling_space_hides_from_the_front_but_survives_in_archived() {
        let mut healthy = chat("healthy");
        activity(&mut healthy, 100);
        let mut dangler = chat("dangler");
        activity(&mut dangler, 200);
        dangler.space_id = Some("gone".into());
        let mut archived_dangler = chat("archived-dangler");
        activity(&mut archived_dangler, 300);
        archived_dangler.archived = true;
        archived_dangler.space_id = Some("gone".into());
        let snapshot = derive_of(&[engine(
            "e1",
            vec![healthy, dangler, archived_dangler],
            vec![space("present", "/present", None, 0)],
            Vec::new(),
        )]);
        assert_eq!(ids(&snapshot.front.recent), [row_id("e1", "healthy")]);
        assert_eq!(ids(&snapshot.archived), [row_id("e1", "archived-dangler")]);
        // A dangling row is still a session_row (archived, child, or
        // dangling-space: any row resolves).
        assert!(snapshot.session(&row_id("e1", "dangler")).is_some());
    }

    #[test]
    fn multi_engine_merges_in_pairing_order() {
        let mut a = chat("a");
        activity(&mut a, 100);
        let mut b = chat("b");
        activity(&mut b, 200);
        // The same raw id on two engines never collides.
        let mut e1 = engine("e1", vec![a], Vec::new(), Vec::new());
        e1.pins = vec!["a".into()];
        let e2 = engine("e2", vec![b], Vec::new(), Vec::new());
        let snapshot = derive_of(&[e1, e2]);
        // Pinned order: engine order first, then stored order.
        assert_eq!(ids(&snapshot.front.pinned), [row_id("e1", "a")]);
        // Recent keeps global recency order across engines.
        assert_eq!(ids(&snapshot.front.recent), [row_id("e2", "b")]);
        // Rows name their engine.
        assert_eq!(snapshot.session(&row_id("e1", "a")).unwrap().engine_id, "e1");
        assert_eq!(snapshot.session(&row_id("e2", "b")).unwrap().engine_id, "e2");
        // One device per engine, hosts first, offline ones kept.
        assert_eq!(snapshot.devices.len(), 2);
        assert!(snapshot.devices.iter().all(|d| d.is_execution_host));
        assert!(snapshot.devices.iter().all(|d| !d.is_self));
        let execution = snapshot.execution_devices();
        assert_eq!(execution.len(), 2);
    }

    #[test]
    fn offline_engines_keep_their_rows_but_read_offline() {
        let mut a = chat("a");
        activity(&mut a, 100);
        let mut offline = engine("e-off", vec![a], Vec::new(), Vec::new());
        offline.online = false;
        offline.from_cache = true;
        let snapshot = derive_of(&[offline]);
        let row = snapshot.session(&row_id("e-off", "a")).unwrap();
        assert!(!row.engine_online);
        assert!(!row.device_online);
        // The engine's device view reads offline, but stays listed.
        assert_eq!(snapshot.devices.len(), 1);
        assert!(!snapshot.devices[0].online);
        assert!(snapshot.devices[0].last_seen_ms.is_none());
        // Pins are not editable: pins_ready is false.
        assert!(!snapshot.pins_ready);
        assert!(!snapshot.synced);
    }

    #[test]
    fn pins_ready_reads_connected_engines_and_all_offline_fleets_fail() {
        // Every *connected* engine ready (the ticket's rule): a mixed fleet
        // (one connected + ready, one offline) still reads true.
        let mut a = chat("a");
        activity(&mut a, 100);
        let connected = engine("e-on", vec![a], Vec::new(), Vec::new());
        let mut offline = engine("e-off", vec![chat("b")], Vec::new(), Vec::new());
        offline.online = false;
        offline.from_cache = true;
        let snapshot = derive_of(&[connected, offline]);
        assert!(snapshot.pins_ready);
        // A connected engine without a sidebar frame is not ready.
        let mut pending = engine("e-pending", vec![chat("c")], Vec::new(), Vec::new());
        pending.sidebar_present = false;
        let snapshot = derive_of(&[pending]);
        assert!(!snapshot.pins_ready);
        // No engines at all: not ready (fresh install).
        assert!(!derive_of(&[]).pins_ready);
    }

    // ── indicators ──────────────────────────────────────────────────────

    fn indicator_of(
        chat_row: &Chat,
        session_row: Option<Session>,
        send: Option<SendState>,
    ) -> ChatIndicator {
        let sessions: Vec<Session> = session_row.into_iter().collect();
        let engine = engine("e1", vec![chat_row.clone()], Vec::new(), sessions);
        let mut send_states = HashMap::new();
        if let Some(send) = send {
            send_states.insert(row_id("e1", &chat_row.id), send);
        }
        let snapshot = derive(&DeriveContext {
            engines: std::slice::from_ref(&engine),
            now: at(NOW_SECS),
            overlays: &Overlays::default(),
            change_requests: &HashMap::new(),
            send_states: &send_states,
            pending_chats: &HashMap::new(),
            running_subagents: &HashMap::new(),
            previous: None,
        });
        snapshot
            .session(&row_id("e1", &chat_row.id))
            .unwrap()
            .indicator
    }

    #[test]
    fn indicator_table() {
        use roboco_proto::SessionStatus;
        // Working fresh → Working.
        let mut chat_row = chat("c");
        chat_row.last_message_at = Some(at(1));
        assert_eq!(
            indicator_of(
                &chat_row,
                Some(session("c", SessionStatus::Working, NOW_SECS - 10)),
                None
            ),
            ChatIndicator::Working
        );
        // Working stale (> 45 s) → Idle when seen…
        let mut seen = chat_row.clone();
        seen.last_seen_at = Some(at(5));
        assert_eq!(
            indicator_of(
                &seen,
                Some(session("c", SessionStatus::Working, NOW_SECS - 60)),
                None
            ),
            ChatIndicator::Idle
        );
        // …or Completed when unseen.
        let mut unseen = chat_row.clone();
        unseen.last_seen_at = None;
        assert_eq!(
            indicator_of(
                &unseen,
                Some(session("c", SessionStatus::Working, NOW_SECS - 60)),
                None
            ),
            ChatIndicator::Completed
        );
        // AwaitingInput (fresh) → AwaitingInput.
        assert_eq!(
            indicator_of(
                &chat_row,
                Some(session("c", SessionStatus::AwaitingInput, NOW_SECS - 10)),
                None
            ),
            ChatIndicator::AwaitingInput
        );
        // Errored + unseen → Errored.
        assert_eq!(
            indicator_of(
                &unseen,
                Some(session("c", SessionStatus::Errored, NOW_SECS)),
                None
            ),
            ChatIndicator::Errored
        );
        // finished + unseen → Completed; seen → Idle.
        assert_eq!(
            indicator_of(
                &unseen,
                Some(session("c", SessionStatus::Idle, NOW_SECS)),
                None
            ),
            ChatIndicator::Completed
        );
        let mut finished_seen = unseen;
        finished_seen.last_seen_at = Some(at(9_999));
        assert_eq!(
            indicator_of(
                &finished_seen,
                Some(session("c", SessionStatus::Idle, NOW_SECS)),
                None
            ),
            ChatIndicator::Idle
        );
    }

    /// Upstream #777 removed the in-flight-send override: a pending
    /// `Sending` state never makes a row Working.
    #[test]
    fn a_pending_send_never_makes_a_row_working() {
        use roboco_proto::SessionStatus;
        let mut chat_row = chat("c");
        chat_row.last_message_at = Some(at(1));
        chat_row.last_seen_at = Some(at(2)); // seen: only the send is pending
        let indicator = indicator_of(
            &chat_row,
            Some(session("c", SessionStatus::Idle, NOW_SECS)),
            Some(SendState::Sending),
        );
        assert_eq!(indicator, ChatIndicator::Idle);
    }

    #[test]
    fn working_since_only_runs_while_working_or_awaiting() {
        use roboco_proto::SessionStatus;
        let mut chat_row = chat("c");
        chat_row.last_message_at = Some(at(1));
        let working = engine(
            "e1",
            vec![chat_row.clone()],
            Vec::new(),
            vec![session("c", SessionStatus::Working, NOW_SECS - 10)],
        );
        let snapshot = derive_of(&[working]);
        assert_eq!(
            snapshot.session(&row_id("e1", "c")).unwrap().working_since_ms,
            Some((NOW_SECS - 10) * 1000)
        );
        // Errored runs never show a start time.
        let errored = engine(
            "e1",
            vec![chat_row],
            Vec::new(),
            vec![session("c", SessionStatus::Errored, NOW_SECS - 10)],
        );
        let snapshot = derive_of(&[errored]);
        assert_eq!(
            snapshot.session(&row_id("e1", "c")).unwrap().working_since_ms,
            None
        );
    }

    // ── projects, groups ─────────────────────────────────────────────────

    #[test]
    fn groups_bucket_repository_clones_across_engines() {
        // Two engines, two checkouts of the same repository: the older
        // checkout names the group.
        let mut clone_a = chat("clone-a");
        activity(&mut clone_a, 100);
        clone_a.space_id = Some("sp-a".into());
        let e1 = engine(
            "e1",
            vec![clone_a],
            vec![space(
                "sp-a",
                "/src/clone-a",
                Some("github.com/roboco/roboco"),
                10
            )],
            Vec::new(),
        );
        let mut clone_b = chat("clone-b");
        activity(&mut clone_b, 200);
        clone_b.space_id = Some("sp-b".into());
        clone_b.device_id = "dev-2".into();
        let mut e2 = engine("e2", vec![clone_b], Vec::new(), Vec::new());
        e2.rows.spaces = vec![Space {
            device_id: "dev-2".into(),
            ..space("sp-b", "/src/clone-b", Some("github.com/roboco/roboco"), 20)
        }];
        e2.device_id = Some("dev-2".into());
        e2.rows.devices = vec![device("dev-2", "Laptop")];

        let snapshot = derive_of(&[e1, e2]);
        // One project group shared by both engines' clones.
        let groups = &snapshot.front.groups;
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].id, "project:repo:github.com/roboco/roboco");
        // The representative is the OLDEST checkout.
        assert_eq!(groups[0].name, "clone-a");
        // Rows keep recency order (the newer clone first).
        assert_eq!(
            ids(&groups[0].sessions),
            [row_id("e2", "clone-b"), row_id("e1", "clone-a")]
        );
        // Project views share the group key and the representative's name.
        let projects = &snapshot.projects;
        assert_eq!(projects.len(), 2);
        for project in projects {
            assert_eq!(project.group_key, "repo:github.com/roboco/roboco");
            assert_eq!(project.group_name, "clone-a");
        }
    }

    #[test]
    fn projectless_rows_group_per_engine_device() {
        let mut home = chat("home-e1");
        activity(&mut home, 100);
        let mut home2 = chat("home-e2");
        activity(&mut home2, 200);
        home2.device_id = "dev-2".into(); // the row's host device
        let e1 = engine("e1", vec![home], Vec::new(), Vec::new());
        let mut e2 = engine("e2", vec![home2], Vec::new(), Vec::new());
        e2.device_id = Some("dev-2".into());
        e2.rows.devices = vec![device("dev-2", "Laptop")];
        let snapshot = derive_of(&[e1, e2]);
        // Group order follows the most recent session (e2 first).
        assert_eq!(snapshot.front.groups.len(), 2);
        assert_eq!(
            snapshot.front.groups[0].id,
            format!("home:{}", row_id("e2", "dev-2"))
        );
        assert_eq!(snapshot.front.groups[0].name, "Home · Laptop");
        assert_eq!(
            snapshot.front.groups[1].id,
            format!("home:{}", row_id("e1", "dev-1"))
        );
        assert_eq!(snapshot.front.groups[1].name, "Home · Studio");
        // A nameless device reads plain "Home".
        let mut e3 = engine("e3", vec![chat("home-e3")], Vec::new(), Vec::new());
        e3.rows.devices = vec![device("dev-3", "unknown-device")];
        let snapshot = derive_of(&[e3]);
        assert_eq!(snapshot.front.groups.len(), 1);
        assert_eq!(snapshot.front.groups[0].name, "Home");
    }

    #[test]
    fn projects_carry_their_view_fields() {
        let mut in_space = chat("in-space");
        activity(&mut in_space, 100);
        in_space.space_id = Some("sp".into());
        let snapshot = derive_of(&[engine(
            "e1",
            vec![in_space],
            vec![space("sp", "/repo", None, 5)],
            Vec::new(),
        )]);
        assert_eq!(snapshot.projects.len(), 1);
        let project = &snapshot.projects[0];
        assert_eq!(project.id, row_id("e1", "sp"));
        assert_eq!(project.path, "/repo");
        assert_eq!(project.name, "repo"); // display_name = basename
        assert!(!project.git_detected);
        assert_eq!(project.engine_id, "e1");
        assert_eq!(project.device_id, row_id("e1", "dev-1"));
        assert_eq!(project.device_name.as_deref(), Some("Studio"));
        assert!(project.device_online);
        // A space without a repository is a group of its own.
        assert_eq!(project.group_key, row_id("e1", "sp"));
        assert_eq!(project.group_name, "repo");
        // Color = FNV of the representative path.
        assert_eq!(project.color_index, project_color_index("/repo"));
        // Projectless and pull-request lists are populated.
        assert!(snapshot.projectless.is_empty());
        assert!(snapshot.pull_requests.open.is_empty());
        assert!(snapshot.session(&row_id("e1", "in-space")).is_some());
    }

    // ── search ───────────────────────────────────────────────────────

    fn search_snapshot() -> WorkspaceSnapshot {
        let mut alpha = chat("alpha");
        alpha.title = Some("Fix the login flow".into());
        alpha.last_message_preview = Some("refactored the auth module".into());
        activity(&mut alpha, 100);
        let mut beta = chat("beta");
        beta.title = Some("Write docs".into());
        beta.last_message_preview = Some("documented the login flow".into());
        activity(&mut beta, 200);
        let mut archived = chat("gamma");
        archived.title = Some("login flow cleanup".into());
        archived.archived = true;
        activity(&mut archived, 50);
        let mut with_project = chat("delta");
        with_project.title = Some("unrelated".into());
        with_project.space_id = Some("sp".into());
        activity(&mut with_project, 10);
        derive_of(&[engine(
            "e1",
            vec![alpha, beta, archived, with_project],
            vec![space("sp", "login-system", None, 0)],
            Vec::new(),
        )])
    }

    #[test]
    fn search_requires_every_term() {
        let snapshot = search_snapshot();
        // One term: two titles, one preview, one project name.
        assert_eq!(snapshot.search("login", 60).len(), 4);
        // AND across terms: "login" + "docs" only matches the docs row.
        let hits = snapshot.search("login docs", 60);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].session.id, row_id("e1", "beta"));
    }

    #[test]
    fn search_rewards_prefixes_and_title_beats_preview() {
        let snapshot = search_snapshot();
        // "Fix the login flow": title prefix "fix" earns the bonus.
        let title_hit = snapshot.search("fix", 60)[0].clone();
        assert_eq!(title_hit.session.id, row_id("e1", "alpha"));
        assert_eq!(title_hit.field, SearchField::Title);
        // Title 100 + prefix bonus 50 = 150.
        assert_eq!(title_hit.score, 150);
        let hits = snapshot.search("login", 60);
        // Order: title (150), archived title (75), project prefix (60),
        // word-boundary preview (30).
        let fields: Vec<(u32, &str)> = hits
            .iter()
            .map(|h| (h.score, h.session.id.as_str()))
            .collect();
        assert_eq!(
            fields,
            vec![
                (150, row_id("e1", "alpha").as_str()),
                (75, row_id("e1", "gamma").as_str()),
                (60, row_id("e1", "delta").as_str()),
                (30, row_id("e1", "beta").as_str()),
            ]
        );
        assert_eq!(hits[0].field, SearchField::Title);
        // The archived row halves its title score: 150 / 2 = 75.
        let archived = hits.iter().find(|h| h.session.archived).unwrap();
        assert_eq!(archived.score, 75);
        // A word-boundary preview match ("… the LOGIN flow") earns the
        // preview half-bonus: 20 + 10.
        let preview = hits
            .iter()
            .find(|h| h.field == SearchField::Preview)
            .unwrap();
        assert_eq!(preview.score, 30);
    }

    #[test]
    fn search_breaks_ties_by_recency_then_id_and_respects_the_limit() {
        let mut a = chat("a");
        a.title = Some("same title".into());
        activity(&mut a, 100);
        let mut b = chat("b");
        b.title = Some("same title".into());
        activity(&mut b, 300);
        let mut c = chat("c");
        c.title = Some("same title".into());
        activity(&mut c, 200);
        let snapshot = derive_of(&[engine("e1", vec![a, b, c], Vec::new(), Vec::new())]);
        let hits = snapshot.search("title", 60);
        let ordered: Vec<String> = hits.iter().map(|h| h.session.id.clone()).collect();
        assert_eq!(
            ordered,
            [row_id("e1", "b"), row_id("e1", "c"), row_id("e1", "a")]
        );
        // The limit truncates.
        assert_eq!(snapshot.search("title", 2).len(), 2);
    }

    #[test]
    fn an_empty_query_returns_nothing_and_project_names_match() {
        let snapshot = search_snapshot();
        assert!(snapshot.search("", 60).is_empty());
        assert!(snapshot.search("   ", 60).is_empty());
        // The project name is a haystack.
        let hits = snapshot.search("login-system", 60);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].field, SearchField::Project);
        assert_eq!(hits[0].session.id, row_id("e1", "delta"));
    }

    /// The search pool is top-level rows only (`parent_chat_id == None`,
    /// archived included) — children never surface in Search.
    #[test]
    fn search_pool_excludes_children() {
        let mut child = chat("child");
        child.title = Some("hidden child".into());
        child.parent_chat_id = Some("parent".into());
        activity(&mut child, 100);
        let snapshot = derive_of(&[engine(
            "e1",
            vec![chat("parent"), child],
            Vec::new(),
            Vec::new(),
        )]);
        assert!(snapshot.search("hidden", 60).is_empty());
    }

    // ── ticket 09: the pending-chat overlay ──────────────────────────

    fn pending_chat(engine_id: &str, raw: &str, space: Option<&str>) -> PendingChat {
        PendingChat {
            engine_id: engine_id.into(),
            chat_id: row_id(engine_id, raw),
            raw_chat_id: raw.into(),
            space_id: space.map(|id| row_id(engine_id, id)),
            device_id: row_id(engine_id, "dev-1"),
            cwd: "/repo".into(),
            config: None,
            branch: Some("main".into()),
            parent_chat_id: None,
            created_at: at(NOW_SECS - 5),
        }
    }

    /// A pending chat renders a row with no engine row behind it: in the
    /// `sessions` map, in `recent` (most recent), titled "New session",
    /// not archived, with the created cwd/branch and the engine's online
    /// state and device name.
    #[test]
    fn a_pending_chat_renders_before_the_engine_echo() {
        let space = space("space-1", "/repo", None, 0);
        let engines = [engine("e1", Vec::new(), vec![space], Vec::new())];
        let mut pending = HashMap::new();
        pending.insert(row_id("e1", "new-chat"), pending_chat("e1", "new-chat", Some("space-1")));
        let snapshot = derive(&DeriveContext {
            engines: &engines,
            now: at(NOW_SECS),
            overlays: &Overlays::default(),
            change_requests: &HashMap::new(),
            send_states: &HashMap::new(),
            pending_chats: &pending,
            running_subagents: &HashMap::new(),
            previous: None,
        });
        let row = snapshot
            .session(&row_id("e1", "new-chat"))
            .expect("the pending chat renders");
        assert_eq!(row.title, "New session");
        assert!(!row.has_title);
        assert!(!row.archived);
        assert_eq!(row.cwd.as_deref(), Some("/repo"));
        assert_eq!(row.branch.as_deref(), Some("main"));
        assert!(row.device_online && row.engine_online);
        assert_eq!(row.device_name.as_deref(), Some("Studio"));
        // It carries the project ref and leads `recent` (created just now).
        assert_eq!(row.project.as_ref().unwrap().id, row_id("e1", "space-1"));
        assert_eq!(snapshot.front.recent[0].id, row.id);
        // The project's session list includes it, so the project is alive.
        assert_eq!(snapshot.projects[0].sessions.len(), 1);
    }

    /// The engine's own row wins once it arrives: the pending overlay
    /// adds no duplicate, and a projectless pending chat groups under its
    /// host device like any other.
    #[test]
    fn the_engine_row_wins_and_projectless_pending_chats_render() {
        let mut echoed = chat("new-chat");
        echoed.title = Some("Engine title".into());
        activity(&mut echoed, 10);
        let engines = [engine("e1", vec![echoed], Vec::new(), Vec::new())];
        let mut pending = HashMap::new();
        pending.insert(row_id("e1", "new-chat"), pending_chat("e1", "new-chat", None));
        let snapshot = derive(&DeriveContext {
            engines: &engines,
            now: at(NOW_SECS),
            overlays: &Overlays::default(),
            change_requests: &HashMap::new(),
            send_states: &HashMap::new(),
            pending_chats: &pending,
            running_subagents: &HashMap::new(),
            previous: None,
        });
        // One row, and it is the engine's (titled), not the synthetic one.
        assert_eq!(snapshot.sessions.len(), 1);
        assert_eq!(snapshot.session(&row_id("e1", "new-chat")).unwrap().title, "Engine title");
        // A projectless pending chat lands in `projectless`.
        assert_eq!(snapshot.projectless.len(), 1);
    }

    /// A pending chat whose engine is absent from the fleet renders
    /// nothing (its 30 s TTL clears it); the derive stays total.
    #[test]
    fn a_pending_chat_of_an_absent_engine_renders_nothing() {
        let mut pending = HashMap::new();
        pending.insert(row_id("gone", "new-chat"), pending_chat("gone", "new-chat", None));
        let snapshot = derive(&DeriveContext {
            engines: &[],
            now: at(NOW_SECS),
            overlays: &Overlays::default(),
            change_requests: &HashMap::new(),
            send_states: &HashMap::new(),
            pending_chats: &pending,
            running_subagents: &HashMap::new(),
            previous: None,
        });
        assert!(snapshot.sessions.is_empty());
        assert!(snapshot.front.recent.is_empty());
    }
}

