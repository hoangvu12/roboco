//! Real-engine test support: an in-process `EngineCore` served over
//! loopback, a counting TCP listener for dial-attempt assertions, a
//! pausable TCP proxy for liveness tests, and a listener recorder. No
//! protocol mocks anywhere (spec "Testing Decisions") — every frame comes
//! from the real engine.

use std::net::SocketAddr;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use roboco_engine::pairing::PairingStore;
use roboco_engine::{EngineCore, EngineListener, EngineProfile, HarnessId};
use roboco_mobile_client::timings::{NotifyTimings, Timings};
pub mod stall_proxy;

use roboco_mobile_client::{Client, ClientConfig, ClientEvent, ClientListener, FleetSnapshot};

/// Assemble a real engine with the mock harness registered
/// (`smoke_registry`: `HarnessRegistry::new()` alone is empty — no mock).
pub fn core(dir: &Path) -> EngineCore {
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir).unwrap(),
        Arc::new(roboco_engine::smoke_registry()),
        HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_chat("same-chat", None, Some(&core.device_id), None, None)
        .unwrap();
    core
}

/// Serve an engine remotely on an ephemeral port.
pub async fn serve(core: &EngineCore, dir: &Path) -> EngineListener {
    roboco_engine::serve_engine_remote("127.0.0.1:0".parse().unwrap(), core.rpc_service(), dir)
        .await
        .unwrap()
}

/// Serve the same engine again on a *fixed* port (the reconnect test):
/// re-binding a just-freed port can need retries on Windows
/// (`TcpListener::bind` sets `SO_REUSEADDR` on Unix only).
pub async fn serve_on_port(core: &EngineCore, dir: &Path, port: u16) -> EngineListener {
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    loop {
        let address: SocketAddr = format!("127.0.0.1:{port}").parse().unwrap();
        match roboco_engine::serve_engine_remote(address, core.rpc_service(), dir).await {
            Ok(listener) => return listener,
            Err(error) => {
                if std::time::Instant::now() > deadline {
                    panic!("could not re-bind port {port} within 2 s: {error}");
                }
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        }
    }
}

/// Mint a one-time pairing code on the engine and build its pairing URL
/// (optionally through a proxy port).
pub fn pairing_url(listener: &EngineListener, dir: &Path, proxy_port: Option<u16>) -> String {
    let store = PairingStore::open(dir).unwrap();
    let code = store.create_code("test", 300).unwrap();
    let host = match proxy_port {
        Some(port) => format!("127.0.0.1:{port}"),
        None => listener.address.to_string(),
    };
    format!("http://{host}/pair#token={}", code.credential)
}

/// Accepts and immediately drops every connection, counting them: the
/// honest way to count *dial attempts* against an engine that is gone.
pub struct CountingListener {
    pub address: SocketAddr,
    pub accepts: Arc<AtomicUsize>,
    task: tokio::task::JoinHandle<()>,
}

impl CountingListener {
    pub async fn start() -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let accepts = Arc::new(AtomicUsize::new(0));
        let count = accepts.clone();
        let task = tokio::spawn(async move {
            loop {
                match listener.accept().await {
                    Ok((stream, _)) => {
                        count.fetch_add(1, Ordering::SeqCst);
                        drop(stream);
                    }
                    Err(_) => break,
                }
            }
        });
        Self {
            address,
            accepts,
            task,
        }
    }

    /// Start on a fixed port (replacing a stopped engine listener).
    pub async fn start_on_port(port: u16) -> Self {
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        loop {
            match tokio::net::TcpListener::bind(("127.0.0.1", port)).await {
                Ok(listener) => {
                    let address = listener.local_addr().unwrap();
                    let accepts = Arc::new(AtomicUsize::new(0));
                    let count = accepts.clone();
                    let task = tokio::spawn(async move {
                        loop {
                            match listener.accept().await {
                                Ok((stream, _)) => {
                                    count.fetch_add(1, Ordering::SeqCst);
                                    drop(stream);
                                }
                                Err(_) => break,
                            }
                        }
                    });
                    return Self {
                        address,
                        accepts,
                        task,
                    };
                }
                Err(error) => {
                    if std::time::Instant::now() > deadline {
                        panic!("could not bind counting listener on {port}: {error}");
                    }
                    tokio::time::sleep(Duration::from_millis(50)).await;
                }
            }
        }
    }

    pub fn accepts(&self) -> usize {
        self.accepts.load(Ordering::SeqCst)
    }
}

impl Drop for CountingListener {
    fn drop(&mut self) {
        self.task.abort();
    }
}

/// Records every `ClientEvent` the client delivers.
#[derive(Clone, Default)]
pub struct Recorder(pub Arc<Mutex<Vec<ClientEvent>>>);

impl ClientListener for Recorder {
    fn on_event(&self, event: ClientEvent) {
        self.0.lock().unwrap().push(event);
    }
}

impl Recorder {
    pub fn events(&self) -> Vec<ClientEvent> {
        self.0.lock().unwrap().clone()
    }
}

/// Every supervisor timing shrunk so liveness behaviour runs in seconds.
pub fn test_timings() -> Timings {
    Timings {
        initial_backoff: Duration::from_millis(50),
        max_backoff: Duration::from_millis(200),
        backoff_reset_after: Duration::from_secs(1),
        identity_timeout: Duration::from_secs(2),
        subscribe_timeout: Duration::from_secs(2),
        sync_deadline: Duration::from_secs(5),
        probe_every: Duration::from_millis(200),
        lag_every: Duration::from_millis(300),
        lag_timeout: Duration::from_millis(400),
        cache_debounce: Duration::from_millis(50),
        background_flush: Duration::from_secs(1),
        refresh_cap: Duration::from_secs(1),
        undelivered_grace: Duration::from_secs(2),
        undelivered_heal: Duration::from_secs(2),
        workspace_tick: Duration::from_millis(100),
        // android-next#05: the forced-usage deadline shrinks so the
        // stall-proxy test cuts a frozen probe in well under a second, and
        // before the link's own stall detection (probe 200 + lag 400 ms)
        // tears the connection down with a different error.
        accounts_forced_deadline: Duration::from_millis(250),
        notify: NotifyTimings::default(),
    }
}

pub fn test_config(data_dir: &Path) -> ClientConfig {
    ClientConfig {
        data_dir: data_dir.to_owned(),
        device_name: "Pixel 9".into(),
        platform: "android".into(),
        app_version: env!("CARGO_PKG_VERSION").into(),
        // Tests drive mock engines: the mock harness is offered.
        show_mock_harness: true,
        native_lib_dir: None,
    }
}

pub fn test_client(
    data_dir: &Path,
    engines: Vec<roboco_mobile_client::PairedEngine>,
    listener: Arc<Recorder>,
) -> Arc<Client> {
    Client::new_with_timings(test_config(data_dir), engines, listener, test_timings()).unwrap()
}

/// Milliseconds since the epoch (the test's clock).
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or_default()
}

/// Wait until the fleet snapshot satisfies `predicate`.
pub async fn wait_for_fleet(
    client: &Client,
    label: &str,
    predicate: impl Fn(&FleetSnapshot) -> bool,
) {
    let mut fleet = client.fleet();
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            if predicate(&fleet.borrow_and_update()) {
                return;
            }
            fleet.changed().await.expect("fleet watch alive");
        }
    })
    .await
    .unwrap_or_else(|_| panic!("fleet never reached: {label}"));
}

/// Wait until any engine view satisfies `predicate` (polls the published
/// views; the fleet snapshot and the views publish together).
pub async fn wait_for_view(
    client: &Client,
    engine_id: &str,
    label: &str,
    predicate: impl Fn(&roboco_mobile_client::EngineView) -> bool,
) {
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            if let Some(view) = client.engine(engine_id) {
                if predicate(&view) {
                    return;
                }
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap_or_else(|_| panic!("engine view never reached: {label}"));
}
