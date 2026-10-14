//! The transcript subscription: `WatchDocMessages` reset/delta frames into
//! an Arc-stable entry list, the opening-tail rule, ticket-12 resume, the
//! desync/backoff heal, and the transcript cache file.
//!
//! Arc-stability is the layout contract (ticket 05): unchanged entries must
//! stay pointer-equal across frames, so row reuse (keyed on `Arc::ptr_eq`)
//! rebuilds nothing. [`apply_frame_arc`] implements exactly the semantics
//! of `roboco_doc::transcript_delta::apply_transcript_frame` plus those
//! identity rules; a property test pins the equivalence.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use roboco_doc::transcript_delta::{TranscriptDesync, TranscriptFrame, TranscriptUpsert};
use roboco_doc::{MessagePart, MessageStatus, SessionMessageEntry, TranscriptUpdate};
use roboco_engine_client::cache::CachedTranscript;
use roboco_engine_client::registry::{EngineKey, scope_transcript_frame};
use serde_json::Value;
use tokio_util::sync::CancellationToken;

use crate::engines::EngineHandle;
use crate::lock;

use super::SessionCore;

/// Re-subscribe backoff after a stream end (lag-check close, link drop):
/// 2 s, doubling, capped at 30 s; reset once frames flow again. A desync
/// resubscribes immediately and leaves the curve untouched.
pub(crate) const RESUBSCRIBE_BACKOFF: Duration = Duration::from_secs(2);
pub(crate) const RESUBSCRIBE_BACKOFF_MAX: Duration = Duration::from_secs(30);
/// Cache writes are throttled to at most one per second while the tail
/// streams; a quiet tail writes every frame (desktop `state.rs`).
pub(crate) const CACHE_WRITE_MIN_INTERVAL: Duration = Duration::from_secs(1);
/// Ticket-12 resume tail window (matches the engine's opening-tail size).
const RESUME_WINDOW: usize = 128;

// ── Arc-stable apply ───────────────────────────────────────────────────────

/// Apply a frame to an Arc-shared entry list with the identity rules:
///
/// - **Reset** reuses the old `Arc` for every entry that is `==` to the old
///   entry with the same id, else `Arc::new` (a reconnect reset of an
///   unchanged chat rebuilds no rows).
/// - **Remove**: `retain` — survivors keep their `Arc`.
/// - **Upsert** reuses the old `Arc` when the entry is equal (only its
///   position moves), else `Arc::new`.
/// - **Append**: `Arc::make_mut` then `push_str` — only the touched entry
///   gets a new pointer.
///
/// On any error the state is unreliable — resubscribe (never keep the
/// half-applied state).
pub fn apply_frame_arc(
    entries: &mut Vec<Arc<SessionMessageEntry>>,
    frame: TranscriptFrame,
) -> Result<(), TranscriptDesync> {
    match frame {
        TranscriptFrame::Reset { reset } => {
            let previous: HashMap<String, Arc<SessionMessageEntry>> =
                entries.iter().map(|e| (e.id.clone(), Arc::clone(e))).collect();
            *entries = reset
                .into_iter()
                .map(|entry| match previous.get(&entry.id) {
                    Some(old) if **old == entry => Arc::clone(old),
                    _ => Arc::new(entry),
                })
                .collect();
            Ok(())
        }
        TranscriptFrame::Delta {
            upsert,
            append,
            remove,
            count,
        } => {
            if !remove.is_empty() {
                let gone: std::collections::HashSet<&str> =
                    remove.iter().map(String::as_str).collect();
                entries.retain(|e| !gone.contains(e.id.as_str()));
            }
            for TranscriptUpsert { after, entry } in upsert {
                // Reuse the old Arc when only the position moves.
                let shared = entries
                    .iter()
                    .find(|e| e.id == entry.id)
                    .filter(|old| ***old == entry)
                    .cloned()
                    .unwrap_or_else(|| Arc::new(entry));
                if let Some(existing) = entries.iter().position(|e| e.id == shared.id) {
                    entries.remove(existing);
                }
                let at = match &after {
                    None => 0,
                    Some(anchor) => match entries.iter().position(|e| &e.id == anchor) {
                        Some(ix) => ix + 1,
                        None => {
                            return Err(TranscriptDesync(format!("missing anchor {anchor}")))
                        }
                    },
                };
                entries.insert(at, shared);
            }
            for roboco_doc::TextAppend {
                entry,
                part,
                text,
                len,
            } in append
            {
                let Some(target) = entries.iter_mut().find(|e| e.id == entry) else {
                    return Err(TranscriptDesync(format!("missing append entry {entry}")));
                };
                let has_part = target.parts.iter().any(|p| {
                    matches!(
                        p,
                        MessagePart::Text { id, .. } | MessagePart::Reasoning { id, .. } if *id == part
                    )
                });
                if !has_part {
                    return Err(TranscriptDesync(format!("missing append part {part}")));
                }
                // `make_mut` clones only when the Arc is shared with the
                // previous snapshot — the untouched entries keep their
                // pointers.
                let target = Arc::make_mut(target);
                let tail = target
                    .parts
                    .iter_mut()
                    .find_map(|p| match p {
                        MessagePart::Text { id, text } | MessagePart::Reasoning { id, text }
                            if *id == part =>
                        {
                            Some(text)
                        }
                        _ => None,
                    })
                    .expect("the part existed before make_mut");
                tail.push_str(&text);
                if tail.len() != len {
                    return Err(TranscriptDesync(format!(
                        "append length mismatch on {entry}#{part}: have {}, expected {len}",
                        tail.len()
                    )));
                }
            }
            if entries.len() != count {
                return Err(TranscriptDesync(format!(
                    "count mismatch: have {}, expected {count}",
                    entries.len()
                )));
            }
            Ok(())
        }
    }
}

/// The raw entry ids a frame touches (reset ids, upsert entry ids, append
/// entry ids) — the adoption set for pending sends.
pub(crate) fn frame_entry_ids(frame: &TranscriptFrame, sink: &mut Vec<String>) {
    match frame {
        TranscriptFrame::Reset { reset } => {
            sink.extend(reset.iter().map(|e| e.id.clone()));
        }
        TranscriptFrame::Delta { upsert, append, .. } => {
            sink.extend(upsert.iter().map(|u| u.entry.id.clone()));
            sink.extend(append.iter().map(|a| a.entry.clone()));
        }
    }
}

// ── the subscription task ──────────────────────────────────────────────────

/// One raw `WatchDocMessages` item, decoded leniently.
pub(crate) struct DecodedFrame {
    pub(crate) update: TranscriptUpdate,
    pub(crate) history_pending: bool,
}

/// Run the transcript subscription for one session until cancelled.
/// Structure: an outer resubscribe loop over an inner frame loop. A desync
/// breaks the inner loop with an immediate resubscribe (backoff curve
/// untouched); a stream end or a closed connection breaks it with the
/// backoff; the backoff resets once frames flow.
pub(crate) async fn run(core: Arc<SessionCore>, cancel: CancellationToken) {
    let Ok(handle) = core.engine_handle() else {
        return;
    };
    let mut backoff = RESUBSCRIBE_BACKOFF;
    // Set by a desync: the next subscribe goes without `resume`.
    let mut after_desync = false;
    loop {
        if cancel.is_cancelled() {
            return;
        }
        // A disconnected engine: wait for the fleet to report Connected
        // instead of spinning dial attempts.
        if !wait_while_disconnected(&handle, &core, &cancel).await {
            return;
        }
        let resume = resume_for_attempt(&mut after_desync, || resume_summary(&core));
        let first_open = {
            let state = lock(&core.state);
            !state.has_full_reset && !state.hydrated_from_cache && resume.is_none()
        };
        let params = subscribe_params(
            &core.chat_id,
            resume.as_ref(),
            first_open,
            core.resume_capable(),
        );
        let connection = match handle.transcript_connection().await {
            Ok(connection) => connection,
            Err(_) => {
                core.set_subscription_down(Some(next_attempt_ms(backoff)));
                if !sleep_cancellable(sleep_backoff(&mut backoff), &cancel).await {
                    return;
                }
                continue;
            }
        };
        let mut subscription = match connection.watch_doc_messages_with(&core.chat_id, params).await
        {
            Ok(subscription) => subscription,
            Err(error) => {
                tracing::debug!(chat = %core.chat_id, %error, "transcript subscribe failed");
                core.set_subscription_down(Some(next_attempt_ms(backoff)));
                if !sleep_cancellable(sleep_backoff(&mut backoff), &cancel).await {
                    return;
                }
                continue;
            }
        };
        // Frames are flowing: the next failure starts from the base delay.
        backoff = RESUBSCRIBE_BACKOFF;
        core.set_subscription_live();
        let mut immediate = false;
        let mut last_cache_write = std::time::Instant::now() - CACHE_WRITE_MIN_INTERVAL;
        loop {
            let item = tokio::select! {
                _ = cancel.cancelled() => return,
                item = subscription.recv() => match item {
                    Some(item) => item,
                    None => break, // Stream ended (lag close, link drop).
                },
            };
            let Some(decoded) = decode_item(item, &handle.key()) else {
                // Malformed beyond repair: resubscribe on the delay (in case
                // the reset itself is what can't parse).
                immediate = false;
                break;
            };
            let applied = core.apply_decoded(decoded);
            match applied {
                Ok(()) => {
                    let write = {
                        let state = lock(&core.state);
                        should_write_cache(
                            state.history_pending,
                            state
                                .entries
                                .last()
                                .map(|e| e.status == Some(MessageStatus::Streaming)),
                            last_cache_write.elapsed(),
                        )
                    };
                    if write {
                        last_cache_write = std::time::Instant::now();
                        core.save_transcript_cache();
                    }
                }
                Err(_desync) => {
                    // The healthy heal path: immediate resubscribe for a
                    // fresh reset; the backoff curve stays where it is.
                    // Ticket 12: that resubscribe goes WITHOUT `resume` (a
                    // plain reset). The held entries and digests are
                    // unchanged by the failed frame, so the same summary
                    // would be sent again and a bad one could loop forever.
                    immediate = true;
                    after_desync = true;
                    break;
                }
            }
        }
        core.set_subscription_down(if immediate { None } else { Some(next_attempt_ms(backoff)) });
        if !immediate && !sleep_cancellable(sleep_backoff(&mut backoff), &cancel).await {
            return;
        }
    }
}

/// The resume summary for this subscribe attempt: none right after a
/// desync (ticket 12's plain reset, consuming the flag, without building a
/// summary), otherwise whatever `summary` yields.
pub(crate) fn resume_for_attempt<R>(
    after_desync: &mut bool,
    summary: impl FnOnce() -> Option<R>,
) -> Option<R> {
    if std::mem::take(after_desync) {
        None
    } else {
        summary()
    }
}

/// The `WatchDocMessages` params for one subscribe attempt. `resume` takes
/// precedence over `openingTail` (the client already has content painted
/// from its cache); without either, a resume-capable engine is still asked
/// for digests (a plain reset that carries them).
pub(crate) fn subscribe_params(
    chat_id: &str,
    resume: Option<&roboco_doc::TranscriptResume>,
    first_open: bool,
    resume_capable: bool,
) -> Value {
    let mut params = serde_json::json!({"chatId": chat_id});
    if let Some(resume) = resume {
        params["resume"] = serde_json::to_value(resume).unwrap_or(Value::Null);
        params["digests"] = Value::Bool(true);
    } else if first_open {
        params["openingTail"] = Value::Bool(true);
        if resume_capable {
            params["digests"] = Value::Bool(true);
        }
    } else if resume_capable {
        params["digests"] = Value::Bool(true);
    }
    params
}

/// The cache-write rule: a quiet tail writes every frame; a streaming
/// tail at most once per [`CACHE_WRITE_MIN_INTERVAL`]; a `historyPending`
/// preview is never written. Pure — the unit test crafts the frames.
pub(crate) fn should_write_cache(
    history_pending: bool,
    tail_streaming: Option<bool>,
    since_last_write: Duration,
) -> bool {
    if history_pending {
        return false;
    }
    match tail_streaming {
        Some(true) => since_last_write >= CACHE_WRITE_MIN_INTERVAL,
        _ => true,
    }
}

fn next_attempt_ms(delay: Duration) -> i64 {
    crate::engines::now_ms() + delay.as_millis() as i64
}

fn sleep_backoff(backoff: &mut Duration) -> Duration {
    let wait = *backoff;
    *backoff = (*backoff * 2).min(RESUBSCRIBE_BACKOFF_MAX);
    wait
}

async fn sleep_cancellable(delay: Duration, cancel: &CancellationToken) -> bool {
    tokio::select! {
        _ = cancel.cancelled() => false,
        _ = tokio::time::sleep(delay) => true,
    }
}

/// Wait until the engine is connected (or the session ends). `false` when
/// the session was cancelled.
async fn wait_while_disconnected(
    handle: &EngineHandle,
    core: &Arc<SessionCore>,
    cancel: &CancellationToken,
) -> bool {
    if handle.is_connected() {
        return true;
    }
    core.set_subscription_down(handle.retry_at_ms());
    let Some(mut fleet) = core.fleet() else {
        return false;
    };
    let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
    loop {
        if cancel.is_cancelled() {
            return false;
        }
        if handle.is_connected() {
            return true;
        }
        let until = tokio::time::Instant::now() + Duration::from_secs(1);
        tokio::select! {
            _ = cancel.cancelled() => return false,
            changed = fleet.changed() => {
                if changed.is_err() {
                    // The client is shutting down; wait out the poll window
                    // rather than spin.
                    tokio::time::sleep_until(until).await;
                }
            }
            _ = tokio::time::sleep_until(until.min(deadline)) => {}
        }
        if tokio::time::Instant::now() >= deadline {
            return handle.is_connected();
        }
    }
}

/// Build the ticket-12 resume summary when the engine advertises
/// `transcript-resume-v1` and this session holds a digest-carrying copy —
/// cached or live ("on a heal or a reopen that has a cached or held
/// copy"). `from_held` rejects copies without digests, which degrade to a
/// plain digests subscription and a reset.
fn resume_summary(core: &Arc<SessionCore>) -> Option<roboco_doc::TranscriptResume> {
    if !core.resume_capable() {
        return None;
    }
    let state = lock(&core.state);
    if state.entries.is_empty() {
        return None;
    }
    let entries: Vec<SessionMessageEntry> = state.entries.iter().map(|e| (**e).clone()).collect();
    roboco_doc::TranscriptResume::from_held(&entries, &state.digests, RESUME_WINDOW)
}

/// Reconciler note (deleted chats): the engine never rejects a
/// subscription for an unknown chat id — `DocHost::open` materializes an
/// empty doc for it — so there is no terminal open error to surface; a
/// chat deleted on the engine opens empty here and the parity-75 give-up
/// path is what runs. Verified against `crates/engine/src/doc_host.rs:746`.
///
/// Sanitize, read `historyPending`, decode, and scope one stream item.
fn decode_item(mut value: Value, key: &EngineKey) -> Option<DecodedFrame> {
    crate::lenient::sanitize_transcript_update(&mut value);
    let history_pending = value
        .get("historyPending")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let mut update: TranscriptUpdate = serde_json::from_value(value).ok()?;
    // Scope `deviceId` and `subagentRef` onto the owning engine (ticket 04's
    // rule, ported from the desktop registry).
    scope_transcript_frame(key, &mut update);
    Some(DecodedFrame {
        update,
        history_pending,
    })
}

impl SessionCore {
    /// Apply one decoded frame under the state lock and publish. Returns
    /// `Err` on desync (the caller resubscribes immediately).
    pub(crate) fn apply_decoded(&self, frame: DecodedFrame) -> Result<(), TranscriptDesync> {
        let mut ids = Vec::new();
        frame_entry_ids(&frame.update.frame, &mut ids);
        let digests = frame.update.digests.clone();
        let context_usage = frame.update.context_usage;
        let is_reset = matches!(frame.update.frame, TranscriptFrame::Reset { .. });
        let history_pending = frame.history_pending;
        let wire_frame = frame.update.frame;
        let revision;
        {
            let mut state = lock(&self.state);
            // Opening-tail rule: a `historyPending` preview is adopted only
            // when nothing complete is shown yet (no cache seed, no prior
            // full reset in this handle).
            if history_pending && (state.hydrated_from_cache || state.has_full_reset) {
                return Ok(());
            }
            apply_frame_arc(&mut state.entries, wire_frame)?;
            state.hydrated = true;
            state.history_pending = history_pending;
            state.context_usage = context_usage;
            if !history_pending {
                state.has_full_reset = true;
            }
            if let Some(digests) = digests.filter(|d| !d.is_empty()) {
                if is_reset {
                    // A reset carries every digest; stale ids die with it.
                    state.digests = digests.into_iter().collect();
                } else {
                    // A delta carries exactly the ids it upserts or appends
                    // to; merge, then drop ids whose entries left.
                    state.digests.extend(digests);
                    let live: std::collections::HashSet<String> =
                        state.entries.iter().map(|e| e.id.clone()).collect();
                    state.digests.retain(|id, _| live.contains(id));
                }
            } else if is_reset {
                // Ticket 12: a reset without digests drops the stored ones.
                // They describe content the reset just replaced, and a
                // later resume summary built from them would be stale.
                state.digests.clear();
            }
            // Adoption: any frame id matching a pending send's message id
            // adopts it (no duplicate row — the engine reuses the id).
            if !ids.is_empty() {
                state.pending.retain(|p| !ids.contains(&p.message_id));
            }
            state.transcript_revision += 1;
            revision = state.transcript_revision;
        }
        self.publish_transcript(revision);
        // The composer reads the entries too (`open_input`, the streaming
        // tail, adoption clearing `send_state`), so every applied frame
        // recomputes it — not only engine-row changes.
        self.recompute_composer();
        Ok(())
    }

    /// Save the current transcript (+ digests) through the debounced cache
    /// writer. The caller skips while `history_pending`.
    pub(crate) fn save_transcript_cache(&self) {
        let (entries, digests) = {
            let state = lock(&self.state);
            (
                state
                    .entries
                    .iter()
                    .map(|e| (**e).clone())
                    .collect::<Vec<_>>(),
                state.digests.clone(),
            )
        };
        self.cache.save_transcript_state(
            &self.raw_chat_id,
            CachedTranscript {
                entries,
                digests,
            },
        );
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use roboco_doc::transcript_delta::{apply_transcript_frame, diff_transcript};
    use std::collections::BTreeMap;

    fn entry(id: &str, text: &str) -> SessionMessageEntry {
        SessionMessageEntry {
            id: id.into(),
            role: roboco_doc::MessageRole::User,
            parts: vec![MessagePart::Text {
                id: "t0".into(),
                text: text.into(),
            }],
            created_at: 0,
            device_id: String::new(),
            status: None,
            continuation_of: None,
            duration_ms: None,
        }
    }

    /// A small deterministic xorshift, so failures reproduce.
    fn rng(seed: u64) -> impl FnMut() -> u64 {
        let mut state = seed;
        move || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        }
    }

    /// The same frame sequence applied to `Vec` and `Vec<Arc>` yields equal
    /// contents (the property the ticket pins). Frames come from the
    /// engine's own `diff_transcript`, so they are well-formed by
    /// construction: appends carry true UTF-8 byte lengths, deltas carry the
    /// true count, and heavy churn degrades to resets exactly like the wire.
    #[test]
    fn arc_apply_matches_plain_apply_on_random_frame_sequences() {
        let mut random = rng(0x5eed_cafe);
        for case in 0..300u64 {
            let mut current: Vec<SessionMessageEntry> = Vec::new();
            let mut arc: Vec<Arc<SessionMessageEntry>> = Vec::new();
            let mut next_id = 0u32;
            for _step in 0..10 {
                let mut target = current.clone();
                // Mutate: append text, edit, insert, remove, or reset.
                match random() % 5 {
                    0 if !target.is_empty() => {
                        let at = (random() as usize) % target.len();
                        if let Some(MessagePart::Text { text, .. }) =
                            target[at].parts.first_mut()
                        {
                            text.push_str(&format!(" chunk{}", random() % 5));
                        }
                    }
                    1 if !target.is_empty() => {
                        let at = (random() as usize) % target.len();
                        let text = format!("rewritten {}", random() % 9);
                        target[at].parts = vec![MessagePart::Text {
                            id: "t0".into(),
                            text,
                        }];
                    }
                    _ => {
                        next_id += 1;
                        let at = (random() as usize) % (target.len() + 1);
                        target.insert(
                            at,
                            entry(&format!("m{next_id}"), &format!("body {next_id}")),
                        );
                    }
                }
                if target.len() > 6 {
                    let drop = (random() as usize) % target.len();
                    target.remove(drop);
                }
                let frame = diff_transcript(&current, &target);
                let plain_frame = clone_frame(&frame);
                let arc_frame = frame;
                let plain = apply_transcript_frame(&mut current, plain_frame);
                let shared = apply_frame_arc(&mut arc, arc_frame);
                assert_eq!(plain.is_ok(), shared.is_ok(), "case {case} diverged");
                let expected: Vec<SessionMessageEntry> = arc.iter().map(|e| (**e).clone()).collect();
                assert_eq!(current, expected, "case {case}");
                current = expected;
            }
        }
    }

    fn clone_frame(frame: &TranscriptFrame) -> TranscriptFrame {
        match frame {
            TranscriptFrame::Reset { reset } => TranscriptFrame::Reset {
                reset: reset.clone(),
            },
            TranscriptFrame::Delta {
                upsert,
                append,
                remove,
                count,
            } => TranscriptFrame::Delta {
                upsert: upsert.clone(),
                append: append.clone(),
                remove: remove.clone(),
                count: *count,
            },
        }
    }

    #[test]
    fn an_append_replaces_only_the_touched_arc() {
        let mut entries = vec![
            Arc::new(entry("a", "one")),
            Arc::new(entry("b", "two")),
            Arc::new(entry("c", "three")),
        ];
        let before: Vec<Arc<SessionMessageEntry>> = entries.clone();
        let append = roboco_doc::TextAppend {
            entry: "b".into(),
            part: "t0".into(),
            text: "!!!".into(),
            len: 6,
        };
        apply_frame_arc(
            &mut entries,
            TranscriptFrame::Delta {
                upsert: vec![],
                append: vec![append],
                remove: vec![],
                count: 3,
            },
        )
        .unwrap();
        assert!(Arc::ptr_eq(&entries[0], &before[0]));
        assert!(Arc::ptr_eq(&entries[2], &before[2]));
        assert!(!Arc::ptr_eq(&entries[1], &before[1]));
        assert_eq!(
            entries[1]
                .parts
                .iter()
                .find_map(|p| match p {
                    MessagePart::Text { id, text } if id == "t0" => Some(text.clone()),
                    _ => None,
                })
                .unwrap(),
            "two!!!"
        );
    }

    #[test]
    fn a_reset_with_identical_content_keeps_every_arc() {
        let entries: Vec<Arc<SessionMessageEntry>> =
            vec![Arc::new(entry("a", "one")), Arc::new(entry("b", "two"))];
        let mut next = entries.clone();
        let reset = next.iter().map(|e| (**e).clone()).collect::<Vec<_>>();
        apply_frame_arc(&mut next, TranscriptFrame::Reset { reset }).unwrap();
        assert!(next.iter().zip(&entries).all(|(a, b)| Arc::ptr_eq(a, b)));
    }

    #[test]
    fn an_upsert_reuses_the_arc_when_only_the_position_moves() {
        let mut entries = vec![
            Arc::new(entry("a", "one")),
            Arc::new(entry("b", "two")),
            Arc::new(entry("c", "three")),
        ];
        let before = entries.clone();
        // Move "c" to the head (same content).
        apply_frame_arc(
            &mut entries,
            TranscriptFrame::Delta {
                upsert: vec![TranscriptUpsert {
                    after: None,
                    entry: (*before[2]).clone(),
                }],
                append: vec![],
                remove: vec![],
                count: 3,
            },
        )
        .unwrap();
        assert_eq!(entries[0].id, "c");
        let expected = [&before[2], &before[0], &before[1]];
        assert!(entries.iter().zip(expected).all(|(a, b)| Arc::ptr_eq(a, b)));
    }

    #[test]
    fn each_desync_case_errors_and_leaves_no_half_state() {
        // Missing anchor.
        let mut entries = vec![Arc::new(entry("a", "one"))];
        let error = apply_frame_arc(
            &mut entries,
            TranscriptFrame::Delta {
                upsert: vec![TranscriptUpsert {
                    after: Some("nope".into()),
                    entry: entry("b", "two"),
                }],
                append: vec![],
                remove: vec![],
                count: 2,
            },
        )
        .unwrap_err();
        assert!(error.0.contains("missing anchor"));
        assert_eq!(entries.len(), 1, "the failed upsert never landed");
        // Missing append entry.
        let append = append_of("ghost", "t0", "?", 1);
        let error = apply_frame_arc(
            &mut entries,
            TranscriptFrame::Delta {
                upsert: vec![],
                append: vec![append],
                remove: vec![],
                count: 1,
            },
        )
        .unwrap_err();
        assert!(error.0.contains("missing append entry"));
        // Missing append part.
        let append = append_of("a", "t9", "?", 4);
        let error = apply_frame_arc(
            &mut entries,
            TranscriptFrame::Delta {
                upsert: vec![],
                append: vec![append],
                remove: vec![],
                count: 1,
            },
        )
        .unwrap_err();
        assert!(error.0.contains("missing append part"));
        // Wrong len (UTF-8 bytes, not chars).
        let append = append_of("a", "t0", "héllo", 5); // 6 bytes
        let error = apply_frame_arc(
            &mut entries,
            TranscriptFrame::Delta {
                upsert: vec![],
                append: vec![append],
                remove: vec![],
                count: 1,
            },
        )
        .unwrap_err();
        assert!(error.0.contains("append length mismatch"));
        // Wrong count.
        let error = apply_frame_arc(
            &mut entries,
            TranscriptFrame::Delta {
                upsert: vec![],
                append: vec![],
                remove: vec![],
                count: 9,
            },
        )
        .unwrap_err();
        assert!(error.0.contains("count mismatch"));
    }

    fn append_of(entry: &str, part: &str, text: &str, len: usize) -> roboco_doc::TextAppend {
        roboco_doc::TextAppend {
            entry: entry.into(),
            part: part.into(),
            text: text.into(),
            len,
        }
    }

    fn entry_arc(id: &str, text: &str) -> Arc<SessionMessageEntry> {
        Arc::new(entry(id, text))
    }

    /// A `historyPending` preview is adopted only when nothing complete is
    /// shown yet, and a `historyPending` view is never what the cache write
    /// sees (the subscription task's `!history_pending` gate).
    #[test]
    fn an_opening_tail_preview_is_ignored_once_content_is_shown() {
        use crate::session::SessionCore;
        let core = test_core();
        // Nothing shown: the preview IS adopted and marks history_pending.
        let preview = DecodedFrame {
            update: TranscriptUpdate {
                frame: TranscriptFrame::Reset {
                    reset: vec![entry("preview-1", "tail")],
                },
                context_usage: None,
                replay_baseline: None,
                digests: None,
            },
            history_pending: true,
        };
        core.apply_decoded(preview).unwrap();
        {
            let state = lock(&core.state);
            assert_eq!(state.entries.len(), 1);
            assert!(state.history_pending);
        }
        // A full reset clears the preview flag.
        let full = DecodedFrame {
            update: TranscriptUpdate {
                frame: TranscriptFrame::Reset {
                    reset: vec![entry("real-1", "authoritative"), entry("real-2", "rows")],
                },
                context_usage: None,
                replay_baseline: None,
                digests: None,
            },
            history_pending: false,
        };
        core.apply_decoded(full).unwrap();
        // A second preview (a heal resubscribing with openingTail) is
        // IGNORED: complete content is already shown.
        let stale = DecodedFrame {
            update: TranscriptUpdate {
                frame: TranscriptFrame::Reset {
                    reset: vec![entry("preview-2", "stale tail")],
                },
                context_usage: None,
                replay_baseline: None,
                digests: None,
            },
            history_pending: true,
        };
        core.apply_decoded(stale).unwrap();
        let state = lock(&core.state);
        assert_eq!(
            state.entries.iter().map(|e| e.id.clone()).collect::<Vec<_>>(),
            vec!["real-1".to_string(), "real-2".to_string()],
            "the stale preview never replaces the authoritative content"
        );
        assert!(!state.history_pending);
        // And the cache-write gate: a history-pending view is never written.
        assert!(!should_write_cache(true, Some(false), CACHE_WRITE_MIN_INTERVAL * 10));
        assert!(!should_write_cache(true, None, CACHE_WRITE_MIN_INTERVAL * 10));
        // A quiet tail writes every frame; a streaming tail is throttled.
        assert!(should_write_cache(false, Some(false), Duration::ZERO));
        assert!(!should_write_cache(false, Some(true), Duration::ZERO));
        assert!(should_write_cache(
            false,
            Some(true),
            CACHE_WRITE_MIN_INTERVAL + Duration::from_millis(1)
        ));
    }

    /// Ticket 12: right after a desync the next subscribe carries no
    /// `resume` (a plain digests reset), so a bad summary cannot loop; the
    /// attempt after that builds a summary again.
    #[test]
    fn the_resubscribe_after_a_desync_never_resumes() {
        let mut after_desync = true;
        let mut built = 0;
        let first = resume_for_attempt(&mut after_desync, || {
            built += 1;
            Some("summary")
        });
        assert_eq!(first, None, "the post-desync attempt must not resume");
        assert_eq!(built, 0, "no summary is even built for it");
        assert!(!after_desync, "the flag is consumed");
        let second = resume_for_attempt(&mut after_desync, || {
            built += 1;
            Some("summary")
        });
        assert_eq!(second, Some("summary"));
        assert_eq!(built, 1);

        // Without a summary the params are a plain subscribe that still asks
        // a resume-capable engine for digests, and never for the opening tail
        // once content is shown.
        let plain = subscribe_params("chat-1", None, false, true);
        assert!(plain.get("resume").is_none());
        assert!(plain.get("openingTail").is_none());
        assert_eq!(plain["digests"], Value::Bool(true));
        assert_eq!(plain["chatId"], Value::String("chat-1".into()));
        let first_open = subscribe_params("chat-1", None, true, false);
        assert_eq!(first_open["openingTail"], Value::Bool(true));
        assert!(first_open.get("digests").is_none());
    }

    /// Ticket 12: a reset that carries no digests drops the stored ones; a
    /// reset that carries them replaces them.
    #[test]
    fn a_reset_without_digests_drops_the_stored_digests() {
        let core = test_core();
        let reset = |digests: Option<BTreeMap<String, String>>| DecodedFrame {
            update: TranscriptUpdate {
                frame: TranscriptFrame::Reset {
                    reset: vec![entry("a", "one"), entry("b", "two")],
                },
                context_usage: None,
                replay_baseline: None,
                digests,
            },
            history_pending: false,
        };
        let with = BTreeMap::from([
            ("a".to_string(), "d-a".to_string()),
            ("b".to_string(), "d-b".to_string()),
        ]);
        core.apply_decoded(reset(Some(with))).unwrap();
        assert_eq!(lock(&core.state).digests.len(), 2);
        core.apply_decoded(reset(None)).unwrap();
        assert!(
            lock(&core.state).digests.is_empty(),
            "stale digests survived a reset without digests"
        );
    }

    fn test_core() -> SessionCore {
        crate::session::testkit::core()
    }
}
