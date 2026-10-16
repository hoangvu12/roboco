//! Completion notices (orchestration v2): when a turn of an agent-created,
//! notifying side chat comes to rest, the engine records an outcome for the
//! chat that created it — based on its own recorded turn-end state, never on
//! anything the agent calls — and wakes an idle parent with one
//! engine-authored notice message that starts a parent turn.
//!
//! This module is the tracer bullet (ticket 12): outcome records for
//! completed / errored / stopped / awaiting-input turns, and delivery to an
//! IDLE parent. A busy parent is left to ticket 13 (its notice waits for the
//! turn to end, and several outcomes merge into one notice); restart
//! durability, acknowledgement and the loop cap land in tickets 13–14. The
//! pending records here are the seam those tickets build on.
//!
//! The notice message is a SYSTEM entry — never a user message — carrying a
//! [`roboco_doc::MessagePart::CompletionNotice`] marker so clients can render
//! it distinctly (ticket 15), with the same text as a plain part so older
//! layouts stay readable. Its text names each side chat by title and short
//! id with its outcome, and points the parent agent at `read_chat`.

use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, PoisonError};

use roboco_doc::{
    MessagePart, MessageRole, MessageStatus, NoticeOutcome, NoticeSide, SessionMessageEntry,
};

use crate::doc_host::DocHost;
use crate::sessions::SessionsEngine;
use crate::{new_id, now_ms};

/// Cap for a notice's one-line detail — an error's reason or a question's
/// summary. Matches the transcript's tool-output summary budget: the notice
/// is a pointer to `read_chat`, never a report.
pub(crate) const NOTICE_DETAIL_MAX: usize = 160;

/// The engine's recorded turn-end state for one notifiable side-chat turn.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Outcome {
    /// The turn completed.
    Completed,
    /// The turn errored, with its one-line reason.
    Errored { reason: Option<String> },
    /// The turn was stopped or interrupted.
    Stopped,
    /// The turn is waiting for input, with the one-line summary of the
    /// question or approval it is waiting on.
    WaitingForInput { summary: String },
}

/// An outcome recorded for a parent, awaiting delivery.
#[derive(Debug, Clone)]
pub(crate) struct PendingOutcome {
    /// The side chat whose turn ended.
    pub(crate) side_chat_id: String,
    pub(crate) outcome: Outcome,
}

/// Outcome records owed to parents, keyed by parent chat id. In-memory for
/// the tracer bullet; ticket 13 makes them durable and merges the ones a
/// busy parent accumulates into one notice.
#[derive(Debug, Default)]
pub(crate) struct CompletionNotices {
    pending: Mutex<HashMap<String, Vec<PendingOutcome>>>,
}

impl CompletionNotices {
    pub(crate) fn record(&self, parent: &str, outcome: PendingOutcome) {
        lock(&self.pending)
            .entry(parent.to_string())
            .or_default()
            .push(outcome);
    }

    /// Take everything owed to `parent` for a delivery. `None` when the
    /// parent is owed nothing.
    pub(crate) fn take_for_delivery(&self, parent: &str) -> Option<Vec<PendingOutcome>> {
        lock(&self.pending)
            .remove(parent)
            .filter(|outcomes| !outcomes.is_empty())
    }

    /// A delivery that could not start a parent turn (busy again, no run
    /// configuration, no doc): the outcomes stay owed, at the front of the
    /// queue in their recorded order.
    pub(crate) fn requeue(&self, parent: &str, outcomes: Vec<PendingOutcome>) {
        if outcomes.is_empty() {
            return;
        }
        let mut all = lock(&self.pending);
        let pending = all.entry(parent.to_string()).or_default();
        for outcome in outcomes.into_iter().rev() {
            pending.insert(0, outcome);
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// First non-empty line, trimmed and capped with an ellipsis — the one-line
/// form a notice's detail owes the parent. `None` for blank text.
pub(crate) fn one_line(text: Option<&str>) -> Option<String> {
    let text = text?.trim();
    if text.is_empty() {
        return None;
    }
    let line = text
        .lines()
        .find(|line| !line.trim().is_empty())
        .unwrap_or(text)
        .trim_end();
    if line.chars().count() <= NOTICE_DETAIL_MAX {
        return Some(line.to_owned());
    }
    let mut cut = NOTICE_DETAIL_MAX;
    while cut > 0 && !line.is_char_boundary(cut) {
        cut -= 1;
    }
    Some(format!("{}…", &line[..cut]))
}

/// The one-line summary of the question or approval a side chat is waiting
/// on: the question's own text, its header when the text is blank.
pub(crate) fn question_summary(questions: &[roboco_proto::UserInputQuestion]) -> String {
    let text = questions
        .first()
        .map(|question| {
            if question.question.trim().is_empty() {
                question.header.as_str()
            } else {
                question.question.as_str()
            }
        })
        .unwrap_or("input");
    one_line(Some(text)).unwrap_or_else(|| "input".into())
}

/// Record an outcome for a side-chat turn and deliver what its parent is
/// owed. Called from the run loop, fire-and-forget: the side chat's own
/// event stream never waits on the parent's turn.
pub(crate) async fn record_and_deliver(
    engine: &SessionsEngine,
    side_chat: &str,
    parent: &str,
    outcome: Outcome,
) {
    engine.notices().record(
        parent,
        PendingOutcome {
            side_chat_id: side_chat.to_string(),
            outcome,
        },
    );
    deliver(engine, parent).await;
}

/// Deliver the parent's pending outcomes as one engine-authored notice that
/// starts a parent turn. An idle parent is woken immediately; a busy one
/// receives nothing here — its outcomes stay pending for ticket 13's
/// end-of-turn delivery, and the notice is never steered mid-turn.
pub(crate) async fn deliver(engine: &SessionsEngine, parent: &str) {
    if engine.turn_in_flight(parent) {
        return;
    }
    let Some(outcomes) = engine.notices().take_for_delivery(parent) else {
        return;
    };
    let Some(host) = engine.host() else {
        tracing::warn!(parent, "completion notice held: no doc host");
        engine.notices().requeue(parent, outcomes);
        return;
    };
    let sides: Vec<NoticeSide> = outcomes.iter().map(|o| side_row(&host, o)).collect();
    let text = notice_text(&sides);
    // The notice turn runs on the parent's own configuration: its chat row
    // when there is one, else the last live request. `dispatch` re-derives
    // the harness session and stamps the Roboco MCP server (so the parent
    // can `read_chat` immediately).
    let request = host.request_from_chat_row(parent, &text).or_else(|| {
        engine.last_request(parent).map(|mut request| {
            request.prompt = text.clone();
            request.resume = None;
            request.attachments = Vec::new();
            request
        })
    });
    let Some(request) = request else {
        tracing::warn!(
            parent,
            "completion notice held: no run configuration for the chat"
        );
        engine.notices().requeue(parent, outcomes);
        return;
    };
    let Ok(handle) = host.open(parent) else {
        tracing::warn!(parent, "completion notice held: doc unavailable");
        engine.notices().requeue(parent, outcomes);
        return;
    };
    // A parent turn that started while this delivery was being built
    // receives the notice after its turn instead (ticket 13): it is never
    // steered mid-turn. The entry has not been written yet, so the outcomes
    // go back to the front of the pending queue untouched.
    if engine.turn_in_flight(parent) {
        engine.notices().requeue(parent, outcomes);
        return;
    }
    let entry = notice_entry(host.device_id(), &sides, &text);
    if let Err(err) = handle.doc().push_message(&entry) {
        tracing::warn!(parent, error = %err, "completion notice entry write failed");
        engine.notices().requeue(parent, outcomes);
        return;
    }
    let harness = host.harness_for_request(parent, &request);
    if let Err(err) = engine
        .dispatch(parent, harness, request, Some(entry.id.clone()), None)
        .await
    {
        // The entry is already in the transcript (readable on the parent's
        // next turn); only the wake failed. Do not requeue: a dispatch that
        // fails once fails the same way twice.
        tracing::warn!(parent, error = %err, "completion notice turn failed to start");
    }
}

fn side_row(host: &DocHost, outcome: &PendingOutcome) -> NoticeSide {
    let title = host
        .workspace()
        .and_then(|ws| ws.chat(&outcome.side_chat_id).ok().flatten())
        .and_then(|chat| chat.title)
        .map(|title| title.trim().to_owned())
        .filter(|title| !title.is_empty())
        .unwrap_or_else(|| "Side chat".into());
    let (recorded, detail) = match &outcome.outcome {
        Outcome::Completed => (NoticeOutcome::Completed, None),
        Outcome::Errored { reason } => (NoticeOutcome::Errored, reason.clone()),
        Outcome::Stopped => (NoticeOutcome::Stopped, None),
        Outcome::WaitingForInput { summary } => {
            (NoticeOutcome::WaitingForInput, Some(summary.clone()))
        }
    };
    NoticeSide {
        chat_id: outcome.side_chat_id.clone(),
        title,
        outcome: recorded,
        detail,
    }
}

/// The notice's readable text — also the parent turn's prompt. Names each
/// side chat by title and short id with its outcome, and tells the agent to
/// use `read_chat` for the full result.
pub(crate) fn notice_text(sides: &[NoticeSide]) -> String {
    let mut lines: Vec<String> = Vec::with_capacity(sides.len() + 2);
    match sides {
        [] => lines.push("Side chats reached outcomes.".into()),
        [only] => lines.push(format!("Side chat {}.", side_line(only))),
        many => {
            lines.push(format!("{} side chats reached outcomes:", many.len()));
            lines.extend(many.iter().map(|side| format!("- {}.", side_line(side))));
        }
    }
    lines.push(read_line(sides));
    lines.join("\n")
}

/// The `read_chat` pointer — the notice is a pointer, never a report.
fn read_line(sides: &[NoticeSide]) -> String {
    match sides {
        [only] => format!(
            "Read the full result with the Roboco `read_chat` tool, chat {}.",
            short_id(&only.chat_id)
        ),
        _ => "Read each chat's full result with the Roboco `read_chat` tool.".into(),
    }
}

/// `"title" (short id) outcome` — the one-line form for one side chat.
fn side_line(side: &NoticeSide) -> String {
    let outcome = match side.outcome {
        NoticeOutcome::Completed => "finished".into(),
        NoticeOutcome::Errored => match &side.detail {
            Some(reason) => format!("failed: {reason}"),
            None => "failed".into(),
        },
        NoticeOutcome::Stopped => "was stopped".into(),
        NoticeOutcome::WaitingForInput => match &side.detail {
            Some(summary) => format!("is waiting for input: {summary}"),
            None => "is waiting for input".into(),
        },
    };
    format!("\"{}\" ({}) {}", side.title, short_id(&side.chat_id), outcome)
}

/// Eight-char id prefix — the sidebar's convention.
fn short_id(id: &str) -> &str {
    &id[..id.len().min(8)]
}

/// The engine-authored notice message: a SYSTEM entry (never a user
/// message) carrying the structured marker clients render distinctly
/// (ticket 15), with the notice's text beside it as a plain part so older
/// layouts stay readable.
fn notice_entry(device_id: &str, sides: &[NoticeSide], text: &str) -> SessionMessageEntry {
    SessionMessageEntry {
        id: format!("notice:{}", new_id()),
        role: MessageRole::System,
        parts: vec![
            MessagePart::CompletionNotice {
                id: "n0".into(),
                sides: sides.to_vec(),
            },
            MessagePart::Text {
                id: "t0".into(),
                text: text.to_owned(),
            },
        ],
        created_at: now_ms(),
        device_id: device_id.to_owned(),
        status: Some(MessageStatus::Complete),
        continuation_of: None,
        duration_ms: None,
        origin_chat_id: None,
        // A notice is an engine-authored message: no provider turn backs
        // it (a fork of the parent falls back to the budgeted paste for
        // it, exactly like the fork seam — ticket 17).
        provider_turn: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn side(chat_id: &str, title: &str, outcome: NoticeOutcome) -> NoticeSide {
        NoticeSide {
            chat_id: chat_id.into(),
            title: title.into(),
            outcome,
            detail: None,
        }
    }

    #[test]
    fn one_line_takes_the_first_line_trimmed_and_capped() {
        assert_eq!(one_line(None), None);
        assert_eq!(one_line(Some("   \n\t")), None);
        assert_eq!(one_line(Some("  provider 502  ")), Some("provider 502".into()));
        // Multi-line errors keep only their first non-empty line.
        assert_eq!(
            one_line(Some("\nprovider 502: bad gateway\nretry later\n")),
            Some("provider 502: bad gateway".into())
        );
        let long = "x".repeat(NOTICE_DETAIL_MAX + 40);
        let capped = one_line(Some(&long)).unwrap();
        assert_eq!(capped.chars().count(), NOTICE_DETAIL_MAX + 1);
        assert!(capped.ends_with('…'));
        // Char-boundary safety on multibyte input.
        let wide = "é".repeat(NOTICE_DETAIL_MAX + 5);
        let capped = one_line(Some(&wide)).unwrap();
        assert_eq!(capped.chars().count(), NOTICE_DETAIL_MAX + 1);
    }

    #[test]
    fn question_summary_prefers_the_question_then_the_header() {
        use roboco_proto::UserInputQuestion;
        let questions = vec![
            UserInputQuestion {
                id: "q1".into(),
                header: "Approval".into(),
                question: "  Should we bump the major version?  ".into(),
                options: vec!["Yes".into(), "No".into()],
                multi_select: false,
            },
        ];
        assert_eq!(
            question_summary(&questions),
            "Should we bump the major version?"
        );
        let header_only = vec![UserInputQuestion {
            id: "q1".into(),
            header: "Approve shell access".into(),
            question: String::new(),
            options: vec![],
            multi_select: false,
        }];
        assert_eq!(question_summary(&header_only), "Approve shell access");
        assert_eq!(question_summary(&[]), "input");
    }

    #[test]
    fn notice_text_names_title_short_id_outcome_and_read_chat() {
        let mut completed = side("chat-side-1234", "Implement login", NoticeOutcome::Completed);
        completed.detail = None;
        let single = notice_text(&[completed]);
        assert_eq!(
            single,
            "Side chat \"Implement login\" (chat-si) finished.\n\
             Read the full result with the Roboco `read_chat` tool, chat chat-si."
        );

        let mut errored = side("chat-side-5678", "Flaky test", NoticeOutcome::Errored);
        errored.detail = Some("the build command timed out".into());
        let mut waiting = side("chat-side-90ab", "Review the API", NoticeOutcome::WaitingForInput);
        waiting.detail = Some("Should we bump the major version?".into());
        let merged = notice_text(&[completed, errored, waiting]);
        assert!(merged.starts_with("3 side chats reached outcomes:\n"));
        assert!(merged.contains("- \"Implement login\" (chat-si) finished."));
        assert!(merged.contains("- \"Flaky test\" (chat-si) failed: the build command timed out."));
        assert!(
            merged
                .contains("- \"Review the API\" (chat-si) is waiting for input: Should we bump the major version?")
        );
        assert!(merged.contains("`read_chat`"));
        // A stopped row carries no detail.
        let stopped = side("chat-side-cdef", "Migration", NoticeOutcome::Stopped);
        assert!(notice_text(&[stopped]).contains("\"Migration\" (chat-si) was stopped."));
        // Degenerate empty input still renders a pointer, never a panic.
        assert!(notice_text(&[]).contains("read_chat"));
    }

    #[test]
    fn pending_outcomes_record_take_and_requeue() {
        let notices = CompletionNotices::default();
        assert!(notices.take_for_delivery("parent").is_none());
        notices.record(
            "parent",
            PendingOutcome {
                side_chat_id: "chat-side-1".into(),
                outcome: Outcome::Completed,
            },
        );
        notices.record(
            "parent",
            PendingOutcome {
                side_chat_id: "chat-side-2".into(),
                outcome: Outcome::Stopped,
            },
        );
        let taken = notices.take_for_delivery("parent").unwrap();
        assert_eq!(taken.len(), 2);
        assert_eq!(taken[0].side_chat_id, "chat-side-1");
        // Taken is taken: a second delivery owes nothing.
        assert!(notices.take_for_delivery("parent").is_none());
        // A failed delivery puts them back at the front, in order.
        notices.requeue("parent", taken);
        let requeued = notices.take_for_delivery("parent").unwrap();
        assert_eq!(requeued[0].side_chat_id, "chat-side-1");
        assert_eq!(requeued[1].side_chat_id, "chat-side-2");
    }
}
