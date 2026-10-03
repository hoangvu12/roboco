//! Pure projection of saved native display entries into chat doc entries.
//! Public content only: model context, hidden thinking, signatures and
//! redacted thinking never reach a transcript part.

use serde_json::Value;

use roboco_doc::{MessagePart, MessageRole, MessageStatus, SessionMessageEntry, ToolDiffStat};
use roboco_harness::mimir::protocol::{
    ContentBlock, DisplayEntry, DisplayItem, Message, MessageContent, ThinkingKind, ToolCallBlock,
    ToolPresentation, ToolResultMessage, decode_json_value,
};
use roboco_proto::{NativeNotice, NativeToolDetail, NativeToolView, ToolCall};

/// What one saved display entry becomes.
#[derive(Debug, Clone, PartialEq)]
pub(super) enum Projected {
    /// A transcript entry, keyed by its native entry id.
    Entry {
        native_id: String,
        entry: SessionMessageEntry,
        /// A user entry with public text: a candidate for the engine's echo.
        user_text: bool,
        /// Tool details to store next to the entry's tool parts.
        details: Vec<NativeToolDetail>,
        /// Image blocks to store under their notice's `detail_ref`.
        images: Vec<(String, Value)>,
    },
    /// A tool result: completes the call part `part_id` wherever it lives.
    ToolResult(Box<ToolResult>),
    /// Model-only content (runtime context, an empty completion delivery).
    Hidden,
}

#[derive(Debug, Clone, PartialEq)]
pub(super) struct ToolResult {
    /// The call's invocation id, when the host recorded one.
    pub invocation_id: Option<String>,
    pub tool_call_id: String,
    pub tool_name: String,
    pub is_error: bool,
    pub presentation: Option<NativeToolView>,
    /// Public output: the host's display content when present, else the result text.
    pub output: String,
    pub display_content: Vec<Value>,
    pub details: Option<Value>,
}

/// The doc part id of a tool call. Invocation ids are unique per
/// conversation; provider call ids are not, so they are scoped by entry.
pub(super) fn tool_part_id(invocation: Option<&str>, entry_id: &str, call_id: &str) -> String {
    match invocation {
        Some(id) => id.to_owned(),
        None => format!("{entry_id}:{call_id}"),
    }
}

pub(super) fn tool_view(
    name: &str,
    tool_call_id: &str,
    invocation_id: Option<&str>,
    presentation: &ToolPresentation,
) -> NativeToolView {
    NativeToolView {
        name: name.to_owned(),
        tool_call_id: tool_call_id.to_owned(),
        invocation_id: invocation_id.map(str::to_owned),
        title: presentation.title.clone(),
        running_title: presentation.running_title.clone(),
        kind: (&presentation.display_kind).into(),
        summary: presentation.summary.clone(),
        locations: presentation.locations(),
        result_state: presentation.result_state.into(),
        preview: presentation.preview.into(),
        semantic: presentation.semantic_content.map(Into::into),
        quiet: presentation.quiet,
        group: presentation.group.as_ref().map(|g| g.native()),
        subagent: presentation.subagent.as_ref().map(|s| s.native()),
        progress: None,
        duration_ms: None,
        detail_ref: None,
        detail_bytes: None,
        child: None,
    }
}

/// A view without host presentation (a call saved before it had one).
pub(super) fn bare_view(
    name: &str,
    tool_call_id: &str,
    invocation_id: Option<&str>,
) -> NativeToolView {
    NativeToolView {
        name: name.to_owned(),
        tool_call_id: tool_call_id.to_owned(),
        invocation_id: invocation_id.map(str::to_owned),
        title: name.to_owned(),
        running_title: None,
        kind: roboco_proto::NativeToolKind::Generic,
        summary: None,
        locations: Vec::new(),
        result_state: roboco_proto::NativeToolResultState::Normal,
        preview: roboco_proto::NativeToolPreview::Full,
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

pub(super) fn tool_part(id: String, view: NativeToolView) -> MessagePart {
    MessagePart::Tool {
        id,
        call: ToolCall::Native {
            view: Box::new(view),
        },
        is_error: false,
        resolved: false,
        output: None,
        diff: None,
        output_ref: None,
        output_bytes: None,
        diff_ref: None,
        diff_stats: None,
        subagent_ref: None,
        subagent_status: None,
        subagent_tail: None,
    }
}

pub(super) fn empty_detail(view: NativeToolView) -> NativeToolDetail {
    NativeToolDetail {
        name: view.name.clone(),
        tool_call_id: view.tool_call_id.clone(),
        invocation_id: view.invocation_id.clone(),
        input: None,
        raw_input: None,
        is_error: None,
        output: None,
        display_content: Vec::new(),
        details: None,
        output_profile: None,
        compactions: Vec::new(),
        progress: None,
        stream: None,
        view,
    }
}

/// Text a reader may see from content blocks. With display text present,
/// only display text counts: it is the host's public form of the same input.
fn public_text(blocks: &[ContentBlock]) -> String {
    let display: Vec<&str> = blocks
        .iter()
        .filter_map(|b| match b {
            ContentBlock::DisplayText(text) => Some(text.as_str()),
            _ => None,
        })
        .collect();
    if !display.is_empty() {
        return display.join("\n");
    }
    blocks
        .iter()
        .filter_map(|b| match b {
            ContentBlock::Text(text) => Some(text.text.as_str()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// The text of one public display block: plain text and resource text.
/// Image, audio, blob and link blocks carry no text; their details keep them.
fn display_text(block: &Value) -> Option<&str> {
    block
        .get("text")
        .and_then(Value::as_str)
        .or_else(|| block.get("resource_text")?.get("text")?.as_str())
}

/// Public result text. A nonempty public display list is authoritative, even
/// when none of its blocks is text: model-facing content is never shown in
/// its place. Only with no display list does the content's public text stand.
pub(super) fn result_output(display_content: &[Value], content: &MessageContent) -> String {
    if !display_content.is_empty() {
        return display_content
            .iter()
            .filter_map(display_text)
            .collect::<Vec<_>>()
            .join("\n");
    }
    public_text(&content.blocks())
}

fn push_text(parts: &mut Vec<MessagePart>, text: &str) {
    if text.is_empty() {
        return;
    }
    if let Some(MessagePart::Text { text: tail, .. }) = parts.last_mut() {
        tail.push('\n');
        tail.push_str(text);
        return;
    }
    let id = format!("t{}", parts.len());
    parts.push(MessagePart::Text {
        id,
        text: text.to_owned(),
    });
}

fn notice(parts: &mut Vec<MessagePart>, notice: NativeNotice) {
    let id = format!("n{}", parts.len());
    parts.push(MessagePart::Notice { id, notice });
}

fn image_notice(
    parts: &mut Vec<MessagePart>,
    images: &mut Vec<(String, Value)>,
    image: &Value,
    blob: String,
) {
    notice(
        parts,
        NativeNotice::Image {
            media_type: image
                .get("media_type")
                .and_then(Value::as_str)
                .map(str::to_owned),
            detail_ref: Some(blob.clone()),
        },
    );
    images.push((blob, image.clone()));
}

fn call_part(
    entry_id: &str,
    chat_id: &str,
    call: &ToolCallBlock,
) -> (MessagePart, NativeToolDetail) {
    let invocation = call.invocation.as_ref().map(|i| i.id.as_str());
    let id = tool_part_id(invocation, entry_id, &call.id);
    let mut view = match &call.invocation {
        Some(invocation) => tool_view(
            &call.name,
            &call.id,
            Some(&invocation.id),
            &invocation.start,
        ),
        None => bare_view(&call.name, &call.id, None),
    };
    view.detail_ref = Some(detail_ref(chat_id, &id));
    let mut detail = empty_detail(view.clone());
    detail.input = decode_json_value(&call.arguments);
    detail.raw_input = call.raw_arguments.clone();
    (tool_part(id, view), detail)
}

pub(super) fn detail_ref(chat_id: &str, part_id: &str) -> String {
    format!("{chat_id}/{part_id}.native")
}

/// Blob name of the detail a saved result entry finished a call with.
pub(super) fn finished_name(part_id: &str, result_entry: &str) -> String {
    format!("{part_id}.result.{result_entry}.native")
}

pub(super) fn output_ref(chat_id: &str, part_id: &str) -> String {
    format!("{chat_id}/{part_id}")
}

fn entry(
    native_id: &str,
    role: MessageRole,
    parts: Vec<MessagePart>,
    device_id: &str,
) -> SessionMessageEntry {
    SessionMessageEntry {
        id: roboco_doc::native_entry_id(native_id),
        role,
        parts,
        created_at: 0,
        device_id: device_id.to_owned(),
        status: Some(MessageStatus::Complete),
        continuation_of: None,
        duration_ms: None,
    }
}

/// Project one saved entry. `chat_id` scopes blob refs; image and unsupported
/// payloads are referenced by `entry.id`-derived blob names the caller stores.
pub(super) fn project(chat_id: &str, device_id: &str, display: &DisplayEntry) -> Projected {
    let id = display.id.as_str();
    let mut parts = Vec::new();
    let mut details = Vec::new();
    let mut images = Vec::new();
    match &display.item {
        DisplayItem::Message(Message::User(user)) => {
            let blocks = user.content.blocks();
            push_text(&mut parts, &public_text(&blocks));
            for (n, block) in blocks.iter().enumerate() {
                if let ContentBlock::Image(image) = block {
                    image_notice(
                        &mut parts,
                        &mut images,
                        image,
                        format!("{chat_id}/{id}-{n}.image"),
                    );
                }
            }
            if parts.is_empty() {
                return Projected::Hidden;
            }
            let user_text = parts.iter().any(|p| matches!(p, MessagePart::Text { .. }));
            return Projected::Entry {
                native_id: id.to_owned(),
                entry: entry(id, MessageRole::User, parts, device_id),
                user_text,
                details,
                images,
            };
        }
        DisplayItem::Message(Message::Assistant(assistant)) => {
            for (n, block) in assistant.content.blocks().iter().enumerate() {
                match block {
                    ContentBlock::Text(text) => push_text(&mut parts, &text.text),
                    ContentBlock::DisplayText(text) => push_text(&mut parts, text),
                    ContentBlock::Thinking(thinking) if thinking.kind == ThinkingKind::Summary => {
                        if !thinking.thinking.is_empty() {
                            let rid = format!("r{}", parts.len());
                            parts.push(MessagePart::Reasoning {
                                id: rid,
                                text: thinking.thinking.clone(),
                            });
                        }
                    }
                    ContentBlock::ToolCall(call) => {
                        let (part, detail) = call_part(id, chat_id, call);
                        parts.push(part);
                        details.push(detail);
                    }
                    ContentBlock::Image(image) => {
                        image_notice(
                            &mut parts,
                            &mut images,
                            image,
                            format!("{chat_id}/{id}-{n}.image"),
                        );
                    }
                    ContentBlock::Thinking(_)
                    | ContentBlock::ModelContext(_)
                    | ContentBlock::RedactedThinking(_) => {}
                }
            }
        }
        DisplayItem::Message(Message::System(system)) => {
            let blocks = system.content.blocks();
            let display: Vec<&str> = blocks
                .iter()
                .filter_map(|b| match b {
                    ContentBlock::DisplayText(text) => Some(text.as_str()),
                    _ => None,
                })
                .collect();
            push_text(&mut parts, &display.join("\n"));
            if parts.is_empty() {
                return Projected::Hidden;
            }
            return Projected::Entry {
                native_id: id.to_owned(),
                entry: entry(id, MessageRole::System, parts, device_id),
                user_text: false,
                details,
                images,
            };
        }
        DisplayItem::Message(Message::ToolResult(result)) => {
            return Projected::ToolResult(Box::new(tool_result(result)));
        }
        DisplayItem::SubagentCompletions(completions) => notice(
            &mut parts,
            NativeNotice::SubagentCompletions {
                completions: completions.iter().map(|c| c.native()).collect(),
            },
        ),
        DisplayItem::PlanLifecycle(plan) => notice(
            &mut parts,
            NativeNotice::PlanLifecycle {
                plan_id: plan.plan_id.clone(),
                name: plan.name.clone(),
                status: plan.status.into(),
            },
        ),
        DisplayItem::Compaction(compaction) => notice(
            &mut parts,
            NativeNotice::Compaction {
                trigger: compaction.trigger.clone(),
                before_tokens: compaction.measurement.as_ref().map(|m| m.before_tokens),
                after_tokens: compaction.measurement.as_ref().map(|m| m.after_tokens),
            },
        ),
        DisplayItem::BranchSummary(summary) => notice(
            &mut parts,
            NativeNotice::BranchSummary {
                summary: summary.clone(),
            },
        ),
        DisplayItem::PluginSnapshot(snapshot) => notice(
            &mut parts,
            NativeNotice::PluginSnapshot {
                plugin_id: snapshot.plugin_id.clone(),
                title: snapshot.title.clone(),
                revision: snapshot.revision,
                fallback: snapshot.fallback.clone(),
                lines: snapshot.lines.clone(),
            },
        ),
    }
    if parts.is_empty() {
        return Projected::Hidden;
    }
    let role = match &display.item {
        DisplayItem::Message(Message::Assistant(_)) => MessageRole::Assistant,
        _ => MessageRole::System,
    };
    Projected::Entry {
        native_id: id.to_owned(),
        entry: entry(id, role, parts, device_id),
        user_text: false,
        details,
        images,
    }
}

fn tool_result(result: &ToolResultMessage) -> ToolResult {
    ToolResult {
        invocation_id: result.invocation_id.clone(),
        tool_call_id: result.tool_call_id.clone(),
        tool_name: result.tool_name.clone(),
        is_error: result.is_error,
        presentation: result.presentation.as_ref().map(|p| {
            tool_view(
                &result.tool_name,
                &result.tool_call_id,
                result.invocation_id.as_deref(),
                p,
            )
        }),
        output: result_output(&result.display_content, &result.content),
        display_content: result.display_content.clone(),
        details: decode_json_value(&result.details).filter(|v| !v.is_null()),
    }
}

/// Per-file stats from a live `file_change` output profile.
pub(super) fn diff_stats(profile: &Value) -> Option<Vec<ToolDiffStat>> {
    let files = profile.get("file_change")?.get("files")?.as_array()?;
    let stats: Vec<ToolDiffStat> = files
        .iter()
        .filter_map(|file| {
            Some(ToolDiffStat {
                path: file.get("path")?.as_str()?.to_owned(),
                additions: file.get("additions")?.as_u64()?,
                deletions: file.get("deletions")?.as_u64()?,
            })
        })
        .collect();
    (!stats.is_empty()).then_some(stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn parse(value: Value) -> DisplayEntry {
        serde_json::from_value(value).unwrap()
    }

    #[test]
    fn user_entries_show_display_text_and_never_model_context() {
        let entry = parse(
            json!({"id": "u1", "item": {"message": {"user": {"content": {"blocks": [
            {"display_text": "read the note"},
            {"model_context": "<issue_description>\nread the note\n</issue_description>"}
        ]}, "details": {"root": 0, "nodes": ["null"]}}}}}),
        );
        let Projected::Entry {
            entry,
            user_text,
            native_id,
            ..
        } = project("chat", "host", &entry)
        else {
            panic!()
        };
        assert!(user_text);
        assert_eq!(native_id, "u1");
        assert_eq!(entry.id, "m-u1");
        assert_eq!(entry.role, MessageRole::User);
        assert_eq!(
            entry.parts,
            vec![MessagePart::Text {
                id: "t0".into(),
                text: "read the note".into()
            }]
        );
    }

    #[test]
    fn runtime_context_and_empty_completion_deliveries_are_hidden() {
        let system = parse(
            json!({"id": "s", "item": {"message": {"system": {"content": {"blocks": [
            {"model_context": "<runtime_context reset=\"true\">"}]}}}}}),
        );
        assert_eq!(project("chat", "host", &system), Projected::Hidden);
        let delivery = parse(
            json!({"id": "d", "item": {"message": {"user": {"content": {"blocks": [
            {"display_text": ""}, {"model_context": "<subagent_completions>"}]},
            "details": {"root": 0, "nodes": ["null"]}}}}}),
        );
        assert_eq!(project("chat", "host", &delivery), Projected::Hidden);
    }

    #[test]
    fn assistant_tool_calls_keep_host_presentation_and_decoded_input() {
        let entry = parse(
            json!({"id": "a1", "item": {"message": {"assistant": {"content": {"blocks": [
            {"thinking": {"thinking": "secret", "signature": "sig", "kind": "hidden", "duration_ms": 1, "provider_metadata": {"openai_responses": null}}},
            {"thinking": {"thinking": "Plan: read it", "signature": "sig", "kind": "summary", "duration_ms": 1, "provider_metadata": {"openai_responses": null}}},
            {"tool_call": {"invocation": {"id": "inv-1", "start": {"title": "Read: note.txt", "running_title": "Reading files",
                "display_kind": "file_read", "summary": "note.txt", "locations": [{"path": "note.txt", "line": null}],
                "result_state": "normal", "preview": "full", "subagent": null, "semantic_content": null, "quiet": false, "group": null}},
             "id": "call_1", "name": "read_file",
             "arguments": {"root": 2, "nodes": [{"string_value": "note.txt"}, {"array": [0]}, {"object": [{"key": "paths", "value": 1}]}]},
             "raw_arguments": null, "provider_data": {"root": 0, "nodes": ["null"]}, "provider_metadata": {"openai_responses": null}}}
        ]}, "model": null, "usage": null, "request_token_anchor": null, "stop_reason": null,
            "provider_data": {"root": 0, "nodes": ["null"]}}}}}),
        );
        let Projected::Entry { entry, details, .. } = project("chat", "host", &entry) else {
            panic!()
        };
        assert_eq!(entry.parts.len(), 2);
        assert_eq!(
            entry.parts[0],
            MessagePart::Reasoning {
                id: "r0".into(),
                text: "Plan: read it".into()
            }
        );
        let MessagePart::Tool {
            id,
            call: ToolCall::Native { view },
            resolved,
            ..
        } = &entry.parts[1]
        else {
            panic!("{:?}", entry.parts[1])
        };
        assert_eq!(id, "inv-1");
        assert!(!resolved);
        assert_eq!(view.title, "Read: note.txt");
        assert_eq!(view.running_title.as_deref(), Some("Reading files"));
        assert_eq!(view.kind, roboco_proto::NativeToolKind::FileRead);
        assert_eq!(view.detail_ref.as_deref(), Some("chat/inv-1.native"));
        assert_eq!(details[0].input, Some(json!({"paths": ["note.txt"]})));
        let text = serde_json::to_string(&entry).unwrap();
        assert!(!text.contains("secret") && !text.contains("sig"));
    }

    #[test]
    fn tool_results_prefer_public_display_content() {
        let result = parse(json!({"id": "r1", "item": {"message": {"tool_result": {
            "invocation_id": "inv-1", "presentation": null,
            "display_content": [{"text": "public report"}],
            "tool_call_id": "call_1", "tool_name": "review",
            "content": {"blocks": [{"text": {"text": "model-facing diagnostics", "provider_metadata": {"openai_responses": null}}},
                {"model_context": "hidden"}]},
            "is_error": false, "details": {"root": 0, "nodes": ["null"]}}}}}));
        let Projected::ToolResult(result) = project("chat", "host", &result) else {
            panic!()
        };
        assert_eq!(result.output, "public report");
        assert_eq!(result.invocation_id.as_deref(), Some("inv-1"));
        assert!(result.details.is_none());
        let plain = parse(json!({"id": "r2", "item": {"message": {"tool_result": {
            "invocation_id": null, "presentation": null, "display_content": [],
            "tool_call_id": "call_2", "tool_name": "read_file",
            "content": {"text": "file body"}, "is_error": true, "details": {"root": 0, "nodes": ["null"]}}}}}));
        let Projected::ToolResult(plain) = project("chat", "host", &plain) else {
            panic!()
        };
        assert_eq!((plain.output.as_str(), plain.is_error), ("file body", true));
    }

    #[test]
    fn display_items_become_typed_notices() {
        let plan = parse(
            json!({"id": "p", "item": {"plan_lifecycle": {"plan_id": "plan-1", "name": "bridge-plan", "status": "review_pending"}}}),
        );
        let Projected::Entry { entry, .. } = project("chat", "host", &plan) else {
            panic!()
        };
        assert_eq!(entry.role, MessageRole::System);
        assert!(matches!(
            &entry.parts[0],
            MessagePart::Notice { notice: NativeNotice::PlanLifecycle { name, .. }, .. } if name == "bridge-plan"
        ));
    }

    #[test]
    fn file_change_profiles_yield_per_file_stats() {
        let profile = json!({"file_change": {"files": [
            {"path": "a.rs", "status": "modified", "additions": 3, "deletions": 1, "replacements": null, "complete": null, "hunks": []},
            {"path": "b.rs", "status": "added", "additions": 9, "deletions": 0, "replacements": null, "complete": null, "hunks": []}
        ]}});
        let stats = diff_stats(&profile).unwrap();
        assert_eq!(stats.len(), 2);
        assert_eq!((stats[1].path.as_str(), stats[1].additions), ("b.rs", 9));
        assert!(diff_stats(&json!("generic")).is_none());
    }
}
