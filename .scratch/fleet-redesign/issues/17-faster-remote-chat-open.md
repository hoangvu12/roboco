# 17 — Opening a chat on another machine: fewer waits

**Why:** each request to another machine costs one round trip, about 160 ms over the user's relayed Tailcat link (ticket 16), and downloads run at about 1.2 MB/s. Opening a remote chat feels slow ("a bit slow when I open other devices' chats", 2026-10-28). The desktop already reuses one open connection per engine. Transcripts ride it as a `WatchDocMessages` subscription (`crates/ui/src/state.rs:3459-3463`, `crates/engine-client/src/registry.rs:449-456`). So the cost is **how many requests wait on each other** and **how many bytes come before the first paint**.

**What to build:**

1. **Measure first.**
   - Put a delay proxy in front of a loopback engine: 160 ms round trip, about 1.2 MB/s. The mobile tests' `stall_proxy` is a starting point.
   - Record every request the desktop and the phone make when a remote chat is selected: method, start, end, and what it waited on. Cover the transcript, file/git status, previews, harness and model catalogs, side chats, and so on.
   - Time to first message on screen, and to the full transcript, for a small chat and a big one (several MB).
   - Write the numbers in Comments.
2. **Then fix what the numbers show.** Likely candidates:
   - Show the cached transcript at once. The desktop keeps opened transcripts (remote-access docs, § Using several engines), and so does the phone, so render the cache and apply the reset or delta when it lands.
   - Send independent requests at the same time instead of one after another.
   - Check that `openingTail` really delivers the newest messages first and that the rest streams in behind them, without blocking input.
   - Defer anything that isn't visible (catalogs, previews) until after the first paint.
   - Don't re-fetch what an earlier open of the same chat already has (transcript resume digests).
3. **Re-measure with the same proxy, and add a regression test.** On the 160 ms proxy, opening a cached remote chat shows messages in at most one round trip plus render time. An uncached small chat needs at most about 2–3 round trips.

**Blocked by:** —

**Status:** done

**Parent:** `.scratch/fleet-redesign/spec.md`

- [x] Before/after table (desktop and phone, small and big chat) in Comments
- [x] A delay-proxy test that fails if chat open goes back to sequential round trips
- [x] No behaviour change on a fast or local engine (existing UI and mobile suites green)

## Comments

### 2026-10-28 — measured, fixed, re-measured (feature/remote-speed)

**The rig.** `roboco_engine_client::delay_proxy` (behind `test-support`) puts a loopback engine behind a link like the user's:
- 80 ms each way (160 ms round trip);
- 1.2 MB/s each way, shared by every connection;
- one round trip before a new connection's first byte.

It also parses the WebSocket RPC frames it carries, fragmented ones included, and logs every request: connection, method, when it was sent, and when its replies landed. That works for the desktop and the phone without instrumenting either.

The two measurements double as the regression tests:
- desktop: `crates/ui/src/shell/remote_open_tests.rs`, a full `Shell` window on a paired engine;
- phone: `crates/mobile-client/tests/remote_open_engine.rs`, the core with its production timings.

`ROBOCO_OPEN_TIMELINE=1` prints each open's request timeline.

The chats:
- small: 6 short turns;
- big: 300 turns × ~15 KB of text and tool output, 4.7 MB in a project that is a git checkout.

"First" is the first message in the client's state; render time is not included. "Full" is when the engine's last transcript frame for the open landed. KB is transcript bytes pulled for that open.

"Before" is `main` at `3c4cfb720`, with only the test helpers added. It was measured with the same tests, so the two columns are directly comparable.

**Desktop** (160 ms link):

| open | before: first | before: full / KB | after: first | after: full / KB |
|---|---|---|---|---|
| small, uncached | 489 ms (3.1 rtt)¹ | 489 ms / 4 | 495 ms (3.1 rtt)¹ | 495 ms / 4 |
| big, uncached | 658 ms (4.1 rtt) | 5.1 s / 4691 | **308 ms (1.9 rtt)** | 5.1 s / 4395 |
| small, warm (switch back) | 14 ms | 308 ms / 4 | 29 ms | 238 ms / 0 |
| big, from disk cache (app restart) | 69 ms | **4.3 s / 4691** | 107 ms | **548 ms / 34** |
| small, right after leaving a big chat mid-download | **3715 ms (23.2 rtt)** | 3.7 s / 4 | **435 ms (2.7 rtt)** | 435 ms / 4 |

¹ The ~220 ms before the first request leaves is client-side, the same before and after: the first chat view opening in a debug test build. On the wire it is one round trip; all four requests (transcript, queue, checkout diffs, previews) go out together.

**Phone** (core, production timings, same link):

| open | before: first | before: full / KB | after: first | after: full / KB |
|---|---|---|---|---|
| small, uncached | 205 ms (1.3 rtt) | 205 ms / 4 | 219 ms (1.4 rtt) | 219 ms / 4 |
| big, uncached | 543 ms (3.4 rtt) | 4.5 s / 4701 | **253 ms (1.6 rtt)** | 4.4 s / 4395 |
| small, cached (restart) | 1 ms | 230 ms / 0 | 1 ms | 210 ms / 0 |
| big, cached (restart) | 20 ms | 794 ms / 34 | 19 ms | 440 ms / 34 |

**What the numbers showed** (desktop timelines; the phone was already in good shape):

1. **Head-of-line blocking.** The desktop carried every transcript on its one shared connection per engine. Leaving a big chat mid-download did not stop its bytes. The next chat's open, and everything else (queue, previews, the health probe), waited behind them: 3.7 s here, 2.8 s in the first rough run.
2. **The "keeps reconnecting" symptom reproduces here** (orchestrator's note on Laptop → PC). The desktop's remote health probe has a 5 s window, and it rode that same connection. On the 1.2 MB/s link a 4.8 MB transcript held the probe's reply for 3.4 s. Anything bigger, or a slower moment, times the probe out, and the registry then tears the connection down and redials. That drops every subscription, and the redial pulls the transcript again. The phone core already counts "bytes still arriving" as slow, not dead; the desktop did not.
3. **No resume on the desktop.** It kept no digests, so every reopen (from the disk cache after a restart, or after the warm copy was evicted) pulled the whole history again: 4.7 MB / 4.3 s for the big chat. The phone already resumed (34 KB).
4. **The opening tail was bounded by parts, not bytes.** 128 parts of big tool output came to ~370 KB, so the "first paint" frame alone cost 2–3 round trips of bandwidth on both clients.
5. **A sequential chain:** the sidebar's project icon lookup read 17 candidate paths one after another over the link, 1.5 round trips each: ~4 s per remote project per window, competing with chat opens. It showed in the first rough run, while the window opened.

Nothing else was sequential on chat open. Catalogs and side chats come from rows already held, not from requests on open, and the cached transcript already painted first on both clients.

**The fixes:**

1. **Desktop transcripts get their own connection** (`EngineTarget::subscribe_transcript`, `registry.rs`), the phone core's spare design. Each remote engine keeps one pre-dialed spare. A transcript stream takes it for itself, and dropping the stream (leaving the chat) closes that connection, so the rest of a big download stops at once. A new spare is dialed behind it. The local engine and a missing spare fall back to the shared connection. Subagent transcript watches use it too.
2. **The remote health probe counts progress** (`PROBE_PROGRESS_BYTES`, 8 KB). A timed-out probe while bytes keep arriving keeps the connection, the phone's rule. With fix 1 the probe rarely queues behind a transcript anyway.
3. **Desktop resume** (`state.rs`). The transcript watch keeps the engine's entry digests in step with every applied frame and saves them with the disk cache (`CachedTranscript`). A reopen with a held copy (warm or from disk) sends `resume` instead of `openingTail`. After a desync it sends a plain reset (no resend loop). Older engines without `transcript-resume-v1` keep today's behaviour.
4. **The opening tail is bounded by bytes as well** (`read_opening_tail_within`, 64 KB; the newest part always included). This benefits both clients with no client change.
   The cache keeps its file shape (`CachedTranscript`, which the phone already writes), and `EngineCache::load_transcript` now reads both shapes.
5. **The project icon lookup reads all candidates at once** and takes the first hit in priority order: one round trip instead of up to 17.

**Acceptance:**
- **Cached chat:** messages before any round trip on both clients (warm 29 ms, disk 107 ms desktop; 1–19 ms phone), then the resume lands one round trip later.
- **Uncached small chat:** 1–1.4 round trips on the wire.

**Tests:**
- `cargo test -p roboco-ui --lib remote_open_tests` and `cargo test -p roboco-mobile-client --test remote_open_engine` fail if:
  - an uncached small open takes over 3.5 round trips;
  - a cached open that waits for the link (its messages must show before the first transcript frame lands: a link-independent check, since loading a 4.7 MB cache took 70–210 ms of CPU in a loaded debug build);
  - a small open after leaving a big download over 3.5 (desktop);
  - a cached big reopen pulls more than a tenth of the history again (desktop).
- `delay_proxy` has its own tests (link delay, fragmented replies around control frames).
- `opening_tail_is_bounded_by_bytes_too` (doc).
- Full local suites (Windows, loaded machine), all ✅ except as noted:
  - `cargo check --workspace --all-targets`;
  - `roboco-ui --lib` (1661 tests, `--test-threads=1`);
  - `roboco-mobile-client` (lib + every integration test);
  - `roboco-mobile --lib`, `roboco-engine-client`, `roboco-doc`;
  - `roboco-engine --lib` plus `transcript_resume` and `remote_listener`.
- Not from this change:
  - `roboco-proto`'s `engine_info_uses_camel_case_fields` fails on `main` too: `ROOMS_V2` was added to the capability list without updating this test.
  - `roboco-engine`: the known host-only `source_control` git tests, and three Windows ConPTY `terminals::windows_tests` that time out here even alone (`terminals.rs` is untouched).
  - Two flakes under load that pass alone: `change_requests::failure_keeps_the_last_success`, and the phone's `busy_sends_queue_steer_and_stop_and_send` (2/2 alone).

**Deviations / notes:**
- The ticket asks for a delay proxy "test that fails if chat open goes back to sequential round trips". The bounds are in round trips of a 160 ms link, with slack for a debug build on a loaded CI box.
- The project-icon fix (5) is about window open, not chat open. It competed for the same link, so it is in.
- Not changed: an uncached big chat still takes ~4.4 s to arrive in full at 1.2 MB/s, because that is its size. It no longer blocks anything else, and later opens resume.
