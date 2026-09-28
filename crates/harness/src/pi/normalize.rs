//! Pure mapping from pi RPC events onto roboco's [`AgentEvent`] model.
//!
//! Sources: the pi RPC protocol digest (`~/roboco-notes/wave2-notes/`,
//! distilled from `pi.dev/docs/latest/rpc` and the installed
//! `@earendil-works/pi-coding-agent` 0.87.0's `dist/modes/json-event.js`,
//! `dist/core/agent-session.d.ts`, and `dist/core/tools/*.d.ts`).
//!
//! Notable shape decisions:
//! - `message_update` carries streaming deltas (`text_delta`/`thinking_delta`);
//!   `message_end` is the authoritative final message. Providers that do not
//!   stream emit only `*_start`/`*_end`, so the `content` on `text_end` /
//!   `thinking_end` (and the message's content blocks) are fallbacks keyed by
//!   `contentIndex` — a streamed block is never double-emitted.
//! - `toolcall_start/delta/end` (the assistant-message deltas) are folded into
//!   the `tool_execution_*` mapping: pi emits tool_execution frames for every
//!   actual execution, keyed by the same toolCallId, with the full args.
//! - `tool_execution_update` is dropped: its `partialResult` is cumulative
//!   (replace, not append) and tool output is journal-only in roboco — an
//!   early ToolResult would resolve the chip while the tool still runs.
//! - Extension UI dialogs (`select`/`confirm`/`input`/`editor`) map onto
//!   roboco's question flow; fire-and-forget methods (`notify`, `setStatus`,
//!   …) are tolerated without events.

use serde_json::{Value, json};

use roboco_proto::{
    AgentEvent, ReasoningLevel, ToolCall, ToolDiff, UserInputAnswer, UserInputQuestion,
};

use crate::acp::normalize::{OUTPUT_CAP, cap_text};

/// Roboco's tool-output cap parity with the ACP path (`16 KiB`).
pub(crate) fn output_text(content: &Value) -> Option<String> {
    // AgentToolResult.content is [{type:"text",text}, …] (ImageContent is not
    // text); tolerate a bare string for robustness.
    let text = match content {
        Value::String(text) => Some(text.clone()),
        Value::Array(blocks) => {
            let mut joined = String::new();
            for block in blocks {
                if let Some(text) = block.get("text").and_then(Value::as_str) {
                    if !joined.is_empty() {
                        joined.push('\n');
                    }
                    joined.push_str(text);
                }
            }
            (!joined.is_empty()).then_some(joined)
        }
        _ => None,
    };
    text.map(|text| cap_text(&text, OUTPUT_CAP))
}

/// A unified patch-style diff from a tool result's `details` (`patch` is the
/// standard unified form pi's edit tool reports; `diff` is display-oriented).
pub(crate) fn tool_diff(result: &Value) -> Option<ToolDiff> {
    for key in ["patch", "diff"] {
        let Some(text) = result.pointer(&format!("/details/{key}")).and_then(Value::as_str) else {
            continue;
        };
        // The patch names the file; old_text is unknowable from a patch, so
        // the pair renders as an add/replace (old absent → new file in the
        // doc's diff view).
        let path = patch_file(text).unwrap_or_default();
        if path.is_empty() {
            continue;
        }
        return Some(ToolDiff {
            path,
            old_text: None,
            new_text: text.to_owned(),
        });
    }
    None
}

/// First file named by a unified patch header (`+++ b/path`).
fn patch_file(text: &str) -> Option<String> {
    let line = text.lines().find(|line| line.starts_with("+++ "))?;
    let path = line[4..].trim().trim_start_matches("b/").to_owned();
    (!path.is_empty()).then_some(path)
}

fn arg_str(args: &Value, key: &str) -> Option<String> {
    args.get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
}

/// Type a pi tool call by its name and arguments (schemas verified against
/// `dist/core/tools/*.d.ts`: read `{path}`, edit
/// `{path, edits:[{oldText,newText}]}`, write `{path, content}`, find
/// `{pattern, path?}`, grep `{pattern, path?, glob?}`, ls `{path?}`,
/// bash/powershell `{command}`). Unknown/extension tools stay `Unknown`
/// with their raw input so the chip can still name them.
pub(crate) fn typed_call(tool_name: &str, args: &Value) -> ToolCall {
    match tool_name {
        "bash" | "powershell" => ToolCall::Exec {
            command: arg_str(args, "command").unwrap_or_default(),
        },
        "read" => ToolCall::ReadFile {
            path: arg_str(args, "path").unwrap_or_default(),
        },
        "write" => ToolCall::WriteFile {
            path: arg_str(args, "path").unwrap_or_default(),
            content: arg_str(args, "content"),
        },
        "edit" => {
            let (old_string, new_string) = args
                .get("edits")
                .and_then(Value::as_array)
                .and_then(|edits| edits.first())
                .map(|edit| {
                    (
                        arg_str(edit, "oldText"),
                        arg_str(edit, "newText"),
                    )
                })
                .unwrap_or((None, None));
            ToolCall::EditFile {
                path: arg_str(args, "path").unwrap_or_default(),
                old_string,
                new_string,
            }
        }
        "grep" => ToolCall::Search {
            pattern: arg_str(args, "pattern").unwrap_or_default(),
            path: arg_str(args, "path"),
        },
        "find" => ToolCall::Glob {
            pattern: arg_str(args, "pattern").unwrap_or_default(),
        },
        "ls" | _ => ToolCall::Unknown {
            name: tool_name.to_owned(),
            input: (!args.is_null()).then(|| args.clone()),
        },
    }
}

/// Maps `message_update.assistantMessageEvent` deltas to events. `tracker`
/// remembers which content indices already streamed so a non-streaming
/// provider's `*_end`/`message_end` fallback emits each block exactly once.
pub(crate) struct DeltaTracker {
    streamed: std::collections::HashSet<u64>,
}

impl DeltaTracker {
    pub fn new() -> Self {
        Self {
            streamed: std::collections::HashSet::new(),
        }
    }

    pub fn reset(&mut self) {
        self.streamed.clear();
    }

    fn mark(&mut self, index: &Value) -> u64 {
        let index = index.as_u64().unwrap_or(0);
        self.streamed.insert(index);
        index
    }

    fn streamed_contains(&self, index: &Value) -> bool {
        self.streamed.contains(&index.as_u64().unwrap_or(0))
    }
}

impl Default for DeltaTracker {
    fn default() -> Self {
        Self::new()
    }
}

pub(crate) fn message_update_events(event: &Value, tracker: &mut DeltaTracker) -> Vec<AgentEvent> {
    let Some(delta) = event.get("assistantMessageEvent") else {
        return Vec::new();
    };
    let kind = delta.get("type").and_then(Value::as_str).unwrap_or("");
    let index = delta.get("contentIndex").cloned().unwrap_or(Value::Null);
    match kind {
        "text_delta" => {
            tracker.mark(&index);
            delta
                .get("delta")
                .and_then(Value::as_str)
                .filter(|text| !text.is_empty())
                .map(|text| AgentEvent::TextDelta { text: text.into() })
                .into_iter()
                .collect()
        }
        "thinking_delta" => {
            tracker.mark(&index);
            delta
                .get("delta")
                .and_then(Value::as_str)
                .filter(|text| !text.is_empty())
                .map(|text| AgentEvent::ReasoningDelta { text: text.into() })
                .into_iter()
                .collect()
        }
        "text_end" | "thinking_end" => {
            let content = delta.get("content").and_then(Value::as_str).unwrap_or("");
            if content.is_empty() || tracker.streamed_contains(&index) {
                return Vec::new();
            }
            let event = if kind == "text_end" {
                AgentEvent::TextDelta {
                    text: content.into(),
                }
            } else {
                AgentEvent::ReasoningDelta {
                    text: content.into(),
                }
            };
            tracker.mark(&index);
            vec![event]
        }
        // toolcall_start/delta/end carry the same toolCallId the
        // tool_execution_* frames restate with full structure.
        _ => Vec::new(),
    }
}

/// The final message: content blocks never streamed emit once (the
/// non-streaming provider fallback), and an assistant `stopReason: "error"`
/// reports its `errorMessage`.
pub(crate) fn message_end_events(event: &Value, tracker: &mut DeltaTracker) -> Vec<AgentEvent> {
    let message = event.get("message").unwrap_or(&Value::Null);
    let mut events = Vec::new();
    let is_assistant = message.get("role").and_then(Value::as_str) == Some("assistant");
    if is_assistant {
        if let Some(blocks) = message.get("content").and_then(Value::as_array) {
            for (index, block) in blocks.iter().enumerate() {
                let index = Value::from(index as u64);
                if tracker.streamed_contains(&index) {
                    continue;
                }
                let text = block.get("text").and_then(Value::as_str).unwrap_or("");
                if text.is_empty() {
                    continue;
                }
                tracker.mark(&index);
                events.push(match block.get("type").and_then(Value::as_str) {
                    Some("thinking") => AgentEvent::ReasoningDelta { text: text.into() },
                    _ => AgentEvent::TextDelta { text: text.into() },
                });
            }
        }
    }
    events
}

/// A failed assistant message: `stopReason: "error"` + `errorMessage`
/// (pi's `StopReason` union: pending/stop/length/toolUse/error/aborted/deferred).
pub(crate) fn message_end_error(event: &Value) -> Option<String> {
    let message = event.get("message").unwrap_or(&Value::Null);
    if message.get("stopReason").and_then(Value::as_str) != Some("error") {
        return None;
    }
    Some(
        message
            .get("errorMessage")
            .and_then(Value::as_str)
            .filter(|text| !text.is_empty())
            .unwrap_or("The model failed to complete the response.")
            .to_owned(),
    )
}

/// Latest cumulative usage → roboco's passthrough (rate-limit probes, never
/// persisted). `input + cacheRead` is the prompt size the provider reports
/// (`pi-ai` Usage: input excludes cached reads); output is output.
pub(crate) fn usage_event(usage: &Value) -> Option<AgentEvent> {
    if !usage.is_object() {
        return None;
    }
    let count = |key: &str| usage.get(key).and_then(Value::as_u64);
    let input = count("input").unwrap_or(0) + count("cacheRead").unwrap_or(0);
    let output = count("output").unwrap_or(0);
    (input > 0 || output > 0).then(|| AgentEvent::Usage {
        input_tokens: input,
        output_tokens: output,
    })
}

/// `get_session_stats` → the context meter. `data.contextUsage` is the
/// estimate compaction and pi's TUI footer use; `tokens` may be `null` right
/// after a compaction (fields preserve the previous measurement), and the
/// object is absent when no model/context window is known.
pub(crate) fn context_usage_event(stats: &Value) -> Option<AgentEvent> {
    let usage = stats.get("contextUsage")?;
    let tokens = usage.get("tokens").and_then(Value::as_u64);
    let window = usage.get("contextWindow").and_then(Value::as_u64);
    (tokens.is_some() || window.is_some()).then(|| AgentEvent::ContextUsage { tokens, window })
}

/// pi's thinking ladder; Roboco's `ReasoningLevel` maps 1:1 below `Ultra`.
pub(crate) fn thinking_level(level: ReasoningLevel) -> &'static str {
    match level {
        ReasoningLevel::Minimal => "minimal",
        ReasoningLevel::Low => "low",
        ReasoningLevel::Medium => "medium",
        ReasoningLevel::High => "high",
        ReasoningLevel::XHigh => "xhigh",
        // pi tops out at "max"; Roboco's ultra tiers clamp to it.
        ReasoningLevel::Max | ReasoningLevel::Ultra | ReasoningLevel::Ultracode
        | ReasoningLevel::Ultrathink => "max",
    }
}

/// The wire id + question shape of a blocking extension UI dialog.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub(crate) enum DialogKind {
    Select,
    Confirm,
    Input,
    Editor,
}

pub(crate) struct Dialog {
    pub wire_id: String,
    pub kind: DialogKind,
    pub question: UserInputQuestion,
}

/// Map one `extension_ui_request` to a question. Returns `None` for
/// fire-and-forget methods (`notify`, `setStatus`, `setWidget`, `setTitle`,
/// `set_editor_text`) — nothing blocks on those.
pub(crate) fn dialog(event: &Value) -> Option<Dialog> {
    let wire_id = event.get("id").and_then(Value::as_str)?;
    let method = event.get("method").and_then(Value::as_str)?;
    let title = event.get("title").and_then(Value::as_str).unwrap_or("");
    let (kind, question, options) = match method {
        "select" => {
            let options: Vec<String> = event
                .get("options")
                .and_then(Value::as_array)
                .map(|options| {
                    options
                        .iter()
                        .map(|option| option.as_str().unwrap_or_default().to_owned())
                        .collect()
                })
                .unwrap_or_default();
            (
                DialogKind::Select,
                if title.is_empty() {
                    "Pi is asking you to choose an option.".to_owned()
                } else {
                    title.to_owned()
                },
                options,
            )
        }
        "confirm" => (
            DialogKind::Confirm,
            event
                .get("message")
                .and_then(Value::as_str)
                .filter(|message| !message.is_empty())
                .unwrap_or("Pi is asking you to confirm.")
                .to_owned(),
            vec!["Yes".into(), "No".into()],
        ),
        "input" | "editor" => (
            if method == "input" {
                DialogKind::Input
            } else {
                DialogKind::Editor
            },
            if title.is_empty() {
                "Pi is asking for your input.".to_owned()
            } else {
                title.to_owned()
            },
            // No options: the composer answers free text.
            Vec::new(),
        ),
        _ => return None,
    };
    Some(Dialog {
        wire_id: wire_id.to_owned(),
        kind,
        question: UserInputQuestion {
            id: uuid::Uuid::new_v4().to_string(),
            header: if title.is_empty() {
                "Pi question".into()
            } else {
                title.to_owned()
            },
            question,
            options,
            multi_select: false,
        },
    })
}

/// Build the `extension_ui_response` object for an answered dialog. An
/// unanswered/dropped resolver degrades to `cancelled` — never a silent
/// default that an extension might act on.
pub(crate) fn dialog_response(wire_id: &str, kind: DialogKind, answers: &[UserInputAnswer]) -> Value {
    let label = answers
        .iter()
        .find(|answer| answer.labels.len() == 1)
        .and_then(|answer| answer.labels.first())
        .map(String::as_str);
    match kind {
        DialogKind::Select => match label {
            Some(value) if !value.is_empty() => json!({
                "type": "extension_ui_response",
                "id": wire_id,
                "value": value,
            }),
            _ => json!({"type": "extension_ui_response", "id": wire_id, "cancelled": true}),
        },
        DialogKind::Confirm => {
            let confirmed = label.is_some_and(|label| label.eq_ignore_ascii_case("yes"));
            json!({
                "type": "extension_ui_response",
                "id": wire_id,
                "confirmed": confirmed,
            })
        }
        DialogKind::Input | DialogKind::Editor => match label {
            Some(value) if !value.is_empty() => json!({
                "type": "extension_ui_response",
                "id": wire_id,
                "value": value,
            }),
            _ => json!({"type": "extension_ui_response", "id": wire_id, "cancelled": true}),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn update(kind: &str, index: u64, extra: Value) -> Value {
        let mut event = json!({
            "type": "message_update",
            "usage": {},
            "assistantMessageEvent": {
                "type": kind,
                "contentIndex": index,
            }
        });
        if let (Some(dst), Some(src)) = (
            event
                .pointer_mut("/assistantMessageEvent")
                .and_then(Value::as_object_mut),
            extra.as_object(),
        ) {
            for (key, value) in src {
                dst.insert(key.clone(), value.clone());
            }
        }
        event
    }

    #[test]
    fn deltas_stream_and_end_blocks_fallback_once() {
        let mut tracker = DeltaTracker::new();
        let events = message_update_events(
            &update("text_delta", 0, json!({"delta": "Hello"})),
            &mut tracker,
        );
        assert_eq!(
            events,
            vec![AgentEvent::TextDelta { text: "Hello".into() }]
        );
        // The end of a streamed block never re-emits.
        assert!(message_update_events(
            &update("text_end", 0, json!({"content": "Hello world"})),
            &mut tracker
        )
        .is_empty());
        // A non-streamed block emits its full content exactly once.
        let events = message_update_events(
            &update("thinking_end", 1, json!({"content": "deep thought"})),
            &mut tracker,
        );
        assert_eq!(
            events,
            vec![AgentEvent::ReasoningDelta {
                text: "deep thought".into()
            }]
        );
        assert!(message_update_events(
            &update("thinking_end", 1, json!({"content": "deep thought"})),
            &mut tracker
        )
        .is_empty());
        // Unknown delta kinds are tolerated (additive upstream).
        assert!(message_update_events(
            &update("future_kind", 2, json!({"delta": "x"})),
            &mut tracker
        )
        .is_empty());
    }

    #[test]
    fn message_end_falls_back_to_content_blocks() {
        let mut tracker = DeltaTracker::new();
        let event = json!({
            "type": "message_end",
            "message": {
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "final"},
                    {"type": "thinking", "text": "quiet"},
                ],
                "stopReason": "stop",
            }
        });
        assert_eq!(
            message_end_events(&event, &mut tracker),
            vec![
                AgentEvent::TextDelta { text: "final".into() },
                AgentEvent::ReasoningDelta { text: "quiet".into() },
            ]
        );
        assert_eq!(message_end_error(&event), None);
    }

    #[test]
    fn errored_messages_report_their_error() {
        let event = json!({
            "type": "message_end",
            "message": {"role": "assistant", "stopReason": "error", "errorMessage": "provider 529"}
        });
        assert_eq!(message_end_error(&event).as_deref(), Some("provider 529"));
        let event = json!({
            "type": "message_end",
            "message": {"role": "assistant", "stopReason": "error"}
        });
        assert!(message_end_error(&event).is_some());
        let event = json!({
            "type": "message_end",
            "message": {"role": "assistant", "stopReason": "aborted"}
        });
        assert_eq!(message_end_error(&event), None);
    }

    #[test]
    fn tool_calls_type_from_verified_schemas() {
        assert_eq!(
            typed_call("bash", &json!({"command": "cargo test"})),
            ToolCall::Exec {
                command: "cargo test".into()
            }
        );
        assert_eq!(
            typed_call("read", &json!({"path": "/a.rs", "offset": 10})),
            ToolCall::ReadFile { path: "/a.rs".into() }
        );
        assert_eq!(
            typed_call("edit", &json!({"path": "a.rs", "edits": [{"oldText": "x", "newText": "y"}]})),
            ToolCall::EditFile {
                path: "a.rs".into(),
                old_string: Some("x".into()),
                new_string: Some("y".into()),
            }
        );
        assert_eq!(
            typed_call("write", &json!({"path": "b.rs", "content": "new"})),
            ToolCall::WriteFile {
                path: "b.rs".into(),
                content: Some("new".into()),
            }
        );
        assert_eq!(
            typed_call("grep", &json!({"pattern": "todo", "path": "src", "ignoreCase": true})),
            ToolCall::Search {
                pattern: "todo".into(),
                path: Some("src".into()),
            }
        );
        assert_eq!(
            typed_call("find", &json!({"pattern": "**/*.rs"})),
            ToolCall::Glob {
                pattern: "**/*.rs".into()
            }
        );
        assert_eq!(
            typed_call("custom", &json!({"anything": 1})),
            ToolCall::Unknown {
                name: "custom".into(),
                input: Some(json!({"anything": 1})),
            }
        );
        assert_eq!(
            typed_call("ls", &Value::Null),
            ToolCall::Unknown {
                name: "ls".into(),
                input: None,
            }
        );
    }

    #[test]
    fn tool_results_join_and_cap_output_and_read_patch_diffs() {
        let result = json!({
            "content": [
                {"type": "text", "text": "line one"},
                {"type": "image", "data": "..."},
                {"type": "text", "text": "line two"},
            ],
            "details": {
                "patch": "--- a/src/lib.rs\n+++ b/src/lib.rs\n@@ -1 +1 @@\n-old\n+new\n"
            }
        });
        assert_eq!(
            output_text(result.get("content").unwrap()).as_deref(),
            Some("line one\nline two")
        );
        let diff = tool_diff(&result).unwrap();
        assert_eq!(diff.path, "src/lib.rs");
        assert_eq!(diff.old_text, None);
        assert!(diff.new_text.contains("-old\n+new"));
        // Output caps at the ACP parity bound.
        let long = json!([{"type": "text", "text": "x".repeat(40 * 1024)}]);
        let capped = output_text(&long).unwrap();
        assert!(capped.len() < 40 * 1024);
    }

    #[test]
    fn usage_and_context_meter_parse_the_documented_shapes() {
        assert_eq!(
            usage_event(&json!({"input": 500, "cacheRead": 400, "output": 100})),
            Some(AgentEvent::Usage {
                input_tokens: 900,
                output_tokens: 100,
            })
        );
        assert_eq!(usage_event(&json!({})), None);
        let stats = json!({
            "contextUsage": {"tokens": 60000, "contextWindow": 200000, "percent": 30}
        });
        assert_eq!(
            context_usage_event(&stats),
            Some(AgentEvent::ContextUsage {
                tokens: Some(60000),
                window: Some(200000),
            })
        );
        // tokens may be null right after compaction.
        assert_eq!(
            context_usage_event(&json!({"contextUsage": {"tokens": null, "contextWindow": 200000}})),
            Some(AgentEvent::ContextUsage {
                tokens: None,
                window: Some(200000),
            })
        );
        assert_eq!(context_usage_event(&json!({})), None);
    }

    #[test]
    fn dialogs_map_to_questions_and_answers() {
        let select = dialog(&json!({
            "type": "extension_ui_request",
            "id": "d1",
            "method": "select",
            "title": "Pick a runner",
            "options": ["pnpm", "npm"],
        }))
        .unwrap();
        assert_eq!(select.kind, DialogKind::Select);
        assert_eq!(select.question.header, "Pick a runner");
        assert_eq!(select.question.options, vec!["pnpm", "npm"]);
        assert_eq!(
            dialog_response(
                &select.wire_id,
                select.kind,
                &[UserInputAnswer {
                    question_id: select.question.id.clone(),
                    labels: vec!["npm".into()],
                }]
            )["value"],
            "npm"
        );
        // Unanswered degrades to cancelled, never a silent default.
        assert_eq!(
            dialog_response(&select.wire_id, select.kind, &[]),
            json!({"type": "extension_ui_response", "id": "d1", "cancelled": true})
        );

        let confirm = dialog(&json!({
            "type": "extension_ui_request",
            "id": "d2",
            "method": "confirm",
            "title": "Run it?",
            "message": "This will delete files.",
        }))
        .unwrap();
        assert_eq!(confirm.kind, DialogKind::Confirm);
        assert_eq!(confirm.question.question, "This will delete files.");
        assert_eq!(
            dialog_response(
                &confirm.wire_id,
                confirm.kind,
                &[UserInputAnswer {
                    question_id: confirm.question.id.clone(),
                    labels: vec!["yes".into()],
                }]
            )["confirmed"],
            true
        );
        assert_eq!(
            dialog_response(&confirm.wire_id, confirm.kind, &[UserInputAnswer {
                question_id: confirm.question.id.clone(),
                labels: vec!["No".into()],
            }])["confirmed"],
            false
        );

        let input = dialog(&json!({
            "type": "extension_ui_request",
            "id": "d3",
            "method": "input",
            "title": "Branch name",
            "placeholder": "feature/…",
        }))
        .unwrap();
        assert_eq!(input.kind, DialogKind::Input);
        assert!(input.question.options.is_empty());
        assert_eq!(
            dialog_response(
                &input.wire_id,
                input.kind,
                &[UserInputAnswer {
                    question_id: input.question.id.clone(),
                    labels: vec!["feature/x".into()],
                }]
            )["value"],
            "feature/x"
        );

        // Fire-and-forget methods never become questions.
        for method in ["notify", "setStatus", "setWidget", "setTitle", "set_editor_text"] {
            assert!(dialog(&json!({
                "type": "extension_ui_request",
                "id": "d4",
                "method": method,
                "message": "hi",
            }))
            .is_none());
        }
        // Unknown dialog methods stay tolerated (additive upstream).
        assert!(dialog(&json!({
            "type": "extension_ui_request",
            "id": "d5",
            "method": "futureDialog",
        }))
        .is_none());
    }
}
