//! `ComposerState` derivation: everything the composer and its status strip
//! render, recomputed from the engine rows (host indicator), the queue, the
//! pending sends and the catalog's mid-turn steering flag.
//!
//! `working` / `turn_running` come **only** from host status
//! (`WatchSessions`, via the session row) or a streaming tail — never from
//! a pending send (upstream #777): `send_state == Sending` renders as the
//! "Sending to host…" pill instead.

use std::sync::Arc;

use roboco_proto::view::{SESSION_STALE_MS, display_status};
use roboco_proto::{Chat, ChatIndicator, ContextUsage, Session, UserInputQuestion};

use crate::lock;

use super::pending::{SendState, headline_state};
use super::queue::QueueItem;
use super::SessionCore;

/// Engine-advertised features that gate composer affordances. Mapped from
/// `EngineInfo.capabilities` (`roboco_proto::workspace::capabilities`).
/// The name is the ticket's pinned record shape (upstream's
/// `HostCapabilities`, kept so the PR #609 composer ports with renames).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct HostCapabilities {
    pub message_queue: bool,
    pub queue_actions: bool,
    pub queue_attachments: bool,
    pub clean_attachment_text: bool,
    pub queue_edit_lease: bool,
    /// Same wire capability as `queue_attachments` (host ≥ 0.2.12 naming).
    pub queued_attachments: bool,
    /// The chat's harness steers mid-turn (unknown until a live catalog).
    pub mid_turn_steering: Option<bool>,
}

impl HostCapabilities {
    pub(crate) fn of(capabilities: &[String], mid_turn_steering: Option<bool>) -> Self {
        let has = |needle: &str| capabilities.iter().any(|c| c == needle);
        let queue_attachments = has(roboco_proto::capabilities::MESSAGE_QUEUE_ATTACHMENTS_V1);
        Self {
            message_queue: has(roboco_proto::capabilities::MESSAGE_QUEUE_V1),
            queue_actions: has(roboco_proto::capabilities::MESSAGE_QUEUE_ACTIONS_V1),
            queue_attachments,
            queued_attachments: queue_attachments,
            clean_attachment_text: has(
                roboco_proto::capabilities::MESSAGE_QUEUE_CLEAN_ATTACHMENT_TEXT_V1,
            ),
            queue_edit_lease: has(roboco_proto::capabilities::MESSAGE_QUEUE_EDIT_LEASE_V1),
            mid_turn_steering,
        }
    }
}

/// The engine's identity + feature set (upstream's `HostInfo` record
/// shape, kept for Kotlin parity; "host" here always means the Roboco
/// engine).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostInfo {
    pub device_id: String,
    pub name: Option<String>,
    pub online: bool,
    pub capabilities: HostCapabilities,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LiveStatus {
    /// The row's display status (no in-flight-send override — #777).
    pub indicator: ChatIndicator,
    /// A turn is actually running on the engine (host Working or a
    /// streaming tail) — new sends queue/steer instead of starting a turn.
    pub turn_running: bool,
    pub working_since_ms: Option<i64>,
    /// The newest transcript entry is streaming.
    pub streaming: bool,
    /// Stop is meaningful (working, awaiting input, or streaming).
    pub can_interrupt: bool,
}

/// The transcript subscription's transport state (adapted: engine
/// connection + subscription liveness — Roboco has no rooms).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RoomState {
    /// Engine `Connected` **and** the transcript subscription is live.
    pub connected: bool,
    /// The engine's or the resubscribe's next attempt.
    pub retry_at_ms: Option<i64>,
    /// Engine not connected (a send would not deliver promptly).
    pub degraded: bool,
}

/// The unresolved question the composer should answer instead of typing.
#[derive(Debug, Clone, PartialEq)]
pub struct InputRequest {
    pub entry_id: String,
    pub request_id: String,
    pub questions: Vec<UserInputQuestion>,
}

/// Everything the composer and its status strip render. Transcript rows are
/// NOT here — they live in [`super::SessionSnapshot`].
#[derive(Debug, Clone, PartialEq)]
pub struct ComposerState {
    pub chat_id: String,
    pub revision: u64,
    pub title: String,
    pub host: HostInfo,
    pub live: LiveStatus,
    /// The shared queue, delivery order.
    pub queue: Vec<QueueItem>,
    /// This device's unadopted sends, oldest first.
    pub pending_sends: Vec<super::pending::PendingSend>,
    /// Headline state of the pending sends, if any.
    pub send_state: Option<SendState>,
    /// A send now would queue rather than deliver promptly.
    pub delivery_degraded: bool,
    pub room: RoomState,
    /// Attachment upload progress (0..1) while bytes are moving.
    pub transfer_progress: Option<f64>,
    /// Answer this instead of typing.
    pub open_input: Option<InputRequest>,
    /// Last queue action failure (cleared on the next action).
    pub queue_error: Option<String>,
    /// The last message this device submitted (scroll-to-own-send target).
    pub last_submitted_message_id: Option<String>,
    pub context_usage: Option<ContextUsage>,
}

impl Default for ComposerState {
    fn default() -> Self {
        Self {
            chat_id: String::new(),
            revision: 0,
            title: "New session".into(),
            host: HostInfo {
                device_id: String::new(),
                name: None,
                online: false,
                capabilities: HostCapabilities::default(),
            },
            live: LiveStatus {
                indicator: ChatIndicator::Idle,
                turn_running: false,
                working_since_ms: None,
                streaming: false,
                can_interrupt: false,
            },
            queue: Vec::new(),
            pending_sends: Vec::new(),
            send_state: None,
            delivery_degraded: false,
            room: RoomState::default(),
            transfer_progress: None,
            open_input: None,
            queue_error: None,
            last_submitted_message_id: None,
            context_usage: None,
        }
    }
}

/// The composer inputs read off the engine table: the chat row, its session
/// row, and the engine's connection state.
pub(crate) struct RowInputs {
    pub chat: Option<Chat>,
    pub session: Option<Session>,
    pub engine_connected: bool,
    pub engine_name: Option<String>,
    pub engine_device_id: Option<String>,
    pub capabilities: Vec<String>,
    pub retry_at_ms: Option<i64>,
}

/// Derive the composer state. Pure given the inputs, so the decision table
/// and the live-status rules unit-test without an engine.
pub(crate) fn build_composer(
    core: &SessionCore,
    inputs: &RowInputs,
    mid_turn_steering: Option<bool>,
) -> ComposerState {
    let state = lock(&core.state);
    let chat = inputs.chat.as_ref();
    let session = inputs.session.as_ref();
    let now = chrono::Utc::now();
    let host_indicator = chat
        .map(|chat| display_status(chat, session, now))
        .unwrap_or(ChatIndicator::Idle);
    let streaming = state
        .entries
        .last()
        .is_some_and(|e| e.status == Some(roboco_doc::MessageStatus::Streaming));
    let working_since_ms = session
        .filter(|s| {
            matches!(
                host_indicator,
                ChatIndicator::Working | ChatIndicator::AwaitingInput
            ) && fresh(s, now)
        })
        .and_then(|s| s.started_at.map(|t| t.timestamp_millis()));
    let busy = matches!(
        host_indicator,
        ChatIndicator::Working | ChatIndicator::AwaitingInput
    );
    ComposerState {
        chat_id: core.chat_id.clone(),
        revision: state.composer_revision,
        title: chat
            .and_then(|c| c.title.clone())
            .map(|t| t.trim().to_owned())
            .filter(|t| !t.is_empty())
            .unwrap_or_else(|| "New session".into()),
        host: HostInfo {
            device_id: inputs
                .engine_device_id
                .clone()
                .or_else(|| chat.map(|c| c.device_id.clone()))
                .unwrap_or_default(),
            name: inputs.engine_name.clone(),
            online: inputs.engine_connected,
            capabilities: HostCapabilities::of(&inputs.capabilities, mid_turn_steering),
        },
        live: LiveStatus {
            indicator: host_indicator,
            turn_running: host_indicator == ChatIndicator::Working || streaming,
            working_since_ms,
            streaming,
            can_interrupt: busy || streaming,
        },
        queue: super::queue::queue_items(&state),
        pending_sends: state.pending.clone(),
        send_state: headline_state(&state.pending),
        delivery_degraded: !inputs.engine_connected,
        room: RoomState {
            connected: inputs.engine_connected && core.subscription_live(),
            retry_at_ms: if inputs.engine_connected {
                state.resubscribe_at_ms
            } else {
                inputs.retry_at_ms.or(state.resubscribe_at_ms)
            },
            degraded: !inputs.engine_connected,
        },
        transfer_progress: state.transfer_progress,
        open_input: open_input(&state.entries),
        queue_error: state.queue_error.clone(),
        last_submitted_message_id: state.last_submitted.clone(),
        context_usage: state.context_usage,
    }
}

fn fresh(session: &Session, now: chrono::DateTime<chrono::Utc>) -> bool {
    now.signed_duration_since(session.updated_at).num_milliseconds() <= SESSION_STALE_MS
}

/// The newest unresolved question with at least one question to answer,
/// scanning host entries and parts in reverse.
pub(crate) fn open_input(entries: &[Arc<roboco_doc::SessionMessageEntry>]) -> Option<InputRequest> {
    for entry in entries.iter().rev() {
        for part in entry.parts.iter().rev() {
            if let roboco_doc::MessagePart::Input {
                request_id,
                questions,
                resolved: false,
                ..
            } = part
                && !questions.is_empty()
            {
                return Some(InputRequest {
                    entry_id: entry.id.clone(),
                    request_id: request_id.clone(),
                    questions: questions.clone(),
                });
            }
        }
    }
    None
}

impl SessionCore {
    /// Recompute the composer from the current rows and publish it (with a
    /// `ComposerChanged` event) when it changed.
    pub(crate) fn recompute_composer(&self) {
        let inputs = self.row_inputs();
        let mid_turn_steering = self.mid_turn_steering();
        let next = build_composer(self, &inputs, mid_turn_steering);
        let revision;
        {
            let mut state = lock(&self.state);
            let mut next = next;
            if *state.composer == next {
                return;
            }
            state.composer_revision += 1;
            next.revision = state.composer_revision;
            revision = next.revision;
            state.composer = Arc::new(next);
        }
        self.client
            .upgrade()
            .map(|client| client.pump.composer(&self.chat_id, revision));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeDelta;
    use roboco_doc::{MessagePart, MessageRole, SessionMessageEntry};

    fn chat_row(unseen: bool) -> Chat {
        serde_json::from_value(serde_json::json!({
            "id": "c1",
            "deviceId": "engine",
            "title": null,
            "archived": false,
            "cwd": null,
            "branch": null,
            "checkoutId": null,
            "lastMessagePreview": null,
            "lastMessageAt": null,
            "createdAt": "2026-01-01T00:00:00Z",
            "spaceId": null,
            "lastSeenAt": if unseen {
                serde_json::Value::Null
            } else {
                serde_json::json!("2026-01-01T00:00:01Z")
            },
        }))
        .unwrap()
    }

    fn session_row(status: roboco_proto::SessionStatus, age: TimeDelta) -> Session {
        let updated = chrono::Utc::now() - age;
        Session {
            last_completed_turn: None,
            chat_id: "c1".into(),
            device_id: "engine".into(),
            status,
            started_at: Some(updated - TimeDelta::minutes(5)),
            updated_at: updated,
            limit: None,
        }
    }

    #[test]
    fn live_status_rules_from_host_indicator_and_streaming() {
        let now = chrono::Utc::now();
        let chat = chat_row(false);
        // Working and fresh.
        let inputs = RowInputs {
            chat: Some(chat.clone()),
            session: Some(session_row(roboco_proto::SessionStatus::Working, TimeDelta::seconds(1))),
            engine_connected: true,
            engine_name: None,
            engine_device_id: Some("engine".into()),
            capabilities: vec![],
            retry_at_ms: None,
        };
        let core = test_core();
        let composer = build_composer(&core, &inputs, None);
        assert!(composer.live.turn_running);
        assert!(composer.live.can_interrupt);
        assert_eq!(composer.live.indicator, ChatIndicator::Working);
        assert!(composer.live.working_since_ms.is_some());
        // AwaitingInput: busy, but not a running turn on its own.
        let inputs = RowInputs {
            session: Some(session_row(
                roboco_proto::SessionStatus::AwaitingInput,
                TimeDelta::seconds(1),
            )),
            ..inputs
        };
        let composer = build_composer(&core, &inputs, None);
        assert!(!composer.live.turn_running);
        assert!(composer.live.can_interrupt);
        // A stale Working row (no heartbeat for >45 s) reads Idle.
        let inputs = RowInputs {
            session: Some(session_row(
                roboco_proto::SessionStatus::Working,
                TimeDelta::seconds(SESSION_STALE_MS + 5_000),
            )),
            ..inputs
        };
        let composer = build_composer(&core, &inputs, None);
        assert_eq!(composer.live.indicator, ChatIndicator::Idle);
        assert!(!composer.live.turn_running);
        assert!(composer.live.working_since_ms.is_none());
    }

    #[test]
    fn capabilities_map_from_engine_info() {
        let caps = HostCapabilities::of(
            &[
                roboco_proto::capabilities::MESSAGE_QUEUE_V1.to_owned(),
                roboco_proto::capabilities::MESSAGE_QUEUE_ATTACHMENTS_V1.to_owned(),
            ],
            Some(true),
        );
        assert!(caps.message_queue);
        assert!(caps.queue_attachments);
        assert!(caps.queued_attachments);
        assert!(!caps.queue_actions);
        assert!(caps.mid_turn_steering == Some(true));
    }

    #[test]
    fn open_input_finds_the_newest_unresolved_question() {
        let entry = |id: &str, parts: Vec<MessagePart>| Arc::new(SessionMessageEntry {
            origin_chat_id: None,
            id: id.into(),
            role: MessageRole::Assistant,
            parts,
            created_at: 0,
            device_id: String::new(),
            status: None,
            continuation_of: None,
            provider_turn: None,
            duration_ms: None,
        });
        let question = |id: &str| roboco_proto::UserInputQuestion {
            id: id.into(),
            header: "Pick".into(),
            question: "Which?".into(),
            options: vec!["a".into()],
            multi_select: false,
        };
        let resolved = entry(
            "old",
            vec![MessagePart::Input {
                id: "p1".into(),
                request_id: "r1".into(),
                questions: vec![question("q1")],
                resolved: true,
            }],
        );
        let open = entry(
            "new",
            vec![MessagePart::Input {
                id: "p2".into(),
                request_id: "r2".into(),
                questions: vec![question("q2")],
                resolved: false,
            }],
        );
        let found = open_input(&[resolved, open]);
        let request = found.expect("the open question is found");
        assert_eq!(request.request_id, "r2");
        assert_eq!(request.entry_id, "new");
        // An empty question list is not an input request.
        let empty = entry(
            "e",
            vec![MessagePart::Input {
                id: "p".into(),
                request_id: "r".into(),
                questions: vec![],
                resolved: false,
            }],
        );
        assert!(open_input(&[empty]).is_none());
    }

    fn test_core() -> SessionCore {
        crate::session::testkit::core()
    }
}
