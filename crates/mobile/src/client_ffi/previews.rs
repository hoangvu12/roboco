//! The preview FFI (agent-previews ticket 05): the [`PreviewPanel`]
//! object (one `WatchPreviews {chatId}` subscription, the
//! `state/preview.ts` shape), the foreign [`PreviewPanelListener`] state
//! callback, and the client proxy's controls (`start/stop/status` of the
//! 7332 listener).
//!
//! `chat_id` is the **engine-scoped** id the app holds (the same id the
//! Sessions list uses); the core decodes it. Callbacks run on a client
//! runtime thread — Kotlin must hop to a coroutine, never block.

use std::sync::Arc;

use roboco_mobile_client as mc;

use super::{CoreClient, CoreResult};

// ── records ───────────────────────────────────────────────────────────────

/// One preview row: the desktop `preview_body` vocabulary — the name, the
/// port, the device subtitle — plus the URL the browser entry loads
/// (`http://{hostname}:7332`; the WebView's Chromium synthesizes
/// `*.localhost` to loopback in-process, so no OS resolver is involved).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PreviewServiceRow {
    /// The engine's stable service id.
    pub id: String,
    /// The row's title (the server's role: "Vite", "API", …).
    pub name: String,
    /// The **effective** hostname (what the in-app proxy routes — the
    /// engine's name, or a persistent distinct label when two engines'
    /// device names collide).
    pub hostname: String,
    /// The engine-side port (the row's `device · localhost:port` subtitle).
    pub port: u16,
    /// The engine device's display name (the row's subtitle).
    pub device_name: String,
    /// `http://{hostname}:7332` — what Open enters.
    pub url: String,
}

/// The panel's phase — the `state/preview.ts` state machine.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PreviewPanelPhase {
    /// No frame yet on the current subscription.
    Loading,
    /// A snapshot is on screen (the rows may be empty — the honest empty
    /// state, not a degraded panel).
    Live,
    /// The engine session is offline, revoked or re-dialing; the rows are
    /// cleared and the panel keeps watching ("Connecting to preview
    /// discovery…").
    Reconnecting,
    /// The engine predates previews; degrade once, never poll forever.
    Unsupported,
}

impl From<mc::PanelPhase> for PreviewPanelPhase {
    fn from(phase: mc::PanelPhase) -> Self {
        match phase {
            mc::PanelPhase::Loading => Self::Loading,
            mc::PanelPhase::Live => Self::Live,
            mc::PanelPhase::Reconnecting => Self::Reconnecting,
            mc::PanelPhase::Unsupported => Self::Unsupported,
        }
    }
}

/// One panel state — `state/preview.ts`: the rows (with effective
/// hostnames) while live, the inline error (a busy 7332, an ingest
/// refusal, the engine's own discovery error), and the phase driving the
/// empty/reconnect/unsupported rendering.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PreviewPanelState {
    pub phase: PreviewPanelPhase,
    /// The engine whose previews these are — the browser entry holds the
    /// proxy's watcher for this engine while it is on screen.
    pub engine_id: String,
    pub services: Vec<PreviewServiceRow>,
    /// The inline error, `null` while healthy.
    pub error: Option<String>,
}

impl From<(String, mc::PanelState)> for PreviewPanelState {
    fn from((engine_id, state): (String, mc::PanelState)) -> Self {
        Self {
            phase: state.phase.into(),
            engine_id,
            services: state
                .services
                .into_iter()
                .map(|service| PreviewServiceRow {
                    url: service.url(mc::PANEL_PROXY_PORT),
                    id: service.id,
                    name: service.name,
                    hostname: service.hostname,
                    port: service.port,
                    device_name: service.device_name,
                })
                .collect(),
            error: state.error,
        }
    }
}

/// The client preview proxy's listener status — the "status of the 7332
/// listener" control the ticket's FFI wraps. The port is `null` until the
/// first watcher arrives (lazy start) and after the idle shutdown; a busy
/// port surfaces the inline error and never changes the URL (the 7331
/// failure mode).
#[derive(Debug, Clone, Default, PartialEq, Eq, uniffi::Record)]
pub struct PreviewProxyStatus {
    /// The bound port while the listener runs.
    pub port: Option<u16>,
    /// A bind failure surfaced inline.
    pub error: Option<String>,
}

impl From<mc::ClientStatus> for PreviewProxyStatus {
    fn from(status: mc::ClientStatus) -> Self {
        Self {
            port: status.port,
            error: status.error,
        }
    }
}

// ── the listener callback ─────────────────────────────────────────────────

/// Receives the panel's state after every change. Called on a client
/// runtime thread: hop to a coroutine, never block, return fast.
#[uniffi::export(with_foreign)]
pub trait PreviewPanelListener: Send + Sync {
    fn on_state(&self, state: PreviewPanelState);
}

// ── the panel object ──────────────────────────────────────────────────────

/// One live preview panel over the core's per-chat subscription. Cheap
/// reads; [`close`] stops the watch (the panel's composition owns the
/// lifecycle). The state stream ends when the panel closes or the client
/// drops.
#[derive(uniffi::Object)]
pub struct PreviewPanel {
    panel: mc::PreviewPanel,
}

#[uniffi::export]
impl PreviewPanel {
    /// The latest state (cheap; never blocks).
    pub fn state(&self) -> PreviewPanelState {
        (self.panel.engine_id().to_owned(), self.panel.state()).into()
    }

    /// Stop watching: the state machine ends, the engine's proxy watcher
    /// is released, and the state callback fires no more. Idempotent.
    ///
    /// Named `stop` (not `close`) on purpose: UniFFI's generated Kotlin
    /// object already carries an `AutoCloseable.close` (the handle's
    /// destroy), and a same-named export would collide with it.
    pub fn stop(&self) {
        self.panel.close();
    }
}

#[uniffi::export]
impl CoreClient {
    /// Start watching one chat's previews: `WatchPreviews {chatId}` on the
    /// chat's engine, the desktop's per-tab subscription shape (2 s
    /// resubscribe, the `state/preview.ts` phases). `chat_id` is the
    /// **scoped** chat id; a chat whose engine is not paired fails fast
    /// (no preview surface exists on unpaired engines beyond the panel's
    /// honest note).
    pub fn watch_previews(
        &self,
        chat_id: String,
        listener: Arc<dyn PreviewPanelListener>,
    ) -> CoreResult<Arc<PreviewPanel>> {
        let panel = self.client.watch_previews(&chat_id)?;
        // The state bridge: delivers the current state first, then every
        // change, on a client runtime thread (the notification sink's
        // contract: Kotlin hops, never blocks here). The stream ends when
        // the panel closes — `close()` drops the state sender — or the
        // client drops.
        let engine_id = panel.engine_id().to_owned();
        let mut states = panel.subscribe();
        mc::runtime::shared().spawn(async move {
            {
                let current = states.borrow_and_update().clone();
                listener.on_state((engine_id.clone(), (*current).clone()).into());
            }
            loop {
                if states.changed().await.is_err() {
                    return; // The panel closed or the client dropped.
                }
                let state = states.borrow_and_update().clone();
                listener.on_state((engine_id.clone(), (*state).clone()).into());
            }
        });
        Ok(Arc::new(PreviewPanel { panel }))
    }

    /// The client preview proxy's listener status (the bound 7332, or the
    /// inline bind error). Never blocks; the listener starts lazily on the
    /// first watcher.
    pub fn preview_proxy_status(&self) -> PreviewProxyStatus {
        self.client.preview_proxy_status().into()
    }

    /// Mark an engine's previews as viewed (or not) — the proxy listener's
    /// lazy start / idle shutdown. The panel does this on its own
    /// open/close; the **browser entry** holds its own watcher while it is
    /// on screen, so the listener stays bound while a preview is actually
    /// being viewed even with the panel's composition disposed beneath it.
    pub fn preview_proxy_set_watching(&self, engine_id: String, watching: bool) {
        self.client.preview_proxy_set_watching(&engine_id, watching);
    }
}

#[cfg(test)]
mod tests {
    use super::super::ClientEvent;
    use super::*;

    #[derive(Default)]
    struct Recorder(std::sync::Mutex<Vec<ClientEvent>>);

    impl super::super::ClientListener for Recorder {
        fn on_event(&self, event: ClientEvent) {
            self.0.lock().unwrap().push(event);
        }
    }

    /// A zero-engine client: unknown chats fail fast over the FFI error
    /// surface, the proxy never binds, and the controls are inert.
    #[test]
    fn the_preview_controls_cross_the_ffi_boundary() {
        use super::super::CoreConfig;
        let dir = tempfile::tempdir().unwrap();
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
            Arc::new(Recorder::default()),
        )
        .unwrap();
        // Nothing paired: a scoped chat of a stranger engine, and a bare
        // id, both refuse fast — nothing is watched, nothing binds.
        let stranger = client.client().scope("engine-nope", "chat-1");
        for chat_id in [stranger, "chat-1".to_owned()] {
            match client.watch_previews(chat_id, Arc::new(NoopListener)) {
                Err(super::super::CoreError::NotFound { .. }) => {}
                Err(other) => panic!("unexpected error: {other:?}"),
                Ok(_) => panic!("an unpaired engine must not open a panel"),
            }
        }
        let status = client.preview_proxy_status();
        assert_eq!(status.port, None);
        assert_eq!(status.error, None);
        // The watcher control is inert but safe on unknown engines.
        client.preview_proxy_set_watching("engine-nope".into(), true);
        client.preview_proxy_set_watching("engine-nope".into(), false);
        client.shutdown();
    }

    struct NoopListener;

    impl PreviewPanelListener for NoopListener {
        fn on_state(&self, _state: PreviewPanelState) {}
    }

}
