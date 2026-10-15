//! The Android flow-test fixture engine (android-app#30): a real
//! `roboco-engine` on the scripted mock harness, seeded deterministically
//! and paired exactly the way a user pairs. The Android instrumented flow
//! tests (`apps/android/app/src/androidTest/…/flows/`) run against three of
//! these processes (`main`, `slow`, `question`) instead of upstream's
//! in-app `DemoHost` — no demo workspace exists in Roboco (D4), so the
//! tests exercise the real wire: pairing, WebSocket, doc commands and the
//! mock harness's scripted replies.
//!
//! Prior art: `web_smoke.rs` (remote listener + pairing URL + one seeded
//! chat) and `web_conformance.rs` (ephemeral port + one stdout ready line
//! the test suite waits for). This example adds:
//!
//! - **Deterministic seeding** (`--profile main|slow|question`): fixed chat
//!   ids, a git repo with a fixed root commit (so the `repositoryId` every
//!   engine derives matches across the three processes, #799), pins and a
//!   "P0" section through `SetSidebarPins` / `SetSidebarSections`, and
//!   completed turns driven through `QueueCommand` exactly like the
//!   composer does.
//! - **A control endpoint** (`--control-port`), test-only: a plain
//!   `std::net::TcpListener` speaking minimal HTTP. `GET /mint` returns
//!   `{"pairUrl": …}` — a fresh single-use pairing code minted from the
//!   engine's `PairingStore` (600 s TTL). Pair codes are single-use, so
//!   every test mints its own instead of sharing one spent URL.
//!   android-ui-parity #79 adds `GET /listener/stop` and
//!   `GET /listener/start` — dropping and re-creating the remote listener
//!   mid-session, so the offline flow tests can kill the engine's
//!   WebSockets without restarting the process (see
//!   [spawn_control_endpoint]).
//! - **Tailcat** (android-next#02, optional): `--tailcat-derp-map <url>`
//!   also publishes the listener through the desktop adapter
//!   (`roboco_engine::tailcat::TailcatServer`, `ROBOCO_TAILCAT_ADAPTER`)
//!   against a local DERP map (`roboco-tailcat-testderp`), and
//!   `GET /mint-tailcat` returns `{"invite": "roboco-tailcat:…"}`. The
//!   emulator's Tailcat smoke pairs with that invite while the engine port
//!   itself is not reachable from the device.
//!
//! The mock harness's demo knobs (`ROBOCO_MOCK_*`) are process-global env
//! read at run time (`crates/harness/src/mock.rs`); the example sets them
//! for its profile so the runner only passes `--profile`. The registry is
//! `smoke_registry()` — the mock harness only, never a real agent
//! (`crates/engine/src/registry.rs:583`).
//!
//! Usage:
//!
//! ```text
//! android_fixture --port 27701 --control-port 27711 --profile main
//! ```
//!
//! stdout (line-buffered, one line): `ANDROID_FIXTURE {"profile":…,
//! "endpoint":…, "control":…, "deviceId":…}` — printed only after the
//! seeding has settled, so a client that sees it can pair immediately.
//!
//! This is an example under `examples/`: it never ships, which is why a
//! plain-TCP control endpoint is acceptable here.

use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use roboco_doc::SessionCommandPayload;
use roboco_engine::{EngineCore, EngineListener, EngineProfile, HarnessId, pairing, smoke_registry};
use roboco_proto::{ReasoningLevel, RunRequest, SandboxLevel};
use roboco_rpc::{RpcReply, RpcService, methods};

/// The mock-harness env knobs per profile (§5 of the ticket): `main`
/// appends links/tables/code/thinking to every reply, `slow` paces a long
/// streaming reply (queue/stop/scroll/notification tests), `question`
/// answers with the scripted question set (the QuestionPanel).
const PROFILE_ENV: &[(&str, &[(&str, &str)])] = &[
    (
        "main",
        &[
            ("ROBOCO_MOCK_MEND", "1"),
            ("ROBOCO_MOCK_TABLE", "1"),
            ("ROBOCO_MOCK_CODE", "1"),
            ("ROBOCO_MOCK_THINKING", "1"),
        ],
    ),
    // 24 repeats × the 7-event script + Done ≈ 169 events × 150 ms ≈ 25.6 s.
    // At 4 (≈ 4.4 s, run 38531061823) and 12 (≈ 12.8 s, run 38552405016)
    // the reply ended before the emulator finished injecting a back-swipe
    // or queueing two messages (queueWhileWorking took 20.6 s: each queue
    // dismisses the IME and the next click re-shows it). 25.6 s still fits
    // the tests' 40 s / 60 s completion waits; the slow-engine tests wait
    // for the previous test's turn to end before sending (awaitSlowEngineIdle).
    ("slow", &[("ROBOCO_MOCK_DELAY_MS", "150"), ("ROBOCO_MOCK_REPEAT", "24")]),
    ("question", &[("ROBOCO_MOCK_QUESTION", "1")]),
];

/// The fixed commit identity of the fixture repo: identical content,
/// author and dates make the root commit — and therefore the
/// `repositoryId` derived from it (#799) — byte-identical in every
/// profile and every engine process.
const GIT_FIXED_DATE: &str = "2026-10-10T12:00:00+00:00";
const GIT_AUTHOR_NAME: &str = "Roboco Fixture";
const GIT_AUTHOR_EMAIL: &str = "fixture@roboco.local";

/// Pair-code TTL for `/mint` (seconds): plenty for one test's redeem.
const MINT_TTL_SECONDS: u64 = 600;

fn main() {
    // CLI + env before the runtime: the mock knobs must be set while the
    // process is still single-threaded (std::env::set_var is unsafe to
    // race against getenv on live threads; the tokio workers would be).
    let (port, control_port, profile, tailcat_derp_map) = parse_args();
    apply_profile_env(&profile);

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("build the fixture runtime");
    runtime.block_on(async move {
        run(port, control_port, profile, tailcat_derp_map).await;
    });
}

async fn run(port: u16, control_port: u16, profile: String, tailcat_derp_map: Option<String>) {
    let dir = tempfile::tempdir().unwrap();
    let repo = init_fixture_repo(dir.path());

    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(smoke_registry()),
        HarnessId::Mock,
    )
    .unwrap();
    // The handle the seed helpers share (typed as the trait object, like
    // web_smoke's seed function takes it).
    let service: Arc<dyn RpcService> = core.rpc_service();
    let bind_addr: std::net::SocketAddr = format!("127.0.0.1:{port}").parse().unwrap();
    let remote = roboco_engine::serve_engine_remote(bind_addr, service.clone(), dir.path())
        .await
        .unwrap();
    let control = TcpListener::bind(format!("127.0.0.1:{control_port}")).unwrap();

    let chats = seed(&service, &repo, &profile).await;

    // android-next#02: publish the listener over Tailcat too. Blocking
    // (the adapter's readiness line); the server lives as long as the
    // process.
    let tailcat = match tailcat_derp_map {
        Some(derp_map) => {
            let data_dir = dir.path().to_path_buf();
            let server = tokio::task::spawn_blocking(move || {
                roboco_engine::tailcat::TailcatServer::start(&data_dir, bind_addr, Some(&derp_map))
            })
            .await
            .expect("tailcat serve task")
            .expect("android_fixture: start the Tailcat adapter (ROBOCO_TAILCAT_ADAPTER)");
            Some(server)
        }
        None => None,
    };
    let tailcat_address = tailcat.as_ref().map(|server| server.address().to_owned());

    // Seeding has settled: announce readiness (stdout is line-buffered, so
    // the runner sees the line as soon as it is printed).
    println!(
        "ANDROID_FIXTURE {}",
        serde_json::json!({
            "profile": profile,
            "endpoint": format!("http://{}", remote.address),
            "control": format!("http://127.0.0.1:{control_port}"),
            "deviceId": core.device_id,
            "tailcat": tailcat_address.is_some(),
        })
    );

    // android-ui-parity #79: the remote listener lives in a slot the
    // control endpoint can take and refill, so a flow test can kill the
    // engine's WebSockets mid-session (`/listener/stop`) and bring them
    // back (`/listener/start`) without restarting the process — the
    // pairing store and the seeded workspace persist in the data dir.
    let remote_slot = Arc::new(std::sync::Mutex::new(Some(remote)));
    let handle = tokio::runtime::Handle::current();
    spawn_control_endpoint(
        control,
        bind_addr,
        service,
        dir.path().to_path_buf(),
        remote_slot,
        handle,
        repo.clone(),
        chats,
        tailcat_address,
    );

    std::future::pending::<()>().await;
    #[allow(unreachable_code)]
    {
        drop(tailcat);
        core.shutdown().await;
    }
}

// ── CLI ─────────────────────────────────────────────────────────────────

/// `android_fixture --port <p> --control-port <c> --profile <name>
/// [--tailcat-derp-map <url>]`.
fn parse_args() -> (u16, u16, String, Option<String>) {
    let mut port: Option<u16> = None;
    let mut control_port: Option<u16> = None;
    let mut profile = "main".to_string();
    let mut tailcat_derp_map: Option<String> = None;
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        let mut value = || {
            args.next()
                .unwrap_or_else(|| panic!("android_fixture: {arg} needs a value"))
        };
        match arg.as_str() {
            "--port" => port = Some(value().parse().expect("--port must be a number")),
            "--control-port" => {
                control_port = Some(value().parse().expect("--control-port must be a number"))
            }
            "--profile" => profile = value(),
            "--tailcat-derp-map" => tailcat_derp_map = Some(value()),
            other => panic!("android_fixture: unknown argument {other}"),
        }
    }
    let port = port.unwrap_or(27701);
    let control_port = control_port.unwrap_or_else(|| panic!("--control-port is required"));
    if !PROFILE_ENV.iter().any(|(name, _)| *name == profile) {
        panic!(
            "android_fixture: unknown profile {profile} (main | slow | question)"
        );
    }
    (port, control_port, profile, tailcat_derp_map)
}

/// Sets the mock-harness env knobs for [profile] (see [PROFILE_ENV]). The
/// mock reads them at *run* time, so this only has to happen before the
/// first run — but it runs before the runtime is built anyway, which is
/// what makes the `set_var` sound.
fn apply_profile_env(profile: &str) {
    let knobs = PROFILE_ENV
        .iter()
        .find(|(name, _)| *name == profile)
        .map(|(_, knobs)| *knobs)
        .expect("parse_args validated the profile");
    // SAFETY: called from `main` before the runtime's worker threads (and
    // the control thread) exist; nothing else reads the environment yet.
    unsafe {
        for (key, value) in knobs {
            std::env::set_var(key, value);
        }
    }
}

// ── the fixture repo ────────────────────────────────────────────────────

/// Creates `<tempdir>/fixture-repo` with one fixed-identity commit, and
/// returns its path (the cwd of every seeded project chat).
fn init_fixture_repo(dir: &Path) -> PathBuf {
    let root = dir.join("fixture-repo");
    std::fs::create_dir_all(root.join("src").join("layout")).unwrap();
    std::fs::write(root.join("src").join("layout").join("mod.rs"), FIXTURE_MOD_RS).unwrap();
    std::fs::write(root.join("src").join("layout").join("rows.rs"), FIXTURE_ROWS_RS).unwrap();
    std::fs::write(root.join("README.md"), FIXTURE_README).unwrap();
    // `init -b main` (git ≥ 2.28; both CI runners and every dev host here
    // are far past it) keeps the branch name off git's default dance.
    git(&root, ["init", "-b", "main"]);
    git(&root, ["add", "-A"]);
    // Fixed author, committer and both dates: the commit hash is a pure
    // function of content + identity + dates, so every profile and every
    // engine process derives the same root commit (and repositoryId).
    let ok = Command::new("git")
        .current_dir(&root)
        .env("GIT_AUTHOR_NAME", GIT_AUTHOR_NAME)
        .env("GIT_AUTHOR_EMAIL", GIT_AUTHOR_EMAIL)
        .env("GIT_COMMITTER_NAME", GIT_AUTHOR_NAME)
        .env("GIT_COMMITTER_EMAIL", GIT_AUTHOR_EMAIL)
        .env("GIT_AUTHOR_DATE", GIT_FIXED_DATE)
        .env("GIT_COMMITTER_DATE", GIT_FIXED_DATE)
        .args(["commit", "-m", "fixture: layout core sources"])
        .output()
        .expect("git commit failed to spawn");
    assert!(
        ok.status.success(),
        "git commit failed: {}",
        String::from_utf8_lossy(&ok.stderr)
    );
    root
}

fn git(cwd: &Path, args: impl IntoIterator<Item = &'static str>) {
    let ok = Command::new("git")
        .current_dir(cwd)
        .args(args)
        .output()
        .expect("git failed to spawn");
    assert!(
        ok.status.success(),
        "git {} failed: {}",
        ok.status,
        String::from_utf8_lossy(&ok.stderr)
    );
}

const FIXTURE_MOD_RS: &str = r#"//! Fixture layout core: two files, enough surface for the flow tests'
//! file-mention search ("@lay" finds src/layout) and nothing else.

mod rows;

pub const MARGIN_X: f32 = 18.0;

pub fn fold(entries: Vec<Entry>) -> Vec<Entry> {
    entries.into_iter().filter(|e| e.kind != Kind::Padding).collect()
}
"#;

const FIXTURE_ROWS_RS: &str = r#"//! Row geometry for the fixture layout core.

pub struct Row {
    pub height: f32,
}

pub fn measure(rows: &[Row], width: f32) -> f32 {
    rows.iter().map(|r| r.height).sum::<f32>().min(width)
}
"#;

const FIXTURE_README: &str = r#"# fixture-repo

The Android flow tests' fixture repository. Every engine process recreates
it with a fixed root commit so the repository ids match.
"#;

// ── seeding ─────────────────────────────────────────────────────────────

/// One seeded chat: id, title, cwd (`None` = project-less, home) and the
/// number of completed mock turns to run through `QueueCommand`.
struct SeedChat {
    id: &'static str,
    title: &'static str,
    cwd: Option<PathBuf>,
    turns: usize,
    archived: bool,
    /// Epoch-ms offsets from now: (last message, created).
    last_message_offset_ms: i64,
    created_offset_ms: i64,
}

/// The `main` profile's chats (§5): deploy (1 turn), the 30-turn veil
/// transcript, the CJK chat with the MEND links, the project-less home
/// chat and one archived chat. `slow` and `question` seed a single chat
/// each — their traffic is driven live by the tests.
fn seed_chats(profile: &str, repo: &Path) -> Vec<SeedChat> {
    match profile {
        "main" => vec![
            SeedChat {
                id: "chat-deploy",
                title: "Wrangler deploy hygiene",
                cwd: Some(repo.to_path_buf()),
                turns: 1,
                archived: false,
                last_message_offset_ms: 86_400_000,
                created_offset_ms: 3 * 86_400_000,
            },
            SeedChat {
                id: "chat-veil",
                title: "Streaming veil on transcript rows",
                cwd: Some(repo.to_path_buf()),
                // The big transcript the jump-to-latest / scroll tests use
                // (upstream's `-big`): 30 completed turns.
                turns: 30,
                archived: false,
                last_message_offset_ms: 7_200_000,
                created_offset_ms: 14 * 86_400_000,
            },
            SeedChat {
                id: "chat-cjk",
                title: "多言語テキストのレイアウト",
                cwd: Some(repo.to_path_buf()),
                // One turn; the main profile's ROBOCO_MOCK_MEND knob makes
                // its reply carry the inline links the tap-a-link test taps.
                turns: 1,
                archived: false,
                last_message_offset_ms: 600_000,
                created_offset_ms: 86_400_000,
            },
            SeedChat {
                id: "chat-home",
                title: "Clean up ~/Downloads",
                cwd: None,
                turns: 0,
                archived: false,
                last_message_offset_ms: 7_200_000,
                created_offset_ms: 86_400_000,
            },
            SeedChat {
                id: "chat-archived",
                title: "Old experiment",
                cwd: None,
                turns: 0,
                archived: true,
                last_message_offset_ms: 30 * 86_400_000,
                created_offset_ms: 60 * 86_400_000,
            },
        ],
        "slow" => vec![SeedChat {
            id: "chat-long",
            title: "Long reply",
            cwd: Some(clone_repo(repo)),
            turns: 0,
            archived: false,
            last_message_offset_ms: 600_000,
            created_offset_ms: 86_400_000,
        }],
        _ => vec![
            SeedChat {
                id: "chat-ask",
                title: "Pick a tone",
                cwd: Some(repo.to_path_buf()),
                turns: 0,
                archived: false,
                last_message_offset_ms: 600_000,
                created_offset_ms: 86_400_000,
            },
            // sendGlidesPromptToTopWithRunway: this profile's reply is one
            // line and then waits for input, so the runway below the glided
            // prompt holds (main's instant ~20-row reply retires it at once).
            SeedChat {
                id: "chat-runway",
                title: "Runway",
                cwd: Some(repo.to_path_buf()),
                turns: 0,
                archived: false,
                last_message_offset_ms: 600_000,
                created_offset_ms: 86_400_000,
            },
        ],
    }
}

/// A clone of the fixture repo: same root commit (same repositoryId),
/// separate working tree — what the slow engine's chat runs in.
fn clone_repo(repo: &Path) -> PathBuf {
    let dest = repo.parent().unwrap().join("fixture-repo-clone");
    let ok = Command::new("git")
        .arg("clone")
        .arg(repo)
        .arg(&dest)
        .output()
        .expect("git clone failed to spawn");
    assert!(
        ok.status.success(),
        "git clone failed: {}",
        String::from_utf8_lossy(&ok.stderr)
    );
    dest
}

/// Seeds the profile and returns its chats (the control endpoint's `/run` reuses them:
/// `seed_chats` itself must run once — the slow profile clones the repo).
async fn seed(service: &Arc<dyn RpcService>, repo: &Path, profile: &str) -> Vec<SeedChat> {
    let device = local_device(service).await;
    let chats = seed_chats(profile, repo);

    // Chats, titles, cwds, activity and the archived flag.
    for chat in &chats {
        let cwd = chat.cwd.as_ref().map(|p| p.to_string_lossy().to_string());
        mutate(
            service,
            serde_json::json!({
                "op": "createChat", "chatId": chat.id, "deviceId": device,
                "cwd": cwd,
            }),
        )
        .await;
        mutate(
            service,
            serde_json::json!({"op": "renameChat", "chatId": chat.id, "title": chat.title}),
        )
        .await;
        mutate(
            service,
            serde_json::json!({
                "op": "setChatActivity", "chatId": chat.id,
                "lastMessageAt": now_ms() - chat.last_message_offset_ms,
                "createdAt": now_ms() - chat.created_offset_ms,
            }),
        )
        .await;
        if chat.archived {
            mutate(
                service,
                serde_json::json!({"op": "setChatArchived", "chatId": chat.id, "archived": true}),
            )
            .await;
        }
    }

    // Pins + the "P0" section (engine-local sidebar state, profile key
    // "local" for a local-scope engine — the key the mobile client derives
    // in crates/mobile-client/src/workspace/sidebar.rs:23).
    if profile == "main" {
        set_sidebar(
            service,
            methods::SET_SIDEBAR_PINS,
            serde_json::json!({
                "profileKey": "local",
                "sessionIds": ["chat-veil", "chat-cjk"],
            }),
        )
        .await;
        set_sidebar(
            service,
            methods::SET_SIDEBAR_SECTIONS,
            serde_json::json!({
                "profileKey": "local",
                "sections": [ { "id": "sec-p0", "name": "P0",
                                "sessionIds": ["chat-deploy"], "collapsed": false } ],
            }),
        )
        .await;
    }

    // The completed turns, driven through the same QueueCommand path the
    // composer uses; each turn settles before the ready line is printed.
    for chat in &chats {
        for turn in 0..chat.turns {
            let command = serde_json::to_value(SessionCommandPayload::Run {
                request: seed_request(repo, chat),
                message_id: format!("seed-{}-{}", chat.id, turn),
            })
            .unwrap();
            match service
                .handle(
                    methods::QUEUE_COMMAND,
                    serde_json::json!({"chatId": chat.id, "command": command}),
                )
                .await
                .expect("seed QueueCommand")
            {
                RpcReply::Value(value) => assert!(
                    value["commandId"].is_string(),
                    "QueueCommand replied without a commandId: {value}"
                ),
                RpcReply::Stream(_) => panic!("QueueCommand returned a stream"),
            }
            let expected = (turn + 1) * 2; // user + assistant entries per turn
            wait_entries(service, chat.id, expected).await;
        }
    }
    chats
}

/// A [RunRequest] for one seed turn: the mock harness ignores the prompt's
/// content (its script is fixed), so every turn uses the same prompt.
fn seed_request(repo: &Path, chat: &SeedChat) -> RunRequest {
    RunRequest {
        prompt: "Seed the fixture transcript".into(),
        harness: None,
        // chat-deploy carries a model + effort (the Claude-mirroring mock
        // model, efforts Low…XHigh): the session's effort chip only shows
        // for a chat whose config names a reasoning level, and the engine
        // backfills that config from the first run's request — with
        // `None` here effortPickerInSession never found `composer-chip-effort`.
        model: (chat.id == "chat-deploy").then(|| "mock-fable-5".to_string()),
        reasoning: (chat.id == "chat-deploy").then_some(ReasoningLevel::High),
        model_options: Default::default(),
        cwd: chat
            .cwd
            .clone()
            .unwrap_or_else(|| repo.to_path_buf())
            .to_string_lossy()
            .to_string(),
        sandbox: SandboxLevel::WorkspaceWrite,
        auto_approve: true,
        attachments: Vec::new(),
        worktree: None,
        mcp: None,
        resume: None,
    }
}

async fn local_device(service: &Arc<dyn RpcService>) -> String {
    match service
        .handle(methods::LOCAL_DEVICE, serde_json::json!({}))
        .await
        .expect("LocalDevice")
    {
        RpcReply::Value(value) => value["deviceId"]
            .as_str()
            .expect("LocalDevice deviceId")
            .to_string(),
        RpcReply::Stream(_) => panic!("LocalDevice returned a stream"),
    }
}

async fn mutate(service: &Arc<dyn RpcService>, params: serde_json::Value) {
    match service.handle(methods::MUTATE, params).await.expect("seed Mutate") {
        RpcReply::Value(value) => assert!(
            value["ok"] == serde_json::json!(true),
            "seed Mutate failed: {value}"
        ),
        RpcReply::Stream(_) => panic!("Mutate returned a stream"),
    }
}

/// Waits until `chat`'s transcript (through `WatchDocMessages`, the same
/// delta stream the app applies) holds `expected` entries — the
/// deterministic "the seeded run finished" signal, no sleeps. Times out at
/// 120 s, the same budget the runner gives the whole startup.
async fn wait_entries(service: &Arc<dyn RpcService>, chat: &str, expected: usize) {
    let reply = service
        .handle(methods::WATCH_DOC_MESSAGES, serde_json::json!({"chatId": chat}))
        .await
        .expect("seed WatchDocMessages");
    let stream = match reply {
        RpcReply::Stream(stream) => stream,
        RpcReply::Value(_) => panic!("WatchDocMessages returned a value"),
    };
    let deadline = tokio::time::Instant::now() + Duration::from_secs(120);
    let mut materialized: Vec<roboco_doc::SessionMessageEntry> = vec![];
    let mut stream = stream; // BoxStream is already pinned; Unpin for next()
    loop {
        let item = tokio::time::timeout_at(deadline, stream.next())
            .await
            .expect("seeded transcript did not settle within 120 s")
            .expect("doc messages stream alive");
        let frame: roboco_doc::TranscriptFrame = serde_json::from_value(item)
            .expect("doc messages frame");
        roboco_doc::apply_transcript_frame(&mut materialized, frame).expect("apply frame");
        if materialized.len() >= expected {
            return;
        }
    }
}

// ── the control endpoint (test-only) ────────────────────────────────────

/// Serves `GET /mint`, `GET /listener/stop` and `GET /listener/start`
/// forever on a plain blocking thread. No dependencies, no HTTP crate:
/// one request per connection, `Connection: close`.
///
/// The listener routes (android-ui-parity #79's flow test,
/// `offlineEngineIsReadOnly`): `/listener/stop` takes the
/// [EngineListener] out of its slot and stops it — aborting the accept
/// task drops its cancellation token, whose drop guard ends every
/// accepted connection, upgraded WebSockets included
/// (`listener.rs:51-52,62-83`, the upgraded socket's
/// `cancel.cancelled()` select at `:244-264`). `/listener/start`
/// re-binds the **same** address over the **same** data dir — the
/// pairing store persists, so the app's credential stays valid. Both
/// are idempotent, and `Handle::block_on` is legal here because this
/// loop runs on a plain std thread, never inside the runtime.
fn spawn_control_endpoint(
    listener: TcpListener,
    bind_addr: std::net::SocketAddr,
    service: Arc<dyn RpcService>,
    data_dir: PathBuf,
    remote_slot: Arc<std::sync::Mutex<Option<EngineListener>>>,
    handle: tokio::runtime::Handle,
    repo: PathBuf,
    chats: Vec<SeedChat>,
    tailcat_address: Option<String>,
) {
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let mut stream = match stream {
                Ok(stream) => stream,
                Err(_) => continue,
            };
            let request = match read_request_line(&mut stream) {
                Some(line) => line,
                None => continue,
            };
            let (method, path) = match request.split_once(' ') {
                Some((method, rest)) => (method, rest.split_once(' ').map_or(rest, |(p, _)| p)),
                None => continue,
            };
            let body = match (method, path) {
                ("GET", "/mint") => serde_json::json!({
                    "pairUrl": mint_pair_url(&data_dir, bind_addr.port()),
                })
                .to_string(),
                // android-next#02: a fresh single-use code wrapped in a
                // Tailcat invite (404 without --tailcat-derp-map).
                ("GET", "/mint-tailcat") => {
                    let Some(address) = tailcat_address.as_deref() else {
                        respond(&mut stream, 404, "{\"error\":\"tailcat is off\"}");
                        continue;
                    };
                    let code = pairing::PairingStore::open(&data_dir)
                        .expect("open PairingStore")
                        .create_code("android-flow", MINT_TTL_SECONDS)
                        .expect("mint pairing code");
                    let invite = roboco_engine::tailcat::TailcatInvite::new(
                        address,
                        &code.credential,
                        code.expires_at,
                    )
                    .expect("tailcat invite")
                    .encode()
                    .expect("encode the invite");
                    serde_json::json!({ "invite": invite }).to_string()
                }
                ("GET", "/listener/stop") => {
                    // Idempotent: a second stop finds the slot empty. Only
                    // this thread locks the slot, so holding the guard
                    // across block_on cannot deadlock.
                    if let Some(mut remote) = remote_slot.lock().unwrap().take() {
                        handle.block_on(remote.stop());
                    }
                    serde_json::json!({ "ok": true }).to_string()
                }
                ("GET", "/listener/start") => {
                    // Idempotent: a running listener is left alone. A failed
                    // re-bind panics the control thread — the rig is broken,
                    // which the tests report loudly.
                    if remote_slot.lock().unwrap().is_none() {
                        let remote = handle
                            .block_on(roboco_engine::serve_engine_remote(
                                bind_addr,
                                service.clone(),
                                &data_dir,
                            ))
                            .expect("android_fixture: restart the engine listener");
                        *remote_slot.lock().unwrap() = Some(remote);
                    }
                    serde_json::json!({ "ok": true }).to_string()
                }
                // A run started by the ENGINE side, not the phone: the core never
                // posts "Run finished" for the phone's own send (notify.rs
                // `send_pending`, ticket 28), so the notification flow needs a run
                // the phone did not start. Same QueueCommand path as the seed.
                ("GET", p) if p.starts_with("/run?chat=") => {
                    let id = &p["/run?chat=".len()..];
                    let Some(chat) = chats.iter().find(|c| c.id == id) else {
                        respond(&mut stream, 404, "{\"error\":\"unknown chat\"}");
                        continue;
                    };
                    let command = serde_json::to_value(SessionCommandPayload::Run {
                        request: seed_request(&repo, chat),
                        message_id: format!("control-{}-{}", chat.id, now_ms()),
                    })
                    .unwrap();
                    match handle.block_on(service.handle(
                        methods::QUEUE_COMMAND,
                        serde_json::json!({"chatId": chat.id, "command": command}),
                    )) {
                        Ok(RpcReply::Value(value)) => value.to_string(),
                        _ => {
                            respond(&mut stream, 400, "{\"error\":\"QueueCommand failed\"}");
                            continue;
                        }
                    }
                }
                _ => {
                    respond(&mut stream, 404, "{\"error\":\"not found\"}");
                    continue;
                }
            };
            respond(&mut stream, 200, &body);
        }
    });
}

/// Reads the request line (blocks; each connection is one short request).
fn read_request_line(stream: &mut std::net::TcpStream) -> Option<String> {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    // The request line; the rest of the head is drained so the client's
    // write side is never blocked.
    reader.read_line(&mut line).ok()?;
    let mut drain = String::new();
    while reader.read_line(&mut drain).ok()? > 2 {
        drain.clear();
    }
    Some(line.trim_end().to_string())
}

fn mint_pair_url(data_dir: &Path, engine_port: u16) -> String {
    let code = pairing::PairingStore::open(data_dir)
        .expect("open PairingStore")
        .create_code("android-flow", MINT_TTL_SECONDS)
        .expect("mint pairing code");
    pairing::pairing_url(&format!("http://127.0.0.1:{engine_port}"), &code.credential)
        .expect("pairing url")
}

fn respond(stream: &mut std::net::TcpStream, status: u16, body: &str) {
    let reason = match status {
        200 => "OK",
        404 => "Not Found",
        _ => "Bad Request",
    };
    let head = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    // The fixture is test-only: a failed write just drops the connection.
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body.as_bytes());
    let _ = stream.flush();
}

/// The `SetSidebarPins` / `SetSidebarSections` calls: ordered-list
/// replaces that reply with the fresh snapshot (only the success/shape is
/// checked here).
async fn set_sidebar(service: &Arc<dyn RpcService>, method: &str, params: serde_json::Value) {
    match service.handle(method, params).await.expect("seed sidebar call") {
        RpcReply::Value(_) => {}
        RpcReply::Stream(_) => panic!("{method} returned a stream"),
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64
}
