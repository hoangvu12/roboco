# 24 — Engine `completion_notices` suite times out on CI runners

**What to build:** Four `crates/engine/tests/completion_notices.rs` tests time out at the 30 s `wait_for` (`completion_notices.rs:285-299`) in 7 of ≈ 30 `ui-tests` runs (2026-10-17 → 27), and pass on rerun (ticket 11 triage):

- `capped_outcomes_merge_into_the_one_parked_notice_row` (4: 40065780800, 40256495998, 40261127256, 40472984050)
- `a_working_parent_receives_the_notice_after_its_turn_as_its_next_turn` (2: 39879365920, 40067795761 attempt 1)
- `a_deleted_parent_is_owed_nothing` (40256510710), `three_children_finishing_while_the_parent_works_merge_into_one_notice` (40261127256)

The file's own comment says the suite boots about 25 engines in parallel on a small runner; raising the wait from 10 to 30 s (46f37cb7b) did not stop it. Likely CPU starvation, but a lost-wakeup race in the notice writer is not ruled out, especially for `capped_outcomes_merge…`. `agent_origin::run_commands_stamp_their_origin_on_the_user_entry` failed once with a similar shape (40122161245).

Ticket 06's nextest retries hide this meanwhile, with flaky-pass reporting on; this ticket's acceptance runs with retries **off** for this binary.

**Blocked by:** none

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md` (from ticket 11)

- [ ] Reproduced locally under load (e.g. `taskset -c 0,1`, or many test threads on 2 cores), or a written reason why it can't be
- [ ] The race is fixed, or the binary's parallelism is capped (nextest test group / fewer threads) with the reason in a comment
- [ ] 20 consecutive green `engine-local-regressions` (or its 06 successor) runs with retries off for this binary

## Comments
