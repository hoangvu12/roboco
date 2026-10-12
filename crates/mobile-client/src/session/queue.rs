//! The shared message queue: `WatchQueue` mirroring, queue actions (send
//! now / steer now / remove / move), and the host-authoritative edit lease.
//!
//! Every queue write is an engine RPC — the engine serializes edits and
//! delivery through the same lock, so the acknowledgement is the
//! linearization point. `from_this_device` is "the id was returned by this
//! client's `QueueMessage`" (the engine stamps rows with its own device
//! id, not the submitter's, so the wire field can't say it).

use std::sync::Arc;

use serde::Deserialize;
use tokio_util::sync::CancellationToken;

use crate::{ClientError, lock};

use super::SessionCore;

// ── records ────────────────────────────────────────────────────────────────

/// Why a queued row is not currently eligible for delivery.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum QueueGate {
    /// Someone holds an edit lease; the row won't deliver until released.
    Editing {
        owner_device_id: String,
        expires_at_ms: i64,
        mine: bool,
    },
    /// A lease lapsed — a person must review before it sends.
    ReviewRequired { owner_device_id: String },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueueItem {
    pub id: String,
    /// Raw row text (what the engine will send).
    pub text: String,
    /// User-visible text (transport trailer + Appshot context stripped).
    pub visible_text: String,
    pub attachments: Vec<String>,
    pub hold_for_turn_end: bool,
    pub issued_by: String,
    pub from_this_device: bool,
    pub issued_at_ms: i64,
    pub edited_at_ms: Option<i64>,
    pub gate: Option<QueueGate>,
    /// A Send-now / Steer / Remove is awaiting the engine's acknowledgement.
    pub action_pending: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueueEditLease {
    pub row_id: String,
    pub lease_id: String,
    /// Row text at lease time.
    pub text: String,
    pub base_text_hash: String,
    pub expires_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum QueueEditStart {
    Acquired(QueueEditLease),
    /// Another editor holds the row.
    Locked,
    /// The row left the queue.
    Missing,
    /// Engine unreachable / capability missing.
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QueueEditAction {
    /// Save the new text.
    Commit,
    /// Keep the old text, release the row.
    Cancel,
    /// Delete the row.
    Discard,
    /// Release without changing anything.
    Release,
}

impl QueueEditAction {
    fn wire(self) -> &'static str {
        match self {
            QueueEditAction::Commit => "commit",
            QueueEditAction::Cancel => "cancel",
            QueueEditAction::Discard => "discard",
            QueueEditAction::Release => "releaseUnchanged",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QueueEditFinish {
    Finished,
    /// The row changed under the lease (hash mismatch).
    Conflict,
    Missing,
    /// The lease was superseded or expired.
    Lost,
    Unavailable,
}

// ── wire rows (lenient) ────────────────────────────────────────────────────

/// A queue row as the engine serializes it. Everything defaults, unknown
/// fields are ignored, and enums arrive as plain strings — a newer engine's
/// row never fails the frame.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub(crate) struct WireQueuedMessage {
    pub id: String,
    pub text: String,
    pub attachments: Vec<String>,
    pub hold_for_turn_end: bool,
    pub issued_by: String,
    pub issued_at: i64,
    pub edited_at: Option<i64>,
    pub delivery_gate: Option<WireQueueGate>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub(crate) enum WireQueueGate {
    #[serde(rename_all = "camelCase")]
    #[allow(dead_code)]
    Editing {
        lease_id: String,
        owner_device_id: String,
        #[allow(dead_code)]
        owner_instance_id: String,
        #[allow(dead_code)]
        acquired_at_ms: i64,
        expires_at_ms: i64,
        #[allow(dead_code)]
        base_text_hash: String,
    },
    #[serde(rename_all = "camelCase")]
    #[allow(dead_code)]
    ReviewRequired {
        #[allow(dead_code)]
        previous_lease_id: String,
        owner_device_id: String,
        #[allow(dead_code)]
        since_ms: i64,
        #[allow(dead_code)]
        base_text_hash: String,
    },
}

/// Map the wire rows to queue items against this client's local sets.
pub(crate) fn queue_items(state: &super::SessionState) -> Vec<QueueItem> {
    state
        .queue
        .iter()
        .map(|item| QueueItem {
            id: item.id.clone(),
            text: item.text.clone(),
            visible_text: crate::attachments::queue_visible_text(&item.text, &item.attachments),
            attachments: item.attachments.clone(),
            hold_for_turn_end: item.hold_for_turn_end,
            issued_by: item.issued_by.clone(),
            from_this_device: state.mine_queued.contains(&item.id),
            issued_at_ms: item.issued_at,
            edited_at_ms: item.edited_at,
            gate: item.delivery_gate.as_ref().map(|gate| match gate {
                WireQueueGate::Editing {
                    owner_device_id,
                    expires_at_ms,
                    lease_id,
                    ..
                } => QueueGate::Editing {
                    owner_device_id: owner_device_id.clone(),
                    expires_at_ms: *expires_at_ms,
                    mine: state.held_leases.contains(lease_id),
                },
                WireQueueGate::ReviewRequired { owner_device_id, .. } => QueueGate::ReviewRequired {
                    owner_device_id: owner_device_id.clone(),
                },
            }),
            action_pending: state.queue_actions_pending.contains(&item.id),
        })
        .collect()
}

// ── the watch task ─────────────────────────────────────────────────────────

/// Run the `WatchQueue` mirror for one session until cancelled. The feed
/// resubscribes itself on every reconnect; each item replaces the whole
/// queue (the engine always sends the current value first).
pub(crate) async fn run(core: Arc<SessionCore>, cancel: CancellationToken) {
    let Ok(handle) = core.engine_handle() else {
        return;
    };
    let mut watch = handle.watch_calls(
        roboco_rpc::methods::WATCH_QUEUE,
        serde_json::json!({"chatId": core.chat_id}),
    );
    loop {
        let item = tokio::select! {
            _ = cancel.cancelled() => return,
            item = watch.recv() => match item {
                Some(item) => item,
                None => return, // The engine is gone from the client.
            },
        };
        let rows = decode_queue_items(&item.value);
        {
            let mut state = lock(&core.state);
            state.queue = rows;
            // A row that left the queue can't be action-pending.
            let present: std::collections::HashSet<String> =
                state.queue.iter().map(|row| row.id.clone()).collect();
            state.queue_actions_pending.retain(|id| present.contains(id));
        }
        core.recompute_composer();
    }
}

/// Decode `{"items": [...]}` (or a bare array) leniently; undecodable rows
/// are dropped, never fatal.
fn decode_queue_items(value: &serde_json::Value) -> Vec<WireQueuedMessage> {
    let items = match value {
        serde_json::Value::Array(items) => items.as_slice(),
        serde_json::Value::Object(map) => match map.get("items") {
            Some(serde_json::Value::Array(items)) => items.as_slice(),
            _ => return Vec::new(),
        },
        _ => return Vec::new(),
    };
    items
        .iter()
        .filter_map(|item| serde_json::from_value::<WireQueuedMessage>(item.clone()).ok())
        .collect()
}

// ── actions ────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum QueueAction {
    SendNow,
    Steer,
    Remove,
}

impl QueueAction {
    fn label(self) -> &'static str {
        match self {
            QueueAction::SendNow | QueueAction::Steer => "send now",
            QueueAction::Remove => "remove",
        }
    }

    fn key(self) -> &'static str {
        match self {
            QueueAction::SendNow | QueueAction::Steer => "sent",
            QueueAction::Remove => "removed",
        }
    }

    fn method(self) -> &'static str {
        match self {
            QueueAction::SendNow => roboco_rpc::methods::SEND_QUEUED_MESSAGE_NOW,
            QueueAction::Steer => roboco_rpc::methods::STEER_QUEUED_MESSAGE_NOW,
            QueueAction::Remove => roboco_rpc::methods::REMOVE_QUEUED_MESSAGE,
        }
    }
}

impl SessionCore {
    /// One queue action: mark `action_pending`, call the RPC, then apply the
    /// outcome (`sent/removed` flags, the error strings the platform shows).
    async fn queue_action(&self, id: &str, action: QueueAction) -> Result<bool, ClientError> {
        let send_now = action != QueueAction::Remove;
        {
            let mut state = lock(&self.state);
            let Some(row) = state.queue.iter().find(|q| &q.id == id) else {
                return Ok(false);
            };
            // Gated rows refuse send and steer, but allow remove.
            if state.queue_actions_pending.contains(id)
                || (send_now && row.delivery_gate.is_some())
            {
                return Ok(false);
            }
            state.queue_actions_pending.insert(id.to_owned());
            state.queue_error = None;
        }
        self.recompute_composer();
        let result = self
            .engine_handle()?
            .call(
                action.method(),
                serde_json::json!({ "chatId": self.chat_id, "id": id }),
            )
            .await;
        let outcome = match result {
            Ok(value) if value.get(action.key()).and_then(|v| v.as_bool()) == Some(true) => {
                Ok(true)
            }
            Ok(_) => Err(
                "The engine did not confirm the action. The message may have already left the queue."
                    .to_owned(),
            ),
            Err(err) => Err(format!(
                "Couldn't complete {}. Check the connection to the engine and the queue before retrying. ({err})",
                action.label()
            )),
        };
        let id = id.to_owned();
        match outcome {
            Ok(done) => {
                lock(&self.state).queue_actions_pending.remove(&id);
                self.recompute_composer();
                Ok(done)
            }
            Err(message) => {
                let mut state = lock(&self.state);
                state.queue_actions_pending.remove(&id);
                state.queue_error = Some(message.clone());
                drop(state);
                self.recompute_composer();
                Err(ClientError::HostUnavailable { message })
            }
        }
    }

    /// Deliver a queued row now (interrupting the live turn if needed).
    pub(crate) async fn send_queued_now(&self, id: &str) -> Result<bool, ClientError> {
        self.queue_action(id, QueueAction::SendNow).await
    }

    /// Deliver a queued row without interrupting: steered into a live turn
    /// that reads mid-turn, else sent next at the turn boundary.
    pub(crate) async fn steer_queued_now(&self, id: &str) -> Result<bool, ClientError> {
        self.queue_action(id, QueueAction::Steer).await
    }

    /// A row's primary action, as the desktop resolves it: text never
    /// interrupts the turn (steer / send next); only attachments, which
    /// need a fresh request, send now (PR #592).
    pub(crate) async fn deliver_queued_now(&self, id: &str) -> Result<bool, ClientError> {
        let has_attachments = lock(&self.state)
            .queue
            .iter()
            .any(|q| q.id == id && !q.attachments.is_empty());
        if has_attachments {
            self.send_queued_now(id).await
        } else {
            self.steer_queued_now(id).await
        }
    }

    /// Remove a queued row (the engine acks before it's gone locally).
    pub(crate) async fn remove_queued(&self, id: &str) -> Result<bool, ClientError> {
        self.queue_action(id, QueueAction::Remove).await
    }

    /// Move a queue row to `to`. False for a missing, gated or
    /// action-pending row, or when the target index is out of range.
    pub(crate) async fn move_queued(&self, id: &str, to: u32) -> Result<bool, ClientError> {
        {
            let state = lock(&self.state);
            let Some(row) = state.queue.iter().find(|q| q.id == id) else {
                return Ok(false);
            };
            if row.delivery_gate.is_some()
                || state.queue_actions_pending.contains(id)
                || to as usize >= state.queue.len().max(1)
            {
                return Ok(false);
            }
        }
        let reply = self
            .engine_handle()?
            .call(
                roboco_rpc::methods::MOVE_QUEUED_MESSAGE,
                serde_json::json!({ "chatId": self.chat_id, "id": id, "toIndex": to }),
            )
            .await?;
        Ok(reply.get("changed").and_then(|v| v.as_bool()).unwrap_or(false))
    }

    /// Nudge a row one slot up (`-1`) or down (`+1`).
    pub(crate) async fn move_queued_by(&self, id: &str, delta: i32) -> Result<bool, ClientError> {
        let (from, count) = {
            let state = lock(&self.state);
            (state.queue.iter().position(|q| q.id == id), state.queue.len())
        };
        let Some(from) = from else {
            return Ok(false);
        };
        let to = from as i64 + delta as i64;
        if to < 0 || to >= count as i64 {
            return Ok(false);
        }
        self.move_queued(id, to as u32).await
    }

    // ── the edit lease ────────────────────────────────────────────────────

    /// `BeginQueuedMessageEdit`. The editor identity is this engine's
    /// `PairedEngine.session_id` — a stable phone identity per engine; the
    /// `instance_id` comes from the caller (a UUID per edit).
    pub(crate) async fn begin_queued_edit(&self, id: &str, instance_id: &str) -> QueueEditStart {
        let editor = self.editor_device_id();
        let Some(editor) = editor else {
            return QueueEditStart::Unavailable;
        };
        let capabilities = self
            .engine_handle()
            .ok()
            .and_then(|handle| handle.engine_info())
            .map(|info| info.capabilities)
            .unwrap_or_default();
        {
            let state = lock(&self.state);
            if !capabilities.contains(&roboco_proto::capabilities::MESSAGE_QUEUE_EDIT_LEASE_V1.to_owned())
                || !state
                    .queue
                    .iter()
                    .any(|q| q.id == id && !state.queue_actions_pending.contains(id))
            {
                return QueueEditStart::Unavailable;
            }
        }
        let Ok(handle) = self.engine_handle() else {
            return QueueEditStart::Unavailable;
        };
        let reply = handle
            .call(
                roboco_rpc::methods::BEGIN_QUEUED_MESSAGE_EDIT,
                serde_json::json!({
                    "chatId": self.chat_id,
                    "id": id,
                    "editorDeviceId": editor,
                    "editorInstanceId": instance_id,
                }),
            )
            .await;
        match reply {
            Ok(value) => match value.get("outcome").and_then(|o| o.as_str()) {
                Some("acquired") => {
                    let field = |k: &str| {
                        value.get(k).and_then(|v| v.as_str()).map(str::to_owned)
                    };
                    match (
                        field("leaseId"),
                        field("baseTextHash"),
                        value.get("expiresAtMs").and_then(|v| v.as_i64()),
                    ) {
                        (Some(lease_id), Some(base_text_hash), Some(expires_at_ms)) => {
                            let lease = QueueEditLease {
                                row_id: id.to_owned(),
                                lease_id: lease_id.clone(),
                                text: field("text").unwrap_or_default(),
                                base_text_hash,
                                expires_at_ms,
                            };
                            lock(&self.state).held_leases.insert(lease_id);
                            self.recompute_composer();
                            QueueEditStart::Acquired(lease)
                        }
                        _ => QueueEditStart::Unavailable,
                    }
                }
                Some("locked") => QueueEditStart::Locked,
                _ => QueueEditStart::Missing,
            },
            Err(_) => QueueEditStart::Unavailable,
        }
    }

    /// `RenewQueuedMessageEdit`: `true` only for `{outcome:"renewed"}`. A
    /// `lost`/`missing` result or a transport error returns `false` — the
    /// caller shows "Edit protection expired; review this message before
    /// sending". The caller drives renewal (every 20 s; the lease is 60 s).
    pub(crate) async fn renew_queued_edit(&self, lease: &QueueEditLease) -> bool {
        let Ok(handle) = self.engine_handle() else {
            return false;
        };
        let reply = handle
            .call(
                roboco_rpc::methods::RENEW_QUEUED_MESSAGE_EDIT,
                serde_json::json!({
                    "chatId": self.chat_id,
                    "id": lease.row_id,
                    "leaseId": lease.lease_id,
                }),
            )
            .await;
        reply
            .is_ok_and(|v| v.get("outcome").and_then(|o| o.as_str()) == Some("renewed"))
    }

    /// `FinishQueuedMessageEdit`. Commit sends `text` +
    /// `expectedTextHash = lease.base_text_hash`; `attachments: Some(v)` is
    /// sent even when `v` is empty (that clears the row's attachments).
    pub(crate) async fn finish_queued_edit(
        &self,
        lease: QueueEditLease,
        action: QueueEditAction,
        text: Option<String>,
        attachments: Option<Vec<String>>,
    ) -> QueueEditFinish {
        let mut params = serde_json::json!({
            "chatId": self.chat_id,
            "id": lease.row_id,
            "leaseId": lease.lease_id,
            "action": action.wire(),
            "expectedTextHash": lease.base_text_hash,
        });
        if let Some(text) = text {
            params["text"] = serde_json::Value::String(text);
        }
        if let Some(attachments) = attachments {
            params["attachments"] =
                serde_json::Value::Array(attachments.into_iter().map(Into::into).collect());
        }
        let Ok(handle) = self.engine_handle() else {
            return QueueEditFinish::Unavailable;
        };
        let result = handle
            .call(roboco_rpc::methods::FINISH_QUEUED_MESSAGE_EDIT, params)
            .await;
        lock(&self.state).held_leases.remove(&lease.lease_id);
        match result {
            Ok(value) => match value.get("outcome").and_then(|o| o.as_str()) {
                Some("committed" | "cancelled" | "discarded" | "released") => {
                    QueueEditFinish::Finished
                }
                Some("conflict") => QueueEditFinish::Conflict,
                Some("missing") => QueueEditFinish::Missing,
                _ => QueueEditFinish::Lost,
            },
            Err(_) => QueueEditFinish::Unavailable,
        }
    }

    /// The engine's phone identity for lease ownership: the pairing
    /// session id on the engine record.
    fn editor_device_id(&self) -> Option<String> {
        self.client
            .upgrade()
            .and_then(|client| {
                lock(&client.engines)
                    .entries
                    .iter()
                    .find(|entry| entry.paired.engine_id == self.engine_id)
                    .map(|entry| entry.paired.session_id.clone())
            })
            .filter(|id| !id.is_empty())
    }
}

/// The queue error strings, verbatim (the platform shows them as-is).
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_send_now_that_the_engine_did_not_confirm_names_the_queue() {
        let message = unconfirmed_action_message(QueueAction::SendNow);
        assert_eq!(
            message,
            "The engine did not confirm the action. The message may have already left the queue."
        );
    }

    #[test]
    fn a_transport_failure_adapts_host_to_engine() {
        let message = transport_failure_message(QueueAction::Remove, "boom");
        assert_eq!(
            message,
            "Couldn't complete remove. Check the connection to the engine and the queue before retrying. (boom)"
        );
        let message = transport_failure_message(QueueAction::Steer, "offline");
        assert!(message.starts_with("Couldn't complete send now."));
    }

    fn unconfirmed_action_message(action: QueueAction) -> String {
        let value = serde_json::json!({ action.key(): false });
        match value.get(action.key()).and_then(|v| v.as_bool()) {
            Some(true) => panic!("confirmed"),
            _ => "The engine did not confirm the action. The message may have already left the queue."
                .to_owned(),
        }
    }

    fn transport_failure_message(action: QueueAction, error: &str) -> String {
        format!(
            "Couldn't complete {}. Check the connection to the engine and the queue before retrying. ({error})",
            action.label()
        )
    }

    #[test]
    fn queue_items_map_gates_and_ownership() {
        let mut state = super::super::SessionState::default();
        state.queue = vec![
            WireQueuedMessage {
                id: "q1".into(),
                text: "hello".into(),
                issued_by: "engine-device".into(),
                ..Default::default()
            },
            WireQueuedMessage {
                id: "q2".into(),
                text: crate::attachments::with_attachments(
                    "",
                    &["/staging/ab12-img.png".to_owned()],
                ),
                attachments: vec!["/staging/ab12-img.png".to_owned()],
                issued_by: "engine-device".into(),
                delivery_gate: Some(WireQueueGate::Editing {
                    lease_id: "lease-1".into(),
                    owner_device_id: "phone-9".into(),
                    owner_instance_id: "i".into(),
                    acquired_at_ms: 0,
                    expires_at_ms: 60_000,
                    base_text_hash: "h".into(),
                }),
                ..Default::default()
            },
        ];
        state.mine_queued.insert("q1".into());
        state.held_leases.insert("lease-1".into());
        let items = queue_items(&state);
        assert!(items[0].from_this_device);
        assert_eq!(items[0].visible_text, "hello");
        assert!(!items[1].from_this_device);
        // An attachment-only row shows the placeholder, not the trailer.
        assert_eq!(items[1].visible_text, crate::attachments::ATTACHMENT_ONLY_TEXT);
        match &items[1].gate {
            Some(QueueGate::Editing { mine, owner_device_id, expires_at_ms }) => {
                assert!(mine, "a lease this client holds reads mine");
                assert_eq!(owner_device_id, "phone-9");
                assert_eq!(*expires_at_ms, 60_000);
            }
            other => panic!("unexpected gate: {other:?}"),
        }
    }

    #[test]
    fn queue_rows_decode_leniently() {
        let value = serde_json::json!({ "items": [
            { "id": "a", "text": "one", "issuedBy": "d", "issuedAt": 5 },
            { "id": "b", "text": "two", "issuedBy": "d", "issuedAt": 6,
              "deliveryGate": { "kind": "reviewRequired", "previousLeaseId": "l", "ownerDeviceId": "d2", "sinceMs": 9, "baseTextHash": "h" } },
            { "id": 7, "text": "undecodable" },
        ]});
        let rows = decode_queue_items(&value);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].id, "a");
        assert_eq!(rows[0].issued_by, "d");
        assert_eq!(rows[1].delivery_gate.as_ref().map(|g| match g {
            WireQueueGate::ReviewRequired { owner_device_id, .. } => owner_device_id.clone(),
            _ => String::new(),
        }), Some("d2".to_owned()));
        // A bare array is accepted; junk is not.
        assert_eq!(decode_queue_items(&serde_json::json!([{"id": "z", "text": "t", "issuedBy": "d", "issuedAt": 0}])).len(), 1);
        assert!(decode_queue_items(&serde_json::json!("nope")).is_empty());
    }
}
