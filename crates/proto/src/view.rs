//! Frontend-agnostic view logic: the derivations every viewport needs and none
//! of them should own — sort orders, staleness gating, sidebar grouping, the
//! boot gate, relative times.
//!
//! This lives in `proto` rather than in the viewport crate so the rules stay
//! pure and independently testable: the same workspace doc must produce the
//! same row order on every surface, and there is exactly one implementation
//! and one test suite per rule.
//!
//! Everything in this module is pure. `chat_indicator` (the status derivation
//! these gate on) is in [`crate::entities`].

use chrono::{DateTime, Utc};

use crate::{Chat, ChatIndicator, Session, SessionStatus, Space};

// ---------------------------------------------------------------------------
// Connection + status
// ---------------------------------------------------------------------------

/// Viewport ⇄ engine connection lifecycle.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConnectionStatus {
    Connecting,
    Ready,
    Failed(String),
}

/// What a chat's status dot / working indicator should show right now.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Indicator {
    None,
    Working,
    AwaitingInput,
    Errored,
}

/// A `Working`/`AwaitingInput` session older than this is treated as dead — a
/// crashed backend must never show an eternal "Working" (feature-inventory
/// §1.12). Engines heartbeat sessions well inside this window.
pub const SESSION_STALE_MS: i64 = 45_000;

/// Staleness-checked indicator for a session row. Pure.
pub fn effective_indicator(session: Option<&Session>, now: DateTime<Utc>) -> Indicator {
    let Some(session) = session else {
        return Indicator::None;
    };
    match session.status {
        SessionStatus::Idle => Indicator::None,
        SessionStatus::Errored => Indicator::Errored,
        SessionStatus::Working | SessionStatus::AwaitingInput => {
            let age_ms = now
                .signed_duration_since(session.updated_at)
                .num_milliseconds();
            if age_ms > SESSION_STALE_MS {
                Indicator::None
            } else if session.status == SessionStatus::Working {
                Indicator::Working
            } else {
                Indicator::AwaitingInput
            }
        }
    }
}

/// The full display status for a chat row / tab dot: live states win, then the
/// synced seen marker decides completed-vs-idle. Staleness gating rides on
/// [`effective_indicator`]; the derivation itself is [`crate::chat_indicator`].
pub fn display_status(chat: &Chat, session: Option<&Session>, now: DateTime<Utc>) -> ChatIndicator {
    let live = session.filter(|s| effective_indicator(Some(s), now) != Indicator::None);
    crate::chat_indicator(chat, live)
}

/// Attention bucket for the sidebar's Active list — lower is more urgent.
pub fn attention_rank(status: ChatIndicator) -> u8 {
    match status {
        ChatIndicator::AwaitingInput => 0,
        ChatIndicator::Errored => 1,
        ChatIndicator::Working => 2,
        ChatIndicator::Completed => 3,
        ChatIndicator::Idle => 4,
    }
}

// ---------------------------------------------------------------------------
// Notification decision (shared by every surface)
// ---------------------------------------------------------------------------

/// Which notification a state transition earned. Shared by the desktop
/// chimes/banners (via `roboco-ui`'s `Sound` mapping), the web port and the
/// Android notification driver — one rule for every client (the mobile
/// notifications ticket moved it here from `crates/ui/src/sound.rs`, ported
/// 1:1; the decision itself must never drift between surfaces).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NotifyKind {
    /// An agent turn completed successfully.
    Done,
    /// The agent is waiting on a question (→ AwaitingInput).
    Request,
    /// A run failed or the durable connection state degraded.
    Attention,
}

/// Notification baseline is separate from the visual activity indicator:
/// going idle can mean cancellation, expiry, or an internal handoff.
///
/// Lives in `proto` (not the viewport crate) so desktop, web and mobile
/// share one copy of the rule (`sound_since`) and its staleness inputs
/// ([`effective_indicator`], [`SESSION_STALE_MS`]).
#[derive(Debug, Clone)]
pub struct SessionNotificationState {
    /// The staleness-gated indicator ([`effective_indicator`]).
    pub indicator: Indicator,
    /// The last successfully completed turn marker, compared across
    /// baselines for the Done rule.
    pub last_completed_turn: Option<String>,
    /// `now − updated_at ≤ SESSION_STALE_MS` — the freshness gate of the
    /// Done rule.
    pub fresh: bool,
}

impl SessionNotificationState {
    /// The baseline of one session row. `now` must be measured on the same
    /// clock as `updated_at` (the mobile client re-stamps rows on the
    /// phone's clock as frames arrive, so `Utc::now()` is correct there).
    pub fn new(session: &Session, now: DateTime<Utc>) -> Self {
        Self {
            indicator: effective_indicator(Some(session), now),
            last_completed_turn: session.last_completed_turn.clone(),
            fresh: now
                .signed_duration_since(session.updated_at)
                .num_milliseconds()
                <= SESSION_STALE_MS,
        }
    }

    /// Call after saving the new baseline, including when delivery is
    /// pending or outputs are disabled. Suppressed pings must never be
    /// replayed later. Precedence: error, then input, then done.
    pub fn sound_since(&self, prev: &Self, send_pending: bool) -> Option<NotifyKind> {
        if self.indicator == Indicator::Errored && prev.indicator != Indicator::Errored {
            return Some(NotifyKind::Attention);
        }
        if self.indicator == Indicator::AwaitingInput
            && prev.indicator != Indicator::AwaitingInput
        {
            return Some(NotifyKind::Request);
        }
        if !send_pending
            && self.fresh
            && self.last_completed_turn.is_some()
            && self.last_completed_turn != prev.last_completed_turn
        {
            return Some(NotifyKind::Done);
        }
        None
    }
}

// ---------------------------------------------------------------------------
// Sort orders
// ---------------------------------------------------------------------------

/// Active-list order: pure recency (`last_message_at` desc, `created_at`
/// fallback), id tiebreak so the sort is total. Deliberately NOT
/// attention-bucketed: status drives the DOT, never the position — bucketing
/// meant that merely OPENING a completed session (completed → seen → idle)
/// dropped its row under the pointer (user report: "their position in the
/// scrollbar changes"). Matches the old sidebar, which rendered chats in
/// recency order and let the dots carry urgency; [`attention_rank`] still
/// aggregates the space rows' urgency dot.
pub fn sort_active(rows: &mut Vec<(ChatIndicator, &Chat)>) {
    rows.sort_by(|(_, a), (_, b)| {
        let ka = a.last_message_at.unwrap_or(a.created_at);
        let kb = b.last_message_at.unwrap_or(b.created_at);
        kb.cmp(&ka).then_with(|| a.id.cmp(&b.id))
    });
}

/// Session-tab order for a space: creation order (activity never reorders
/// tabs), id tiebreak. Pure.
pub fn sort_tabs(chats: &mut [&Chat]) {
    chats.sort_by(|a, b| {
        a.created_at
            .cmp(&b.created_at)
            .then_with(|| a.id.cmp(&b.id))
    });
}

/// Spaces list order: creation order, id tiebreak — total and stable across
/// devices. Pure.
pub fn sort_spaces(spaces: &mut [Space]) {
    spaces.sort_by(|a, b| {
        a.created_at
            .cmp(&b.created_at)
            .then_with(|| a.id.cmp(&b.id))
    });
}

/// The project a space belongs to: spaces sharing a repository identity —
/// clones and worktrees of one repository, on any device — are one project.
/// A space without an identity is a project of its own. Pure.
pub fn project_key(space: &Space) -> String {
    match space.repository_id.as_deref() {
        Some(repository_id) => format!("repo:{repository_id}"),
        None => space.id.clone(),
    }
}

/// The space that speaks for `space`'s whole project: the oldest member, id
/// tiebreak. Its name and color stand for every member, and the choice is
/// the same on every device, so a repository looks alike everywhere. Pure.
pub fn representative_space<'a>(spaces: &'a [Space], space: &'a Space) -> &'a Space {
    let Some(repository_id) = space.repository_id.as_deref() else {
        return space;
    };
    spaces
        .iter()
        .filter(|s| s.repository_id.as_deref() == Some(repository_id))
        .min_by_key(|s| (s.created_at, s.id.as_str()))
        .unwrap_or(space)
}

/// Sidebar order: `last_message_at` desc, falling back to `created_at`; ties
/// break by `created_at` desc then id so the sort is total and stable across
/// devices. Pure.
pub fn sort_chats(chats: &mut [Chat]) {
    chats.sort_by(|a, b| {
        let ka = a.last_message_at.unwrap_or(a.created_at);
        let kb = b.last_message_at.unwrap_or(b.created_at);
        kb.cmp(&ka)
            .then_with(|| b.created_at.cmp(&a.created_at))
            .then_with(|| a.id.cmp(&b.id))
    });
}

// ---------------------------------------------------------------------------
// Boot gate
// ---------------------------------------------------------------------------

/// The engine connection determines whether the shell is ready.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GatePhase {
    Loading,
    Failed(String),
    Ready,
}

pub fn gate_phase(connection: &ConnectionStatus) -> GatePhase {
    match connection {
        ConnectionStatus::Connecting => GatePhase::Loading,
        ConnectionStatus::Failed(error) => GatePhase::Failed(error.clone()),
        ConnectionStatus::Ready => GatePhase::Ready,
    }
}

#[cfg(test)]
mod project_tests {
    use super::*;

    fn space(id: &str, device: &str, repository: Option<&str>, minutes: i64) -> Space {
        Space {
            id: id.into(),
            device_id: device.into(),
            path: format!("/{device}/{id}"),
            name: None,
            git_detected: repository.is_some(),
            git_checked_at: None,
            checkout_id: None,
            repository_id: repository.map(str::to_string),
            created_at: DateTime::<Utc>::UNIX_EPOCH + chrono::TimeDelta::minutes(minutes),
        }
    }

    #[test]
    fn spaces_sharing_a_repository_are_one_project_led_by_the_oldest() {
        let spaces = vec![
            space("laptop", "mac", Some("github.com/o/r"), 2),
            space("server", "vps", Some("github.com/o/r"), 1),
            space("notes", "mac", None, 0),
        ];
        assert_eq!(project_key(&spaces[0]), "repo:github.com/o/r");
        assert_eq!(project_key(&spaces[0]), project_key(&spaces[1]));
        assert_eq!(project_key(&spaces[2]), "notes");
        assert_eq!(representative_space(&spaces, &spaces[0]).id, "server");
        assert_eq!(representative_space(&spaces, &spaces[1]).id, "server");
        assert_eq!(representative_space(&spaces, &spaces[2]).id, "notes");
    }
}

#[cfg(test)]
mod gate_tests {
    use super::*;

    #[test]
    fn engine_connection_controls_the_shell() {
        assert_eq!(
            gate_phase(&ConnectionStatus::Connecting),
            GatePhase::Loading
        );
        assert_eq!(gate_phase(&ConnectionStatus::Ready), GatePhase::Ready);
        assert_eq!(
            gate_phase(&ConnectionStatus::Failed("unavailable".into())),
            GatePhase::Failed("unavailable".into())
        );
    }
}

// ---------------------------------------------------------------------------
// Sidebar grouping
// ---------------------------------------------------------------------------

/// One grouped-by-project sidebar section.
#[derive(Debug, Clone, PartialEq)]
pub struct ChatGroup<'a> {
    pub label: String,
    pub chats: Vec<&'a Chat>,
}

/// Project label for a chat: the basename of its cwd, or "No project".
pub fn project_label(cwd: Option<&str>) -> String {
    let Some(cwd) = cwd.map(str::trim).filter(|c| !c.is_empty()) else {
        return "No project".to_string();
    };
    if matches!(cwd, "~" | "~/") {
        return "No project".to_string();
    }
    std::path::Path::new(cwd.trim_end_matches(['/', '\\']))
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .filter(|n| !n.is_empty())
        .unwrap_or_else(|| cwd.to_string())
}

/// Group chats by project label, preserving the incoming (recency) order both
/// for groups (by their most recent chat) and rows within a group. Pure.
pub fn group_chats<'a>(chats: impl IntoIterator<Item = &'a Chat>) -> Vec<ChatGroup<'a>> {
    let mut groups: Vec<ChatGroup<'a>> = Vec::new();
    for chat in chats {
        let label = project_label(chat.cwd.as_deref());
        match groups.iter_mut().find(|g| g.label == label) {
            Some(group) => group.chats.push(chat),
            None => groups.push(ChatGroup {
                label,
                chats: vec![chat],
            }),
        }
    }
    groups
}

/// Compact relative time ("now", "5m", "3h", "2d", "1w", …) — no "ago" suffix;
/// port of roboco's `formatTimeAgo`.
pub fn format_time_ago(then: DateTime<Utc>, now: DateTime<Utc>) -> String {
    let s = now.signed_duration_since(then).num_seconds().max(0);
    // Under a minute reads as "now" — otherwise 45–59s floors to a bare "0m".
    if s < 60 {
        return "now".to_string();
    }
    let m = s / 60;
    if m < 60 {
        return format!("{m}m");
    }
    let h = m / 60;
    if h < 24 {
        return format!("{h}h");
    }
    let d = h / 24;
    if d < 7 {
        return format!("{d}d");
    }
    let w = d / 7;
    if w < 5 {
        return format!("{w}w");
    }
    let mo = d / 30;
    if mo < 12 {
        return format!("{mo}mo");
    }
    format!("{}y", d / 365)
}

/// Session-row sub-line, "project · branch" (roboco `chatLocation`): the repo
/// checkout identity. Either part may be missing; empty when both are.
pub fn chat_location(chat: &Chat) -> Option<String> {
    let project = chat
        .cwd
        .as_deref()
        .map(str::trim)
        .filter(|c| !c.is_empty())
        .map(|c| project_label(Some(c)));
    let reference = chat
        .branch
        .as_deref()
        .map(str::trim)
        .filter(|b| !b.is_empty());
    match (project, reference) {
        (Some(p), Some(r)) => Some(format!("{p} · {r}")),
        (Some(p), None) => Some(p),
        (None, Some(r)) => Some(r.to_string()),
        (None, None) => None,
    }
}

// ---------------------------------------------------------------------------
// Tool summaries (pure)
// ---------------------------------------------------------------------------

/// Collapse model-generated text onto ONE line for single-line surfaces (tool
/// chips, titles, previews): newlines, tabs and runs of whitespace become
/// single spaces, trimmed.
///
/// Both viewports need this for the same reason from opposite directions — gpui
/// breaks on a literal `\n` before its ellipsis logic, and a terminal cell grid
/// would take an embedded newline as a cursor move.
pub fn single_line(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn plural(n: usize, one: &str, many: &str) -> String {
    if n == 1 {
        format!("{n} {one}")
    } else {
        format!("{n} {many}")
    }
}

/// Per-kind chip label + one-line detail. Labels match roboco's `describeTool`
/// (tool-chip.tsx) exactly, so the two viewports name a tool identically.
pub fn tool_chip_content(call: &crate::ToolCall) -> (&'static str, String) {
    let (label, detail) = tool_chip_content_raw(call);
    (label, single_line(&detail))
}

fn tool_chip_content_raw(call: &crate::ToolCall) -> (&'static str, String) {
    use crate::ToolCall;
    match call {
        ToolCall::Exec { command } => ("Run", command.clone()),
        ToolCall::ReadFile { path } => ("Read", path.clone()),
        ToolCall::WriteFile { path, .. } => ("Write", path.clone()),
        ToolCall::EditFile { path, .. } => ("Edit", path.clone()),
        ToolCall::ApplyPatch { path } => {
            ("Patch", path.clone().unwrap_or_else(|| "workspace".into()))
        }
        ToolCall::Search { pattern, path } => (
            "Search",
            match path {
                Some(path) => format!("{pattern} in {path}"),
                None => pattern.clone(),
            },
        ),
        ToolCall::Glob { pattern } => ("Glob", pattern.clone()),
        ToolCall::WebFetch { url, .. } => ("Fetch", url.clone()),
        ToolCall::WebSearch { query } => ("Web", query.clone()),
        ToolCall::Todo { items } => {
            let done = items.iter().filter(|i| i.done).count();
            ("Todo", format!("{done}/{} done", items.len()))
        }
        ToolCall::Mcp { server, tool, .. } => ("MCP", format!("{server} · {tool}")),
        // Subagent spawns decode as Unknown named "Agent[: <description>]"
        // (every native driver's convention): label them "Agent" with the
        // description as the detail — "Tool · Agent: scan repo" read as two
        // labels fighting.
        ToolCall::Unknown { name, .. } => match name.strip_prefix("Agent: ") {
            Some(description) => ("Agent", description.to_owned()),
            None if name == "Agent" => ("Agent", String::new()),
            None => ("Tool", name.clone()),
        },
    }
}

/// The ToolGroup summary line — "Ran 3 commands · edited 2 files".
///
/// Takes `(call, is_error)` pairs so each viewport can keep its own row model;
/// the summary itself is one implementation for both.
pub fn tool_group_summary(tools: &[(crate::ToolCall, bool)]) -> String {
    use crate::ToolCall;
    let mut commands = 0usize;
    let mut edited: Vec<&str> = Vec::new();
    let mut reads = 0usize;
    let mut searches = 0usize;
    let mut fetches = 0usize;
    let mut todos = 0usize;
    let mut other = 0usize;
    let mut failed = 0usize;
    for (call, is_error) in tools {
        if *is_error {
            failed += 1;
        }
        match call {
            ToolCall::Exec { .. } => commands += 1,
            ToolCall::WriteFile { path, .. } | ToolCall::EditFile { path, .. } => {
                if !edited.contains(&path.as_str()) {
                    edited.push(path);
                }
            }
            ToolCall::ApplyPatch { path } => {
                let p = path.as_deref().unwrap_or("patch");
                if !edited.contains(&p) {
                    edited.push(p);
                }
            }
            ToolCall::ReadFile { .. } => reads += 1,
            ToolCall::Search { .. } | ToolCall::Glob { .. } | ToolCall::WebSearch { .. } => {
                searches += 1
            }
            ToolCall::WebFetch { .. } => fetches += 1,
            ToolCall::Todo { .. } => todos += 1,
            ToolCall::Mcp { .. } | ToolCall::Unknown { .. } => other += 1,
        }
    }
    let mut segments: Vec<String> = Vec::new();
    if commands > 0 {
        segments.push(format!("ran {}", plural(commands, "command", "commands")));
    }
    if !edited.is_empty() {
        segments.push(format!("edited {}", plural(edited.len(), "file", "files")));
    }
    if reads > 0 {
        segments.push(format!("read {}", plural(reads, "file", "files")));
    }
    if searches > 0 {
        segments.push(format!("searched {}", plural(searches, "time", "times")));
    }
    if fetches > 0 {
        segments.push(format!("fetched {}", plural(fetches, "page", "pages")));
    }
    if todos > 0 {
        segments.push("updated todos".to_string());
    }
    if other > 0 {
        segments.push(format!("called {}", plural(other, "tool", "tools")));
    }
    if segments.is_empty() {
        segments.push(plural(tools.len(), "tool", "tools"));
    }
    if failed > 0 {
        segments.push(format!("{failed} failed"));
    }
    let mut summary = segments.join(" · ");
    // Capitalize the first segment only (roboco's style).
    if let Some(first) = summary.get(0..1) {
        let upper = first.to_uppercase();
        summary.replace_range(0..1, &upper);
    }
    summary
}

/// The status-dot palette, as oklch triples (L, C, H°).
///
/// Colors live here rather than in the viewport because the *meaning* of a
/// dot is part of the protocol, not the presentation — a given status must
/// read the same on every surface. `roboco-ui` has the oklch→sRGB math.
pub mod dot {
    /// Running. Pink, not amber: the harsh yellow read as a warning, and running
    /// is routine (user request).
    pub const WORKING: (f32, f32, f32) = (0.718, 0.202, 349.761);
    /// Asking a question. Indigo — must read differently from "busy" at a glance.
    pub const AWAITING: (f32, f32, f32) = (0.673, 0.182, 276.935);
    /// Errored. Red-400.
    pub const ERRORED: (f32, f32, f32) = (0.704, 0.191, 22.216);
    /// Finished but unseen. Emerald — reads as "ready for you".
    pub const COMPLETED: (f32, f32, f32) = (0.765, 0.177, 163.223);
}

// ---------------------------------------------------------------------------
// Checkout selection (new sessions)
// ---------------------------------------------------------------------------

/// Where a new session runs (t3code's env-mode: `local | worktree`).
///
/// "Current worktree" is deliberately **not** a third mode — it is `Local` when
/// the picked ref already happens to be materialized as a worktree, in which
/// case the session reuses that checkout's path. Modelling it as three states
/// would let the UI hold a combination the engine cannot honour.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum CheckoutKind {
    /// The space's own folder — or the picked ref's existing worktree.
    #[default]
    Local,
    /// A fresh isolated worktree created off the picked base ref on send.
    NewWorktree,
}

/// The resolved on-send checkout action.
#[derive(Debug, Clone, PartialEq)]
pub enum CheckoutPlan {
    /// Run in the space folder as-is. `branch` is the checkout's branch (the
    /// picked or current ref), carried onto `createChat` so the session names
    /// it from the first frame; `None` = refs never loaded.
    CurrentCheckout { branch: Option<String> },
    /// Reuse the picked ref's existing worktree (a cwd override; no git).
    ReuseWorktree { path: String, branch: String },
    /// `CreateWorktree` off `base` on send (the engine mints a `roboco/<name>`
    /// branch). `base: None` = refs never loaded — send falls back to the space
    /// folder rather than failing.
    NewWorktree { base: Option<String> },
}

/// Resolve the on-send action from the mode and the picked ref.
pub fn checkout_plan(kind: CheckoutKind, picked: Option<&crate::RepoRef>) -> CheckoutPlan {
    let name = picked.map(|r| r.name.clone());
    match kind {
        CheckoutKind::NewWorktree => CheckoutPlan::NewWorktree { base: name },
        CheckoutKind::Local => match picked.and_then(|r| r.worktree_path.clone()) {
            Some(path) => CheckoutPlan::ReuseWorktree {
                path,
                branch: name.unwrap_or_default(),
            },
            None => CheckoutPlan::CurrentCheckout { branch: name },
        },
    }
}

/// Label of the checkout-kind trigger (t3code `resolveEnvModeLabel`).
pub fn checkout_label(kind: CheckoutKind, picked: Option<&crate::RepoRef>) -> &'static str {
    match kind {
        CheckoutKind::NewWorktree => "New worktree",
        CheckoutKind::Local => {
            if picked.is_some_and(|r| r.worktree_path.is_some()) {
                "Current worktree"
            } else {
                "Current checkout"
            }
        }
    }
}

#[cfg(test)]
mod checkout_tests {
    use super::*;
    use crate::RepoRef;

    fn plain(name: &str) -> RepoRef {
        RepoRef {
            name: name.into(),
            current: false,
            worktree_path: None,
        }
    }

    fn materialized(name: &str, path: &str) -> RepoRef {
        RepoRef {
            name: name.into(),
            current: false,
            worktree_path: Some(path.into()),
        }
    }

    #[test]
    fn local_resolves_by_whether_the_ref_has_a_worktree() {
        // The same mode means two different things depending on the ref — which
        // is exactly why "current worktree" is not its own state.
        assert_eq!(
            checkout_plan(CheckoutKind::Local, Some(&plain("main"))),
            CheckoutPlan::CurrentCheckout {
                branch: Some("main".into())
            }
        );
        assert_eq!(
            checkout_plan(CheckoutKind::Local, Some(&materialized("feat", "/wt/feat"))),
            CheckoutPlan::ReuseWorktree {
                path: "/wt/feat".into(),
                branch: "feat".into()
            }
        );
        // No ref picked at all is still the space folder — with no branch to
        // stamp until refs load.
        assert_eq!(
            checkout_plan(CheckoutKind::Local, None),
            CheckoutPlan::CurrentCheckout { branch: None }
        );
    }

    #[test]
    fn new_worktree_carries_its_base_and_tolerates_none() {
        assert_eq!(
            checkout_plan(CheckoutKind::NewWorktree, Some(&plain("main"))),
            CheckoutPlan::NewWorktree {
                base: Some("main".into())
            }
        );
        // Refs never loaded: send falls back to the space folder rather than
        // failing, so the base is allowed to be absent.
        assert_eq!(
            checkout_plan(CheckoutKind::NewWorktree, None),
            CheckoutPlan::NewWorktree { base: None }
        );
    }

    #[test]
    fn labels_say_which_of_the_three_outcomes_you_will_get() {
        assert_eq!(
            checkout_label(CheckoutKind::Local, Some(&plain("main"))),
            "Current checkout"
        );
        assert_eq!(
            checkout_label(CheckoutKind::Local, Some(&materialized("f", "/wt/f"))),
            "Current worktree"
        );
        assert_eq!(
            checkout_label(CheckoutKind::NewWorktree, Some(&plain("main"))),
            "New worktree"
        );
    }
}

// ---------------------------------------------------------------------------
// Notification decision tests (moved 1:1 from crates/ui/src/sound.rs —
// the rule now lives here so desktop, web and mobile share one copy).
// ---------------------------------------------------------------------------

#[cfg(test)]
mod notification_tests {
    use super::*;

    fn baseline(indicator: Indicator, turn: Option<&str>) -> SessionNotificationState {
        SessionNotificationState {
            indicator,
            last_completed_turn: turn.map(str::to_owned),
            fresh: true,
        }
    }

    #[test]
    fn interrupted_and_expired_activity_never_chime() {
        let working = baseline(Indicator::Working, Some("old"));
        let idle = baseline(Indicator::None, Some("old"));
        assert_eq!(idle.sound_since(&working, false), None);
        // An older host without explicit completion metadata is silent too.
        assert_eq!(
            baseline(Indicator::None, None).sound_since(&baseline(Indicator::Working, None), false),
            None
        );
    }

    #[test]
    fn a_run_error_chimes_once_and_never_masquerades_as_completion() {
        let working = baseline(Indicator::Working, Some("old"));
        let errored = baseline(Indicator::Errored, Some("failed"));
        assert_eq!(
            errored.sound_since(&working, false),
            Some(NotifyKind::Attention)
        );
        assert_eq!(errored.sound_since(&errored, false), None);
    }

    #[test]
    fn ordinary_queue_completions_survive_coalesced_working_states() {
        let first = baseline(Indicator::Working, None);
        let second = baseline(Indicator::Working, Some("first"));
        assert_eq!(second.sound_since(&first, false), Some(NotifyKind::Done));
        assert_eq!(second.sound_since(&second, false), None);
        let last = baseline(Indicator::None, Some("second"));
        assert_eq!(last.sound_since(&second, false), Some(NotifyKind::Done));
        assert_eq!(last.sound_since(&last, false), None);
    }

    #[test]
    fn pending_send_consumes_completion_but_preserves_input_requests() {
        let before = baseline(Indicator::Working, None);
        let settled = baseline(Indicator::None, Some("first"));
        assert_eq!(settled.sound_since(&before, true), None);
        assert_eq!(settled.sound_since(&settled, false), None);
        let question = baseline(Indicator::AwaitingInput, Some("first"));
        assert_eq!(
            question.sound_since(&settled, true),
            Some(NotifyKind::Request)
        );
        assert_eq!(question.sound_since(&question, false), None);
    }

    #[test]
    fn stale_completion_is_consumed_without_replaying_on_a_heartbeat() {
        let before = baseline(Indicator::Working, None);
        let mut stale = baseline(Indicator::None, Some("old"));
        stale.fresh = false;
        assert_eq!(stale.sound_since(&before, false), None);
        let refreshed = baseline(Indicator::Working, Some("old"));
        assert_eq!(refreshed.sound_since(&stale, false), None);
    }

    /// `SessionNotificationState::new` folds the 45 s staleness gate into
    /// the baseline exactly as `effective_indicator` does (mobile's driver
    /// relies on this when it computes baselines off engine frames).
    #[test]
    fn new_derives_the_indicator_and_freshness_from_the_row() {
        let now = Utc::now();
        let fresh = now - chrono::TimeDelta::seconds(10);
        let stale = now - chrono::TimeDelta::seconds(50);
        let session = |status: SessionStatus, updated| Session {
            last_completed_turn: Some("t".into()),
            chat_id: "c".into(),
            device_id: "d".into(),
            status,
            started_at: None,
            updated_at: updated,
        };
        let working = SessionNotificationState::new(&session(SessionStatus::Working, fresh), now);
        assert_eq!(working.indicator, Indicator::Working);
        assert!(working.fresh);
        let expired = SessionNotificationState::new(&session(SessionStatus::Working, stale), now);
        // A Working row older than SESSION_STALE_MS reads as dead.
        assert_eq!(expired.indicator, Indicator::None);
        assert!(!expired.fresh);
        let idle = SessionNotificationState::new(&session(SessionStatus::Idle, fresh), now);
        assert_eq!(idle.indicator, Indicator::None);
        assert!(idle.fresh);
        assert_eq!(
            idle.last_completed_turn.as_deref(),
            Some("t"),
            "the completion marker rides along for the comparison"
        );
    }
}
