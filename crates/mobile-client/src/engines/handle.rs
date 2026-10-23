//! The internal API tickets 07/08/09 consume: `EngineHandle` (calls, feed
//! watches, transcript connections, the engine's cache) over the fleet
//! snapshot. Names are binding; signatures may gain lifetimes and generics.

use super::link::EngineCommand;
use super::{EngineEntry, EngineState, LiveClient};
use crate::cache::EngineCacheHandle;
use crate::{ClientError, ClientInner, lock};
use roboco_engine_client::registry::{EngineKey, ScopedId};
use roboco_engine_client::routing::wire_params;
use roboco_proto::EngineInfo;
use roboco_rpc::{RpcClient, RpcSubscription, methods};
use serde::de::DeserializeOwned;
use serde_json::Value;
use std::sync::{Arc, Weak};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot, watch};
use tokio_util::sync::CancellationToken;

/// One item of a feed watch, tagged with the link generation that
/// produced it: a generation change means the stream restarted (resets its
/// state).
#[derive(Debug, Clone)]
pub struct FeedItem {
    pub generation: u64,
    pub value: Value,
}

/// A resubscribing feed watch: the stream is re-subscribed on every
/// reconnect, on the feed (or calls) connection, until the receiver drops.
#[derive(Debug)]
pub struct FeedWatch {
    rx: mpsc::Receiver<FeedItem>,
}

impl FeedWatch {
    /// The next item, or `None` when the watch was dropped.
    pub async fn recv(&mut self) -> Option<FeedItem> {
        self.rx.recv().await
    }

    pub async fn next(&mut self) -> Option<FeedItem> {
        self.recv().await
    }
}

/// A handed-out transcript connection: one `WatchDocMessages` subscription
/// each (ticket 08), watched by the lag check. Closed when frozen or when
/// the engine's tasks stop.
pub struct TranscriptConnection {
    client: Arc<RpcClient>,
    engine_id: String,
    cancel: CancellationToken,
}

impl Drop for TranscriptConnection {
    fn drop(&mut self) {
        // Stop the lag check with the connection (the task itself may take
        // one lag tick to notice; it releases the client then).
        self.cancel.cancel();
    }
}

impl TranscriptConnection {
    /// The raw multiplexing client — ticket 08 drives its
    /// `WatchDocMessages` subscription through this.
    pub fn client(&self) -> &Arc<RpcClient> {
        &self.client
    }

    /// The engine this connection belongs to.
    pub fn engine_id(&self) -> &str {
        &self.engine_id
    }

    /// Subscribe to a transcript. `chat_id` is the scoped id; the wire
    /// params are routed (scoped id decoded to the engine's raw id,
    /// `targetDeviceId` stripped).
    pub async fn watch_doc_messages(&self, chat_id: &str) -> Result<RpcSubscription, ClientError> {
        self.watch_doc_messages_with(chat_id, serde_json::Value::Null)
            .await
    }

    /// [`Self::watch_doc_messages`] plus additive stream options, merged
    /// into the params object: `openingTail` (the newest ≤128-part preview
    /// while the full mirror loads), `digests` (ticket 12's entry digests),
    /// and `resume` (a held-copy summary that takes precedence over
    /// `openingTail`). Engines that predate them ignore them.
    pub async fn watch_doc_messages_with(
        &self,
        chat_id: &str,
        extra: Value,
    ) -> Result<RpcSubscription, ClientError> {
        let key = EngineKey(self.engine_id.clone());
        let mut params = serde_json::json!({"chatId": chat_id});
        if let (Some(object), Some(extra)) = (params.as_object_mut(), extra.as_object()) {
            for (field, value) in extra {
                object.insert(field.clone(), value.clone());
            }
        }
        let params = wire_params(
            &key,
            methods::WATCH_DOC_MESSAGES,
            params,
        )
        .map_err(rpc_error_to_client_error)?;
        self.client
            .subscribe_checked(methods::WATCH_DOC_MESSAGES, params)
            .await
            .map_err(rpc_error_to_client_error)
    }

    pub fn is_closed(&self) -> bool {
        self.client.is_closed()
    }

    /// Stop the connection's lag check. The connection itself is dropped by
    /// its owner.
    pub fn stop(&self) {
        self.cancel.cancel();
    }
}

/// A handle to one paired engine. Cheap to clone; created through
/// `Client::engine_handle` / `Client::resolve`.
#[derive(Clone)]
pub struct EngineHandle {
    weak: Weak<ClientInner>,
    key: EngineKey,
    command_tx: mpsc::UnboundedSender<EngineCommand>,
    feed_slot: watch::Receiver<Option<LiveClient>>,
    calls_slot: watch::Receiver<Option<LiveClient>>,
    engine_cancel: CancellationToken,
    cache: EngineCacheHandle,
}

impl EngineHandle {
    pub(crate) fn from_parts(weak: Weak<ClientInner>, entry: &EngineEntry) -> Self {
        Self {
            weak,
            key: EngineKey(entry.paired.engine_id.clone()),
            command_tx: entry.command_tx.clone(),
            feed_slot: entry.feed_slot.subscribe(),
            calls_slot: entry.calls_slot.subscribe(),
            engine_cancel: entry.cancel.clone(),
            cache: entry.cache.clone(),
        }
    }

    fn inner(&self) -> Result<Arc<ClientInner>, ClientError> {
        self.weak.upgrade().ok_or(ClientError::Closed)
    }

    fn entry<R>(&self, f: impl FnOnce(&EngineEntry) -> R) -> Result<R, ClientError> {
        let inner = self.inner()?;
        lock(&inner.engines)
            .entries
            .iter()
            .find(|entry| entry.paired.engine_id == self.key.0)
            .map(f)
            .ok_or_else(|| ClientError::NotFound {
                message: format!("Unknown engine: {}", self.key.0),
            })
    }

    pub fn engine_id(&self) -> &str {
        &self.key.0
    }

    /// The engine's registry key (ticket 08's transcript scoping seam).
    pub(crate) fn key(&self) -> &EngineKey {
        &self.key
    }

    /// When the live link last proved its rows current (a feed frame or a
    /// probe reply), epoch ms.
    pub(crate) fn proven_ms(&self) -> Option<i64> {
        self.entry(|entry| entry.proven_ms).ok().flatten()
    }

    /// The next scheduled redial, while reconnecting (the view's
    /// `retry_at_ms`).
    pub(crate) fn retry_at_ms(&self) -> Option<i64> {
        self.entry(|entry| entry.retry_at_ms).ok().flatten()
    }

    pub fn is_connected(&self) -> bool {
        self.entry(|entry| entry.state == EngineState::Connected)
            .unwrap_or(false)
    }

    pub fn state(&self) -> EngineState {
        self.entry(|entry| entry.state.clone())
            .unwrap_or(EngineState::Parked {
                reason: super::ParkReason::Revoked,
            })
    }

    pub fn engine_info(&self) -> Option<EngineInfo> {
        self.entry(|entry| entry.info.clone()).ok().flatten()
    }

    /// The engine's offline cache: rows, sidebar, transcripts, catalog.
    pub fn cache(&self) -> &EngineCacheHandle {
        &self.cache
    }

    /// A unary RPC on the calls connection (falling back to the feed while
    /// it redials). Scoped ids in `params` are decoded to the engine's raw
    /// ids and `targetDeviceId` is stripped; the reply deadline comes from
    /// the shared tiered table. Refused, never queued, when the engine is
    /// not connected.
    pub async fn call(&self, method: &str, params: Value) -> Result<Value, ClientError> {
        let method = method.to_owned();
        let client = self.call_client()?;
        let params = wire_params(&self.key, &method, params).map_err(rpc_error_to_client_error)?;
        let deadline = call_deadline(&method);
        let result = tokio::time::timeout(deadline, client.call(&method, params))
            .await
            .map_err(|_| ClientError::HostUnavailable {
                message: "Engine request timed out".into(),
            })
            .and_then(|result| result.map_err(rpc_error_to_client_error))?;
        Ok(result)
    }

    /// The current value of a stream, asked once: subscribe on the calls
    /// connection, take the first item (every watch sends its current value
    /// first) within `budget`, and cancel. A point-in-time read of state the
    /// engine only serves as a watch.
    pub(crate) async fn first_item(
        &self,
        method: &str,
        params: Value,
        budget: Duration,
    ) -> Result<Value, ClientError> {
        let client = self.call_client()?;
        let params = wire_params(&self.key, method, params).map_err(rpc_error_to_client_error)?;
        tokio::time::timeout(budget, async {
            let mut subscription = client
                .subscribe_checked(method, params)
                .await
                .map_err(rpc_error_to_client_error)?;
            subscription.recv().await.ok_or_else(|| ClientError::HostUnavailable {
                message: "Engine connection closed".into(),
            })
        })
        .await
        .map_err(|_| ClientError::HostUnavailable {
            message: "Engine request timed out".into(),
        })?
    }

    /// The connection a call rides: calls, else the feed. The availability
    /// check and the pick are synchronous — a disconnected engine fails
    /// within microseconds, never queues.
    fn call_client(&self) -> Result<Arc<RpcClient>, ClientError> {
        self.entry(|entry| {
            match entry.state {
                EngineState::Parked { .. } => Err(ClientError::Auth {
                    message: "Engine is parked; pair again to connect.".into(),
                }),
                EngineState::Connected => Ok(()),
                _ => Err(ClientError::HostUnavailable {
                    message: "Engine is offline; reconnecting".into(),
                }),
            }?;
            // Prefer the calls connection; fall back to the feed while the
            // calls link redials.
            let calls = entry
                .calls_slot
                .borrow()
                .as_ref()
                .map(|live| live.client.clone())
                .filter(|client| !client.is_closed());
            let feed = entry
                .feed_slot
                .borrow()
                .as_ref()
                .map(|live| live.client.clone())
                .filter(|client| !client.is_closed());
            calls.or(feed).ok_or_else(|| ClientError::HostUnavailable {
                message: "Engine is offline; reconnecting".into(),
            })
        })?
    }

    pub async fn call_as<T: DeserializeOwned>(
        &self,
        method: &str,
        params: Value,
    ) -> Result<T, ClientError> {
        let value = self.call(method, params).await?;
        serde_json::from_value(value).map_err(|error| ClientError::HostError {
            message: error.to_string(),
        })
    }

    /// Watch a stream on the feed connection, resubscribed on every
    /// reconnect. A stream the engine does not serve (`unknown method`) is
    /// unsupported for that generation only; the feed never tears down for
    /// it.
    pub fn watch_feed(&self, method: &str, params: Value) -> FeedWatch {
        self.spawn_watch(self.feed_slot.clone(), method.to_owned(), params)
    }

    /// Watch a stream on the calls connection (per-chat `WatchQueue`).
    pub fn watch_calls(&self, method: &str, params: Value) -> FeedWatch {
        self.spawn_watch(self.calls_slot.clone(), method.to_owned(), params)
    }

    fn spawn_watch(
        &self,
        slot: watch::Receiver<Option<LiveClient>>,
        method: String,
        params: Value,
    ) -> FeedWatch {
        let key = self.key.clone();
        let (tx, rx) = mpsc::channel::<FeedItem>(256);
        crate::runtime::shared().spawn(async move {
            let mut slot = slot;
            let mut subscribed_generation = None;
            loop {
                // Wait for a live connection of a generation we have not
                // already tried (an unsupported stream waits for the next
                // generation, not the same client again).
                let live = loop {
                    let current = slot.borrow_and_update().clone();
                    match current {
                        Some(live)
                            if Some(live.generation) != subscribed_generation
                                && !live.client.is_closed() =>
                        {
                            break live;
                        }
                        _ => {
                            if slot.changed().await.is_err() {
                                return; // Engine gone.
                            }
                        }
                    }
                };
                let params = match wire_params(&key, &method, params.clone()) {
                    Ok(params) => params,
                    Err(_) => return,
                };
                match live.client.subscribe_checked(&method, params).await {
                    Ok(mut subscription) => {
                        subscribed_generation = Some(live.generation);
                        loop {
                            match subscription.recv().await {
                                Some(value) => {
                                    if tx
                                        .send(FeedItem {
                                            generation: live.generation,
                                            value,
                                        })
                                        .await
                                        .is_err()
                                    {
                                        return; // Watcher dropped.
                                    }
                                }
                                None => break, // Connection died: next link.
                            }
                        }
                    }
                    Err(_) => {
                        // `unknown method` (unsupported on this engine
                        // generation) or a connection-level failure: both
                        // wait for a new generation before trying again —
                        // the feed itself is never torn down for either.
                        subscribed_generation = Some(live.generation);
                        if !wait_for_newer_generation(&mut slot, live.generation).await {
                            return;
                        }
                    }
                }
            }
        });
        FeedWatch { rx }
    }

    /// A transcript connection: the engine's spare if one is warm, else a
    /// fresh dial. The connection is watched by the lag check (a frozen
    /// connection is closed); ticket 08 resubscribes on a fresh one.
    pub async fn transcript_connection(&self) -> Result<TranscriptConnection, ClientError> {
        let state = self.state();
        if let EngineState::Parked { .. } = state {
            return Err(ClientError::Auth {
                message: "Engine is parked; pair again to connect.".into(),
            });
        }
        let command_tx = self.command_tx.clone();
        let (reply, receipt) = oneshot::channel();
        command_tx
            .send(EngineCommand::TakeSpare { reply })
            .map_err(|_| ClientError::NotFound {
                message: format!("Unknown engine: {}", self.key.0),
            })?;
        let client = receipt.await.map_err(|_| ClientError::HostUnavailable {
            message: "Engine connection is unavailable.".into(),
        })??;
        let timings = self
            .inner()
            .map(|inner| inner.timings.clone())
            .unwrap_or_default();
        // A child of the engine's token: cancelled when the engine's tasks
        // stop, or when this connection is dropped.
        let cancel = self.engine_cancel.child_token();
        let lag_cancel = cancel.clone();
        super::link::spawn_lag_check(client.clone(), lag_cancel, timings, self.feed_slot.clone());
        Ok(TranscriptConnection {
            client,
            engine_id: self.key.0.clone(),
            cancel,
        })
    }
}

/// Wait until the slot carries a connection of a later generation.
/// `false` when the engine is gone.
async fn wait_for_newer_generation(
    slot: &mut watch::Receiver<Option<LiveClient>>,
    after: u64,
) -> bool {
    loop {
        let current = slot.borrow_and_update().clone();
        if let Some(next) = current {
            if next.generation > after {
                return true;
            }
        }
        if slot.changed().await.is_err() {
            return false;
        }
    }
}

/// Map RPC wire errors to the client error surface: `unknown method` →
/// `Unsupported`; engine-side errors → `HostError`; transport →
/// `HostUnavailable`.
pub(crate) fn rpc_error_to_client_error(error: roboco_rpc::RpcError) -> ClientError {
    match error {
        roboco_rpc::RpcError::UnknownMethod(method) => ClientError::Unsupported {
            message: format!("The engine does not support this method: {method}"),
        },
        roboco_rpc::RpcError::Failed(message) | roboco_rpc::RpcError::BadParams(message) => {
            ClientError::HostError { message }
        }
        roboco_rpc::RpcError::Transport(message) => ClientError::HostUnavailable { message },
        roboco_rpc::RpcError::Closed => ClientError::HostUnavailable {
            message: "Engine connection lost".into(),
        },
    }
}

/// The mobile unary deadline table, on top of the shared desktop table
/// (`roboco_engine_client::registry::call_deadline`): the catalogs get
/// 100 s (cold-booting an adapter CLI), chunked transfers 900 s — the
/// tiers `ec:client.ts` ships and the ticket's table pins.
fn call_deadline(method: &str) -> Duration {
    if method == roboco_rpc::methods::LIST_SKILLS {
        return Duration::from_secs(100);
    }
    if matches!(
        method,
        roboco_rpc::methods::UPLOAD_CHUNK
            | roboco_rpc::methods::UPLOAD_COMMIT
            | roboco_rpc::methods::READ_ATTACHMENT_CHUNK
    ) {
        return Duration::from_secs(900);
    }
    roboco_engine_client::registry::call_deadline(method)
}

/// Scope helper shared with ticket 08's frame consumption.
#[allow(dead_code)] // consumed by ticket 08
pub(crate) fn scope_id(engine_id: &str, raw_id: &str) -> String {
    ScopedId::encode(&EngineKey(engine_id.to_owned()), raw_id)
}
