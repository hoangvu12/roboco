use base64::{Engine as _, engine::general_purpose::STANDARD};
use mimir_plugin_sdk::raw::session_control::EntryChunk;
use serde::Serialize;

/// An `entry-chunk` with its bytes as base64. The SDK's own serde form is a
/// number array that costs up to four bytes per byte and would overflow a
/// frame at the default chunk size.
#[derive(Debug, Serialize)]
pub struct ChunkResult {
    pub id: String,
    pub offset: u64,
    pub total: u64,
    pub length: usize,
    pub encoding: &'static str,
    pub data: String,
}

impl From<EntryChunk> for ChunkResult {
    fn from(chunk: EntryChunk) -> Self {
        Self {
            length: chunk.bytes.len(),
            data: STANDARD.encode(&chunk.bytes),
            id: chunk.id,
            offset: chunk.offset,
            total: chunk.total,
            encoding: "base64",
        }
    }
}

#[derive(Debug, Serialize)]
pub struct Attached<'a, T: Serialize> {
    pub session: &'a str,
    pub state: &'a T,
    pub reused: bool,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::limits::{Limits, LimitsRequest, MAX_FRAME_BYTES, MIN_FRAME_BYTES};
    use crate::methods::Call;
    use crate::outbox::{Fed, Outbox, Reassembler};
    use crate::wire::{Id, Incoming, parse_frame, response};
    use mimir_plugin_sdk::raw::session_control::{
        DisplayEntry, DisplayItem, EntryHeader, EntryPage, JournalCut, JournalRef, PageEntry,
    };
    use serde_json::Value;

    fn id() -> Id {
        match parse_frame(br#"{"jsonrpc":"2.0","id":1,"method":"m"}"#) {
            Incoming::Request { id, .. } => id,
            other => panic!("{other:?}"),
        }
    }

    fn chunk(len: usize, fill: u8) -> EntryChunk {
        EntryChunk { id: "e".into(), offset: 0, total: len as u64, bytes: vec![fill; len] }
    }

    #[test]
    fn the_sdk_number_array_form_overflows_a_default_frame_and_base64_does_not() {
        let limits = Limits::default();
        let raw = chunk(limits.chunk_bytes, 0xFF);
        let naive = serde_json::to_vec(&raw).unwrap();
        assert!(naive.len() > limits.max_frame_bytes, "naive form is {} bytes", naive.len());
        let wire = response(&id(), &ChunkResult::from(raw));
        assert!(wire.len() <= limits.max_frame_bytes, "base64 form is {} bytes", wire.len());
        assert!(wire.len() * 2 < naive.len());
    }

    #[test]
    fn base64_chunks_round_trip_every_byte_value() {
        let bytes: Vec<u8> = (0..=255u8).cycle().take(100_000).collect();
        let result = ChunkResult::from(EntryChunk { id: "e".into(), offset: 9, total: 10, bytes: bytes.clone() });
        assert_eq!((result.length, result.offset, result.total), (bytes.len(), 9, 10));
        assert_eq!(STANDARD.decode(&result.data).unwrap(), bytes);
        assert_eq!(result.encoding, "base64");
    }

    #[test]
    fn a_chunk_at_every_negotiated_budget_fits_one_frame() {
        for frame in [MIN_FRAME_BYTES, 100_000, 1 << 20, MAX_FRAME_BYTES] {
            let limits = Limits::negotiate(LimitsRequest { max_frame_bytes: Some(frame), chunk_bytes: Some(usize::MAX) }).unwrap();
            let wire = response(&id(), &ChunkResult::from(chunk(limits.chunk_bytes, 0xFF)));
            assert!(wire.len() <= frame, "frame {frame}: chunk response {}", wire.len());
        }
    }

    fn tiny_page(entries: usize, oversized: bool) -> EntryPage {
        EntryPage {
            cut: JournalCut { journal: JournalRef::Root, leaf: Some("x".repeat(40)) },
            entries: (0..entries)
                .map(|n| {
                    let id = format!("{n:08x}");
                    if oversized {
                        PageEntry::Oversized(EntryHeader { id, bytes: u64::MAX })
                    } else {
                        PageEntry::Entry(DisplayEntry { id, item: DisplayItem::BranchSummary(String::new()) })
                    }
                })
                .collect(),
            bytes: 0,
            older: true,
            newer: true,
        }
    }

    #[test]
    fn the_densest_page_the_clamps_allow_stays_inside_the_frame() {
        for frame in [MIN_FRAME_BYTES, 100_000, 1 << 20, 4 << 20, MAX_FRAME_BYTES] {
            let limits = Limits::negotiate(LimitsRequest { max_frame_bytes: Some(frame), chunk_bytes: None }).unwrap();
            let max_entries = limits.page_entries(u32::MAX) as usize;
            let host_bytes = limits.page_bytes(u32::MAX) as usize;
            let entry_json = br#"{"id":"00000000","item":{"branch_summary":""}}"#.len();
            let by_bytes = host_bytes / entry_json;
            for oversized in [false, true] {
                let count = max_entries.min(if oversized { usize::MAX } else { by_bytes });
                let wire = response(&id(), &tiny_page(count, oversized));
                assert!(wire.len() <= frame, "frame {frame} oversized={oversized}: {} bytes for {count} entries", wire.len());
            }
        }
    }

    #[test]
    fn a_page_beyond_the_clamps_is_still_delivered_whole_by_fragments() {
        let limits = Limits::default();
        let wire = response(&id(), &tiny_page(40_000, false));
        assert!(wire.len() > limits.max_frame_bytes);
        let mut outbox = Outbox::new(limits);
        outbox.push_control(wire.clone());
        let mut reassembler = Reassembler::default();
        let mut rebuilt = None;
        while let Some(line) = outbox.next_line() {
            assert!(line.len() <= limits.max_frame_bytes);
            if let Fed::Frame(frame) = reassembler.feed(&line).unwrap() {
                rebuilt = Some(frame);
            }
        }
        let rebuilt = rebuilt.unwrap();
        assert_eq!(rebuilt, wire);
        let value: Value = serde_json::from_slice(&rebuilt).unwrap();
        assert_eq!(value["result"]["entries"].as_array().unwrap().len(), 40_000);
    }

    #[test]
    fn requested_pages_resolve_through_parse_call_to_the_clamped_selection() {
        let limits = Limits::negotiate(LimitsRequest { max_frame_bytes: Some(MIN_FRAME_BYTES), chunk_bytes: None }).unwrap();
        let raw = serde_json::value::to_raw_value(&serde_json::json!({
            "session": "s", "cut": {"journal": "root", "leaf": null}, "max_entries": 4096, "max_bytes": 4194304
        }))
        .unwrap();
        let Ok(Call::ReadEntries { selection, .. }) = crate::methods::parse_call("session.read_entries", Some(&raw), &limits) else { panic!() };
        assert_eq!(selection.max_entries, 256);
        assert_eq!(selection.max_bytes as usize, MIN_FRAME_BYTES / 4 * 3);
    }

    fn round_trips<T: serde::Serialize + serde::de::DeserializeOwned>(wire: Value) -> T {
        let parsed: T = serde_json::from_value(wire.clone()).unwrap();
        assert_eq!(serde_json::to_value(&parsed).unwrap(), wire);
        parsed
    }

    fn child_wire(cut: Value) -> Value {
        serde_json::json!({
            "handle": "agent-d9f04c6fe3392859", "attempt": 2, "profile": "general-purpose",
            "description": "Probe child", "model": "fake/fake-model", "status": "running",
            "background": true, "spawned_by": null, "completion_pending": false,
            "presentation": {
                "attempt": 2, "agent_id": "agent-d9f04c6fe3392859", "agent": "general-purpose",
                "model": "fake/fake-model", "description": "Probe child", "status": "running",
                "continued": true, "background": true, "turns": 1, "timeout_ms": null,
                "remaining_ms": null, "phase": "running", "tool_uses": 0, "tokens": 18,
                "context_percent": 0, "compactions": 0, "elapsed_ms": 40
            },
            "cut": cut,
        })
    }

    #[test]
    fn a_child_cut_is_optional_and_a_missing_cut_is_null() {
        use mimir_plugin_sdk::raw::session_control::ChildInfo;
        let known = round_trips::<ChildInfo>(child_wire(serde_json::json!({"journal": {"child": "agent-d9f04c6fe3392859"}, "leaf": "l1"})));
        assert!(matches!(known.cut, Some(JournalCut { journal: JournalRef::Child(ref handle), .. }) if handle == "agent-d9f04c6fe3392859"));
        let unreadable = round_trips::<ChildInfo>(child_wire(Value::Null));
        assert!(unreadable.cut.is_none());
        let mut absent = child_wire(Value::Null);
        absent.as_object_mut().unwrap().remove("cut");
        let parsed: ChildInfo = serde_json::from_value(absent).unwrap();
        assert!(parsed.cut.is_none());
        assert_eq!(serde_json::to_value(&parsed).unwrap()["cut"], Value::Null);
    }

    #[test]
    fn view_children_are_child_or_oversized_items() {
        use mimir_plugin_sdk::raw::session_control::{ViewChild, ViewItem};
        let full = round_trips::<ViewItem>(serde_json::json!({"child": {"child": child_wire(Value::Null)}}));
        assert!(matches!(full, ViewItem::Child(ViewChild::Child(ref info)) if info.attempt == 2 && info.cut.is_none()));
        let header = serde_json::json!({
            "handle": "agent-d9f04c6fe3392859", "attempt": 3, "status": "completed",
            "completion_pending": true, "cut": {"journal": {"child": "agent-d9f04c6fe3392859"}, "leaf": null}, "bytes": 2_000_000
        });
        let oversized = round_trips::<ViewItem>(serde_json::json!({"child": {"oversized": header}}));
        assert!(matches!(oversized, ViewItem::Child(ViewChild::Oversized(ref h)) if h.bytes == 2_000_000 && h.completion_pending));
        let unreadable = serde_json::json!({"child": {"oversized": {
            "handle": "agent-d9f04c6fe3392859", "attempt": 1, "status": "stopped",
            "completion_pending": false, "cut": null, "bytes": 70_000
        }}});
        assert!(matches!(round_trips::<ViewItem>(unreadable), ViewItem::Child(ViewChild::Oversized(ref h)) if h.cut.is_none()));
        assert!(serde_json::from_value::<ViewItem>(serde_json::json!({"child": {"header": {}}})).is_err());
    }

    #[test]
    fn a_start_carries_its_children_beside_the_root_cut() {
        use mimir_plugin_sdk::raw::session_control::{ViewChild, ViewItem};
        let start = serde_json::json!({"start": {
            "state": {
                "info": {"id": "s1", "cwd": "/work", "title": null, "live": true},
                "configuration": {"provider": "fake", "model": "fake-model", "reasoning": null, "mode": "build"},
                "active_request": null, "requests": [], "plan": null, "goal": null, "user_request": null
            },
            "children": [
                {"child": child_wire(serde_json::json!({"journal": {"child": "agent-d9f04c6fe3392859"}, "leaf": "l9"}))},
                {"oversized": {"handle": "agent-0000000000000001", "attempt": 1, "status": "queued",
                               "completion_pending": false, "cut": null, "bytes": 900_000}}
            ],
            "root": {"cut": {"journal": "root", "leaf": "r1"}, "live_invocations": []},
            "recovering": false
        }});
        let ViewItem::Start(parsed) = round_trips::<ViewItem>(start) else { panic!("not a start") };
        assert_eq!(parsed.children.len(), 2);
        assert!(matches!(parsed.children[0], ViewChild::Child(_)));
        assert!(matches!(parsed.children[1], ViewChild::Oversized(_)));
    }

    #[test]
    fn child_controls_and_outcomes_keep_their_exact_forms() {
        use mimir_plugin_sdk::raw::session_control::{ChildControl, ChildOutcome};
        assert!(matches!(round_trips::<ChildControl>(serde_json::json!("accepted")), ChildControl::Accepted));
        assert!(matches!(round_trips::<ChildControl>(serde_json::json!("terminal")), ChildControl::Terminal));
        assert!(matches!(round_trips::<ChildControl>(serde_json::json!("finalizing")), ChildControl::Finalizing));
        assert!(matches!(round_trips::<ChildControl>(serde_json::json!({"attempt_changed": 2})), ChildControl::AttemptChanged(2)));
        let outcome = serde_json::json!({
            "handle": "agent-d9f04c6fe3392859", "attempt": 1, "status": "completed", "result": "child result",
            "error": null, "changed_files": [],
            "usage": {"input_tokens": 11, "output_tokens": 7, "reasoning_tokens": 0, "cache_read_tokens": 0,
                      "cache_write_tokens": 0, "total_tokens": 18, "provider_usage": {"root": 0, "nodes": ["null"]}}
        });
        let parsed: ChildOutcome = serde_json::from_value(outcome).unwrap();
        assert_eq!((parsed.attempt, parsed.result.as_deref()), (1, Some("child result")));
    }
}
