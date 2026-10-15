//! Tailcat end to end (android-next#02): a real in-process engine whose
//! paired listener is published by the desktop adapter
//! (`roboco_engine::tailcat::TailcatServer`), and a phone core that pairs
//! from a `roboco-tailcat:` invite through the real `libroboco_tailcat`
//! loaded with `dlopen`. Hermetic: DERP and STUN run on 127.0.0.1
//! (`adapters/roboco-tailcat/cmd/roboco-tailcat-testderp`).
//!
//! Runs where CI built the three Go pieces and points at them; skips with a
//! log line otherwise (the engine's own pinned-adapter test does the same):
//!
//! - `ROBOCO_TAILCAT_LIB_DIR` — the directory holding the host
//!   `libroboco_tailcat` (`go build -buildmode=c-shared ./capi`);
//! - `ROBOCO_TAILCAT_ADAPTER` — the `roboco-tailcat` adapter executable;
//! - `ROBOCO_TAILCAT_TESTDERP` — the `roboco-tailcat-testderp` executable.

mod support;

use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, Instant};

use roboco_engine::pairing::PairingStore;
use roboco_engine::tailcat::{TailcatInvite, TailcatServer};
use roboco_mobile_client::timings::Timings;
use roboco_mobile_client::{
    Client, ClientConfig, EngineState, EngineTransport, EngineView, TransportKind,
};
use support::{Recorder, serve, test_timings};

/// The three Go artifacts, or `None` (skip) when CI did not build them.
struct GoPieces {
    lib_dir: PathBuf,
    testderp: PathBuf,
}

fn go_pieces() -> Option<GoPieces> {
    let env = |name: &str| {
        std::env::var_os(name)
            .map(PathBuf::from)
            .filter(|path| path.exists())
    };
    let (Some(lib_dir), Some(_adapter), Some(testderp)) = (
        env("ROBOCO_TAILCAT_LIB_DIR"),
        env("ROBOCO_TAILCAT_ADAPTER"),
        env("ROBOCO_TAILCAT_TESTDERP"),
    ) else {
        eprintln!(
            "skipping the Tailcat end-to-end test: set ROBOCO_TAILCAT_LIB_DIR, \
             ROBOCO_TAILCAT_ADAPTER and ROBOCO_TAILCAT_TESTDERP (CI builds them)"
        );
        return None;
    };
    Some(GoPieces { lib_dir, testderp })
}

/// The local DERP+STUN relay; killed (stdin closed) on drop.
struct TestDerp {
    child: Child,
    map_url: String,
}

impl TestDerp {
    fn start(binary: &PathBuf) -> Self {
        let mut child = Command::new(binary)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .expect("testderp starts");
        let stdout = child.stdout.take().expect("testderp stdout");
        let mut line = String::new();
        BufReader::new(stdout)
            .read_line(&mut line)
            .expect("testderp readiness line");
        let ready: serde_json::Value = serde_json::from_str(line.trim()).expect("readiness JSON");
        let map_url = ready["derpMap"].as_str().expect("derpMap URL").to_owned();
        Self { child, map_url }
    }
}

impl Drop for TestDerp {
    fn drop(&mut self) {
        drop(self.child.stdin.take());
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// Supervisor timings with room for a tunnel: an open pings the engine
/// (up to ~20 s) before the first WebSocket dial.
fn tailcat_timings() -> Timings {
    Timings {
        identity_timeout: Duration::from_secs(10),
        subscribe_timeout: Duration::from_secs(10),
        sync_deadline: Duration::from_secs(20),
        max_backoff: Duration::from_secs(1),
        ..test_timings()
    }
}

fn client_with(data_dir: &std::path::Path, lib_dir: &PathBuf, engines: Vec<roboco_mobile_client::PairedEngine>) -> Arc<Client> {
    Client::new_with_timings(
        ClientConfig {
            native_lib_dir: Some(lib_dir.clone()),
            ..support::test_config(data_dir)
        },
        engines,
        Arc::new(Recorder::default()),
        tailcat_timings(),
    )
    .unwrap()
}

/// Wait (bounded, generously: tunnels rebuild with a ping) for the view.
async fn wait_for(client: &Client, engine_id: &str, label: &str, predicate: impl Fn(&EngineView) -> bool) -> EngineView {
    let deadline = Instant::now() + Duration::from_secs(90);
    loop {
        if let Some(view) = client.engine(engine_id) {
            if predicate(&view) {
                return view;
            }
            if Instant::now() > deadline {
                panic!(
                    "{label}: engine stuck in {:?} ({:?})",
                    view.state, view.last_error
                );
            }
        } else if Instant::now() > deadline {
            panic!("{label}: engine {engine_id} is missing");
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

fn mint_invite(engine_dir: &std::path::Path, address: &str) -> String {
    let code = PairingStore::open(engine_dir)
        .unwrap()
        .create_code("test", 300)
        .unwrap();
    TailcatInvite::new(address, &code.credential, code.expires_at)
        .unwrap()
        .encode()
        .unwrap()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_tailcat_engine_pairs_and_reconnects_from_its_stored_record() {
    let Some(go) = go_pieces() else {
        return;
    };
    let derp = TestDerp::start(&go.testderp);
    let engine_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let listener = serve(&core, engine_dir.path()).await;
    let target = listener.address;
    let map_url = derp.map_url.clone();
    let server_dir = engine_dir.path().to_owned();
    let server = tokio::task::spawn_blocking(move || {
        TailcatServer::start(&server_dir, target, Some(&map_url)).expect("tailcat serve")
    })
    .await
    .unwrap();
    let address = server.address().to_owned();

    // 1. Pair from the invite: the record keeps the address and a
    //    generated client identity, and shows only the fingerprint.
    let data_dir = tempfile::tempdir().unwrap();
    let client = client_with(data_dir.path(), &go.lib_dir, Vec::new());
    let invite = mint_invite(engine_dir.path(), &address);
    let target = roboco_mobile_client::parse_pairing_url(&invite).unwrap();
    assert_eq!(target.transport, TransportKind::Tailcat);
    let record = tokio::time::timeout(Duration::from_secs(60), client.pair_engine(&invite, None))
        .await
        .expect("pairing completes")
        .expect("pairing succeeds");
    assert!(record.base_url.starts_with("tailcat:"), "{}", record.base_url);
    assert!(!record.base_url.contains(&address));
    let client_state = match &record.transport {
        EngineTransport::Tailcat {
            address: stored,
            client_state,
        } => {
            assert_eq!(stored, &address);
            assert!(!client_state.is_empty(), "the generated identity is stored");
            client_state.clone()
        }
        other => panic!("expected a Tailcat record, got {other:?}"),
    };
    assert!(!format!("{record:?}").contains(&address));
    let view = wait_for(&client, &record.engine_id, "paired", |view| {
        view.state == EngineState::Connected
    })
    .await;
    assert_eq!(view.transport, TransportKind::Tailcat);
    client.shutdown();

    // 2. A restarted app holds only the stored record: it reconnects over
    //    Tailcat with the same identity.
    let restarted_dir = tempfile::tempdir().unwrap();
    let client = client_with(restarted_dir.path(), &go.lib_dir, vec![record.clone()]);
    let view = wait_for(&client, &record.engine_id, "restarted client", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let mut generation = view.diagnostics.generation;

    // 3. A forced tunnel drop (what a network change does) heals.
    client.set_network_changed();
    let view = wait_for(&client, &record.engine_id, "after a tunnel drop", |view| {
        view.state == EngineState::Connected && view.diagnostics.generation > generation
    })
    .await;
    generation = view.diagnostics.generation;

    // 4. The engine's adapter restarts from its persisted state: the
    //    address is unchanged, and the phone reconnects without re-pairing.
    drop(server);
    let map_url = derp.map_url.clone();
    let server_dir = engine_dir.path().to_owned();
    let server = tokio::task::spawn_blocking(move || {
        TailcatServer::start(&server_dir, target_addr(&listener), Some(&map_url))
            .expect("tailcat serve again")
    })
    .await
    .unwrap();
    assert_eq!(server.address(), address, "the address is stable");
    wait_for(&client, &record.engine_id, "after an engine restart", |view| {
        view.state == EngineState::Connected && view.diagnostics.generation > generation
    })
    .await;
    // The stored identity never changed.
    assert!(matches!(
        &record.transport,
        EngineTransport::Tailcat { client_state: stored, .. } if *stored == client_state
    ));
    client.shutdown();
    drop(server);
}

fn target_addr(listener: &roboco_engine::EngineListener) -> std::net::SocketAddr {
    listener.address
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn without_the_library_a_tailcat_invite_is_unavailable_and_direct_is_unaffected() {
    let engine_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let listener = serve(&core, engine_dir.path()).await;
    let data_dir = tempfile::tempdir().unwrap();
    let empty_lib_dir = tempfile::tempdir().unwrap();
    let client = client_with(data_dir.path(), &empty_lib_dir.path().to_owned(), Vec::new());
    let far_future = support::now_ms() + 300_000;
    let invite = TailcatInvite::new("tcAAAAAAAAAAAAAAAAAAAAAAAA", "code", far_future)
        .unwrap()
        .encode()
        .unwrap();
    match client.pair_engine(&invite, None).await {
        Err(roboco_mobile_client::PairError::TailcatUnavailable { message }) => {
            assert!(!message.contains("tcAAAA"), "{message}")
        }
        other => panic!("expected TailcatUnavailable, got {:?}", other.err()),
    }
    // A direct engine pairs and connects as always.
    let url = support::pairing_url(&listener, engine_dir.path(), None);
    let record = client.pair_engine(&url, None).await.expect("direct pairing");
    assert_eq!(record.transport, EngineTransport::Direct);
    wait_for(&client, &record.engine_id, "direct engine", |view| {
        view.state == EngineState::Connected && view.transport == TransportKind::Direct
    })
    .await;
    client.shutdown();
}
