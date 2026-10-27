//! Real-engine notification driver tests (ticket 28): one **real**
//! in-process engine on a loopback port per test, the shared decision
//! rule's verdict delivered through a recorded [`NotifyListener`] — no
//! protocol mocks anywhere (spec "Testing Decisions").
//!
//! The pure rule (baselines, the catch-up arithmetic, the link watch's
//! grace windows, the own-send suppression) is unit-tested in
//! `src/notify.rs`; these tests prove the whole stack: pairing seeds
//! silently, a run through the real mock harness emits Done / Input /
//! Failed, side chats never emit, the link watch fires on a dropped
//! listener and restores on the reconnect, and a completion that landed
//! during a socket gap arrives after the reconnect.
//!
//! Determinism: the mock harness streams instantly by default, so a run
//! can finish between two driver passes; every run here paces itself with
//! `ROBOCO_MOCK_DELAY_MS` so the Working baseline always lands first.
//! Env knobs are process-global: every knob test holds [`MOCK_ENV`], so a
//! knob never leaks into another test's runs under `cargo test` (nextest
//! isolates per process anyway; `--test-threads=1` serialises fully).

mod support;

use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use roboco_mobile_client::timings::{NotifyTimings, Timings};
use roboco_mobile_client::{
    Client, ClientConfig, EngineState, NotifyListener, NullListener, RunActivity, SendOutcome,
    SessionEvent,
};
use roboco_proto::view::NotifyKind;
use support::{pairing_url, serve_on_port};

/// Serializes every mock-engine test in this binary (env knobs are
/// process-global).
static MOCK_ENV: OnceLock<Mutex<()>> = OnceLock::new();

fn mock_env() -> &'static Mutex<()> {
    MOCK_ENV.get_or_init(|| Mutex::new(()))
}

fn set_env(name: &str, value: Option<&str>) {
    // SAFETY: every mock-engine test in this binary holds `MOCK_ENV`, so no
    // other thread reads or writes the environment while this runs.
    unsafe {
        match value {
            Some(value) => std::env::set_var(name, value),
            None => std::env::remove_var(name),
        }
    }
}

/// Run `f` while `vars` are set, restoring the previous values after.
async fn with_mock_env<T>(vars: &[(&str, &str)], f: impl Future<Output = T>) -> T {
    let _guard = mock_env().lock().unwrap_or_else(|p| p.into_inner());
    let previous: Vec<(&str, Option<String>)> = vars
        .iter()
        .map(|(name, _)| (*name, std::env::var(name).ok()))
        .collect();
    for (name, value) in vars {
        set_env(name, Some(value));
    }
    let result = f.await;
    for (name, value) in previous {
        set_env(name, value.as_deref());
    }
    result
}

// ── the recording listener ────────────────────────────────────────────────

#[derive(Default)]
struct NotifyRecorder {
    events: Mutex<Vec<SessionEvent>>,
    resolved: Mutex<Vec<String>>,
    link_lost: Mutex<Vec<String>>,
    link_restored: Mutex<Vec<String>>,
    activity: Mutex<Vec<RunActivity>>,
}

impl NotifyRecorder {
    fn shared() -> Arc<Self> {
        Arc::new(Self::default())
    }

    fn session_events(&self) -> Vec<SessionEvent> {
        self.events.lock().unwrap().clone()
    }

    fn last_activity(&self) -> RunActivity {
        self.activity
            .lock()
            .unwrap()
            .last()
            .cloned()
            .unwrap_or_default()
    }

    /// Wait until `predicate` holds over the recorded session events.
    async fn wait_for(&self, label: &str, predicate: impl Fn(&[SessionEvent]) -> bool) {
        let deadline = Instant::now() + Duration::from_secs(30);
        while !predicate(&self.session_events()) {
            assert!(Instant::now() < deadline, "timed out waiting for: {label}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// Wait until the link watch fired once for the engine.
    async fn wait_for_link(&self, slot: &Mutex<Vec<String>>, engine_id: &str, label: &str) {
        let deadline = Instant::now() + Duration::from_secs(30);
        while !slot.lock().unwrap().iter().any(|id| id == engine_id) {
            assert!(Instant::now() < deadline, "timed out waiting for {label}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
}

impl NotifyListener for NotifyRecorder {
    fn session_event(&self, event: SessionEvent) {
        self.events.lock().unwrap().push(event);
    }

    fn resolved(&self, engine_id: String, chat_id: String) {
        self.resolved
            .lock()
            .unwrap()
            .push(format!("{engine_id}:{chat_id}"));
    }

    fn link_lost(&self, engine_id: String, _engine_name: String) {
        self.link_lost.lock().unwrap().push(engine_id);
    }

    fn link_restored(&self, engine_id: String) {
        self.link_restored.lock().unwrap().push(engine_id);
    }

    fn run_activity(&self, activity: RunActivity) {
        self.activity.lock().unwrap().push(activity);
    }
}

// ── the rig ───────────────────────────────────────────────────────────────

/// A paired, connected client with the notification driver recording, its
/// engine core and a restartable listener on a fixed port.
struct Rig {
    client: Arc<Client>,
    core: roboco_engine::EngineCore,
    engine_id: String,
    /// The engine's own raw chat id ("same-chat").
    raw_chat: String,
    #[allow(dead_code)]
    engine_dir: tempfile::TempDir,
    #[allow(dead_code)]
    data_dir: tempfile::TempDir,
    port: u16,
    listener: Option<roboco_engine::EngineListener>,
    notify: Arc<NotifyRecorder>,
}

impl Drop for Rig {
    fn drop(&mut self) {
        self.client.shutdown();
        self.listener.take();
    }
}

impl Rig {
    /// The scoped chat id (the deep link's id).
    fn chat_id(&self) -> String {
        self.client.scope(&self.engine_id, &self.raw_chat)
    }

    fn stop_listener(&mut self) {
        self.listener.take();
    }

    async fn restart_listener(&mut self) {
        if self.listener.is_some() {
            return;
        }
        let core = &self.core;
        let dir = self.engine_dir.path().to_owned();
        self.listener = Some(serve_on_port(core, &dir, self.port).await);
    }

    /// Wait until the engine view is `Connected`.
    async fn wait_connected(&self) {
        let deadline = Instant::now() + Duration::from_secs(30);
        while !self
            .client
            .engine(&self.engine_id)
            .is_some_and(|view| view.state == EngineState::Connected)
        {
            assert!(Instant::now() < deadline, "the engine never reconnected");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// Queue a run straight on the engine (the desktop's send path: NOT
    /// this phone's pending send, so its completion chimes).
    fn engine_side_run(&self, raw_chat: &str, prompt: &str) {
        self.core
            .doc_host
            .queue_command(
                raw_chat,
                roboco_doc::SessionCommandPayload::Run {
                    request: run_request(prompt),
                    message_id: uuid::Uuid::new_v4().to_string(),
                },
            )
            .unwrap();
    }

    /// Wait until the activity shows a working run for `chat_id`.
    async fn wait_working(&self, chat_id: &str) {
        let deadline = Instant::now() + Duration::from_secs(30);
        while !self
            .notify
            .last_activity()
            .working
            .iter()
            .any(|run| run.chat_id == chat_id)
        {
            assert!(Instant::now() < deadline, "the run never became working");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
}

/// Fast supervisor timings plus a shrunken notification set: the link
/// grace runs in ~300 ms, the startup quiet in ~400 ms, the send window
/// in 2 s and the tick in 50 ms.
fn notify_test_timings() -> Timings {
    let mut timings = support::test_timings();
    timings.notify = NotifyTimings {
        link_grace: Duration::from_millis(300),
        startup_quiet: Duration::from_millis(400),
        send_window: Duration::from_millis(2_000),
        tick: Duration::from_millis(50),
    };
    timings
}

/// Pair a fresh engine (mock harness) on a fixed port and start the
/// notification driver on the client.
async fn notify_rig() -> Rig {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = support::core(engine_dir.path());
    // A fixed port so the listener can be stopped and re-served.
    let port = {
        let probe = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        probe.local_addr().unwrap().port()
    };
    let listener = serve_on_port(&core, engine_dir.path(), port).await;
    let url = pairing_url(&listener, engine_dir.path(), None);
    let client = Client::new_with_timings(
        ClientConfig {
            data_dir: data_dir.path().to_owned(),
            device_name: "Pixel 9".into(),
            platform: "android".into(),
            app_version: env!("CARGO_PKG_VERSION").into(),
            show_mock_harness: true,
            native_lib_dir: None,
        },
        Vec::new(),
        Vec::new(),
        Arc::new(NullListener),
        notify_test_timings(),
    )
    .unwrap();
    let record = tokio::time::timeout(Duration::from_secs(10), client.pair_engine(&url, None))
        .await
        .expect("pairing completes")
        .expect("pairing succeeds");
    let notify = NotifyRecorder::shared();
    client.set_notify_listener(notify.clone());
    // Connected, rows known, driver armed.
    let deadline = Instant::now() + Duration::from_secs(30);
    while !client
        .engine(&record.engine_id)
        .is_some_and(|view| view.state == EngineState::Connected)
    {
        assert!(Instant::now() < deadline, "the engine never connected");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    Rig {
        client,
        core,
        engine_id: record.engine_id,
        raw_chat: "same-chat".into(),
        engine_dir,
        data_dir,
        port,
        listener: Some(listener),
        notify,
    }
}

/// Send a prompt through the session handle, the way the app does.
async fn phone_send(rig: &Rig, prompt: &str) {
    let handle = rig.client.open_session(&rig.chat_id()).unwrap();
    let outcome = handle
        .send(roboco_mobile_client::session::SendRequest::text(prompt))
        .await
        .unwrap();
    assert!(
        matches!(
            outcome,
            SendOutcome::Started { .. } | SendOutcome::Steered { .. }
        ),
        "the send started a turn: {outcome:?}"
    );
}

fn run_request(prompt: &str) -> roboco_proto::RunRequest {
    roboco_proto::RunRequest {
        prompt: prompt.into(),
        harness: None,
        model: None,
        reasoning: None,
        model_options: Default::default(),
        cwd: "/tmp".into(),
        sandbox: roboco_proto::SandboxLevel::WorkspaceWrite,
        auto_approve: false,
        attachments: Vec::new(),
        worktree: None,
        mcp: None,
        resume: None,
        provider_fork: None,
        resume_fallback_history: None,
    }
}

// ── tests ─────────────────────────────────────────────────────────────────

/// Pairing and the first live frame seed silently: no session event for a
/// chat with no stored baseline, and the activity starts at empty.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pairing_and_boot_seed_silently() {
    let rig = notify_rig().await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(
        rig.notify.session_events().is_empty(),
        "boot must be silent: {:?}",
        rig.notify.session_events()
    );
    assert_eq!(rig.notify.last_activity(), RunActivity::default());
}

/// A normal run (started on the engine — the desktop's send): Done for
/// the top-level chat, Resolved once it is marked seen.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_normal_run_emits_done_and_resolves_when_seen() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "150")], async {
        let rig = notify_rig().await;
        // Mark the chat seen first, so the completion is unseen afterwards.
        rig.client.mark_seen(&rig.chat_id());
        rig.engine_side_run(&rig.raw_chat, "engine-side hello");
        rig.notify
            .wait_for("the Done event", |events| {
                events.len() == 1
                    && events[0].chat_id == rig.chat_id()
                    && events[0].kind == NotifyKind::Done
            })
            .await;
        let event = &rig.notify.session_events()[0];
        assert_eq!(event.engine_id, rig.engine_id);
        assert!(!event.engine_name.is_empty());
        assert_eq!(event.chat_title, "New session");
        // Seen → Resolved (the platform cancels the notification).
        rig.client.mark_seen(&rig.chat_id());
        let deadline = Instant::now() + Duration::from_secs(30);
        while rig.notify.resolved.lock().unwrap().is_empty() {
            assert!(Instant::now() < deadline, "the Done never resolved");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert_eq!(
            rig.notify.resolved.lock().unwrap()[0],
            format!("{}:{}", rig.engine_id, rig.chat_id())
        );
        // Exactly one event the whole time: nothing replays.
        assert_eq!(rig.notify.session_events().len(), 1);
    })
    .await;
}

/// The chat's own send consumes its completion: no Done for the run this
/// phone started (the acceptance "emits no Done for the phone's own
/// pending send") — deterministically, whether or not the driver observed
/// the run's Working phase.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_own_pending_send_consumes_the_completion() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "150")], async {
        let rig = notify_rig().await;
        phone_send(&rig, "from this phone").await;
        // The paced run finishes well inside the 2 s send window.
        let deadline = Instant::now() + Duration::from_secs(10);
        while rig.notify.last_activity().working.is_empty() {
            assert!(Instant::now() < deadline, "the run never started");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        while !rig.notify.last_activity().working.is_empty() {
            assert!(Instant::now() < deadline, "the run never ended");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert!(
            rig.notify.session_events().is_empty(),
            "no Done for the phone's own send: {:?}",
            rig.notify.session_events()
        );
        // But the run WAS attributed to this phone while it worked.
        assert!(rig.notify.activity.lock().unwrap().iter().any(|a| {
            a.working.iter().any(|run| run.from_this_device)
        }));
    })
    .await;
}

/// A side chat (parentChatId) never emits, even though its baseline moves
/// and the run was started on the engine.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn side_chats_never_emit() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "150")], async {
        let rig = notify_rig().await;
        rig.core
            .workspace
            .create_chat_with_parent(
                "side-chat",
                None,
                Some(&rig.core.device_id),
                None,
                None,
                Some("same-chat".to_owned()),
                None,
            )
            .unwrap();
        let side = rig.client.scope(&rig.engine_id, "side-chat");
        let deadline = Instant::now() + Duration::from_secs(30);
        while rig.client.workspace().session(&side).is_none() {
            assert!(Instant::now() < deadline, "the side chat never appeared");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        rig.engine_side_run("side-chat", "a side run");
        // The side chat works and completes; no event ever fires.
        let deadline = Instant::now() + Duration::from_secs(10);
        while rig.notify.last_activity().working.iter().any(|run| run.chat_id == side) {
            assert!(Instant::now() < deadline, "the side run never ended");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert!(
            rig.notify.session_events().is_empty(),
            "side chats never emit: {:?}",
            rig.notify.session_events()
        );
    })
    .await;
}

/// `ROBOCO_MOCK_ERROR=1`: the run fails → Failed (Attention).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_run_error_emits_failed() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "150"), ("ROBOCO_MOCK_ERROR", "1")], async {
        let rig = notify_rig().await;
        rig.engine_side_run(&rig.raw_chat, "this one will fail");
        rig.notify
            .wait_for("the Failed event", |events| {
                events.len() == 1 && events[0].kind == NotifyKind::Attention
            })
            .await;
    })
    .await;
}

/// `ROBOCO_MOCK_QUESTION=1`: the run asks a question → Input (Request).
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_question_emits_input() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "150"), ("ROBOCO_MOCK_QUESTION", "1")], async {
        let rig = notify_rig().await;
        rig.engine_side_run(&rig.raw_chat, "ask me something");
        rig.notify
            .wait_for("the Input event", |events| {
                events.len() == 1 && events[0].kind == NotifyKind::Request
            })
            .await;
    })
    .await;
}

/// The engine listener drops while a run is working: LinkLost after the
/// grace (never inside STARTUP_QUIET), once; LinkRestored on the
/// reconnect.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn link_lost_fires_once_and_restores_on_reconnect() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "20000")], async {
        let mut rig = notify_rig().await;
        // A long working run on the engine.
        rig.engine_side_run(&rig.raw_chat, "a long run");
        rig.wait_working(&rig.chat_id()).await;
        // The socket dies mid-run.
        rig.stop_listener();
        rig.notify
            .wait_for_link(&rig.notify.link_lost, &rig.engine_id, "LinkLost")
            .await;
        // Exactly once.
        tokio::time::sleep(Duration::from_millis(600)).await;
        assert_eq!(rig.notify.link_lost.lock().unwrap().len(), 1);
        // Reconnect → LinkRestored (the platform cancels the lost one).
        rig.restart_listener().await;
        rig.wait_connected().await;
        rig.notify
            .wait_for_link(&rig.notify.link_restored, &rig.engine_id, "LinkRestored")
            .await;
    })
    .await;
}

/// The engine listener drops with nothing working: no LinkLost at all.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn no_link_lost_without_a_working_run() {
    let mut rig = notify_rig().await;
    rig.stop_listener();
    tokio::time::sleep(Duration::from_millis(900)).await;
    assert!(
        rig.notify.link_lost.lock().unwrap().is_empty(),
        "no run was working at the drop"
    );
}

/// A completion that lands while the socket is dead arrives after the
/// reconnect (the reconnect catch-up; the > 45 s stale variant is
/// unit-tested in `src/notify.rs` — here the gap is short, so the normal
/// rule carries the same event through the same code path).
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_completion_of_a_gap_arrives_after_the_reconnect() {
    with_mock_env(&[("ROBOCO_MOCK_DELAY_MS", "300")], async {
        let mut rig = notify_rig().await;
        rig.client.mark_seen(&rig.chat_id());
        rig.engine_side_run(&rig.raw_chat, "engine-side run");
        rig.wait_working(&rig.chat_id()).await;
        // The socket dies; the run finishes on the engine, unseen.
        rig.stop_listener();
        tokio::time::sleep(Duration::from_millis(900)).await;
        rig.restart_listener().await;
        rig.wait_connected().await;
        rig.notify
            .wait_for("the post-gap Done", |events| {
                events.len() == 1
                    && events[0].chat_id == rig.chat_id()
                    && events[0].kind == NotifyKind::Done
            })
            .await;
        // Exactly one — the heartbeat never replays it.
        tokio::time::sleep(Duration::from_millis(400)).await;
        assert_eq!(rig.notify.session_events().len(), 1);
    })
    .await;
}
