//! Test fixture: the desktop's real bootstrap (an embedded local engine and
//! its registry) plus one engine paired over loopback — the shape a server
//! paired through Settings → Devices has. Real sockets: RPC completions wake
//! gpui tasks from tokio workers, so the fixture opts into `allow_parking`
//! (the same escape `state::cache_tests` uses).

use std::{
    sync::Arc,
    time::{Duration, Instant},
};

use gpui::{AppContext, Entity, TestAppContext};
use roboco_engine::{EngineCore, EngineListener};
use roboco_engine_client::delay_proxy::{DelayProxy, LinkProfile};

use crate::engine_registry::{EngineKey, EngineRegistry, EngineTarget, ScopedId};
use crate::state::{AppState, EngineBootConfig};
use crate::theme::Theme;

pub(crate) struct PairedEngines {
    pub runtime: tokio::runtime::Runtime,
    pub local_dir: tempfile::TempDir,
    _remote_dir: tempfile::TempDir,
    pub remote: EngineCore,
    listener: Option<EngineListener>,
    pub state: Entity<AppState>,
    pub boot: EngineBootConfig,
    pub registry: EngineRegistry,
    pub key: EngineKey,
    pub target: EngineTarget,
    /// The slow link the pairing (and so every request) goes through, when
    /// started with [`PairedEngines::start_over`].
    pub proxy: Option<DelayProxy>,
}

/// Pump gpui until `done` holds, sleeping between passes so tokio workers
/// can land their wakes.
pub(crate) fn wait_until(
    cx: &mut TestAppContext,
    what: &str,
    mut done: impl FnMut(&mut TestAppContext) -> bool,
) {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        cx.run_until_parked();
        if done(cx) {
            return;
        }
        assert!(Instant::now() < deadline, "Timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

impl PairedEngines {
    /// Boot the local engine through `AppState::bootstrap`, pair a second
    /// real engine over loopback, and wait until it is connected.
    pub fn start(cx: &mut TestAppContext) -> Self {
        Self::start_over(cx, None, |_| {})
    }

    /// [`Self::start`], with the paired engine reached through a delay
    /// proxy shaped like `link` (when given), and `seed` run on the paired
    /// engine before the desktop pairs it.
    pub fn start_over(
        cx: &mut TestAppContext,
        link: Option<LinkProfile>,
        seed: impl FnOnce(&EngineCore),
    ) -> Self {
        cx.executor().allow_parking();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .unwrap();
        let local_dir = tempfile::tempdir().unwrap();
        let remote_dir = tempfile::tempdir().unwrap();
        cx.update(|cx| {
            gpui_tokio::init_from_handle(cx, runtime.handle().clone());
            gpui_base::init(cx);
            cx.set_global(Theme::default());
            crate::app_menus::init(cx);
            crate::history::init(
                Default::default(),
                Default::default(),
                Default::default(),
                Default::default(),
                cx,
            );
            crate::settings::init(Default::default(), local_dir.path(), cx);
        });
        let (remote, listener, pairing_url, proxy) = runtime.block_on(async {
            let core = EngineCore::assemble_with_profile(
                roboco_engine::EngineProfile::local(remote_dir.path()).unwrap(),
                Arc::new(roboco_engine::HarnessRegistry::new()),
                roboco_proto::HarnessId::Mock,
            )
            .unwrap();
            seed(&core);
            let listener = roboco_engine::serve_engine_remote(
                "127.0.0.1:0".parse().unwrap(),
                core.rpc_service(),
                remote_dir.path(),
            )
            .await
            .unwrap();
            let proxy = match link {
                Some(link) => Some(DelayProxy::start(listener.address, link).await),
                None => None,
            };
            let address = proxy
                .as_ref()
                .map_or(listener.address, |proxy| proxy.address);
            let code = roboco_engine::pairing::PairingStore::open(remote_dir.path())
                .unwrap()
                .create_code("paired fixture", 300)
                .unwrap();
            let url =
                roboco_engine::pairing::pairing_url(&format!("http://{address}"), &code.credential)
                    .unwrap();
            (core, listener, url, proxy)
        });
        let boot = EngineBootConfig {
            data_dir: local_dir.path().to_path_buf(),
            ipc_port: 0,
            default_harness: roboco_proto::HarnessId::Mock,
        };
        let state = cx.new(|_| AppState::new());
        cx.update(|cx| AppState::bootstrap(state.clone(), boot.clone(), cx));
        wait_until(cx, "the client registry", |cx| {
            state.read_with(cx, |s, _| s.registry().is_some())
        });
        let registry = state.read_with(cx, |s, _| s.registry().unwrap().clone());
        let key = runtime
            .block_on(registry.pair(&pairing_url, "Roboco desktop"))
            .unwrap();
        let target = registry.target(&key).unwrap();
        wait_until(cx, "the paired engine", |_| target.is_connected());
        Self {
            runtime,
            local_dir,
            _remote_dir: remote_dir,
            remote,
            listener: Some(listener),
            state,
            boot,
            registry,
            key,
            target,
            proxy,
        }
    }

    /// `raw` on the paired engine, as the desktop projects it.
    pub fn scoped(&self, raw: &str) -> String {
        ScopedId::encode(&self.key, raw)
    }

    /// The paired engine's device row id, as the desktop projects it.
    pub fn remote_device(&self) -> String {
        self.scoped(&self.remote.device_id)
    }

    /// The local engine's device id (unscoped: the local key never scopes).
    pub fn local_device(&self, cx: &mut TestAppContext) -> String {
        self.state
            .read_with(cx, |s, _| s.local_device_id.clone())
            .expect("local device")
    }

    pub fn wait_for_state(
        &self,
        cx: &mut TestAppContext,
        what: &str,
        predicate: impl Fn(&AppState) -> bool,
    ) {
        let state = self.state.clone();
        wait_until(cx, what, |cx| state.read_with(cx, |s, _| predicate(s)));
    }

    /// Stop the registry, the local engine and the paired engine.
    pub fn shutdown(mut self, cx: &mut TestAppContext) {
        let engine = self.state.read_with(cx, |s, _| s.engine().cloned());
        drop(self.listener.take());
        let Self {
            runtime,
            remote,
            registry,
            ..
        } = self;
        runtime.block_on(async {
            registry.shutdown().await;
            if let Some(engine) = engine {
                engine.shutdown().await;
            }
            remote.shutdown().await;
        });
    }
}
