//! The per-engine link supervisor: feed + calls + transcript + spare
//! connections over authenticated WebSockets, with reconnect backoff, park
//! rules, sync deadlines, health probes and lenient frame application.
//!
//! Design ported from upstream zeronsh/zeron PR #621's
//! `crates/client/src/direct/host.rs`
//! (mapped onto authenticated WebSockets instead of SSH channels):
//!
//! - **feed** — `EngineInfo` identity + health, and the five registry
//!   watches. State authority for the engine.
//! - **calls** — every unary RPC (and ticket 07/08/09's streams), opened
//!   with the feed but supervised independently: its death never tears the
//!   feed down, and it redials behind the probe cadence while the feed
//!   lives. `call` falls back to the feed connection while it is down.
//! - **transcript** (0..N) — handed out on demand by
//!   [`super::EngineHandle::transcript_connection`], one per open session;
//!   each is watched by the lag check, which closes a frozen connection.
//! - **spare** — at most one pre-authenticated idle connection per engine
//!   while the app is foreground; handed out first, then refilled. Closed
//!   on background.
//!
//! Every dial resolves its endpoint through the engine's [`Route`](super::route::Route)
//! (android-next#02): direct, or a loopback Tailcat tunnel opened on demand.

use super::route::DialTarget;
use super::{EngineEntry, EngineState, FEED_STREAMS, LiveClient, ParkReason, display_name};
use crate::cache::EngineCacheHandle;
use crate::clock::SessionClock;
use crate::compat::{self, VersionVerdict};
use crate::events::ClientEvent;
use crate::timings::{Backoff, Timings};
use crate::{ClientError, ClientInner, lock};
use roboco_engine_client::cache::CachedRows;
use roboco_proto::{Chat, Device, EngineInfo, Session, SidebarStateSnapshot, Space};
use roboco_rpc::{RpcClient, RpcError, RpcSubscription, methods};
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use std::collections::HashSet;
use std::sync::{Arc, Weak};
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, oneshot, watch};
use tokio_util::sync::CancellationToken;

pub(crate) type CommandSender = mpsc::UnboundedSender<EngineCommand>;

/// Commands to one engine's supervisor. The public surface (`DialNow`,
/// `Refresh`, …) comes from the client and `EngineHandle`;
/// `ProbeResult`, `CallsResult` and `StoreSpare` are internal completions.
///
/// The event commands (`DialNow`, `Foreground`, `Verify`) carry the instant
/// they were issued, and a supervisor ignores one issued before its current
/// dial started: that dial already answered it. Without the stamp a queued
/// `DialNow` left over from the backoff wait tore down the link it had
/// just produced.
pub(crate) enum EngineCommand {
    /// Reset backoff and dial now (`reconnect_engine`, network back).
    DialNow { at: Instant },
    /// Foreground: probe the feed immediately, refill the spare.
    Foreground { at: Instant },
    /// The OS network changed under a Tailcat route: probe a connected
    /// feed at once and rebuild the tunnel only if it fails; a link that is
    /// down rebuilds the tunnel and dials now.
    Verify { at: Instant },
    /// Background: flush pending cache writes, then close the spare.
    Background,
    /// Pull-to-refresh: probe now; ack on a fresh frame, a probe reply, a
    /// failure, or the refresh cap.
    Refresh(oneshot::Sender<()>),
    /// Hand out a transcript connection: the spare, or a fresh dial.
    TakeSpare {
        reply: oneshot::Sender<Result<Arc<RpcClient>, ClientError>>,
    },
    /// A spare refill dial finished.
    StoreSpare(Arc<RpcClient>),
    /// A health probe finished.
    ProbeResult {
        generation: u64,
        result: Result<EngineInfo, RpcError>,
        /// Bytes reached the feed while the probe waited (a reply queued
        /// behind a large frame, not a dead link).
        progressing: bool,
    },
    /// A calls-connection redial finished.
    CallsResult {
        generation: u64,
        result: Result<Arc<RpcClient>, RpcError>,
    },
    /// An auxiliary dial (spare, transcript) was refused on upgrade: the
    /// session is revoked — park, exactly like a refused feed dial.
    DialRefused,
}

impl EngineCommand {
    pub(crate) fn dial_now() -> Self {
        Self::DialNow { at: Instant::now() }
    }

    pub(crate) fn foreground() -> Self {
        Self::Foreground { at: Instant::now() }
    }

    pub(crate) fn verify() -> Self {
        Self::Verify { at: Instant::now() }
    }
}

/// Whether an event command issued `at` is answered by a dial that started
/// at `dial_started` (issued before it: stale, ignore).
fn stale(at: Instant, dial_started: Instant) -> bool {
    at < dial_started
}

/// Everything one supervisor task needs. The task owns the live
/// connections; the shared entry owns the observable state.
pub(crate) struct EngineCtx {
    pub(crate) weak: Weak<ClientInner>,
    pub(crate) engine_id: String,
    pub(crate) cancel: CancellationToken,
    pub(crate) command_rx: mpsc::UnboundedReceiver<EngineCommand>,
    pub(crate) timings: Timings,
    /// Client-wide network flag (`true` = online).
    pub(crate) network: watch::Receiver<bool>,
    /// Client-wide foreground flag.
    pub(crate) foreground: watch::Receiver<bool>,
    pub(crate) cache: EngineCacheHandle,
    /// A feed connection minted by `pair_engine`, adopted as the first link.
    pub(crate) initial_feed: Option<Arc<RpcClient>>,
    /// A spare refill dial is in flight.
    pub(crate) spare_refill: Arc<std::sync::atomic::AtomicBool>,
}

/// How the steady loop ended.
enum LinkEnd {
    /// The link died with this reason (`None` keeps the previous error).
    Drop {
        error: Option<String>,
    },
    /// The OS network went definitively offline.
    Offline,
    /// Clean redial request (`reconnect_engine`): dial now, keep the error.
    Redial,
    Parked {
        reason: ParkReason,
        message: String,
    },
    Cancelled,
}

impl std::fmt::Display for LinkEnd {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            LinkEnd::Drop { error: Some(error) } => write!(f, "dropped: {error}"),
            LinkEnd::Drop { error: None } => f.write_str("dropped: connection closed"),
            LinkEnd::Offline => f.write_str("network offline"),
            LinkEnd::Redial => f.write_str("redial requested"),
            LinkEnd::Parked { reason, .. } => write!(f, "parked: {reason:?}"),
            LinkEnd::Cancelled => f.write_str("cancelled"),
        }
    }
}

/// One frame (or end-of-stream) forwarded from a feed subscription.
enum FrameEvent {
    Frame { method: &'static str, value: Value },
    Ended { method: &'static str },
}

/// Streams that have not delivered a frame this link, excluding the ones
/// this engine generation does not serve.
fn stream_missing(seen: &HashSet<&'static str>, unsupported: &[&'static str]) -> Vec<String> {
    let mut missing = Vec::new();
    for method in FEED_STREAMS {
        if !unsupported.contains(&method) && !seen.contains(&method) {
            missing.push(method.to_owned());
        }
    }
    missing
}

fn all_streams_delivered(seen: &HashSet<&'static str>, unsupported: &[&'static str]) -> bool {
    FEED_STREAMS
        .iter()
        .all(|method| unsupported.contains(method) || seen.contains(method))
}

pub(crate) fn supervise(ctx: EngineCtx) -> tokio::task::JoinHandle<()> {
    crate::runtime::shared().spawn(async move {
        run(ctx).await;
    })
}

async fn run(mut ctx: EngineCtx) {
    let timings = ctx.timings.clone();
    let mut backoff = Backoff::new(&timings);
    // An empty credential means the platform could not decrypt its
    // Keystore record: park as revoked *before* any dial — an empty Bearer
    // header would fall through to the engine's browser first-frame auth
    // and then retry forever on its "authentication timeout".
    if entry_field(&ctx, |entry| entry.paired.credential.is_empty()).unwrap_or(true) {
        park_revoked(&ctx).await;
        return;
    }
    loop {
        if ctx.cancel.is_cancelled() || entry_missing(&ctx) {
            return;
        }
        // Offline gate: no dial attempts run while the OS says there is no
        // network; pending dials are cut short by the offline races below.
        if !*ctx.network.borrow() {
            update_state(&ctx, EngineState::Offline, None, None);
            if !wait_for_network(&mut ctx).await {
                return;
            }
            backoff.reset();
            continue;
        }
        update_state(&ctx, EngineState::Connecting, None, None);
        let started = Instant::now();
        // Dial the feed, racing the network going away and cancellation.
        // `pair_engine` may hand us a live connection it already dialed.
        let feed = match ctx.initial_feed.take() {
            Some(client) if !client.is_closed() => client,
            prior => {
                let _ = prior; // A closed pair-time client: dial fresh.
                let target = entry_pair(&ctx);
                let dial = target.connect();
                tokio::select! {
                    _ = ctx.cancel.cancelled() => return,
                    _ = wait_offline(&ctx.network) => { continue; }
                    result = dial => match result {
                        Ok(client) => Arc::new(client),
                        Err(err) => {
                            if roboco_rpc::is_auth_refused(&err) {
                                park_revoked(&ctx).await;
                                return;
                            }
                            drop_with_retry(&mut ctx, &mut backoff, err.to_string(), started).await;
                            continue;
                        }
                    },
                }
            }
        };
        // Identity: EngineInfo within the budget, checked against the pin.
        let info = match identity(&feed, &timings).await {
            Ok(info) => info,
            Err(err) => {
                if err == IDENTITY_TIMEOUT {
                    // A tunnel that connects but never answers: rebuild it.
                    invalidate_route(&ctx, "identity timed out");
                }
                feed.close();
                drop_with_retry(&mut ctx, &mut backoff, err, started).await;
                continue;
            }
        };
        if entry_field(&ctx, |entry| entry.paired.device_id.clone())
            .flatten()
            .as_deref()
            .is_some_and(|expected| info.device_id != expected)
        {
            park(
                &ctx,
                ParkReason::IdentityChanged,
                "Engine changed; pair again to connect.".into(),
            )
            .await;
            return;
        }
        let verdict = verdict_for(&ctx, &info);
        if verdict.incompatible {
            park(
                &ctx,
                ParkReason::Incompatible,
                verdict
                    .last_error
                    .clone()
                    .unwrap_or_else(|| "Engine and app versions are incompatible.".into()),
            )
            .await;
            return;
        }
        // Calls connection: opened with the feed, supervised independently.
        let calls = match dial_aux(&ctx).await {
            Ok(client) => Some(client),
            Err(err) => {
                tracing::warn!(engine = %ctx.engine_id, %err, "calls connection failed; calls share the feed for now");
                None
            }
        };
        // The feed watches. `unknown method` marks one stream unsupported
        // for this engine generation (older engines have no sidebar state);
        // it never tears the feed down.
        let (mut frames, unsupported) = match subscribe_feeds(&feed, &timings).await {
            Ok(subs) => subs,
            Err(err) => {
                feed.close();
                if let Some(calls) = &calls {
                    calls.close();
                }
                drop_with_retry(&mut ctx, &mut backoff, err, started).await;
                continue;
            }
        };
        let mut unsupported: Vec<&'static str> = unsupported;
        // Connected: bump the generation, publish the slots, pin the
        // identity if this is the first proof.
        let generation = mutate_entry(&ctx, |entry| entry.next_generation()).unwrap_or_default();
        let mut pin_event = None;
        update_entry(&ctx, |entry| {
            entry.state = EngineState::Connected;
            entry.last_error = None;
            entry.retry_at_ms = None;
            entry.last_connected_ms = Some(super::now_ms());
            entry.info = Some(info.clone());
            entry.notice = verdict.notice.clone();
            entry.diagnostics.generation = generation;
            entry.diagnostics.missing_streams = stream_missing(&HashSet::new(), &unsupported);
            if entry.paired.device_id.as_deref() != Some(info.device_id.as_str()) {
                entry.paired.device_id = Some(info.device_id.clone());
                pin_event = Some(ClientEvent::EngineIdentityPinned {
                    engine_id: entry.paired.engine_id.clone(),
                    device_id: info.device_id.clone(),
                    name: display_name(
                        entry.engine_name.as_deref(),
                        entry.paired.name.as_deref(),
                        &super::host_of(&entry.paired.base_url),
                    ),
                });
            }
            entry.feed_slot.send_replace(Some(LiveClient {
                client: feed.clone(),
                generation,
            }));
            if let Some(calls) = &calls {
                entry.calls_slot.send_replace(Some(LiveClient {
                    client: calls.clone(),
                    generation,
                }));
            }
        });
        if let Some(event) = pin_event {
            emit(&ctx, |pump| pump.pinned(event));
        }
        publish(&ctx);
        maybe_refill_spare(&ctx);
        let outcome = steady(
            &mut ctx,
            feed.clone(),
            calls.clone(),
            &mut frames,
            generation,
            &mut unsupported,
            &timings,
            started,
        )
        .await;
        tracing::info!(
            engine = %ctx.engine_id,
            generation,
            lived_ms = started.elapsed().as_millis() as u64,
            end = %outcome,
            "engine link ended"
        );
        // A pending debounced save dies with the steady loop: write it now
        // so a link drop right after a frame never loses the cache write.
        write_cache(&ctx);
        // Clear the live slots so watch tasks wait for the next link. The
        // transcript connections handed out under this link close with it
        // (their lag checks watch the feed slot).
        let held = mutate_entry(&ctx, |entry| {
            (
                entry.feed_slot.send_replace(None),
                entry.calls_slot.send_replace(None),
            )
        });
        // Close the old sockets now (with a Close frame where the socket
        // still takes one) instead of leaving them for the engine's 45 s
        // silence lease. The calls slot may hold a client the steady loop
        // redialed.
        feed.close();
        if let Some(calls) = &calls {
            calls.close();
        }
        if let Some((feed_slot, calls_slot)) = held {
            for live in feed_slot.into_iter().chain(calls_slot) {
                live.client.close();
            }
        }
        match outcome {
            LinkEnd::Cancelled => return,
            LinkEnd::Redial => {
                backoff.reset();
                continue;
            }
            LinkEnd::Offline => continue,
            LinkEnd::Parked { reason, message } => {
                park(&ctx, reason, message).await;
                return;
            }
            LinkEnd::Drop { error } => {
                backoff.maybe_reset(started.elapsed());
                let error = error.unwrap_or_else(|| "connection to the engine lost".into());
                drop_with_retry(&mut ctx, &mut backoff, error, started).await;
                continue;
            }
        }
    }
}

/// The steady state: frames flow, probes run, commands are answered.
async fn steady(
    ctx: &mut EngineCtx,
    feed: Arc<RpcClient>,
    mut calls: Option<Arc<RpcClient>>,
    frames: &mut mpsc::Receiver<FrameEvent>,
    generation: u64,
    unsupported: &mut Vec<&'static str>,
    timings: &Timings,
    dial_started: Instant,
) -> LinkEnd {
    let mut frames_seen: HashSet<&'static str> = HashSet::new();
    let mut clock = SessionClock::default();
    let mut spare: Option<Arc<RpcClient>> = None;
    let mut probe_in_flight = false;
    // A network-change verification is waiting on the probe in flight: if
    // it fails, the tunnel is rebuilt along with the link.
    let mut verifying = false;
    let mut calls_retry_at: Option<Instant> = None;
    let mut refresh_ack: Option<oneshot::Sender<()>> = None;
    let mut refresh_deadline = Box::pin(tokio::time::sleep(timings.refresh_cap));
    let mut cache_flush: Option<std::pin::Pin<Box<tokio::time::Sleep>>> = None;
    let mut feed_closed = closed_watch(&feed);
    let mut calls_closed = calls.as_ref().map(closed_watch);
    let mut probe_timer = tokio::time::interval(timings.probe_every);
    probe_timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let _ = probe_timer.tick().await; // The first tick is immediate; skip it.
    let mut sync_deadline = Box::pin(tokio::time::sleep(timings.sync_deadline));
    let mut missing_dirty = false;
    loop {
        let frame_recv = frames.recv();
        tokio::pin!(frame_recv);
        tokio::select! {
            _ = ctx.cancel.cancelled() => return LinkEnd::Cancelled,
            _ = &mut feed_closed => {
                return LinkEnd::Drop { error: None }
            }
            _ = async {
                match calls_closed.as_mut() {
                    Some(closed) => closed.await,
                    None => std::future::pending().await,
                }
            } => {
                // The calls connection died: redial it without touching the
                // feed (the #621 split — a dead calls link must never drop
                // the rows feed; a busy transcript must never block calls).
                calls = None;
                calls_closed = None;
                calls_retry_at = Some(Instant::now());
                update_entry(ctx, |entry| {
                                        entry.calls_slot.send_replace(None);
                                    });
                publish(ctx);
            }
            _ = probe_timer.tick() => {
                if calls.is_none() && calls_retry_at.map_or(true, |at| at + timings.probe_every <= Instant::now()) {
                    calls_retry_at = None;
                    spawn_calls_dial(ctx, generation);
                }
                if !probe_in_flight {
                    probe_in_flight = true;
                    spawn_probe(ctx, &feed, generation, timings.identity_timeout);
                }
            }
            command = ctx.command_rx.recv() => {
                match command {
                    None => return LinkEnd::Cancelled,
                    Some(EngineCommand::DialNow { at }) => {
                        if stale(at, dial_started) {
                            tracing::debug!(engine = %ctx.engine_id, "ignoring a redial request this link already answered");
                        } else {
                            return LinkEnd::Redial;
                        }
                    }
                    Some(EngineCommand::Foreground { at }) => {
                        // Back from the background (a thaw: the sockets may
                        // have died while the process was frozen): prove the
                        // feed now, on a short budget, instead of a full
                        // probe interval later. A link dialed after the
                        // event is fresh already.
                        if !stale(at, dial_started) {
                            probe_timer.reset();
                            if !probe_in_flight {
                                probe_in_flight = true;
                                spawn_probe(ctx, &feed, generation, timings.wake_probe_timeout);
                            }
                        }
                        if spare.is_none() {
                            maybe_refill_spare(ctx);
                        }
                    }
                    Some(EngineCommand::Verify { at }) => {
                        // A network change: keep a link that still answers;
                        // rebuild the tunnel only when it does not.
                        if !stale(at, dial_started) {
                            verifying = true;
                            probe_timer.reset();
                            if !probe_in_flight {
                                probe_in_flight = true;
                                spawn_probe(ctx, &feed, generation, timings.wake_probe_timeout);
                            }
                        }
                    }
                    Some(EngineCommand::Background) => {
                        // A pending debounced save has not reached the
                        // writer yet: write it now, then prove it is on
                        // disk before closing the spare.
                        cache_flush = None;
                        write_cache(ctx);
                        flush_cache(ctx, timings.background_flush).await;
                        if let Some(spare) = spare.take() {
                            spare.close();
                        }
                    }
                    Some(EngineCommand::Refresh(ack)) => {
                        probe_timer.reset();
                        if !probe_in_flight {
                            probe_in_flight = true;
                            spawn_probe(ctx, &feed, generation, timings.identity_timeout);
                        }
                        refresh_ack = Some(ack);
                        refresh_deadline
                            .as_mut()
                            .reset(tokio::time::Instant::now() + timings.refresh_cap);
                    }
                    Some(EngineCommand::TakeSpare { reply }) => {
                        match spare.take() {
                            Some(client) => {
                                let _ = reply.send(Ok(client));
                            }
                            None => {
                                let target = entry_pair(ctx);
                                spawn_aux_dial(command_tx_of(ctx), target, reply);
                            }
                        }
                        maybe_refill_spare(ctx);
                    }
                    Some(EngineCommand::StoreSpare(client)) => {
                        if spare.is_none() && *ctx.foreground.borrow() && !ctx.cancel.is_cancelled() {
                            spare = Some(client);
                        } else {
                            client.close();
                        }
                    }
                    Some(EngineCommand::ProbeResult { generation: probe_generation, result, progressing }) => {
                        probe_in_flight = false;
                        if probe_generation == generation {
                            let verified = std::mem::take(&mut verifying);
                            match result {
                                Ok(info) => {
                                    if entry_field(ctx, |entry| entry.paired.device_id.clone())
                                        .flatten()
                                        .as_deref()
                                        .is_some_and(|expected| info.device_id != expected)
                                    {
                                        return LinkEnd::Parked {
                                            reason: ParkReason::IdentityChanged,
                                            message: "Engine changed; pair again to connect.".into(),
                                        };
                                    }
                                    let verdict = verdict_for(ctx, &info);
                                    if verdict.incompatible {
                                        return LinkEnd::Parked {
                                            reason: ParkReason::Incompatible,
                                            message: verdict.last_error.unwrap_or_else(|| {
                                                "Engine and app versions are incompatible.".into()
                                            }),
                                        };
                                    }
                                    update_entry(ctx, |entry| {
                                        entry.info = Some(info.clone());
                                        entry.notice = verdict.notice.clone();
                                    });
                                    publish(ctx);
                                    ack_refresh(&mut refresh_ack);
                                }
                                Err(err) => match err {
                                    RpcError::Transport(_) if progressing => {
                                        // Bytes kept arriving: the reply is
                                        // queued behind a large frame (a
                                        // transcript reset over a relay),
                                        // not lost. Keep the link.
                                        tracing::info!(
                                            engine = %ctx.engine_id,
                                            "health probe slow but the feed is receiving; keeping the link"
                                        );
                                    }
                                    RpcError::Transport(_) | RpcError::Closed => {
                                        tracing::info!(engine = %ctx.engine_id, error = %err, verified, "health probe failed");
                                        let timed_out = matches!(&err, RpcError::Transport(message) if message == PROBE_TIMEOUT);
                                        if verified || timed_out {
                                            // A network change the link did
                                            // not survive, or a tunnel that
                                            // takes requests but never
                                            // answers: rebuild the tunnel.
                                            invalidate_route(ctx, "health probe failed");
                                        }
                                        return LinkEnd::Drop {
                                            error: match err {
                                                RpcError::Closed => None,
                                                other => Some(other.to_string()),
                                            },
                                        };
                                    }
                                    // The engine answered with an error: the
                                    // connection is alive, the call failed.
                                    // Never drop the link for it.
                                    _ => tracing::warn!(
                                        engine = %ctx.engine_id,
                                        error = %err,
                                        "engine health probe failed"
                                    ),
                                },
                            }
                        }
                    }
                    Some(EngineCommand::DialRefused) => {
                        return revoked_end();
                    }
                    Some(EngineCommand::CallsResult { generation: calls_generation, result }) => {
                        if calls_generation == generation {
                            match result {
                                Ok(client) => {
                                    calls = Some(client.clone());
                                    calls_closed = Some(closed_watch(&client));
                                    calls_retry_at = None;
                                    update_entry(ctx, |entry| {
                                        entry.calls_slot.send_replace(Some(LiveClient {
                                            client: client.clone(),
                                            generation,
                                        }));
                                    });
                                    publish(ctx);
                                }
                                Err(err) => {
                                    if roboco_rpc::is_auth_refused(&err) {
                                        // The session was revoked: park like a
                                        // refused feed dial (HTTP 401 on the
                                        // upgrade), never retry.
                                        return revoked_end();
                                    }
                                    tracing::warn!(engine = %ctx.engine_id, %err, "calls redial failed");
                                    calls_retry_at = Some(Instant::now());
                                }
                            }
                        }
                    }
                }
            }
            event = &mut frame_recv => {
                let Some(event) = event else {
                    return LinkEnd::Drop { error: None };
                };
                match event {
                    FrameEvent::Ended { method } => {
                        // The stream ended while the feed lives: treat it as
                        // unsupported for this generation (an older engine,
                        // or a server-side stream error) — never fatal.
                        if !unsupported.contains(&method) {
                            unsupported.push(method);
                        }
                        missing_dirty = true;
                    }
                    FrameEvent::Frame { method, value } => {
                        let first = !frames_seen.contains(method);
                        frames_seen.insert(method);
                        apply_frame(ctx, &mut clock, method, value);
                        if first {
                            missing_dirty = true;
                        }
                        ack_refresh(&mut refresh_ack);
                        if cache_flush.is_none() {
                            cache_flush = Some(Box::pin(tokio::time::sleep(timings.cache_debounce)));
                        }
                    }
                }
                if missing_dirty {
                    missing_dirty = false;
                    let missing = stream_missing(&frames_seen, unsupported);
                    update_entry(ctx, |entry| {
                        entry.diagnostics.missing_streams = missing.clone();
                    });
                    publish(ctx);
                }
            }
            _ = &mut sync_deadline, if !all_streams_delivered(&frames_seen, unsupported) => {
                let missing = stream_missing(&frames_seen, unsupported);
                if !missing.is_empty() {
                    return LinkEnd::Drop {
                        error: Some(format!(
                            "connected to the engine, but it sent no {} within {} s",
                            missing.join(" / "),
                            timings.sync_deadline.as_secs()
                        )),
                    };
                }
            }
            _ = async {
                match cache_flush.as_mut() {
                    Some(flush) => flush.await,
                    None => std::future::pending().await,
                }
            } => {
                cache_flush = None;
                write_cache(ctx);
            }
            _ = async {
                match refresh_ack.as_ref() {
                    Some(_) => refresh_deadline.as_mut().await,
                    None => std::future::pending().await,
                }
            } => {
                ack_refresh(&mut refresh_ack);
            }
            _ = wait_offline(&ctx.network) => {
                return LinkEnd::Offline;
            }
        }
    }
}

/// An owned close-watcher: resolves when the connection's pump ends.
fn closed_watch(
    client: &Arc<RpcClient>,
) -> std::pin::Pin<Box<dyn std::future::Future<Output = ()> + Send>> {
    let client = client.clone();
    Box::pin(async move {
        client.closed().await;
    })
}

fn ack_refresh(refresh_ack: &mut Option<oneshot::Sender<()>>) {
    if let Some(ack) = refresh_ack.take() {
        let _ = ack.send(());
    }
}

/// Apply one feed frame leniently. A decode error never tears the link
/// down; undecodable rows keep their previous copy, so a newer engine can
/// never blank the list.
fn apply_frame(ctx: &EngineCtx, clock: &mut SessionClock, method: &'static str, value: Value) {
    let mut pinned_event = None;
    match method {
        methods::WATCH_CHATS => {
            let previous = entry_field(ctx, |entry| entry.rows.chats.clone());
            let decoded = crate::lenient::decode_rows::<Chat>(
                value,
                &crate::lenient::chat_substitutions(),
                "id",
            );
            apply_rows(
                ctx,
                decoded,
                previous,
                |rows| &mut rows.chats,
                |row| row.id.as_str(),
            );
        }
        methods::WATCH_SPACES => {
            let previous = entry_field(ctx, |entry| entry.rows.spaces.clone());
            let decoded = crate::lenient::decode_rows::<Space>(value, &[], "id");
            apply_rows(
                ctx,
                decoded,
                previous,
                |rows| &mut rows.spaces,
                |row| row.id.as_str(),
            );
        }
        methods::WATCH_DEVICES => {
            let previous = entry_field(ctx, |entry| entry.rows.devices.clone());
            let decoded = crate::lenient::decode_rows::<Device>(value, &[], "id");
            apply_rows(
                ctx,
                decoded,
                previous,
                |rows| &mut rows.devices,
                |row| row.id.as_str(),
            );
            // The engine's own row: display name, platform, version.
            if let Some(device_id) =
                entry_field(ctx, |entry| entry.paired.device_id.clone()).flatten()
            {
                let engine_device = entry_field(ctx, |entry| {
                    entry
                        .rows
                        .devices
                        .iter()
                        .find(|device| device.id == device_id)
                        .cloned()
                })
                .flatten();
                if let Some(device) = engine_device {
                    update_entry(ctx, |entry| {
                        let name = device.name.trim();
                        let name = if name.is_empty() || name == "unknown-device" {
                            None
                        } else {
                            Some(name.to_owned())
                        };
                        if name != entry.engine_name {
                            entry.engine_name = name.clone();
                            entry.paired.name = name.clone();
                            if entry.paired.device_id.is_some() {
                                pinned_event = Some(ClientEvent::EngineIdentityPinned {
                                    engine_id: entry.paired.engine_id.clone(),
                                    device_id: device.id.clone(),
                                    name: display_name(
                                        name.as_deref(),
                                        entry.paired.name.as_deref(),
                                        &super::host_of(&entry.paired.base_url),
                                    ),
                                });
                            }
                        }
                    });
                }
            }
        }
        methods::WATCH_SESSIONS => {
            let previous = entry_field(ctx, |entry| entry.rows.sessions.clone());
            let decoded = crate::lenient::decode_rows::<Session>(
                value,
                &crate::lenient::session_substitutions(),
                "chatId",
            );
            match decoded {
                Ok(decoded) => {
                    // The clock rebases freshly decoded rows on the phone's
                    // clock; kept (undecodable) rows keep the phone stamp
                    // they already carry.
                    let stats = decode_stats(&decoded);
                    let rebased = clock.rebase(decoded.rows, super::now());
                    let merged = merge_rows(previous.as_deref(), rebased, &stats.ids, |row| {
                        row.chat_id.as_str()
                    });
                    update_entry(ctx, |entry| {
                        entry.rows.sessions = merged.0;
                        record_decode(entry, &stats, merged.1);
                        entry.from_cache = false;
                    });
                }
                Err(error) => count_frame_error(ctx, error),
            }
        }
        methods::WATCH_SIDEBAR_STATE => {
            match crate::lenient::decode_row::<SidebarStateSnapshot>(&value, &[]) {
                Ok((sidebar, _note)) => {
                    update_entry(ctx, |entry| {
                        entry.sidebar = Some(sidebar);
                        entry.from_cache = false;
                    });
                }
                Err(error) => {
                    // Keep the previous value; count the error.
                    update_entry(ctx, |entry| {
                        entry.diagnostics.skipped_rows =
                            entry.diagnostics.skipped_rows.saturating_add(1);
                        entry.diagnostics.last_decode_error = Some(error);
                    });
                }
            }
        }
        other => tracing::warn!(engine = %ctx.engine_id, method = other, "unexpected feed stream"),
    }
    if let Some(event) = pinned_event {
        emit(ctx, |pump| pump.pinned(event));
    }
    publish(ctx);
}

fn apply_rows<T>(
    ctx: &EngineCtx,
    decoded: Result<crate::lenient::DecodedRows<T>, String>,
    previous: Option<Vec<T>>,
    set: impl FnOnce(&mut CachedRows) -> &mut Vec<T>,
    id_of: fn(&T) -> &str,
) where
    T: DeserializeOwned + Clone,
{
    match decoded {
        Ok(decoded) => {
            let stats = decode_stats(&decoded);
            let merged = merge_rows(previous.as_deref(), decoded.rows, &stats.ids, id_of);
            update_entry(ctx, |entry| {
                *set(&mut entry.rows) = merged.0;
                record_decode(entry, &stats, merged.1);
                entry.from_cache = false;
            });
        }
        Err(error) => count_frame_error(ctx, error),
    }
}

/// The pieces of a decoded frame the diagnostics fold needs, split out
/// before the rows move into the merge.
struct DecodeStats {
    ids: HashSet<String>,
    repaired: usize,
    errors: Vec<String>,
}

fn decode_stats<T>(decoded: &crate::lenient::DecodedRows<T>) -> DecodeStats {
    DecodeStats {
        ids: decoded.ids.iter().cloned().collect(),
        repaired: decoded.repaired.len(),
        errors: decoded.errors.clone(),
    }
}

/// Fold one decoded frame into the engine's lenient-decoding counters
/// (link-lifetime totals: `repaired` rows kept after a repair, `kept`
/// previous copies of undecodable rows, and the skipped rows).
fn record_decode(entry: &mut EngineEntry, stats: &DecodeStats, kept: u32) {
    entry.diagnostics.repaired_rows = entry
        .diagnostics
        .repaired_rows
        .saturating_add(stats.repaired as u32 + kept);
    entry.diagnostics.skipped_rows = entry
        .diagnostics
        .skipped_rows
        .saturating_add(stats.errors.len() as u32);
    entry.diagnostics.last_decode_error = stats.errors.first().cloned();
}

fn count_frame_error(ctx: &EngineCtx, error: String) {
    // The whole frame was unreadable: keep every previous row (a newer
    // engine must never blank the list) and count it.
    update_entry(ctx, |entry| {
        entry.diagnostics.skipped_rows = entry.diagnostics.skipped_rows.saturating_add(1);
        entry.diagnostics.last_decode_error = Some(error);
    });
}

/// Merge decoded rows with the previous list: rows present in the frame but
/// undecodable keep their previous copy; rows absent from the frame are
/// deleted. Returns the new list and how many previous copies were kept.
fn merge_rows<T: Clone>(
    previous: Option<&[T]>,
    rows: Vec<T>,
    frame_ids: &HashSet<String>,
    id_of: fn(&T) -> &str,
) -> (Vec<T>, u32) {
    let mut out = rows;
    let decoded_ids: HashSet<String> = out.iter().map(|row| id_of(row).to_owned()).collect();
    let mut kept = 0;
    if let Some(previous) = previous {
        for row in previous {
            let id = id_of(row);
            if frame_ids.contains(id) && !decoded_ids.contains(id) {
                out.push(row.clone());
                kept += 1;
            }
        }
    }
    (out, kept)
}

fn write_cache(ctx: &EngineCtx) {
    let Some((rows, sidebar)) =
        entry_field(ctx, |entry| (entry.rows.clone(), entry.sidebar.clone()))
    else {
        return;
    };
    ctx.cache.save_rows(rows);
    if let Some(sidebar) = sidebar {
        ctx.cache.save_sidebar(sidebar);
    }
}

async fn flush_cache(ctx: &EngineCtx, bound: Duration) {
    let _ = tokio::time::timeout(bound, ctx.cache.flush()).await;
}

/// Park an engine: stop dialing, record why, and wait for the task to be
/// restarted (forget, re-pair, `reconnect_engine` for incompatible).
/// The one copy every revoked-session park shows (a refused dial or an
/// undecryptable credential).
const REVOKED_MESSAGE: &str = "Session revoked; pair again to connect.";

/// Park as [ParkReason::Revoked] with [REVOKED_MESSAGE].
async fn park_revoked(ctx: &EngineCtx) {
    park(ctx, ParkReason::Revoked, REVOKED_MESSAGE.into()).await;
}

/// The link's end for a revoked session (the calls/feed redial refused).
fn revoked_end() -> LinkEnd {
    LinkEnd::Parked {
        reason: ParkReason::Revoked,
        message: REVOKED_MESSAGE.into(),
    }
}

async fn park(ctx: &EngineCtx, reason: ParkReason, message: String) {
    update_entry(ctx, |entry| {
        entry.state = EngineState::Parked { reason };
        entry.last_error = Some(message);
        entry.retry_at_ms = None;
        entry.feed_slot.send_replace(None);
        entry.calls_slot.send_replace(None);
    });
    publish(ctx);
    ctx.cancel.cancelled().await;
}

/// Record a drop, schedule the retry, and wait out the backoff
/// (interruptibly: `reconnect_engine`, foreground, a network change,
/// network loss, or cancellation all cut it short). An event command
/// issued before the failed dial started was answered by that dial and
/// does not cut the wait.
async fn drop_with_retry(
    ctx: &mut EngineCtx,
    backoff: &mut Backoff,
    error: String,
    dial_started: Instant,
) {
    tracing::info!(engine = %ctx.engine_id, %error, "engine link down; retrying");
    update_entry(ctx, |entry| {
        if !matches!(
            entry.state,
            EngineState::Parked { .. } | EngineState::Offline
        ) {
            entry.state = EngineState::Reconnecting;
        }
        entry.last_error = Some(error);
    });
    let wait = backoff.next_wait();
    update_entry(ctx, |entry| {
        entry.retry_at_ms = Some(super::now_ms() + wait.as_millis() as i64);
    });
    publish(ctx);
    let sleep = tokio::time::sleep(wait);
    tokio::pin!(sleep);
    loop {
        tokio::select! {
            _ = ctx.cancel.cancelled() => return,
            _ = wait_offline(&ctx.network) => return,
            _ = &mut sleep => return,
            command = ctx.command_rx.recv() => {
                match command {
                    Some(EngineCommand::DialNow { at } | EngineCommand::Foreground { at })
                        if !stale(at, dial_started) =>
                    {
                        return;
                    }
                    Some(EngineCommand::Verify { at }) if !stale(at, dial_started) => {
                        // The link is down and the network moved: the next
                        // dial binds the new interface.
                        invalidate_route(ctx, "network changed while reconnecting");
                        return;
                    }
                    Some(EngineCommand::Background) => {
                        write_cache(ctx);
                        flush_cache(ctx, ctx.timings.background_flush).await
                    }
                    Some(EngineCommand::Refresh(ack)) => { let _ = ack.send(()); }
                    Some(EngineCommand::TakeSpare { reply }) => {
                        let target = entry_pair(ctx);
                        spawn_aux_dial(command_tx_of(ctx), target, reply);
                    }
                    None => return,
                    _ => {}
                }
            }
        }
    }
}

/// Wait until the network comes back (or the engine is cancelled). While
/// offline, `DialNow` is ignored: no dial attempts run.
async fn wait_for_network(ctx: &mut EngineCtx) -> bool {
    let mut network = ctx.network.clone();
    loop {
        if *network.borrow_and_update() {
            return true;
        }
        tokio::select! {
            _ = ctx.cancel.cancelled() => return false,
            changed = network.changed() => {
                if changed.is_err() {
                    return false;
                }
            }
            command = ctx.command_rx.recv() => {
                match command {
                    None => return false,
                    Some(EngineCommand::Refresh(ack)) => { let _ = ack.send(()); }
                    Some(EngineCommand::TakeSpare { reply }) => {
                        let _ = reply.send(Err(ClientError::HostUnavailable {
                            message: "No network connection.".into(),
                        }));
                    }
                    Some(EngineCommand::Background) => {
                        write_cache(ctx);
                        flush_cache(ctx, ctx.timings.background_flush).await
                    }
                    _ => {}
                }
            }
        }
    }
}

async fn wait_offline(network: &watch::Receiver<bool>) {
    let mut network = network.clone();
    loop {
        if !*network.borrow_and_update() {
            return;
        }
        if network.changed().await.is_err() {
            return;
        }
    }
}

/// The identity error a silent engine (or a dead tunnel) produces.
const IDENTITY_TIMEOUT: &str = "Engine identity timed out";

async fn identity(feed: &Arc<RpcClient>, timings: &Timings) -> Result<EngineInfo, String> {
    match tokio::time::timeout(
        timings.identity_timeout,
        feed.call_as(methods::ENGINE_INFO, json!({})),
    )
    .await
    {
        Ok(Ok(info)) => Ok(info),
        Ok(Err(err)) => Err(err.to_string()),
        Err(_) => Err(IDENTITY_TIMEOUT.into()),
    }
}

fn verdict_for(ctx: &EngineCtx, info: &EngineInfo) -> VersionVerdict {
    let Some(inner) = ctx.weak.upgrade() else {
        return VersionVerdict::default();
    };
    let (paired_name, engine_name, base_url, device_version) = lock(&inner.engines)
        .entries
        .iter()
        .find(|entry| entry.paired.engine_id == ctx.engine_id)
        .map(|entry| {
            (
                entry.paired.name.clone(),
                entry.engine_name.clone(),
                entry.paired.base_url.clone(),
                // Ticket 11's fallback: the engine's own Device row version.
                entry
                    .rows
                    .devices
                    .iter()
                    .find(|device| device.id == info.device_id)
                    .and_then(|device| device.version.clone()),
            )
        })
        .unwrap_or_default();
    let name = display_name(
        engine_name.as_deref(),
        paired_name.as_deref(),
        &super::host_of(&base_url),
    );
    compat::assess_engine(
        info,
        &inner.config.app_version,
        &name,
        device_version.as_deref(),
    )
}

/// Dial an auxiliary connection (calls, spares).
async fn dial_aux(ctx: &EngineCtx) -> Result<Arc<RpcClient>, RpcError> {
    entry_pair(ctx).connect().await.map(Arc::new)
}

/// Subscribe the constant feed watch set. Acks within `subscribe_timeout`
/// total. `unknown method` marks that stream unsupported for this engine
/// generation; every other failure drops the link.
async fn subscribe_feeds(
    feed: &Arc<RpcClient>,
    timings: &Timings,
) -> Result<(mpsc::Receiver<FrameEvent>, Vec<&'static str>), String> {
    let (tx, rx) = mpsc::channel::<FrameEvent>(256);
    let mut unsupported = Vec::new();
    let budget = async {
        for method in FEED_STREAMS {
            match feed.subscribe_checked(method, json!({})).await {
                Ok(subscription) => forward_stream(subscription, method, tx.clone()),
                Err(RpcError::UnknownMethod(_)) => {
                    tracing::info!(method, "engine does not serve this stream; skipping");
                    unsupported.push(method);
                }
                Err(err) => return Err(err.to_string()),
            }
        }
        Ok::<(), String>(())
    };
    match tokio::time::timeout(timings.subscribe_timeout, budget).await {
        Ok(Ok(())) => Ok((rx, unsupported)),
        Ok(Err(err)) => Err(err),
        Err(_) => Err("engine subscriptions timed out".into()),
    }
}

fn forward_stream(
    mut subscription: RpcSubscription,
    method: &'static str,
    tx: mpsc::Sender<FrameEvent>,
) {
    crate::runtime::shared().spawn(async move {
        while let Some(value) = subscription.recv().await {
            if tx.send(FrameEvent::Frame { method, value }).await.is_err() {
                return;
            }
        }
        let _ = tx.send(FrameEvent::Ended { method });
    });
}

/// The transport error a health probe that got no reply in time reports.
const PROBE_TIMEOUT: &str = "Engine health check timed out";

/// `EngineInfo` on the feed within `budget`. A timeout reports whether
/// bytes reached the feed meanwhile (`progressing`): a slow reply behind a
/// big frame, not a dead link.
fn spawn_probe(ctx: &EngineCtx, feed: &Arc<RpcClient>, generation: u64, budget: Duration) {
    let command_tx = command_tx_of(ctx);
    let feed = feed.clone();
    crate::runtime::shared().spawn(async move {
        let started = tokio::time::Instant::now();
        let (result, progressing) =
            match tokio::time::timeout(budget, feed.call_as(methods::ENGINE_INFO, json!({}))).await
            {
                Ok(result) => (result, false),
                Err(_) => (
                    Err(RpcError::Transport(PROBE_TIMEOUT.into())),
                    feed.received_since(started),
                ),
            };
        let _ = command_tx.send(EngineCommand::ProbeResult {
            generation,
            result,
            progressing,
        });
    });
}

/// Drop the engine's Tailcat tunnel (a no-op for direct routes) so the next
/// dial opens a fresh one.
fn invalidate_route(ctx: &EngineCtx, why: &str) {
    if let Some(route) = entry_field(ctx, |entry| entry.route.clone()) {
        if route.is_tailcat() {
            tracing::info!(engine = %ctx.engine_id, why, "rebuilding the Tailcat tunnel");
        }
        route.invalidate();
    }
}

fn spawn_calls_dial(ctx: &EngineCtx, generation: u64) {
    let command_tx = command_tx_of(ctx);
    let target = entry_pair(ctx);
    crate::runtime::shared().spawn(async move {
        let result = target.connect().await.map(Arc::new);
        let _ = command_tx.send(EngineCommand::CallsResult { generation, result });
    });
}

/// Refill the spare in the background (at most one refill dial in flight
/// per engine, only while the app is foreground): each trigger used to
/// start its own dial, and every surplus connection was dialed only to be
/// dropped.
fn maybe_refill_spare(ctx: &EngineCtx) {
    if !*ctx.foreground.borrow() || ctx.cancel.is_cancelled() {
        return;
    }
    if ctx.spare_refill.swap(true, std::sync::atomic::Ordering::AcqRel) {
        return;
    }
    let refilling = ctx.spare_refill.clone();
    let command_tx = command_tx_of(ctx);
    let target = entry_pair(ctx);
    crate::runtime::shared().spawn(async move {
        let dialed = target.connect().await;
        refilling.store(false, std::sync::atomic::Ordering::Release);
        match dialed {
            Ok(client) => {
                let _ = command_tx.send(EngineCommand::StoreSpare(Arc::new(client)));
            }
            Err(err) if roboco_rpc::is_auth_refused(&err) => {
                let _ = command_tx.send(EngineCommand::DialRefused);
            }
            Err(_) => {}
        }
    });
}

fn command_tx_of(ctx: &EngineCtx) -> CommandSender {
    // A shutdown (or `forget_engine`) racing an in-flight aux dial leaves
    // no engine entry to route the reply to; a closed channel turns that
    // into the no-op it already is on the receive side — panicking on a
    // runtime thread instead would take the process down.
    weak_field(ctx, |inner| {
        lock(&inner.engines)
            .entries
            .iter()
            .find(|entry| entry.paired.engine_id == ctx.engine_id)
            .map(|entry| entry.command_tx.clone())
    })
    .flatten()
    .unwrap_or_else(|| mpsc::unbounded_channel().0)
}

/// The engine's route and credential, read fresh for every dial (a re-pair
/// swaps both).
fn entry_pair(ctx: &EngineCtx) -> DialTarget {
    entry_field(ctx, |entry| DialTarget {
        route: entry.route.clone(),
        credential: entry.paired.credential.clone(),
    })
    .unwrap_or_default()
}

/// Dial a transcript/calls/spare connection and reply through `reply`.
/// A refused upgrade parks the engine (the session is revoked) instead of
/// retrying forever.
fn spawn_aux_dial(
    command_tx: CommandSender,
    target: DialTarget,
    reply: oneshot::Sender<Result<Arc<RpcClient>, ClientError>>,
) {
    crate::runtime::shared().spawn(async move {
        let result = match target.connect().await {
            Ok(client) => Ok(Arc::new(client)),
            Err(err) => {
                if roboco_rpc::is_auth_refused(&err) {
                    let _ = command_tx.send(EngineCommand::DialRefused);
                }
                Err(ClientError::HostUnavailable {
                    message: err.to_string(),
                })
            }
        };
        let _ = reply.send(result);
    });
}

fn entry_missing(ctx: &EngineCtx) -> bool {
    weak_field(ctx, |inner| {
        !lock(&inner.engines)
            .entries
            .iter()
            .any(|entry| entry.paired.engine_id == ctx.engine_id)
    })
    .unwrap_or(true)
}

fn entry_field<T: Clone>(ctx: &EngineCtx, f: impl FnOnce(&EngineEntry) -> T) -> Option<T> {
    weak_field(ctx, |inner| {
        lock(&inner.engines)
            .entries
            .iter()
            .find(|entry| entry.paired.engine_id == ctx.engine_id)
            .map(f)
    })
    .flatten()
}

fn weak_field<T>(ctx: &EngineCtx, f: impl FnOnce(&Arc<ClientInner>) -> T) -> Option<T> {
    ctx.weak.upgrade().map(|inner| f(&inner))
}

fn mutate_entry<R>(ctx: &EngineCtx, f: impl FnOnce(&mut EngineEntry) -> R) -> Option<R> {
    let Some(inner) = ctx.weak.upgrade() else {
        return None;
    };
    crate::mutate_engine(&inner, &ctx.engine_id, f)
}

fn update_entry(ctx: &EngineCtx, f: impl FnOnce(&mut EngineEntry)) -> bool {
    let Some(inner) = ctx.weak.upgrade() else {
        return false;
    };
    crate::update_engine(&inner, &ctx.engine_id, f)
}

fn update_state(ctx: &EngineCtx, state: EngineState, error: Option<String>, retry_at: Option<i64>) {
    update_entry(ctx, |entry| {
        entry.state = state;
        if error.is_some() {
            entry.last_error = error;
        }
        entry.retry_at_ms = retry_at;
    });
    publish(ctx);
}

fn publish(ctx: &EngineCtx) {
    if let Some(inner) = ctx.weak.upgrade() {
        crate::publish(&inner);
    }
}

fn emit(ctx: &EngineCtx, f: impl FnOnce(&crate::events::EventPump)) {
    if let Some(inner) = ctx.weak.upgrade() {
        f(&inner.pump);
    }
}

/// The lag check for a handed-out transcript connection: an `EngineInfo`
/// on the connection itself — its reply comes back behind every frame the
/// engine queued before it, so a missing reply with no bytes arriving
/// proves the connection is frozen. Close it; ticket 08 resubscribes on a
/// fresh one.
///
/// The connection also closes when the link it was handed out under ends
/// (the feed slot leaves that generation): a link that died with the
/// network (or a frozen process) took this socket with it, and the session
/// must resubscribe on the next link instead of waiting out the lag check.
pub(crate) fn spawn_lag_check(
    client: Arc<RpcClient>,
    engine_cancel: CancellationToken,
    timings: Timings,
    mut feed_slot: watch::Receiver<Option<super::LiveClient>>,
) {
    let link = feed_slot.borrow_and_update().as_ref().map(|live| live.generation);
    crate::runtime::shared().spawn(async move {
        let link_ended = async {
            let Some(link) = link else {
                // Handed out between links: only the lag check applies.
                return std::future::pending::<()>().await;
            };
            loop {
                if feed_slot.changed().await.is_err() {
                    return;
                }
                let current = feed_slot.borrow_and_update().as_ref().map(|live| live.generation);
                if current != Some(link) {
                    return;
                }
            }
        };
        tokio::pin!(link_ended);
        loop {
            tokio::select! {
                _ = engine_cancel.cancelled() => return,
                _ = &mut link_ended => {
                    tracing::debug!("engine link ended; closing its transcript connection");
                    client.close();
                    return;
                }
                _ = tokio::time::sleep(timings.lag_every) => {}
            }
            let asked = tokio::time::Instant::now();
            let reply = tokio::select! {
                _ = engine_cancel.cancelled() => return,
                _ = &mut link_ended => {
                    tracing::debug!("engine link ended; closing its transcript connection");
                    client.close();
                    return;
                }
                reply = tokio::time::timeout(
                    timings.lag_timeout,
                    client.call(methods::ENGINE_INFO, json!({})),
                ) => reply,
            };
            // Any answer, even an error, came through in order; a dead
            // connection ends its streams on its own. A late reply whose
            // bytes are still arriving (a big reset over a relay) is slow,
            // not frozen.
            if reply.is_err() {
                if client.received_since(asked) {
                    tracing::info!("transcript connection slow but receiving; keeping it");
                    continue;
                }
                tracing::warn!("transcript connection frozen; closing it");
                client.close();
                return;
            }
        }
    });
}
