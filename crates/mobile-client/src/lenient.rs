//! Version-drift tolerance for engine payloads.
//!
//! An installed app skews against engines: the engine can be newer than the
//! app. Everything the mobile client reads from it goes through here first:
//! a row or transcript item the app's types can't read is *repaired*
//! (unknown optional values dropped, unknown enum values mapped to a safe
//! default, unknown message kinds turned into a visible "unsupported"
//! placeholder) instead of failing the whole frame and leaving a blank list
//! or chat. A decode error never tears the link down — a deliberate
//! difference from the desktop registry's strict `decode(frame)?`.
//!
//! Port of zeron #621's `crates/client/src/direct/lenient.rs` (renamed).

use roboco_doc::{MessagePart, SessionMessageEntry};
use roboco_proto::ContextUsage;
use serde::de::DeserializeOwned;
use serde_json::{Map, Value};
use std::collections::HashSet;

/// Replace a value at a dotted path when present (`"config.sandbox"`).
pub(crate) type Substitution = (&'static str, Value);

/// Substitutions for `WatchSessions` frames: unknown `Session.status`
/// values read as `idle` (the least alarming live state).
pub(crate) fn session_substitutions() -> Vec<Substitution> {
    vec![("status", Value::String("idle".into()))]
}

/// Substitutions for `WatchChats` frames: an unknown `ReasoningLevel` reads
/// as absent; an unknown `SandboxLevel` reads as the engine-local default.
pub(crate) fn chat_substitutions() -> Vec<Substitution> {
    vec![
        ("config.reasoning", Value::Null),
        ("config.sandbox", Value::String("workspace-write".into())),
    ]
}

/// Try `value` as `T`; on failure repair it: first the listed substitutions,
/// then dropping one nested object field, then one top-level field (never
/// `id`). Returns the row and what was changed (`None` = parsed as is).
pub(crate) fn decode_row<T: DeserializeOwned>(
    value: &Value,
    substitutions: &[Substitution],
) -> Result<(T, Option<String>), String> {
    let first_err = match serde_json::from_value::<T>(value.clone()) {
        Ok(row) => return Ok((row, None)),
        Err(err) => err.to_string(),
    };
    let Value::Object(map) = value else {
        return Err(first_err);
    };
    let base = map.clone();
    let mut substituted = map.clone();
    let mut notes = Vec::new();
    for (path, fallback) in substitutions {
        if let Some(slot) = pointer_mut(&mut substituted, path)
            && *slot != *fallback
        {
            *slot = fallback.clone();
            notes.push(format!("{path}→{fallback}"));
        }
    }
    if !notes.is_empty()
        && let Ok(row) = serde_json::from_value::<T>(Value::Object(substituted))
    {
        return Ok((row, Some(format!("replaced {}", notes.join(", ")))));
    }
    // Drop one nested field (an unknown enum inside an optional object).
    for (key, inner) in &base {
        let Value::Object(inner) = inner else {
            continue;
        };
        for nested in inner.keys() {
            let mut candidate = base.clone();
            if let Some(Value::Object(obj)) = candidate.get_mut(key) {
                obj.remove(nested);
            }
            if let Ok(row) = serde_json::from_value::<T>(Value::Object(candidate)) {
                return Ok((row, Some(format!("ignored {key}.{nested}"))));
            }
        }
    }
    // Drop one top-level field (optional fields default).
    for key in base.keys().filter(|k| k.as_str() != "id") {
        let mut candidate = base.clone();
        candidate.remove(key);
        if let Ok(row) = serde_json::from_value::<T>(Value::Object(candidate)) {
            return Ok((row, Some(format!("ignored {key}"))));
        }
    }
    Err(first_err)
}

fn pointer_mut<'a>(map: &'a mut Map<String, Value>, path: &str) -> Option<&'a mut Value> {
    let mut parts = path.split('.');
    let mut slot = map.get_mut(parts.next()?)?;
    for part in parts {
        slot = slot.as_object_mut()?.get_mut(part)?;
    }
    Some(slot)
}

/// A watch snapshot decoded row by row.
#[derive(Debug)]
pub(crate) struct DecodedRows<T> {
    pub(crate) rows: Vec<T>,
    /// Ids of every row in the frame, parsed or not (deletion guard).
    pub(crate) ids: HashSet<String>,
    /// Rows that could not be read even after repair.
    pub(crate) errors: Vec<String>,
    /// Rows kept after a repair (what was changed).
    pub(crate) repaired: Vec<String>,
}

/// Decode a watch snapshot leniently: a JSON array (or an object wrapping
/// exactly one array, for forward compatibility); rows are repaired where
/// possible and otherwise reported, never fatal. `id_field` is the row's
/// identity field ("id" for chats/spaces/devices, "chatId" for sessions):
/// the ids of undecodable rows still land in [`DecodedRows::ids`], so the
/// deletion guard can keep their previous copies.
pub(crate) fn decode_rows<T: DeserializeOwned>(
    value: Value,
    substitutions: &[Substitution],
    id_field: &str,
) -> Result<DecodedRows<T>, String> {
    let items = match value {
        Value::Array(items) => items,
        Value::Object(mut map) => {
            let arrays: Vec<String> = map
                .iter()
                .filter(|(_, v)| v.is_array())
                .map(|(k, _)| k.clone())
                .collect();
            match arrays.as_slice() {
                [key] => match map.remove(key) {
                    Some(Value::Array(items)) => items,
                    _ => unreachable!("checked above"),
                },
                _ => return Err("expected a list of rows, got an object".into()),
            }
        }
        other => {
            return Err(format!(
                "expected a list of rows, got {}",
                json_kind(&other)
            ));
        }
    };
    let mut decoded = DecodedRows {
        rows: Vec::with_capacity(items.len()),
        ids: HashSet::with_capacity(items.len()),
        errors: Vec::new(),
        repaired: Vec::new(),
    };
    for item in items {
        let id = item
            .get(id_field)
            .and_then(|v| v.as_str())
            .map(str::to_owned);
        if let Some(id) = &id {
            decoded.ids.insert(id.clone());
        }
        let label = match &id {
            Some(id) => format!("row {}", short_id(id)),
            None => "row without id".to_owned(),
        };
        match decode_row::<T>(&item, substitutions) {
            Ok((row, note)) => {
                decoded.rows.push(row);
                if let Some(note) = note {
                    decoded.repaired.push(format!("{label}: {note}"));
                }
            }
            Err(err) => decoded.errors.push(format!("{label}: {err}")),
        }
    }
    Ok(decoded)
}

/// Placeholder body for a message part this app version can't show.
#[allow(dead_code)] // the transcript path is exercised by its tests; ticket 08 wires the stream
pub(crate) fn unsupported_text(kind: &str) -> String {
    format!("_Unsupported content ({kind}). Update the app to view it._")
}

/// Make a `WatchDocMessages` update readable by this app version, in place.
/// Returns how many entries/parts were repaired. Ticket 08 runs every
/// `WatchDocMessages` item through this before decoding.
#[allow(dead_code)] // consumed by ticket 08
pub(crate) fn sanitize_transcript_update(update: &mut Value) -> usize {
    let Value::Object(map) = update else {
        return 0;
    };
    let mut repaired = 0;
    if let Some(Value::Array(entries)) = map.get_mut("reset") {
        for (i, entry) in entries.iter_mut().enumerate() {
            repaired += sanitize_entry(entry, i);
        }
    }
    if let Some(Value::Array(upserts)) = map.get_mut("upsert") {
        for (i, upsert) in upserts.iter_mut().enumerate() {
            if let Some(entry) = upsert.get_mut("entry") {
                repaired += sanitize_entry(entry, i);
            }
        }
    }
    // Additive side channels: unreadable → absent.
    for key in ["contextUsage", "replayBaseline"] {
        let bad = match map.get(key) {
            None | Some(Value::Null) => false,
            Some(v) if key == "contextUsage" => {
                serde_json::from_value::<ContextUsage>(v.clone()).is_err()
            }
            Some(v) => {
                serde_json::from_value::<roboco_doc::transcript_delta::TranscriptBaseline>(
                    v.clone(),
                )
                .is_err()
            }
        };
        if bad {
            map.remove(key);
            repaired += 1;
        }
    }
    repaired
}

#[allow(dead_code)] // with sanitize_transcript_update
fn sanitize_entry(entry: &mut Value, index: usize) -> usize {
    if serde_json::from_value::<SessionMessageEntry>(entry.clone()).is_ok() {
        return 0;
    }
    let mut repaired = 1;
    let Value::Object(map) = entry else {
        *entry = fallback_entry(&format!("unreadable-{index}"), "message");
        return repaired;
    };
    let id = map
        .get("id")
        .and_then(Value::as_str)
        .map(str::to_owned)
        .unwrap_or_else(|| format!("unreadable-{index}"));
    map.insert("id".into(), Value::String(id.clone()));
    if !matches!(
        map.get("role").and_then(Value::as_str),
        Some("user" | "assistant" | "system")
    ) {
        map.insert("role".into(), Value::String("assistant".into()));
    }
    if !matches!(map.get("status"), None | Some(Value::Null))
        && !matches!(
            map.get("status").and_then(Value::as_str),
            Some("streaming" | "complete" | "aborted")
        )
    {
        map.remove("status");
    }
    if !map.get("createdAt").is_some_and(Value::is_i64) {
        map.insert("createdAt".into(), Value::from(0));
    }
    if !map.get("deviceId").is_some_and(Value::is_string) {
        map.insert("deviceId".into(), Value::String(String::new()));
    }
    for key in ["continuationOf", "durationMs"] {
        let ok = match map.get(key) {
            None | Some(Value::Null) => true,
            Some(v) if key == "durationMs" => v.is_i64(),
            Some(v) => v.is_string(),
        };
        if !ok {
            map.remove(key);
        }
    }
    match map.get_mut("parts") {
        Some(Value::Array(parts)) => {
            for (i, part) in parts.iter_mut().enumerate() {
                if serde_json::from_value::<MessagePart>(part.clone()).is_err() {
                    *part = repair_part(part, &id, i);
                    repaired += 1;
                }
            }
        }
        _ => {
            map.insert("parts".into(), Value::Array(Vec::new()));
        }
    }
    if serde_json::from_value::<SessionMessageEntry>(entry.clone()).is_err() {
        *entry = fallback_entry(&id, "message");
    }
    repaired
}

#[allow(dead_code)] // with sanitize_transcript_update
fn repair_part(part: &Value, entry_id: &str, index: usize) -> Value {
    let kind = part
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or("unknown")
        .to_owned();
    let id = part
        .get("id")
        .and_then(Value::as_str)
        .map(str::to_owned)
        .unwrap_or_else(|| format!("{entry_id}-part-{index}"));
    let mut candidate = part.clone();
    if let Value::Object(map) = &mut candidate {
        map.insert("id".into(), Value::String(id.clone()));
        // A tool call of a kind this app doesn't know renders as a generic
        // tool chip named after it.
        if kind == "tool"
            && let Some(call) = map.get("call").cloned()
            && serde_json::from_value::<roboco_proto::ToolCall>(call.clone()).is_err()
        {
            let name = ["name", "tool", "kind"]
                .iter()
                .find_map(|k| call.get(*k).and_then(Value::as_str))
                .unwrap_or("tool")
                .to_owned();
            map.insert(
                "call".into(),
                serde_json::json!({ "kind": "unknown", "name": name, "input": call }),
            );
        }
    }
    if let Ok((part, _)) = decode_row::<MessagePart>(&candidate, &[]) {
        return serde_json::to_value(part).unwrap_or(Value::Null);
    }
    serde_json::json!({ "kind": "text", "id": id, "text": unsupported_text(&kind) })
}

fn fallback_entry(id: &str, kind: &str) -> Value {
    serde_json::json!({
        "id": id,
        "role": "assistant",
        "parts": [{ "kind": "text", "id": format!("{id}-unsupported"), "text": unsupported_text(kind) }],
        "createdAt": 0,
        "deviceId": "",
    })
}

pub(crate) fn json_kind(value: &Value) -> &'static str {
    match value {
        Value::Null => "null",
        Value::Bool(_) => "a boolean",
        Value::Number(_) => "a number",
        Value::String(_) => "a string",
        Value::Array(_) => "a list",
        Value::Object(_) => "an object",
    }
}

pub(crate) fn short_id(id: &str) -> &str {
    id.get(..8).unwrap_or(id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use roboco_proto::{Chat, Session, SessionStatus};
    use serde_json::json;

    fn chat(id: &str, harness: &str) -> Value {
        json!({
            "id": id,
            "deviceId": "dev",
            "title": "Chat",
            "archived": false,
            "cwd": null,
            "branch": null,
            "checkoutId": null,
            "config": {"harness": harness, "model": null, "reasoning": null, "modelOptions": {}, "sandbox": "workspace-write"},
            "lastMessagePreview": null,
            "lastMessageAt": null,
            "createdAt": "2026-01-01T00:00:00Z",
            "spaceId": null,
        })
    }

    #[test]
    fn an_unknown_session_status_decodes_as_idle() {
        let frame = json!([
            {"lastCompletedTurn": null, "chatId": "c", "deviceId": "d",
             "status": "whirring", "startedAt": null,
             "updatedAt": "2026-01-01T00:00:00Z"}
        ]);
        let decoded = decode_rows::<Session>(frame, &session_substitutions(), "chatId").unwrap();
        assert_eq!(decoded.rows.len(), 1);
        assert_eq!(decoded.rows[0].status, SessionStatus::Idle);
        assert_eq!(decoded.repaired.len(), 1);
    }

    #[test]
    fn a_chat_with_an_unknown_harness_decodes_with_config_dropped() {
        let value = chat("c1", "future-harness");
        let (row, note) = decode_row::<Chat>(&value, &chat_substitutions()).unwrap();
        assert_eq!(row.id, "c1");
        assert!(row.config.is_none(), "unknown harness drops the config");
        assert!(
            note.as_deref().is_some_and(|note| note.contains("config")),
            "the repair note names the dropped field: {note:?}"
        );
    }

    #[test]
    fn chat_substitutions_repair_unknown_reasoning_and_sandbox() {
        let mut value = chat("c2", "claude-code");
        value["config"]["reasoning"] = json!("ultra");
        value["config"]["sandbox"] = json!("stratosphere");
        let (row, note) = decode_row::<Chat>(&value, &chat_substitutions()).unwrap();
        let config = row.config.unwrap();
        assert!(config.reasoning.is_none());
        assert_eq!(config.sandbox, roboco_proto::SandboxLevel::WorkspaceWrite);
        assert!(note.unwrap().contains("config.reasoning"));
    }

    #[test]
    fn an_undecodable_row_with_a_known_id_keeps_its_previous_copy() {
        // Row "kept" is undecodable (a missing required field that repair
        // cannot fix: `updatedAt` missing entirely and not optional).
        let frame = json!([
            {"lastCompletedTurn": null, "chatId": "kept", "deviceId": "d", "status": "idle", "startedAt": null, "updatedAt": "2026-01-01T00:00:00Z"},
            {"lastCompletedTurn": null, "chatId": "bad", "deviceId": "d", "status": "idle", "startedAt": null}
        ]);
        let decoded = decode_rows::<Session>(frame, &session_substitutions(), "chatId").unwrap();
        assert_eq!(decoded.rows.len(), 1);
        assert_eq!(decoded.rows[0].chat_id, "kept");
        // The deletion guard: "bad" is in the frame, so the mirror must keep
        // whatever it already holds for it — it must not read as deleted.
        assert!(decoded.ids.contains("bad"));
        assert_eq!(decoded.errors.len(), 1);
        // ...and a newer engine must never blank the list: a wholly
        // undecodable frame is reported, not applied.
        let bad = decode_rows::<Session>(json!({"wrapped": "nope"}), &session_substitutions(), "chatId");
        assert!(bad.is_err());
    }

    #[test]
    fn an_object_wrapping_exactly_one_array_is_accepted() {
        let frame = json!({"rows": [
            {"lastCompletedTurn": null, "chatId": "c", "deviceId": "d", "status": "idle", "startedAt": null, "updatedAt": "2026-01-01T00:00:00Z"}
        ]});
        let decoded = decode_rows::<Session>(frame, &session_substitutions(), "chatId").unwrap();
        assert_eq!(decoded.rows.len(), 1);
        // Two arrays or a non-array wrapper is a frame error.
        assert!(decode_rows::<Session>(
            json!({"a": [], "b": []}),
            &session_substitutions(),
            "chatId"
        )
        .is_err());
        assert!(decode_rows::<Session>(json!("nope"), &session_substitutions(), "chatId").is_err());
    }

    #[test]
    fn an_unknown_message_part_kind_becomes_the_unsupported_text_part() {
        let mut update = json!({
            "reset": [{
                "id": "m1",
                "role": "user",
                "parts": [
                    {"kind": "text", "id": "p1", "text": "hi"},
                    {"kind": "holo", "id": "p2", "payload": {"x": 1}}
                ],
                "createdAt": 5,
                "deviceId": "d"
            }]
        });
        // Two repairs: the entry (it did not parse as a whole) and the part.
        let repaired = sanitize_transcript_update(&mut update);
        assert_eq!(repaired, 2);
        let parsed: roboco_doc::TranscriptUpdate =
            serde_json::from_value(update.clone()).unwrap();
        let roboco_doc::TranscriptFrame::Reset { reset } = parsed.frame else {
            panic!("expected a reset frame")
        };
        assert_eq!(reset.len(), 1);
        assert_eq!(reset[0].parts.len(), 2);
        match &reset[0].parts[1] {
            roboco_doc::MessagePart::Text { id, text, .. } => {
                assert_eq!(id, "p2");
                assert_eq!(
                    text,
                    "_Unsupported content (holo). Update the app to view it._"
                );
            }
            other => panic!("unexpected part: {other:?}"),
        }
    }

    #[test]
    fn unreadable_entry_scalars_are_repaired_into_a_valid_entry() {
        let mut update = json!({
            "reset": [{
                "id": "m1",
                "role": "narrator",
                "parts": "not-a-list",
                "createdAt": "soon",
                "deviceId": 7,
                "status": "exploded",
                "durationMs": "many"
            }]
        });
        let repaired = sanitize_transcript_update(&mut update);
        assert!(repaired >= 1);
        let parsed: roboco_doc::TranscriptUpdate =
            serde_json::from_value(update).unwrap_or_else(|e| panic!("{e}"));
        let roboco_doc::TranscriptFrame::Reset { reset } = parsed.frame else {
            panic!("expected a reset frame")
        };
        assert_eq!(reset.len(), 1);
        assert_eq!(reset[0].id, "m1");
        assert_eq!(reset[0].device_id, "");
        assert_eq!(reset[0].created_at, 0);
    }

    #[test]
    fn an_unknown_tool_call_becomes_a_generic_tool_chip() {
        let mut update = json!({
            "reset": [{
                "id": "m1",
                "role": "assistant",
                "parts": [
                    {"kind": "tool", "id": "p1", "call": {"kind": "time-machine", "state": "done", "name": "backflip"}}
                ],
                "createdAt": 5,
                "deviceId": "d"
            }]
        });
        let repaired = sanitize_transcript_update(&mut update);
        assert_eq!(repaired, 2);
        let parsed: roboco_doc::TranscriptUpdate =
            serde_json::from_value(update).unwrap();
        let roboco_doc::TranscriptFrame::Reset { reset } = parsed.frame else {
            panic!("expected a reset frame")
        };
        match &reset[0].parts[0] {
            roboco_doc::MessagePart::Tool { call, .. } => {
                match call {
                    roboco_proto::ToolCall::Unknown { name, .. } => {
                        assert_eq!(name, "backflip");
                    }
                    other => panic!("unexpected call: {other:?}"),
                }
            }
            other => panic!("unexpected part: {other:?}"),
        }
    }

    #[test]
    fn unreadable_side_channels_are_removed_but_valid_ones_kept() {
        let mut update = json!({
            "reset": [],
            "contextUsage": {"tokens": "not a number"},
            "replayBaseline": {"also": "nonsense"}
        });
        assert_eq!(sanitize_transcript_update(&mut update), 2);
        assert!(update.get("contextUsage").is_none());
        assert!(update.get("replayBaseline").is_none());
        // A readable baseline stays.
        let mut update = json!({
            "reset": [],
            "replayBaseline": {"entries": {}}
        });
        assert_eq!(sanitize_transcript_update(&mut update), 0);
        assert!(update.get("replayBaseline").is_some());
    }
}
