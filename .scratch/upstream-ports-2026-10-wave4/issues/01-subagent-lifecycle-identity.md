# 01 — Subagent lifecycle identity and restart recovery

**What to build:** A subagent's spawn identity survives parent-session
resumes, stale chips are recovered instead of duplicated or lost, and
claude cold-resume history scanning moves off the runtime: read the native
transcript with std I/O under `spawn_blocking`, parse only lines that can
name a spawn or its agent, stop on read errors, and ignore a subagent's own
nested spawns (long transcripts reach 100+ MB; parsing every line as JSON
on a tokio worker stalled resumes).

**Blocked by:** 03 — sessions.rs/doc_host state; upstream #676 lands after
#637/#604 as well.

**Status:** ready-for-human

**Upstream SHAs:** `cc8b147a` (#676) — `crates/engine/src/doc_host.rs`
(+30), `crates/engine/src/sessions.rs` (±21), `crates/engine/tests/e2e.rs`
(+156), `crates/harness/src/claude/{mod,normalize,wire}.rs` (normalize +258).
All paths exist here with the same names.

**Verification budget:** `cargo check -p roboco-engine -p roboco-harness -j 3`;
targeted nextest `-p roboco-engine e2e` and the new claude normalize tests.

- [x] Spawn identity stable across resumes; stale chips recovered
- [x] History scan runs under spawn_blocking, skips sidechains/nested spawns
- [x] Engine + harness tests green
- [x] Port commit records upstream SHA

## Comments

- Ported `cc8b147a` (#676) by intent: `doc_host.rs`
  `mark_abandoned_streams` now settles this device's running subagent
  chips (failed) across its assistant entries — including completed parent
  turns — before stamping abandoned `streaming` entries, and republishes
  when chips changed; `sessions.rs` moves the in-place chip refresh out of
  the live-sink block into the `done` handling, so a resumed run finishes
  an older chip without ever opening a sink; `claude/{mod,normalize,wire}.rs`
  add `Normalizer::for_resume` (spawn_blocking std-I/O scan of
  `~/.claude/projects/*/<session>.jsonl`, fast-path line filter, sidechain/
  nested-spawn skip, agentId→spawn restore), `agent_tool_spawns`\  
  (SendMessage wake → original spawn), task_notification parent resolution
  via stable task id first, and the resume-aware wire doc comments.
- Tests ported: 3 claude normalize tests (resumed notifications, child
  frames, native-history restore), 2 e2e tests (`spawn_chip` helper,
  recovery settling chips in completed local entries only, persisted chip
  updated by a sink-less Done). Rebrand: `roboco_*` paths; no other
  adaptation needed.
- Exclusions: none — the upstream diff is engine+harness, no cloud/iOS
  code.
- Discovered (NOT fixed here, out of lane scope): three harness examples
  (`grok_subagent_probe`, `opencode_subagent_probe`, `opencode_turn_probe`)
  are stale on main — they construct `RunControls` without `execution_lease`,
  so any `cargo test --no-run -p roboco-harness` without explicit target
  selection fails (CI selects `--lib`/`--test` explicitly, so main stays
  green). Upstream's `df0cd298` (#630) updated the examples with
  `execution_lease: None` — that adoption belongs to ticket 30's #630
  comparison review (misc lane). Verification here used CI-style target
  selection (`--lib`, `--test e2e`).
- Verification: `cargo check -p roboco-engine -p roboco-harness --tests -j 3`
  clean; nextest: harness `--lib -E 'test(claude)'` 34/34 (normalize 19 incl.
  the 3 new), engine `--lib --test e2e` 347/347 (incl. the 2 new).
