//! The layout seam (ticket 08): `TranscriptView::attach` feeds a real
//! session's snapshots into the layout worker through the Rust-only input
//! channel — transcript rows never cross FFI. A real in-process engine
//! serves the frames (spec "Testing Decisions" — no protocol mocks).

use std::sync::Arc;
use std::time::{Duration, Instant};

use roboco_mobile::client_ffi::{ClientEvent, ClientListener, CoreClient, CoreConfig};
use roboco_mobile::layout::{
    FaceData, FaceRole, LayoutListener, PendingUser, PlatformMeasurer, TextSystem,
    TranscriptInput, TranscriptView,
};

#[derive(Default)]
struct Recorder(std::sync::Mutex<Vec<ClientEvent>>);

impl ClientListener for Recorder {
    fn on_event(&self, event: ClientEvent) {
        self.0.lock().unwrap().push(event);
    }
}

/// Fixed-advance fallback so the test never depends on a platform text
/// engine (the layout tests' own rig).
struct FixedFallback;

impl PlatformMeasurer for FixedFallback {
    fn measure(&self, _face: FaceRole, size: f32, _ligatures: bool, text: String) -> f32 {
        text.chars().count() as f32 * size * 1.1
    }

    fn measure_run(
        &self,
        _face: FaceRole,
        size: f32,
        _ligatures: bool,
        text: String,
    ) -> Vec<f32> {
        text.chars().map(|_| size * 1.1).collect()
    }
}

fn font(name: &str) -> Vec<u8> {
    std::fs::read(format!(
        "{}/../ui/assets/fonts/{name}.ttf",
        env!("CARGO_MANIFEST_DIR")
    ))
    .unwrap()
}

fn text_system() -> Arc<TextSystem> {
    let faces = [
        (FaceRole::Sans, "Geist"),
        (FaceRole::SansMedium, "Geist-Medium"),
        (FaceRole::SansSemibold, "Geist-SemiBold"),
        (FaceRole::SansBold, "Geist-Bold"),
        (FaceRole::SansItalic, "Geist-Italic"),
        (FaceRole::SansSemiboldItalic, "Geist-SemiBoldItalic"),
        (FaceRole::Mono, "GeistMono"),
    ]
    .into_iter()
    .map(|(role, name)| FaceData {
        role,
        bytes: font(name),
    })
    .collect();
    TextSystem::new(faces, Some(Arc::new(FixedFallback)))
}

#[derive(Default)]
struct FrameCounter(std::sync::atomic::AtomicU64);

impl LayoutListener for FrameCounter {
    fn frame_ready(&self, _revision: u64) {
        self.0
            .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    }
}

/// Pair a client against a fresh mock engine; returns the client, the
/// engine id, the scoped chat id, and the engine's listener (dropping the
/// listener stops the engine serving).
async fn paired_client()
-> (
    Arc<CoreClient>,
    String,
    String,
    roboco_engine::EngineListener,
    tempfile::TempDir,
    tempfile::TempDir,
) {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = roboco_engine::EngineCore::assemble_with_profile(
        roboco_engine::EngineProfile::local(engine_dir.path()).unwrap(),
        Arc::new(roboco_engine::smoke_registry()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_chat("same-chat", None, Some(&core.device_id), None, None)
        .unwrap();
    let listener = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        engine_dir.path(),
    )
    .await
    .unwrap();
    let host = listener.address.to_string();
    let store = roboco_engine::pairing::PairingStore::open(engine_dir.path()).unwrap();
    let code = store.create_code("test", 300).unwrap();
    let url = format!("http://{host}/pair#token={}", code.credential);
    let client = CoreClient::new(
        CoreConfig {
            data_dir: data_dir.path().to_string_lossy().to_string(),
            device_name: "Pixel 9".into(),
            platform: "android".into(),
            app_version: env!("CARGO_PKG_VERSION").into(),
            show_mock_harness: true,
            native_lib_dir: None,
        },
        Vec::new(),
        Vec::new(),
        Arc::new(Recorder::default()),
    )
    .unwrap();
    let record = client
        .pair_engine(url.clone(), None)
        .await
        .expect("pairing succeeds");
    // Connected + rows.
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        let connected = client
            .engines()
            .iter()
            .any(|view| view.engine_id == record.engine_id && view.state == roboco_mobile::client_ffi::EngineState::Connected);
        if connected {
            break;
        }
        assert!(Instant::now() < deadline, "engine never connected");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let chat_id = client
        .client()
        .scope(&record.engine_id, "same-chat");
    (client, record.engine_id, chat_id, listener, engine_dir, data_dir)
}

/// `attach` returns `true` for an open session and `false` for an unknown
/// chat; a sent message's echo reaches the layout worker, and the adopted
/// entry paints.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn attach_feeds_the_layout_worker() {
    let (client, _engine_id, chat_id, _listener, _engine_dir, _data_dir) = paired_client().await;
    let handle = client.open_session(chat_id.clone()).unwrap();
    let text = text_system();
    let counter = Arc::new(FrameCounter::default());
    let view = TranscriptView::new(text, counter.clone());
    assert!(view.attach(client.clone(), chat_id.clone()), "attach succeeds");
    assert!(
        !view.attach(client.clone(), "engine:v1:not-a-real-chat".into()),
        "an unknown chat returns false"
    );
    view.set_viewport(320.0, 1.0);
    let outcome = handle
        .send(roboco_mobile::client_ffi::SendRequest {
            text: "hello layout".into(),
            attachments: Vec::new(),
            worktree: None,
            busy: roboco_mobile::client_ffi::BusyPolicy::Queue,
        })
        .await;
    let outcome = outcome.unwrap();
    let message_id = match outcome {
        roboco_mobile::client_ffi::SendOutcome::Started { message_id } => message_id,
        other => panic!("idle send starts a turn: {other:?}"),
    };
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        if counter.0.load(std::sync::atomic::Ordering::SeqCst) > 0 {
            break;
        }
        assert!(Instant::now() < deadline, "no layout frame arrived");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    // The adopted user entry paints (the entry id is the sent message id).
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        let frame = view.frame();
        if frame.row_count() > 0 {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the sent message never painted (rows: {})",
            frame.row_count()
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let _ = message_id;
    view.close();
    client.shutdown();
}

/// The pending echo row (with the raw trailer text) reaches the input
/// channel while the send is in flight — the seam contract ticket 08
/// fixed and ticket 05 documented.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_pending_echo_reaches_the_layout_input() {
    use roboco_mobile_client::session::SendRequest as RustSendRequest;
    use roboco_mobile_client::{BusyPolicy, OutgoingAttachment};

    let (client, _engine_id, chat_id, _listener, _engine_dir, _data_dir) = paired_client().await;
    let handle = client
        .client()
        .open_session(&chat_id)
        .expect("the session opens");
    let seen: Arc<std::sync::Mutex<Vec<TranscriptInput>>> =
        Arc::new(std::sync::Mutex::new(Vec::new()));
    let sink = seen.clone();
    let _guard = handle.watch(move |snapshot| {
        sink.lock().unwrap().push(TranscriptInput {
            entries: snapshot.entries.clone(),
            pending: snapshot
                .pending
                .iter()
                .map(|p| PendingUser {
                    id: p.message_id.clone(),
                    text: p.text.clone(),
                })
                .collect(),
            working: snapshot.working,
            working_since_ms: snapshot.working_since_ms,
            streaming: snapshot.streaming,
        });
    });
    // A send with an attachment: the echo (with its placeholder trailer)
    // is observable while the upload is in flight.
    let send_handle = handle.clone();
    let bytes: Vec<u8> = (0..600_000u32).map(|i| (i % 251) as u8).collect();
    let send_task = tokio::spawn(async move {
        send_handle
            .send(RustSendRequest {
                text: "with an image".into(),
                attachments: vec![OutgoingAttachment {
                    name: "pic.png".into(),
                    mime_type: "image/png".into(),
                    data: bytes,
                }],
                worktree: None,
                busy: BusyPolicy::Queue,
            })
            .await
    });
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        let has_pending = seen
            .lock()
            .unwrap()
            .iter()
            .any(|input| input.pending.iter().any(|p| p.text.contains("with an image")));
        if has_pending {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the pending echo never reached the input channel"
        );
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    send_task.await.unwrap().unwrap();
    // Adoption: the entry arrives and the pending row disappears.
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        let inputs = seen.lock().unwrap();
        let adopted = inputs
            .iter()
            .any(|input| input.entries.iter().any(|e| !e.parts.is_empty()));
        let quiet = inputs
            .last()
            .is_some_and(|input| input.pending.is_empty());
        if adopted && quiet {
            break;
        }
        drop(inputs);
        assert!(Instant::now() < deadline, "adoption never completed");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    client.shutdown();
}
