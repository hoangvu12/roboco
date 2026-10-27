//! Synthetic Pi RPC peer (Rust, cross-platform): speaks the first-party
//! JSONL wire the native driver maps, over real stdio. Never built into the
//! application. Driven by `crates/harness/tests/pi_rpc.rs`.
//!
//! Scenarios are picked from the prompt text: a normal turn, an errored
//! message, a rejected prompt, a recovered auto-retry, compaction still
//! running at the settle barrier, a handled command, extension errors,
//! stdout noise with a lone surrogate escape, an oversized frame, a steer
//! rejected while pi streams, input left queued while idle (recovered by
//! clear_queue), and a crash. Turns run in a worker thread, so the main
//! loop keeps answering state queries and accepting or rejecting prompts
//! while a run streams — exactly the mid-run shape the driver's settle
//! probe and steering surface exercise.
use serde_json::{Value, json};
use std::io::{BufRead, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

fn emit(v: Value) {
    let mut out = std::io::stdout().lock();
    writeln!(out, "{v}").unwrap();
    out.flush().unwrap();
}

fn response(v: &Value, data: Value) {
    emit(json!({"type":"response","id":v["id"],"command":v["type"],"success":true,"data":data}));
}

fn reject(v: &Value, error: &str) {
    emit(json!({"type":"response","id":v["id"],"command":v["type"],"success":false,"error":error}));
}

/// A user echo: the delivery-record consumption receipt.
fn user_message(text: &str) {
    emit(json!({"type":"message_start","message":{"role":"user","content":[{"type":"text","text":text}]}}));
    emit(json!({"type":"message_end","message":{"role":"user","content":[{"type":"text","text":text}]}}));
}

fn assistant_chunks(chunks: &[&str], stop: &str, error: Option<&str>) {
    emit(json!({"type":"message_start","message":{"role":"assistant","content":[]}}));
    for chunk in chunks {
        emit(json!({
            "type":"message_update",
            "usage":{"input":12,"output":3,"totalTokens":15,"cost":{}},
            "assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":chunk}
        }));
    }
    let mut message = json!({
        "role":"assistant","content":[],"stopReason":stop,
        "usage":{"input":12,"output":3}
    });
    if let Some(error) = error {
        message["errorMessage"] = json!(error);
    }
    emit(json!({"type":"message_end","message":message}));
}

fn turn_end() {
    emit(json!({"type":"turn_end","message":{"role":"assistant","content":[],"stopReason":"stop"},"toolResults":[]}));
    emit(json!({"type":"agent_end","messages":[],"willRetry":false}));
    emit(json!({"type":"agent_settled"}));
}

/// The peer's shared live state, mirrored into `get_state`.
#[derive(Clone, Default)]
struct Live {
    active: Arc<AtomicBool>,
    compacting: Arc<AtomicBool>,
    /// Input pi left queued while idle: reported, never started (until the
    /// driver's clear_queue recovers it).
    stranded: Arc<AtomicBool>,
    queue: Arc<Mutex<Vec<String>>>,
}

/// Run one turn's events for `text` in the calling thread (the worker).
fn run_turn(text: &str, live: &Live) {
    user_message(text);
    if text == "error" {
        emit(json!({"type":"turn_start"}));
        assistant_chunks(&["doomed"], "error", Some("provider returned 529"));
        emit(json!({"type":"turn_end","message":{"role":"assistant","content":[],"stopReason":"error"},"toolResults":[]}));
        emit(json!({"type":"agent_end","messages":[],"willRetry":false}));
        emit(json!({"type":"agent_settled"}));
        return;
    }
    if text == "retry" {
        emit(json!({"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":10,"errorMessage":"overloaded"}));
        emit(json!({"type":"auto_retry_end","success":true,"attempt":1}));
        emit(json!({"type":"turn_start"}));
        assistant_chunks(&["reply:retry"], "stop", None);
        turn_end();
        return;
    }
    if text == "compact-tail" {
        // The turn settles, then threshold compaction is still running when
        // the settle barrier reads state — and it ends with no settle event
        // after it (the barrier must re-check at compaction_end).
        emit(json!({"type":"turn_start"}));
        assistant_chunks(&["compacting turn"], "stop", None);
        emit(json!({"type":"turn_end","message":{"role":"assistant","content":[],"stopReason":"stop"},"toolResults":[]}));
        emit(json!({"type":"agent_end","messages":[],"willRetry":false}));
        live.active.store(false, Ordering::SeqCst);
        live.compacting.store(true, Ordering::SeqCst);
        emit(json!({"type":"agent_settled"}));
        emit(json!({"type":"compaction_start","reason":"threshold"}));
        std::thread::sleep(std::time::Duration::from_millis(300));
        live.compacting.store(false, Ordering::SeqCst);
        emit(json!({"type":"compaction_end","reason":"threshold","result":{"estimatedTokensAfter":9}}));
        return;
    }
    if text == "ext-error" {
        emit(json!({"type":"extension_error","extensionPath":"/x.ts","event":"tool_call","error":"handler threw"}));
        emit(json!({"type":"turn_start"}));
        assistant_chunks(&["reply:ext-error"], "stop", None);
        turn_end();
        return;
    }
    if text == "noise" {
        // A dependency's stray console output, then a JS string sliced mid
        // surrogate pair.
        {
            let mut out = std::io::stdout().lock();
            writeln!(out, "loading plugin...").unwrap();
            writeln!(
                out,
                r#"{{"type":"message_update","assistantMessageEvent":{{"type":"text_delta","contentIndex":0,"delta":"cut \ud83d"}}}}"#
            )
            .unwrap();
            out.flush().unwrap();
        }
        emit(json!({"type":"turn_start"}));
        assistant_chunks(&["reply:noise"], "stop", None);
        turn_end();
        return;
    }
    if text == "oversized" {
        // One frame over the driver's 32 MiB bound: it costs that frame,
        // never the run.
        let huge = format!(
            r#"{{"type":"message_update","assistantMessageEvent":{{"type":"text_delta","contentIndex":0,"delta":"{}"}}}}"#,
            "x".repeat(33 * 1024 * 1024)
        );
        {
            let mut out = std::io::stdout().lock();
            writeln!(out, "{huge}").unwrap();
            out.flush().unwrap();
        }
        emit(json!({"type":"turn_start"}));
        assistant_chunks(&["reply:oversized"], "stop", None);
        turn_end();
        return;
    }
    if text == "crash" {
        assistant_chunks(&["partial text"], "stop", None);
        eprintln!("pi peer fatal: crash scenario stderr context");
        std::process::exit(23);
    }
    if text == "slow" {
        // Streams long enough for a steer to land mid-run, then settles
        // with its reply.
        emit(json!({"type":"turn_start"}));
        assistant_chunks(&["working"], "stop", None);
        std::thread::sleep(std::time::Duration::from_millis(400));
        assistant_chunks(&["reply:slow"], "stop", None);
        turn_end();
        return;
    }
    emit(json!({"type":"turn_start"}));
    assistant_chunks(&[&format!("reply:{text}")], "stop", None);
    turn_end();
}

/// The worker: run the turn, then keep draining the queue (steers pi
/// accepted mid-run start once the run rests).
fn worker(mut text: String, live: Live) {
    loop {
        run_turn(&text, &live);
        live.active.store(false, Ordering::SeqCst);
        let next = live.queue.lock().unwrap().first().cloned();
        match next {
            Some(next) => {
                live.queue.lock().unwrap().remove(0);
                text = next;
            }
            None => break,
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--version") {
        println!("0.87.0-peer");
        return;
    }
    // A broken test must not leave even a direct fixture process behind.
    std::thread::spawn(|| {
        std::thread::sleep(std::time::Duration::from_secs(30));
        std::process::exit(99);
    });
    let resumed = args
        .iter()
        .position(|a| a == "--session")
        .map(|i| args[i + 1].clone());
    // The session file lives in the run's cwd (the driver spawns with it);
    // a resume keeps the stored id, a fresh start mints one.
    let file = resumed.clone().unwrap_or_else(|| {
        std::env::current_dir()
            .unwrap()
            .join(".pi-peer-session.jsonl")
            .display()
            .to_string()
    });
    let session = resumed
        .as_deref()
        .and_then(|path| {
            std::fs::read_to_string(path)
                .ok()
                .and_then(|s| s.lines().next().map(str::to_owned))
        })
        .map(|line| {
            serde_json::from_str::<Value>(&line)
                .ok()
                .and_then(|v| v["id"].as_str().map(str::to_owned))
                .unwrap_or_else(|| "pi-peer-session".into())
        })
        .unwrap_or_else(|| "pi-peer-session".into());
    if resumed.is_none() {
        let _ = std::fs::write(&file, format!("{}\n", json!({"id":session})));
    }

    let live = Live::default();
    let mut model = "mock".to_owned();
    let mut thinking = "medium".to_owned();
    // `stranded` only strands once: the clear_queue recovery re-delivers
    // the text, and that re-delivery runs as a normal turn.
    let mut stranded_once = false;

    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap();
        let Ok(v) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        match v["type"].as_str().unwrap_or("") {
            "get_state" => response(
                &v,
                json!({
                    "sessionId":session,
                    "sessionFile":file,
                    "isStreaming":live.active.load(Ordering::SeqCst),
                    "isCompacting":live.compacting.load(Ordering::SeqCst),
                    "pendingMessageCount":u64::from(live.stranded.load(Ordering::SeqCst)),
                    "model":{"id":model,"provider":"mock","contextWindow":128000},
                    "thinkingLevel":thinking,
                }),
            ),
            "get_available_models" => response(
                &v,
                json!({"models":[
                    {"id":"mock","provider":"mock","name":"Mock","reasoning":true,"contextWindow":128000},
                    {"id":"mock-2","provider":"mock","name":"Mock 2","reasoning":true,"contextWindow":128000}
                ]}),
            ),
            "set_model" => {
                model = v["modelId"].as_str().unwrap_or("mock").to_owned();
                response(&v, json!({"id":model}));
            }
            "set_thinking_level" => {
                thinking = v["level"].as_str().unwrap_or("medium").to_owned();
                response(&v, json!({}));
            }
            "set_steering_mode" => response(&v, json!({})),
            "abort" => {
                live.active.store(false, Ordering::SeqCst);
                response(&v, json!({}));
                emit(json!({"type":"agent_settled"}));
            }
            "clear_queue" => {
                let drained: Vec<String> = std::mem::take(&mut *live.queue.lock().unwrap());
                live.stranded.store(false, Ordering::SeqCst);
                response(&v, json!({"steering":drained,"followUp":[]}));
            }
            "get_session_stats" => response(
                &v,
                json!({"sessionTokens":{"input":12,"output":3,"total":15}}),
            ),
            "get_entries" => response(&v, json!({"entries":[],"leafId":null})),
            "prompt" => {
                let text = v["message"].as_str().unwrap_or("").to_owned();
                if text == "reject" {
                    reject(&v, "A prompt is already running (busy)");
                    continue;
                }
                if text == "handled" {
                    // pi runs the extension command itself: accepted,
                    // handled — no run and no agent_settled ever follow.
                    response(&v, json!({"disposition":"handled"}));
                    continue;
                }
                if text == "stranded" && !stranded_once {
                    // Accepted and queued; pi then leaves it queued while
                    // idle — never started. The driver's settle probe
                    // clears the queue and the text re-delivers.
                    stranded_once = true;
                    response(&v, json!({"disposition":"queued"}));
                    live.queue.lock().unwrap().push(text);
                    live.stranded.store(true, Ordering::SeqCst);
                    continue;
                }
                if live.active.load(Ordering::SeqCst) {
                    // A steer mid-run: queued (old-style acceptance — no
                    // disposition), with the queue announced.
                    live.queue.lock().unwrap().push(text.clone());
                    let queued = live.queue.lock().unwrap().clone();
                    emit(json!({"type":"queue_update","steering":queued,"followUp":[]}));
                    response(&v, json!({}));
                    continue;
                }
                live.active.store(true, Ordering::SeqCst);
                response(&v, json!({"disposition":"started"}));
                let live = live.clone();
                std::thread::spawn(move || worker(text, live));
            }
            _ => {}
        }
    }
}
