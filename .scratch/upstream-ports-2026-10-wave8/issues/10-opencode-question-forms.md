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

**Status:** done (ported in cc5325dcd)

**Verification budget:** `cargo test -p roboco-harness -- opencode`.
Source: `.scratch/upstream-drift/2026-10-22.md` § engine tickets.

- [x] Session-form handling + tests ported
- [x] Commit records upstream SHA `9609f994a` (#833)

## Comments

- 2026-10-24: Ported in cc5325dcd. Carried
  `v2_question_form_payload` (form.created → shared question.asked
  payload with field keys), `map_questions` → (key, question) pairs
  with minted panel ids, `form_answer_body` (field-keyed answers,
  multiselect arrays), the form-route reply arm (with the dropped-
  resolver no-op for forms, 1.x reject unchanged), removal of the
  harness's own InputRequested/InputResolved (the engine input bridge
  is sole emitter — roboco's Claude/Codex and sessions.rs already carry
  this), and all four tests. Adaptation: roboco's engine-local
  `UserInputQuestion` has no `prefill`/`multiline` fields, so those
  initializers were dropped (the only delta vs upstream). No fixture
  changes needed — the bus-frame tests use the in-file TurnWire
  harness. Verification: `--lib opencode::` 71/71 (incl. the 3 new unit
  tests + form-route async test), `--test opencode` 31/31; no new
  clippy or rustfmt findings (repo has pre-existing divergence in
  unrelated files).
