//! Engine-level transcript resume (`WatchDocMessages` with `digests` /
//! `resume`) over the real authenticated listener: the reconnect cost of a
//! 500-entry transcript drops from a full reset to a catch-up delta, with a
//! guaranteed fallback to reset whenever the client's summary does not match.
//!
//! Reuses the remote-listener harness (`remote_listener.rs`): pair over
//! HTTP, dial the WebSocket with the granted credential, subscribe, and
//! compare against what a plain subscription resets to.

use std::sync::Arc;
use std::time::Duration;

use roboco_doc::{
    MessagePart, MessageRole, MessageStatus, SegmentWriter, SessionMessageEntry, TranscriptUpdate,
    apply_transcript_frame, apply_update_digests,
};
use roboco_engine::{EngineCore, EngineListener, EngineProfile, HarnessRegistry, pairing::PairingStore};
use roboco_rpc::{connect_ws_authenticated, methods};
use serde_json::{Value, json};

const CHAT: &str = "resume-chat";
/// Realistic prose-sized entries: the size-bound assertion below needs the
/// reset to dominate the shared replay baseline.
const ENTRY_TEXT: usize = 300;

fn assistant_entry(id: &str, text: &str) -> SessionMessageEntry {
    SessionMessageEntry {
        id: id.into(),
        role: MessageRole::Assistant,
        parts: vec![MessagePart::Text {
            id: "t0".into(),
            text: text.into(),
        }],
        created_at: 0,
        device_id: "host".into(),
        status: None,
        continuation_of: None,
        provider_turn: None,
        duration_ms: None,
    }
}

fn text(i: usize) -> String {
    format!("entry {i:03}: {}", "roboco transcript row. ".repeat(ENTRY_TEXT / 26))
}

/// Pair with the engine over HTTP and dial the authenticated WebSocket.
async fn paired_client(
    remote: &EngineListener,
    dir: &std::path::Path,
) -> roboco_rpc::RpcClient {
    let base = format!("http://{}", remote.address);
    let url = format!("ws://{}", remote.address);
    let store = PairingStore::open(dir).unwrap();
    let code = store.create_code("remote", 300).unwrap();
    let grant: Value = reqwest::Client::new()
        .post(format!("{base}/pairing/redeem"))
        .bearer_auth(&code.credential)
        .json(&json!({"label":"Phone"}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap()
        .json()
        .await
        .unwrap();
    connect_ws_authenticated(&url, grant["credential"].as_str().unwrap())
        .await
        .unwrap_or_else(|_| panic!("connect {url}"))
}

/// Next raw stream item (the wire bytes are what compatibility asserts on).
async fn next_value(
    rx: &mut tokio::sync::mpsc::Receiver<Value>,
    what: &str,
) -> Value {
    tokio::time::timeout(Duration::from_secs(5), rx.recv())
        .await
        .unwrap_or_else(|_| panic!("timeout waiting for {what}"))
        .unwrap_or_else(|| panic!("stream closed waiting for {what}"))
}

fn decode(value: &Value, what: &str) -> TranscriptUpdate {
    serde_json::from_value(value.clone()).unwrap_or_else(|e| panic!("decode {what}: {e}"))
}

/// The upsert list of a delta frame (a resume/live path shape helper).
fn upserts_of(update: &TranscriptUpdate, what: &str) -> Vec<roboco_doc::TranscriptUpsert> {
    match &update.frame {
        roboco_doc::TranscriptFrame::Delta { upsert, .. } => upsert.clone(),
        other => panic!("expected a delta for {what}, got {other:?}"),
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn transcript_resume_over_the_real_listener() {
    let dir = tempfile::tempdir().unwrap();
    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    core.workspace
        .create_space("space", &core.device_id, &dir.path().to_string_lossy(), None, false)
        .unwrap();
    core.workspace
        .create_chat(CHAT, Some("space"), None, None, None)
        .unwrap();
    let remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();

    // 0. The capability gates the opt-in params.
    let client = paired_client(&remote, dir.path()).await;
    let info = client.call(methods::ENGINE_INFO, json!({})).await.unwrap();
    assert!(
        info["capabilities"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c == "transcript-resume-v1"),
        "EngineInfo must advertise transcript-resume-v1"
    );

    // 1. Seed 500 entries: user rows through the handle, assistant rows
    // through the doc.
    let handle = core.doc_host.open(CHAT).unwrap();
    for i in 0..250 {
        handle
            .write_user_message(&format!("u{i}"), &text(i), i as i64)
            .unwrap();
        handle
            .doc()
            .push_message(&assistant_entry(&format!("m{i}"), &text(500 + i)))
            .unwrap();
    }
    // The last assistant row flips status while the phone is away.
    let flip_target = "m249";

    // 2. Subscribe with digests: the reset carries a digest for every entry.
    let mut rx = client
        .subscribe(
            methods::WATCH_DOC_MESSAGES,
            json!({ "chatId": CHAT, "digests": true }),
        )
        .await
        .unwrap();
    let reset_value = next_value(&mut rx, "opening digests reset").await;
    let reset = decode(&reset_value, "opening digests reset");
    let held = match reset.frame {
        roboco_doc::TranscriptFrame::Reset { reset } => reset,
        other => panic!("expected a reset, got {other:?}"),
    };
    assert_eq!(held.len(), 500);
    let reset_digests = reset.digests.expect("reset carries digests");
    assert_eq!(reset_digests.len(), 500, "a digest for every entry");
    for entry in &held {
        assert!(
            reset_digests.contains_key(&entry.id),
            "entry {} lacks a digest",
            entry.id
        );
    }
    let mut digests: std::collections::HashMap<String, String> =
        reset_digests.into_iter().collect();
    drop(rx);
    drop(client);

    // 3. While the phone is away: append two entries (one of them a live
    // streaming row that keeps growing), and flip one recent status.
    handle
        .doc()
        .push_message(&assistant_entry("a-extra", &text(900)))
        .unwrap();
    let mut writer = SegmentWriter::begin(handle.doc(), "a-live", "host", 1_000).unwrap();
    writer
        .sync(&[MessagePart::Text {
            id: "t0".into(),
            text: "streaming part".into(),
        }])
        .unwrap();
    writer
        .sync(&[MessagePart::Text {
            id: "t0".into(),
            text: "streaming partial reply that kept growing while away".into(),
        }])
        .unwrap();
    drop(writer);
    handle
        .doc()
        .set_message_status(flip_target, MessageStatus::Complete)
        .unwrap();

    // 4. Reconnect and resume from the held copy. The first item is a delta,
    // not a reset, and applying it lands exactly on a fresh reset's state.
    let client = paired_client(&remote, dir.path()).await;
    let resume =
        roboco_doc::TranscriptResume::from_held(&held, &digests, 64).expect("held copy resumable");
    let mut resumed = client
        .subscribe(
            methods::WATCH_DOC_MESSAGES,
            json!({ "chatId": CHAT, "resume": resume }),
        )
        .await
        .unwrap();
    let first_value = next_value(&mut resumed, "resumed catch-up frame").await;
    let first = decode(&first_value, "resumed catch-up frame");
    // The catch-up delta carries digests for exactly the entries it upserts.
    let upserted = upserts_of(&first, "resumed catch-up frame");
    let delta_digests = first.digests.as_ref().expect("delta carries digests").clone();
    assert!(apply_update_digests(&mut digests, &first));
    let catch_up = match first.frame.clone() {
        roboco_doc::TranscriptFrame::Delta { .. } => first.frame,
        roboco_doc::TranscriptFrame::Reset { reset } => {
            panic!("resume must not reset: {} entries", reset.len())
        }
    };

    // A plain subscription resets to the authoritative state.
    let plain = paired_client(&remote, dir.path()).await;
    let mut plain_rx = plain
        .subscribe(methods::WATCH_DOC_MESSAGES, json!({ "chatId": CHAT }))
        .await
        .unwrap();
    let plain_value = next_value(&mut plain_rx, "plain reset").await;
    let plain_reset = decode(&plain_value, "plain reset");
    let authoritative = match plain_reset.frame {
        roboco_doc::TranscriptFrame::Reset { reset } => reset,
        other => panic!("expected a plain reset, got {other:?}"),
    };

    let mut current = held.clone();
    apply_transcript_frame(&mut current, catch_up).expect("resume frame applies");
    assert_eq!(&current, &authoritative, "resume == fresh reset");

    assert_eq!(upserted.len(), 3, "the last three entries changed");
    assert_eq!(delta_digests.len(), 3);
    for upsert in &upserted {
        assert!(delta_digests.contains_key(&upsert.entry.id));
    }

    // Size bound: the resumed first item is under 10% of the full reset.
    let resumed_bytes = serde_json::to_vec(&first_value).unwrap().len();
    let reset_bytes = serde_json::to_vec(&plain_value).unwrap().len();
    assert!(
        resumed_bytes * 10 < reset_bytes,
        "resumed first item {resumed_bytes} bytes must be < 10% of the {reset_bytes}-byte reset"
    );

    // 5. Live updates keep carrying digests, and the client map stays exact.
    handle
        .write_user_message("u-after", &text(999), 2_000)
        .unwrap();
    let live_value = next_value(&mut resumed, "live delta after a write").await;
    let live = decode(&live_value, "live delta");
    let live_upserts = upserts_of(&live, "live delta");
    assert!(live_upserts.iter().any(|u| u.entry.id == "u-after"));
    let live_digests = live.digests.as_ref().expect("live delta carries digests");
    for upsert in &live_upserts {
        assert!(
            live_digests.contains_key(&upsert.entry.id),
            "digest for the live upsert {}",
            upsert.entry.id
        );
    }
    apply_transcript_frame(&mut current, live.frame.clone()).expect("live delta applies");
    assert!(apply_update_digests(&mut digests, &live));

    // Recompute entries and the digest map from a fresh digests reset: the
    // resumed + live path matches exactly, and the map stayed in step.
    let fresh = paired_client(&remote, dir.path()).await;
    let mut fresh_rx = fresh
        .subscribe(
            methods::WATCH_DOC_MESSAGES,
            json!({ "chatId": CHAT, "digests": true }),
        )
        .await
        .unwrap();
    let fresh_value = next_value(&mut fresh_rx, "fresh digests reset").await;
    let fresh_reset = decode(&fresh_value, "fresh digests reset");
    let fresh_entries = match fresh_reset.frame {
        roboco_doc::TranscriptFrame::Reset { reset } => reset,
        other => panic!("expected a fresh reset, got {other:?}"),
    };
    assert_eq!(&current, &fresh_entries, "resumed + live == fresh reset");
    let recomputed: std::collections::HashMap<String, String> = fresh_reset
        .digests
        .expect("fresh reset digests")
        .into_iter()
        .collect();
    assert_eq!(digests, recomputed, "apply_update_digests keeps the map exact");
    drop(fresh_rx);
    drop(fresh);

    // 6. A mismatched summary falls back to an ordinary reset.
    let mut tampered = roboco_doc::TranscriptResume::from_held(&current, &digests, 64).unwrap();
    tampered.prefix = flip_one_hex_char(&tampered.prefix);
    let mismatch = paired_client(&remote, dir.path()).await;
    let mut mismatch_rx = mismatch
        .subscribe(
            methods::WATCH_DOC_MESSAGES,
            json!({ "chatId": CHAT, "resume": tampered }),
        )
        .await
        .unwrap();
    let mismatch_first = decode(&next_value(&mut mismatch_rx, "mismatch fallback").await, "mismatch");
    assert!(
        matches!(mismatch_first.frame, roboco_doc::TranscriptFrame::Reset { .. }),
        "a mismatched prefix must reset"
    );
    drop(mismatch_rx);
    drop(mismatch);

    // 7. Restart on the same data dir: digests are deterministic, so the
    // held copy resumes with an empty delta.
    drop(resumed);
    drop(plain_rx);
    drop(plain);
    drop(client);
    drop(handle);
    drop(remote);
    core.shutdown().await;
    drop(core);

    let core = EngineCore::assemble_with_profile(
        EngineProfile::local(dir.path()).unwrap(),
        Arc::new(HarnessRegistry::new()),
        roboco_engine::HarnessId::Mock,
    )
    .unwrap();
    let remote = roboco_engine::serve_engine_remote(
        "127.0.0.1:0".parse().unwrap(),
        core.rpc_service(),
        dir.path(),
    )
    .await
    .unwrap();
    let client = paired_client(&remote, dir.path()).await;
    let resume =
        roboco_doc::TranscriptResume::from_held(&current, &digests, 64).expect("still resumable");
    let mut restarted = client
        .subscribe(
            methods::WATCH_DOC_MESSAGES,
            json!({ "chatId": CHAT, "resume": resume }),
        )
        .await
        .unwrap();
    let after_restart = decode(&next_value(&mut restarted, "post-restart resume").await, "restart");
    match &after_restart.frame {
        roboco_doc::TranscriptFrame::Delta { upsert, remove, count, .. } => {
            assert!(upsert.is_empty(), "nothing changed across the restart");
            assert!(remove.is_empty());
            assert_eq!(*count, current.len());
        }
        other => panic!("restart resume must be a delta, got {other:?}"),
    }
    drop(restarted);

    // 8. Compatibility: a plain subscription (no new params) sees no
    // `digests` key on any frame — reset or live delta.
    let compat = paired_client(&remote, dir.path()).await;
    let mut compat_rx = compat
        .subscribe(methods::WATCH_DOC_MESSAGES, json!({ "chatId": CHAT }))
        .await
        .unwrap();
    let compat_reset_value = next_value(&mut compat_rx, "compat reset").await;
    assert!(compat_reset_value.get("digests").is_none());
    let doc = core.doc_host.open(CHAT).unwrap();
    doc.write_user_message("u-compat", &text(998), 3_000).unwrap();
    let compat_delta_value = next_value(&mut compat_rx, "compat live delta").await;
    assert!(
        compat_delta_value.get("digests").is_none(),
        "plain streams never carry digests"
    );
    drop(compat_rx);
    drop(compat);
    drop(remote);
    core.shutdown().await;
}

/// Flip one hex character without changing the length.
fn flip_one_hex_char(digest: &str) -> String {
    let mut chars: Vec<char> = digest.chars().collect();
    let i = chars.iter().position(|c| *c != '0').unwrap_or(0);
    chars[i] = '0';
    chars.into_iter().collect()
}
