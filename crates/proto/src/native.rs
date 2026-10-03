//! Native conversations: harnesses whose own host owns execution and the saved
//! conversation (Mimir). The engine projects the host-confirmed state into the
//! chat doc as [`NativeChatState`], keeps complete public tool details in
//! engine-local blobs behind [`NativeToolView::detail_ref`], and accepts typed
//! [`NativeControl`]s through the durable command ledger. Nothing here is a
//! prompt: controls never travel as model text.

use serde::{Deserialize, Serialize};
use ts_rs::TS;

/// The engine's ownership of a chat's native conversation.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize, TS)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum NativeLink {
    /// No attachment is held: a fresh chat, or one not reopened since the engine started.
    #[default]
    Detached,
    Attaching,
    /// The engine owns the attachment and a live view.
    Attached,
    /// Another Mimir process, usually the TUI, owns the conversation. Nothing
    /// was submitted; close it there and reconnect.
    Busy {
        message: String,
    },
    /// The bridge ended while attached. Work in flight stopped with it; the
    /// saved conversation is reconciled when the bridge restarts.
    Interrupted {
        message: String,
    },
    Releasing,
    /// Released for the TUI ("Continue in Mimir"). The mapping is retained and
    /// a reconnect reopens the same conversation.
    Released,
    /// The harness cannot run: missing executable or plugin, incompatible bridge.
    Unavailable {
        message: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeConversation {
    pub id: String,
    /// The host's normalized folder: the identity the TUI lists it under.
    pub cwd: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeMode {
    Build,
    Plan,
}

/// Host-confirmed configuration. Provider and model are absent while the
/// conversation has no connected model; reasoning is absent when the model
/// has no reasoning control.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeConfiguration {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub reasoning: Option<String>,
    pub mode: NativeMode,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeRequestStatus {
    Queued,
    Running,
    Completed,
    Cancelled,
    Failed,
    Interrupted,
}

impl NativeRequestStatus {
    pub fn is_terminal(self) -> bool {
        !matches!(self, Self::Queued | Self::Running)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeRequest {
    pub id: String,
    pub status: NativeRequestStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub submission_key: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativePlanStatus {
    ReviewPending,
    SavedStopped,
    Accepted,
    Implementing,
    Completed,
    Abandoned,
}

/// Identity and lifecycle of the current plan. The Markdown body is read on
/// demand; decisions name `id`, never a proposal observation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativePlanSummary {
    pub id: String,
    pub name: String,
    pub status: NativePlanStatus,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativePlanSection {
    pub title: String,
    pub body: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativePlanArtifact {
    pub id: String,
    pub name: String,
    pub path: String,
    pub markdown: String,
    pub sections: Vec<NativePlanSection>,
    pub stages: Vec<NativePlanSection>,
    pub status: NativePlanStatus,
}

/// Implement continues the conversation; implement-fresh first clears the
/// model's context. All three select Build.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativePlanDecision {
    Implement,
    ImplementFresh,
    SaveAndStop,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeGoalPhase {
    Active,
    Paused,
    Blocked,
    Complete,
    Cleared,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeGoalCause {
    Started,
    Edited,
    Resumed,
    NaturalContinuation,
    ReviewGap,
    ReviewAccepted,
    UserPaused,
    UserInput,
    Manual,
    TimeLimit,
    NoProgress,
    RuntimeFailure,
    Restart,
    Blocked,
    ReviewUnavailable,
    Cleared,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeGoalRequirement {
    pub requirement: String,
    pub evidence: String,
}

/// The checklist an independent review accepted when the goal completed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeGoalCompletion {
    pub checklist: Vec<NativeGoalRequirement>,
    pub limitations: Option<String>,
    pub completed_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeGoal {
    pub id: String,
    pub objective: String,
    pub phase: NativeGoalPhase,
    pub cause: NativeGoalCause,
    pub reason: Option<String>,
    /// The completion review's open gap, until it is closed.
    pub review_gap: Option<String>,
    pub work_turns: u32,
    pub started_at: String,
    pub completion: Option<NativeGoalCompletion>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeQuestionOption {
    pub label: String,
    pub description: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeQuestion {
    pub id: String,
    pub prompt: String,
    pub allow_multiple: bool,
    pub options: Vec<NativeQuestionOption>,
}

/// A pending question or tool approval. Only this exact id can be answered.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeUserRequest {
    pub id: String,
    pub questions: Vec<NativeQuestion>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeAnswer {
    pub question_id: String,
    pub selected_options: Vec<String>,
    pub freeform_text: Option<String>,
    pub none_of_above: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeChildStatus {
    Queued,
    Running,
    Completed,
    Failed,
    Deadline,
    Stopped,
    Interrupted,
}

impl NativeChildStatus {
    pub fn is_terminal(self) -> bool {
        !matches!(self, Self::Queued | Self::Running)
    }
}

/// The host's live presentation of a delegated run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeSubagentRun {
    pub attempt: Option<u32>,
    pub agent_id: String,
    pub agent: String,
    pub model: Option<String>,
    pub description: String,
    pub status: NativeChildStatus,
    pub continued: bool,
    pub background: bool,
    pub turns: u32,
    pub timeout_ms: Option<u64>,
    pub remaining_ms: Option<u64>,
    pub phase: String,
    pub tool_uses: u32,
    pub tokens: u64,
    pub context_percent: Option<u8>,
    pub compactions: u32,
    pub elapsed_ms: u64,
}

/// A native delegated agent owned by the chat's conversation. The handle and
/// `doc_id` stay fixed across attempts; steer and Stop name the exact attempt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeChild {
    pub handle: String,
    pub attempt: u32,
    #[serde(default)]
    pub profile: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub model: Option<String>,
    pub status: NativeChildStatus,
    #[serde(default)]
    pub background: bool,
    /// The parent tool invocation that admitted this attempt, when known.
    #[serde(default)]
    pub spawned_by: Option<String>,
    /// The parent has not yet received this attempt's terminal result.
    pub completion_pending: bool,
    #[serde(default)]
    pub presentation: Option<NativeSubagentRun>,
    /// The right-pane document holding this child's transcript.
    pub doc_id: String,
    /// The view announced only the child's header because its details were
    /// too large; read the inventory for the rest.
    #[serde(default)]
    pub oversized: bool,
    /// Every attempt seen under this handle, oldest first, the current one
    /// last. A continued child keeps its earlier attempts' records here.
    #[serde(default)]
    pub attempts: Vec<NativeChildAttempt>,
}

/// One attempt of a delegated child, kept after the child moves on.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeChildAttempt {
    pub attempt: u32,
    pub status: NativeChildStatus,
    #[serde(default)]
    pub presentation: Option<NativeSubagentRun>,
    /// Engine blob ref of this attempt's terminal [`NativeChildOutcome`],
    /// read once the attempt settled (`FetchToolBlob`).
    #[serde(default)]
    pub outcome_ref: Option<String>,
}

/// Token usage the host reported, with the provider's own usage record.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeUsage {
    pub input_tokens: u64,
    pub output_tokens: u64,
    pub reasoning_tokens: u64,
    pub cache_read_tokens: u64,
    pub cache_write_tokens: u64,
    pub total_tokens: u64,
    #[serde(default)]
    #[ts(type = "unknown")]
    pub provider: Option<serde_json::Value>,
}

/// Admission state of a message the engine submitted. A message is Roboco's
/// until the host admits it; `Unknown` means the host's answer was lost and is
/// never retried silently.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum NativeDelivery {
    Submitting,
    #[serde(rename_all = "camelCase")]
    Admitted {
        request_id: String,
    },
    /// Steering the host accepted into this running request.
    #[serde(rename_all = "camelCase")]
    Steered {
        request_id: String,
    },
    /// A host command handled it without admitting work.
    Handled,
    /// The host's answer was lost: it may or may not have taken the message.
    /// Nothing is resent; only a keyed prompt may be retried (see
    /// [`NativeSubmission::retryable`]).
    Unknown {
        message: String,
    },
    Refused {
        message: String,
    },
}

/// How the engine handed a message to the host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeSubmissionKind {
    /// Keyed prompt admission: the message id is the submission key.
    #[default]
    Prompt,
    /// Host slash-command routing. Commands carry no key.
    Command,
    /// Guidance into the running request. Steering carries no key.
    Steer,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeSubmission {
    /// The chat message id, which is also the host submission key.
    pub message_id: String,
    #[serde(default)]
    pub kind: NativeSubmissionKind,
    pub delivery: NativeDelivery,
    /// `RetrySubmission` is accepted for this message: a keyed prompt the host
    /// may not have admitted, or a prompt or command the host refused outright.
    #[serde(default)]
    pub retryable: bool,
}

impl NativeSubmission {
    pub fn new(message_id: &str, kind: NativeSubmissionKind, delivery: NativeDelivery) -> Self {
        let retryable = match &delivery {
            NativeDelivery::Refused { .. } => kind != NativeSubmissionKind::Steer,
            NativeDelivery::Unknown { .. } => kind == NativeSubmissionKind::Prompt,
            _ => false,
        };
        Self {
            message_id: message_id.to_owned(),
            kind,
            delivery,
            retryable,
        }
    }
}

/// Everything clients render about a native conversation that is not
/// transcript content. Written by the engine only, from host-confirmed state.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeChatState {
    #[serde(default)]
    pub conversation: Option<NativeConversation>,
    #[serde(default)]
    pub link: NativeLink,
    /// The host did not retain the live suffix since the last saved
    /// checkpoint. Live output is withheld until the next checkpoint; nothing
    /// shown is a complete response until then.
    #[serde(default)]
    pub recovering: bool,
    #[serde(default)]
    pub configuration: Option<NativeConfiguration>,
    #[serde(default)]
    pub active_request: Option<String>,
    /// The most recent requests, newest last.
    #[serde(default)]
    pub requests: Vec<NativeRequest>,
    #[serde(default)]
    pub plan: Option<NativePlanSummary>,
    #[serde(default)]
    pub goal: Option<NativeGoal>,
    #[serde(default)]
    pub user_request: Option<NativeUserRequest>,
    #[serde(default)]
    pub children: Vec<NativeChild>,
    #[serde(default)]
    pub submissions: Vec<NativeSubmission>,
}

impl NativeChatState {
    /// Host-confirmed work is still owed: a running request, an active goal,
    /// or a queued or running child. Root completion alone is not rest.
    pub fn working(&self) -> bool {
        self.active_request.is_some()
            || self
                .goal
                .as_ref()
                .is_some_and(|goal| goal.phase == NativeGoalPhase::Active)
            || self
                .children
                .iter()
                .any(|child| !child.status.is_terminal())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(tag = "change", rename_all = "camelCase")]
pub enum NativeGoalChange {
    #[serde(rename_all = "camelCase")]
    Start {
        objective: String,
        /// Absent for an unbounded goal.
        duration_seconds: Option<u64>,
    },
    /// Replaces the objective; a paused goal stays paused.
    Edit {
        objective: String,
    },
    Pause,
    #[serde(rename_all = "camelCase")]
    Resume {
        duration_seconds: Option<u64>,
    },
    Clear,
}

/// A typed control for a native conversation, carried by the durable command
/// ledger and executed at most once.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(tag = "control", rename_all = "camelCase")]
pub enum NativeControl {
    /// Changes made while running apply to the next prompt.
    Configure {
        provider: Option<String>,
        model: Option<String>,
        reasoning: Option<String>,
        mode: Option<NativeMode>,
    },
    /// Host command routing. Unknown and terminal-only commands are refused.
    Command {
        name: String,
        tail: String,
    },
    #[serde(rename_all = "camelCase")]
    DecidePlan {
        plan_id: String,
        decision: NativePlanDecision,
    },
    ChangeGoal {
        change: NativeGoalChange,
    },
    #[serde(rename_all = "camelCase")]
    Answer {
        request_id: String,
        answers: Vec<NativeAnswer>,
    },
    /// Guidance for the root at its next model boundary; not an interruption.
    Steer {
        text: String,
    },
    /// Interrupt exactly this admitted root request.
    #[serde(rename_all = "camelCase")]
    CancelRequest {
        request_id: String,
    },
    SteerChild {
        handle: String,
        attempt: u32,
        text: String,
    },
    /// Stop exactly this attempt of a child: never the parent, a sibling or a newer attempt.
    StopChild {
        handle: String,
        attempt: u32,
    },
    /// Continue in Mimir: release the idle attachment and await the host's
    /// cleanup so the TUI can open the same conversation.
    Release,
    /// Reopen the mapped conversation after a release, busy owner or bridge restart.
    Reconnect,
    /// Resubmit a message whose admission is unknown, under its original key.
    #[serde(rename_all = "camelCase")]
    RetrySubmission {
        message_id: String,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeErrorKind {
    Closed,
    NotReady,
    NotFound,
    /// Work is in the way, or another Mimir process owns the conversation.
    Busy,
    Invalid,
    Cancelled,
    Failed,
    /// The engine holds no attachment for this chat.
    NotAttached,
    /// The bridge is not running or not compatible.
    Unavailable,
}

/// A child control's host result. Acceptance is not proof of delivery or
/// cleanup: the child's settled status arrives with the inventory.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(tag = "result", rename_all = "camelCase")]
pub enum NativeChildControl {
    /// Guidance queued for the next model boundary, or stopping began.
    Accepted,
    /// The child moved on to this attempt; nothing changed.
    AttemptChanged {
        attempt: u32,
    },
    Terminal,
    Finalizing,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(tag = "outcome", rename_all = "camelCase")]
pub enum NativeControlOutcome {
    /// Accepted with nothing further to report; resulting state arrives in the projection.
    Applied,
    Configured {
        configuration: NativeConfiguration,
    },
    /// The host started work under this request.
    #[serde(rename_all = "camelCase")]
    Admitted {
        request_id: String,
    },
    /// Transient command output.
    Display {
        text: String,
    },
    Child {
        control: NativeChildControl,
    },
    /// Whether this control newly cancelled the request.
    Cancelled {
        newly: bool,
    },
    Released,
    Refused {
        kind: NativeErrorKind,
        message: String,
    },
    /// The host's answer was lost: the control may or may not have taken
    /// effect. Resulting state arrives in the projection; nothing is resent.
    Unknown {
        message: String,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeToolKind {
    Generic,
    Shell,
    FileRead,
    ImageView,
    FileChange,
    FileContentSearch,
    FilePathSearch,
    Status,
    Plan,
    Answer,
    UserRequest,
    Web,
    Agent,
    Mcp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeToolResultState {
    Normal,
    Empty,
    NoMatches,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeToolPreview {
    Full,
    Compact,
}

/// A public meaning frontends render directly instead of the raw output.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub enum NativeToolSemantic {
    AnswerMarkdown,
    ReportMarkdown,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeToolLocation {
    pub path: String,
    pub line: Option<u32>,
}

/// Adjacent calls from one model turn sharing `key` render as one row:
/// "<label> · <item>, <item>".
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeToolGroup {
    pub key: String,
    pub label: String,
    pub item: String,
}

/// The host's display semantics for one tool call, carried on its transcript
/// part. Renderers use these values, never guesses from tool names. A quiet
/// call shows only with details expanded, unless it failed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeToolView {
    /// The model-facing tool name.
    pub name: String,
    pub tool_call_id: String,
    pub invocation_id: Option<String>,
    pub title: String,
    pub running_title: Option<String>,
    pub kind: NativeToolKind,
    pub summary: Option<String>,
    #[serde(default)]
    pub locations: Vec<NativeToolLocation>,
    pub result_state: NativeToolResultState,
    pub preview: NativeToolPreview,
    pub semantic: Option<NativeToolSemantic>,
    #[serde(default)]
    pub quiet: bool,
    pub group: Option<NativeToolGroup>,
    pub subagent: Option<NativeSubagentRun>,
    /// Latest public progress line while the call runs.
    pub progress: Option<String>,
    pub duration_ms: Option<u64>,
    /// Engine blob ref (`{chatId}/{partId}.native`) of the [`NativeToolDetail`].
    pub detail_ref: Option<String>,
    pub detail_bytes: Option<u64>,
    /// The native child this call delegated to, bound through the host's
    /// spawn lineage (`child.spawned_by` = this invocation).
    pub child: Option<String>,
}

/// Every public detail of one tool call, stored as an engine-local blob.
/// Host values (arguments, display content, details) are kept as the host's
/// own JSON. Model-only context never appears here.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeToolDetail {
    pub name: String,
    pub tool_call_id: String,
    pub invocation_id: Option<String>,
    #[ts(type = "unknown")]
    pub input: Option<serde_json::Value>,
    pub raw_input: Option<String>,
    pub is_error: Option<bool>,
    /// Public result text: the host's display output when it has one.
    pub output: Option<String>,
    /// Public display content blocks, in host order.
    #[ts(type = "unknown[]")]
    pub display_content: Vec<serde_json::Value>,
    #[ts(type = "unknown")]
    pub details: Option<serde_json::Value>,
    #[ts(type = "unknown")]
    pub output_profile: Option<serde_json::Value>,
    /// Host-side result compactions; original bytes may be unavailable.
    #[ts(type = "unknown[]")]
    pub compactions: Vec<serde_json::Value>,
    /// Every progress line the call reported, as JSON lines (one JSON string
    /// per line). The view's `progress` is only the latest.
    #[serde(default)]
    pub progress: Option<NativeBlobSeries>,
    /// The call's complete streamed output, as received.
    #[serde(default)]
    pub stream: Option<NativeBlobSeries>,
    pub view: NativeToolView,
}

/// A complete record kept as numbered engine blobs: read
/// `{blobRef}.{index:06}` for each index below `chunks`, in order, with
/// `FetchToolBlob` windows. Nothing is dropped; chunks only bound reads.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeBlobSeries {
    pub blob_ref: String,
    pub chunks: u32,
    pub bytes: u64,
    /// Records received: progress lines, or output deltas.
    pub records: u64,
}

/// A public transcript item that is neither text nor a tool call.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[serde(tag = "notice", rename_all = "camelCase")]
pub enum NativeNotice {
    #[serde(rename_all = "camelCase")]
    PlanLifecycle {
        plan_id: String,
        name: String,
        status: NativePlanStatus,
    },
    #[serde(rename_all = "camelCase")]
    Compaction {
        trigger: String,
        before_tokens: Option<u64>,
        after_tokens: Option<u64>,
    },
    BranchSummary {
        summary: String,
    },
    /// A read-only plugin display saved into the transcript. It has no callbacks.
    #[serde(rename_all = "camelCase")]
    PluginSnapshot {
        plugin_id: String,
        title: String,
        revision: u64,
        fallback: String,
        #[ts(type = "unknown")]
        lines: serde_json::Value,
    },
    SubagentCompletions {
        completions: Vec<NativeChildCompletion>,
    },
    /// Transient command output.
    CommandDisplay {
        text: String,
    },
    /// Transient host progress, such as a scheduled model retry.
    Status {
        text: String,
    },
    /// Live output ended without the host's checkpoint: the bridge or engine
    /// stopped. What was shown provisionally is kept apart from the saved
    /// transcript, as JSON `MessagePart[]` behind `detail_ref`; whatever the
    /// host saved arrives as ordinary entries on reconnect.
    #[serde(rename_all = "camelCase")]
    Interrupted {
        message: String,
        detail_ref: Option<String>,
    },
    /// An image block. The bytes stay in the engine blob behind `detail_ref`.
    #[serde(rename_all = "camelCase")]
    Image {
        media_type: Option<String>,
        detail_ref: Option<String>,
    },
    /// A public item this engine has no renderer for, kept inspectable.
    #[serde(rename_all = "camelCase")]
    Unsupported {
        item: String,
        detail_ref: Option<String>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeChildCompletion {
    pub handle: String,
    pub attempt: Option<u32>,
    pub status: NativeChildStatus,
    pub description: String,
    pub result_preview: String,
    pub result_truncated: bool,
    pub changed_files: Vec<String>,
    pub omitted_changed_files: u64,
    /// The host's full presentation of the delivered run.
    #[serde(default)]
    pub run: Option<NativeSubagentRun>,
    #[serde(default)]
    pub usage: Option<NativeUsage>,
}

/// A child's terminal outcome read without delivering it to the parent.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeChildOutcome {
    pub handle: String,
    pub attempt: u32,
    pub status: NativeChildStatus,
    pub result: Option<String>,
    pub error: Option<String>,
    pub changed_files: Vec<String>,
    #[serde(default)]
    pub usage: NativeUsage,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeCommand {
    pub name: String,
    pub description: String,
    pub aliases: Vec<String>,
    /// The argument hint, when the command takes one.
    pub argument: Option<String>,
    /// Rejected while the conversation has active work.
    pub idle_only: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeSkill {
    /// Effective catalog name, including a plugin qualifier when applicable.
    pub name: String,
    pub description: String,
    /// Exact source identity for disk skills; absent for lazy plugin skills.
    pub path: Option<String>,
    pub bundled: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeModelChoice {
    pub id: String,
    pub name: String,
    /// Selectable reasoning levels; empty when the model has no reasoning control.
    pub reasoning: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeProviderChoice {
    pub id: String,
    pub name: String,
    pub models: Vec<NativeModelChoice>,
}

/// What a chat's composer can offer, from the chat's own native conversation.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
pub struct NativeChatCatalog {
    pub commands: Vec<NativeCommand>,
    pub skills: Vec<NativeSkill>,
    pub providers: Vec<NativeProviderChoice>,
}

/// Setup readiness on this device, with the corrective action when not ready.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum NativeReadiness {
    Ready {
        executable: String,
        version: Option<String>,
        bridge: String,
    },
    MissingExecutable {
        action: String,
    },
    PluginMissing {
        message: String,
        action: String,
    },
    Incompatible {
        message: String,
        action: String,
    },
    NoModels {
        action: String,
    },
    Failed {
        message: String,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn controls_use_stable_tagged_wire_names() {
        let control = NativeControl::StopChild {
            handle: "agent-1".into(),
            attempt: 2,
        };
        assert_eq!(
            serde_json::to_value(&control).unwrap(),
            serde_json::json!({"control": "stopChild", "handle": "agent-1", "attempt": 2})
        );
        let goal = NativeControl::ChangeGoal {
            change: NativeGoalChange::Start {
                objective: "ship".into(),
                duration_seconds: Some(60),
            },
        };
        assert_eq!(
            serde_json::to_value(&goal).unwrap(),
            serde_json::json!({"control": "changeGoal", "change": {"change": "start", "objective": "ship", "durationSeconds": 60}})
        );
        let delivery = NativeDelivery::Admitted {
            request_id: "r1".into(),
        };
        assert_eq!(
            serde_json::to_value(&delivery).unwrap(),
            serde_json::json!({"state": "admitted", "requestId": "r1"})
        );
        assert_eq!(
            serde_json::to_value(NativeSubmission::new(
                "m1",
                NativeSubmissionKind::Steer,
                NativeDelivery::Steered {
                    request_id: "r1".into()
                }
            ))
            .unwrap(),
            serde_json::json!({"messageId": "m1", "kind": "steer",
                "delivery": {"state": "steered", "requestId": "r1"}, "retryable": false})
        );
        let unknown = |kind| {
            NativeSubmission::new(
                "m1",
                kind,
                NativeDelivery::Unknown {
                    message: "lost".into(),
                },
            )
            .retryable
        };
        assert!(
            unknown(NativeSubmissionKind::Prompt),
            "a keyed prompt can be resent"
        );
        assert!(!unknown(NativeSubmissionKind::Command));
        assert!(!unknown(NativeSubmissionKind::Steer));
        assert_eq!(
            serde_json::to_value(NativeControlOutcome::Unknown {
                message: "lost".into()
            })
            .unwrap(),
            serde_json::json!({"outcome": "unknown", "message": "lost"})
        );
        let outcome = NativeControlOutcome::Child {
            control: NativeChildControl::AttemptChanged { attempt: 3 },
        };
        assert_eq!(
            serde_json::to_value(&outcome).unwrap(),
            serde_json::json!({"outcome": "child", "control": {"result": "attemptChanged", "attempt": 3}})
        );
    }

    #[test]
    fn root_completion_with_a_running_child_or_active_goal_is_still_working() {
        let child = NativeChild {
            handle: "agent-1".into(),
            attempt: 1,
            profile: "general-purpose".into(),
            description: "probe".into(),
            model: None,
            status: NativeChildStatus::Running,
            background: true,
            spawned_by: None,
            completion_pending: false,
            presentation: None,
            doc_id: "chat--sub--agent-1".into(),
            oversized: false,
            attempts: Vec::new(),
        };
        let mut state = NativeChatState {
            children: vec![child],
            ..Default::default()
        };
        assert!(state.working());
        state.children[0].status = NativeChildStatus::Completed;
        assert!(!state.working());
        state.goal = Some(NativeGoal {
            id: "g".into(),
            objective: "o".into(),
            phase: NativeGoalPhase::Active,
            cause: NativeGoalCause::Started,
            reason: None,
            review_gap: None,
            work_turns: 0,
            started_at: "t".into(),
            completion: None,
        });
        assert!(state.working());
    }
}
