# 03 — Harness children must die with the engine (crash path) + stranded awaiting-input side chats

**What to build:** Close the two remaining ways a harness process
outlives its chat far past the 30-min idle window (observed on the
desktop app):

1. **Engine death without graceful shutdown orphans every harness child
   on unix.** The app quits gracefully (ui `on_app_quit` →
   `EngineHandle::shutdown` → `terminate` per chat — parked sessions
   included), and teardown kills the tree (`acp/child.rs` group TERM/KILL
   + `terminate_tree`). But if the engine process dies *hard* — crash,
   force-quit, OOM-kill, `kill -9`, or the daemon restarting it — none of
   that runs: `kill_on_drop` and `Drop` need a live owner. Every pi /
   claude / codex child plus its `roboco mcp` bridge is reparented and
   **stays alive forever**; a daemon restart then stacks new children on
   the old orphans. Windows is already safe
   (`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, `windows_process.rs:145`).
   Nothing in the repo uses `PR_SET_PDEATHSIG`/`pre_exec` today.
2. **An awaiting-input chat holds its child indefinitely — by design —
   and side chats can strand there.** `is_active` counts
   `SessionStatus::AwaitingInput` as a turn in flight, so the idle reaper
   never arms ("a chat parked on a question for days counts as busy",
   `account_failover.rs`). Correct for a user's own chat — but an
   agent-created side chat that asked a question (permission prompt,
   extension dialog) whose parent never answers holds its pi process
   forever. Nobody is ever coming.

**Blocked by:** None.

**Status:** ready-for-agent (fix A); fix B shape below needs one product
call.

**Fix A — `PR_SET_PDEATHSIG` pre-exec on every harness spawn (unix):**
add an ownership helper in `crates/harness/src/process.rs` (next to
`signal_target`) that stamps `prctl(PR_SET_PDEATHSIG, SIGKILL)` via
`Command::pre_exec` — build a `std::process::Command`, set the hook,
convert into the tokio `Command` the callers use (`From` impl). Apply it
at every harness spawn site: `acp::child::configure` (pi and the ACP
adapters) and the claude/codex/opencode/cursor spawn paths. The kernel
then SIGKILLs the child the instant the engine dies, whatever killed it.
Handle the classic fork/prctl race: after `prctl`, re-check `getppid()`
and `_exit` if the parent already died. Keep the existing group teardown
for the graceful path (PDEATHSIG is only the crash net).
Grandchildren (bridges, subagent pi's, tool processes) don't inherit
PDEATHSIG and still orphan — but they self-terminate quickly (the
bridge's stdin closes; tools end at command end) and ticket 01's reaper
cleans the zombies they leave under PID 1. Terminal panes are
intentionally NOT stamped (a shell may hold real user work; keep them
out of scope).

**Fix B — unstrand awaiting-input side chats (pick one):**
- (recommended) When a side chat's parent is retired — stopped, archived,
  or deleted — drain the side chat's pending question(s) with empty
  answers (the same unpark `terminate`/`stop_turn` already use), letting
  the turn settle and the idle reaper take the child. Mirrors
  `completion_notices::stop_parent`'s "a stopped parent must not be woken"
  rule: a gone parent must not keep a child's question parked either.
- (alternative) A long timeout (e.g. 24 h) on awaiting-input agent-created
  side chats that drains the question and parks the session.

**Tests:** unix integration test in `crates/harness` following the
`process.rs` fixture style: an intermediate process spawns a configured
child (`sleep`), the test SIGKILLs the intermediate, assert the child
dies within a bound; plus an engine test that retiring a parent drains a
stranded awaiting-input side chat and its runtime then reaps.

**Out of scope:** tickets 01 (zombie reaper) and 02 (park budget) —
complementary: 01 buries what dies, 02 bounds the parked, 03 makes
nothing survive the engine itself.

## Comments

**2026-10-24 — implemented on `session-process-hygiene` (03800d7c7).**
Fix A: `dies_with_parent` (PR_SET_PDEATHSIG + the getppid fork-race
re-check) stamps every harness spawn — centrally in `acp::child::
configure` (pi + ACP adapters, including the one-shot probes) and at
the claude/codex/opencode/cursor spawn sites; Linux only (no prctl on
macOS — and roboco is never PID 1 there; Windows Job Objects already
own the tree). Verified live with a self-SIGKILLed helper test process.
Terminal panes are intentionally unstamped, as scoped. Fix B: BOTH legs
— ask-time auto-answer for a side chat whose parent is deleted or
archived, and `stop_turn` drains the stopped parent's live side chats'
pending questions empty. A live parent's questions still wait untouched
(tested). Archived-parent draining beyond stop (delete/archive of a
parent with live children) rides the ask-time check for new questions;
a dedicated archive-time sweep remains a possible follow-up.
