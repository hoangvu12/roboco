//! Resume on another account (orchestration v2, ticket 09): the engine
//! operation behind a Limited chat's account failover.
//!
//! A Limited **Claude Code** or **Codex** chat can continue on a different
//! harness account of the same harness. The engine:
//!
//! 1. ranks the CACHED harness accounts (no network — see
//!    [`AgentAccounts::failover_candidates`]): switchable, not an API key,
//!    not signed out, not still limited, not the account that hit the limit
//!    nor the live login; lowest usage first, accounts sharing the limited
//!    one's org or workspace ranked lower;
//! 2. probes the chosen one once ([`AgentAccounts::probe_account`]) — a
//!    probe that reveals a dead login or an exhausted window moves to the
//!    next candidate;
//! 3. activates it (the CLI store swap every chat on that harness runs
//!    under from now on);
//! 4. retires the chat's warm harness runtime, and stamps every warm
//!    runtime with the login's credential fingerprint so a runtime started
//!    under another login is never reused (the dispatch guard in
//!    `sessions.rs`);
//! 5. resumes the chat's SAME harness session with the continue prompt —
//!    through the durable command plane, so queued messages stay behind
//!    the continuation exactly as a plain Resume.
//!
//! If the harness refuses the native resume, the continuation request
//! carries the budgeted history (ticket 01) as its fallback: the driver
//! pastes it in front of the prompt on the fresh session it starts. If the
//! resumed turn fails with a signed-out error, the run's own marking
//! (ticket 08) has already marked that account; the engine moves to the
//! next candidate. Any other failure restores the previously active
//! account — a failed failover leaves the harness as it found it.
//!
//! The RPC entry points (`ListResumeAccountCandidates`,
//! `ResumeOnAnotherAccount`) live in `rpc.rs`; this module is the
//! engine-side state machine, driven through [`SessionsEngine`].

use std::collections::HashSet;
use std::time::Duration;

use roboco_doc::{MessagePart, MessageRole, MessageStatus, SessionCommandPayload, SessionMessageEntry};
use roboco_proto::{
    AgentAccount, AgentSignInState, HarnessId, ResumeAccountCandidates, ResumeOnAccountOutcome,
    RESUME_CONTINUE_PROMPT,
};

use crate::agent_accounts::AgentAccounts;
use crate::sessions::SessionsEngine;
use crate::{new_id, now_ms, EngineError};

/// Only these harnesses fail over: each keeps one switchable account login
/// whose sessions survive a switch (research 03). Pure.
pub(crate) fn failover_supported(harness: HarnessId) -> bool {
    matches!(harness, HarnessId::ClaudeCode | HarnessId::Codex)
}

fn harness_label(harness: HarnessId) -> &'static str {
    match harness {
        HarnessId::ClaudeCode => "Claude Code",
        HarnessId::Codex => "Codex",
        _ => "This harness",
    }
}

/// How long the watcher waits for the continuation turn to START. The
/// command dispatches within milliseconds on an idle chat; a wide bound
/// only covers an agent update holding the queue.
const TURN_START_WINDOW: Duration = Duration::from_secs(30);

/// Cap on how many candidates one failover chain tries: the ranked list is
/// short, but a store that keeps returning the same row must never spin.
const MAX_ATTEMPTS: usize = 8;

/// How long the watcher waits for the continuation turn to SETTLE after it
/// started: the wait is driven by the status watch (no polling), so a long
/// turn costs nothing — the bound only stops a watcher leaking forever on
/// a run that never settles (a chat parked on a question for days counts as
/// never here; the switch itself stands, only the chain ends).
const SETTLE_WINDOW: Duration = Duration::from_secs(24 * 60 * 60);

/// How long the watcher re-checks the signed-out mark for after a settle
/// (bounded): the run's own marking is spawned, not awaited (it queues
/// behind the accounts ops lock a usage probe can hold), so the mark can
/// land a beat after the turn errored.
const MARK_GRACE: Duration = Duration::from_secs(2);

/// The ranked switch targets for `chat_id` (ticket 09): the cached rows of
/// the chat's harness, best first, plus the account the chat's limit hit.
/// Empty for a harness without failover, or when every other account is
/// limited, signed out, an API key, or unswitchable — then only Resume at
/// reset is offered. [`SessionsEngine::resume_account_candidates`].
pub(crate) async fn candidates(
    engine: &SessionsEngine,
    chat_id: &str,
) -> Result<ResumeAccountCandidates, EngineError> {
    let session = engine.session_status(chat_id);
    // The harness whose accounts to switch: the limit record's when the
    // chat is Limited (the common path), else the chat's last run's.
    let harness = session
        .as_ref()
        .and_then(|row| row.limit.as_ref().map(|limit| limit.harness))
        .or_else(|| engine.last_request(chat_id).and_then(|last| last.harness));
    let Some(harness) = harness else {
        return Ok(ResumeAccountCandidates {
            chat_id: chat_id.to_string(),
            harness: HarnessId::ClaudeCode,
            limited_account_id: None,
            candidates: Vec::new(),
            signed_out: Vec::new(),
        });
    };
    let (limited_account_id, candidates, signed_out) = match engine.agent_accounts() {
        Some(accounts) => {
            let limited = session
                .as_ref()
                .and_then(|row| row.limit.as_ref())
                .and_then(|limit| limit.account_id.clone());
            if failover_supported(harness) {
                let candidates = accounts.failover_candidates(harness, limited.as_deref()).await;
                (
                    limited,
                    candidates,
                    // The dead logins the picker offers "Sign in again"
                    // for (stories 47/50) — listed, never switched to.
                    accounts.failover_signed_out(harness).await,
                )
            } else {
                (limited, Vec::new(), Vec::new())
            }
        }
        None => (None, Vec::new(), Vec::new()),
    };
    Ok(ResumeAccountCandidates {
        chat_id: chat_id.to_string(),
        harness,
        limited_account_id,
        candidates,
        signed_out,
    })
}

/// **Resume on another account** (ticket 09): rank, probe, activate,
/// retire the warm runtime, dispatch the continuation. Returns the
/// synchronous outcome; the turn's own result arrives through the chat's
/// status, and a signed-out failure is handled by the engine (next
/// candidate, else the previous account restored) before the chat
/// settles. [`SessionsEngine::resume_on_another_account`].
pub(crate) async fn resume_on_another_account(
    engine: &SessionsEngine,
    chat_id: &str,
    chosen: Option<&str>,
) -> Result<ResumeOnAccountOutcome, EngineError> {
    let Some(accounts) = engine.agent_accounts() else {
        return Ok(ResumeOnAccountOutcome::Failed {
            reason: "This engine has no harness accounts.".into(),
        });
    };
    let Some(limit) = engine.session_status(chat_id).and_then(|row| row.limit) else {
        return Ok(ResumeOnAccountOutcome::Failed {
            reason: "Only a Limited chat can resume on another account.".into(),
        });
    };
    let harness = limit.harness;
    if !failover_supported(harness) {
        return Ok(ResumeOnAccountOutcome::Failed {
            reason: format!("{} has no switchable accounts.", harness_label(harness)),
        });
    }
    // The account to restore if the failover fails: the login the harness
    // runs under right now (the account the limit hit, unless the user
    // switched since the chat went Limited).
    let previous = accounts.active_account(harness).await.map(|row| row.id);
    let attempt = attempt(
        engine,
        &accounts,
        chat_id,
        harness,
        limit.account_id.as_deref(),
        previous.clone(),
        HashSet::new(),
        chosen,
    )
    .await;
    match attempt {
        Attempt::Switched(row) => Ok(ResumeOnAccountOutcome::Switched {
            account_id: row.id.clone(),
            account_email: row.email.clone().or_else(|| row.display_name.clone()),
        }),
        Attempt::NoCandidates => Ok(ResumeOnAccountOutcome::NoCandidates),
        Attempt::Failed(reason) => Ok(ResumeOnAccountOutcome::Failed { reason }),
    }
}

/// One attempt's result.
enum Attempt {
    /// The account was probed, activated and the continuation dispatched;
    /// the watcher owns the rest.
    Switched(AgentAccount),
    /// No healthy candidate remains.
    NoCandidates,
    /// The attempt failed; nothing was switched.
    Failed(String),
}

/// Probe, activate, retire and dispatch one candidate (skipping it when
/// the probe reveals it is dead or limited — then the next). `pick`
/// names the user's chosen account — used for the FIRST candidate only,
/// later iterations fall back to the best-ranked. `tried` guards against
/// a store that keeps offering the same row.
#[allow(clippy::too_many_arguments)]
// Boxed with an explicit `Send` bound: `attempt` and `watch` recurse
// (a signed-out continuation tries the next candidate), and a recursive
// async fn's future cannot be proven `Send` for `tokio::spawn` otherwise.
fn attempt<'a>(
    engine: &'a SessionsEngine,
    accounts: &'a AgentAccounts,
    chat_id: &'a str,
    harness: HarnessId,
    limited_account_id: Option<&'a str>,
    previous: Option<String>,
    mut tried: HashSet<String>,
    mut pick: Option<&'a str>,
) -> futures::future::BoxFuture<'a, Attempt> {
    Box::pin(async move {
        for _ in 0..MAX_ATTEMPTS {
            if engine.shutting_down() {
                return Attempt::Failed("the engine is shutting down".into());
            }
            let mut ranked = accounts
                .failover_candidates(harness, limited_account_id)
                .await;
            // Rows this chain already tried are out — a probe that failed
            // transiently (the row never changed) must move ON, not re-pick the
            // same account and die on the second attempt. Skipped rows (signed
            // out, still limited) drop out of the ranking on their own: their
            // probes marked them.
            ranked.retain(|row| !tried.contains(&row.id));
            // The user's pick must be a candidate — anything else (the limited
            // account, a signed-out row, a foreign id) is refused, not silently
            // replaced by the recommendation.
            let candidate = match pick.take() {
                Some(id) => {
                    let Some(at) = ranked.iter().position(|row| row.id == id) else {
                        return Attempt::Failed(
                            "That account is not a candidate — it is limited, signed out, or in use."
                                .into(),
                        );
                    };
                    Some(ranked.remove(at))
                }
                None => ranked.first().cloned(),
            };
            let Some(candidate) = candidate else {
                return Attempt::NoCandidates;
            };
            tried.insert(candidate.id.clone());
            // The chosen account is probed ONCE before switching: fresh meters,
            // a hard rejection caught before anything is touched.
            let row = match accounts.probe_account(harness, &candidate.id).await {
                Ok(row) => row,
                Err(err) => {
                    tracing::warn!(
                        chat = %chat_id,
                        account = %candidate.id,
                        error = %err,
                        "failover probe failed; trying the next candidate"
                    );
                    continue;
                }
            };
            if row.sign_in_state == AgentSignInState::SignedOut {
                tracing::info!(
                    chat = %chat_id,
                    account = %candidate.id,
                    "failover candidate is signed out; trying the next"
                );
                continue;
            }
            if row.limited_until.is_some_and(|until| until > chrono::Utc::now()) {
                tracing::info!(
                    chat = %chat_id,
                    account = %candidate.id,
                    "failover candidate is still limited; trying the next"
                );
                continue;
            }
            // Activate — the swap every chat on this harness now runs under.
            if let Err(err) = accounts.activate(harness, &row.id).await {
                tracing::warn!(
                    chat = %chat_id,
                    account = %row.id,
                    error = %err,
                    "failover activation failed; trying the next candidate"
                );
                continue;
            }
            // Retire the chat's warm runtime so the continuation starts under
            // the new login (the fingerprint guard covers every other chat's
            // warm runtime at its next dispatch).
            let _ = engine.terminate(chat_id).await;
            if let Err(reason) = dispatch_continuation(engine, chat_id, harness) {
                // The switch landed but the continuation could not be queued:
                // undo it, or the harness is left on an account nothing uses.
                restore(engine, accounts, chat_id, harness, previous.as_deref()).await;
                return Attempt::Failed(reason);
            }
            // The watcher owns the turn's outcome: signed-out → the next
            // candidate; other failures → the previous account back.
            let watcher_engine = engine.clone();
            let watcher_accounts = accounts.clone();
            let watcher_chat = chat_id.to_string();
            let watcher_limited = limited_account_id.map(str::to_owned);
            let watcher_previous = previous.clone();
            tokio::spawn(async move {
                watch(
                    &watcher_engine,
                    &watcher_accounts,
                    &watcher_chat,
                    harness,
                    watcher_limited.as_deref(),
                    watcher_previous,
                    tried,
                )
                .await;
            });
            return Attempt::Switched(row);
        }
        Attempt::Failed("too many failover attempts".into())
    })
}

/// Queue the continuation: the same harness session, resumed with the
/// continue prompt, through the durable command plane (the same seam the
/// composer's Resume uses — queued messages wait behind it, and the turn
/// survives an engine restart between the switch and the dispatch). The
/// budgeted transcript (ticket 01) rides as the request's fallback
/// history, delivered by the driver only when the harness refuses the
/// native resume and starts a fresh session.
fn dispatch_continuation(
    engine: &SessionsEngine,
    chat_id: &str,
    harness: HarnessId,
) -> Result<(), String> {
    let last = engine
        .last_request(chat_id)
        .ok_or_else(|| "the chat has no run configuration to continue".to_string())?;
    let resume = engine.harness_session_to_resume(chat_id, &last.cwd, harness);
    let fallback = fallback_history(engine, chat_id);
    let request = roboco_proto::RunRequest {
        env: Default::default(),
        prompt: RESUME_CONTINUE_PROMPT.into(),
        harness: Some(harness),
        model: last.model.clone(),
        reasoning: last.reasoning.clone(),
        model_options: last.model_options.clone(),
        cwd: last.cwd.clone(),
        sandbox: last.sandbox,
        auto_approve: last.auto_approve,
        // The continuation is text only: attachments and worktrees belong
        // to the turn that asked for them; the checkout is already where
        // the chat ran. Dispatch stamps its own MCP server.
        attachments: Vec::new(),
        worktree: None,
        mcp: None,
        // The stored session, resumed EXPLICITLY (bypassing dispatch's
        // re-derivation) so the fallback history pairs with it; a failover
        // never forks.
        resume,
        resume_fallback_history: fallback,
        provider_fork: None,
    };
    let host = engine
        .host()
        .ok_or_else(|| "the engine's doc host is not wired".to_string())?;
    host.queue_command(
        chat_id,
        SessionCommandPayload::Run {
            request,
            message_id: new_id(),
        },
    )
    .map(drop)
    .map_err(|err| err.to_string())
}

/// The chat's own budgeted transcript — the fallback a fresh session gets
/// when the harness refuses the native resume. The chat itself is the
/// readable source (`read_chat`); the selection rules and budget live in
/// [`crate::transcript_budget`].
fn fallback_history(engine: &SessionsEngine, chat_id: &str) -> Option<String> {
    let handle = engine.host()?.open(chat_id).ok()?;
    let entries = handle.doc().read_entries().unwrap_or_default();
    crate::transcript_budget::budgeted_transcript_prefix(&entries, Some(chat_id))
}

/// Watch the continuation turn (fire-and-forget): once it settles, a
/// signed-out failure moves to the next candidate (the run's error has
/// already marked the account — ticket 08); any other failure restores the
/// previously active account and REPORTS it (ticket 09) — the chat's
/// transcript gains a System entry saying the switch failed and the
/// previous account is active again, so the user (and the web client, which
/// renders System entries) sees it. A turn the user stopped, or a chat that
/// vanished, ends the chain and leaves the switch standing — the user's
/// stop is not a failed failover.
///
/// Driven by the session status watch, not polling (the old 100ms/250ms
/// loops could miss a fast Working→Errored flip entirely and spun forever
/// while a question waited): the watcher sleeps until a row it cares about
/// changes, wakes on the engine's own transitions, and bounds itself by
/// [`TURN_START_WINDOW`] (the start) and [`SETTLE_WINDOW`] (the settle).
async fn watch(
    engine: &SessionsEngine,
    accounts: &AgentAccounts,
    chat_id: &str,
    harness: HarnessId,
    limited_account_id: Option<&str>,
    previous: Option<String>,
    tried: HashSet<String>,
) {
    // The row the pre-switch chat settled on (Limited: Errored with a
    // written-at). A status row is only "the continuation's outcome" when
    // it is newer than this moment — the chat sat Errored before the turn
    // ever started, so the old row must not read as the turn's failure.
    let began = chrono::Utc::now();
    // Phase 1: the continuation starts. The queued command dispatches
    // within milliseconds; the bound only covers a held queue. The watch
    // wakes on every transition — including a turn that errors before it
    // ever reads Working, which the old poller could sleep straight through.
    let mut rows = engine.watch_sessions();
    let start_by = tokio::time::Instant::now() + TURN_START_WINDOW;
    let started = loop {
        if engine.shutting_down() {
            return;
        }
        let row = engine.session_status(chat_id);
        match row.map(|row| (row.status, row.updated_at)) {
            Some((roboco_proto::SessionStatus::Working | roboco_proto::SessionStatus::AwaitingInput, _)) => {
                break true;
            }
            // Errored again (or idle) on a row WRITTEN after we began: the
            // turn ran and settled within the window — watch it as settled.
            Some((roboco_proto::SessionStatus::Idle | roboco_proto::SessionStatus::Errored, at))
                if at > began =>
            {
                break false;
            }
            // No row yet (or the chat's pre-switch row: Errored with a
            // written-at before we began) — the command is still on its way.
            _ => {}
        }
        tokio::select! {
            changed = rows.changed() => {
                if changed.is_err() {
                    return;
                }
            }
            _ = tokio::time::sleep_until(start_by) => {
                tracing::info!(
                    chat = %chat_id,
                    "failover continuation never started; leaving the queued command to drain"
                );
                return;
            }
        }
    };
    // Phase 2: settle. AwaitingInput is still the user's turn in progress —
    // the watcher stays (for free) until the turn ends one way or another.
    let settled = if started {
        let settle_by = tokio::time::Instant::now() + SETTLE_WINDOW;
        let mut settled = None;
        loop {
            if engine.shutting_down() {
                return;
            }
            let status = engine.session_status(chat_id).map(|row| row.status);
            match status {
                Some(roboco_proto::SessionStatus::Idle)
                | Some(roboco_proto::SessionStatus::Errored)
                | None => {
                    settled = status;
                    break;
                }
                // Working | AwaitingInput: keep waiting on the watch.
                _ => {}
            }
            tokio::select! {
                changed = rows.changed() => {
                    if changed.is_err() {
                        return;
                    }
                }
                _ = tokio::time::sleep_until(settle_by) => {
                    tracing::info!(
                        chat = %chat_id,
                        "failover continuation never settled; leaving the switch standing"
                    );
                    return;
                }
            }
        }
        settled
    } else {
        // The turn already settled during phase 1: read its final state.
        engine.session_status(chat_id).map(|row| row.status)
    };
    match settled {
        Some(roboco_proto::SessionStatus::Errored) => {
            // Why did it fail? A signed-out error has already marked the
            // switched-to account (the run's own wiring, ticket 08): the
            // ACTIVE row reading SignedOut is that mark. The mark is
            // spawned (it queues behind the accounts ops lock), so it can
            // land a beat after the settle — re-check it for a bounded
            // grace before concluding the failure was something else.
            let mut signed_out = false;
            let deadline = tokio::time::Instant::now() + MARK_GRACE;
            loop {
                let active = accounts.active_account(harness).await;
                signed_out = active
                    .as_ref()
                    .is_some_and(|row| row.sign_in_state == AgentSignInState::SignedOut);
                if signed_out || tokio::time::Instant::now() >= deadline {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(250)).await;
            }
            if signed_out {
                tracing::info!(
                    chat = %chat_id,
                    "failover resume failed signed out; trying the next candidate"
                );
                let next = attempt(
                    engine,
                    accounts,
                    chat_id,
                    harness,
                    limited_account_id,
                    previous.clone(),
                    tried,
                    None,
                )
                .await;
                // The chain ran out of candidates (or the store broke): the
                // failover failed as a whole — put the previous account
                // back so the harness is not left on a login whose turn
                // just failed and nothing uses, and say so on the chat.
                if matches!(next, Attempt::NoCandidates | Attempt::Failed(_)) {
                    let reason = match next {
                        Attempt::NoCandidates => {
                            "every other account is limited, signed out, or in use".to_string()
                        }
                        Attempt::Failed(reason) => reason,
                        Attempt::Switched(_) => unreachable!("matched above"),
                    };
                    restore(engine, accounts, chat_id, harness, previous.as_deref()).await;
                    note_failed_failover(engine, chat_id, previous.as_deref(), &reason);
                }
            } else {
                restore(engine, accounts, chat_id, harness, previous.as_deref()).await;
                note_failed_failover(
                    engine,
                    chat_id,
                    previous.as_deref(),
                    "the resumed turn failed",
                );
            }
        }
        // Completed, interrupted (the user's stop — not a failed
        // failover), or the chat went away: the chain ends here.
        _ => {}
    }
}

/// Write the failed-failover report into the chat's transcript (ticket 09:
/// "restore the previously active account and report the failure"): a
/// System entry, rendered by every client, saying what failed and which
/// account the harness is back on. Best-effort — a failed write costs the
/// note, never the switch.
fn note_failed_failover(engine: &SessionsEngine, chat_id: &str, previous: Option<&str>, reason: &str) {
    let Some(host) = engine.host() else {
        return;
    };
    let Some(handle) = host.open(chat_id).ok() else {
        return;
    };
    let account = previous
        .map(|id| format!("account {id}"))
        .unwrap_or_else(|| "its previous login".into());
    let text = format!(
        "Resume on another account failed: {reason}. The harness is back on {account}."
    );
    let entry = SessionMessageEntry {
        id: format!("failover:{}", new_id()),
        role: MessageRole::System,
        parts: vec![MessagePart::Text {
            id: "t0".into(),
            text,
        }],
        created_at: now_ms(),
        device_id: host.device_id().to_owned(),
        status: Some(MessageStatus::Complete),
        continuation_of: None,
        duration_ms: None,
        origin_chat_id: None,
        provider_turn: None,
    };
    if let Err(err) = handle.doc().push_message(&entry) {
        tracing::warn!(chat = %chat_id, error = %err, "failed-failover note write failed");
    }
}

/// Put the previously active account back (best-effort): a failover that
/// failed left the harness on an account whose turn errored — the world
/// returns to the login the chats were running under.
async fn restore(
    engine: &SessionsEngine,
    accounts: &AgentAccounts,
    chat_id: &str,
    harness: HarnessId,
    previous: Option<&str>,
) {
    let Some(previous) = previous else {
        return;
    };
    if let Err(err) = accounts.activate(harness, previous).await {
        tracing::warn!(
            chat = %chat_id,
            account = %previous,
            error = %err,
            "could not restore the previous harness account after a failed failover"
        );
    }
}
