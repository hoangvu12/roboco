//! Opening a chat on another machine over a slow link (fleet-redesign 17).
//! A real engine sits behind a delay proxy shaped like the user's relayed
//! Tailcat link (160 ms per round trip, 1.2 MB/s); the proxy logs every RPC
//! the phone core makes, so the tests count the round trips a chat open
//! waits on instead of guessing them.
//!
//! `ROBOCO_OPEN_TIMELINE=1` prints each open's request timeline (the
//! numbers in the ticket's Comments come from it).

mod support;

use std::sync::Arc;
use std::time::Duration;

use roboco_doc::{MessagePart, MessageRole, MessageStatus, SessionMessageEntry};
use roboco_engine::EngineCore;
use roboco_engine_client::delay_proxy::{DelayProxy, LinkProfile, RpcCall};
use roboco_mobile_client::timings::Timings;
use roboco_mobile_client::{Client, EngineState, PairedEngine};
use roboco_proto::ToolCall;

use support::{
    Recorder, core as engine_core, pairing_url, serve, test_config, wait_for_fleet, wait_for_view,
};

/// A small chat: a handful of short turns.
const SMALL: &str = "small-chat";
const SMALL_ENTRIES: usize = 6;
/// A big chat: several MB of turns with tool output (300 turns × ~15 KB).
const BIG: &str = "big-chat";
const BIG_ENTRIES: usize = 300;

fn entry(id: &str, role: MessageRole, parts: Vec<MessagePart>) -> SessionMessageEntry {
    SessionMessageEntry {
        origin_chat_id: None,
        id: id.into(),
        role,
        parts,
        created_at: 1_000,
        device_id: "remote-device".into(),
        status: Some(MessageStatus::Complete),
        continuation_of: None,
        provider_turn: None,
        duration_ms: None,
    }
}

fn text(id: String, len: usize) -> MessagePart {
    MessagePart::Text {
        id,
        text: "lorem ipsum ".repeat(len / 12),
    }
}

fn tool(id: String, len: usize) -> MessagePart {
    MessagePart::Tool {
        id,
        call: ToolCall::Exec {
            command: "cargo test".into(),
        },
        is_error: false,
        resolved: true,
        output: Some("test result: ok. ".repeat(len / 17)),
        diff: None,
        output_ref: None,
        output_bytes: None,
        diff_ref: None,
        diff_stats: None,
        subagent_ref: None,
        subagent_status: None,
        subagent_tail: None,
    }
}

/// Seed the small and the big chat on the engine.
fn seed(core: &EngineCore) {
    for (chat, turns) in [(SMALL, SMALL_ENTRIES), (BIG, BIG_ENTRIES)] {
        core.workspace
            .create_chat(chat, None, Some(&core.device_id), None, None)
            .unwrap();
        let doc = core.doc_host.open(chat).unwrap();
        for turn in 0..turns {
            let id = format!("{chat}-{turn}");
            let parts = if chat == BIG {
                let mut parts = vec![text(format!("{id}-t"), 2_000)];
                parts.extend((0..4).map(|n| tool(format!("{id}-x{n}"), 3_000)));
                parts
            } else {
                vec![text(format!("{id}-t"), 200)]
            };
            let role = if turn % 2 == 0 {
                MessageRole::User
            } else {
                MessageRole::Assistant
            };
            doc.doc().push_message(&entry(&id, role, parts)).unwrap();
        }
    }
}

/// A phone core with the app's own timings: the shrunk test timings probe
/// the engine every 200 ms, which no real link carries.
fn phone(data_dir: &std::path::Path, engines: Vec<PairedEngine>) -> Arc<Client> {
    Client::new_with_timings(
        test_config(data_dir),
        engines,
        Vec::new(),
        Arc::new(Recorder::default()),
        Timings::default(),
    )
    .unwrap()
}

struct Rig {
    core: EngineCore,
    _engine_dir: tempfile::TempDir,
    data_dir: tempfile::TempDir,
    _listener: roboco_engine::EngineListener,
    proxy: DelayProxy,
    record: PairedEngine,
}

async fn rig() -> Rig {
    let engine_dir = tempfile::tempdir().unwrap();
    let data_dir = tempfile::tempdir().unwrap();
    let core = engine_core(engine_dir.path());
    seed(&core);
    let listener = serve(&core, engine_dir.path()).await;
    let proxy = DelayProxy::start(listener.address, LinkProfile::RELAYED).await;
    let url = pairing_url(&listener, engine_dir.path(), Some(proxy.address.port()));
    let client = phone(data_dir.path(), Vec::new());
    let record = tokio::time::timeout(Duration::from_secs(30), client.pair_engine(&url, None))
        .await
        .expect("pairing completes")
        .expect("pairing succeeds");
    client.shutdown();
    Rig {
        core,
        _engine_dir: engine_dir,
        data_dir,
        _listener: listener,
        proxy,
        record,
    }
}

impl Rig {
    /// A client started on this rig's data dir (and so its transcript
    /// cache), connected, with its rows and its spare connection in.
    async fn client(&self) -> Arc<Client> {
        let client = phone(self.data_dir.path(), vec![self.record.clone()]);
        let engine = self.record.engine_id.clone();
        wait_for_view(&client, &engine, "connected", |view| {
            view.state == EngineState::Connected
        })
        .await;
        wait_for_fleet(&client, "the chats", |fleet| {
            fleet
                .engine(&engine)
                .is_some_and(|e| e.rows.chats.iter().any(|chat| chat.id == BIG))
        })
        .await;
        // Let the connect-time traffic (the calls link, the spare) settle,
        // so the open is measured on its own.
        settle(&self.proxy).await;
        client
    }
}

/// Wait until the link has carried no new request (other than the
/// periodic health probe) and no byte to the client for a second.
async fn settle(proxy: &DelayProxy) {
    let activity =
        || proxy.calls().iter().filter(|call| !is_probe(call)).count() as u64 + proxy.bytes_down();
    let mut seen = activity();
    loop {
        tokio::time::sleep(Duration::from_millis(1_000)).await;
        let now = activity();
        if now == seen {
            return;
        }
        seen = now;
    }
}

/// The supervisor's periodic `EngineInfo` health probe: link upkeep, not
/// part of opening a chat.
fn is_probe(call: &RpcCall) -> bool {
    call.method == roboco_rpc::methods::ENGINE_INFO
}

/// One measured open.
struct Open {
    /// Until the first message was on the snapshot.
    first: Duration,
    /// Until the whole transcript was.
    full: Duration,
    /// Transcript bytes the open pulled over the link.
    transcript_bytes: usize,
    /// When the open's first transcript frame landed.
    first_frame: Option<Duration>,
    rtt: Duration,
}

impl Open {
    fn first_rtts(&self) -> f64 {
        self.first.as_secs_f64() / self.rtt.as_secs_f64()
    }

    /// The messages were on screen before any transcript frame arrived:
    /// they came from a held copy, not the link (a link-independent check;
    /// how long the copy takes to load is the machine's, not the link's).
    fn painted_before_the_link(&self) -> bool {
        self.first_frame.is_none_or(|frame| self.first < frame)
    }
}

/// Open `chat` the way the app does (open, then attach the view) and time
/// the first message and the full transcript.
async fn open(client: &Client, rig: &Rig, chat: &str, expected: usize, label: &str) -> Open {
    let proxy = &rig.proxy;
    let scoped = client.scope(&rig.record.engine_id, chat);
    let started = proxy.now();
    let handle = client.open_session(&scoped).unwrap();
    handle.set_view_attached(true);
    let mut first = None;
    let deadline = std::time::Instant::now() + Duration::from_secs(60);
    let transcript_replied = || {
        proxy
            .calls_since(started)
            .iter()
            .any(|call| call.method == roboco_rpc::methods::WATCH_DOC_MESSAGES && call.replies > 0)
    };
    let complete = loop {
        let snapshot = handle.snapshot();
        if first.is_none() && !snapshot.entries.is_empty() {
            first = Some(proxy.now() - started);
        }
        if snapshot.entries.len() == expected && !snapshot.history_pending && transcript_replied() {
            break proxy.now() - started;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "{label}: the transcript never completed"
        );
        tokio::time::sleep(Duration::from_millis(2)).await;
    };
    // Whatever else the open set off.
    settle(proxy).await;
    let calls: Vec<RpcCall> = proxy
        .calls_since(started)
        .into_iter()
        .filter(|call| !is_probe(call))
        .collect();
    // "Full" is the engine's last word on it: a cached copy shows every
    // entry before any frame arrives, and the frames that follow still
    // have to land.
    let transcript: Vec<&RpcCall> = calls
        .iter()
        .filter(|call| call.method == roboco_rpc::methods::WATCH_DOC_MESSAGES)
        .collect();
    let full = transcript
        .iter()
        .filter_map(|call| call.last_reply)
        .map(|at| at.saturating_sub(started))
        .fold(complete, Duration::max);
    if std::env::var("ROBOCO_OPEN_TIMELINE").as_deref() == Ok("1") {
        eprintln!(
            "--- phone: {label}: first message {:.0} ms, full {:.0} ms
{}",
            first.unwrap().as_secs_f64() * 1000.0,
            full.as_secs_f64() * 1000.0,
            proxy.timeline(&calls, started)
        );
    }
    let transcript_bytes = transcript.iter().map(|call| call.reply_bytes).sum();
    let first_frame = transcript
        .iter()
        .filter_map(|call| call.first_reply)
        .map(|at| at.saturating_sub(started))
        .min();
    handle.set_view_attached(false);
    Open {
        first: first.unwrap(),
        full,
        transcript_bytes,
        first_frame,
        rtt: proxy.profile.round_trip(),
    }
}

/// Waits for the open chats' transcript cache files to land (the reopen
/// reads them).
async fn flush_cache(client: &Client) {
    client.on_background();
    tokio::time::sleep(Duration::from_millis(500)).await;
    client.on_foreground();
}

/// The numbers behind the ticket's table, and the regression bounds: on the
/// 160 ms link an uncached small chat shows its messages within about three
/// round trips, and a cached chat (small or big) shows them before the link
/// answers.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn opening_a_remote_chat_waits_on_few_round_trips() {
    let rig = rig().await;
    let client = rig.client().await;
    let small = open(&client, &rig, SMALL, SMALL_ENTRIES, "small, uncached").await;
    let big = open(&client, &rig, BIG, BIG_ENTRIES, "big, uncached").await;
    flush_cache(&client).await;
    client.shutdown();
    drop(client);

    let client = rig.client().await;
    let cached_small = open(&client, &rig, SMALL, SMALL_ENTRIES, "small, cached").await;
    let cached_big = open(&client, &rig, BIG, BIG_ENTRIES, "big, cached").await;
    client.shutdown();
    let row = |name: &str, open: &Open| {
        eprintln!(
            "phone | {name} | {:.0} ms ({:.1} rtt) | {:.0} ms | {} KB",
            open.first.as_secs_f64() * 1000.0,
            open.first_rtts(),
            open.full.as_secs_f64() * 1000.0,
            open.transcript_bytes / 1024
        );
    };
    row("small, uncached", &small);
    row("big, uncached", &big);
    row("small, cached", &cached_small);
    row("big, cached", &cached_big);
    assert!(
        small.first_rtts() <= 3.5,
        "small uncached: {:.1} round trips",
        small.first_rtts()
    );
    assert!(
        cached_small.painted_before_the_link(),
        "small cached waited for the link"
    );
    assert!(
        cached_big.painted_before_the_link(),
        "big cached waited for the link"
    );
    drop(rig.core);
}
