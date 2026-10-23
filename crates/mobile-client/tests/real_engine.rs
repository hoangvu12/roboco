//! Real-engine tests: every frame comes from an in-process `EngineCore`
//! served over loopback (`spec "Testing Decisions"` — no protocol mocks).
//! Coverage follows the ticket's acceptance list: pairing, connect,
//! reconnect, park rules, offline cache, forget, call routing, liveness
//! and the large reset.

mod support;

use roboco_mobile_client::{Client, ClientError, EngineState, PairedEngine, ParkReason};
use std::sync::Arc;
use std::time::{Duration, Instant};
use support::{
    CountingListener, Recorder, pairing_url, serve, serve_on_port, test_client, wait_for_fleet,
    wait_for_view,
};

fn engine_rows(client: &Client, engine_id: &str) -> usize {
    client
        .fleet()
        .borrow_and_update()
        .engine(engine_id)
        .map(|engine| engine.rows.chats.len())
        .unwrap_or(0)
}

/// Pair a fresh engine, pin its identity, reach `Connected` within 5 s and
/// receive the engine's rows. The notice is `None` (same version).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pair_pins_identity_connects_and_receives_feed_rows() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let mut listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder.clone());
    let record = tokio::time::timeout(Duration::from_secs(10), client.pair_engine(&url, None))
        .await
        .expect("pairing completes")
        .expect("pairing succeeds");
    assert_eq!(record.device_id.as_deref(), Some(core.device_id.as_str()));
    assert!(!record.credential.is_empty());
    assert!(!record.session_id.is_empty());
    assert_eq!(record.label, "Pixel 9");
    // The engine reaches Connected within 5 s.
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if client
                .engine(&record.engine_id)
                .is_some_and(|view| view.state == EngineState::Connected)
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("engine reaches Connected within 5 s");
    // ...and its feed rows include the chat created on the engine.
    wait_for_fleet(&client, "feed rows", |fleet| {
        fleet
            .engine(&record.engine_id)
            .map(|engine| engine.rows.chats.iter().any(|chat| chat.id == "same-chat"))
            .unwrap_or(false)
    })
    .await;
    let view = client.engine(&record.engine_id).unwrap();
    assert!(!view.from_cache, "live frames arrived");
    assert_eq!(view.notice, None, "same version: no notice");
    assert!(
        !view.capabilities.is_empty(),
        "capabilities flow into the view"
    );
    assert!(view.diagnostics.generation >= 1);
    // The platform got the pin to persist.
    assert!(recorder.events().iter().any(|event| matches!(
        event,
        roboco_mobile_client::ClientEvent::EngineIdentityPinned { engine_id, device_id, .. }
            if engine_id == &record.engine_id && device_id == &core.device_id
    )));
    // The aggregate flipped to Connected, and the coalesced events told
    // the platform so (16 ms bursts; wait one out).
    assert_eq!(
        client.connectivity().state,
        roboco_mobile_client::ConnectivityState::Connected
    );
    tokio::time::sleep(Duration::from_millis(200)).await;
    let events = recorder.events();
    assert!(
        events
            .iter()
            .any(|event| matches!(event, roboco_mobile_client::ClientEvent::EnginesChanged)),
        "EnginesChanged must reach the listener: {events:?}"
    );
    assert!(
        events.iter().any(|event| matches!(
            event,
            roboco_mobile_client::ClientEvent::ConnectivityChanged { .. }
        )),
        "ConnectivityChanged must reach the listener: {events:?}"
    );
    client.shutdown();
    drop(listener);
    core.shutdown().await;
}

/// A single-use code cannot be redeemed twice, and an expired one is
/// refused the same way.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn used_and_expired_codes_are_refused() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let listener = serve(&core, engine_dir.path()).await;
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let url = pairing_url(&listener, engine_dir.path(), None);
    client.pair_engine(&url, None).await.unwrap();
    match client.pair_engine(&url, None).await {
        Err(roboco_mobile_client::PairError::Refused) => {}
        other => panic!("a used code must be Refused, got {other:?}"),
    }
    // An expired code: mint with a 1 s TTL, let it lapse, redeem.
    let store = roboco_engine::pairing::PairingStore::open(engine_dir.path()).unwrap();
    let expired = store.create_code("expired", 1).unwrap();
    tokio::time::sleep(Duration::from_millis(1500)).await;
    let url = format!("http://{}/pair#token={}", listener.address, expired.credential);
    match client.pair_engine(&url, None).await {
        Err(roboco_mobile_client::PairError::Refused) => {}
        other => panic!("an expired code must be Refused, got {other:?}"),
    }
    client.shutdown();
    drop(listener);
    core.shutdown().await;
}

/// Stopping the engine listener makes the engine `Reconnecting` with
/// `retry_at_ms` set; re-serving the same `EngineCore` on the same port
/// returns it to `Connected` without any API call. `generation` increments
/// and the cached rows stay readable throughout.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn listener_restart_reconnects_with_a_new_generation() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let mut listener = serve(&core, engine_dir.path()).await;
    let port = listener.address.port();
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let first_generation = client
        .engine(&record.engine_id)
        .unwrap()
        .diagnostics
        .generation;
    client
        .engine_handle(&record.engine_id)
        .unwrap()
        .cache()
        .flush()
        .await;
    // Kill the listener.
    listener.stop().await;
    wait_for_view(
        &client,
        &record.engine_id,
        "reconnecting with a retry",
        |view| view.state == EngineState::Reconnecting && view.retry_at_ms.is_some(),
    )
    .await;
    // The rows stay readable (the in-memory mirror of the cached rows).
    assert!(engine_rows(&client, &record.engine_id) >= 1);
    // Re-serve the same engine on the same port: no API call is needed.
    let mut listener = serve_on_port(&core, engine_dir.path(), port).await;
    wait_for_view(&client, &record.engine_id, "connected again", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let second_generation = client
        .engine(&record.engine_id)
        .unwrap()
        .diagnostics
        .generation;
    assert!(
        second_generation > first_generation,
        "generation increments"
    );
    assert!(engine_rows(&client, &record.engine_id) >= 1);
    client.shutdown();
    listener.stop().await;
    core.shutdown().await;
}

fn generation_of(client: &Client, engine_id: &str) -> u64 {
    client.engine(engine_id).unwrap().diagnostics.generation
}

/// Lifecycle events never tear down a healthy link, and one event costs
/// at most one reconnect: the foreground and network-online callbacks used
/// to queue both `Foreground` and `DialNow`; the backoff wait took one,
/// and the new link read the other as a redial request and dropped itself.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn lifecycle_events_cost_at_most_one_reconnect() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let mut listener = serve(&core, engine_dir.path()).await;
    let port = listener.address.port();
    let url = pairing_url(&listener, engine_dir.path(), None);
    let client = test_client(data_dir.path(), Vec::new(), Arc::new(Recorder::default()));
    let record = client.pair_engine(&url, None).await.unwrap();
    let engine_id = record.engine_id.clone();
    wait_for_view(&client, &engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    // A connected engine: foreground and network-online keep the link.
    let healthy = generation_of(&client, &engine_id);
    client.on_foreground();
    client.set_network_online(true);
    client.set_network_online(true);
    tokio::time::sleep(Duration::from_millis(1500)).await;
    let view = client.engine(&engine_id).unwrap();
    assert_eq!(view.state, EngineState::Connected);
    assert_eq!(
        view.diagnostics.generation, healthy,
        "a lifecycle event redialed a healthy link"
    );
    // A dropped engine: the thaw's foreground event reconnects exactly once.
    listener.stop().await;
    wait_for_view(&client, &engine_id, "reconnecting", |view| {
        view.state == EngineState::Reconnecting
    })
    .await;
    let mut listener = serve_on_port(&core, engine_dir.path(), port).await;
    client.on_foreground();
    client.set_network_online(true);
    wait_for_view(&client, &engine_id, "connected again", |view| {
        view.state == EngineState::Connected
    })
    .await;
    tokio::time::sleep(Duration::from_millis(1500)).await;
    let view = client.engine(&engine_id).unwrap();
    assert_eq!(view.state, EngineState::Connected);
    assert_eq!(
        view.diagnostics.generation,
        healthy + 1,
        "the reconnect after the outage must be a single link"
    );
    client.shutdown();
    listener.stop().await;
    core.shutdown().await;
}

/// After `PairingStore::revoke` and a listener restart, the engine becomes
/// `Parked { Revoked }` (HTTP 401 on upgrade — the Rust client never sees
/// a 4401 close code) and makes no further dial attempts.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn revoked_session_parks_and_stops_dialing() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let mut listener = serve(&core, engine_dir.path()).await;
    let port = listener.address.port();
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    // Revoke, then restart: the next dial gets HTTP 401 on the upgrade.
    listener.stop().await;
    roboco_engine::pairing::PairingStore::open(engine_dir.path())
        .unwrap()
        .revoke(&record.session_id)
        .unwrap();
    let mut listener = serve_on_port(&core, engine_dir.path(), port).await;
    wait_for_view(&client, &record.engine_id, "parked as revoked", |view| {
        view.state
            == EngineState::Parked {
                reason: ParkReason::Revoked,
            }
    })
    .await;
    // Parked engines do not dial: swap in a counting listener for 3 s.
    listener.stop().await;
    let counter = CountingListener::start_on_port(port).await;
    tokio::time::sleep(Duration::from_secs(3)).await;
    assert_eq!(counter.accepts(), 0, "a parked engine must not dial");
    drop(counter);
    client.shutdown();
    core.shutdown().await;
}

/// Re-serving a *different* engine whose directory received a copy of the
/// first engine's `pairing.sqlite3` parks the link: same credential, new
/// identity.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn identity_change_parks() {
    let engine_a_dir = tempfile::tempdir().unwrap();
    let engine_b_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core_a = support::core(engine_a_dir.path());
    let mut listener = serve(&core_a, engine_a_dir.path()).await;
    let port = listener.address.port();
    let url = pairing_url(&listener, engine_a_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    // Engine B: a fresh core in a directory that got A's pairing store.
    listener.stop().await;
    std::fs::copy(
        engine_a_dir.path().join("pairing.sqlite3"),
        engine_b_dir.path().join("pairing.sqlite3"),
    )
    .unwrap();
    let core_b = support::core(engine_b_dir.path());
    let mut listener = serve_on_port(&core_b, engine_b_dir.path(), port).await;
    wait_for_view(
        &client,
        &record.engine_id,
        "parked as identity changed",
        |view| {
            view.state
                == EngineState::Parked {
                    reason: ParkReason::IdentityChanged,
                }
        },
    )
    .await;
    client.shutdown();
    listener.stop().await;
    core_a.shutdown().await;
    core_b.shutdown().await;
}

/// `pair_engine(url2, Some(engine_id))` on a parked engine un-parks it and
/// keeps the engine id stable.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn re_pairing_a_parked_engine_keeps_the_engine_id() {
    let engine_a_dir = tempfile::tempdir().unwrap();
    let engine_b_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core_a = support::core(engine_a_dir.path());
    let mut listener = serve(&core_a, engine_a_dir.path()).await;
    let port = listener.address.port();
    let url = pairing_url(&listener, engine_a_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    // Park it via an identity change (engine B with A's pairing store).
    listener.stop().await;
    std::fs::copy(
        engine_a_dir.path().join("pairing.sqlite3"),
        engine_b_dir.path().join("pairing.sqlite3"),
    )
    .unwrap();
    let core_b = support::core(engine_b_dir.path());
    let mut listener = serve_on_port(&core_b, engine_b_dir.path(), port).await;
    wait_for_view(&client, &record.engine_id, "parked", |view| {
        view.state
            == EngineState::Parked {
                reason: ParkReason::IdentityChanged,
            }
    })
    .await;
    // Re-pair onto the same engine id with a fresh code from engine B.
    let url_b = pairing_url(&listener, engine_b_dir.path(), None);
    let re_paired = client
        .pair_engine(&url_b, Some(record.engine_id.clone()))
        .await
        .unwrap();
    assert_eq!(re_paired.engine_id, record.engine_id, "engine id is stable");
    assert_eq!(
        re_paired.device_id.as_deref(),
        Some(core_b.device_id.as_str())
    );
    wait_for_view(
        &client,
        &record.engine_id,
        "connected after re-pair",
        |view| view.state == EngineState::Connected,
    )
    .await;
    client.shutdown();
    listener.stop().await;
    core_a.shutdown().await;
    core_b.shutdown().await;
}

/// A `PairedEngine` record with an empty credential (Keystore decryption
/// failed on the platform) parks as revoked *before* any dial.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_empty_credential_parks_without_dialing() {
    let data_dir = tempfile::tempdir().unwrap();
    // No engine at all: a counting listener proves zero upgrade attempts.
    let counter = CountingListener::start().await;
    let record = PairedEngine {
        engine_id: uuid::Uuid::new_v4().to_string(),
        base_url: format!("http://{}", counter.address),
        credential: String::new(),
        session_id: "session".into(),
        label: "Pixel 9".into(),
        paired_at_ms: 0,
        device_id: Some("some-engine".into()),
        name: None,
        transport: Default::default(),
    };
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), vec![record], recorder);
    wait_for_view(
        client.as_ref(),
        &client.engines()[0].engine_id,
        "parked",
        |view| {
            view.state
                == EngineState::Parked {
                    reason: ParkReason::Revoked,
                }
        },
    )
    .await;
    tokio::time::sleep(Duration::from_secs(1)).await;
    assert_eq!(counter.accepts(), 0, "an empty credential must never dial");
    client.shutdown();
}

/// Two engines paired at once: killing one leaves the other `Connected`,
/// and its calls keep working.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn two_engines_are_independent() {
    let engine_a_dir = tempfile::tempdir().unwrap();
    let engine_b_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core_a = support::core(engine_a_dir.path());
    let core_b = support::core(engine_b_dir.path());
    let mut listener_a = serve(&core_a, engine_a_dir.path()).await;
    let mut listener_b = serve(&core_b, engine_b_dir.path()).await;
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record_a = client
        .pair_engine(&pairing_url(&listener_a, engine_a_dir.path(), None), None)
        .await
        .unwrap();
    let record_b = client
        .pair_engine(&pairing_url(&listener_b, engine_b_dir.path(), None), None)
        .await
        .unwrap();
    for record in [&record_a, &record_b] {
        wait_for_view(&client, &record.engine_id, "both connected", |view| {
            view.state == EngineState::Connected
        })
        .await;
    }
    // Kill engine A.
    listener_a.stop().await;
    wait_for_view(&client, &record_a.engine_id, "A reconnecting", |view| {
        view.state == EngineState::Reconnecting
    })
    .await;
    // B stays connected and its calls keep working.
    assert_eq!(
        client.engine(&record_b.engine_id).unwrap().state,
        EngineState::Connected
    );
    let info = client
        .engine_handle(&record_b.engine_id)
        .unwrap()
        .call("EngineInfo", serde_json::json!({}))
        .await
        .expect("calls on the surviving engine still work");
    assert_eq!(info["deviceId"], core_b.device_id);
    client.shutdown();
    listener_b.stop().await;
    core_a.shutdown().await;
    core_b.shutdown().await;
}

/// Restarting the client with the same `data_dir` and record while the
/// engine is down shows the cached rows with `from_cache = true`, and no
/// file under `data_dir` contains the credential.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn offline_restart_serves_cached_rows_and_never_writes_the_credential() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let mut listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_fleet(&client, "rows cached", |fleet| {
        fleet
            .engine(&record.engine_id)
            .map(|engine| engine.rows.chats.iter().any(|chat| chat.id == "same-chat"))
            .unwrap_or(false)
    })
    .await;
    // Let the 50 ms write debounce land, then prove the write is on disk.
    tokio::time::sleep(Duration::from_millis(300)).await;
    client
        .engine_handle(&record.engine_id)
        .unwrap()
        .cache()
        .flush()
        .await;
    client.shutdown();
    listener.stop().await;
    // Restart with the engine down.
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), vec![record.clone()], recorder);
    wait_for_fleet(&client, "cached rows visible", |fleet| {
        fleet
            .engine(&record.engine_id)
            .map(|engine| {
                engine.rows.chats.iter().any(|chat| chat.id == "same-chat") && engine.from_cache
            })
            .unwrap_or(false)
    })
    .await;
    // No dial can succeed: the view is not Connected.
    assert_ne!(
        client.engine(&record.engine_id).unwrap().state,
        EngineState::Connected
    );
    // The credential never touched disk.
    let mut files = Vec::new();
    collect_files(data_dir.path(), &mut files);
    assert!(!files.is_empty(), "the cache wrote something");
    for file in files {
        let bytes = std::fs::read(&file).unwrap();
        assert!(
            !contains_subslice(&bytes, record.credential.as_bytes()),
            "credential bytes found in {}",
            file.display()
        );
    }
    client.shutdown();
    core.shutdown().await;
}

fn collect_files(dir: &std::path::Path, out: &mut Vec<std::path::PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_files(&path, out);
        } else {
            out.push(path);
        }
    }
}

fn contains_subslice(haystack: &[u8], needle: &[u8]) -> bool {
    haystack
        .windows(needle.len())
        .any(|window| window == needle)
}

/// `forget_engine` removes the engine directory and its view; scoped ids
/// stop resolving.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn forget_engine_removes_the_cache_and_the_view() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_fleet(&client, "rows cached", |fleet| {
        fleet
            .engine(&record.engine_id)
            .map(|engine| engine.rows.chats.iter().any(|chat| chat.id == "same-chat"))
            .unwrap_or(false)
    })
    .await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    client
        .engine_handle(&record.engine_id)
        .unwrap()
        .cache()
        .flush()
        .await;
    let cache_dir = roboco_engine_client::cache::EngineCache::new(data_dir.path())
        .engine_directory(&record.engine_id);
    assert!(cache_dir.exists());
    let scoped = client.scope(&record.engine_id, "same-chat");
    client.forget_engine(&record.engine_id).await.unwrap();
    assert!(client.engine(&record.engine_id).is_none());
    assert!(!cache_dir.exists(), "the engine's cache directory is gone");
    assert!(matches!(
        client.resolve(&scoped),
        Err(ClientError::NotFound { .. })
    ));
    client.shutdown();
    drop(listener);
    core.shutdown().await;
}

/// `call` on a disconnected engine fails within 50 ms with
/// `HostUnavailable("Engine is offline; reconnecting")`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn call_on_a_disconnected_engine_fails_immediately() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let mut listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    listener.stop().await;
    wait_for_view(&client, &record.engine_id, "reconnecting", |view| {
        view.state == EngineState::Reconnecting
    })
    .await;
    let handle = client.engine_handle(&record.engine_id).unwrap();
    let started = Instant::now();
    let error = handle.call("EngineInfo", serde_json::json!({})).await;
    let elapsed = started.elapsed();
    match error {
        Err(ClientError::HostUnavailable { message }) => {
            assert_eq!(message, "Engine is offline; reconnecting");
        }
        other => panic!("expected HostUnavailable, got {other:?}"),
    }
    assert!(
        elapsed < Duration::from_millis(50),
        "the refusal must be immediate, took {elapsed:?}"
    );
    client.shutdown();
    core.shutdown().await;
}

/// A stalled link (a proxy that stops forwarding) is dropped by the health
/// probe: the engine goes `Reconnecting` within 20 s, and recovers when
/// the proxy resumes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_stalled_link_is_dropped_by_the_probe_and_recovers() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let listener = serve(&core, engine_dir.path()).await;
    let proxy = support::stall_proxy::StallProxy::start(listener.address).await;
    let url = pairing_url(&listener, engine_dir.path(), Some(proxy.address.port()));
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    proxy.pause();
    let started = Instant::now();
    wait_for_view(
        &client,
        &record.engine_id,
        "reconnecting after a stall",
        |view| view.state == EngineState::Reconnecting,
    )
    .await;
    assert!(
        started.elapsed() < Duration::from_secs(20),
        "the probe must drop a stalled link within 20 s"
    );
    proxy.resume();
    wait_for_view(
        &client,
        &record.engine_id,
        "connected after resume",
        |view| view.state == EngineState::Connected,
    )
    .await;
    client.shutdown();
    drop(proxy);
    drop(listener);
    core.shutdown().await;
}

/// A frozen transcript connection is closed by the lag check.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_frozen_transcript_connection_is_closed_by_the_lag_check() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let listener = serve(&core, engine_dir.path()).await;
    let proxy = support::stall_proxy::StallProxy::start(listener.address).await;
    let url = pairing_url(&listener, engine_dir.path(), Some(proxy.address.port()));
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let handle = client.engine_handle(&record.engine_id).unwrap();
    let connection = handle.transcript_connection().await.unwrap();
    assert!(!connection.is_closed());
    proxy.pause();
    let started = Instant::now();
    while !connection.is_closed() {
        assert!(
            started.elapsed() < Duration::from_secs(30),
            "the lag check must close a frozen transcript connection within 30 s"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    connection.stop();
    proxy.resume();
    client.shutdown();
    drop(proxy);
    drop(listener);
    core.shutdown().await;
}

/// `set_network_online(false)` makes every engine `Offline` with zero
/// dials; `true` dials again immediately.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn network_offline_stops_dials_and_online_dials_now() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let mut listener = serve(&core, engine_dir.path()).await;
    let port = listener.address.port();
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    client.set_network_online(false);
    wait_for_view(&client, &record.engine_id, "offline", |view| {
        view.state == EngineState::Offline
    })
    .await;
    assert_eq!(
        client.connectivity().state,
        roboco_mobile_client::ConnectivityState::Offline
    );
    // Zero dials while offline: swap in a counting listener for 2 s.
    listener.stop().await;
    let counter = CountingListener::start_on_port(port).await;
    tokio::time::sleep(Duration::from_secs(2)).await;
    assert_eq!(counter.accepts(), 0, "no dials while the network is down");
    // Back online: a dial within milliseconds.
    let started = Instant::now();
    client.set_network_online(true);
    while counter.accepts() == 0 {
        assert!(
            started.elapsed() < Duration::from_millis(500),
            "no dial within 500 ms of coming online"
        );
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    drop(counter);
    client.shutdown();
    core.shutdown().await;
}

/// `refresh()` resolves (capped) while an engine is down, and the engine
/// reconnects after it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn refresh_resolves_and_reconnects() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let mut listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    // Pull-to-refresh while connected: bounded by the cap.
    let cap = Duration::from_secs(2);
    tokio::time::timeout(cap, client.refresh())
        .await
        .expect("refresh resolves within the cap");
    // Down: refresh resolves quickly (the engine reports failure).
    listener.stop().await;
    wait_for_view(&client, &record.engine_id, "reconnecting", |view| {
        view.state == EngineState::Reconnecting
    })
    .await;
    let started = Instant::now();
    tokio::time::timeout(cap, client.refresh())
        .await
        .expect("refresh resolves while down");
    assert!(started.elapsed() < cap);
    client.shutdown();
    core.shutdown().await;
}

/// Scoped ids round-trip through `scope`/`resolve`; a feed watch delivers
/// generation-tagged items; a transcript connection streams
/// `WatchDocMessages`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn scoped_ids_feed_watches_and_transcript_connections_work() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    core.doc_host
        .open("same-chat")
        .unwrap()
        .write_user_message("m1", "hello", 123, None)
        .unwrap();
    let listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    // Scope/resolve round trip.
    let scoped = client.scope(&record.engine_id, "same-chat");
    let (handle, raw_id) = client.resolve(&scoped).unwrap();
    assert_eq!(raw_id, "same-chat");
    assert_eq!(handle.engine_id(), record.engine_id);
    // A feed watch delivers items tagged with the live generation.
    let generation = client
        .engine(&record.engine_id)
        .unwrap()
        .diagnostics
        .generation;
    let mut watch = handle.watch_feed("WatchChats", serde_json::json!({}));
    let item = tokio::time::timeout(Duration::from_secs(5), watch.recv())
        .await
        .expect("feed watch delivers")
        .expect("feed watch stays open");
    assert_eq!(item.generation, generation);
    // A transcript connection streams WatchDocMessages for the scoped id.
    let connection = handle.transcript_connection().await.unwrap();
    let mut transcript = connection.watch_doc_messages(&scoped).await.unwrap();
    let frame = tokio::time::timeout(Duration::from_secs(5), transcript.recv())
        .await
        .expect("transcript frame arrives")
        .expect("transcript stream stays open");
    let text = serde_json::to_string(&frame).unwrap();
    assert!(
        text.contains("hello"),
        "the reset carries the engine's message"
    );
    drop(transcript);
    connection.stop();
    client.shutdown();
    drop(listener);
    core.shutdown().await;
}

/// `rename_engine` renames the engine's own device row through the calls
/// connection, and the devices feed mirrors it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn rename_engine_reaches_the_engine() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    client
        .rename_engine(&record.engine_id, "  Studio Mac  ")
        .await
        .expect("rename reaches the engine");
    wait_for_fleet(&client, "device row renamed", |fleet| {
        fleet
            .engine(&record.engine_id)
            .map(|engine| {
                engine
                    .rows
                    .devices
                    .iter()
                    .any(|device| device.id == core.device_id && device.name == "Studio Mac")
            })
            .unwrap_or(false)
    })
    .await;
    client.shutdown();
    drop(listener);
    core.shutdown().await;
}

/// `revoke_own_session` revokes this device's session on the engine
/// (best-effort): the session shows up revoked in the engine's store and
/// in its `GetRemoteAccess` snapshot, and a fresh authenticated connect
/// with the old credential is refused (ui-parity #140's Rust acceptance).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn revoke_own_session_reaches_the_engine() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    let client = test_client(data_dir.path(), Vec::new(), recorder);
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    client
        .revoke_own_session(&record.engine_id)
        .await
        .expect("revoke is best-effort");
    // The pairing store itself records the revocation.
    let store = roboco_engine::pairing::PairingStore::open(engine_dir.path()).unwrap();
    let sessions = store.list_sessions().unwrap();
    assert!(
        sessions
            .iter()
            .any(|session| session.id == record.session_id && session.revoked_at.is_some()),
        "the engine revoked this device's session"
    );
    // The engine's own view of it (the `GetRemoteAccess` snapshot the
    // settings page shows): the same session, with `revokedAt` set.
    let handle = client.engine_handle(&record.engine_id).unwrap();
    let snapshot = handle
        .call(roboco_rpc::methods::GET_REMOTE_ACCESS, serde_json::json!({}))
        .await
        .unwrap();
    assert!(
        snapshot["sessions"]
            .as_array()
            .unwrap()
            .iter()
            .any(|session| {
                session["id"] == record.session_id.as_str() && !session["revokedAt"].is_null()
            }),
        "the remote access snapshot shows the session revoked: {snapshot}"
    );
    // The revoked credential can no longer open a fresh authenticated
    // connection (an already-open socket keeps working until its next
    // dial — the ticket's note).
    let ws_url = record.base_url.replacen("http", "ws", 1);
    assert!(
        roboco_rpc::connect_ws_authenticated(&ws_url, &record.credential)
            .await
            .is_err(),
        "a fresh connect with the revoked credential must fail"
    );
    client.shutdown();
    drop(listener);
    core.shutdown().await;
}

/// A `WatchDocMessages` reset larger than 64 MiB is received intact over
/// `connect_ws_authenticated` (the 256 MiB client ceiling), while a
/// `ListHarnesses` call on the calls connection still returns within 2 s.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "streams a 70 MiB reset; run with --ignored large_reset"]
async fn large_reset_streams_intact_while_calls_stay_fast() {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    let big = "x".repeat(70 * 1024 * 1024);
    core.doc_host
        .open("same-chat")
        .unwrap()
        .write_user_message("big-message", &big, 123, None)
        .unwrap();
    let listener = serve(&core, engine_dir.path()).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let recorder = Arc::new(Recorder::default());
    // Production lag budgets: the reset legitimately queues the lag probe's
    // reply behind its own bytes (that is the check's design), so the
    // shrunk liveness timings would close the connection mid-transfer.
    let mut timings = support::test_timings();
    timings.lag_every = Duration::from_secs(10);
    timings.lag_timeout = Duration::from_secs(15);
    let client = roboco_mobile_client::Client::new_with_timings(
        support::test_config(data_dir.path()),
        Vec::new(),
        recorder,
        timings,
    )
    .unwrap();
    let record = client.pair_engine(&url, None).await.unwrap();
    wait_for_view(&client, &record.engine_id, "connected", |view| {
        view.state == EngineState::Connected
    })
    .await;
    let handle = client.engine_handle(&record.engine_id).unwrap();
    let scoped = client.scope(&record.engine_id, "same-chat");
    let connection = handle.transcript_connection().await.unwrap();
    let mut transcript = connection.watch_doc_messages(&scoped).await.unwrap();
    // While the reset streams, a call on the calls connection must return
    // within 2 s.
    let calls = tokio::spawn({
        let handle = handle.clone();
        async move {
            let started = Instant::now();
            let result = handle.call("ListHarnesses", serde_json::json!({})).await;
            (result.is_ok(), started.elapsed())
        }
    });
    let mut received = 0usize;
    let deadline = Duration::from_secs(120);
    while received < big.len() {
        let frame = tokio::time::timeout(deadline, transcript.recv())
            .await
            .expect("transcript frames keep arriving")
            .expect("the transcript stream stays open");
        if let Some(entries) = frame.get("reset").and_then(|value| value.as_array()) {
            for entry in entries {
                if entry.get("id").and_then(|id| id.as_str()) == Some("big-message") {
                    received = entry
                        .get("parts")
                        .and_then(|parts| parts.as_array())
                        .and_then(|parts| parts.first())
                        .and_then(|part| part.get("text"))
                        .and_then(|text| text.as_str())
                        .map(str::len)
                        .unwrap_or(0);
                }
            }
        }
    }
    assert_eq!(received, big.len(), "the reset arrived intact");
    let (calls_ok, calls_elapsed) = calls.await.unwrap();
    assert!(calls_ok, "the calls connection answered during the reset");
    assert!(
        calls_elapsed < Duration::from_secs(2),
        "ListHarnesses took {calls_elapsed:?} while the transcript streamed"
    );
    drop(transcript);
    connection.stop();
    client.shutdown();
    drop(listener);
    core.shutdown().await;
}
