# 24 — Engine `completion_notices` suite times out on CI runners

**What to build:** Four `crates/engine/tests/completion_notices.rs` tests time out at the 30 s `wait_for` (`completion_notices.rs:285-299`) in 7 of ≈ 30 `ui-tests` runs (2026-10-17 → 27), and pass on rerun (ticket 11 triage):

- `capped_outcomes_merge_into_the_one_parked_notice_row` (4: 40065780800, 40256495998, 40261127256, 40472984050)
- `a_working_parent_receives_the_notice_after_its_turn_as_its_next_turn` (2: 39879365920, 40067795761 attempt 1)
- `a_deleted_parent_is_owed_nothing` (40256510710), `three_children_finishing_while_the_parent_works_merge_into_one_notice` (40261127256)

The file's own comment says the suite boots about 25 engines in parallel on a small runner; raising the wait from 10 to 30 s (46f37cb7b) did not stop it. Likely CPU starvation, but a lost-wakeup race in the notice writer is not ruled out, especially for `capped_outcomes_merge…`. `agent_origin::run_commands_stamp_their_origin_on_the_user_entry` failed once with a similar shape (40122161245).

Ticket 06's nextest retries hide this meanwhile, with flaky-pass reporting on; this ticket's acceptance runs with retries **off** for this binary.

**Blocked by:** none

**Status:** implemented on branch `fix/completion-notices` (PR #42, 2026-10-29); the 20-run acceptance item is measured after merge

**Parent:** `.scratch/ci-speed/spec.md` (from ticket 11)

- [x] Reproduced locally under load (e.g. `taskset -c 0,1`, or many test threads on 2 cores), or a written reason why it can't be
- [x] The race is fixed, or the binary's parallelism is capped (nextest test group / fewer threads) with the reason in a comment
- [ ] 20 consecutive green `engine-local-regressions` (or its 06 successor) runs with retries off for this binary

## Comments

**2026-10-29 — root cause: a race, not CPU starvation.** `SessionDoc::queue_command` wrote a command entry key by key inside Loro's pending auto-commit transaction, with `originChatId` after `status`, the last required field. Opening a chat doc spawns its `chat_task` worker, which drains the command log at once, and reads on another thread see the uncommitted ops. So a chat's first command could parse as a complete Pending Run with `origin_chat_id: None` and execute as a user-typed send. The side chat's turn was then never notifiable, no outcome was recorded, and `wait_for(ledger_rows == N)` timed out. Instrumented failing run: `execute chat=chat-side-2 … kind=Run origin=None` for a Run queued with `originChatId: chat-parent`. `agent_origin::run_commands_stamp_their_origin_on_the_user_entry` (40122161245) fits the same shape: the first `QUEUE_COMMAND` on a fresh chat, with its origin lost.

Reproduced on the Windows dev host with no CPU pinning: about 1 suite failure in 3 runs (`capped_outcomes_merge…`, `a_deleted_parent…`, `an_outcome_after_the_observation…`, `a_late_observation_report…`). The 2026-10-28 failures after #40 (fleet-redesign 16/17) are this same race. It also hit `feature/web-removal` before #40 merged.

Fix (PR #42): write `id` last in `queue_command` and in `write_queued_map` (fresh queue rows had the same shape for `originChatId` / `deliveryGate` / `completionNotice`). Both readers require `id`, so an entry stays invisible until it's complete. Regression tests `a_concurrent_drain_never_reads_a_half_written_command` / `…_row` in `roboco-doc` fail at once with the old key order. Locally after the fix: 20/20 green in parallel and 3/3 with `--test-threads=1`. No parallelism cap was needed. Still open: the 20 consecutive CI runs with retries off.
