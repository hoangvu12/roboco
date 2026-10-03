//! The `sh.roboco.bridge` wire: JSON-RPC envelopes, typed error kinds, and the
//! public Mimir SDK values the bridge forwards (snake_case serde of the SDK's
//! WIT records, externally tagged variants). Everything is parsed here, at the
//! process boundary, into types the engine can trust.
//!
//! Contract: `integrations/mimir/README.md` and its `src/methods.rs`.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use roboco_proto::{
    NativeAnswer, NativeChildCompletion, NativeChildControl, NativeChildOutcome, NativeChildStatus,
    NativeCommand, NativeConfiguration, NativeConversation, NativeGoal, NativeGoalCause,
    NativeGoalCompletion, NativeGoalPhase, NativeGoalRequirement, NativeMode, NativeModelChoice,
    NativePlanArtifact, NativePlanSection, NativePlanStatus, NativePlanSummary,
    NativeProviderChoice, NativeQuestion, NativeQuestionOption, NativeRequest, NativeRequestStatus,
    NativeSkill, NativeSubagentRun, NativeToolGroup, NativeToolKind, NativeToolLocation,
    NativeToolPreview, NativeToolResultState, NativeToolSemantic, NativeUsage, NativeUserRequest,
};

pub const PLUGIN_ID: &str = "sh.roboco.bridge";
pub const PROTOCOL_VERSION: u32 = 1;

/// The host interfaces this engine build understands. A bridge reporting any
/// other version is refused: the vocabularies below are pinned to these.
pub const HOST_CONTRACTS: &[(&str, &str)] = &[
    ("mimir:sessions/session-control", "8.0.0"),
    ("mimir:observations/session-observation", "7.0.0"),
    ("mimir:presentation/types", "3.0.0"),
    ("mimir:plugin-core/plugin-runtime", "3.0.0"),
    ("mimir:frontend/frontend", "1.0.0"),
];

/// Methods the engine calls. A bridge missing one is incompatible.
pub const REQUIRED_METHODS: &[&str] = &[
    "bridge.shutdown",
    "catalog",
    "session.create",
    "session.open",
    "session.close",
    "session.state",
    "session.open_view",
    "view.close",
    "session.read_entries",
    "session.read_entry_chunk",
    "session.commands",
    "session.command",
    "session.skills",
    "session.plan",
    "session.children",
    "session.child_outcome",
    "session.steer_child",
    "session.stop_child",
    "session.prompt",
    "session.steer",
    "session.configure",
    "session.decide_plan",
    "session.change_goal",
    "session.answer",
    "session.cancel_request",
];

/// Bridge error kinds (`error.data.kind`). The session kinds mirror the host's
/// own session errors; the rest are transport and bridge conditions.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    Parse,
    InvalidRequest,
    MethodNotFound,
    InvalidParams,
    Internal,
    Closed,
    NotReady,
    NotFound,
    Busy,
    Invalid,
    Cancelled,
    Failed,
    NotAttached,
    Overloaded,
    FrameTooLarge,
    NotInitialized,
    AlreadyInitialized,
    IncompatibleProtocol,
    ShuttingDown,
    UnknownView,
    UnknownAttachment,
    /// The bridge process ended before answering.
    Disconnected,
    /// A reply did not have the documented shape.
    Malformed,
}

impl ErrorKind {
    fn parse(kind: Option<&str>, code: i64) -> Self {
        match kind {
            Some("parse") => Self::Parse,
            Some("invalid_request") => Self::InvalidRequest,
            Some("method_not_found") => Self::MethodNotFound,
            Some("invalid_params") => Self::InvalidParams,
            Some("internal") => Self::Internal,
            Some("closed") => Self::Closed,
            Some("not_ready") => Self::NotReady,
            Some("not_found") => Self::NotFound,
            Some("busy") => Self::Busy,
            Some("invalid") => Self::Invalid,
            Some("cancelled") => Self::Cancelled,
            Some("failed") => Self::Failed,
            Some("not_attached") => Self::NotAttached,
            Some("overloaded") => Self::Overloaded,
            Some("frame_too_large") => Self::FrameTooLarge,
            Some("not_initialized") => Self::NotInitialized,
            Some("already_initialized") => Self::AlreadyInitialized,
            Some("incompatible_protocol") => Self::IncompatibleProtocol,
            Some("shutting_down") => Self::ShuttingDown,
            Some("unknown_view") => Self::UnknownView,
            Some("unknown_attachment") => Self::UnknownAttachment,
            _ => match code {
                -32700 => Self::Parse,
                -32600 => Self::InvalidRequest,
                -32601 => Self::MethodNotFound,
                -32602 => Self::InvalidParams,
                -32001 => Self::Closed,
                -32002 => Self::NotReady,
                -32003 => Self::NotFound,
                -32004 => Self::Busy,
                -32005 => Self::Invalid,
                -32006 => Self::Cancelled,
                -32007 => Self::Failed,
                -32010 => Self::NotAttached,
                -32011 => Self::Overloaded,
                -32012 => Self::FrameTooLarge,
                -32013 => Self::NotInitialized,
                -32014 => Self::AlreadyInitialized,
                -32015 => Self::IncompatibleProtocol,
                -32016 => Self::ShuttingDown,
                -32017 => Self::UnknownView,
                -32018 => Self::UnknownAttachment,
                _ => Self::Internal,
            },
        }
    }

    pub fn native(self) -> roboco_proto::NativeErrorKind {
        use roboco_proto::NativeErrorKind as K;
        match self {
            Self::Closed => K::Closed,
            Self::NotReady => K::NotReady,
            Self::NotFound => K::NotFound,
            Self::Busy => K::Busy,
            Self::Invalid | Self::InvalidParams => K::Invalid,
            Self::Cancelled => K::Cancelled,
            Self::NotAttached | Self::UnknownAttachment => K::NotAttached,
            Self::Disconnected | Self::ShuttingDown | Self::NotInitialized => K::Unavailable,
            _ => K::Failed,
        }
    }
}

#[derive(Debug, Clone, thiserror::Error)]
#[error("{message}")]
pub struct BridgeError {
    pub kind: ErrorKind,
    pub code: i64,
    pub message: String,
    pub data: Value,
}

impl BridgeError {
    pub fn disconnected(message: impl Into<String>) -> Self {
        Self {
            kind: ErrorKind::Disconnected,
            code: 0,
            message: message.into(),
            data: Value::Null,
        }
    }

    pub fn malformed(message: impl Into<String>) -> Self {
        Self {
            kind: ErrorKind::Malformed,
            code: 0,
            message: message.into(),
            data: Value::Null,
        }
    }

    pub(crate) fn from_wire(error: &Value) -> Self {
        let code = error.get("code").and_then(Value::as_i64).unwrap_or(0);
        let data = error.get("data").cloned().unwrap_or(Value::Null);
        Self {
            kind: ErrorKind::parse(data.get("kind").and_then(Value::as_str), code),
            code,
            message: error
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("bridge error")
                .to_owned(),
            data,
        }
    }
}

/// The negotiated `initialize` result the engine relies on.
#[derive(Debug, Clone, Deserialize)]
pub struct Hello {
    pub protocol_version: u32,
    pub bridge: BridgeIdentity,
    pub limits: Limits,
    pub host_contracts: std::collections::BTreeMap<String, String>,
    pub methods: Vec<String>,
    #[serde(default)]
    pub ready: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct BridgeIdentity {
    pub id: String,
    pub version: String,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize)]
pub struct Limits {
    pub max_frame_bytes: usize,
    pub chunk_bytes: usize,
}

impl Hello {
    /// Refuse a bridge built against other host contracts or missing a method.
    pub fn check(&self) -> Result<(), String> {
        if self.protocol_version != PROTOCOL_VERSION {
            return Err(format!(
                "bridge speaks protocol {}, this engine speaks {PROTOCOL_VERSION}",
                self.protocol_version
            ));
        }
        if self.bridge.id != PLUGIN_ID {
            return Err(format!("bridge reported plugin id {}", self.bridge.id));
        }
        for (name, version) in HOST_CONTRACTS {
            match self.host_contracts.get(*name) {
                Some(found) if found == version => {}
                Some(found) => {
                    return Err(format!(
                        "bridge uses {name}@{found}, this engine needs {name}@{version}"
                    ));
                }
                None => return Err(format!("bridge does not report {name}")),
            }
        }
        if let Some(missing) = REQUIRED_METHODS
            .iter()
            .find(|method| !self.methods.iter().any(|m| m == *method))
        {
            return Err(format!("bridge does not offer {missing}"));
        }
        if !self.ready {
            return Err("bridge reported it is not ready".into());
        }
        Ok(())
    }
}

/// Decode the SDK's `json-value` node graph into ordinary JSON.
pub fn decode_json_value(value: &Value) -> Option<Value> {
    let root = value.get("root")?.as_u64()? as usize;
    let nodes = value.get("nodes")?.as_array()?;
    fn node(nodes: &[Value], index: usize, depth: usize) -> Option<Value> {
        if depth > 256 {
            return None;
        }
        let entry = nodes.get(index)?;
        if entry.as_str() == Some("null") {
            return Some(Value::Null);
        }
        let (kind, inner) = entry.as_object()?.iter().next()?;
        Some(match kind.as_str() {
            "boolean" => Value::Bool(inner.as_bool()?),
            "signed" => Value::from(inner.as_i64()?),
            "unsigned" => Value::from(inner.as_u64()?),
            "float" => {
                serde_json::Number::from_f64(inner.as_f64()?).map_or(Value::Null, Value::Number)
            }
            "string_value" => Value::String(inner.as_str()?.to_owned()),
            "array" => Value::Array(
                inner
                    .as_array()?
                    .iter()
                    .map(|child| node(nodes, child.as_u64()? as usize, depth + 1))
                    .collect::<Option<_>>()?,
            ),
            "object" => {
                let mut map = serde_json::Map::new();
                for field in inner.as_array()? {
                    let key = field.get("key")?.as_str()?.to_owned();
                    let child = field.get("value")?.as_u64()? as usize;
                    map.insert(key, node(nodes, child, depth + 1)?);
                }
                Value::Object(map)
            }
            _ => return None,
        })
    }
    node(nodes, root, 0)
}

// ── host state ──────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Deserialize)]
pub struct SessionInfo {
    pub id: String,
    pub cwd: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub live: bool,
}

impl SessionInfo {
    pub fn native(&self) -> NativeConversation {
        NativeConversation {
            id: self.id.clone(),
            cwd: self.cwd.clone(),
            title: self.title.clone(),
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    Build,
    Plan,
}

impl From<Mode> for NativeMode {
    fn from(mode: Mode) -> Self {
        match mode {
            Mode::Build => NativeMode::Build,
            Mode::Plan => NativeMode::Plan,
        }
    }
}

impl From<NativeMode> for Mode {
    fn from(mode: NativeMode) -> Self {
        match mode {
            NativeMode::Build => Mode::Build,
            NativeMode::Plan => Mode::Plan,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct Configuration {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub reasoning: Option<String>,
    pub mode: Mode,
}

impl Configuration {
    pub fn native(&self) -> NativeConfiguration {
        NativeConfiguration {
            provider: self.provider.clone(),
            model: self.model.clone(),
            reasoning: self.reasoning.clone(),
            mode: self.mode.into(),
        }
    }
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct ConfigurationChange {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub reasoning: Option<String>,
    pub mode: Option<Mode>,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RequestStatus {
    Queued,
    Running,
    Completed,
    Cancelled,
    Failed,
    Interrupted,
}

impl From<RequestStatus> for NativeRequestStatus {
    fn from(status: RequestStatus) -> Self {
        match status {
            RequestStatus::Queued => Self::Queued,
            RequestStatus::Running => Self::Running,
            RequestStatus::Completed => Self::Completed,
            RequestStatus::Cancelled => Self::Cancelled,
            RequestStatus::Failed => Self::Failed,
            RequestStatus::Interrupted => Self::Interrupted,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct RequestInfo {
    pub id: String,
    pub status: RequestStatus,
    #[serde(default)]
    pub submission_key: Option<String>,
}

impl RequestInfo {
    pub fn native(&self) -> NativeRequest {
        NativeRequest {
            id: self.id.clone(),
            status: self.status.into(),
            submission_key: self.submission_key.clone(),
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlanStatus {
    ReviewPending,
    SavedStopped,
    Accepted,
    Implementing,
    Completed,
    Abandoned,
}

impl From<PlanStatus> for NativePlanStatus {
    fn from(status: PlanStatus) -> Self {
        match status {
            PlanStatus::ReviewPending => Self::ReviewPending,
            PlanStatus::SavedStopped => Self::SavedStopped,
            PlanStatus::Accepted => Self::Accepted,
            PlanStatus::Implementing => Self::Implementing,
            PlanStatus::Completed => Self::Completed,
            PlanStatus::Abandoned => Self::Abandoned,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct PlanSummary {
    pub id: String,
    pub name: String,
    pub status: PlanStatus,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PlanSection {
    pub title: String,
    pub body: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PlanArtifact {
    pub id: String,
    pub name: String,
    pub path: String,
    pub markdown: String,
    pub sections: Vec<PlanSection>,
    pub stages: Vec<PlanSection>,
    pub status: PlanStatus,
}

impl PlanArtifact {
    pub fn native(self) -> NativePlanArtifact {
        let section = |s: PlanSection| NativePlanSection {
            title: s.title,
            body: s.body,
        };
        NativePlanArtifact {
            id: self.id,
            name: self.name,
            path: self.path,
            markdown: self.markdown,
            sections: self.sections.into_iter().map(section).collect(),
            stages: self.stages.into_iter().map(section).collect(),
            status: self.status.into(),
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GoalPhase {
    Active,
    Paused,
    Blocked,
    Complete,
    Cleared,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GoalCause {
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

#[derive(Debug, Clone, Deserialize)]
pub struct GoalRequirement {
    pub requirement: String,
    pub evidence: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct GoalCompletion {
    pub checklist: Vec<GoalRequirement>,
    pub limitations: Option<String>,
    pub completed_at: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct GoalInfo {
    pub id: String,
    pub objective: String,
    pub phase: GoalPhase,
    pub cause: GoalCause,
    pub reason: Option<String>,
    pub review_gap: Option<String>,
    pub work_turns: u32,
    pub started_at: String,
    pub completion: Option<GoalCompletion>,
}

impl GoalInfo {
    pub fn native(&self) -> NativeGoal {
        NativeGoal {
            id: self.id.clone(),
            objective: self.objective.clone(),
            phase: match self.phase {
                GoalPhase::Active => NativeGoalPhase::Active,
                GoalPhase::Paused => NativeGoalPhase::Paused,
                GoalPhase::Blocked => NativeGoalPhase::Blocked,
                GoalPhase::Complete => NativeGoalPhase::Complete,
                GoalPhase::Cleared => NativeGoalPhase::Cleared,
            },
            cause: match self.cause {
                GoalCause::Started => NativeGoalCause::Started,
                GoalCause::Edited => NativeGoalCause::Edited,
                GoalCause::Resumed => NativeGoalCause::Resumed,
                GoalCause::NaturalContinuation => NativeGoalCause::NaturalContinuation,
                GoalCause::ReviewGap => NativeGoalCause::ReviewGap,
                GoalCause::ReviewAccepted => NativeGoalCause::ReviewAccepted,
                GoalCause::UserPaused => NativeGoalCause::UserPaused,
                GoalCause::UserInput => NativeGoalCause::UserInput,
                GoalCause::Manual => NativeGoalCause::Manual,
                GoalCause::TimeLimit => NativeGoalCause::TimeLimit,
                GoalCause::NoProgress => NativeGoalCause::NoProgress,
                GoalCause::RuntimeFailure => NativeGoalCause::RuntimeFailure,
                GoalCause::Restart => NativeGoalCause::Restart,
                GoalCause::Blocked => NativeGoalCause::Blocked,
                GoalCause::ReviewUnavailable => NativeGoalCause::ReviewUnavailable,
                GoalCause::Cleared => NativeGoalCause::Cleared,
            },
            reason: self.reason.clone(),
            review_gap: self.review_gap.clone(),
            work_turns: self.work_turns,
            started_at: self.started_at.clone(),
            completion: self.completion.as_ref().map(|c| NativeGoalCompletion {
                checklist: c
                    .checklist
                    .iter()
                    .map(|r| NativeGoalRequirement {
                        requirement: r.requirement.clone(),
                        evidence: r.evidence.clone(),
                    })
                    .collect(),
                limitations: c.limitations.clone(),
                completed_at: c.completed_at.clone(),
            }),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct QuestionOption {
    pub label: String,
    #[serde(default)]
    pub description: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Question {
    pub id: String,
    pub prompt: String,
    #[serde(default)]
    pub allow_multiple: bool,
    #[serde(default)]
    pub options: Vec<QuestionOption>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct UserRequest {
    pub id: String,
    pub questions: Vec<Question>,
}

impl UserRequest {
    pub fn native(&self) -> NativeUserRequest {
        NativeUserRequest {
            id: self.id.clone(),
            questions: self
                .questions
                .iter()
                .map(|q| NativeQuestion {
                    id: q.id.clone(),
                    prompt: q.prompt.clone(),
                    allow_multiple: q.allow_multiple,
                    options: q
                        .options
                        .iter()
                        .map(|o| NativeQuestionOption {
                            label: o.label.clone(),
                            description: o.description.clone(),
                        })
                        .collect(),
                })
                .collect(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Answer {
    pub question_id: String,
    pub selected_options: Vec<String>,
    pub freeform_text: Option<String>,
    pub none_of_above: bool,
}

impl From<&NativeAnswer> for Answer {
    fn from(answer: &NativeAnswer) -> Self {
        Self {
            question_id: answer.question_id.clone(),
            selected_options: answer.selected_options.clone(),
            freeform_text: answer.freeform_text.clone(),
            none_of_above: answer.none_of_above,
        }
    }
}

/// Host-confirmed conversation state, delivered whole when it changes.
#[derive(Debug, Clone, Deserialize)]
pub struct ViewState {
    pub info: SessionInfo,
    pub configuration: Configuration,
    pub active_request: Option<String>,
    #[serde(default)]
    pub requests: Vec<RequestInfo>,
    pub plan: Option<PlanSummary>,
    pub goal: Option<GoalInfo>,
    pub user_request: Option<UserRequest>,
}

impl ViewState {
    pub fn plan(&self) -> Option<NativePlanSummary> {
        self.plan.as_ref().map(|p| NativePlanSummary {
            id: p.id.clone(),
            name: p.name.clone(),
            status: p.status.into(),
        })
    }

    pub fn request(&self, id: &str) -> Option<&RequestInfo> {
        self.requests.iter().find(|r| r.id == id)
    }

    pub fn request_for_key(&self, key: &str) -> Option<&RequestInfo> {
        self.requests
            .iter()
            .find(|r| r.submission_key.as_deref() == Some(key))
    }
}

/// `create`/`open` result.
#[derive(Debug, Clone, Deserialize)]
pub struct Attached {
    pub session: String,
    pub state: ViewState,
    /// This bridge already held the attachment.
    #[serde(default)]
    pub reused: bool,
}

// ── journals and entries ────────────────────────────────────────────────────

#[derive(Debug, Clone, PartialEq, Eq, Hash, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum JournalRef {
    Root,
    Child(String),
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct JournalCut {
    pub journal: JournalRef,
    pub leaf: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum EntryAnchor {
    Latest,
    Before(String),
    After(String),
}

#[derive(Debug, Clone, Deserialize)]
pub struct EntryHeader {
    pub id: String,
    pub bytes: u64,
}

/// One page entry: a complete display entry, or the header of one too large
/// for the page whose exact bytes must be read in chunks.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PageEntry {
    Entry(Value),
    Oversized(EntryHeader),
}

#[derive(Debug, Clone, Deserialize)]
pub struct EntryPage {
    pub cut: JournalCut,
    pub entries: Vec<PageEntry>,
    #[serde(default)]
    pub bytes: u64,
    #[serde(default)]
    pub older: bool,
    #[serde(default)]
    pub newer: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct EntryChunk {
    pub id: String,
    pub offset: u64,
    pub total: u64,
    pub length: usize,
    pub encoding: String,
    pub data: String,
}

// ── children ────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RunStatus {
    Queued,
    Running,
    Completed,
    Failed,
    Deadline,
    Stopped,
    Interrupted,
}

impl From<RunStatus> for NativeChildStatus {
    fn from(status: RunStatus) -> Self {
        match status {
            RunStatus::Queued => Self::Queued,
            RunStatus::Running => Self::Running,
            RunStatus::Completed => Self::Completed,
            RunStatus::Failed => Self::Failed,
            RunStatus::Deadline => Self::Deadline,
            RunStatus::Stopped => Self::Stopped,
            RunStatus::Interrupted => Self::Interrupted,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct SubagentRun {
    pub attempt: Option<u32>,
    pub agent_id: String,
    pub agent: String,
    pub model: Option<String>,
    pub description: String,
    pub status: RunStatus,
    #[serde(default)]
    pub continued: bool,
    #[serde(default)]
    pub background: bool,
    #[serde(default)]
    pub turns: u32,
    pub timeout_ms: Option<u64>,
    pub remaining_ms: Option<u64>,
    #[serde(default)]
    pub phase: String,
    #[serde(default)]
    pub tool_uses: u32,
    #[serde(default)]
    pub tokens: u64,
    pub context_percent: Option<u8>,
    #[serde(default)]
    pub compactions: u32,
    #[serde(default)]
    pub elapsed_ms: u64,
}

impl SubagentRun {
    pub fn native(&self) -> NativeSubagentRun {
        NativeSubagentRun {
            attempt: self.attempt,
            agent_id: self.agent_id.clone(),
            agent: self.agent.clone(),
            model: self.model.clone(),
            description: self.description.clone(),
            status: self.status.into(),
            continued: self.continued,
            background: self.background,
            turns: self.turns,
            timeout_ms: self.timeout_ms,
            remaining_ms: self.remaining_ms,
            phase: self.phase.clone(),
            tool_uses: self.tool_uses,
            tokens: self.tokens,
            context_percent: self.context_percent,
            compactions: self.compactions,
            elapsed_ms: self.elapsed_ms,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct ChildInfo {
    pub handle: String,
    pub attempt: u32,
    pub profile: String,
    pub description: String,
    pub model: Option<String>,
    pub status: RunStatus,
    #[serde(default)]
    pub background: bool,
    pub spawned_by: Option<String>,
    pub completion_pending: bool,
    pub presentation: SubagentRun,
    pub cut: Option<JournalCut>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ChildHeader {
    pub handle: String,
    pub attempt: u32,
    pub status: RunStatus,
    pub completion_pending: bool,
    pub cut: Option<JournalCut>,
    pub bytes: u64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ViewChild {
    Child(Box<ChildInfo>),
    Oversized(ChildHeader),
}

impl ViewChild {
    pub fn handle(&self) -> &str {
        match self {
            Self::Child(child) => &child.handle,
            Self::Oversized(header) => &header.handle,
        }
    }

    pub fn cut(&self) -> Option<&JournalCut> {
        match self {
            Self::Child(child) => child.cut.as_ref(),
            Self::Oversized(header) => header.cut.as_ref(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct ChildOutcome {
    pub handle: String,
    pub attempt: u32,
    pub status: RunStatus,
    pub result: Option<String>,
    pub error: Option<String>,
    #[serde(default)]
    pub changed_files: Vec<String>,
    #[serde(default)]
    pub usage: Usage,
}

/// Token usage as the host reports it, with the provider's own record.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Usage {
    pub input_tokens: u64,
    pub output_tokens: u64,
    pub reasoning_tokens: u64,
    pub cache_read_tokens: u64,
    pub cache_write_tokens: u64,
    pub total_tokens: u64,
    /// `json-value`, decoded on use.
    pub provider_usage: Value,
}

impl Usage {
    pub fn native(&self) -> NativeUsage {
        NativeUsage {
            input_tokens: self.input_tokens,
            output_tokens: self.output_tokens,
            reasoning_tokens: self.reasoning_tokens,
            cache_read_tokens: self.cache_read_tokens,
            cache_write_tokens: self.cache_write_tokens,
            total_tokens: self.total_tokens,
            provider: decode_json_value(&self.provider_usage).filter(|v| !v.is_null()),
        }
    }
}

impl ChildOutcome {
    pub fn native(self) -> NativeChildOutcome {
        NativeChildOutcome {
            handle: self.handle,
            attempt: self.attempt,
            status: self.status.into(),
            result: self.result,
            error: self.error,
            changed_files: self.changed_files,
            usage: self.usage.native(),
        }
    }
}

/// `{"control": "accepted"}` or `{"control": {"attempt_changed": 2}}`.
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChildControl {
    Accepted,
    AttemptChanged(u32),
    Terminal,
    Finalizing,
}

impl From<ChildControl> for NativeChildControl {
    fn from(control: ChildControl) -> Self {
        match control {
            ChildControl::Accepted => Self::Accepted,
            ChildControl::AttemptChanged(attempt) => Self::AttemptChanged { attempt },
            ChildControl::Terminal => Self::Terminal,
            ChildControl::Finalizing => Self::Finalizing,
        }
    }
}

// ── catalogs ────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReasoningControl {
    Unsupported,
    Levels(Vec<String>),
}

#[derive(Debug, Clone, Deserialize)]
pub struct ModelChoice {
    pub id: String,
    pub name: String,
    pub reasoning: ReasoningControl,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ProviderChoice {
    pub id: String,
    pub name: String,
    pub models: Vec<ModelChoice>,
}

impl ProviderChoice {
    pub fn native(&self) -> NativeProviderChoice {
        NativeProviderChoice {
            id: self.id.clone(),
            name: self.name.clone(),
            models: self
                .models
                .iter()
                .map(|m| NativeModelChoice {
                    id: m.id.clone(),
                    name: m.name.clone(),
                    reasoning: match &m.reasoning {
                        ReasoningControl::Unsupported => Vec::new(),
                        ReasoningControl::Levels(levels) => levels.clone(),
                    },
                })
                .collect(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct Catalog {
    pub providers: Vec<ProviderChoice>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CommandArgument {
    None,
    Optional(String),
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Availability {
    IdleOnly,
    Any,
}

#[derive(Debug, Clone, Deserialize)]
pub struct CommandDescriptor {
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub aliases: Vec<String>,
    pub argument: CommandArgument,
    pub availability: Availability,
    #[serde(default)]
    pub order: i32,
}

impl CommandDescriptor {
    pub fn native(&self) -> NativeCommand {
        NativeCommand {
            name: self.name.clone(),
            description: self.description.clone(),
            aliases: self.aliases.clone(),
            argument: match &self.argument {
                CommandArgument::None => None,
                CommandArgument::Optional(hint) => Some(hint.clone()),
            },
            idle_only: self.availability == Availability::IdleOnly,
        }
    }

    pub fn answers_to(&self, name: &str) -> bool {
        self.name == name || self.aliases.iter().any(|alias| alias == name)
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct SkillDescription {
    pub name: String,
    #[serde(default)]
    pub description: String,
    pub path: Option<String>,
    #[serde(default)]
    pub bundled: bool,
}

impl SkillDescription {
    pub fn native(&self) -> NativeSkill {
        NativeSkill {
            name: self.name.clone(),
            description: self.description.clone(),
            path: self.path.clone(),
            bundled: self.bundled,
        }
    }
}

/// `session.command` result. Display is transient; submitted names the
/// already-admitted request, which a lost reply does not undo.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CommandResult {
    Handled,
    Display(String),
    Submitted(String),
}

// ── prompts ─────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Delivery {
    Start,
    FollowUp,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TextRange {
    pub start: u64,
    pub end: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillSelection {
    pub name: String,
    pub path: Option<String>,
    pub visible_text: String,
    pub text_range: Option<TextRange>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptImage {
    pub media_type: String,
    /// Base64 bytes.
    pub data: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptInput {
    pub text: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub images: Vec<PromptImage>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub skills: Vec<SkillSelection>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum GoalChange {
    Start {
        objective: String,
        duration_seconds: Option<u64>,
    },
    Edit(String),
    Pause,
    Resume(Option<u64>),
    Clear,
}

impl From<&roboco_proto::NativeGoalChange> for GoalChange {
    fn from(change: &roboco_proto::NativeGoalChange) -> Self {
        use roboco_proto::NativeGoalChange as C;
        match change {
            C::Start {
                objective,
                duration_seconds,
            } => Self::Start {
                objective: objective.clone(),
                duration_seconds: *duration_seconds,
            },
            C::Edit { objective } => Self::Edit(objective.clone()),
            C::Pause => Self::Pause,
            C::Resume { duration_seconds } => Self::Resume(*duration_seconds),
            C::Clear => Self::Clear,
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PlanDecision {
    Implement,
    ImplementFresh,
    SaveAndStop,
}

impl From<roboco_proto::NativePlanDecision> for PlanDecision {
    fn from(decision: roboco_proto::NativePlanDecision) -> Self {
        use roboco_proto::NativePlanDecision as D;
        match decision {
            D::Implement => Self::Implement,
            D::ImplementFresh => Self::ImplementFresh,
            D::SaveAndStop => Self::SaveAndStop,
        }
    }
}

// ── views ───────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Deserialize)]
pub struct ViewCheckpoint {
    pub cut: JournalCut,
    #[serde(default)]
    pub live_invocations: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ViewStart {
    pub state: ViewState,
    #[serde(default)]
    pub children: Vec<ViewChild>,
    pub root: ViewCheckpoint,
    #[serde(default)]
    pub recovering: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Source {
    #[serde(default)]
    pub agent_id: String,
    #[serde(default)]
    pub parent_agent_id: Option<String>,
    #[serde(default)]
    pub tool_call_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Observation {
    pub sequence: u64,
    pub source: Source,
    pub event: Event,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ViewObservation {
    /// The admitted request that root activity belongs to.
    pub request_id: Option<String>,
    /// The attempt of a native child's activity (`source.agent_id` is its handle).
    pub child_attempt: Option<u32>,
    pub observation: Observation,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Completion {
    pub request_id: String,
    pub stop_reason: String,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ViewItem {
    Start(ViewStart),
    Reset(ViewStart),
    Observation(Box<ViewObservation>),
    Checkpoint(ViewCheckpoint),
    State(Box<ViewState>),
    Child(ViewChild),
    Display(String),
    Completed(Completion),
}

#[derive(Debug, Clone, Deserialize)]
pub struct ViewEvent {
    pub position: u64,
    pub item: ViewItem,
}

/// What a view subscription delivers.
#[derive(Debug, Clone)]
pub enum ViewNote {
    Event(Box<ViewEvent>),
    /// The bridge could not serialize one event; the view continues.
    EventError {
        position: Option<u64>,
        message: String,
    },
    /// The view stream ended (session closed, state unreadable, bridge gone).
    Ended {
        reason: String,
    },
}

// ── observations ────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ThinkingKind {
    Hidden,
    Summary,
}

#[derive(Debug, Clone, Deserialize)]
pub struct IndexedValue {
    pub value: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ThinkingDelta {
    pub value: String,
    pub kind: ThinkingKind,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ToolKind {
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

impl From<&ToolKind> for NativeToolKind {
    fn from(kind: &ToolKind) -> Self {
        match kind {
            ToolKind::Generic => Self::Generic,
            ToolKind::Shell => Self::Shell,
            ToolKind::FileRead => Self::FileRead,
            ToolKind::ImageView => Self::ImageView,
            ToolKind::FileChange => Self::FileChange,
            ToolKind::FileContentSearch => Self::FileContentSearch,
            ToolKind::FilePathSearch => Self::FilePathSearch,
            ToolKind::Status => Self::Status,
            ToolKind::Plan => Self::Plan,
            ToolKind::Answer => Self::Answer,
            ToolKind::UserRequest => Self::UserRequest,
            ToolKind::Web => Self::Web,
            ToolKind::Agent => Self::Agent,
            ToolKind::Mcp => Self::Mcp,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolLocation {
    pub path: String,
    pub line: Option<u32>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolRowGroup {
    pub key: String,
    pub label: String,
    pub item: String,
}

impl ToolRowGroup {
    pub fn native(&self) -> NativeToolGroup {
        NativeToolGroup {
            key: self.key.clone(),
            label: self.label.clone(),
            item: self.item.clone(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolRowStyle {
    pub title: Option<String>,
    #[serde(default)]
    pub quiet: bool,
    pub group: Option<ToolRowGroup>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolPresentation {
    pub title: String,
    pub running_title: Option<String>,
    pub display_kind: ToolKind,
    pub summary: Option<String>,
    #[serde(default)]
    pub locations: Vec<ToolLocation>,
    pub result_state: ResultState,
    pub preview: Preview,
    pub subagent: Option<SubagentRun>,
    pub semantic_content: Option<SemanticContent>,
    #[serde(default)]
    pub quiet: bool,
    pub group: Option<ToolRowGroup>,
}

impl ToolPresentation {
    pub fn locations(&self) -> Vec<NativeToolLocation> {
        self.locations
            .iter()
            .map(|l| NativeToolLocation {
                path: l.path.clone(),
                line: l.line,
            })
            .collect()
    }
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ResultState {
    Normal,
    Empty,
    NoMatches,
}

impl From<ResultState> for NativeToolResultState {
    fn from(state: ResultState) -> Self {
        match state {
            ResultState::Normal => Self::Normal,
            ResultState::Empty => Self::Empty,
            ResultState::NoMatches => Self::NoMatches,
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Preview {
    Full,
    Compact,
}

impl From<Preview> for NativeToolPreview {
    fn from(preview: Preview) -> Self {
        match preview {
            Preview::Full => Self::Full,
            Preview::Compact => Self::Compact,
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SemanticContent {
    AnswerMarkdown,
    ReportMarkdown,
}

impl From<SemanticContent> for NativeToolSemantic {
    fn from(content: SemanticContent) -> Self {
        match content {
            SemanticContent::AnswerMarkdown => Self::AnswerMarkdown,
            SemanticContent::ReportMarkdown => Self::ReportMarkdown,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolStarted {
    pub name: String,
    pub id: String,
    pub invocation_id: String,
    pub input_json: String,
    pub presentation: ToolPresentation,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolFinished {
    pub name: String,
    pub id: String,
    pub input_json: String,
    /// Public output text. `model_output` is model-facing and never shown.
    pub output: String,
    pub is_error: bool,
    pub details_json: String,
    pub output_profile: Value,
    pub presentation: ToolPresentation,
    pub invocation_id: String,
    #[serde(default)]
    pub display_content: Vec<Value>,
    #[serde(default)]
    pub duration_ms: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolOutput {
    pub tool_call_id: String,
    #[serde(default)]
    pub output_stream: String,
    #[serde(default)]
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolProgress {
    pub id: String,
    pub text: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolStyled {
    pub id: String,
    pub style: ToolRowStyle,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolStatus {
    pub id: String,
    pub is_error: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct UserRequestReady {
    pub tool_call_id: String,
    pub request: UserRequest,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ApprovalChoice {
    pub label: String,
    #[serde(default)]
    pub description: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ApprovalQuestion {
    pub id: String,
    pub prompt: String,
    #[serde(default)]
    pub choices: Vec<ApprovalChoice>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolApproval {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub questions: Vec<ApprovalQuestion>,
}

impl ToolApproval {
    /// An approval is answered like a question: each choice is an option.
    pub fn native(&self) -> NativeUserRequest {
        NativeUserRequest {
            id: self.id.clone(),
            questions: self
                .questions
                .iter()
                .map(|q| NativeQuestion {
                    id: q.id.clone(),
                    prompt: q.prompt.clone(),
                    allow_multiple: false,
                    options: q
                        .choices
                        .iter()
                        .map(|c| NativeQuestionOption {
                            label: c.label.clone(),
                            description: c.description.clone(),
                        })
                        .collect(),
                })
                .collect(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct ContextSnapshot {
    #[serde(default)]
    pub used_prompt_tokens: u64,
    #[serde(default)]
    pub context_window: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ModelRetry {
    pub attempt: u32,
    pub max_attempts: u32,
    #[serde(default)]
    pub reason: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TextEvent {
    #[serde(alias = "message")]
    pub text: String,
}

/// The observation events the engine renders. Every other host event is
/// `Other`: diagnostics, accounting and model-facing projections.
#[derive(Debug, Clone)]
pub enum Event {
    TextDelta(IndexedValue),
    ThinkingDelta(ThinkingDelta),
    ToolStarted(Box<ToolStarted>),
    ToolFinished(Box<ToolFinished>),
    ToolCompletionStatus(ToolStatus),
    ToolOutputDelta(ToolOutput),
    ToolProgress(ToolProgress),
    ToolStyled(ToolStyled),
    ToolResultCompacted(Value),
    UserRequestReady(UserRequestReady),
    ToolApprovalRequested(ToolApproval),
    ToolApprovalSettled(String),
    InputAccepted(TextEvent),
    ContextSnapshot(ContextSnapshot),
    ModelRetryScheduled(ModelRetry),
    Status(TextEvent),
    RunError(TextEvent),
    Other(String),
}

impl<'de> Deserialize<'de> for Event {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        use serde::de::Error;
        let map = serde_json::Map::<String, Value>::deserialize(deserializer)?;
        let mut entries = map.into_iter();
        let (name, value) = entries
            .next()
            .ok_or_else(|| D::Error::custom("observation event without a variant"))?;
        fn typed<T: serde::de::DeserializeOwned, E: Error>(value: Value) -> Result<T, E> {
            serde_json::from_value(value).map_err(E::custom)
        }
        Ok(match name.as_str() {
            "text_delta" => Self::TextDelta(typed(value)?),
            "thinking_delta" => Self::ThinkingDelta(typed(value)?),
            "tool_started" => Self::ToolStarted(Box::new(typed(value)?)),
            "tool_finished" => Self::ToolFinished(Box::new(typed(value)?)),
            "tool_completion_status" => Self::ToolCompletionStatus(typed(value)?),
            "tool_output_delta" => Self::ToolOutputDelta(typed(value)?),
            "tool_progress" => Self::ToolProgress(typed(value)?),
            "tool_styled" => Self::ToolStyled(typed(value)?),
            "tool_result_compacted" => Self::ToolResultCompacted(value),
            "user_request_ready" => Self::UserRequestReady(typed(value)?),
            "tool_approval_requested" => Self::ToolApprovalRequested(typed(value)?),
            "tool_approval_settled" => Self::ToolApprovalSettled(typed(value)?),
            "input_accepted" => Self::InputAccepted(typed(value)?),
            "context_snapshot" => Self::ContextSnapshot(typed(value)?),
            "model_retry_scheduled" => Self::ModelRetryScheduled(typed(value)?),
            "status" => Self::Status(typed(value)?),
            "run_error" => Self::RunError(typed(value)?),
            _ => Self::Other(name),
        })
    }
}

// ── display entries ─────────────────────────────────────────────────────────

/// A saved public transcript entry, parsed from the host's `display-entry`.
#[derive(Debug, Clone, Deserialize)]
pub struct DisplayEntry {
    pub id: String,
    pub item: DisplayItem,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DisplayItem {
    Message(Message),
    SubagentCompletions(Vec<SubagentCompletion>),
    PlanLifecycle(PlanLifecycle),
    Compaction(Compaction),
    BranchSummary(String),
    PluginSnapshot(PluginSnapshot),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Message {
    User(UserMessage),
    Assistant(AssistantMessage),
    System(SystemMessage),
    ToolResult(Box<ToolResultMessage>),
}

#[derive(Debug, Clone, Deserialize)]
pub struct UserMessage {
    pub content: MessageContent,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssistantMessage {
    pub content: MessageContent,
    pub model: Option<String>,
    pub stop_reason: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SystemMessage {
    pub content: MessageContent,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolResultMessage {
    pub invocation_id: Option<String>,
    pub presentation: Option<ToolPresentation>,
    #[serde(default)]
    pub display_content: Vec<Value>,
    pub tool_call_id: String,
    pub tool_name: String,
    pub content: MessageContent,
    pub is_error: bool,
    /// The host's tool details (`json-value`), decoded on use.
    #[serde(default)]
    pub details: Value,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageContent {
    Text(String),
    Blocks(Vec<ContentBlock>),
}

impl MessageContent {
    pub fn blocks(&self) -> Vec<ContentBlock> {
        match self {
            Self::Text(text) => vec![ContentBlock::Text(TextBlock { text: text.clone() })],
            Self::Blocks(blocks) => blocks.clone(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct TextBlock {
    pub text: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolInvocation {
    pub id: String,
    pub start: ToolPresentation,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ToolCallBlock {
    pub invocation: Option<ToolInvocation>,
    pub id: String,
    pub name: String,
    /// `json-value` arguments, decoded on use.
    #[serde(default)]
    pub arguments: Value,
    pub raw_arguments: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ThinkingBlock {
    pub thinking: String,
    pub kind: ThinkingKind,
}

/// Content blocks. `model_context`, thinking signatures and redacted thinking
/// are model-only; the projection never shows them.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ContentBlock {
    Text(TextBlock),
    DisplayText(String),
    ModelContext(String),
    Image(Value),
    ToolCall(Box<ToolCallBlock>),
    Thinking(ThinkingBlock),
    RedactedThinking(String),
}

#[derive(Debug, Clone, Deserialize)]
pub struct SubagentCompletion {
    pub run: SubagentRun,
    #[serde(default)]
    pub changed_files: Vec<String>,
    #[serde(default)]
    pub omitted_changed_files: u64,
    #[serde(default)]
    pub result_preview: String,
    #[serde(default)]
    pub result_truncated: bool,
    #[serde(default)]
    pub usage: Option<Usage>,
}

impl SubagentCompletion {
    pub fn native(&self) -> NativeChildCompletion {
        NativeChildCompletion {
            handle: self.run.agent_id.clone(),
            attempt: self.run.attempt,
            status: self.run.status.into(),
            description: self.run.description.clone(),
            result_preview: self.result_preview.clone(),
            result_truncated: self.result_truncated,
            changed_files: self.changed_files.clone(),
            omitted_changed_files: self.omitted_changed_files,
            run: Some(self.run.native()),
            usage: self.usage.as_ref().map(Usage::native),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct PlanLifecycle {
    pub plan_id: String,
    pub name: String,
    pub status: PlanStatus,
}

#[derive(Debug, Clone, Deserialize)]
pub struct CompactionMeasurement {
    pub before_tokens: u64,
    pub after_tokens: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Compaction {
    pub trigger: String,
    pub measurement: Option<CompactionMeasurement>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PluginSnapshot {
    pub plugin_id: String,
    pub title: String,
    pub revision: u64,
    #[serde(default)]
    pub lines: Value,
    #[serde(default)]
    pub fallback: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn json_value_graphs_decode_to_plain_json() {
        let graph = json!({"root": 2, "nodes": [
            {"string_value": "note.txt"}, {"array": [0]}, {"object": [{"key": "paths", "value": 1}]}
        ]});
        assert_eq!(
            decode_json_value(&graph),
            Some(json!({"paths": ["note.txt"]}))
        );
        assert_eq!(
            decode_json_value(&json!({"root": 0, "nodes": ["null"]})),
            Some(Value::Null)
        );
        let cyclic = json!({"root": 0, "nodes": [{"array": [0]}]});
        assert_eq!(decode_json_value(&cyclic), None);
    }

    #[test]
    fn child_completions_and_outcomes_keep_usage_and_the_full_run() {
        let usage = json!({"input_tokens": 120, "output_tokens": 30, "reasoning_tokens": 7,
            "cache_read_tokens": 64, "cache_write_tokens": 2, "total_tokens": 150,
            "provider_usage": {"root": 1, "nodes": [{"unsigned": 150}, {"object": [{"key": "total", "value": 0}]}]}});
        let run = json!({"attempt": 1, "agent_id": "agent-1", "agent": "general-purpose", "model": "fake-model",
            "description": "Probe", "status": "completed", "continued": false, "background": false, "turns": 2,
            "timeout_ms": null, "remaining_ms": null, "phase": "done", "tool_uses": 1, "tokens": 150,
            "context_percent": 3, "compactions": 0, "elapsed_ms": 900});
        let item: DisplayItem = serde_json::from_value(json!({"subagent_completions": [{
            "run": run, "changed_files": [], "omitted_changed_files": 0, "result_preview": "child result",
            "result_truncated": false, "usage": usage}]}))
        .unwrap();
        let DisplayItem::SubagentCompletions(completions) = item else {
            panic!()
        };
        let native = completions[0].native();
        let used = native.usage.expect("usage is kept");
        assert_eq!(
            (used.input_tokens, used.output_tokens, used.total_tokens),
            (120, 30, 150)
        );
        assert_eq!(used.provider, Some(json!({"total": 150})));
        let run = native.run.expect("the full run is kept");
        assert_eq!((run.turns, run.tool_uses, run.elapsed_ms), (2, 1, 900));

        let outcome: ChildOutcome = serde_json::from_value(json!({"handle": "agent-1", "attempt": 1,
            "status": "completed", "result": "child result", "error": null, "changed_files": [], "usage": usage}))
        .unwrap();
        let native = outcome.native();
        assert_eq!(
            (
                native.usage.reasoning_tokens,
                native.usage.cache_read_tokens
            ),
            (7, 64)
        );
    }

    #[test]
    fn captured_view_items_parse() {
        let start: ViewEvent = serde_json::from_value(json!({"position": 1, "item": {"start": {
            "state": {"info": {"id": "s", "cwd": "/w", "title": null, "live": true},
                "configuration": {"provider": "fake", "model": "fake-model", "reasoning": null, "mode": "build"},
                "active_request": null, "requests": [], "plan": null, "goal": null, "user_request": null},
            "children": [], "root": {"cut": {"journal": "root", "leaf": "53f4"}, "live_invocations": []},
            "recovering": false}}}))
        .unwrap();
        let ViewItem::Start(start) = start.item else {
            panic!()
        };
        assert_eq!(start.root.cut.journal, JournalRef::Root);
        let child: ViewItem = serde_json::from_value(json!({"checkpoint": {
            "cut": {"journal": {"child": "agent-1"}, "leaf": "8270"}, "live_invocations": ["inv"]}}))
        .unwrap();
        let ViewItem::Checkpoint(checkpoint) = child else {
            panic!()
        };
        assert_eq!(checkpoint.cut.journal, JournalRef::Child("agent-1".into()));
        let observation: ViewItem = serde_json::from_value(json!({"observation": {
            "request_id": "r", "child_attempt": null,
            "observation": {"sequence": 3, "source": {"session_id": "s", "run_id": "x", "agent_id": "a",
                "parent_agent_id": null, "turn": 1, "model_attempt": 1, "tool_call_id": null},
                "event": {"text_delta": {"index": 0, "value": "hi"}}}}}))
        .unwrap();
        let ViewItem::Observation(observation) = observation else {
            panic!()
        };
        assert!(
            matches!(observation.observation.event, Event::TextDelta(ref d) if d.value == "hi")
        );
        let unknown: Event =
            serde_json::from_value(json!({"cache_decision": {"decision": "x"}})).unwrap();
        assert!(matches!(unknown, Event::Other(ref name) if name == "cache_decision"));
        let control: ChildControl = serde_json::from_value(json!({"attempt_changed": 2})).unwrap();
        assert!(matches!(control, ChildControl::AttemptChanged(2)));
        let accepted: ChildControl = serde_json::from_value(json!("accepted")).unwrap();
        assert!(matches!(accepted, ChildControl::Accepted));
    }

    #[test]
    fn mismatched_contracts_are_refused() {
        let mut hello: Hello = serde_json::from_value(json!({
            "protocol_version": 1,
            "bridge": {"id": PLUGIN_ID, "version": "0.1.0"},
            "limits": {"max_frame_bytes": 1048576, "chunk_bytes": 262144},
            "host_contracts": HOST_CONTRACTS.iter().map(|(k, v)| (k.to_string(), json!(v))).collect::<serde_json::Map<_, _>>(),
            "methods": REQUIRED_METHODS,
            "ready": true
        }))
        .unwrap();
        assert_eq!(hello.check(), Ok(()));
        hello
            .host_contracts
            .insert("mimir:sessions/session-control".into(), "9.0.0".into());
        assert!(hello.check().unwrap_err().contains("session-control@9.0.0"));
    }

    #[test]
    fn error_kinds_come_from_data_then_code() {
        let busy = BridgeError::from_wire(
            &json!({"code": -32004, "message": "owned", "data": {"kind": "busy"}}),
        );
        assert_eq!(busy.kind, ErrorKind::Busy);
        let by_code = BridgeError::from_wire(&json!({"code": -32010, "message": "x"}));
        assert_eq!(by_code.kind, ErrorKind::NotAttached);
    }
}
