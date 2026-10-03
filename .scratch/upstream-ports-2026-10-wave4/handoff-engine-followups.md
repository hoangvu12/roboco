# Handoff: engine-lane follow-ups (wave4/engine)

Written after the engine lane's 5 tickets landed; the next session picks up
three follow-up tasks the user ordered in-session. Read this whole file first.

## Current state

- Worktree: `/home/ubuntu/roboco-w4-engine`, branch `wave4/engine` (never push it).
- **5 ticket commits done + verified + reviewed** (fmt-clean, one per ticket):
  `36222bb2` (02 reaper, aaeede8b), `7b4ed76c` (03 spin, 4d106b82),
  `711b6ed2` (01 lifecycle, cc8b147a), `55593697` (04 diff-sync, bf64e71f),
  `f0bd0afd` (05 malloc-trim, 4a7ef2cc). Lane ready to merge into `main`
  (ticket order, coordinator's call).
- **Dirty file (intentional):** `crates/engine/tests/message_queue.rs` — the
  RED regression test `send_next_promotes_the_row_to_lead_the_queue_at_turn_end`
  replaces the old `steer_now_leaves_the_row_when_the_agent_cannot_steer_mid_turn`
  (which pinned the bug). Confirmed red: panics at the `.expect()` in
  `steer_queued_now` because the engine currently returns
  `Err("the selected agent cannot accept mid-turn steering")`.
- Upstream SHAs are viewable from any worktree (`git show <sha>` — shared object store).

## The three tasks (user: "do first 2, ignore last 2" + the queue bug)

### Task A — pi 120s startup budget (upstream df0cd298 #630)

`crates/harness/src/pi/mod.rs` ~line 248: `startup_timeout:
Duration::from_secs(60)` default (builder `with_startup_timeout` exists, line
273). Upstream hard-codes 120s (their pi/mod.rs:618,
`timeout(Duration::from_secs(120), self.bootstrap(&mut backlog))` —
cold-extension startup can exceed 60s; we spawn the same per-run
`--extension` bridge). Fix: default 60s → 120s, keep the builder, extend the
field doc comment. No upstream test for it; verify with `cargo check -p
roboco-harness --tests -j 3` + existing pi tests still green.

### Task B — dropped-input-resolver stays an error (upstream df0cd298 #630)

Port upstream's engine input-bridge hunk into
`crates/engine/src/sessions.rs` ~line 530 (the `request_input` closure — our
code matches their pre-change shape exactly). Their diff:

- Split the single oneshot into `tx`/`rx` (returned to the harness) and
  `answer_tx`/`answer_rx` (parked in `pending_inputs`).
- Spawn a forwarder: `select! { answer = answer_rx => if let Ok(answer) = answer { let _ = tx.send(answer); }, _ = tx.closed() => { lock(&pending).remove(&request_id); let _ = engine_tx.send(AgentEvent::InputResolved { request_id }); } }`
  (see `git show df0cd298 -- crates/engine/src/sessions.rs`).
- Only Ok answers forward — a dropped `answer_tx` (Err) forwards nothing, so
  the harness's `rx` resolves Err = error, never an empty answer.
- `respond_input` (line ~779) unchanged — it sends to whatever is parked.
- IMPORTANT: upstream KEEPS the run-end drain as empty answers
  (`for (_, tx) in drain() { tx.send(Vec::new()) }`, ~line 2648) and the
  parked post-turn auto-decline (~line 2431) as `Vec::new()` — do NOT change
  those; only the dropped-resolver path is new.

### Task C — the queue "Send next" bug (user-reported desktop bug)

**Symptom:** queue row's "Send next" button (shown exactly for harnesses that
cannot steer mid-turn, e.g. SteeringMode::TurnBoundary) → click → RPC
`SteerQueuedMessageNow` → engine error → UI "Send failed"/"Couldn't send that
message" toast.

**Root cause:** Roboco ported upstream `731697b6` (#498)'s UI half only
(`f793862a "Port upstream 731697b6 (ui)"`) — the engine half was never
carried, so `doc_host.steer_queued_now` (`crates/engine/src/doc_host.rs`
~line 1522) still has the old #277-era hard rejection while the UI advertises
"Send at the next turn". Fix = port the engine half of `731697b6`
(`git show 731697b6 -- crates/engine/src/doc_host.rs crates/engine/src/sessions.rs`):

1. **Remove** from `steer_queued_now`: the `sessions engine not wired` error
   and the `!sessions.steers_mid_turn(...)` rejection.
2. **Add** before the `take_queued` fallthrough:
   `if self.sessions().is_some_and(|s| s.defers_to_turn_end(chat_id, None)) { take item; insert at steer_slot; queue_paused=false; publish_queue; return Ok(true) }`.
3. **`SessionsEngine::defers_to_turn_end(chat_id, request: Option<(HarnessId, &RunRequest)>) -> bool`** (sessions.rs, near `steers_mid_turn` line ~289):
   `turn_in_flight(chat_id)` AND live run `(runtime_config.harness_id, steerable && request.is_none_or(|(id, r)| h.runtime_config.can_route(id, r)))` AND `!steers_mid_turn(harness)`. All deps exist (RunHandle has `steerable` + `runtime_config`).
4. **`ChatDocHandle::steer_slot(&self, id) -> Result<usize, DocError>`** + new field `steered_rows: Mutex<Vec<String>>` (init `Mutex::new(Vec::new())` in the handle constructor next to `drain_lock`). Slot = count of leading queue rows already in `steered_rows` (retain against live queue first, then record `id`).
5. **Drain site:** wherever the queued head is taken for dispatch (the
   `take_queued(&head.id)` in the drain/dispatch path), add
   `lock(&handle.steered_rows).retain(|row| row != &item.id);`.
6. **Deliberately EXCLUDE** `command_drain_lock` (also in 731697b6 — Run/Steer
   dispatch race hardening, separable; record as excluded in the commit).

The red test (already in the tree, Task 0) goes green: Ok(true), row leads
(`["promoted", "ordinary"]`), id stable, delivered first at turn end, ordinary
row follows. Also re-run the whole `--test message_queue` suite
(`steer_now_starts_the_next_turn_when_the_previous_turn_is_already_idle` and
`held_policy_keeps_a_steerable_message_visible_until_steer_now` must stay
green — they exercise the non-defers paths).

## User decisions on record (do NOT revisit)

- Adopt: Task A (120s) + Task B (dropped-resolver) — "do first 2".
- **Ignore:** `pi/PROTOCOL.md` doc port and legacy ACP session resume
  (`~/.pi/pi-acp/session-map.json` shim) — "ignore last 2". Record as
  deliberately excluded in the adoption commits.
- No structural convergence with upstream's #630 pi driver; ours stays.

## Commits + verification

- One commit per task on `wave4/engine`, after the red test goes green
  (Task C commit includes the test). Message style: follow the lane's five
  (title with `(upstream <sha>, #PR)`, body explains, records exclusions,
  cites the user report for Task C).
- Worker env: `source ~/.bashrc_pi`; `-j 3`; no `cargo clean`; touch the file
  before check (sccache); target already symlinked.
- nextest gotchas learned here:
  - Bare filters match test NAMES, not binaries — use
    `-E 'binary(x) or test(y)'`.
  - `cargo nextest run -p roboco-harness` WITHOUT `--lib`/`--test` fails on 3
    pre-existing broken examples (`grok_subagent_probe`, `opencode_subagent_probe`,
    `opencode_turn_probe` — missing `execution_lease`; upstream fixed them in
    df0cd298, ticket 30's scope). Always select targets explicitly.
  - `--lib` cannot repeat across packages; run per package.
- fmt: repo has ~200 pre-existing drift hunks on main (no fmt gate) — only
  keep NEW code fmt-clean (`cargo fmt --check -p <pkg>` and compare against
  base before blaming yourself).

## Pre-existing failures on main — do NOT chase (verified on clean base)

1. `roboco-engine::previews preview_watch_follows_the_session_checkout_and_owning_device`.
2. `roboco-harness acp::tests::antigravity_named_home_settings_preserve_business_auth` (ticket 09's surface, harness lane).
3. The 3 broken harness examples above (ticket 30's surface).

Baseline for the lane's full suites: engine 505/506, harness lib 297/298
(both failures pre-existing). `cargo check -p roboco` clean on host
(aarch64-linux-gnu); windows cross-check blocked by environmental `ring`
failure (identical on base; windows.yml CI covers post-merge).
