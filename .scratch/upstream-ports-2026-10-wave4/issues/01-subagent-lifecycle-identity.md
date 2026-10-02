# 01 — Subagent lifecycle identity and restart recovery

**What to build:** A subagent's spawn identity survives parent-session
resumes, stale chips are recovered instead of duplicated or lost, and
claude cold-resume history scanning moves off the runtime: read the native
transcript with std I/O under `spawn_blocking`, parse only lines that can
name a spawn or its agent, stop on read errors, and ignore a subagent's own
nested spawns (long transcripts reach 100+ MB; parsing every line as JSON
on a tokio worker stalled resumes).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `cc8b147a` (#676) — `crates/engine/src/doc_host.rs`
(+30), `crates/engine/src/sessions.rs` (±21), `crates/engine/tests/e2e.rs`
(+156), `crates/harness/src/claude/{mod,normalize,wire}.rs` (normalize +258).
All paths exist here with the same names.

**Verification budget:** `cargo check -p roboco-engine -p roboco-harness -j 3`;
targeted nextest `-p roboco-engine e2e` and the new claude normalize tests.

- [ ] Spawn identity stable across resumes; stale chips recovered
- [ ] History scan runs under spawn_blocking, skips sidechains/nested spawns
- [ ] Engine + harness tests green
- [ ] Port commit records upstream SHA

## Comments
