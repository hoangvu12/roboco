//! Completion notices (orchestration v2): when a turn of an agent-created,
//! notifying side chat comes to rest, the engine records an outcome for
//! the chat that created it — based on its own recorded turn-end state,
//! never on anything the agent calls — and wakes the parent with one
//! engine-authored notice message that starts a parent turn.
//!
//! Ticket 12 was the tracer bullet (outcome records, idle-parent
//! delivery). This module carries ticket 13's rules on top:
//!
//! - **Busy parent:** a notice is never steered into a working parent. The
//!   outcome stays pending until the parent's turn ends, and the notice
//!   then starts its NEXT turn (the run loop calls [`deliver`] from its
//!   turn-end sites — see `spawn_notice_delivery` in `sessions.rs`).
//! - **Merging:** outcomes that arrive before a delivery — several side
//!   chats finishing while the parent works, or one chat's later turns —
//!   pile up in the same pending set and are delivered as ONE notice.
//! - **Durability:** the pending set is mirrored to an engine-local ledger
//!   (ADR 0004) on every change and loaded at assembly, so undelivered
//!   outcomes survive an engine restart and reach their parent after boot.
//! - **Runs lost to a restart:** boot recovery records an [`Outcome::Interrupted`]
//!   outcome for a notifiable run the restart killed and did not
//!   auto-resume. An auto-resumed run owes nothing at boot — the
//!   re-dispatch keeps the starting message's origin, so the notice
//!   follows the resumed turn's own end.
//! - **Archived chats are quiet both ways:** an archived side chat records
//!   nothing for its parent, and an archived parent is owed nothing.
//!
//! Ticket 14's rules on top:
//!
//! - **Acknowledgement:** an outcome is dropped when the parent observed it
//!   after it happened — through `wait_for_turn`, `send_message` with wait,
//!   `read_chat` or `get_chat`. The MCP server reports such observations to
//!   the engine ([`CompletionNotices::ack`]); each side chat carries a
//!   monotonic settle serial advanced before its settled status publishes,
//!   so an observation covers every outcome at or below the serial it
//!   observed — including a record that lands right after the report raced
//!   it — and never one for a later turn.
//! - **Stop and interrupt:** stopping or interrupting the parent drops the
//!   notices owed to it and suppresses outcomes from side chats whose
//!   notifiable turns were running at that moment ([`stop_parent`] — T3's
//!   interrupt behaviour). Side chats keep running; Stop does not cascade.
//!   A later message from the parent to a side chat re-arms its next turn
//!   (`mark_notifiable` in `sessions.rs`).
//! - **Loop cap:** at most [`NOTICE_LOOP_CAP`] consecutive parent turns may
//!   be started by notices without a user message in between. Beyond it,
//!   the notice parks as a visible, PAUSED queue row on the parent and
//!   waits for the user: a user-authored message resets the count and
//!   thaws the queue, and the row then runs as the parent's next turn.
//!
//! The notice message is a SYSTEM entry — never a user message — carrying a
//! [`roboco_doc::MessagePart::CompletionNotice`] marker so clients can render
//! it distinctly (ticket 15), with the same text as a plain part so older
//! layouts stay readable. Its text names each side chat by title and short
//! id with its outcome, and points the parent agent at `read_chat`.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, PoisonError};

use serde::{Deserialize, Serialize};

use roboco_doc::{
    MessagePart, MessageRole, MessageStatus, NoticeOutcome, NoticeSide, QueuedMessage,
    SessionMessageEntry,
};

use crate::doc_host::DocHost;
use crate::sessions::SessionsEngine;
use crate::{new_id, now_ms};

/// Cap for a notice's one-line detail — an error's reason or a question's
/// summary. Matches the transcript's tool-output summary budget: the notice
/// is a pointer to `read_chat`, never a report.
pub(crate) const NOTICE_DETAIL_MAX: usize = 160;

/// The reason a restart-lost outcome carries: one line, from the engine's
/// own interruption note ("Run interrupted by engine restart").
pub(crate) const INTERRUPTED_REASON: &str = "engine restart";

/// At most this many consecutive parent turns may be started by notices
/// without a user message in between (ticket 14; the spec's default 25).
/// Beyond it a notice parks in the parent's queue, paused, until the user
/// acts — a runaway orchestration loop stops and waits for them.
pub(crate) const NOTICE_LOOP_CAP: u32 = 25;

/// The id prefix the engine stamps on its own notice artifacts: the system
/// notice entry a delivery writes, and the paused queue row the loop cap
/// parks (ticket 14). The turn-start bookkeeping keys on it — a turn whose
/// starting message id carries it is notice-started, not user-started
/// (see [`CompletionNotices::notice_turn_started`]).
pub(crate) const NOTICE_ID_PREFIX: &str = "notice:";

/// The engine's recorded turn-end state for one notifiable side-chat turn.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Outcome {
    /// The turn completed.
    Completed,
    /// The turn errored, with its one-line reason.
    Errored { reason: Option<String> },
    /// The turn was stopped or interrupted by the user.
    Stopped,
    /// The run was lost to an engine restart and not auto-resumed (ticket
    /// 13): the parent is told the work never finished. An auto-resumed
    /// run owes nothing here — its notice follows the resumed turn's end.
    Interrupted { reason: Option<String> },
    /// The turn is waiting for input, with the one-line summary of the
    /// question or approval it is waiting on.
    WaitingForInput { summary: String },
}

impl Outcome {
    /// The wire row this outcome records as: the outcome kind plus the
    /// one-line detail it carries. One shape for the notice row, the
    /// durable ledger and the notice text.
    fn as_row(&self) -> (NoticeOutcome, Option<String>) {
        match self {
            Outcome::Completed => (NoticeOutcome::Completed, None),
            Outcome::Errored { reason } => (NoticeOutcome::Errored, reason.clone()),
            Outcome::Stopped => (NoticeOutcome::Stopped, None),
            Outcome::Interrupted { reason } => (NoticeOutcome::Interrupted, reason.clone()),
            Outcome::WaitingForInput { summary } => {
                (NoticeOutcome::WaitingForInput, Some(summary.clone()))
            }
        }
    }

    /// Rebuild an outcome from a stored row ([`Outcome::as_row`]'s inverse).
    fn from_row(outcome: NoticeOutcome, detail: Option<String>) -> Outcome {
        match outcome {
            NoticeOutcome::Completed => Outcome::Completed,
            NoticeOutcome::Errored => Outcome::Errored { reason: detail },
            NoticeOutcome::Stopped => Outcome::Stopped,
            NoticeOutcome::Interrupted => Outcome::Interrupted { reason: detail },
            NoticeOutcome::WaitingForInput => Outcome::WaitingForInput {
                summary: detail.unwrap_or_else(|| "input".into()),
            },
        }
    }
}

/// An outcome recorded for a parent, awaiting delivery.
#[derive(Debug, Clone)]
pub(crate) struct PendingOutcome {
    /// The side chat whose turn ended.
    pub(crate) side_chat_id: String,
    pub(crate) outcome: Outcome,
    /// The side chat's settle serial this outcome belongs to (ticket 14):
    /// the monotonic turn counter stamped by [`CompletionNotices::settle`]
    /// at the turn's settle. An observation of the side chat covers
    /// outcomes at or below its watermark. A reloaded (restarted) outcome
    /// reads as 0, which any later observation covers — that outcome is for
    /// a turn that settled before the restart, so a read after it has seen
    /// it.
    pub(crate) settle: u64,
}

/// One pending outcome as it rests on disk. The wire row's own shape
/// (camelCase, additive — ADR 0005), so the ledger needs no schema of its
/// own and a row written by an older engine still reads.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredOutcome {
    side_chat_id: String,
    outcome: NoticeOutcome,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    detail: Option<String>,
}

impl From<&PendingOutcome> for StoredOutcome {
    fn from(pending: &PendingOutcome) -> Self {
        let (outcome, detail) = pending.outcome.as_row();
        Self {
            side_chat_id: pending.side_chat_id.clone(),
            outcome,
            detail,
        }
    }
}

impl From<StoredOutcome> for PendingOutcome {
    fn from(stored: StoredOutcome) -> Self {
        Self {
            side_chat_id: stored.side_chat_id,
            outcome: Outcome::from_row(stored.outcome, stored.detail),
            // A restarted outcome's serial is unknown — 0 reads as "settled
            // before the restart", which any later observation of the side
            // chat covers (see `PendingOutcome::settle`).
            settle: 0,
        }
    }
}

/// The ledger on disk (ticket 14): the pending outcomes plus the loop-cap
/// counters, both of which must survive a restart. Written as
/// `{ pending, loops }`; a ticket-13 ledger (a bare map of parent →
/// outcome rows) still reads — its pending set loads, its loop counts are
/// empty.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredLedger {
    #[serde(default)]
    pending: HashMap<String, Vec<StoredOutcome>>,
    /// parent → consecutive notice-started turns at the last write.
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    loops: HashMap<String, u32>,
}

/// The notice bookkeeping under one lock: pending outcomes (keyed by
/// parent), the observation watermarks (keyed by parent + side chat), and
/// the loop-cap counters (keyed by parent). One mutex makes every mutation
/// below atomic against a concurrent record, take or acknowledgement —
/// and each persists the whole ledger under the lock.
#[derive(Debug, Default)]
struct NoticeState {
    pending: HashMap<String, Vec<PendingOutcome>>,
    /// (parent, side chat) → the side chat's settle serial when the parent
    /// last observed it (ticket 14): outcomes at or below the watermark owe
    /// no notice, including a record that lands right after the observation.
    acks: HashMap<(String, String), u64>,
    /// parent → consecutive notice-started turns since the last
    /// user-authored message (ticket 14's loop cap).
    loops: HashMap<String, u32>,
}

/// Outcome records owed to parents, keyed by parent chat id.
#[derive(Debug, Default)]
pub(crate) struct CompletionNotices {
    state: Mutex<NoticeState>,
    /// side chat → turns settled so far (ticket 14): the monotonic serial
    /// outcomes and observations compare on. In-memory by design — a
    /// restart's boot recovery re-stamps serials for the outcomes it
    /// records, and a lost watermark costs at most one redundant notice.
    /// Never taken together with [`Self::state`] except by `ack`, which
    /// takes this one first (the one ordering rule to keep deadlock-free).
    settles: Mutex<HashMap<String, u64>>,
    /// Where the ledger is mirrored (ticket 13): an outcome is a result the
    /// parent has not received yet, and losing one means a child's work
    /// silently never reaches its creator. `None` until a store path is
    /// wired — bare `SessionsEngine` tests run in-memory, like the
    /// journal's.
    store: Mutex<Option<PathBuf>>,
}

impl CompletionNotices {
    pub(crate) fn record(&self, parent: &str, outcome: PendingOutcome) {
        let mut state = lock(&self.state);
        // The parent observed this settle already (ticket 14): the
        // acknowledgement raced the record — most tightly through
        // `wait_for_turn`, which resolves off the same status flip that
        // triggered the record. Dropped, not delivered.
        if state
            .acks
            .get(&(parent.to_string(), outcome.side_chat_id.clone()))
            .is_some_and(|watermark| outcome.settle <= *watermark)
        {
            return;
        }
        state
            .pending
            .entry(parent.to_string())
            .or_default()
            .push(outcome);
        self.persist(&state);
    }

    /// Take everything owed to `parent` for a delivery. `None` when the
    /// parent is owed nothing.
    pub(crate) fn take_for_delivery(&self, parent: &str) -> Option<Vec<PendingOutcome>> {
        let mut state = lock(&self.state);
        let outcomes = state
            .pending
            .remove(parent)
            .filter(|outcomes| !outcomes.is_empty());
        if outcomes.is_some() {
            self.persist(&state);
        }
        outcomes
    }

    /// A delivery that could not start a parent turn (busy again, no run
    /// configuration, no doc): the outcomes stay owed, at the front of the
    /// queue in their recorded order.
    pub(crate) fn requeue(&self, parent: &str, outcomes: Vec<PendingOutcome>) {
        if outcomes.is_empty() {
            return;
        }
        let mut state = lock(&self.state);
        let owed = state.pending.entry(parent.to_string()).or_default();
        for outcome in outcomes.into_iter().rev() {
            owed.insert(0, outcome);
        }
        self.persist(&state);
    }

    /// Archived and deleted parents are owed nothing (ticket 13): the
    /// pending records go, and the ledger forgets them.
    pub(crate) fn drop_for(&self, parent: &str) {
        let mut state = lock(&self.state);
        if state.pending.remove(parent).is_some() {
            self.persist(&state);
        }
    }

    /// The parents owed outcomes right now — the boot delivery's iteration.
    pub(crate) fn parents(&self) -> Vec<String> {
        lock(&self.state).pending.keys().cloned().collect()
    }

    /// Advance and return the chat's settle serial: one per turn that came
    /// to rest (completed, errored, stopped, or parked awaiting input).
    /// Called from the run loop at each settle, BEFORE the settled status
    /// publishes, so anything that observes the chat through that status
    /// sees a serial at least as high as everything it observed.
    pub(crate) fn settle(&self, chat_id: &str) -> u64 {
        let mut settles = lock(&self.settles);
        let serial = settles.entry(chat_id.to_string()).or_insert(0);
        *serial += 1;
        *serial
    }

    /// The parent observed the side chat after its last outcome happened
    /// (ticket 14) — through `wait_for_turn`, `send_message` with wait,
    /// `read_chat` or `get_chat`; the MCP server reports it. Outcomes for
    /// settles at or below the observed serial are dropped (they were
    /// seen), and the watermark stands so a record that lands right after
    /// this — the settle raced the report — is dropped too. Outcomes for
    /// LATER turns owe their notice. Returns the watermark for the log.
    pub(crate) fn ack(&self, parent: &str, side_chat: &str) -> u64 {
        let watermark = lock(&self.settles).get(side_chat).copied().unwrap_or(0);
        let mut state = lock(&self.state);
        if let Some(owed) = state.pending.get_mut(parent) {
            owed.retain(|outcome| {
                !(outcome.side_chat_id == side_chat && outcome.settle <= watermark)
            });
            if owed.is_empty() {
                state.pending.remove(parent);
            }
        }
        state
            .acks
            .insert((parent.to_string(), side_chat.to_string()), watermark);
        self.persist(&state);
        watermark
    }

    /// A notice turn started for `parent` (ticket 14's loop cap): the
    /// consecutive count of notice-started turns grows. Called from the
    /// dispatch path whenever the starting message id carries the engine's
    /// notice prefix — a delivered notice entry or a released parked row.
    pub(crate) fn notice_turn_started(&self, parent: &str) {
        let mut state = lock(&self.state);
        *state.loops.entry(parent.to_string()).or_insert(0) += 1;
        self.persist(&state);
    }

    /// Whether `parent` hit the loop cap: the next notice must not start a
    /// turn — it parks in the queue instead, until a user message arrives.
    pub(crate) fn loop_cap_reached(&self, parent: &str) -> bool {
        lock(&self.state)
            .loops
            .get(parent)
            .copied()
            .unwrap_or(0)
            .ge(&NOTICE_LOOP_CAP)
    }

    /// The parent's current consecutive notice-turn count — the cap's own
    /// number, for logs and tests.
    pub(crate) fn loop_turns(&self, parent: &str) -> u32 {
        lock(&self.state).loops.get(parent).copied().unwrap_or(0)
    }

    /// A user-authored message reached `parent`: the streak of consecutive
    /// notice-started turns is broken, so the cap resets (ticket 14). A
    /// Stop counts as user intervention for the same reason — see
    /// [`stop_parent`].
    pub(crate) fn note_user_message(&self, parent: &str) {
        let mut state = lock(&self.state);
        if state.loops.remove(parent).is_some() {
            self.persist(&state);
        }
    }

    /// Wire the durable ledger and load what a previous engine run left:
    /// outcomes recorded but never delivered survive the restart. A
    /// missing file is a first boot (empty ledger, not a failure); an
    /// unreadable or malformed one starts empty rather than failing boot —
    /// the worst outcome is one undelivered notice, never a dead engine.
    pub(crate) fn load(&self, path: &Path) {
        {
            let mut store = lock(&self.store);
            *store = Some(path.to_path_buf());
        }
        let bytes = match std::fs::read(path) {
            Ok(bytes) => bytes,
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => return,
            Err(err) => {
                tracing::warn!(
                    path = %path.display(),
                    error = %err,
                    "completion-notice ledger unreadable; starting empty"
                );
                return;
            }
        };
        let malformed = |err: serde_json::Error| {
            tracing::warn!(
                path = %path.display(),
                error = %err,
                "completion-notice ledger malformed; starting empty"
            );
        };
        let value: serde_json::Value = match serde_json::from_slice(&bytes) {
            Ok(value) => value,
            Err(err) => {
                malformed(err);
                return;
            }
        };
        // Ticket 14 wrapped the ledger as `{ pending, loops }`; a ticket-13
        // ledger was a bare map of parent → outcome rows. Both load — the
        // legacy shape's loop counts are simply empty (a fresh cap).
        let stored: StoredLedger = if value.get("pending").is_some() {
            match serde_json::from_value(value) {
                Ok(stored) => stored,
                Err(err) => {
                    malformed(err);
                    return;
                }
            }
        } else {
            match serde_json::from_value::<HashMap<String, Vec<StoredOutcome>>>(value) {
                Ok(pending) => StoredLedger {
                    pending,
                    loops: HashMap::new(),
                },
                Err(err) => {
                    malformed(err);
                    return;
                }
            }
        };
        let mut state = lock(&self.state);
        for (parent, rows) in stored.pending {
            state
                .pending
                .entry(parent)
                .or_default()
                .extend(rows.into_iter().map(PendingOutcome::from));
        }
        // A fresh engine's counters are the ledger's: the loop cap must not
        // reset just because the engine restarted (outcomes survive, so a
        // runaway loop would otherwise get 25 more turns per boot).
        state.loops.extend(stored.loops);
        tracing::info!(
            parents = state.pending.len(),
            "completion-notice ledger loaded"
        );
    }

    /// Mirror the notice state into the ledger. Called under the state
    /// lock, so a mutation and its write are atomic against other
    /// mutations — two outcomes racing cannot write snapshots that miss
    /// each other. Synchronous and best-effort: the map is small (one row
    /// per undelivered outcome plus one counter per parent), writes are
    /// rare (a record, a take, a requeue, an acknowledgement, a counter
    /// tick), and a lost write costs at most one notice.
    fn persist(&self, state: &NoticeState) {
        let Some(path) = lock(&self.store).clone() else {
            return;
        };
        let stored = StoredLedger {
            pending: state
                .pending
                .iter()
                .map(|(parent, outcomes)| {
                    (
                        parent.clone(),
                        outcomes.iter().map(StoredOutcome::from).collect(),
                    )
                })
                .collect(),
            loops: state.loops.clone(),
        };
        let result = (|| -> Result<(), std::io::Error> {
            let bytes = serde_json::to_vec(&stored)
                .map_err(|err| std::io::Error::other(err.to_string()))?;
            let temporary = path.with_extension("json.tmp");
            std::fs::write(&temporary, &bytes)?;
            std::fs::rename(&temporary, &path)?;
            Ok(())
        })();
        if let Err(err) = result {
            tracing::warn!(error = %err, "completion-notice ledger write failed");
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
    // Cap by characters, not bytes: multibyte text keeps the full budget.
    let cut: String = line.chars().take(NOTICE_DETAIL_MAX).collect();
    Some(format!("{cut}…"))
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

/// Is this chat archived (ticket 13)? A missing or unreadable row reads as
/// active: only a row that says archived silences notices — a row the
/// engine cannot read must not silently eat a report.
pub(crate) fn chat_archived(engine: &SessionsEngine, chat_id: &str) -> bool {
    // Bind the host first: `workspace()` borrows it, and a
    // `host().and_then(|host| host.workspace())` chain would borrow a
    // closure-local (the same temporary-borrow trap the ticket-12 merge
    // left behind in `notifiable_parent_for`).
    let Some(host) = engine.host() else {
        return false;
    };
    host.workspace()
        .and_then(|ws| ws.chat(chat_id).ok().flatten())
        .is_some_and(|chat| chat.archived)
}

/// Record an outcome for a side-chat turn and deliver what its parent is
/// owed. Called from the run loop, fire-and-forget: the side chat's own
/// event stream never waits on the parent's turn.
pub(crate) async fn record_and_deliver(
    engine: &SessionsEngine,
    side_chat: &str,
    parent: &str,
    settle: u64,
    outcome: Outcome,
) {
    // Archived chats are quiet both ways (ticket 13): an archived side chat
    // sends nothing, an archived parent is owed nothing. Both read the row
    // as it is now — archiving is a deliberate state change, not a flap.
    if chat_archived(engine, side_chat) || chat_archived(engine, parent) {
        return;
    }
    engine.notices().record(
        parent,
        PendingOutcome {
            side_chat_id: side_chat.to_string(),
            outcome,
            settle,
        },
    );
    deliver(engine, parent).await;
}

/// The stopped-parent rule (ticket 14, T3's interrupt behaviour): the
/// pending notices `parent` is owed go — a parent the user stopped must
/// not restart on its own — and side chats whose notifiable turns are
/// running right now stop owing outcomes for them: the claim is cleared,
/// so the record sites (which read it at settle time) record nothing when
/// those turns end. The side chats themselves keep running — Stop does
/// not cascade — and a later message from the parent to one of them
/// re-arms it for that chat's next turn (`mark_notifiable` in
/// `sessions.rs`). A Stop is user intervention for the loop cap too, so
/// the notice-turn streak resets and the cap's parked rows go with the
/// rest of the pending notices.
pub(crate) fn stop_parent(engine: &SessionsEngine, parent: &str) {
    engine.notices().drop_for(parent);
    engine.notices().note_user_message(parent);
    engine.suppress_notifiable_runs(parent);
    drop_parked_notices(engine, parent);
}

/// Remove the loop cap's parked notice rows from the parent's queue (the
/// stopped-parent rule, ticket 14): a parked row IS a pending notice — the
/// visible form the cap left it waiting in — so a Stop drops it like any
/// other. The Stop's own queue pause keeps the drain away while the rows
/// go; a user's next message thaws an empty queue.
fn drop_parked_notices(engine: &SessionsEngine, parent: &str) {
    let Some(host) = engine.host() else {
        return;
    };
    let Ok(handle) = host.open(parent) else {
        return;
    };
    let Ok(rows) = handle.doc().read_queue() else {
        return;
    };
    let parked: Vec<String> = rows
        .into_iter()
        .filter(|row| row.id.starts_with(NOTICE_ID_PREFIX))
        .map(|row| row.id)
        .collect();
    for id in parked {
        let _ = handle.doc().remove_queued(&id);
    }
}

/// Deliver the parent's pending outcomes as one engine-authored notice that
/// starts a parent turn. Called when an outcome lands — an idle parent is
/// woken immediately — and whenever a parent's turn ends (ticket 13's
/// busy-parent rule: the notice was never steered mid-turn; it starts the
/// parent's next turn now). A parent that turns busy again while the
/// delivery is being built requeues its outcomes untouched, for the next
/// turn end.
pub(crate) async fn deliver(engine: &SessionsEngine, parent: &str) {
    // Teardown never starts a parent's notice turn (the same rule that
    // freezes the message queues): the outcomes stay pending — durably —
    // and the next boot delivers them.
    if engine.shutting_down() {
        return;
    }
    if engine.turn_in_flight(parent) {
        return;
    }
    // An archived parent receives nothing (ticket 13): drop what piled up
    // rather than holding it — an archived chat owes no wakeups, even after
    // it is unarchived.
    if chat_archived(engine, parent) {
        engine.notices().drop_for(parent);
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
    // The loop cap (ticket 14): the 26th consecutive notice-started turn
    // does not start. The notice parks as a visible, PAUSED queue row on
    // the parent and waits for the user — a user-authored message resets
    // the count and thaws the queue, and the row then runs as the parent's
    // next turn (counting as notice-started itself, via its id prefix).
    if engine.notices().loop_cap_reached(parent) {
        park_notice(engine, parent, &host, &text, outcomes);
        return;
    }
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
    // receives the notice after that turn instead (ticket 13): it is never
    // steered mid-turn. The entry has not been written yet, so the outcomes
    // go back to the front of the pending queue untouched — the run loop's
    // turn-end delivery picks them up when that turn ends.
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

/// Park a capped notice as a visible, paused queue row on the parent (the
/// loop cap's waiting state, ticket 14). The queue pauses BEFORE the row
/// lands, so the doc-change drain finds it frozen and cannot send the row
/// straight out; a recovered queue starts paused too, so the row keeps
/// waiting across a restart. The row's id carries the notice prefix — the
/// turn it eventually starts still counts as notice-started — and its
/// text is the notice itself, so the queue shows exactly what waits. The
/// outcomes are consumed into the row (a durable carrier of its own:
/// queue rows live on the chat doc); only a row that cannot be written
/// puts them back into the pending set.
fn park_notice(
    engine: &SessionsEngine,
    parent: &str,
    host: &DocHost,
    text: &str,
    outcomes: Vec<PendingOutcome>,
) {
    let row = QueuedMessage {
        id: format!("{NOTICE_ID_PREFIX}{}", new_id()),
        text: text.to_owned(),
        attachments: Vec::new(),
        hold_for_turn_end: false,
        issued_by: host.device_id().to_owned(),
        issued_at: now_ms(),
        edited_at: None,
        delivery_gate: None,
        // Engine-authored, not user-typed: the id prefix (not the origin)
        // carries that for the loop-cap bookkeeping.
        origin_chat_id: None,
    };
    let parked = host.open(parent).ok().and_then(|handle| {
        // Pause first: the row's own write wakes the drain, which must
        // already see a frozen queue.
        handle.pause_queue();
        handle.doc().push_queued(&row).ok()
    });
    if parked.is_none() {
        tracing::warn!(
            parent,
            "completion notice held: the capped notice row could not be parked"
        );
        engine.notices().requeue(parent, outcomes);
        return;
    }
    tracing::info!(
        parent,
        turns = engine.notices().loop_turns(parent),
        "completion notice parked: notice loop cap reached"
    );
}

fn side_row(host: &DocHost, outcome: &PendingOutcome) -> NoticeSide {
    let title = host
        .workspace()
        .and_then(|ws| ws.chat(&outcome.side_chat_id).ok().flatten())
        .and_then(|chat| chat.title)
        .map(|title| title.trim().to_owned())
        .filter(|title| !title.is_empty())
        .unwrap_or_else(|| "Side chat".into());
    let (recorded, detail) = outcome.outcome.as_row();
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
        NoticeOutcome::Errored => with_detail(side, "failed"),
        NoticeOutcome::Stopped => "was stopped".into(),
        NoticeOutcome::Interrupted => with_detail(side, "was interrupted"),
        NoticeOutcome::WaitingForInput => with_detail(side, "is waiting for input"),
    };
    format!("\"{}\" ({}) {}", side.title, short_id(&side.chat_id), outcome)
}

/// `"<verb>: <detail>"` when the row carries a one-liner, else `"<verb>"` —
/// the shape every detail-bearing outcome shares.
fn with_detail(side: &NoticeSide, verb: &str) -> String {
    match &side.detail {
        Some(text) => format!("{verb}: {text}"),
        None => verb.into(),
    }
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
        id: format!("{NOTICE_ID_PREFIX}{}", new_id()),
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

    /// A pending outcome for a turn settled before the engine booted
    /// (the reloaded shape, settle 0).
    fn pending(side_chat: &str, outcome: Outcome) -> PendingOutcome {
        PendingOutcome {
            side_chat_id: side_chat.into(),
            outcome,
            settle: 0,
        }
    }

    fn pending_at(side_chat: &str, settle: u64, outcome: Outcome) -> PendingOutcome {
        PendingOutcome {
            side_chat_id: side_chat.into(),
            outcome,
            settle,
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
        let single = notice_text(&[completed.clone()]);
        assert_eq!(
            single,
            "Side chat \"Implement login\" (chat-sid) finished.\n\
             Read the full result with the Roboco `read_chat` tool, chat chat-sid."
        );

        let mut errored = side("chat-side-5678", "Flaky test", NoticeOutcome::Errored);
        errored.detail = Some("the build command timed out".into());
        let mut waiting = side("chat-side-90ab", "Review the API", NoticeOutcome::WaitingForInput);
        waiting.detail = Some("Should we bump the major version?".into());
        let merged = notice_text(&[completed, errored, waiting]);
        assert!(merged.starts_with("3 side chats reached outcomes:\n"));
        assert!(merged.contains("- \"Implement login\" (chat-sid) finished."));
        assert!(merged.contains("- \"Flaky test\" (chat-sid) failed: the build command timed out."));
        assert!(
            merged
                .contains("- \"Review the API\" (chat-sid) is waiting for input: Should we bump the major version?")
        );
        assert!(merged.contains("`read_chat`"));
        // A stopped row carries no detail.
        let stopped = side("chat-side-cdef", "Migration", NoticeOutcome::Stopped);
        assert!(notice_text(&[stopped]).contains("\"Migration\" (chat-sid) was stopped."));
        // A restart-lost row says interrupted, with its one-line reason.
        let mut interrupted = side("chat-side-cdef", "Migration", NoticeOutcome::Interrupted);
        interrupted.detail = Some(INTERRUPTED_REASON.into());
        let text = notice_text(&[interrupted]);
        assert!(text.contains("\"Migration\" (chat-sid) was interrupted: engine restart."));
        // Degenerate empty input still renders a pointer, never a panic.
        assert!(notice_text(&[]).contains("read_chat"));
    }

    #[test]
    fn pending_outcomes_record_take_and_requeue() {
        let notices = CompletionNotices::default();
        assert!(notices.take_for_delivery("parent").is_none());
        notices.record("parent", pending("chat-side-1", Outcome::Completed));
        notices.record("parent", pending("chat-side-2", Outcome::Stopped));
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

    #[test]
    fn drop_for_forgets_a_parent_and_parents_lists_the_owed() {
        let notices = CompletionNotices::default();
        assert!(notices.parents().is_empty());
        notices.record("parent-a", pending("chat-side-1", Outcome::Completed));
        notices.record(
            "parent-b",
            pending(
                "chat-side-2",
                Outcome::Interrupted {
                    reason: Some(INTERRUPTED_REASON.into()),
                },
            ),
        );
        let mut owed = notices.parents();
        owed.sort();
        assert_eq!(owed, vec!["parent-a".to_string(), "parent-b".to_string()]);
        notices.drop_for("parent-a");
        assert_eq!(notices.parents(), vec!["parent-b".to_string()]);
        // Dropping a parent that is owed nothing stays a no-op.
        notices.drop_for("parent-a");
        assert_eq!(notices.parents(), vec!["parent-b".to_string()]);
    }

    #[test]
    fn pending_outcomes_survive_a_store_round_trip() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("completion-notices.json");

        let first = CompletionNotices::default();
        // Before a store is wired, records are in-memory only.
        first.record("parent", pending("chat-side-ephemeral", Outcome::Completed));
        // Wiring the ledger on a fresh engine: no file, no outcomes.
        let second = CompletionNotices::default();
        second.load(&path);
        assert!(second.take_for_delivery("parent").is_none());

        second.record("parent", pending("chat-side-1234", Outcome::Completed));
        second.record(
            "parent",
            pending(
                "chat-side-5678",
                Outcome::Errored {
                    reason: Some("provider 502: bad gateway".into()),
                },
            ),
        );
        second.record(
            "other-parent",
            pending(
                "chat-side-90ab",
                Outcome::Interrupted {
                    reason: Some(INTERRUPTED_REASON.into()),
                },
            ),
        );
        // A requeue (a delivery that could not start) is as durable as a
        // record: the next engine still owes the outcomes.
        second.requeue(
            "parent",
            vec![pending(
                "chat-side-cdef",
                Outcome::WaitingForInput {
                    summary: "Should we bump the major version?".into(),
                },
            )],
        );

        let reloaded = CompletionNotices::default();
        reloaded.load(&path);
        let mut owed = reloaded.parents();
        owed.sort();
        assert_eq!(
            owed,
            vec!["other-parent".to_string(), "parent".to_string()]
        );
        let mut parent = reloaded.take_for_delivery("parent").unwrap();
        assert_eq!(parent.len(), 3);
        // The requeued outcome keeps its place at the front.
        assert_eq!(parent.remove(0).side_chat_id, "chat-side-cdef");
        assert_eq!(parent[0].side_chat_id, "chat-side-1234");
        assert_eq!(parent[0].outcome, Outcome::Completed);
        assert_eq!(
            parent[1].outcome,
            Outcome::Errored {
                reason: Some("provider 502: bad gateway".into())
            }
        );
        assert_eq!(
            reloaded.take_for_delivery("other-parent").unwrap()[0].outcome,
            Outcome::Interrupted {
                reason: Some(INTERRUPTED_REASON.into())
            }
        );
        // A ledger taken is a ledger written: re-delivery owes nothing.
        let third = CompletionNotices::default();
        third.load(&path);
        assert!(third.take_for_delivery("parent").is_none());
        assert!(third.take_for_delivery("other-parent").is_none());
    }

    #[test]
    fn a_malformed_ledger_starts_empty_without_panicking() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("completion-notices.json");
        std::fs::write(&path, b"{not json").unwrap();
        let notices = CompletionNotices::default();
        notices.load(&path);
        assert!(notices.parents().is_empty());
        // The wired ledger still accepts new records.
        notices.record("parent", pending("chat-side-1", Outcome::Completed));
        assert_eq!(notices.parents(), vec!["parent".to_string()]);
    }

    #[test]
    fn outcome_rows_round_trip_through_every_kind() {
        for (outcome, recorded, detail) in [
            (Outcome::Completed, NoticeOutcome::Completed, None),
            (
                Outcome::Errored {
                    reason: Some("boom".into()),
                },
                NoticeOutcome::Errored,
                Some("boom".to_string()),
            ),
            (Outcome::Stopped, NoticeOutcome::Stopped, None),
            (
                Outcome::Interrupted {
                    reason: Some(INTERRUPTED_REASON.into()),
                },
                NoticeOutcome::Interrupted,
                Some(INTERRUPTED_REASON.to_string()),
            ),
            (
                Outcome::WaitingForInput {
                    summary: "Proceed?".into(),
                },
                NoticeOutcome::WaitingForInput,
                Some("Proceed?".to_string()),
            ),
        ] {
            let (row, row_detail) = outcome.as_row();
            assert_eq!(row, recorded);
            assert_eq!(row_detail, detail);
            assert_eq!(Outcome::from_row(row, row_detail), outcome);
        }
        // A stored row with no detail still rebuilds: errored keeps `None`,
        // waiting falls back to the generic question summary.
        assert_eq!(
            Outcome::from_row(NoticeOutcome::Errored, None),
            Outcome::Errored { reason: None }
        );
        assert_eq!(
            Outcome::from_row(NoticeOutcome::WaitingForInput, None),
            Outcome::WaitingForInput {
                summary: "input".into()
            }
        );
    }

    // ---- ticket 14: acknowledgement, stop, loop cap -----------------------

    #[test]
    fn an_acknowledgement_drops_observed_outcomes_and_covers_a_racing_record() {
        let notices = CompletionNotices::default();
        // Turn 1 settles: the parent is busy, so the outcome waits.
        let turn_one = notices.settle("chat-side-1");
        notices.record("parent", pending_at("chat-side-1", turn_one, Outcome::Completed));
        assert_eq!(notices.parents(), vec!["parent".to_string()]);

        // The parent observed the chat after the turn ended (wait_for_turn,
        // read_chat, get_chat — all arrive as this call): the outcome goes.
        let watermark = notices.ack("parent", "chat-side-1");
        assert_eq!(watermark, turn_one);
        assert!(notices.parents().is_empty());

        // The report raced the record — the settle and the status flip it
        // observed happened before the observation reached the engine, so a
        // record for THAT settle lands after the ack: dropped too.
        notices.record("parent", pending_at("chat-side-1", turn_one, Outcome::Completed));
        assert!(notices.parents().is_empty());

        // A later turn is a different settle: it owes its notice.
        let turn_two = notices.settle("chat-side-1");
        assert!(turn_two > turn_one);
        notices.record("parent", pending_at("chat-side-1", turn_two, Outcome::Completed));
        let taken = notices.take_for_delivery("parent").unwrap();
        assert_eq!(taken.len(), 1);
        assert_eq!(taken[0].settle, turn_two);
    }

    #[test]
    fn an_ack_covers_only_the_side_chat_it_observed() {
        let notices = CompletionNotices::default();
        let settle = notices.settle("chat-side-a");
        notices.settle("chat-side-b");
        notices.record("parent", pending_at("chat-side-a", settle, Outcome::Completed));
        notices.record("parent", pending_at("chat-side-b", settle, Outcome::Completed));

        // Observing one chat never silences another.
        notices.ack("parent", "chat-side-a");
        let owed = notices.take_for_delivery("parent").unwrap();
        assert_eq!(owed.len(), 1);
        assert_eq!(owed[0].side_chat_id, "chat-side-b");
        // And only for the parent that observed it.
        notices.record("other-parent", pending_at("chat-side-a", settle, Outcome::Completed));
        notices.ack("parent", "chat-side-a");
        assert_eq!(notices.parents(), vec!["other-parent".to_string()]);
    }

    #[test]
    fn an_ack_of_an_unsettled_chat_reads_watermark_zero() {
        let notices = CompletionNotices::default();
        // A side chat that never settled in this engine run: an outcome
        // reloaded from a ledger (settle 0) is still covered — its turn
        // settled before the restart, so a read after it has seen it.
        assert_eq!(notices.ack("parent", "chat-side-1"), 0);
        notices.record("parent", pending("chat-side-1", Outcome::Completed));
        assert!(notices.parents().is_empty());
        // But a fresh settle outruns that watermark.
        let turn = notices.settle("chat-side-1");
        notices.record("parent", pending_at("chat-side-1", turn, Outcome::Completed));
        assert_eq!(notices.parents(), vec!["parent".to_string()]);
    }

    #[test]
    fn the_loop_cap_counts_notice_turns_until_a_user_message() {
        let notices = CompletionNotices::default();
        assert!(!notices.loop_cap_reached("parent"));
        for turn in 1..=NOTICE_LOOP_CAP {
            notices.notice_turn_started("parent");
            assert_eq!(notices.loop_turns("parent"), turn);
        }
        assert!(notices.loop_cap_reached("parent"));
        // One more does not panic and stays capped.
        notices.notice_turn_started("parent");
        assert_eq!(notices.loop_turns("parent"), NOTICE_LOOP_CAP + 1);

        // A user message resets the streak; the cap lifts.
        notices.note_user_message("parent");
        assert_eq!(notices.loop_turns("parent"), 0);
        assert!(!notices.loop_cap_reached("parent"));
        // Resetting a chat with no streak is a no-op.
        notices.note_user_message("other-parent");
        assert_eq!(notices.loop_turns("other-parent"), 0);

        // Another chat's counter is independent.
        for _ in 0..NOTICE_LOOP_CAP {
            notices.notice_turn_started("other-parent");
        }
        assert!(notices.loop_cap_reached("other-parent"));
        assert!(!notices.loop_cap_reached("parent"));
    }

    #[test]
    fn the_loop_counts_survive_a_restart_and_a_legacy_ledger_still_loads() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("completion-notices.json");

        // Ticket 13's ledger: a bare parent → rows map, no counts. It loads,
        // with an empty cap.
        std::fs::write(
            &path,
            serde_json::json!({
                "chat-parent": [
                    { "sideChatId": "chat-side-1", "outcome": "completed" }
                ]
            })
            .to_string(),
        )
        .unwrap();
        let legacy = CompletionNotices::default();
        legacy.load(&path);
        assert_eq!(legacy.parents(), vec!["chat-parent".to_string()]);
        assert_eq!(legacy.loop_turns("chat-parent"), 0);
        assert!(legacy.take_for_delivery("chat-parent").is_some());

        // Ticket 14's ledger carries the counts alongside the pending set.
        for _ in 0..NOTICE_LOOP_CAP {
            legacy.notice_turn_started("chat-parent");
        }
        let reloaded = CompletionNotices::default();
        reloaded.load(&path);
        assert!(reloaded.loop_cap_reached("chat-parent"));
        assert_eq!(reloaded.loop_turns("chat-parent"), NOTICE_LOOP_CAP);
        // A user message still resets it after the reload.
        reloaded.note_user_message("chat-parent");
        assert!(!reloaded.loop_cap_reached("chat-parent"));
        // A count with no pending outcomes is not a parent.
        assert!(reloaded.parents().is_empty());
    }
}
