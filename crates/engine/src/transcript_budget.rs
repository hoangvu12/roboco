//! Budgeted transcript selection: the messages delivered in front of a
//! prompt that starts a provider session lacking the chat's prior context.
//!
//! A forked side chat's first prompt used to paste the entire source
//! transcript — every tool call included — as raw JSON in front of the new
//! message, with no size limit: long chats burned the new session's context
//! before it started (t3-orchestration-v2 spec story 58, ticket 01). The
//! transcript in front of the message is now a budgeted selection:
//!
//! 1. the latest user message, the latest assistant message and the first
//!    user message;
//! 2. then the newest remaining messages, newest first, until the budget is
//!    spent.
//!
//! **Contents:** user and assistant text, command runs (input, exit code,
//! output), errors and file-change names. Reasoning (the model's private
//! scratchpad) and attachments are left out. Long items are truncated.
//!
//! **Budget:** 16k tokens, counted at one token per UTF-8 byte — a
//! deliberately pessimistic proxy, not a tokenizer. It bounds everything the
//! wrapper puts in front of the new user message; the new message itself is
//! never counted and never shortened.
//!
//! A transcript that fits the budget whole is delivered complete, with no
//! note. One that does not carries a **coverage header** naming how many
//! messages were omitted and how to read them back with the `read_chat` MCP
//! tool using the source chat's id.
//!
//! One engine piece on purpose: every path that must hand a chat's prior
//! transcript to a session that lacks it reuses
//! [`budgeted_transcript_prompt`] (or its prefix form,
//! [`budgeted_transcript_prefix`], which the native-fork fallback rides) —
//! the fork bootstrap (ticket 01), the account-failover fallback (ticket
//! 09), the native-fork fallbacks (17–20), forks into another harness (22)
//! and the one-way harness switch (24).

use roboco_doc::{join_continuation_entries, MessagePart, MessageRole, SessionMessageEntry};
use roboco_proto::{ToolCall, ToolDiff};

/// The transcript budget: 16k "tokens" at one token per UTF-8 byte, bounding
/// everything delivered in front of the new user message (wrapper, coverage
/// header and transcript — never the new message itself).
pub const TRANSCRIPT_BUDGET_BYTES: usize = 16 * 1024;

/// Cap for any single delivered message once the transcript must be
/// selected: no one message may eat more than a quarter of the budget, so
/// the three priority messages always fit together with room for recent
/// context. A transcript that fits whole is never truncated, whatever this
/// cap says.
const ITEM_CAP_BYTES: usize = TRANSCRIPT_BUDGET_BYTES / 4;

/// Smallest truncated delivery worth its bytes: below this an item is
/// omitted rather than shipped as a meaningless stub.
const MIN_ITEM_BYTES: usize = 64;

/// The lead is shared by every budgeted-history use (ticket 01): a side
/// chat's fork bootstrap, a main chat's harness switch, and an account
/// failover's fresh-session fallback — so it names neither of them, only
/// the chat and the transcript.
const LEAD: &str = "Continue this chat using the following prior transcript as context.";

/// The wrapper lead for callers without a special one (the sessions
/// switch-seam paths pick theirs).
pub(crate) fn default_lead() -> &'static str {
    LEAD
}
const TRANSCRIPT_OPEN: &str = "<transcript>\n";
const TRANSCRIPT_CLOSE: &str = "\n</transcript>\n\n";
const TRUNCATION_MARKER: &str = " …[truncated]";

/// One transcript entry rendered for handoff: its role plus the parts the
/// contents policy keeps.
struct TranscriptItem {
    role: MessageRole,
    text: String,
}

/// The delivered prompt: the budgeted transcript of `entries` in front of
/// `prompt`, or `None` when the entries carry no deliverable messages (the
/// caller then sends `prompt` as is).
///
/// `source_chat_id` is the chat whose transcript the omitted messages are
/// readable in — the fork's source for the fork bootstrap, the chat itself
/// for harness switches and account failovers.
#[cfg(test)]
pub fn budgeted_transcript_prompt(
    entries: &[SessionMessageEntry],
    source_chat_id: Option<&str>,
    prompt: &str,
) -> Option<String> {
    budgeted_transcript_prefix(entries, source_chat_id)
        .map(|prefix| format!("{prefix}{prompt}"))
}

/// The budgeted transcript alone (ticket 17): everything
/// [`budgeted_transcript_prompt`] would put in front of the new user
/// message, without the message. The native-fork path rides this as the
/// request's fallback history — the driver pastes it in front of the prompt
/// only when the harness refuses the fork, so a successful fork pastes
/// nothing at all.
pub fn budgeted_transcript_prefix(
    entries: &[SessionMessageEntry],
    source_chat_id: Option<&str>,
) -> Option<String> {
    budgeted_transcript_prefix_with_lead(entries, source_chat_id, LEAD)
}

/// [`budgeted_transcript_prefix`] with the wrapper lead the caller chose (a
/// provider resuming its own recorded session in a switched chat, upstream
/// #966: "while you were away…" / "you are now taking over…").
pub fn budgeted_transcript_prefix_with_lead(
    entries: &[SessionMessageEntry],
    source_chat_id: Option<&str>,
    lead: &str,
) -> Option<String> {
    let items = transcript_items(entries);
    let lines: Vec<String> = items
        .iter()
        .map(|item| format!("{}: {}", role_label(item.role), item.text))
        .collect();
    if lines.is_empty() {
        return None;
    }
    let wrapper_fixed = lead.len() + 1 + TRANSCRIPT_OPEN.len() + TRANSCRIPT_CLOSE.len();
    let transcript = lines.join("\n");
    if wrapper_fixed + transcript.len() <= TRANSCRIPT_BUDGET_BYTES {
        // The whole transcript fits: complete, verbatim, no coverage note.
        return Some(wrap_with_lead(None, &lines, lead));
    }

    // Selection mode. The header's length depends on the omitted count, so
    // reserve its worst case up front (the largest count is all items);
    // intermediate selections then cannot grow past the budget.
    let header_max = coverage_header_max(items.len(), source_chat_id);
    let mut remaining = TRANSCRIPT_BUDGET_BYTES
        .saturating_sub(wrapper_fixed)
        .saturating_sub(header_max);
    let mut delivered: Vec<Option<String>> = vec![None; lines.len()];

    // Priorities, in order: the latest user message, the latest assistant
    // message, the first user message. Never dropped while any room remains
    // — an oversized one is truncated to the smaller of the per-item cap and
    // what is left.
    let last_user = items.iter().rposition(|item| item.role == MessageRole::User);
    let last_assistant = items
        .iter()
        .rposition(|item| item.role == MessageRole::Assistant);
    let first_user = items.iter().position(|item| item.role == MessageRole::User);
    for idx in [last_user, last_assistant, first_user].into_iter().flatten() {
        if delivered[idx].is_some() {
            continue;
        }
        let cap = ITEM_CAP_BYTES.min(remaining.saturating_sub(1));
        if cap < MIN_ITEM_BYTES {
            break; // nothing meaningful fits any more
        }
        let line = truncate_line(&lines[idx], cap);
        remaining -= line.len() + 1;
        delivered[idx] = Some(line);
    }

    // Fill with the newest remaining messages, newest first. An item that
    // does not fit (after its per-item cap) is skipped whole — counted as
    // omitted — and older items still get their chance.
    for idx in (0..lines.len()).rev() {
        if delivered[idx].is_some() {
            continue;
        }
        let Some(line) = fill_line(&lines[idx], remaining) else {
            continue;
        };
        remaining -= line.len() + 1;
        delivered[idx] = Some(line);
    }

    let omitted = delivered.iter().filter(|slot| slot.is_none()).count();
    let selected: Vec<String> = delivered.into_iter().flatten().collect();
    if selected.is_empty() {
        // Unreachable with the shipped budget (three capped priorities
        // always fit); degrade to the verbatim transcript rather than send
        // an empty block.
        return Some(wrap_with_lead(None, &lines, lead));
    }
    let header = coverage_header(omitted, source_chat_id);
    Some(wrap_with_lead(Some(header), &selected, lead))
}

/// Render transcript entries into handoff items. Continuation entries are
/// fragments of one logical message (entries split at `MSG_INLINE_MAX`), so
/// they are joined first: the budget and the omission count must see
/// messages, not fragments. System entries (fork seams, system notices) are
/// structure, not transcript, and never render.
fn transcript_items(entries: &[SessionMessageEntry]) -> Vec<TranscriptItem> {
    let joined = join_continuation_entries(entries.to_vec());
    let mut out = Vec::with_capacity(joined.len());
    for entry in joined {
        if entry.role == MessageRole::System {
            continue;
        }
        let text = render_parts(&entry.parts);
        if text.trim().is_empty() {
            continue;
        }
        out.push(TranscriptItem {
            role: entry.role,
            text,
        });
    }
    out
}

/// The delivered label for a role.
fn role_label(role: MessageRole) -> &'static str {
    match role {
        MessageRole::User => "user",
        MessageRole::Assistant => "assistant",
        // Unreachable: system entries never become items.
        MessageRole::System => "system",
    }
}

/// The parts of one entry, joined: the contents policy in code.
fn render_parts(parts: &[MessagePart]) -> String {
    let mut blocks: Vec<String> = Vec::new();
    for part in parts {
        let block = match part {
            MessagePart::Text { text, .. } => {
                if text.trim().is_empty() {
                    continue;
                }
                text.clone()
            }
            MessagePart::Error { message, .. } => format!("Error: {message}"),
            MessagePart::Tool { .. } => render_command_run(part),
            // Reasoning is the model's private scratchpad; images are
            // attachments; input requests, fork seams, harness-switch seams
            // and completion notices are structure — none of them belong in
            // a budgeted summary.
            MessagePart::Reasoning { .. }
            | MessagePart::Image { .. }
            | MessagePart::Input { .. }
            | MessagePart::Fork { .. }
            | MessagePart::HarnessSwitch { .. }
            | MessagePart::CompletionNotice { .. } => continue,
        };
        blocks.push(block);
    }
    blocks.join("\n")
}

/// One command run: the input, the exit code, the output, the file changes.
///
/// The doc records no numeric exit status — the fold keeps the failure flag,
/// which carries the same signal for a handoff (`0` ok, `1` failed,
/// `unknown` while the call is unresolved). `output` is the persisted
/// one-line summary; full outputs live outside the doc. `diff_stats` (and
/// the legacy inline `diff`) name the files the run changed.
fn render_command_run(part: &MessagePart) -> String {
    let MessagePart::Tool {
        call,
        is_error,
        resolved,
        output,
        diff,
        diff_stats,
        ..
    } = part
    else {
        return String::new();
    };
    let mut lines = vec![format!("Command: {}", describe_call(call))];
    let exit = match (*resolved, *is_error) {
        (true, false) => "0",
        (true, true) => "1",
        (false, _) => "unknown",
    };
    lines.push(format!("Exit code: {exit}"));
    if let Some(output) = output.as_deref().filter(|output| !output.trim().is_empty()) {
        lines.push(output.to_owned());
    }
    let mut paths: Vec<&str> = Vec::new();
    if let Some(stats) = diff_stats {
        paths.extend(stats.iter().map(|stat| stat.path.as_str()));
    }
    if let Some(diff) = diff {
        paths.push(diff.path.as_str()); // pre-strip docs kept inline diffs
    }
    if !paths.is_empty() {
        lines.push(format!("File changes: {}", paths.join(", ")));
    }
    lines.join("\n")
}

/// Compact rendering of a (already privacy-sanitized) tool call — the input
/// line of a command run. Heavy inputs never reach the doc (the render
/// policy strips them), so this stays short by construction.
fn describe_call(call: &ToolCall) -> String {
    match call {
        ToolCall::Exec { command } => command.clone(),
        ToolCall::ReadFile { path } => format!("read {path}"),
        ToolCall::WriteFile { path, .. } => format!("write {path}"),
        ToolCall::EditFile { path, .. } => format!("edit {path}"),
        ToolCall::ApplyPatch { path } => match path {
            Some(path) => format!("patch {path}"),
            None => "patch".to_owned(),
        },
        ToolCall::Search { pattern, .. } => format!("search {pattern}"),
        ToolCall::Glob { pattern } => format!("glob {pattern}"),
        ToolCall::WebFetch { url, .. } => format!("fetch {url}"),
        ToolCall::WebSearch { query } => format!("search web {query}"),
        ToolCall::Todo { items } => format!("todo ({} items)", items.len()),
        ToolCall::Mcp { server, tool, .. } => format!("{server}/{tool}"),
        ToolCall::Unknown { name, .. } => name.clone(),
    }
}

/// A fill candidate: its per-item-capped form, included only when it fits
/// the remaining budget — otherwise the message is omitted whole.
fn fill_line(line: &str, remaining: usize) -> Option<String> {
    if remaining <= MIN_ITEM_BYTES {
        return None;
    }
    let capped = truncate_line(line, ITEM_CAP_BYTES);
    (capped.len() + 1 <= remaining).then_some(capped)
}

/// Truncate to at most `cap` bytes (marker included), at a UTF-8 char
/// boundary, marking the cut so the reader knows there was more.
fn truncate_line(line: &str, cap: usize) -> String {
    if line.len() <= cap {
        return line.to_owned();
    }
    let keep = cap.saturating_sub(TRUNCATION_MARKER.len());
    let mut end = keep.min(line.len());
    while end > 0 && !line.is_char_boundary(end) {
        end -= 1;
    }
    let mut out = line[..end].to_owned();
    out.push_str(TRUNCATION_MARKER);
    out
}

/// The coverage header for a partial transcript: how many messages were
/// omitted, and how to read them back. A selection that omitted nothing but
/// truncated something still points at `read_chat` — the cut text is just
/// as recoverable as the omitted messages.
fn coverage_header(omitted: usize, source_chat_id: Option<&str>) -> String {
    let lead = if omitted > 0 {
        format!("This transcript is partial: {omitted} messages were omitted.")
    } else {
        "This transcript is partial: long messages were truncated.".to_owned()
    };
    match source_chat_id {
        Some(chat_id) => format!(
            "{lead} Read the full transcript with the `read_chat` MCP tool using chat id \"{chat_id}\"."
        ),
        None => lead,
    }
}

/// The worst-case header length for a given item count: the largest omitted
/// count is all items (widest counter), plus the count-free truncation
/// variant, plus the trailing newline.
fn coverage_header_max(items: usize, source_chat_id: Option<&str>) -> usize {
    coverage_header(items, source_chat_id)
        .len()
        .max(coverage_header(0, source_chat_id).len())
        + 1
}

/// Assemble the budgeted transcript: lead, optional coverage header, and
/// the transcript block — ending with the blank line that separates it
/// from whatever follows (the new user message, whole and always last).
fn wrap(header: Option<String>, lines: &[String]) -> String {
    wrap_with_lead(header, lines, LEAD)
}

/// [`wrap`] with the lead the caller chose — a provider taking over a
/// switched conversation (upstream #966) says so instead of the generic
/// continuation lead.
fn wrap_with_lead(header: Option<String>, lines: &[String], lead: &str) -> String {
    let mut out =
        String::with_capacity(lead.len() + TRANSCRIPT_OPEN.len() + TRANSCRIPT_CLOSE.len() + 256);
    out.push_str(lead);
    out.push('\n');
    if let Some(header) = header {
        out.push_str(&header);
        out.push('\n');
    }
    out.push_str(TRANSCRIPT_OPEN);
    out.push_str(&lines.join("\n"));
    out.push_str(TRANSCRIPT_CLOSE);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use roboco_doc::{MessageStatus, ToolDiffStat};

    fn entry(id: &str, role: MessageRole, parts: Vec<MessagePart>) -> SessionMessageEntry {
        SessionMessageEntry {
            duration_ms: None,
            origin_chat_id: None,
            provider_turn: None,
            id: id.into(),
            role,
            parts,
            created_at: 1,
            device_id: "device".into(),
            status: Some(MessageStatus::Complete),
            continuation_of: None,
        }
    }

    fn user(id: &str, body: &str) -> SessionMessageEntry {
        entry(
            id,
            MessageRole::User,
            vec![MessagePart::Text {
                id: format!("{id}-t"),
                text: body.into(),
            }],
        )
    }

    fn assistant(id: &str, body: &str) -> SessionMessageEntry {
        entry(
            id,
            MessageRole::Assistant,
            vec![MessagePart::Text {
                id: format!("{id}-t"),
                text: body.into(),
            }],
        )
    }

    fn tool_part(id: &str, command: &str) -> MessagePart {
        MessagePart::Tool {
            id: id.into(),
            call: ToolCall::Exec {
                command: command.into(),
            },
            is_error: false,
            resolved: true,
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

    const NEW_MESSAGE: &str = "NEW: what next?";

    /// Everything the wrapper puts in front of the new message (the
    /// budgeted part).
    fn delivered_prefix(prompt: &str) -> &str {
        &prompt[..prompt.len() - NEW_MESSAGE.len()]
    }

    /// The count inside "N messages were omitted".
    fn omitted_count(prompt: &str) -> usize {
        let at = prompt
            .find(" messages were omitted")
            .expect("coverage header");
        let start = prompt[..at]
            .char_indices()
            .rev()
            .find(|(_, c)| !c.is_ascii_digit())
            .map(|(i, _)| i + 1)
            .unwrap_or(0);
        prompt[start..at].parse().expect("omitted count")
    }

    #[test]
    fn a_fitting_transcript_rides_complete_with_no_note() {
        let entries = vec![
            user("u1", "Remember PINEAPPLE"),
            assistant("a1", "I remember PINEAPPLE"),
            user("u2", "And the code?"),
        ];
        let prompt = budgeted_transcript_prompt(&entries, Some("main"), NEW_MESSAGE).unwrap();
        assert!(prompt.ends_with(NEW_MESSAGE));
        for body in ["Remember PINEAPPLE", "I remember PINEAPPLE", "And the code?"] {
            assert!(prompt.contains(body), "missing {body}: {prompt}");
        }
        // Complete means complete: no omission note, no read pointer, no cuts.
        assert!(!prompt.contains("omitted"), "{prompt}");
        assert!(!prompt.contains("read_chat"), "{prompt}");
        assert!(!prompt.contains(TRUNCATION_MARKER), "{prompt}");
        assert!(prompt.contains(LEAD), "{prompt}");
        assert!(prompt.contains("<transcript>"), "{prompt}");
        // Delivered in transcript order.
        assert!(
            prompt.find("Remember PINEAPPLE").unwrap() < prompt.find("And the code?").unwrap()
        );
    }

    #[test]
    fn selection_keeps_priorities_then_fills_newest_first_within_the_budget() {
        let mut entries = vec![
            user("u0", "ORIGINAL: build the parser"),
            assistant("a0", "on it"),
        ];
        for i in 1..=20 {
            entries.push(user(&format!("u{i}"), &format!("fill-{i}: {}", "x".repeat(900))));
            entries.push(assistant(&format!("a{i}"), &format!("note-{i}: {}", "y".repeat(90))));
        }
        entries.push(user("u99", "LATEST-Q: ship it"));
        entries.push(assistant("a99", "LATEST-A: done"));
        let prompt = budgeted_transcript_prompt(&entries, Some("main"), NEW_MESSAGE).unwrap();
        // The three priorities survive every budget.
        for body in ["ORIGINAL: build the parser", "LATEST-Q: ship it", "LATEST-A: done"] {
            assert!(prompt.contains(body), "missing priority {body}: {prompt}");
        }
        // Recent context is preferred over the middle of the transcript.
        assert!(prompt.contains("fill-20:"), "{prompt}");
        assert!(prompt.contains("note-20:"), "{prompt}");
        assert!(!prompt.contains("fill-2:"), "{prompt}");
        // The coverage header names the omitted count and the source chat.
        assert!(omitted_count(&prompt) >= 1);
        assert!(prompt.contains("read_chat"), "{prompt}");
        assert!(prompt.contains("chat id \"main\""), "{prompt}");
        // Everything in front of the new message fits the budget.
        assert!(
            delivered_prefix(&prompt).len() <= TRANSCRIPT_BUDGET_BYTES,
            "delivered {} bytes",
            delivered_prefix(&prompt).len()
        );
        assert!(prompt.ends_with(NEW_MESSAGE));
    }

    #[test]
    fn an_oversized_message_is_truncated_not_dropped() {
        let entries = vec![
            user("u0", "FIRST: the original request"),
            assistant("a0", "acknowledged"),
            user("u1", &format!("BIG {}", "z".repeat(30_000))),
            assistant("a1", "done"),
        ];
        let prompt = budgeted_transcript_prompt(&entries, Some("main"), NEW_MESSAGE).unwrap();
        assert!(prompt.contains("BIG "), "{prompt}");
        assert!(prompt.contains(TRUNCATION_MARKER), "{prompt}");
        // The 30k body was cut to a bounded prefix: most of it is gone, and
        // nothing close to the full body survived.
        assert!(prompt.contains(&"z".repeat(4000)), "{prompt}");
        assert!(!prompt.contains(&"z".repeat(5000)), "{prompt}");
        // The other messages still ride along.
        assert!(prompt.contains("FIRST: the original request"), "{prompt}");
        assert!(prompt.contains("done"), "{prompt}");
        assert!(
            delivered_prefix(&prompt).len() <= TRANSCRIPT_BUDGET_BYTES,
            "delivered {} bytes",
            delivered_prefix(&prompt).len()
        );
        // Truncated-but-nothing-omitted still points at read_chat.
        assert!(!prompt.contains("messages were omitted"), "{prompt}");
        assert!(prompt.contains("read_chat"), "{prompt}");
    }

    #[test]
    fn the_new_user_message_is_never_shortened() {
        let entries = vec![
            user("u0", "ORIGINAL: build the parser"),
            assistant("a0", "on it"),
            user("u1", &format!("BIG {}", "z".repeat(30_000))),
            assistant("a1", "done"),
        ];
        let new_message = format!("{} {}", "NEW:", "n".repeat(60_000));
        let prompt = budgeted_transcript_prompt(&entries, Some("main"), &new_message).unwrap();
        assert!(prompt.ends_with(&new_message));
        assert!(prompt.contains(&new_message));
        // The oversized new message never ate the transcript's budget.
        assert!(prompt.contains("ORIGINAL: build the parser"), "{prompt}");
        assert!(
            prompt.len() - new_message.len() <= TRANSCRIPT_BUDGET_BYTES,
            "delivered {} bytes",
            prompt.len() - new_message.len()
        );
    }

    #[test]
    fn contents_policy_keeps_text_runs_errors_and_file_changes_only() {
        let entries = vec![
            entry(
                "u1",
                MessageRole::User,
                vec![
                    MessagePart::Text {
                        id: "u1-t".into(),
                        text: "look at this".into(),
                    },
                    MessagePart::Reasoning {
                        id: "u1-r".into(),
                        text: "PRIVATE SCRATCHPAD".into(),
                    },
                    MessagePart::Image {
                        id: "u1-i".into(),
                        path: "/uploads/p.png".into(),
                        name: "p.png".into(),
                        mime_type: "image/png".into(),
                    },
                    MessagePart::Input {
                        id: "u1-in".into(),
                        request_id: "req-1".into(),
                        questions: vec![],
                        resolved: true,
                    },
                ],
            ),
            entry(
                "a1",
                MessageRole::Assistant,
                vec![
                    MessagePart::Tool {
                        id: "a1-tool".into(),
                        call: ToolCall::Exec {
                            command: "cargo test".into(),
                        },
                        is_error: true,
                        resolved: true,
                        output: Some("3 failed".into()),
                        diff: None,
                        output_ref: None,
                        output_bytes: None,
                        diff_ref: None,
                        diff_stats: Some(vec![ToolDiffStat {
                            path: "/w/a.rs".into(),
                            additions: 2,
                            deletions: 1,
                        }]),
                        subagent_ref: None,
                        subagent_status: None,
                        subagent_tail: None,
                    },
                    MessagePart::Error {
                        id: "a1-e".into(),
                        message: "boom".into(),
                    },
                    MessagePart::Text {
                        id: "a1-t".into(),
                        text: "here is what happened".into(),
                    },
                ],
            ),
            // System entries (the fork seam) are structure, not transcript.
            entry("seam", MessageRole::System, vec![MessagePart::Fork {
                id: "fork:x".into(),
                source_chat_id: "main".into(),
                source_title: "Main".into(),
            }]),
            // An entry that renders blank (reasoning only) is no message.
            entry(
                "a2",
                MessageRole::Assistant,
                vec![MessagePart::Reasoning {
                    id: "a2-r".into(),
                    text: "thinking only".into(),
                }],
            ),
        ];
        let prompt = budgeted_transcript_prompt(&entries, Some("main"), NEW_MESSAGE).unwrap();
        assert!(prompt.contains("look at this"), "{prompt}");
        assert!(prompt.contains("Command: cargo test"), "{prompt}");
        assert!(prompt.contains("Exit code: 1"), "{prompt}");
        assert!(prompt.contains("3 failed"), "{prompt}");
        assert!(prompt.contains("File changes: /w/a.rs"), "{prompt}");
        assert!(prompt.contains("Error: boom"), "{prompt}");
        assert!(prompt.contains("here is what happened"), "{prompt}");
        assert!(prompt.contains("user: look at this"), "{prompt}");
        assert!(prompt.contains("assistant: Command: cargo test"), "{prompt}");
        assert!(!prompt.contains("PRIVATE SCRATCHPAD"), "{prompt}");
        assert!(!prompt.contains("p.png"), "{prompt}");
        assert!(!prompt.contains("req-1"), "{prompt}");
        assert!(!prompt.contains("fork:x"), "{prompt}");
        assert!(!prompt.contains("Main"), "{prompt}");
        assert!(!prompt.contains("thinking only"), "{prompt}");
    }

    #[test]
    fn unresolved_calls_report_an_unknown_exit_code() {
        let mut part = tool_part("t", "ls");
        if let MessagePart::Tool {
            resolved, is_error, ..
        } = &mut part
        {
            *resolved = false;
            *is_error = false;
        }
        let entries = vec![entry(
            "a1",
            MessageRole::Assistant,
            vec![part, tool_part("t2", "pwd")],
        )];
        let prompt = budgeted_transcript_prompt(&entries, None, NEW_MESSAGE).unwrap();
        assert!(prompt.contains("Command: ls"), "{prompt}");
        assert!(prompt.contains("Exit code: unknown"), "{prompt}");
        assert!(prompt.contains("Command: pwd"), "{prompt}");
        assert!(prompt.contains("Exit code: 0"), "{prompt}");
    }

    #[test]
    fn truncation_cuts_at_char_boundaries() {
        let line = format!("user: {}", "é".repeat(6_000));
        let cut = truncate_line(&line, ITEM_CAP_BYTES);
        assert!(cut.len() <= ITEM_CAP_BYTES, "{}", cut.len());
        assert!(cut.ends_with(TRUNCATION_MARKER));
        // Slicing at a char boundary keeps the string valid UTF-8 whole.
        let reassembled: String = cut.chars().collect();
        assert_eq!(reassembled, cut);
        // A line under the cap is untouched.
        assert_eq!(truncate_line("short", ITEM_CAP_BYTES), "short");
    }

    #[test]
    fn continuation_fragments_join_into_one_message() {
        let mut root = user("m1", "first half");
        root.parts.push(MessagePart::Text {
            id: "m1-t".into(),
            text: "second half".into(),
        });
        let tail = entry(
            "m1#c1",
            MessageRole::User,
            vec![MessagePart::Text {
                id: "m1-t2".into(),
                text: "third half".into(),
            }],
        );
        let tail = SessionMessageEntry {
            continuation_of: Some("m1".into()),
            ..tail
        };
        let prompt = budgeted_transcript_prompt(&[root, tail], Some("main"), NEW_MESSAGE).unwrap();
        // One message, delivered once, parts in order.
        assert_eq!(prompt.matches("user:").count(), 1, "{prompt}");
        assert!(prompt.contains("first half\nsecond half\nthird half"), "{prompt}");
    }

    #[test]
    fn no_deliverable_transcript_returns_none() {
        let entries = vec![
            entry(
                "seam",
                MessageRole::System,
                vec![MessagePart::Fork {
                    id: "fork:x".into(),
                    source_chat_id: "main".into(),
                    source_title: "Main".into(),
                }],
            ),
            user("u1", "   "),
        ];
        assert!(budgeted_transcript_prompt(&entries, Some("main"), NEW_MESSAGE).is_none());
        assert!(budgeted_transcript_prompt(&[], Some("main"), NEW_MESSAGE).is_none());
    }

    #[test]
    fn the_prefix_is_the_prompt_without_its_tail() {
        // The native-fork fallback (ticket 17) rides the prefix: the driver
        // appends the user message itself, so the prefix must be exactly the
        // delivered prompt minus the message — same selection, same header,
        // same trailing blank line, never counting the message.
        let mut entries = vec![
            user("u0", "ORIGINAL: build the parser"),
            assistant("a0", "on it"),
        ];
        for i in 1..=20 {
            entries.push(user(&format!("u{i}"), &format!("fill-{i}: {}", "x".repeat(900))));
            entries.push(assistant(&format!("a{i}"), &format!("note-{i}: {}", "y".repeat(90))));
        }
        let prefix = budgeted_transcript_prefix(&entries, Some("main")).unwrap();
        let prompt = budgeted_transcript_prompt(&entries, Some("main"), NEW_MESSAGE).unwrap();
        assert!(prompt.starts_with(&prefix), "prefix must head the prompt");
        assert!(prompt.ends_with(NEW_MESSAGE));
        assert_eq!(&prompt[..prefix.len()], &prefix);
        assert!(prefix.ends_with("\n\n"));
        assert!(!prefix.contains(NEW_MESSAGE));
        assert!(prefix.contains("read_chat"));
        assert!(prefix.len() <= TRANSCRIPT_BUDGET_BYTES);
        // An undeliverable transcript has no prefix either.
        assert!(budgeted_transcript_prefix(&[], Some("main")).is_none());
    }
}
