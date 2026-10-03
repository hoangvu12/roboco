//! The desktop's model of a native (Mimir) conversation.
//!
//! `NativeChatState` is the only authority: the engine writes it from
//! host-confirmed facts, so every view here is a pure function of it. A control
//! the user sends is read back from the command ledger: the typed host outcome
//! says accepted, refused or unknown, and a command the host has not settled
//! stays unconfirmed. This module holds no GPUI types so it is tested directly.

use crate::engine_registry::EngineConnectionState;
use roboco_doc::{SessionCommandEntry, SessionCommandStatus};
use roboco_engine::doc_host::{ToolBlobEncoding, ToolBlobWindow};
use roboco_proto::{
    NativeAnswer, NativeChatCatalog, NativeChatState, NativeChild, NativeChildControl,
    NativeChildStatus, NativeConfiguration, NativeControl, NativeControlOutcome, NativeDelivery,
    NativeGoal, NativeGoalCause, NativeGoalPhase, NativeLink, NativeMode, NativeNotice,
    NativePlanStatus, NativeQuestion, NativeReadiness, NativeSubmission, NativeSubmissionKind,
    NativeToolDetail, NativeToolPreview, NativeToolResultState, NativeToolView, NativeUsage,
};
use serde_json::Value;

pub const RELEASE_BLOCKED_COPY: &str = "Mimir is still working in this chat. Wait for it to finish or stop it, then continue in Mimir.";
pub const RECOVERING_COPY: &str = "Mimir is recovering this run. Live output is withheld until the next checkpoint, so nothing shown is a complete response yet.";
pub const IDLE_ONLY_COPY: &str = "Available when Mimir is idle";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tone {
    Neutral,
    Active,
    Warn,
    Danger,
}

// ---------------------------------------------------------------------------
// Work, release, recovery
// ---------------------------------------------------------------------------

pub fn attached(state: &NativeChatState) -> bool {
    matches!(state.link, NativeLink::Attached)
}

/// The engine refuses Release with Busy while work or a question is open; the button mirrors it.
pub fn release_blocker(state: &NativeChatState) -> Option<&'static str> {
    (state.working() || state.user_request.is_some()).then_some(RELEASE_BLOCKED_COPY)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkView {
    pub title: &'static str,
    pub detail: Option<String>,
    pub tone: Tone,
    /// Reconnect re-attaches this same chat's conversation.
    pub can_reconnect: bool,
    pub show_release: bool,
}

/// Connection state of the conversation. Engine reachability and work status are separate axes.
pub fn link_view(link: &NativeLink) -> LinkView {
    let view = |title, detail: Option<&str>, tone, can_reconnect, show_release| LinkView {
        title,
        detail: detail.map(str::to_owned),
        tone,
        can_reconnect,
        show_release,
    };
    match link {
        NativeLink::Detached => view(
            "Not connected to Mimir",
            Some("Reconnect to resume this conversation."),
            Tone::Warn,
            true,
            false,
        ),
        NativeLink::Attaching => view("Connecting to Mimir", None, Tone::Neutral, false, false),
        NativeLink::Attached => view("Connected to Mimir", None, Tone::Neutral, false, true),
        NativeLink::Busy { message } => view(
            "Mimir is busy elsewhere",
            Some(message),
            Tone::Warn,
            true,
            false,
        ),
        NativeLink::Interrupted { message } => view(
            "Connection to Mimir was interrupted",
            Some(message),
            Tone::Warn,
            true,
            false,
        ),
        NativeLink::Releasing => view(
            "Handing the conversation to Mimir",
            None,
            Tone::Neutral,
            false,
            false,
        ),
        NativeLink::Released => view(
            "Continued in Mimir",
            Some("Reconnect here to take the conversation back."),
            Tone::Neutral,
            true,
            false,
        ),
        NativeLink::Unavailable { message } => view(
            "Mimir is unavailable",
            Some(message),
            Tone::Danger,
            true,
            false,
        ),
    }
}

/// Engine reachability is its own axis, never folded into the link or work status.
pub fn engine_offline_copy(state: EngineConnectionState) -> Option<&'static str> {
    match state {
        EngineConnectionState::Off => Some(
            "This engine is offline. Mimir's own state is unchanged and will resume when the engine is back.",
        ),
        EngineConnectionState::Reconnecting => Some("Reconnecting to this engine."),
        EngineConnectionState::Connected => None,
    }
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/// `provider/model`, the id the engine's `split_model` reads back.
pub fn configuration_model_id(configuration: &NativeConfiguration) -> Option<String> {
    let model = configuration.model.as_deref()?;
    Some(match configuration.provider.as_deref() {
        Some(provider) => format!("{provider}/{model}"),
        None => model.to_owned(),
    })
}

pub fn mode_label(mode: NativeMode) -> &'static str {
    match mode {
        NativeMode::Build => "Build",
        NativeMode::Plan => "Plan",
    }
}

/// The host-confirmed mode, model and reasoning as one line.
pub fn configuration_label(configuration: &NativeConfiguration) -> String {
    let mut parts = vec![
        mode_label(configuration.mode).to_owned(),
        configuration_model_id(configuration).unwrap_or_else(|| "No model".to_owned()),
    ];
    if let Some(reasoning) = &configuration.reasoning {
        parts.push(reasoning.clone());
    }
    parts.join(" · ")
}

/// Who decides the model, reasoning and mode the chat's next prompt runs with.
/// The engine reconfigures the host from any model or reasoning a prompt
/// carries, so stale per-chat picks must never ride a prompt the host owns.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConfigAuthority {
    /// The composer's picks are the user's real choice and travel with the
    /// prompt: not a native chat, a chat this send creates, or a native
    /// conversation the host has not configured yet.
    Draft,
    /// An existing native chat whose host state has not arrived since it was
    /// opened. Whether the host owns its configuration is unknown, so nothing
    /// is sent until the projection lands.
    AwaitingHost,
    /// The host's confirmed configuration. Draft picks are never sent; the
    /// dock changes it with `Configure`.
    Host(NativeConfiguration),
}

/// `native_chat`: the chat row names the native harness, or a projection
/// already said so. `fresh`: this send creates the chat. `projected`: the
/// chat's live watch has delivered its state since the chat was opened.
pub fn config_authority(
    native_chat: bool,
    fresh: bool,
    projected: bool,
    state: Option<&NativeChatState>,
) -> ConfigAuthority {
    if let Some(configuration) = state.and_then(|state| state.configuration.clone()) {
        return ConfigAuthority::Host(configuration);
    }
    if native_chat && !fresh && !projected {
        return ConfigAuthority::AwaitingHost;
    }
    ConfigAuthority::Draft
}

/// The composer's model slot for a chat whose host owns the configuration, or
/// whose host state is still on its way. `None` keeps the ordinary picker.
pub fn config_slot_label(authority: &ConfigAuthority) -> Option<String> {
    match authority {
        ConfigAuthority::Draft => None,
        ConfigAuthority::AwaitingHost => Some("Waiting for Mimir".to_owned()),
        ConfigAuthority::Host(configuration) => Some(configuration_label(configuration)),
    }
}

/// A host command typed while the conversation works goes to the host now: the
/// engine routes it as command routing into the running request. Only a
/// command the host's catalog marks idle-only waits for the turn to end.
pub fn command_sends_now(text: &str, idle_only: impl Fn(&str) -> bool) -> bool {
    roboco_proto::invocation::leading_command(text).is_some_and(|(name, _)| !idle_only(name))
}

/// The picker's choice as a `Configure` control that names only what changed.
pub fn configure_control(
    current: Option<&NativeConfiguration>,
    model: Option<&str>,
    reasoning: Option<&str>,
    mode: Option<NativeMode>,
) -> NativeControl {
    let (provider, model) = match model {
        Some(id) => match id.split_once('/') {
            Some((provider, model)) => (Some(provider.to_owned()), Some(model.to_owned())),
            None => (None, Some(id.to_owned())),
        },
        None => (None, None),
    };
    NativeControl::Configure {
        provider,
        model,
        reasoning: reasoning
            .filter(|next| current.and_then(|c| c.reasoning.as_deref()) != Some(*next))
            .map(str::to_owned),
        mode: mode.filter(|next| current.map(|c| c.mode) != Some(*next)),
    }
}

pub fn reasoning_levels(
    catalog: Option<&NativeChatCatalog>,
    configuration: Option<&NativeConfiguration>,
) -> Vec<String> {
    let (Some(catalog), Some(configuration)) = (catalog, configuration) else {
        return Vec::new();
    };
    for provider in &catalog.providers {
        if configuration
            .provider
            .as_deref()
            .is_some_and(|id| id != provider.id)
        {
            continue;
        }
        if let Some(model) = provider
            .models
            .iter()
            .find(|choice| Some(choice.id.as_str()) == configuration.model.as_deref())
        {
            return model.reasoning.clone();
        }
    }
    Vec::new()
}

// ---------------------------------------------------------------------------
// Goal
// ---------------------------------------------------------------------------

pub fn goal_cause_label(cause: NativeGoalCause) -> &'static str {
    match cause {
        NativeGoalCause::Started => "Started",
        NativeGoalCause::Edited => "Edited",
        NativeGoalCause::Resumed => "Resumed",
        NativeGoalCause::NaturalContinuation => "Continuing on its own",
        NativeGoalCause::ReviewGap => "Review found a gap",
        NativeGoalCause::ReviewAccepted => "Review accepted the work",
        NativeGoalCause::UserPaused => "Paused by you",
        NativeGoalCause::UserInput => "Paused for your input",
        NativeGoalCause::Manual => "Changed manually",
        NativeGoalCause::TimeLimit => "Time limit reached",
        NativeGoalCause::NoProgress => "No progress was made",
        NativeGoalCause::RuntimeFailure => "Stopped by a runtime failure",
        NativeGoalCause::Restart => "Restarted with the host",
        NativeGoalCause::Blocked => "Blocked",
        NativeGoalCause::ReviewUnavailable => "Review was unavailable",
        NativeGoalCause::Cleared => "Cleared",
    }
}

pub fn goal_phase_label(phase: NativeGoalPhase) -> &'static str {
    match phase {
        NativeGoalPhase::Active => "Active",
        NativeGoalPhase::Paused => "Paused",
        NativeGoalPhase::Blocked => "Blocked",
        NativeGoalPhase::Complete => "Complete",
        NativeGoalPhase::Cleared => "Cleared",
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GoalAction {
    Edit,
    Pause,
    Resume,
    Clear,
}

impl GoalAction {
    pub fn label(self) -> &'static str {
        match self {
            Self::Edit => "Edit",
            Self::Pause => "Pause",
            Self::Resume => "Resume",
            Self::Clear => "Clear",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GoalView {
    pub phase_label: &'static str,
    pub cause_label: &'static str,
    pub tone: Tone,
    pub reason: Option<String>,
    pub review_gap: Option<String>,
    pub work_turns: u32,
    pub actions: Vec<GoalAction>,
}

pub fn goal_view(goal: &NativeGoal) -> GoalView {
    let actions = match goal.phase {
        NativeGoalPhase::Active => vec![GoalAction::Edit, GoalAction::Pause, GoalAction::Clear],
        NativeGoalPhase::Paused | NativeGoalPhase::Blocked => {
            vec![GoalAction::Edit, GoalAction::Resume, GoalAction::Clear]
        }
        NativeGoalPhase::Complete => vec![GoalAction::Clear],
        NativeGoalPhase::Cleared => Vec::new(),
    };
    GoalView {
        phase_label: goal_phase_label(goal.phase),
        cause_label: goal_cause_label(goal.cause),
        tone: match goal.phase {
            NativeGoalPhase::Blocked => Tone::Danger,
            NativeGoalPhase::Active => Tone::Active,
            _ => Tone::Neutral,
        },
        reason: goal.reason.clone(),
        review_gap: goal.review_gap.clone(),
        work_turns: goal.work_turns,
        actions,
    }
}

/// A goal that is gone (no goal, or cleared) can be started again.
pub fn goal_is_open(goal: Option<&NativeGoal>) -> bool {
    goal.is_some_and(|goal| goal.phase != NativeGoalPhase::Cleared)
}

// ---------------------------------------------------------------------------
// Plan
// ---------------------------------------------------------------------------

pub fn plan_status_label(status: NativePlanStatus) -> &'static str {
    match status {
        NativePlanStatus::ReviewPending => "Awaiting your review",
        NativePlanStatus::SavedStopped => "Saved, stopped",
        NativePlanStatus::Accepted => "Accepted",
        NativePlanStatus::Implementing => "Implementing",
        NativePlanStatus::Completed => "Completed",
        NativePlanStatus::Abandoned => "Abandoned",
    }
}

// ---------------------------------------------------------------------------
// Questions and approvals
// ---------------------------------------------------------------------------

/// A question's in-progress answer, kept as the user typed it.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct QuestionDraft {
    pub selected: Vec<String>,
    pub freeform: String,
    pub none_of_above: bool,
}

impl QuestionDraft {
    pub fn toggle_option(&mut self, question: &NativeQuestion, label: &str) {
        let has = self.selected.iter().any(|entry| entry == label);
        if question.allow_multiple {
            if has {
                self.selected.retain(|entry| entry != label);
            } else {
                self.selected.push(label.to_owned());
            }
        } else {
            self.selected = if has {
                Vec::new()
            } else {
                vec![label.to_owned()]
            };
        }
        self.none_of_above = false;
    }

    pub fn toggle_none_of_above(&mut self) {
        self.selected.clear();
        self.none_of_above = !self.none_of_above;
    }

    pub fn answered(&self) -> bool {
        !self.selected.is_empty() || self.none_of_above || !self.freeform.trim().is_empty()
    }
}

/// One `NativeAnswer` per question, in order; `None` while any question is unanswered.
pub fn build_answers(
    questions: &[NativeQuestion],
    drafts: &std::collections::HashMap<String, QuestionDraft>,
) -> Option<Vec<NativeAnswer>> {
    let empty = QuestionDraft::default();
    questions
        .iter()
        .map(|question| {
            let draft = drafts.get(&question.id).unwrap_or(&empty);
            if !draft.answered() {
                return None;
            }
            let freeform = draft.freeform.trim();
            Some(NativeAnswer {
                question_id: question.id.clone(),
                selected_options: draft.selected.clone(),
                freeform_text: (!freeform.is_empty()).then(|| freeform.to_owned()),
                none_of_above: draft.none_of_above,
            })
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Submissions (message delivery)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeliveryView {
    pub message_id: String,
    pub label: String,
    pub detail: Option<String>,
    pub tone: Tone,
    /// `RetrySubmission` is offered only when the host flagged it replay-safe.
    pub retryable: bool,
}

fn submission_noun(kind: NativeSubmissionKind) -> &'static str {
    match kind {
        NativeSubmissionKind::Prompt => "Message",
        NativeSubmissionKind::Command => "Command",
        NativeSubmissionKind::Steer => "Guidance",
    }
}

pub fn delivery_view(submission: &NativeSubmission) -> DeliveryView {
    let noun = submission_noun(submission.kind);
    let (label, detail, tone) = match &submission.delivery {
        NativeDelivery::Submitting => (format!("{noun} sending to Mimir"), None, Tone::Neutral),
        NativeDelivery::Admitted { request_id } => (
            format!("{noun} accepted by Mimir"),
            Some(format!("Request {request_id}")),
            Tone::Neutral,
        ),
        NativeDelivery::Steered { request_id } => (
            format!("{noun} added to the running request"),
            Some(format!("Request {request_id}")),
            Tone::Neutral,
        ),
        NativeDelivery::Handled => (format!("{noun} handled by Mimir"), None, Tone::Neutral),
        NativeDelivery::Unknown { message } => (
            format!("{noun} delivery is unknown"),
            Some(format!("{message} It was not retried automatically.")),
            Tone::Warn,
        ),
        NativeDelivery::Refused { message } => (
            format!("{noun} refused by Mimir"),
            Some(message.clone()),
            Tone::Danger,
        ),
    };
    DeliveryView {
        message_id: submission.message_id.clone(),
        label,
        detail,
        tone,
        retryable: submission.retryable,
    }
}

/// Only submissions that need attention stay in the dock; settled ones are in the transcript.
pub fn attention_deliveries(state: &NativeChatState) -> Vec<DeliveryView> {
    state
        .submissions
        .iter()
        .filter(|submission| {
            matches!(
                submission.delivery,
                NativeDelivery::Submitting
                    | NativeDelivery::Unknown { .. }
                    | NativeDelivery::Refused { .. }
            )
        })
        .map(delivery_view)
        .collect()
}

// ---------------------------------------------------------------------------
// Readiness
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReadinessView {
    pub ready: bool,
    pub title: String,
    /// The corrective step to run by hand; Roboco never installs anything.
    pub action: Option<String>,
    pub detail: Option<String>,
}

pub fn readiness_view(readiness: &NativeReadiness) -> ReadinessView {
    let not_ready = |title: &str, action: Option<&String>, detail: Option<&String>| ReadinessView {
        ready: false,
        title: title.to_owned(),
        action: action.cloned(),
        detail: detail.cloned(),
    };
    match readiness {
        NativeReadiness::Ready {
            executable,
            version,
            bridge,
        } => ReadinessView {
            ready: true,
            title: match version {
                Some(version) => format!("Mimir {version} is ready"),
                None => "Mimir is ready".to_owned(),
            },
            action: None,
            detail: Some(format!("{executable} with bridge {bridge}")),
        },
        NativeReadiness::MissingExecutable { action } => {
            not_ready("Mimir is not installed", Some(action), None)
        }
        NativeReadiness::PluginMissing { message, action } => not_ready(
            "The Roboco plugin is not installed in Mimir",
            Some(action),
            Some(message),
        ),
        NativeReadiness::Incompatible { message, action } => not_ready(
            "This Mimir version is not compatible",
            Some(action),
            Some(message),
        ),
        NativeReadiness::NoModels { action } => {
            not_ready("Mimir has no connected models", Some(action), None)
        }
        NativeReadiness::Failed { message } => {
            not_ready("Could not check Mimir", None, Some(message))
        }
    }
}

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InvocationKind {
    Command,
    Skill,
}

/// One slash or skill row from the host's own catalog. Identities are the
/// host's: nothing is filtered by a client allowlist, and terminal-only
/// commands are already absent from the host's catalog.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InvocationRow {
    pub kind: InvocationKind,
    pub name: String,
    pub description: String,
    pub argument: Option<String>,
    /// A skill's exact source path, or the `harness-skill:` identity the engine
    /// maps back to a native skill.
    pub path: Option<String>,
    pub idle_only: bool,
}

pub fn invocation_rows(catalog: &NativeChatCatalog) -> Vec<InvocationRow> {
    let commands = catalog.commands.iter().map(|command| InvocationRow {
        kind: InvocationKind::Command,
        name: command.name.clone(),
        description: command.description.clone(),
        argument: command.argument.clone(),
        path: None,
        idle_only: command.idle_only,
    });
    let skills = catalog.skills.iter().map(|skill| InvocationRow {
        kind: InvocationKind::Skill,
        name: skill.name.clone(),
        description: skill.description.clone(),
        argument: None,
        path: Some(
            skill
                .path
                .clone()
                .unwrap_or_else(|| format!("harness-skill:{}", skill.name)),
        ),
        idle_only: false,
    });
    commands.chain(skills).collect()
}

/// An `idleOnly` command is shown but not selectable while the conversation has active work.
pub fn row_unavailable(row: &InvocationRow, working: bool) -> Option<&'static str> {
    (working && row.idle_only).then_some(IDLE_ONLY_COPY)
}

// ---------------------------------------------------------------------------
// Children
// ---------------------------------------------------------------------------

pub fn child_status_label(status: NativeChildStatus) -> &'static str {
    match status {
        NativeChildStatus::Queued => "Queued",
        NativeChildStatus::Running => "Running",
        NativeChildStatus::Completed => "Completed",
        NativeChildStatus::Failed => "Failed",
        NativeChildStatus::Deadline => "Hit its deadline",
        NativeChildStatus::Stopped => "Stopped",
        NativeChildStatus::Interrupted => "Interrupted",
    }
}

pub fn child_title(child: &NativeChild) -> &str {
    if child.description.is_empty() {
        &child.profile
    } else {
        &child.description
    }
}

/// The engine projects child doc ids raw; a tab opened from a spawn chip holds
/// the engine-scoped form. Both name the same doc.
fn same_doc(projected: &str, doc_id: &str) -> bool {
    crate::engine_registry::ScopedId::parse(doc_id).is_ok_and(|id| id.raw_id == projected)
}

pub fn child_by_doc<'a>(
    state: Option<&'a NativeChatState>,
    doc_id: &str,
) -> Option<&'a NativeChild> {
    state?
        .children
        .iter()
        .find(|child| same_doc(&child.doc_id, doc_id))
}

/// A child doc id the owning chat's engine resolves: the projected raw id,
/// scoped to the chat's engine, so a remote chat's child never opens on the
/// local engine.
pub fn child_doc_for(chat_id: &str, raw_doc_id: &str) -> String {
    match crate::engine_registry::ScopedId::parse(chat_id) {
        Ok(chat) => crate::engine_registry::ScopedId::encode(&chat.engine, raw_doc_id),
        Err(_) => raw_doc_id.to_owned(),
    }
}

/// When the projected children need the canonical inventory: a header-only
/// child carries no task, attempts or presentation.
pub fn needs_inventory(children: &[NativeChild]) -> bool {
    children.iter().any(|child| child.oversized)
}

/// What the inventory read is for. A new value means the read may be stale.
pub fn inventory_key(children: &[NativeChild]) -> String {
    children
        .iter()
        .filter(|child| child.oversized)
        .map(|child| format!("{}#{}#{:?}", child.handle, child.attempt, child.status))
        .collect::<Vec<_>>()
        .join(",")
}

/// The projected children with each header-only one replaced by its canonical
/// inventory entry: the same handle and doc. An entry the inventory lacks stays
/// as its header.
pub fn hydrate_children(projected: &[NativeChild], inventory: &[NativeChild]) -> Vec<NativeChild> {
    projected
        .iter()
        .map(|child| {
            if !child.oversized {
                return child.clone();
            }
            inventory
                .iter()
                .find(|full| full.handle == child.handle && full.doc_id == child.doc_id)
                .cloned()
                .unwrap_or_else(|| child.clone())
        })
        .collect()
}

// ---------------------------------------------------------------------------
// Command receipts
// ---------------------------------------------------------------------------

/// What the host ledger said about one control, read back with `GetCommand`.
/// `Unconfirmed` means this client stopped reading while the host had not
/// settled the command. It is not a failure: the command stays in the ledger
/// and is never resent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ControlVerdict {
    Applied,
    Configured {
        label: String,
    },
    Admitted,
    Display {
        text: String,
    },
    Cancelled {
        newly: bool,
    },
    Released,
    ChildAccepted,
    ChildMoved {
        attempt: u32,
    },
    ChildEnded {
        finalizing: bool,
    },
    Refused {
        message: String,
    },
    Unknown {
        message: String,
    },
    NotRun {
        status: SessionCommandStatus,
        resolution: Option<String>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ControlReceipt {
    Sending,
    NotQueued {
        message: String,
    },
    Pending {
        command_id: String,
    },
    Unconfirmed {
        command_id: String,
        reason: String,
    },
    Settled {
        command_id: String,
        verdict: ControlVerdict,
    },
}

fn verdict_from_outcome(outcome: &NativeControlOutcome) -> ControlVerdict {
    match outcome {
        NativeControlOutcome::Applied => ControlVerdict::Applied,
        NativeControlOutcome::Configured { configuration } => ControlVerdict::Configured {
            label: configuration_label(configuration),
        },
        NativeControlOutcome::Admitted { .. } => ControlVerdict::Admitted,
        NativeControlOutcome::Display { text } => ControlVerdict::Display { text: text.clone() },
        NativeControlOutcome::Child { control } => match control {
            NativeChildControl::Accepted => ControlVerdict::ChildAccepted,
            NativeChildControl::AttemptChanged { attempt } => {
                ControlVerdict::ChildMoved { attempt: *attempt }
            }
            NativeChildControl::Terminal => ControlVerdict::ChildEnded { finalizing: false },
            NativeChildControl::Finalizing => ControlVerdict::ChildEnded { finalizing: true },
        },
        NativeControlOutcome::Cancelled { newly } => ControlVerdict::Cancelled { newly: *newly },
        NativeControlOutcome::Released => ControlVerdict::Released,
        NativeControlOutcome::Refused { message, .. } => ControlVerdict::Refused {
            message: message.clone(),
        },
        NativeControlOutcome::Unknown { message } => ControlVerdict::Unknown {
            message: message.clone(),
        },
    }
}

/// The receipt for a ledger entry. A typed host outcome always wins over the queue status.
pub fn receipt_from_command(entry: &SessionCommandEntry) -> ControlReceipt {
    let command_id = entry.id.clone();
    if entry.status == SessionCommandStatus::Pending {
        return ControlReceipt::Pending { command_id };
    }
    let verdict = match (&entry.outcome, entry.status) {
        (Some(outcome), _) => verdict_from_outcome(outcome),
        (None, SessionCommandStatus::Unknown) => ControlVerdict::Unknown {
            message: entry
                .resolution
                .clone()
                .unwrap_or_else(|| "The host's answer was lost.".to_owned()),
        },
        (None, status) => ControlVerdict::NotRun {
            status,
            resolution: entry.resolution.clone(),
        },
    };
    ControlReceipt::Settled {
        command_id,
        verdict,
    }
}

fn not_run_phrase(status: SessionCommandStatus) -> &'static str {
    match status {
        SessionCommandStatus::Pending => "is still waiting",
        SessionCommandStatus::Applied => "was applied without host detail",
        SessionCommandStatus::Rejected => "was rejected",
        SessionCommandStatus::Expired => "expired before it ran",
        SessionCommandStatus::Superseded => "was superseded by a newer command",
        SessionCommandStatus::Cancelled => "was cancelled",
        SessionCommandStatus::Unknown => "has an unknown result",
    }
}

pub fn verdict_text(verdict: &ControlVerdict) -> String {
    match verdict {
        ControlVerdict::Applied => "Mimir accepted it.".to_owned(),
        ControlVerdict::Configured { label } => format!("Configured: {label}"),
        ControlVerdict::Admitted => "Mimir started the request.".to_owned(),
        ControlVerdict::Display { text } => text.clone(),
        ControlVerdict::Cancelled { newly: true } => {
            "Stop sent. Mimir is cancelling the request.".to_owned()
        }
        ControlVerdict::Cancelled { newly: false } => "That request was already stopping.".to_owned(),
        ControlVerdict::Released => "Released. Continue the conversation in Mimir.".to_owned(),
        ControlVerdict::ChildAccepted => "Mimir accepted it for the agent.".to_owned(),
        ControlVerdict::ChildMoved { attempt } => {
            format!("That agent moved on to attempt {attempt}. Nothing was sent to it.")
        }
        ControlVerdict::ChildEnded { finalizing: false } => {
            "That attempt has already ended. Nothing was sent.".to_owned()
        }
        ControlVerdict::ChildEnded { finalizing: true } => {
            "That agent is finishing and cannot take this now. Nothing was sent.".to_owned()
        }
        ControlVerdict::Refused { message } => format!("Mimir refused it. {message}"),
        ControlVerdict::Unknown { message } => format!(
            "Mimir's answer was lost, so it may or may not have taken effect. Check the conversation state. Nothing was resent. {message}"
        )
        .trim()
        .to_owned(),
        ControlVerdict::NotRun { status, resolution } => format!(
            "The engine did not run it. It {}{}",
            not_run_phrase(*status),
            match resolution {
                Some(resolution) => format!(". {resolution}"),
                None => ".".to_owned(),
            }
        ),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReceiptTone {
    Progress,
    Ok,
    Warn,
    Danger,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReceiptView {
    pub text: String,
    pub tone: ReceiptTone,
    /// A receipt that needs the user stays until they act again.
    pub attention: bool,
}

pub fn receipt_view(receipt: &ControlReceipt) -> ReceiptView {
    let (text, tone, attention) = match receipt {
        ControlReceipt::Sending => ("Sending to Mimir".to_owned(), ReceiptTone::Progress, false),
        ControlReceipt::NotQueued { message } => (
            format!("Could not send: {message}"),
            ReceiptTone::Danger,
            true,
        ),
        ControlReceipt::Pending { .. } => (
            "Queued. Waiting for Mimir to settle it.".to_owned(),
            ReceiptTone::Progress,
            false,
        ),
        ControlReceipt::Unconfirmed { command_id, reason } => (
            format!(
                "Mimir has not confirmed this yet ({reason}). It stays queued as {command_id}. Nothing was resent."
            ),
            ReceiptTone::Warn,
            true,
        ),
        ControlReceipt::Settled { verdict, .. } => {
            let (tone, attention) = match verdict {
                ControlVerdict::Refused { .. } | ControlVerdict::NotRun { .. } => {
                    (ReceiptTone::Danger, true)
                }
                ControlVerdict::Unknown { .. }
                | ControlVerdict::ChildMoved { .. }
                | ControlVerdict::ChildEnded { .. } => (ReceiptTone::Warn, true),
                ControlVerdict::Applied
                | ControlVerdict::Configured { .. }
                | ControlVerdict::Admitted
                | ControlVerdict::Display { .. }
                | ControlVerdict::Cancelled { .. }
                | ControlVerdict::Released
                | ControlVerdict::ChildAccepted => (ReceiptTone::Ok, false),
            };
            (verdict_text(verdict), tone, attention)
        }
    };
    ReceiptView {
        text,
        tone,
        attention,
    }
}

/// Still travelling or waiting on the host: a second click would double-send.
pub fn receipt_in_flight(receipt: Option<&ControlReceipt>) -> bool {
    matches!(
        receipt,
        Some(ControlReceipt::Sending | ControlReceipt::Pending { .. })
    )
}

/// In flight, or settled without needing attention: the control has done its one job.
pub fn receipt_holds_control(receipt: Option<&ControlReceipt>) -> bool {
    receipt_in_flight(receipt)
        || matches!(receipt, Some(settled @ ControlReceipt::Settled { .. }) if !receipt_view(settled).attention)
}

/// Gaps before each ledger read after a control is queued. The schedule is the
/// whole budget, about 15 seconds. Nothing polls after it.
pub const RECEIPT_DELAYS_MS: [u64; 11] = [
    150, 300, 600, 1000, 1500, 2000, 2000, 2000, 2000, 2000, 2000,
];

type Report<'a> = &'a mut dyn FnMut(&ControlReceipt) -> bool;

/// Queue one control, then read its ledger entry on a bounded schedule. The
/// control is queued exactly once. A read that fails or runs out of time ends
/// as `Unconfirmed` with the command id kept. `report` returns false when the
/// owner has gone away, which stops the work.
pub async fn run_control<Q, QF, R, RF, P, PF>(
    queue: Q,
    mut read: R,
    mut pause: P,
    report: Report<'_>,
) -> ControlReceipt
where
    Q: FnOnce() -> QF,
    QF: std::future::Future<Output = Result<String, String>>,
    R: FnMut(String) -> RF,
    RF: std::future::Future<Output = Result<Option<SessionCommandEntry>, String>>,
    P: FnMut(std::time::Duration) -> PF,
    PF: std::future::Future<Output = ()>,
{
    let sending = ControlReceipt::Sending;
    if !report(&sending) {
        return sending;
    }
    let command_id = match queue().await {
        Ok(id) => id,
        Err(message) => {
            let failed = ControlReceipt::NotQueued { message };
            report(&failed);
            return failed;
        }
    };
    let pending = ControlReceipt::Pending {
        command_id: command_id.clone(),
    };
    if !report(&pending) {
        return pending;
    }
    let waiting = "the host has not settled it yet";
    let mut reason = waiting.to_owned();
    for delay in RECEIPT_DELAYS_MS {
        pause(std::time::Duration::from_millis(delay)).await;
        match read(command_id.clone()).await {
            Ok(Some(entry)) => {
                let receipt = receipt_from_command(&entry);
                if !matches!(receipt, ControlReceipt::Pending { .. }) {
                    report(&receipt);
                    return receipt;
                }
                reason = waiting.to_owned();
            }
            Ok(None) => {
                let lost = ControlReceipt::Unconfirmed {
                    command_id,
                    reason: "the engine has no record of it".to_owned(),
                };
                report(&lost);
                return lost;
            }
            Err(message) => reason = format!("the receipt could not be read: {message}"),
        }
    }
    let unconfirmed = ControlReceipt::Unconfirmed { command_id, reason };
    report(&unconfirmed);
    unconfirmed
}

/// The last child control the user sent, bound to the attempt it named.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChildControlSent {
    pub stop: bool,
    pub attempt: u32,
    pub receipt: ControlReceipt,
}

/// "Guidance queued" and "Stopping" appear only after the host's own
/// `Child Accepted`, never from the transport enqueue. A control that raced an
/// attempt change, ended, or was refused says so and is never retried on a
/// newer attempt.
pub fn child_control_label(
    sent: Option<&ChildControlSent>,
    child: &NativeChild,
) -> Option<ReceiptView> {
    let sent = sent?;
    let noun = if sent.stop { "stop" } else { "guidance" };
    if !matches!(
        &sent.receipt,
        ControlReceipt::Settled {
            verdict: ControlVerdict::ChildAccepted,
            ..
        }
    ) {
        let mut view = receipt_view(&sent.receipt);
        if matches!(sent.receipt, ControlReceipt::Pending { .. }) {
            view.text = format!("Waiting for Mimir to accept the {noun}.");
        }
        return Some(view);
    }
    if sent.attempt != child.attempt {
        return Some(ReceiptView {
            text: format!(
                "Attempt {} has ended. Your {noun} was not sent to attempt {}.",
                sent.attempt, child.attempt
            ),
            tone: ReceiptTone::Warn,
            attention: true,
        });
    }
    if child.status.is_terminal() {
        return None;
    }
    Some(ReceiptView {
        text: if sent.stop {
            "Stopping"
        } else {
            "Guidance queued"
        }
        .to_owned(),
        tone: ReceiptTone::Ok,
        attention: false,
    })
}

pub fn usage_line(usage: &NativeUsage) -> String {
    let mut parts = vec![
        format!("{} tokens", thousands(usage.total_tokens)),
        format!("{} in", thousands(usage.input_tokens)),
        format!("{} out", thousands(usage.output_tokens)),
    ];
    for (count, what) in [
        (usage.reasoning_tokens, "reasoning"),
        (usage.cache_read_tokens, "cache read"),
        (usage.cache_write_tokens, "cache write"),
    ] {
        if count > 0 {
            parts.push(format!("{} {what}", thousands(count)));
        }
    }
    parts.join(", ")
}

pub fn thousands(value: u64) -> String {
    let digits = value.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (ix, ch) in digits.chars().enumerate() {
        if ix > 0 && (digits.len() - ix) % 3 == 0 {
            out.push(',');
        }
        out.push(ch);
    }
    out
}

pub fn format_bytes(bytes: u64) -> String {
    if bytes < 1024 {
        format!("{bytes} B")
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    }
}

// ---------------------------------------------------------------------------
// Notices
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NoticeView {
    pub title: String,
    pub body: Option<String>,
    pub tone: Tone,
    /// Blob ref of the full record, when the host kept one.
    pub detail_ref: Option<String>,
    /// The record is image bytes, saved rather than shown as text.
    pub binary_detail: bool,
    pub media_type: Option<String>,
}

fn token_count(value: Option<u64>) -> String {
    value.map_or_else(|| "unknown".to_owned(), thousands)
}

/// One honest row per host notice variant.
pub fn notice_view(notice: &NativeNotice) -> NoticeView {
    let plain = |title: String, body: Option<String>| NoticeView {
        title,
        body,
        tone: Tone::Neutral,
        detail_ref: None,
        binary_detail: false,
        media_type: None,
    };
    match notice {
        NativeNotice::PlanLifecycle { name, status, .. } => plain(
            format!("Plan \"{name}\""),
            Some(plan_status_label(*status).to_owned()),
        ),
        NativeNotice::Compaction {
            trigger,
            before_tokens,
            after_tokens,
        } => plain(
            "Context compacted".to_owned(),
            Some(format!(
                "{trigger}. {} tokens before, {} after.",
                token_count(*before_tokens),
                token_count(*after_tokens)
            )),
        ),
        NativeNotice::BranchSummary { summary } => {
            plain("Branch summary".to_owned(), Some(summary.clone()))
        }
        NativeNotice::PluginSnapshot {
            title,
            fallback,
            lines,
            ..
        } => plain(
            title.clone(),
            Some(plugin_snapshot_text(lines).unwrap_or_else(|| fallback.clone())),
        ),
        NativeNotice::SubagentCompletions { completions } => plain(
            if completions.len() == 1 {
                "Agent finished".to_owned()
            } else {
                format!("{} agents finished", completions.len())
            },
            Some(
                completions
                    .iter()
                    .map(completion_text)
                    .collect::<Vec<_>>()
                    .join("\n\n"),
            ),
        ),
        NativeNotice::CommandDisplay { text } => {
            plain("Command output".to_owned(), Some(text.clone()))
        }
        NativeNotice::Status { text } => plain("Status".to_owned(), Some(text.clone())),
        NativeNotice::Interrupted {
            message,
            detail_ref,
        } => NoticeView {
            title: "Interrupted".to_owned(),
            body: Some(format!(
                "{message} Output up to the interruption is provisional."
            )),
            tone: Tone::Warn,
            detail_ref: detail_ref.clone(),
            binary_detail: false,
            media_type: None,
        },
        NativeNotice::Image {
            media_type,
            detail_ref,
        } => NoticeView {
            title: "Image".to_owned(),
            body: Some(match media_type {
                Some(media_type) => format!("{media_type}. The image bytes are kept by the host."),
                None => "The image bytes are kept by the host.".to_owned(),
            }),
            tone: Tone::Neutral,
            detail_ref: detail_ref.clone(),
            binary_detail: true,
            media_type: media_type.clone(),
        },
        NativeNotice::Unsupported { item, detail_ref } => NoticeView {
            title: "Content Roboco cannot show".to_owned(),
            body: Some(format!(
                "Mimir sent a \"{item}\" item this version does not render."
            )),
            tone: Tone::Warn,
            detail_ref: detail_ref.clone(),
            binary_detail: false,
            media_type: None,
        },
    }
}

/// One delivered child run in full: identity, outcome, every changed file and the usage the host reported.
fn completion_text(completion: &roboco_proto::NativeChildCompletion) -> String {
    let attempt = completion
        .attempt
        .map(|attempt| format!(", attempt {attempt}"))
        .unwrap_or_default();
    let mut out = format!(
        "{} ({}{attempt}, {})",
        completion.description,
        completion.handle,
        child_status_label(completion.status)
    );
    if !completion.result_preview.is_empty() {
        out.push('\n');
        out.push_str(&completion.result_preview);
        if completion.result_truncated {
            out.push_str("\nThe host shortened this result.");
        }
    }
    if !completion.changed_files.is_empty() || completion.omitted_changed_files > 0 {
        out.push_str(&format!(
            "\nChanged files: {}",
            completion.changed_files.join(", ")
        ));
        if completion.omitted_changed_files > 0 {
            out.push_str(&format!(
                " and {} more the host did not list",
                completion.omitted_changed_files
            ));
        }
    }
    if let Some(usage) = &completion.usage {
        out.push_str(&format!("\n{}", usage_line(usage)));
    }
    out
}

/// A plugin snapshot's own lines when they are plain text; otherwise the host's fallback is used.
fn plugin_snapshot_text(lines: &Value) -> Option<String> {
    let lines = lines.as_array().filter(|lines| !lines.is_empty())?;
    let text: Option<Vec<&str>> = lines.iter().map(Value::as_str).collect();
    Some(text?.join("\n"))
}

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

/// A running call shows its running title; a settled one its title.
pub fn tool_title(view: &NativeToolView, resolved: bool) -> &str {
    match (&view.running_title, resolved) {
        (Some(running), false) => running,
        _ => &view.title,
    }
}

/// The host's compact result state, said on the chip instead of an empty body.
pub fn result_state_label(state: NativeToolResultState) -> Option<&'static str> {
    match state {
        NativeToolResultState::Normal => None,
        NativeToolResultState::Empty => Some("No output"),
        NativeToolResultState::NoMatches => Some("No matches"),
    }
}

/// The chip's title: the running title while the call runs, then the title
/// with the host's result state once it settled.
pub fn chip_title(view: &NativeToolView, resolved: bool) -> String {
    let title = tool_title(view, resolved);
    match result_state_label(view.result_state).filter(|_| resolved) {
        Some(state) => format!("{title} · {state}"),
        None => title.to_owned(),
    }
}

/// How the chip's inline body shows the part's result preview, per the host's declaration.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InlineResult {
    /// No inline result: the host declared a compact preview, or an empty or
    /// no-match result the chip title already states.
    Hidden,
    /// The result is public Markdown (an answer or a report).
    Markdown,
    /// The result as verbatim text.
    Text,
}

pub fn inline_result(view: &NativeToolView) -> InlineResult {
    if view.result_state != NativeToolResultState::Normal {
        return InlineResult::Hidden;
    }
    if view.semantic.is_some() {
        return InlineResult::Markdown;
    }
    match view.preview {
        NativeToolPreview::Full => InlineResult::Text,
        NativeToolPreview::Compact => InlineResult::Hidden,
    }
}

/// What a fetched detail is current for. A call's detail record is rewritten
/// while it runs and once more when it settles, so a read made earlier is
/// stale when this changes.
pub fn detail_version(view: &NativeToolView, resolved: bool, is_error: bool) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for byte in [u8::from(resolved), u8::from(is_error)]
        .into_iter()
        .chain(view.duration_ms.unwrap_or(u64::MAX).to_le_bytes())
    {
        hash = (hash ^ u64::from(byte)).wrapping_mul(0x0100_0000_01b3);
    }
    hash
}

/// A quiet call that succeeded is folded away; a failure is always visible.
pub fn is_quiet_success(view: &NativeToolView, is_error: bool) -> bool {
    view.quiet && !is_error
}

/// Adjacent calls sharing a group key render as one row ("<label> · <item>, <item>").
/// `None` when they do not all share a key.
pub fn group_label(views: &[&NativeToolView]) -> Option<String> {
    let first = views.first()?.group.as_ref()?;
    if !views.iter().all(|view| {
        view.group
            .as_ref()
            .is_some_and(|group| group.key == first.key)
    }) {
        return None;
    }
    let items: Vec<&str> = views
        .iter()
        .filter_map(|view| view.group.as_ref())
        .map(|group| group.item.as_str())
        .collect();
    Some(format!("{} · {}", first.label, items.join(", ")))
}

pub fn pretty(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        other => serde_json::to_string_pretty(other).unwrap_or_default(),
    }
}

/// Progress is JSON lines, one JSON string per line; a malformed line is shown as written.
pub fn parse_progress_lines(text: &str) -> Vec<String> {
    text.lines()
        .filter(|line| !line.is_empty())
        .map(|line| match serde_json::from_str::<Value>(line) {
            Ok(Value::String(text)) => text,
            _ => line.to_owned(),
        })
        .collect()
}

/// Refs of a series' numbered chunks, in read order.
pub fn series_refs(series: &roboco_proto::NativeBlobSeries) -> Vec<String> {
    (0..series.chunks)
        .map(|index| format!("{}.{index:06}", series.blob_ref))
        .collect()
}

/// One bounded read window's worth of a blob series, accumulated as bytes and
/// decoded once, so a character cut by a window edge is whole in the result.
#[derive(Debug, Default)]
pub struct BlobBytes {
    bytes: Vec<u8>,
}

impl BlobBytes {
    pub fn push(&mut self, window: &ToolBlobWindow) -> Result<usize, String> {
        let before = self.bytes.len();
        match window.encoding {
            ToolBlobEncoding::Utf8 => self.bytes.extend_from_slice(window.text.as_bytes()),
            ToolBlobEncoding::Base64 => {
                use base64::Engine as _;
                let decoded = base64::engine::general_purpose::STANDARD
                    .decode(window.text.as_bytes())
                    .map_err(|err| format!("The blob window was not valid base64: {err}"))?;
                self.bytes.extend_from_slice(&decoded);
            }
        }
        Ok(self.bytes.len() - before)
    }

    /// Whole bytes already decoded, such as a finished chunk joined into a series.
    pub fn push_raw(&mut self, bytes: &[u8]) {
        self.bytes.extend_from_slice(bytes);
    }

    pub fn len(&self) -> usize {
        self.bytes.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn into_text(self) -> String {
        String::from_utf8_lossy(&self.bytes).into_owned()
    }
}

/// One titled part of a tool detail view. `markdown` bodies are the host's
/// public Markdown (an answer or a report) and render as such.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DetailSection {
    pub title: String,
    pub body: String,
    pub markdown: bool,
}

fn section(title: impl Into<String>, body: impl Into<String>) -> DetailSection {
    DetailSection {
        title: title.into(),
        body: body.into(),
        markdown: false,
    }
}

/// The full tool detail, section by section: every public field the host
/// sent, raw input and the parsed input both, each compaction and the output
/// profile as the host's own JSON. The engine already withheld model-only
/// context, and a non-empty public list is never replaced by a fallback.
pub fn detail_sections(
    detail: &NativeToolDetail,
    progress: Option<&str>,
    stream: Option<&str>,
) -> Vec<DetailSection> {
    let view = &detail.view;
    let mut out = Vec::new();
    let status = match detail.is_error {
        Some(true) => "Failed",
        Some(false) => "Succeeded",
        None => "No result yet",
    };
    let mut call = match view.duration_ms {
        Some(ms) => format!("{status}, {:.1} s · {}", ms as f64 / 1000.0, detail.name),
        None => format!("{status} · {}", detail.name),
    };
    if let Some(state) = result_state_label(view.result_state) {
        call.push_str(&format!(" · {state}"));
    }
    out.push(section("Call", call));
    if !view.locations.is_empty() {
        let locations: Vec<String> = view
            .locations
            .iter()
            .map(|location| match location.line {
                Some(line) => format!("{}:{line}", location.path),
                None => location.path.clone(),
            })
            .collect();
        out.push(section("Locations", locations.join("\n")));
    }
    if let Some(input) = detail.input.as_ref().filter(|input| !input.is_null()) {
        out.push(section("Input", pretty(input)));
    }
    if let Some(raw) = &detail.raw_input {
        out.push(section("Raw input, as the host received it", raw.clone()));
    }
    match detail.output.as_deref() {
        Some(output) if !output.is_empty() => out.push(DetailSection {
            title: "Result".to_owned(),
            body: output.to_owned(),
            markdown: view.semantic.is_some(),
        }),
        _ if !detail.display_content.is_empty() => out.push(section(
            "Result",
            format!(
                "The host's public content for this call has no text. Its {} content blocks are listed below.",
                detail.display_content.len()
            ),
        )),
        _ => {}
    }
    if !detail.display_content.is_empty() {
        out.push(section(
            format!("Public content blocks ({})", detail.display_content.len()),
            pretty(&Value::Array(detail.display_content.clone())),
        ));
    }
    if let Some(details) = detail.details.as_ref().filter(|value| !value.is_null()) {
        out.push(section("Host details", pretty(details)));
    }
    if let Some(profile) = detail
        .output_profile
        .as_ref()
        .filter(|value| !value.is_null())
    {
        out.push(section("Output profile", pretty(profile)));
    }
    if !detail.compactions.is_empty() {
        out.push(section(
            format!(
                "Compactions ({}). The original bytes may be unavailable.",
                detail.compactions.len()
            ),
            pretty(&Value::Array(detail.compactions.clone())),
        ));
    }
    if let (Some(series), Some(text)) = (&detail.progress, progress) {
        out.push(section(
            format!(
                "Progress ({} lines, {})",
                series.records,
                format_bytes(series.bytes)
            ),
            parse_progress_lines(text).join("\n"),
        ));
    }
    if let (Some(series), Some(text)) = (&detail.stream, stream) {
        out.push(section(
            format!(
                "Streamed output ({} deltas, {})",
                series.records,
                format_bytes(series.bytes)
            ),
            text,
        ));
    }
    out
}

pub fn sections_text(sections: &[DetailSection]) -> String {
    sections
        .iter()
        .map(|section| format!("{}\n{}", section.title, section.body))
        .collect::<Vec<_>>()
        .join("\n\n")
}

/// One engine record in a complete export, copied byte for byte.
pub struct ExportPart<'a> {
    pub label: String,
    pub bytes: &'a [u8],
}

/// A complete export: every record whole and separate, each under a header
/// that names it and its exact byte count. Nothing is parsed, re-encoded or cut.
pub fn compose_export(title: &str, parts: &[ExportPart<'_>]) -> Vec<u8> {
    let mut out = format!(
        "{title}\nEach section is one engine record, copied byte for byte. Its header gives the exact byte count.\n"
    )
    .into_bytes();
    for part in parts {
        out.extend_from_slice(
            format!("\n=== {} ({} bytes) ===\n", part.label, part.bytes.len()).as_bytes(),
        );
        out.extend_from_slice(part.bytes);
        out.extend_from_slice(b"\n=== end ===\n");
    }
    out
}

// ---------------------------------------------------------------------------
// Control transport
// ---------------------------------------------------------------------------

/// The `QueueCommand` params for a typed native control.
pub fn control_params(chat_id: &str, control: &NativeControl) -> Value {
    serde_json::json!({
        "chatId": chat_id,
        "command": { "kind": "native", "control": control },
        "transfers": [],
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use roboco_proto::{
        NativeBlobSeries, NativeChildAttempt, NativeCommand, NativeGoalCompletion,
        NativeModelChoice, NativeProviderChoice, NativeQuestionOption, NativeSkill,
        NativeToolGroup, NativeToolKind, NativeToolPreview, NativeToolResultState,
    };

    fn child(attempt: u32, status: NativeChildStatus) -> NativeChild {
        NativeChild {
            handle: "agent-1".into(),
            attempt,
            profile: "general-purpose".into(),
            description: "probe".into(),
            model: None,
            status,
            background: true,
            spawned_by: None,
            completion_pending: false,
            presentation: None,
            doc_id: "chat--sub--agent-1".into(),
            oversized: false,
            attempts: Vec::<NativeChildAttempt>::new(),
        }
    }

    fn goal(phase: NativeGoalPhase) -> NativeGoal {
        NativeGoal {
            id: "g".into(),
            objective: "ship".into(),
            phase,
            cause: NativeGoalCause::ReviewGap,
            reason: Some("tests are red".into()),
            review_gap: Some("no migration test".into()),
            work_turns: 3,
            started_at: "t".into(),
            completion: None::<NativeGoalCompletion>,
        }
    }

    fn view(name: &str) -> NativeToolView {
        NativeToolView {
            name: name.into(),
            tool_call_id: "c1".into(),
            invocation_id: None,
            title: "Read a.rs".into(),
            running_title: Some("Reading a.rs".into()),
            kind: NativeToolKind::FileRead,
            summary: None,
            locations: Vec::new(),
            result_state: NativeToolResultState::Normal,
            preview: NativeToolPreview::Full,
            semantic: None,
            quiet: false,
            group: None,
            subagent: None,
            progress: None,
            duration_ms: None,
            detail_ref: None,
            detail_bytes: None,
            child: None,
        }
    }

    #[test]
    fn release_mirrors_the_engine_busy_test() {
        let mut state = NativeChatState::default();
        assert_eq!(release_blocker(&state), None);
        state.children.push(child(1, NativeChildStatus::Running));
        assert_eq!(release_blocker(&state), Some(RELEASE_BLOCKED_COPY));
        state.children[0].status = NativeChildStatus::Completed;
        assert_eq!(release_blocker(&state), None);
        state.user_request = Some(roboco_proto::NativeUserRequest {
            id: "r".into(),
            questions: Vec::new(),
        });
        assert_eq!(
            release_blocker(&state),
            Some(RELEASE_BLOCKED_COPY),
            "an open question blocks release even when idle"
        );
    }

    #[test]
    fn link_states_offer_the_exact_actions() {
        assert!(link_view(&NativeLink::Attached).show_release);
        assert!(!link_view(&NativeLink::Attached).can_reconnect);
        for link in [
            NativeLink::Detached,
            NativeLink::Released,
            NativeLink::Busy {
                message: "tui".into(),
            },
            NativeLink::Interrupted {
                message: "bridge".into(),
            },
            NativeLink::Unavailable {
                message: "gone".into(),
            },
        ] {
            let view = link_view(&link);
            assert!(view.can_reconnect, "{link:?} reconnects the same chat");
            assert!(!view.show_release);
        }
        assert_eq!(
            link_view(&NativeLink::Busy {
                message: "tui".into()
            })
            .detail
            .as_deref(),
            Some("tui")
        );
        let attaching = link_view(&NativeLink::Attaching);
        assert!(!attaching.can_reconnect && !attaching.show_release);
    }

    #[test]
    fn engine_offline_is_a_separate_axis_from_the_link() {
        assert!(engine_offline_copy(EngineConnectionState::Off).is_some());
        assert!(engine_offline_copy(EngineConnectionState::Reconnecting).is_some());
        assert_eq!(engine_offline_copy(EngineConnectionState::Connected), None);
    }

    #[test]
    fn configuration_label_names_mode_model_and_reasoning() {
        let configuration = NativeConfiguration {
            provider: Some("anthropic".into()),
            model: Some("claude-sonnet-5".into()),
            reasoning: Some("high".into()),
            mode: NativeMode::Plan,
        };
        assert_eq!(
            configuration_label(&configuration),
            "Plan · anthropic/claude-sonnet-5 · high"
        );
        let bare = NativeConfiguration {
            provider: None,
            model: None,
            reasoning: None,
            mode: NativeMode::Build,
        };
        assert_eq!(configuration_label(&bare), "Build · No model");
    }

    #[test]
    fn configuration_authority_separates_fresh_drafts_from_established_native_chats() {
        let configuration = NativeConfiguration {
            provider: Some("a".into()),
            model: Some("m".into()),
            reasoning: None,
            mode: NativeMode::Build,
        };
        let state = NativeChatState {
            configuration: Some(configuration.clone()),
            ..Default::default()
        };
        assert_eq!(
            config_authority(false, false, true, None),
            ConfigAuthority::Draft,
            "an ordinary chat keeps its picks"
        );
        assert_eq!(
            config_authority(true, true, false, None),
            ConfigAuthority::Draft,
            "a native chat this send creates sends the user's real choice"
        );
        assert_eq!(
            config_authority(true, false, false, None),
            ConfigAuthority::AwaitingHost,
            "an existing native chat opened a moment ago waits for its host state"
        );
        assert_eq!(
            config_authority(true, false, true, Some(&NativeChatState::default())),
            ConfigAuthority::Draft,
            "a native conversation the host never configured takes the user's choice"
        );
        assert_eq!(
            config_authority(true, false, true, Some(&state)),
            ConfigAuthority::Host(configuration.clone())
        );
        assert_eq!(
            config_slot_label(&ConfigAuthority::Host(configuration)).as_deref(),
            Some("Build · a/m")
        );
        assert_eq!(
            config_slot_label(&ConfigAuthority::AwaitingHost).as_deref(),
            Some("Waiting for Mimir")
        );
        assert_eq!(config_slot_label(&ConfigAuthority::Draft), None);
    }

    #[test]
    fn host_commands_go_now_unless_idle_only() {
        let idle_only = |name: &str| name == "compact";
        assert!(command_sends_now("/goal ship it", idle_only));
        assert!(!command_sends_now("/compact", idle_only));
        assert!(
            !command_sends_now("please read /etc/hosts", idle_only),
            "plain text waits in the queue"
        );
        assert!(!command_sends_now("    /goal indented code", idle_only));
    }

    #[test]
    fn oversized_children_take_the_canonical_inventory_entry_for_the_same_doc() {
        let mut header = child(2, NativeChildStatus::Running);
        header.oversized = true;
        header.description.clear();
        header.profile.clear();
        let mut full = child(2, NativeChildStatus::Running);
        full.attempts = vec![roboco_proto::NativeChildAttempt {
            attempt: 1,
            status: NativeChildStatus::Failed,
            presentation: None,
            outcome_ref: Some("chat/agent-1.1.outcome".into()),
        }];
        let mut other_doc = full.clone();
        other_doc.doc_id = "other--sub--agent-1".into();
        assert!(needs_inventory(std::slice::from_ref(&header)));
        assert_eq!(
            hydrate_children(std::slice::from_ref(&header), &[other_doc]),
            vec![header.clone()],
            "a same-handle entry for another doc is not this child"
        );
        let hydrated = hydrate_children(std::slice::from_ref(&header), std::slice::from_ref(&full));
        assert_eq!(hydrated, vec![full.clone()]);
        assert_eq!(child_title(&hydrated[0]), "probe");
        let small = child(1, NativeChildStatus::Running);
        assert_eq!(
            hydrate_children(std::slice::from_ref(&small), &[]),
            vec![small]
        );
        assert_ne!(
            inventory_key(std::slice::from_ref(&header)),
            inventory_key(&[{
                let mut moved = header.clone();
                moved.attempt = 3;
                moved
            }]),
            "a new attempt asks for a fresh read"
        );
    }

    #[test]
    fn projected_child_doc_ids_match_scoped_tab_ids_and_scope_to_the_chat_engine() {
        let state = NativeChatState {
            children: vec![child(1, NativeChildStatus::Running)],
            ..Default::default()
        };
        assert!(child_by_doc(Some(&state), "chat--sub--agent-1").is_some());
        let remote = crate::engine_registry::ScopedId::encode(
            &crate::engine_registry::EngineKey("remote".into()),
            "chat",
        );
        let doc = child_doc_for(&remote, "chat--sub--agent-1");
        assert_ne!(
            doc, "chat--sub--agent-1",
            "a remote chat's child is scoped to its engine"
        );
        assert!(child_by_doc(Some(&state), &doc).is_some());
        assert_eq!(
            child_doc_for("chat", "chat--sub--agent-1"),
            "chat--sub--agent-1"
        );
    }

    #[test]
    fn configure_names_only_what_changed() {
        let current = NativeConfiguration {
            provider: Some("a".into()),
            model: Some("m".into()),
            reasoning: Some("high".into()),
            mode: NativeMode::Build,
        };
        assert_eq!(
            configure_control(
                Some(&current),
                Some("b/n"),
                Some("high"),
                Some(NativeMode::Plan)
            ),
            NativeControl::Configure {
                provider: Some("b".into()),
                model: Some("n".into()),
                reasoning: None,
                mode: Some(NativeMode::Plan),
            }
        );
        assert_eq!(
            configure_control(Some(&current), None, Some("low"), Some(NativeMode::Build)),
            NativeControl::Configure {
                provider: None,
                model: None,
                reasoning: Some("low".into()),
                mode: None,
            }
        );
    }

    #[test]
    fn reasoning_levels_come_from_the_host_catalog() {
        let catalog = NativeChatCatalog {
            commands: Vec::new(),
            skills: Vec::new(),
            providers: vec![NativeProviderChoice {
                id: "a".into(),
                name: "A".into(),
                models: vec![NativeModelChoice {
                    id: "m".into(),
                    name: "M".into(),
                    reasoning: vec!["low".into(), "high".into()],
                }],
            }],
        };
        let configuration = NativeConfiguration {
            provider: Some("a".into()),
            model: Some("m".into()),
            reasoning: None,
            mode: NativeMode::Build,
        };
        assert_eq!(
            reasoning_levels(Some(&catalog), Some(&configuration)),
            vec!["low".to_owned(), "high".to_owned()]
        );
        assert!(reasoning_levels(None, Some(&configuration)).is_empty());
    }

    #[test]
    fn goal_actions_follow_the_phase() {
        let actions = |phase| goal_view(&goal(phase)).actions;
        assert_eq!(
            actions(NativeGoalPhase::Active),
            vec![GoalAction::Edit, GoalAction::Pause, GoalAction::Clear]
        );
        assert_eq!(
            actions(NativeGoalPhase::Paused),
            vec![GoalAction::Edit, GoalAction::Resume, GoalAction::Clear]
        );
        assert_eq!(
            actions(NativeGoalPhase::Blocked),
            vec![GoalAction::Edit, GoalAction::Resume, GoalAction::Clear]
        );
        assert_eq!(actions(NativeGoalPhase::Complete), vec![GoalAction::Clear]);
        assert!(actions(NativeGoalPhase::Cleared).is_empty());
        let view = goal_view(&goal(NativeGoalPhase::Blocked));
        assert_eq!(view.tone, Tone::Danger);
        assert_eq!(view.cause_label, "Review found a gap");
        assert_eq!(view.review_gap.as_deref(), Some("no migration test"));
        assert_eq!(view.reason.as_deref(), Some("tests are red"));
        assert!(goal_is_open(Some(&goal(NativeGoalPhase::Paused))));
        assert!(!goal_is_open(Some(&goal(NativeGoalPhase::Cleared))));
        assert!(!goal_is_open(None));
    }

    fn question(id: &str, multiple: bool) -> NativeQuestion {
        NativeQuestion {
            id: id.into(),
            prompt: "pick".into(),
            allow_multiple: multiple,
            options: vec![NativeQuestionOption {
                label: "a".into(),
                description: "the a option".into(),
            }],
        }
    }

    #[test]
    fn answers_keep_selection_freeform_and_none_of_above_apart() {
        let single = question("q1", false);
        let multi = question("q2", true);
        let mut drafts = std::collections::HashMap::new();
        assert_eq!(
            build_answers(&[single.clone(), multi.clone()], &drafts),
            None,
            "an unanswered question blocks the answer"
        );
        let mut d1 = QuestionDraft::default();
        d1.toggle_option(&single, "a");
        d1.toggle_option(&single, "b");
        assert_eq!(d1.selected, vec!["b".to_owned()], "single choice replaces");
        let mut d2 = QuestionDraft::default();
        d2.toggle_option(&multi, "a");
        d2.toggle_option(&multi, "b");
        d2.toggle_option(&multi, "a");
        assert_eq!(d2.selected, vec!["b".to_owned()], "multiselect toggles");
        d2.freeform = "  and also this ".into();
        drafts.insert("q1".to_owned(), d1);
        drafts.insert("q2".to_owned(), d2);
        let answers = build_answers(&[single.clone(), multi.clone()], &drafts).unwrap();
        assert_eq!(answers[1].freeform_text.as_deref(), Some("and also this"));
        assert_eq!(answers[1].selected_options, vec!["b".to_owned()]);
        let mut none = QuestionDraft::default();
        none.toggle_option(&single, "a");
        none.toggle_none_of_above();
        assert!(none.selected.is_empty() && none.none_of_above);
        none.toggle_option(&single, "a");
        assert!(
            !none.none_of_above,
            "choosing an option clears none-of-above"
        );
        let mut only_none = QuestionDraft::default();
        only_none.toggle_none_of_above();
        drafts.insert("q1".to_owned(), only_none);
        let answers = build_answers(&[single], &drafts).unwrap();
        assert!(answers[0].none_of_above && answers[0].selected_options.is_empty());
    }

    #[test]
    fn deliveries_offer_retry_only_when_the_host_says_so() {
        let unknown_prompt = NativeSubmission::new(
            "m1",
            NativeSubmissionKind::Prompt,
            NativeDelivery::Unknown {
                message: "lost".into(),
            },
        );
        let unknown_steer = NativeSubmission::new(
            "m2",
            NativeSubmissionKind::Steer,
            NativeDelivery::Unknown {
                message: "lost".into(),
            },
        );
        let settled = NativeSubmission::new(
            "m3",
            NativeSubmissionKind::Prompt,
            NativeDelivery::Admitted {
                request_id: "r1".into(),
            },
        );
        let state = NativeChatState {
            submissions: vec![unknown_prompt, unknown_steer, settled],
            ..Default::default()
        };
        let views = attention_deliveries(&state);
        assert_eq!(views.len(), 2, "an admitted message needs no attention");
        assert!(views[0].retryable);
        assert!(!views[1].retryable, "a steer is never replay-safe");
        assert!(views[1].label.contains("Guidance delivery is unknown"));
        assert!(
            views[1]
                .detail
                .as_deref()
                .is_some_and(|detail| detail.contains("not retried automatically"))
        );
    }

    #[test]
    fn readiness_gives_a_manual_action_and_never_installs() {
        let missing = readiness_view(&NativeReadiness::PluginMissing {
            message: "no plugin".into(),
            action: "mimir plugin install roboco".into(),
        });
        assert!(!missing.ready);
        assert_eq!(
            missing.action.as_deref(),
            Some("mimir plugin install roboco")
        );
        let ready = readiness_view(&NativeReadiness::Ready {
            executable: "/usr/bin/mimir".into(),
            version: Some("1.2.3".into()),
            bridge: "v1".into(),
        });
        assert!(ready.ready && ready.action.is_none());
        assert_eq!(ready.title, "Mimir 1.2.3 is ready");
        assert!(
            readiness_view(&NativeReadiness::Failed {
                message: "x".into()
            })
            .action
            .is_none()
        );
    }

    #[test]
    fn catalog_rows_keep_host_identities_and_idle_only() {
        let catalog = NativeChatCatalog {
            commands: vec![NativeCommand {
                name: "compact".into(),
                description: "compact".into(),
                aliases: Vec::new(),
                argument: Some("focus".into()),
                idle_only: true,
            }],
            skills: vec![
                NativeSkill {
                    name: "review".into(),
                    description: "r".into(),
                    path: Some("/skills/review/SKILL.md".into()),
                    bundled: false,
                },
                NativeSkill {
                    name: "plug:lazy".into(),
                    description: "l".into(),
                    path: None,
                    bundled: true,
                },
            ],
            providers: Vec::new(),
        };
        let rows = invocation_rows(&catalog);
        assert_eq!(rows[0].kind, InvocationKind::Command);
        assert_eq!(rows[0].argument.as_deref(), Some("focus"));
        assert_eq!(rows[1].path.as_deref(), Some("/skills/review/SKILL.md"));
        assert_eq!(rows[2].path.as_deref(), Some("harness-skill:plug:lazy"));
        assert_eq!(row_unavailable(&rows[0], true), Some(IDLE_ONLY_COPY));
        assert_eq!(row_unavailable(&rows[0], false), None);
        assert_eq!(row_unavailable(&rows[1], true), None);
    }

    #[test]
    fn child_control_labels_stay_honest_about_attempts() {
        let running = child(2, NativeChildStatus::Running);
        let sent = |stop: bool, attempt: u32, receipt: ControlReceipt| ChildControlSent {
            stop,
            attempt,
            receipt,
        };
        let accepted = ControlReceipt::Settled {
            command_id: "cmd-1".into(),
            verdict: ControlVerdict::ChildAccepted,
        };
        let text = |sent: Option<&ChildControlSent>, child: &NativeChild| {
            child_control_label(sent, child).map(|view| view.text)
        };
        assert_eq!(text(None, &running), None);
        assert_eq!(
            text(
                Some(&sent(
                    false,
                    2,
                    ControlReceipt::Pending {
                        command_id: "cmd-1".into()
                    }
                )),
                &running
            )
            .as_deref(),
            Some("Waiting for Mimir to accept the guidance."),
            "the enqueue alone is not acceptance"
        );
        assert_eq!(
            text(Some(&sent(false, 2, accepted.clone())), &running).as_deref(),
            Some("Guidance queued")
        );
        assert_eq!(
            text(Some(&sent(true, 2, accepted.clone())), &running).as_deref(),
            Some("Stopping")
        );
        let stale = text(Some(&sent(true, 1, accepted.clone())), &running)
            .expect("a control that raced an attempt change is shown stale");
        assert!(stale.contains("Attempt 1 has ended") && stale.contains("attempt 2"));
        let moved = text(
            Some(&sent(
                false,
                2,
                ControlReceipt::Settled {
                    command_id: "cmd-1".into(),
                    verdict: ControlVerdict::ChildMoved { attempt: 3 },
                },
            )),
            &running,
        )
        .unwrap();
        assert_eq!(
            moved,
            "That agent moved on to attempt 3. Nothing was sent to it."
        );
        let settled = child(2, NativeChildStatus::Stopped);
        assert_eq!(
            text(Some(&sent(true, 2, accepted)), &settled),
            None,
            "Stopping clears once the child settles"
        );
        assert_eq!(child_title(&running), "probe");
        let mut nameless = running.clone();
        nameless.description.clear();
        assert_eq!(child_title(&nameless), "general-purpose");
    }

    #[test]
    fn every_notice_variant_has_an_honest_row() {
        let notices = vec![
            NativeNotice::PlanLifecycle {
                plan_id: "p".into(),
                name: "Ship".into(),
                status: NativePlanStatus::ReviewPending,
            },
            NativeNotice::Compaction {
                trigger: "auto".into(),
                before_tokens: Some(120000),
                after_tokens: None,
            },
            NativeNotice::BranchSummary {
                summary: "went left".into(),
            },
            NativeNotice::PluginSnapshot {
                plugin_id: "x".into(),
                title: "Plugin".into(),
                revision: 1,
                fallback: "fallback text".into(),
                lines: serde_json::json!([{"rich": true}]),
            },
            NativeNotice::SubagentCompletions {
                completions: Vec::new(),
            },
            NativeNotice::CommandDisplay { text: "out".into() },
            NativeNotice::Status {
                text: "retrying".into(),
            },
            NativeNotice::Interrupted {
                message: "bridge died".into(),
                detail_ref: Some("c/p.native".into()),
            },
            NativeNotice::Image {
                media_type: Some("image/png".into()),
                detail_ref: Some("c/i".into()),
            },
            NativeNotice::Unsupported {
                item: "widget".into(),
                detail_ref: None,
            },
        ];
        let views: Vec<NoticeView> = notices.iter().map(notice_view).collect();
        assert_eq!(views[0].body.as_deref(), Some("Awaiting your review"));
        assert_eq!(
            views[1].body.as_deref(),
            Some("auto. 120,000 tokens before, unknown after.")
        );
        assert_eq!(
            views[3].body.as_deref(),
            Some("fallback text"),
            "a non-text plugin snapshot falls back read-only"
        );
        assert_eq!(views[4].title, "0 agents finished");
        assert_eq!(views[7].tone, Tone::Warn);
        assert!(views[7].body.as_deref().unwrap().contains("provisional"));
        assert_eq!(views[7].detail_ref.as_deref(), Some("c/p.native"));
        assert_eq!(views[8].detail_ref.as_deref(), Some("c/i"));
        assert_eq!(views[9].title, "Content Roboco cannot show");
        let text_snapshot = notice_view(&NativeNotice::PluginSnapshot {
            plugin_id: "x".into(),
            title: "T".into(),
            revision: 2,
            fallback: "f".into(),
            lines: serde_json::json!(["one", "two"]),
        });
        assert_eq!(text_snapshot.body.as_deref(), Some("one\ntwo"));
    }

    #[test]
    fn running_title_quiet_and_group_follow_the_host() {
        let mut v = view("read");
        assert_eq!(tool_title(&v, false), "Reading a.rs");
        assert_eq!(tool_title(&v, true), "Read a.rs");
        v.running_title = None;
        assert_eq!(tool_title(&v, false), "Read a.rs");
        v.quiet = true;
        assert!(is_quiet_success(&v, false));
        assert!(!is_quiet_success(&v, true), "a quiet failure stays visible");
        let grouped = |item: &str, key: &str| {
            let mut v = view("read");
            v.group = Some(NativeToolGroup {
                key: key.into(),
                label: "Read".into(),
                item: item.into(),
            });
            v
        };
        let a = grouped("a.rs", "k");
        let b = grouped("b.rs", "k");
        let c = grouped("c.rs", "other");
        assert_eq!(group_label(&[&a, &b]).as_deref(), Some("Read · a.rs, b.rs"));
        assert_eq!(group_label(&[&a, &c]), None);
        assert_eq!(group_label(&[&view("x")]), None);
    }

    #[test]
    fn blob_windows_decode_a_split_utf8_character_once() {
        let text = "héllo";
        let bytes = text.as_bytes();
        let (head, tail) = bytes.split_at(2);
        use base64::Engine as _;
        let window = |part: &[u8]| ToolBlobWindow {
            encoding: ToolBlobEncoding::Base64,
            text: base64::engine::general_purpose::STANDARD.encode(part),
            offset: 0,
            total_bytes: bytes.len() as u64,
            next_offset: None,
        };
        let mut blob = BlobBytes::default();
        blob.push(&window(head)).unwrap();
        blob.push(&window(tail)).unwrap();
        assert_eq!(blob.len(), bytes.len());
        assert_eq!(blob.into_text(), text);
        let mut bad = BlobBytes::default();
        assert!(
            bad.push(&ToolBlobWindow {
                encoding: ToolBlobEncoding::Base64,
                text: "!!!".into(),
                offset: 0,
                total_bytes: 0,
                next_offset: None,
            })
            .is_err()
        );
    }

    #[test]
    fn series_refs_number_every_chunk() {
        let series = NativeBlobSeries {
            blob_ref: "c/p.progress".into(),
            chunks: 3,
            bytes: 10,
            records: 4,
        };
        assert_eq!(
            series_refs(&series),
            vec![
                "c/p.progress.000000",
                "c/p.progress.000001",
                "c/p.progress.000002"
            ]
        );
    }

    fn detail() -> NativeToolDetail {
        NativeToolDetail {
            name: "bash".into(),
            tool_call_id: "c1".into(),
            invocation_id: None,
            input: Some(serde_json::json!({"cmd": "ls"})),
            raw_input: None,
            is_error: Some(false),
            output: None,
            display_content: vec![serde_json::json!({"type": "text", "text": "public"})],
            details: None,
            output_profile: None,
            compactions: Vec::new(),
            progress: Some(NativeBlobSeries {
                blob_ref: "p".into(),
                chunks: 1,
                bytes: 8,
                records: 2,
            }),
            stream: None,
            view: view("bash"),
        }
    }

    #[test]
    fn detail_text_never_swaps_in_a_model_only_fallback() {
        let text = sections_text(&detail_sections(
            &detail(),
            Some("\"step one\"\nnot json\n"),
            None,
        ));
        assert!(text.contains("Input\n{\n  \"cmd\": \"ls\"\n}"));
        assert!(
            text.contains("Public content blocks (1)") && text.contains("\"public\""),
            "{text}"
        );
        assert!(
            text.contains("has no text. Its 1 content blocks are listed below."),
            "a non-empty public list is listed, not replaced"
        );
        assert!(text.contains("Progress (2 lines, 8 B)\nstep one\nnot json"));
        assert!(!text.contains("Streamed output"));
    }

    #[test]
    fn detail_view_keeps_every_public_field_the_host_sent() {
        let mut full = detail();
        full.raw_input = Some("{\"cmd\":\"ls\" }".into());
        full.output = Some("## Found\n\n- one".into());
        full.output_profile = Some(serde_json::json!({"lines": 2, "kind": "report"}));
        full.compactions = vec![
            serde_json::json!({"reason": "size", "kept": 100}),
            serde_json::json!({"reason": "age"}),
        ];
        full.view.semantic = Some(roboco_proto::NativeToolSemantic::ReportMarkdown);
        full.view.result_state = NativeToolResultState::NoMatches;
        let sections = detail_sections(&full, None, None);
        let titles: Vec<&str> = sections.iter().map(|s| s.title.as_str()).collect();
        assert_eq!(
            titles,
            vec![
                "Call",
                "Input",
                "Raw input, as the host received it",
                "Result",
                "Public content blocks (1)",
                "Output profile",
                "Compactions (2). The original bytes may be unavailable.",
            ],
            "parsed input does not hide the raw input"
        );
        assert_eq!(sections[0].body, "Succeeded · bash · No matches");
        assert_eq!(sections[2].body, "{\"cmd\":\"ls\" }");
        assert!(
            sections[3].markdown,
            "a declared report renders as Markdown"
        );
        assert!(sections[5].body.contains("\"kind\": \"report\""));
        assert!(
            sections[6].body.contains("\"reason\": \"size\"")
                && sections[6].body.contains("\"age\"")
        );
    }

    #[test]
    fn chips_follow_the_host_semantic_preview_and_result_state() {
        let mut v = view("grep");
        v.result_state = NativeToolResultState::NoMatches;
        assert_eq!(
            chip_title(&v, false),
            "Reading a.rs",
            "running calls keep the running title"
        );
        assert_eq!(chip_title(&v, true), "Read a.rs · No matches");
        assert_eq!(inline_result(&v), InlineResult::Hidden);
        v.result_state = NativeToolResultState::Empty;
        assert_eq!(chip_title(&v, true), "Read a.rs · No output");
        v.result_state = NativeToolResultState::Normal;
        assert_eq!(chip_title(&v, true), "Read a.rs");
        assert_eq!(inline_result(&v), InlineResult::Text);
        v.preview = NativeToolPreview::Compact;
        assert_eq!(
            inline_result(&v),
            InlineResult::Hidden,
            "a compact preview shows no body"
        );
        v.semantic = Some(roboco_proto::NativeToolSemantic::AnswerMarkdown);
        assert_eq!(inline_result(&v), InlineResult::Markdown);
        let running = detail_version(&v, false, false);
        v.duration_ms = Some(40);
        assert_ne!(
            running,
            detail_version(&v, true, false),
            "settling makes an earlier read stale"
        );
        assert_eq!(
            detail_version(&v, true, false),
            detail_version(&v, true, false)
        );
    }

    #[test]
    fn exports_keep_each_record_whole_and_separate() {
        let record = br#"{"name":"bash","odd":  [1,2]}"#;
        let progress = "\"a\"\n\"b\"\n".as_bytes();
        let export = compose_export(
            "Mimir tool detail",
            &[
                ExportPart {
                    label: "Detail record c/p.native".into(),
                    bytes: record,
                },
                ExportPart {
                    label: "Progress c/p.progress".into(),
                    bytes: progress,
                },
            ],
        );
        let text = String::from_utf8(export).unwrap();
        assert!(text.contains(&format!(
            "=== Detail record c/p.native ({} bytes) ===\n{}\n=== end ===",
            record.len(),
            std::str::from_utf8(record).unwrap()
        )));
        assert!(
            text.contains("=== Progress c/p.progress (8 bytes) ===\n\"a\"\n\"b\"\n\n=== end ===")
        );
    }

    #[test]
    fn controls_travel_as_typed_native_commands() {
        let params = control_params(
            "chat-1",
            &NativeControl::CancelRequest {
                request_id: "req-7".into(),
            },
        );
        assert_eq!(
            params,
            serde_json::json!({
                "chatId": "chat-1",
                "command": {"kind": "native", "control": {"control": "cancelRequest", "requestId": "req-7"}},
                "transfers": [],
            })
        );
    }

    #[test]
    fn usage_line_hides_zero_buckets() {
        let usage = NativeUsage {
            input_tokens: 1200,
            output_tokens: 34,
            reasoning_tokens: 0,
            cache_read_tokens: 500,
            cache_write_tokens: 0,
            total_tokens: 1734,
            provider: None,
        };
        assert_eq!(
            usage_line(&usage),
            "1,734 tokens, 1,200 in, 34 out, 500 cache read"
        );
        assert_eq!(thousands(0), "0");
        assert_eq!(thousands(1_000_000), "1,000,000");
        assert_eq!(format_bytes(2048), "2.0 KB");
    }
}
