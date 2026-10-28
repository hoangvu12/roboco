//! Real-engine agent-account tests (android-next#05): every frame comes
//! from an in-process `EngineCore` served over loopback and paired
//! through the client — no protocol mocks. A fixture Claude home
//! (`CLAUDE_CONFIG_DIR`, set before the engine assembles) stands in for
//! the CLI's logins, exactly as `crates/engine/tests/
//! m5c_accounts_uploads_titles.rs` does engine-side: two logins list
//! (the live one active), a switch flips them, a forget drops the saved
//! slot, an offline engine answers `HostUnavailable`, and a forced list
//! through a frozen link is cut by the 45 s deadline (shrunken in
//! `support::test_timings` so the stall proxy test runs in
//! milliseconds). `force_usage` is only ever asked over the frozen link:
//! a live forced list would probe the provider over the network, which
//! no test may do.

mod support;

use std::future::Future;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use roboco_mobile_client::Client;
use roboco_mobile_client::EngineState;
use roboco_mobile_client::ClientError;

use support::{Recorder, core as engine_core, serve, test_client, wait_for_view};

static MOCK_ENV: OnceLock<Mutex<()>> = OnceLock::new();

fn mock_env() -> &'static Mutex<()> {
    MOCK_ENV.get_or_init(|| Mutex::new(()))
}

fn set_env(name: &str, value: Option<&str>) {
    // SAFETY: every fixture-env test in this binary holds `MOCK_ENV`, so
    // no other thread reads or writes the environment while this runs.
    unsafe {
        match value {
            Some(value) => std::env::set_var(name, value),
            None => std::env::remove_var(name),
        }
    }
}

/// Run `f` with the Claude home pointed at `fixture`, restoring the
/// previous value after. The engine inside `f` must be assembled after
/// the set (its `AgentAccountsConfig::detect` reads the env once).
async fn with_claude_home<T>(fixture: &std::path::Path, f: impl Future<Output = T>) -> T {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let previous = std::env::var("CLAUDE_CONFIG_DIR").ok();
    set_env("CLAUDE_CONFIG_DIR", Some(&fixture.to_string_lossy()));
    let value = f.await;
    set_env("CLAUDE_CONFIG_DIR", previous.as_deref());
    value
}

/// Run `f` holding the env lock without touching any variable — so a
/// parallel fixture test's `CLAUDE_CONFIG_DIR` never leaks into this
/// test's engine assembly.
async fn with_env_lock<T>(f: impl Future<Output = T>) -> T {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    f.await
}

/// One Claude login: `~/.claude.json` plus `.credentials.json` under the
/// fixture home, with a far-future expiry (a usage probe must never try
/// to rotate it). Mirrors the engine test's `write_claude_login`
/// (including the `projects` map and rate-limit tier the identity merge
/// and plan label read).
fn write_claude_login(home: &std::path::Path, email: &str, uuid: &str, token: &str) {
    std::fs::create_dir_all(home).expect("claude dir");
    std::fs::write(
        home.join(".claude.json"),
        serde_json::json!({
            "oauthAccount": {
                "accountUuid": uuid,
                "emailAddress": email,
                "displayName": "Test User",
                "organizationName": "Test Org",
                "organizationType": "claude_max",
                "organizationRateLimitTier": "default_claude_max_20x",
            },
            "userID": format!("user-{uuid}"),
            "projects": { "/keep/me": { "history": [] } },
        })
        .to_string(),
    )
    .expect("claude config");
    std::fs::write(
        home.join(".credentials.json"),
        serde_json::json!({
            "claudeAiOauth": {
                "accessToken": token,
                "refreshToken": format!("refresh-{token}"),
                "expiresAt": 4_102_444_800_000i64,
            }
        })
        .to_string(),
    )
    .expect("claude creds");
}

/// The paired engine's scoped device id, once the derived workspace
/// lists it (the workspace store re-derives after the link comes up).
async fn engine_device_id(client: &Client) -> String {
    tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            if let Some(device) = client.workspace().execution_devices().first() {
                return device.id.clone();
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("the engine device never listed")
}

/// One engine, one paired client, and the paired engine's scoped device
/// id — the shape the platform passes to the accounts calls.
struct AccountsRig {
    _engine_dir: tempfile::TempDir,
    /// The client's data dir: kept alive with the client (its cache
    /// writes land here).
    _data_dir: tempfile::TempDir,
    core: roboco_engine::EngineCore,
    listener: Option<roboco_engine::EngineListener>,
    client: Arc<Client>,
    engine_id: String,
    device_id: String,
}

impl AccountsRig {
    async fn start() -> AccountsRig {
        let engine_dir = tempfile::tempdir().unwrap();
        let data_dir = tempfile::tempdir().unwrap();
        let core = engine_core(engine_dir.path());
        let listener = serve(&core, engine_dir.path()).await;
        let url = support::pairing_url(&listener, engine_dir.path(), None);
        let client = test_client(data_dir.path(), Vec::new(), Arc::new(Recorder::default()));
        let record = client.pair_engine(&url, None).await.expect("pairing");
        wait_for_view(&client, &record.engine_id, "connected", |view| {
            view.state == EngineState::Connected
        })
        .await;
        let device_id = engine_device_id(&client).await;
        Self {
            _engine_dir: engine_dir,
            _data_dir: data_dir,
            core,
            listener: Some(listener),
            client,
            engine_id: record.engine_id,
            device_id,
        }
    }

    /// Stop the engine's listener and wait until the client noticed —
    /// the engine core stays alive.
    async fn stop_listener(&mut self) {
        if let Some(mut listener) = self.listener.take() {
            listener.stop().await;
        }
        wait_for_view(&self.client, &self.engine_id, "offline", |view| {
            view.state != EngineState::Connected
        })
        .await;
    }

    /// The active account of `harness`, panicking with the emails when
    /// there is not exactly one.
    fn active(&self, harness: roboco_proto::HarnessId) -> roboco_proto::AgentAccount {
        let snapshot = self
            .client
            .cached_agent_accounts(&self.device_id)
            .expect("a cached snapshot");
        let of_harness: Vec<&roboco_proto::AgentAccount> = snapshot
            .accounts
            .iter()
            .filter(|a| a.harness == harness)
            .collect();
        let active: Vec<_> = of_harness.iter().filter(|a| a.active).collect();
        assert_eq!(
            active.len(),
            1,
            "exactly one active account: {:?}",
            emails(&snapshot, harness)
        );
        (*active[0]).clone()
    }
}

/// The harness's (email, active) pairs, sorted for order-insensitive
/// assertions.
fn emails(
    snapshot: &roboco_proto::AgentAccountsSnapshot,
    harness: roboco_proto::HarnessId,
) -> Vec<(String, bool)> {
    let mut pairs: Vec<(String, bool)> = snapshot
        .accounts
        .iter()
        .filter(|a| a.harness == harness)
        .map(|a| (a.email.clone().unwrap_or_default(), a.active))
        .collect();
    pairs.sort();
    pairs
}

/// List, activate and forget over the paired link, plus the cache every
/// success refreshes: the live login lists (and is auto-snapshotted), a
/// second login replaces it live (the first becomes a saved, inactive
/// slot), a switch flips them and the reply agrees, a forget drops the
/// saved slot, and the cache always mirrors the newest reply.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn list_activate_forget_and_the_cache() {
    let fixture = tempfile::tempdir().unwrap();
    write_claude_login(fixture.path(), "alice@example.com", "uuid-alice", "token-alice");
    with_claude_home(fixture.path(), async {
        let rig = AccountsRig::start().await;
        // Alice is live: the first list detects her and auto-snapshots
        // her into a slot.
        let first = rig
            .client
            .list_agent_accounts(&rig.device_id, false)
            .await
            .expect("first list");
        assert_eq!(
            emails(&first, roboco_proto::HarnessId::ClaudeCode),
            vec![("alice@example.com".to_string(), true)],
        );
        let alice = first
            .accounts
            .iter()
            .find(|a| a.email.as_deref() == Some("alice@example.com"))
            .expect("alice listed")
            .id
            .clone();

        // Bob logs in via the CLI (the live files replaced): the next
        // list snapshots him and shows Alice as a saved, inactive slot.
        write_claude_login(fixture.path(), "bob@example.com", "uuid-bob", "token-bob");
        let snapshot = rig
            .client
            .list_agent_accounts(&rig.device_id, false)
            .await
            .expect("second list");
        assert_eq!(
            emails(&snapshot, roboco_proto::HarnessId::ClaudeCode),
            vec![
                ("alice@example.com".to_string(), false),
                ("bob@example.com".to_string(), true),
            ]
        );
        assert!(
            snapshot.accounts.iter().all(|a| a.switchable),
            "file-backed logins are switchable"
        );
        // The cache mirrors the newest reply.
        assert_eq!(
            rig.client
                .cached_agent_accounts(&rig.device_id)
                .expect("cached"),
            snapshot
        );
        // A raw engine device id resolves to the same engine — and the
        // same cached entry (every `device_id` shape shares one cache
        // slot, keyed by the resolved engine).
        let raw_device_id = rig.core.doc_host.device_id().to_string();
        assert!(rig.client.cached_agent_accounts(&raw_device_id).is_some());

        // Switch to Alice: her slot goes active, Bob's goes inactive, the
        // reply and the cache agree.
        let switched = rig
            .client
            .activate_agent_account(&rig.device_id, "claude-code", &alice)
            .await
            .expect("activate");
        assert_eq!(
            emails(&switched, roboco_proto::HarnessId::ClaudeCode),
            vec![
                ("alice@example.com".to_string(), true),
                ("bob@example.com".to_string(), false),
            ]
        );
        assert_eq!(
            emails(
                &rig.client
                    .cached_agent_accounts(&rig.device_id)
                    .expect("cached after activate"),
                roboco_proto::HarnessId::ClaudeCode
            ),
            emails(&switched, roboco_proto::HarnessId::ClaudeCode)
        );

        // Forget the now-inactive Bob: one account left, Alice still in
        // use.
        let bob = rig.active(roboco_proto::HarnessId::ClaudeCode);
        assert_eq!(bob.email.as_deref(), Some("alice@example.com"));
        let bob_id = switched
            .accounts
            .iter()
            .find(|a| a.email.as_deref() == Some("bob@example.com"))
            .expect("bob listed")
            .id
            .clone();
        let after_forget = rig
            .client
            .forget_agent_account(&rig.device_id, "claude-code", &bob_id)
            .await
            .expect("forget");
        assert_eq!(
            emails(&after_forget, roboco_proto::HarnessId::ClaudeCode),
            vec![("alice@example.com".to_string(), true)]
        );

        rig.client.shutdown();
    })
    .await;
}

/// An engine whose listener is stopped answers `HostUnavailable` (the
/// chip hides; the page keeps what it has and says the device is
/// offline) — never a hang, never a panic.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_offline_engine_answers_host_unavailable() {
    with_env_lock(async {
        let mut rig = AccountsRig::start().await;
        // Seed the cache so the offline answer is observed against a known
        // engine, then stop the listener and ask again.
        rig.client
            .list_agent_accounts(&rig.device_id, false)
            .await
            .expect("the online list");
        rig.stop_listener().await;
        match rig
            .client
            .list_agent_accounts(&rig.device_id, false)
            .await
            .expect_err("an offline engine cannot answer")
        {
            ClientError::HostUnavailable { message } => {
                assert!(!message.is_empty());
            }
            other => panic!("expected HostUnavailable, got {other:?}"),
        }
        rig.client.shutdown();
    })
    .await;
}

/// A forced list over a frozen link is cut by the forced-usage deadline
/// (PR #578's 45 s, shrunken to 400 ms in the test timings): a hung
/// probe pins no caller. The freeze means the engine never even receives
/// the call, so no provider probe runs.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_forced_list_through_a_frozen_link_hits_the_deadline() {
    with_env_lock(async {
        let engine_dir = tempfile::tempdir().unwrap();
        let data_dir = tempfile::tempdir().unwrap();
        let core = engine_core(engine_dir.path());
        let direct = serve(&core, engine_dir.path()).await;
        let proxy = support::stall_proxy::StallProxy::start(direct.address).await;
        let url = format!(
            "http://{}/pair#token={}",
            proxy.address,
            roboco_engine::pairing::PairingStore::open(engine_dir.path())
                .unwrap()
                .create_code("test", 300)
                .unwrap()
                .credential
        );
        let client = test_client(data_dir.path(), Vec::new(), Arc::new(Recorder::default()));
        let record = client.pair_engine(&url, None).await.expect("pairing");
        wait_for_view(&client, &record.engine_id, "connected", |view| {
            view.state == EngineState::Connected
        })
        .await;
        let device_id = engine_device_id(&client).await;

        proxy.pause();
        let started = std::time::Instant::now();
        let error = client
            .list_agent_accounts(&device_id, true)
            .await
            .expect_err("the frozen forced list must time out");
        match error {
            ClientError::HostUnavailable { message } => {
                assert!(message.contains("timed out"), "the deadline error: {message}");
            }
            other => panic!("expected HostUnavailable, got {other:?}"),
        }
        // Well under the real 45 s — the shrunken deadline fired, not the
        // engine (which never answered).
        assert!(
            started.elapsed() < Duration::from_secs(5),
            "the shrunken deadline fired in {:?}",
            started.elapsed()
        );
        proxy.resume();
        client.shutdown();
    })
    .await;
}

/// Wave-10 34 + 36 (upstream #966's `device_login_*` and the callback
/// relay): a phone starts a computer's sign-in over the paired link.
/// Claude's login is engine-served — a loopback PKCE listener on the
/// computer, no CLI and no network until the browser lands — so the phone
/// listens on that same port of its own loopback to relay the redirect.
/// In a test the "phone" and the computer share one loopback, so the port
/// is genuinely taken: the start answers with the busy-port message and
/// cancels the sign-in on the computer (36's "a busy phone port shows the
/// error"). Polling an unknown attempt says it expired; an agent Roboco
/// doesn't sign in to is refused.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_phone_starts_a_computers_sign_in_and_a_busy_port_says_so() {
    with_env_lock(async {
        let rig = AccountsRig::start().await;
        match rig
            .client
            .start_agent_login(&rig.device_id, "claude-code", None)
            .await
        {
            Err(ClientError::HostError { message }) => {
                assert!(
                    message.starts_with("Port ") && message.contains("is in use on this phone"),
                    "the busy-port message: {message}"
                );
            }
            other => panic!("the shared loopback port is busy: {other:?}"),
        }
        match rig.client.poll_agent_login(&rig.device_id, "no-such-login").await {
            Err(ClientError::HostError { message }) => {
                assert!(message.contains("expired"), "{message}");
            }
            other => panic!("an unknown attempt has expired: {other:?}"),
        }
        assert!(
            rig.client
                .start_agent_login(&rig.device_id, "mock", None)
                .await
                .is_err()
        );
        rig.client.shutdown();
    })
    .await;
}
