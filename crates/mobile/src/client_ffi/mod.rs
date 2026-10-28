//! UniFFI facade over `roboco-mobile-client`: the [`CoreClient`], the
//! foreign [`ClientListener`], and the static `parse_pairing_url` helper.
//!
//! Threading: every method is safe from any thread and never blocks on the
//! network; `async` methods run on the client's own tokio runtime and can
//! be awaited from Kotlin coroutines directly.
//!
//! Rust consumers inside this crate (the layout engine, ticket 08) reach
//! the client through [`CoreClient::client`], never over FFI.
//!
//! `workspace.rs` carries the workspace reads/mutations and the catalog
//! label helpers (ticket 07); `accounts.rs` the agent-accounts records,
//! calls and usage levels (android-next#05); `failover.rs` the Limited
//! chat's resume-on-another-account records and calls (orchestration v2
//! ticket 09's mobile half).

mod accounts;
mod failover;
mod new_session;
mod notify;
mod previews;
mod providers;
mod rooms;
mod session;
mod types;
mod workspace;

pub use accounts::*;
pub use failover::*;
pub use new_session::*;
pub use notify::*;
pub use previews::*;
pub use providers::*;
pub use rooms::*;
pub use session::*;
pub use types::*;

use std::sync::Arc;

use roboco_mobile_client as mc;

/// Receives coalesced change events (at most one burst per display frame).
/// Called on a client runtime thread: hop to the main thread and pull
/// there. Implementations must return quickly.
#[uniffi::export(with_foreign)]
pub trait ClientListener: Send + Sync {
    fn on_event(&self, event: ClientEvent);
}

struct ListenerBridge(Arc<dyn ClientListener>);

impl mc::ClientListener for ListenerBridge {
    fn on_event(&self, event: mc::ClientEvent) {
        // No Rust lock is held here: a foreign callback that re-enters
        // Rust synchronously must not deadlock.
        self.0.on_event(event.into());
    }
}

pub(crate) async fn on_runtime<T, F>(fut: F) -> CoreResult<T>
where
    F: std::future::Future<Output = Result<T, mc::ClientError>> + Send + 'static,
    T: Send + 'static,
{
    mc::runtime::run(fut).await.map_err(Into::into)
}

/// The mobile client: pairing, supervised engine connections, the offline
/// cache. Create once per app run with the platform's stored records;
/// `pair_engine` mints new ones.
#[derive(uniffi::Object)]
pub struct CoreClient {
    client: Arc<mc::Client>,
}

/// Rust-only accessors (not exported over FFI): the layout engine and the
/// integration tests reach the client through these.
impl CoreClient {
    /// The Rust client (crate-internal consumers).
    pub fn client(&self) -> &mc::Client {
        &self.client
    }

    /// The Rust session handle for `chat_id`, when one is open (the layout
    /// engine's `attach` seam). Rust-only, not exported over FFI.
    pub fn session_handle(&self, chat_id: &str) -> Option<mc::SessionHandle> {
        self.client.session(chat_id)
    }
}

#[uniffi::export]
impl CoreClient {
    /// Build and start. Never blocks on the network: the disk cache loads
    /// synchronously (the list paints immediately), then every engine dials
    /// in the background. An engine whose record carries an empty
    /// credential parks as revoked without dialing.
    #[uniffi::constructor]
    pub fn new(
        config: CoreConfig,
        engines: Vec<PairedEngine>,
        rooms: Vec<RoomRecord>,
        listener: Arc<dyn ClientListener>,
    ) -> CoreResult<Arc<Self>> {
        // Core init: the client's link/reconnect tracing reaches logcat
        // (idempotent; `core_version` usually installed it already).
        crate::logcat::install();
        let engines: Vec<mc::PairedEngine> = engines.into_iter().map(Into::into).collect();
        let rooms: Vec<mc::rooms::RoomRecord> = rooms.into_iter().map(Into::into).collect();
        let client =
            mc::Client::new(config.into(), engines, rooms, Arc::new(ListenerBridge(listener)))?;
        Ok(Arc::new(Self { client }))
    }

    /// Stop all tasks (app teardown). Snapshots stay readable.
    pub fn shutdown(&self) {
        self.client.shutdown();
    }

    // ── notifications (ticket 28) ───────────────────────────────────────

    /// Install the notification driver's delivery surface and start it.
    /// Call once after `new` (a later call replaces the sink). Callbacks
    /// arrive on a client runtime thread: hop to a coroutine, never block.
    pub fn set_notification_sink(&self, sink: Arc<dyn NotificationSink>) {
        self.client
            .set_notify_listener(Arc::new(SinkBridge(sink)));
    }

    // ── engines ────────────────────────────────────────────────────────

    /// Pair a new engine (or re-pair one). Never blocks the caller beyond
    /// the redeem + dial: returns the record the platform must persist.
    /// If the dial fails the record is still returned with
    /// `device_id: None` — the engine reconnects in the background.
    pub async fn pair_engine(
        &self,
        pairing_url: String,
        replace_engine_id: Option<String>,
    ) -> Result<PairedEngine, PairError> {
        // On the client runtime, like every other async export (`on_runtime`
        // is typed for `ClientError`): UniFFI polls this future on the
        // foreign executor, where the redeem's HTTP request, the dial and
        // the tokio timers have no reactor and panic — surfacing in Kotlin
        // as `InternalException` (the flow rig's every redeem failed so).
        let client = self.client.clone();
        match mc::runtime::shared()
            .spawn(async move { client.pair_engine(&pairing_url, replace_engine_id).await })
            .await
        {
            Ok(result) => result.map(PairedEngine::from).map_err(PairError::from),
            Err(err) => Err(PairError::Failed {
                message: format!("pairing task failed: {err}"),
            }),
        }
    }

    /// Every paired engine, in pairing order.
    pub fn engines(&self) -> Vec<EngineView> {
        self.client.engines().into_iter().map(Into::into).collect()
    }

    pub fn engine(&self, engine_id: String) -> Option<EngineView> {
        self.client.engine(&engine_id).map(Into::into)
    }

    /// Drops the links and the engine's cache directory.
    pub async fn forget_engine(&self, engine_id: String) -> CoreResult<()> {
        on_runtime({
            let client = self.client.clone();
            async move { client.forget_engine(&engine_id).await }
        })
        .await
    }

    /// Rename the engine's own device row on the engine.
    pub async fn rename_engine(&self, engine_id: String, name: String) -> CoreResult<()> {
        on_runtime({
            let client = self.client.clone();
            async move { client.rename_engine(&engine_id, &name).await }
        })
        .await
    }

    /// Revoke this device's session on the engine (best-effort; called
    /// before `forget_engine`).
    pub async fn revoke_own_session(&self, engine_id: String) -> CoreResult<()> {
        on_runtime({
            let client = self.client.clone();
            async move { client.revoke_own_session(&engine_id).await }
        })
        .await
    }

    /// Reset backoff and dial now (non-parked engines only, except
    /// `Parked { Incompatible }`, which is re-assessed).
    pub fn reconnect_engine(&self, engine_id: String) {
        self.client.reconnect_engine(&engine_id);
    }

    /// Pull-to-refresh: reconnect everything not connected, probe every
    /// connected feed; resolves within the refresh cap.
    pub async fn refresh(&self) {
        // On the client runtime: `refresh` tokio::spawns its waits, which
        // panics on UniFFI's foreign executor (see `pair_engine`).
        let client = self.client.clone();
        let _ = mc::runtime::shared()
            .spawn(async move { client.refresh().await })
            .await;
    }

    /// The aggregate connectivity posture.
    pub fn connectivity(&self) -> Connectivity {
        self.client.connectivity().into()
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    /// OS network path: `false` only for a definitive "no network".
    pub fn set_network_online(&self, online: bool) {
        self.client.set_network_online(online);
    }

    /// The default network changed (a different `Network`, or new link
    /// properties): Tailcat engines rebuild their tunnels on the new
    /// interface and redial now. Direct engines are untouched.
    pub fn set_network_changed(&self) {
        self.client.set_network_changed();
    }

    /// App returned to the foreground: probe connected feeds, dial the
    /// rest now, re-open spares.
    pub fn on_foreground(&self) {
        self.client.on_foreground();
    }

    /// App is backgrounding: flush pending cache writes, close spares.
    pub fn on_background(&self) {
        self.client.on_background();
    }

    /// The system is short of memory (`onTrimMemory`): shrink the warm
    /// sessions to a smaller byte budget (`critical`: keep only what's on
    /// screen or busy) — wave-10 43.
    pub fn trim_memory(&self, critical: bool) {
        self.client.trim_memory(critical);
    }
}

// ── static helpers ─────────────────────────────────────────────────────────

/// Parse a pairing link or a `roboco-tailcat:` invite for the confirm UI.
/// Neither the token nor the Tailcat address crosses.
#[uniffi::export]
pub fn parse_pairing_url(input: String) -> Result<PairingTarget, PairError> {
    mc::parse_pairing_url(&input)
        .map(PairingTarget::from)
        .map_err(PairError::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct Recorder(std::sync::Mutex<Vec<ClientEvent>>);

    impl ClientListener for Recorder {
        fn on_event(&self, event: ClientEvent) {
            self.0.lock().unwrap().push(event);
        }
    }

    /// A client with zero engines is `Disabled` and emits no events.
    #[test]
    fn a_client_with_zero_engines_is_disabled_and_silent() {
        let dir = tempfile::tempdir().unwrap();
        let recorder = Arc::new(Recorder::default());
        let client = CoreClient::new(
            CoreConfig {
                data_dir: dir.path().to_string_lossy().to_string(),
                device_name: "Pixel 9".into(),
                platform: "android".into(),
                app_version: "0.9.2".into(),
                show_mock_harness: true,
                native_lib_dir: None,
            },
            Vec::new(),
            Vec::new(),
            recorder.clone(),
        )
        .unwrap();
        assert!(client.engines().is_empty());
        assert_eq!(client.connectivity().state, ConnectivityState::Disabled);
        std::thread::sleep(std::time::Duration::from_millis(100));
        client.shutdown();
        // No engines → no events (not even an initial ConnectivityChanged).
        assert!(
            recorder.0.lock().unwrap().is_empty(),
            "a client with no engines must not emit events"
        );
    }

    /// The static pairing parser maps its errors across the boundary.
    #[test]
    fn parse_pairing_url_maps_errors() {
        let target = parse_pairing_url("http://h:8123/pair#token=abc".into()).unwrap();
        assert_eq!(target.base_url, "http://h:8123");
        assert_eq!(target.host, "h:8123");
        assert!(!target.tailcat);
        assert_eq!(target.expires_at_ms, None);
        // A junk invite is an invalid URL, never echoing its input.
        match parse_pairing_url("roboco-tailcat:SECRET!".into()).unwrap_err() {
            PairError::InvalidUrl { message } => assert!(!message.contains("SECRET")),
            other => panic!("unexpected {other:?}"),
        }
        // A valid, live invite crosses as a secret-free Tailcat target.
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64;
        let expires_at = now_ms + 300_000;
        let invite = roboco_proto::tailcat::TailcatInvite::new("tcSECRETaddress", "SECRETcode", expires_at)
            .unwrap()
            .encode()
            .unwrap();
        let target = parse_pairing_url(invite).unwrap();
        assert!(target.tailcat);
        assert_eq!(target.host, "Tailcat");
        assert_eq!(target.expires_at_ms, Some(expires_at));
        assert!(target.base_url.starts_with("tailcat:"));
        assert!(!format!("{target:?}").contains("SECRET"));
        // An expired one maps to TailcatExpired.
        let expired = roboco_proto::tailcat::TailcatInvite::new("tcSECRETaddress", "SECRETcode", 1)
            .unwrap()
            .encode()
            .unwrap();
        assert_eq!(parse_pairing_url(expired).unwrap_err(), PairError::TailcatExpired);
        assert!(matches!(
            parse_pairing_url("ftp://h/pair#token=x".into()),
            Err(PairError::InvalidUrl { .. })
        ));
    }
}
