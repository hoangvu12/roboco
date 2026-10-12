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
//! label helpers (ticket 07).

mod new_session;
mod session;
mod types;
mod workspace;

pub use new_session::*;
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

#[allow(dead_code)] // consumed in Rust by tickets 07/08/09's layout work
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
        listener: Arc<dyn ClientListener>,
    ) -> CoreResult<Arc<Self>> {
        let engines: Vec<mc::PairedEngine> = engines.into_iter().map(Into::into).collect();
        let client = mc::Client::new(config.into(), engines, Arc::new(ListenerBridge(listener)))?;
        Ok(Arc::new(Self { client }))
    }

    /// Stop all tasks (app teardown). Snapshots stay readable.
    pub fn shutdown(&self) {
        self.client.shutdown();
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
        self.client
            .pair_engine(&pairing_url, replace_engine_id)
            .await
            .map(PairedEngine::from)
            .map_err(PairError::from)
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
        self.client.refresh().await;
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

    /// App returned to the foreground: probe connected feeds, dial the
    /// rest now, re-open spares.
    pub fn on_foreground(&self) {
        self.client.on_foreground();
    }

    /// App is backgrounding: flush pending cache writes, close spares.
    pub fn on_background(&self) {
        self.client.on_background();
    }
}

// ── static helpers ─────────────────────────────────────────────────────────

/// Parse a pairing link for the confirm UI. The token never crosses.
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
            },
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
        assert_eq!(
            parse_pairing_url("roboco-tailcat:x".into()).unwrap_err(),
            PairError::TailcatUnsupported
        );
        assert!(matches!(
            parse_pairing_url("ftp://h/pair#token=x".into()),
            Err(PairError::InvalidUrl { .. })
        ));
    }
}
