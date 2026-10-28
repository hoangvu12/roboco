//! Opening a chat on another machine over a slow link (fleet-redesign 17).
//! The paired engine sits behind a delay proxy shaped like the user's
//! relayed Tailcat link (160 ms per round trip, 1.2 MB/s); the proxy logs
//! every RPC the desktop makes, so the test counts the round trips a chat
//! open waits on instead of guessing them. A full shell window is open, so
//! everything a chat open sets off (files, git, previews, catalogs) rides
//! the same link.
//!
//! `ROBOCO_OPEN_TIMELINE=1` prints each open's request timeline (the
//! numbers in the ticket's Comments come from it).

use super::*;
use crate::paired_fixture::{PairedEngines, wait_until};
use gpui::TestAppContext;
use roboco_doc::{MessagePart, MessageRole, MessageStatus, SessionMessageEntry};
use roboco_engine_client::delay_proxy::{DelayProxy, LinkProfile, RpcCall};
use roboco_proto::ToolCall;

const SMALL: &str = "small-chat";
const SMALL_ENTRIES: usize = 6;
/// Several MB: 300 turns of ~15 KB (text plus tool output).
const BIG: &str = "big-chat";
const BIG_ENTRIES: usize = 300;

fn entry(id: &str, role: MessageRole, parts: Vec<MessagePart>) -> SessionMessageEntry {
    SessionMessageEntry {
        duration_ms: None,
        id: id.into(),
        role,
        parts,
        created_at: 1_000,
        device_id: "remote-device".into(),
        status: Some(MessageStatus::Complete),
        continuation_of: None,
        origin_chat_id: None,
        provider_turn: None,
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

/// A project (a git checkout, so the git panels have work to do) with the
/// small and the big chat in it.
fn seed(core: &roboco_engine::EngineCore, project: &std::path::Path) {
    let _ = std::process::Command::new("git")
        .arg("init")
        .arg("-q")
        .current_dir(project)
        .status();
    std::fs::write(project.join("README.md"), "hello\n").unwrap();
    core.workspace
        .create_space(
            "project",
            &core.device_id,
            &project.to_string_lossy(),
            None,
            false,
        )
        .unwrap();
    let config: roboco_proto::ChatConfig = serde_json::from_value(
        serde_json::json!({ "harness": "mock", "sandbox": "workspace-write" }),
    )
    .unwrap();
    for (chat, turns) in [(SMALL, SMALL_ENTRIES), (BIG, BIG_ENTRIES)] {
        core.workspace
            .create_chat(chat, Some("project"), None, Some(config.clone()), None)
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

/// The registry's periodic `EngineInfo` health probe: link upkeep, not
/// part of opening a chat.
fn is_probe(call: &RpcCall) -> bool {
    call.method == roboco_rpc::methods::ENGINE_INFO
}

/// Pump until the link has carried no new request (other than the health
/// probe) and carried no byte to the client for a second.
fn settle(cx: &mut TestAppContext, proxy: &DelayProxy) {
    let activity = || {
        proxy.calls().iter().filter(|call| !is_probe(call)).count() + proxy.bytes_down() as usize
    };
    let mut seen = activity();
    let mut quiet_since = std::time::Instant::now();
    wait_until(cx, "the link to go quiet", |_| {
        let now = activity();
        if now != seen {
            seen = now;
            quiet_since = std::time::Instant::now();
        }
        quiet_since.elapsed() >= Duration::from_secs(1)
    });
}

/// Leave the chat and forget every transcript copy, warm and on disk: the
/// next open of any chat is uncached.
fn forget_transcripts(cx: &mut TestAppContext, fixture: &PairedEngines) {
    fixture.state.update(cx, |s, cx| {
        s.select_chat(None, cx);
        s.forget_warm_transcripts();
    });
    fn remove_chats(dir: &std::path::Path) {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                remove_chats(&path);
            } else if path
                .file_name()
                .is_some_and(|name| name.to_string_lossy().starts_with("chat-"))
            {
                let _ = std::fs::remove_file(path);
            }
        }
    }
    remove_chats(&fixture.local_dir.path().join("engine-cache-v1"));
}

struct Open {
    first: Duration,
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

/// Open `chat` from the sidebar and time its first message and its full,
/// engine-confirmed transcript.
fn open(
    cx: &mut TestAppContext,
    fixture: &PairedEngines,
    window: &gpui::WindowHandle<Shell>,
    chat: &str,
    expected: usize,
    label: &str,
) -> Open {
    let proxy = fixture.proxy.as_ref().expect("a slow link");
    let id = fixture.scoped(chat);
    let started = proxy.now();
    window
        .update(cx, |shell, _, cx| shell.open_chat(id.clone(), cx))
        .unwrap();
    let mut first = None;
    let state = fixture.state.clone();
    wait_until(cx, label, |cx| {
        let (shown, complete) = state.read_with(cx, |s, _| {
            (
                s.selected_chat.as_deref() == Some(id.as_str()) && !s.transcript.is_empty(),
                s.transcript_replayed && s.transcript.len() == expected,
            )
        });
        if shown && first.is_none() {
            first = Some(proxy.now() - started);
        }
        complete
            && proxy.calls_since(started).iter().any(|call| {
                call.method == roboco_rpc::methods::WATCH_DOC_MESSAGES && call.replies > 0
            })
    });
    let complete = proxy.now() - started;
    settle(cx, proxy);
    let calls: Vec<RpcCall> = proxy
        .calls_since(started)
        .into_iter()
        .filter(|call| !is_probe(call))
        .collect();
    // "Full" is the engine's last word on it: a cached copy shows every
    // entry before any frame arrives, and the frames that follow still
    // have to land.
    let full = calls
        .iter()
        .filter(|call| call.method == roboco_rpc::methods::WATCH_DOC_MESSAGES)
        .filter_map(|call| call.last_reply)
        .map(|at| at.saturating_sub(started))
        .fold(complete, Duration::max);
    if std::env::var("ROBOCO_OPEN_TIMELINE").as_deref() == Ok("1") {
        eprintln!(
            "--- desktop: {label}: first message {:.0} ms, full {:.0} ms\n{}",
            first.unwrap().as_secs_f64() * 1000.0,
            full.as_secs_f64() * 1000.0,
            proxy.timeline(&calls, started)
        );
    }
    Open {
        first: first.unwrap(),
        full,
        transcript_bytes: calls
            .iter()
            .filter(|call| call.method == roboco_rpc::methods::WATCH_DOC_MESSAGES)
            .map(|call| call.reply_bytes)
            .sum(),
        first_frame: calls
            .iter()
            .filter(|call| call.method == roboco_rpc::methods::WATCH_DOC_MESSAGES)
            .filter_map(|call| call.first_reply)
            .map(|at| at.saturating_sub(started))
            .min(),
        rtt: proxy.profile.round_trip(),
    }
}

fn row(name: &str, open: &Open) {
    eprintln!(
        "desktop | {name} | {:.0} ms ({:.1} rtt) | {:.0} ms | {} KB",
        open.first.as_secs_f64() * 1000.0,
        open.first_rtts(),
        open.full.as_secs_f64() * 1000.0,
        open.transcript_bytes / 1024
    );
}

/// The numbers behind the ticket's table, and the regression bounds: on the
/// 160 ms link an uncached small chat shows its messages within about three
/// round trips, even right after leaving a big chat mid-download; a cached
/// one (warm or from disk) shows before the link answers; and reopening a
/// big cached chat does not pull its whole history again.
#[gpui::test]
fn opening_a_remote_chat_waits_on_few_round_trips(cx: &mut TestAppContext) {
    let project = tempfile::tempdir().unwrap();
    let fixture = PairedEngines::start_over(cx, Some(LinkProfile::RELAYED), |core| {
        seed(core, project.path())
    });
    let big_id = fixture.scoped(BIG);
    fixture.wait_for_state(cx, "the paired engine's chats", |s| {
        s.chats.iter().any(|c| c.id == big_id)
    });
    let window = cx.add_window(|_, cx| Shell::new(fixture.state.clone(), fixture.boot.clone(), cx));
    let proxy = fixture.proxy.as_ref().unwrap();
    settle(cx, proxy);
    forget_transcripts(cx, &fixture);
    settle(cx, proxy);

    let small = open(
        cx,
        &fixture,
        &window,
        SMALL,
        SMALL_ENTRIES,
        "small, uncached",
    );
    let big = open(cx, &fixture, &window, BIG, BIG_ENTRIES, "big, uncached");
    let warm_small = open(cx, &fixture, &window, SMALL, SMALL_ENTRIES, "small, warm");
    fixture.state.update(cx, |s, _| s.forget_warm_transcripts());
    let cached_big = open(cx, &fixture, &window, BIG, BIG_ENTRIES, "big, from disk");

    // Head of line: leave a big chat while its history still downloads,
    // and open a small one.
    forget_transcripts(cx, &fixture);
    settle(cx, proxy);
    let big_id = fixture.scoped(BIG);
    let leaving = proxy.now();
    window
        .update(cx, |shell, _, cx| shell.open_chat(big_id.clone(), cx))
        .unwrap();
    wait_until(cx, "the big chat's first frame", |_| {
        proxy
            .calls_since(leaving)
            .iter()
            .any(|call| call.method == roboco_rpc::methods::WATCH_DOC_MESSAGES && call.replies > 0)
    });
    let after_big = open(
        cx,
        &fixture,
        &window,
        SMALL,
        SMALL_ENTRIES,
        "small, right after leaving a big download",
    );

    row("small, uncached", &small);
    row("big, uncached", &big);
    row("small, warm (switch back)", &warm_small);
    row("big, from disk (restart)", &cached_big);
    row("small, leaving a big download", &after_big);
    assert!(
        small.first_rtts() <= 3.5,
        "small uncached: {:.1} round trips",
        small.first_rtts()
    );
    assert!(
        warm_small.painted_before_the_link(),
        "small warm waited for the link"
    );
    assert!(
        cached_big.painted_before_the_link(),
        "big from disk waited for the link"
    );
    assert!(
        cached_big.transcript_bytes * 10 < big.transcript_bytes,
        "a cached reopen resumes: {} KB pulled again of {} KB",
        cached_big.transcript_bytes / 1024,
        big.transcript_bytes / 1024
    );
    assert!(
        after_big.first_rtts() <= 3.5,
        "a small chat after leaving a big download: {:.1} round trips",
        after_big.first_rtts()
    );
    fixture.shutdown(cx);
}
