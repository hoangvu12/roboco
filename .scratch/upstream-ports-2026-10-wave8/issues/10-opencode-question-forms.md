# 10 — OpenCode v2: answer the question tool's session forms (upstream #833, `9609f994a`)

**What to build:** Port `9609f994a` (#833): the opencode adapter answers
the question tool's session forms — the structured question/answer flow
(question panels with options, multi-question sessions) expressed through
opencode's protocol, replacing the plain-text fallback.

**Why now:** roboco's opencode adapter is current from the perf wave
(ticket 5c: stop-in-place, model-on-send); the change is harness-only
(+317/−59 in `opencode/mod.rs`, +133 tests) with no UI or wire
involvement beyond the question surfaces roboco already renders.

**Steps:**
1. `git show 9609f994a` — `harness/src/opencode/mod.rs` and
   `harness/src/opencode/tests.rs`.
2. Port the session-form protocol handling and its tests, adapting to
   the shapes roboco's question-panel UI (ACP-lineage) expects.
3. Verify the fake-opencode fixtures the tests use carry over; adapt
   fixture paths per AGENTS.md.

**Excluded upstream hunks:** none expected.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo test -p roboco-harness -- opencode`.
Source: `.scratch/upstream-drift/2026-10-22.md` § engine tickets.

- [ ] Session-form handling + tests ported
- [ ] Commit records upstream SHA `9609f994a` (#833)

## Comments
