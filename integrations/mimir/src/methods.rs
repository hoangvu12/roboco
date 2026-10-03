use crate::limits::{Limits, LimitsRequest};
use crate::wire::BridgeError;
use mimir_plugin_sdk::raw::{
    input::{Delivery, Image, Input, SkillSelection},
    session_control::{
        Answer, ChunkSelection, ConfigurationChange, EntryAnchor, EntrySelection, GoalChange,
        JournalCut, McpServer, PlanDecision, ViewDetail,
    },
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::{Deserialize, de::DeserializeOwned};
use serde_json::value::RawValue;

pub const INITIALIZE: &str = "initialize";

/// Every method the bridge dispatches once initialized, in documentation order.
pub const METHODS: &[&str] = &[
    "initialize",
    "bridge.status",
    "bridge.shutdown",
    "catalog",
    "session.list",
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
    "session.cancel_all",
    "session.attach_mcp",
    "session.detach_mcp",
];

/// The host interface versions this bridge was built against. `build.py`
/// checks these against the component's actual imports.
pub const HOST_CONTRACTS: &[(&str, &str)] = &[
    ("mimir:sessions/session-control", "8.0.0"),
    ("mimir:observations/session-observation", "7.0.0"),
    ("mimir:presentation/types", "3.0.0"),
    ("mimir:plugin-core/plugin-runtime", "3.0.0"),
    ("mimir:frontend/frontend", "1.0.0"),
];

const DEFAULT_PAGE_ENTRIES: u32 = 256;
const DEFAULT_PAGE_BYTES: u32 = 512 << 10;

#[derive(Debug, Deserialize)]
pub struct ClientInfo {
    pub name: String,
    #[serde(default)]
    pub version: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InitializeParams {
    pub protocol_versions: Vec<u32>,
    #[serde(default)]
    pub client: Option<ClientInfo>,
    #[serde(default)]
    pub limits: Option<LimitsRequest>,
}

pub enum Call {
    Initialize(InitializeParams),
    Status,
    Shutdown,
    Catalog { cwd: String },
    List { cwd: Option<String>, cursor: Option<String> },
    Create { cwd: String, configuration: ConfigurationChange },
    Open { session: String },
    Close { session: String },
    State { session: String },
    OpenView { session: String, detail: ViewDetail },
    CloseView { view: String },
    ReadEntries { session: String, selection: EntrySelection },
    ReadEntryChunk { session: String, selection: ChunkSelection },
    Commands { session: String },
    Command { session: String, name: String, tail: String },
    Skills { session: String },
    Plan { session: String },
    Children { session: String },
    ChildOutcome { session: String, handle: String, attempt: u32 },
    SteerChild { session: String, handle: String, attempt: u32, text: String },
    StopChild { session: String, handle: String, attempt: u32 },
    Prompt { session: String, input: Input, delivery: Delivery, submission_key: Option<String> },
    Steer { session: String, text: String },
    Configure { session: String, change: ConfigurationChange },
    DecidePlan { session: String, plan_id: String, decision: PlanDecision },
    ChangeGoal { session: String, change: GoalChange },
    Answer { session: String, request_id: String, answers: Vec<Answer> },
    CancelRequest { session: String, request_id: String },
    CancelAll { session: String },
    AttachMcp { session: String, servers: Vec<McpServer> },
    DetachMcp { session: String, attachment: String },
}

fn object<T: DeserializeOwned>(params: Option<&RawValue>) -> Result<T, BridgeError> {
    let text = params.map_or("{}", RawValue::get);
    if !text.starts_with('{') {
        return Err(BridgeError::invalid_params("params must be an object"));
    }
    serde_json::from_str(text).map_err(|error| BridgeError::invalid_params(error.to_string()))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Empty {}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct OnlySession {
    session: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CatalogParams {
    cwd: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ListParams {
    #[serde(default)]
    cwd: Option<String>,
    #[serde(default)]
    cursor: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CreateParams {
    cwd: String,
    #[serde(default)]
    configuration: Option<ConfigurationChange>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct OpenParams {
    session: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct OpenViewParams {
    session: String,
    #[serde(default)]
    detail: Option<ViewDetail>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ViewParams {
    view: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ReadEntriesParams {
    session: String,
    cut: JournalCut,
    #[serde(default)]
    anchor: Option<EntryAnchor>,
    #[serde(default)]
    max_entries: Option<u32>,
    #[serde(default)]
    max_bytes: Option<u32>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ReadChunkParams {
    session: String,
    cut: JournalCut,
    id: String,
    #[serde(default)]
    offset: u64,
    #[serde(default)]
    max_bytes: Option<u32>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CommandParams {
    session: String,
    name: String,
    #[serde(default)]
    tail: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SteerChildParams {
    session: String,
    handle: String,
    attempt: u32,
    text: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct AttemptParams {
    session: String,
    handle: String,
    attempt: u32,
}

/// Prompt input with image bytes as base64 rather than the SDK's number array,
/// which would expand every byte to as many as four.
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireInput {
    text: String,
    #[serde(default)]
    images: Vec<WireImage>,
    #[serde(default)]
    skills: Vec<SkillSelection>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireImage {
    media_type: String,
    data: String,
}

impl WireInput {
    fn into_input(self) -> Result<Input, BridgeError> {
        let images = self
            .images
            .into_iter()
            .enumerate()
            .map(|(index, image)| {
                let data = STANDARD.decode(&image.data).map_err(|error| {
                    BridgeError::invalid_params(format!("images[{index}].data is not base64: {error}"))
                })?;
                Ok(Image { media_type: image.media_type, data })
            })
            .collect::<Result<_, BridgeError>>()?;
        Ok(Input { text: self.text, images, skills: self.skills })
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PromptParams {
    session: String,
    input: WireInput,
    delivery: Delivery,
    #[serde(default)]
    submission_key: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SteerParams {
    session: String,
    text: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ConfigureParams {
    session: String,
    change: ConfigurationChange,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct DecidePlanParams {
    session: String,
    plan_id: String,
    decision: PlanDecision,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ChangeGoalParams {
    session: String,
    change: GoalChange,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct AnswerParams {
    session: String,
    request_id: String,
    answers: Vec<Answer>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RequestParams {
    session: String,
    request_id: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct AttachMcpParams {
    session: String,
    servers: Vec<McpServer>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct DetachMcpParams {
    session: String,
    attachment: String,
}

fn at_least_one(name: &str, value: Option<u32>, default: u32) -> Result<u32, BridgeError> {
    match value {
        Some(0) => Err(BridgeError::invalid_params(format!("{name} must be at least 1"))),
        Some(value) => Ok(value),
        None => Ok(default),
    }
}

/// Validates `params` for `method` and clamps size requests to `limits`.
/// Nothing here reaches the host; an unknown method is never a prompt.
pub fn parse_call(
    method: &str,
    params: Option<&RawValue>,
    limits: &Limits,
) -> Result<Call, BridgeError> {
    Ok(match method {
        INITIALIZE => Call::Initialize(object(params)?),
        "bridge.status" => {
            object::<Empty>(params)?;
            Call::Status
        }
        "bridge.shutdown" => {
            object::<Empty>(params)?;
            Call::Shutdown
        }
        "catalog" => Call::Catalog { cwd: object::<CatalogParams>(params)?.cwd },
        "session.list" => {
            let p: ListParams = object(params)?;
            Call::List { cwd: p.cwd, cursor: p.cursor }
        }
        "session.create" => {
            let p: CreateParams = object(params)?;
            Call::Create {
                cwd: p.cwd,
                configuration: p.configuration.unwrap_or(ConfigurationChange {
                    provider: None,
                    model: None,
                    reasoning: None,
                    mode: None,
                }),
            }
        }
        "session.open" => Call::Open { session: object::<OpenParams>(params)?.session },
        "session.close" => Call::Close { session: object::<OnlySession>(params)?.session },
        "session.state" => Call::State { session: object::<OnlySession>(params)?.session },
        "session.open_view" => {
            let p: OpenViewParams = object(params)?;
            Call::OpenView { session: p.session, detail: p.detail.unwrap_or(ViewDetail::Full) }
        }
        "view.close" => Call::CloseView { view: object::<ViewParams>(params)?.view },
        "session.read_entries" => {
            let p: ReadEntriesParams = object(params)?;
            let entries = at_least_one("max_entries", p.max_entries, DEFAULT_PAGE_ENTRIES)?;
            let bytes = at_least_one("max_bytes", p.max_bytes, DEFAULT_PAGE_BYTES)?;
            Call::ReadEntries {
                session: p.session,
                selection: EntrySelection {
                    cut: p.cut,
                    anchor: p.anchor.unwrap_or(EntryAnchor::Latest),
                    max_entries: limits.page_entries(entries),
                    max_bytes: limits.page_bytes(bytes),
                },
            }
        }
        "session.read_entry_chunk" => {
            let p: ReadChunkParams = object(params)?;
            let bytes = at_least_one("max_bytes", p.max_bytes, u32::MAX)?;
            Call::ReadEntryChunk {
                session: p.session,
                selection: ChunkSelection {
                    cut: p.cut,
                    id: p.id,
                    offset: p.offset,
                    max_bytes: limits.chunk_request(bytes),
                },
            }
        }
        "session.commands" => Call::Commands { session: object::<OnlySession>(params)?.session },
        "session.command" => {
            let p: CommandParams = object(params)?;
            Call::Command { session: p.session, name: p.name, tail: p.tail }
        }
        "session.skills" => Call::Skills { session: object::<OnlySession>(params)?.session },
        "session.plan" => Call::Plan { session: object::<OnlySession>(params)?.session },
        "session.children" => Call::Children { session: object::<OnlySession>(params)?.session },
        "session.child_outcome" => {
            let p: AttemptParams = object(params)?;
            Call::ChildOutcome { session: p.session, handle: p.handle, attempt: p.attempt }
        }
        "session.steer_child" => {
            let p: SteerChildParams = object(params)?;
            Call::SteerChild { session: p.session, handle: p.handle, attempt: p.attempt, text: p.text }
        }
        "session.stop_child" => {
            let p: AttemptParams = object(params)?;
            Call::StopChild { session: p.session, handle: p.handle, attempt: p.attempt }
        }
        "session.prompt" => {
            let p: PromptParams = object(params)?;
            Call::Prompt {
                session: p.session,
                input: p.input.into_input()?,
                delivery: p.delivery,
                submission_key: p.submission_key,
            }
        }
        "session.steer" => {
            let p: SteerParams = object(params)?;
            Call::Steer { session: p.session, text: p.text }
        }
        "session.configure" => {
            let p: ConfigureParams = object(params)?;
            Call::Configure { session: p.session, change: p.change }
        }
        "session.decide_plan" => {
            let p: DecidePlanParams = object(params)?;
            Call::DecidePlan { session: p.session, plan_id: p.plan_id, decision: p.decision }
        }
        "session.change_goal" => {
            let p: ChangeGoalParams = object(params)?;
            Call::ChangeGoal { session: p.session, change: p.change }
        }
        "session.answer" => {
            let p: AnswerParams = object(params)?;
            Call::Answer { session: p.session, request_id: p.request_id, answers: p.answers }
        }
        "session.cancel_request" => {
            let p: RequestParams = object(params)?;
            Call::CancelRequest { session: p.session, request_id: p.request_id }
        }
        "session.cancel_all" => Call::CancelAll { session: object::<OnlySession>(params)?.session },
        "session.attach_mcp" => {
            let p: AttachMcpParams = object(params)?;
            Call::AttachMcp { session: p.session, servers: p.servers }
        }
        "session.detach_mcp" => {
            let p: DetachMcpParams = object(params)?;
            Call::DetachMcp { session: p.session, attachment: p.attachment }
        }
        "session.notify" | "session.context_snapshot" => {
            return Err(BridgeError::method_not_found(method).with_data(
                "reason",
                "model-only context is never exposed through the bridge".into(),
            ));
        }
        _ => return Err(BridgeError::method_not_found(method)),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::code;
    use serde_json::{Value, json, value::to_raw_value};

    fn call(method: &str, params: Value) -> Result<Call, BridgeError> {
        let raw = to_raw_value(&params).unwrap();
        parse_call(method, Some(&raw), &Limits::default())
    }

    fn cut() -> Value {
        json!({"journal": "root", "leaf": null})
    }

    fn minimal(method: &str) -> Value {
        let session = json!("s");
        match method {
            "initialize" => json!({"protocol_versions": [1]}),
            "bridge.status" | "bridge.shutdown" => json!({}),
            "catalog" => json!({"cwd": "/tmp"}),
            "session.list" => json!({}),
            "session.create" => json!({"cwd": "/tmp"}),
            "session.open" | "session.close" | "session.state" | "session.commands"
            | "session.skills" | "session.plan" | "session.children" | "session.cancel_all" => {
                json!({"session": session})
            }
            "session.open_view" => json!({"session": session}),
            "view.close" => json!({"view": "v1"}),
            "session.read_entries" => json!({"session": session, "cut": cut()}),
            "session.read_entry_chunk" => json!({"session": session, "cut": cut(), "id": "e"}),
            "session.command" => json!({"session": session, "name": "n"}),
            "session.child_outcome" => json!({"session": session, "handle": "h", "attempt": 1}),
            "session.steer_child" => json!({"session": session, "handle": "h", "attempt": 1, "text": "t"}),
            "session.stop_child" => json!({"session": session, "handle": "h", "attempt": 1}),
            "session.prompt" => json!({"session": session, "input": {"text": "hi"}, "delivery": "start"}),
            "session.steer" => json!({"session": session, "text": "t"}),
            "session.configure" => json!({"session": session, "change": {}}),
            "session.decide_plan" => json!({"session": session, "plan_id": "p", "decision": "implement"}),
            "session.change_goal" => json!({"session": session, "change": "pause"}),
            "session.answer" => json!({"session": session, "request_id": "r", "answers": []}),
            "session.cancel_request" => json!({"session": session, "request_id": "r"}),
            "session.attach_mcp" => json!({"session": session, "servers": []}),
            "session.detach_mcp" => json!({"session": session, "attachment": "a"}),
            other => panic!("no sample for {other}"),
        }
    }

    #[test]
    fn every_listed_method_parses_with_minimal_params() {
        for method in METHODS {
            match call(method, minimal(method)) {
                Ok(_) => {}
                Err(error) => panic!("{method}: {error:?}"),
            }
        }
    }

    #[test]
    fn unknown_methods_are_method_not_found_never_prompts() {
        for method in ["", "prompt", "session.nope", "session.Prompt", "session.prompt ", "rpc.discover"] {
            let Err(error) = call(method, json!({})) else { panic!("{method:?} parsed") };
            assert_eq!(error.code, code::METHOD_NOT_FOUND, "{method:?}");
        }
    }

    #[test]
    fn model_only_context_methods_are_withheld_with_a_reason() {
        for method in ["session.notify", "session.context_snapshot"] {
            let Err(error) = call(method, json!({"session": "s"})) else { panic!() };
            assert_eq!(error.code, code::METHOD_NOT_FOUND);
            assert!(error.data["reason"].as_str().unwrap().contains("model-only"));
            assert!(!METHODS.contains(&method));
        }
    }

    #[test]
    fn params_must_be_an_object_with_known_fields() {
        for bad in ["[]", "[\"s\"]", "1", "\"s\"", "null", "true"] {
            let raw = RawValue::from_string(bad.into()).unwrap();
            let Err(error) = parse_call("session.state", Some(&raw), &Limits::default()) else { panic!("{bad}") };
            assert_eq!(error.code, code::INVALID_PARAMS, "{bad}");
        }
        let Err(error) = call("session.state", json!({"session": "s", "extra": 1})) else { panic!() };
        assert_eq!(error.code, code::INVALID_PARAMS);
        let Err(error) = call("session.state", json!({"session": 5})) else { panic!() };
        assert_eq!(error.code, code::INVALID_PARAMS);
        let Err(error) = call("session.state", json!({})) else { panic!() };
        assert_eq!(error.code, code::INVALID_PARAMS);
        assert!(parse_call("bridge.status", None, &Limits::default()).is_ok());
    }

    #[test]
    fn page_requests_are_clamped_to_the_negotiated_frame() {
        let Ok(Call::ReadEntries { selection, .. }) = call(
            "session.read_entries",
            json!({"session": "s", "cut": cut(), "max_entries": 100000, "max_bytes": 4194304}),
        ) else { panic!() };
        assert_eq!(selection.max_entries, 4096);
        assert_eq!(selection.max_bytes as usize, Limits::default().max_frame_bytes / 4 * 3);
        let Ok(Call::ReadEntries { selection, .. }) =
            call("session.read_entries", json!({"session": "s", "cut": cut()}))
        else { panic!() };
        assert_eq!((selection.max_entries, selection.max_bytes), (256, 512 << 10));
        assert!(matches!(selection.anchor, EntryAnchor::Latest));
        for zero in [json!({"max_entries": 0}), json!({"max_bytes": 0})] {
            let mut params = json!({"session": "s", "cut": cut()});
            params.as_object_mut().unwrap().extend(zero.as_object().unwrap().clone());
            assert_eq!(call("session.read_entries", params).err().unwrap().code, code::INVALID_PARAMS);
        }
    }

    #[test]
    fn chunk_requests_never_ask_for_more_than_the_chunk_budget() {
        let Ok(Call::ReadEntryChunk { selection, .. }) = call(
            "session.read_entry_chunk",
            json!({"session": "s", "cut": cut(), "id": "e", "offset": 7, "max_bytes": 4194304}),
        ) else { panic!() };
        assert_eq!(selection.max_bytes as usize, Limits::default().chunk_bytes);
        assert_eq!(selection.offset, 7);
        let Ok(Call::ReadEntryChunk { selection, .. }) =
            call("session.read_entry_chunk", json!({"session": "s", "cut": cut(), "id": "e"}))
        else { panic!() };
        assert_eq!(selection.max_bytes as usize, Limits::default().chunk_bytes);
    }

    #[test]
    fn prompt_carries_the_submission_key_and_the_sdk_shapes() {
        let Ok(Call::Prompt { submission_key, delivery, .. }) = call(
            "session.prompt",
            json!({"session": "s", "input": {"text": "hi", "images": [{"media_type": "image/png", "data": "iVBORw0="}]}, "delivery": "follow_up", "submission_key": "k-1"}),
        ) else { panic!() };
        assert_eq!(submission_key.as_deref(), Some("k-1"));
        assert!(matches!(delivery, Delivery::FollowUp));
        let Err(error) = call(
            "session.prompt",
            json!({"session": "s", "input": {"text": "hi"}, "delivery": "later"}),
        ) else { panic!() };
        assert_eq!(error.code, code::INVALID_PARAMS);
        let Err(error) = call(
            "session.prompt",
            json!({"session": "s", "input": {"text": "hi", "images": [{"media_type": "image/png", "data": "***"}]}, "delivery": "start"}),
        ) else { panic!() };
        assert!(error.message.contains("images[0]"));
    }

    #[test]
    fn goal_changes_use_the_sdk_variants() {
        for change in [
            json!({"start": {"objective": "ship", "duration_seconds": null}}),
            json!({"edit": "new"}),
            json!("pause"),
            json!({"resume": 60}),
            json!("clear"),
        ] {
            assert!(call("session.change_goal", json!({"session": "s", "change": change})).is_ok());
        }
    }

    #[test]
    fn method_list_has_no_duplicates() {
        let mut seen = std::collections::HashSet::new();
        for method in METHODS {
            assert!(seen.insert(method), "{method} listed twice");
        }
    }
}
