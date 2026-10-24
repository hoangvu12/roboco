# Session process hygiene: parked sessions and orphan zombies

Investigated 2026-10-24 (user report: "roboco doesn't kill pi processes even
when the turn is completed — spawn a lot of side chats with the pi harness,
and even when those side chats are done"). Two distinct behaviors, one
by-design and one a real leak. Live-reproduced on this box (engine
`roboco headless` is PID 1 of its container, uptime 8 days).

## Finding 1 — parked sessions keep the harness CLI alive 30 min after the turn ends (by design)

`crates/engine/src/sessions.rs` (`drive_run`, ~3719-3748): a completed turn
on a steerable harness **parks** the runtime — the child and its steering
mailbox stay warm so the next message routes in with zero respawn/resume
latency. The idle reaper releases it after `ROBOCO_SESSION_IDLE_MS`
(default **30 min**; env-only override, no setting). Confirmed live:

- 3 pi side chats + 2 claude-code side chats spawned 14:37-14:38, turns
  done 14:38:57 → all five harness CLIs + their `roboco mcp` bridges
  stayed alive and idle.
- Engine log: `reaping idle persistent session` for all 5 at
  15:09:05-15:09:12 (exactly 30 min later). All processes gone after.

Footprint per parked session: pi ≈ 140-200 MB RSS + bridge ≈ 14 MB;
claude ≈ 315-370 MB + bridge. A parallel wave of 8 side chats ≈ 1.2 GB
parked for 30 min. This is the resource shape behind the user's report.

**All harnesses park** (parking is engine-level, gated on
`supports_steering()`, which is `true` for pi/claude/codex/opencode/cursor/
acp). Harness differences: claude and codex can hold a parked session
**longer** — `TurnControl::set_background` (claude background agents/
shells, codex open commands) defers the reaper indefinitely while the
agent reports live background work, and live subagent sinks stretch the
window to `subagent_silence` (8× = 4 h; only claude/codex emit
`AgentEvent::Subagent`). Pi is the *shortest*-lived: it reports neither.

Parked sessions are NOT respawned at engine restart
(`recover_stale` auto-resumes only chats whose journal lacks a final
`Done`).

## Finding 2 — the engine never reaps orphaned descendants; as PID 1 it accumulates zombies forever (real leak)

Every teardown path kills the harness tree properly
(`crates/harness/src/acp/child.rs` group SIGTERM→SIGKILL +
`process::descendants`/`terminate_tree` for detached strays;
`shutdown_child` waits the direct child). But **grandchildren that exit
after their parent is dead are reparented to PID 1 — and the engine never
waits for children it did not spawn itself.** On a normal host init reaps
them; in the headless container the engine IS PID 1, so they stay.

Live evidence (this box, 8 days of uptime):

- 1,072 zombies under PID 1 at 2026-10-24 15:22: `pane-echo` 209,
  `sccache` 153, `roboco` 104, `sleep` 79, `cargo` 57, `git` 48,
  `sh`+`bash` 66, `rustc` 32, `claude` 18, `codex` 15, plus test fixtures
  (`fake-acp.sh`, `fake-codex.sh`), build tools, `MainThread`/
  `tokio-rt-worker` (engine-helper comm names), …
- Controlled repro: after the 15:09 reap of the 3 pi side chats, the
  `[roboco]` zombie count went 101 → 104 — **exactly one
  `roboco mcp` bridge zombie per reaped pi session** (the group TERM
  kills pi and its bridge; whichever exits first loses the reap race, the
  loser is orphaned to PID 1). Sources are not just bridges: any harness
  killed mid-turn (stop, quiesce watchdog, reap) orphans its running tool
  processes (cargo/git/sccache/sleep) and anything run inside terminal
  panes (`pane-echo`); each becomes a permanent zombie once it exits.

Zombies cost ~a kernel task struct + a PID each: slow, unbounded growth;
PID-table and `ps` pollution on long-lived deployments.

Immediate user-side mitigation: `ROBOCO_SESSION_IDLE_MS=300000` (5 min)
on the engine's environment shrinks Finding 1; nothing shrinks Finding 2
short of restarting the container (or wrapping the engine in tini/
dumb-init — an ops workaround, not a fix, since we ship `roboco headless`
as the container entrypoint).

## Finding 3 — desktop app: crash orphans children; awaiting-input holds them indefinitely

The desktop app embeds the same engine code (30-min reaper included) and
quits gracefully (`crates/ui/src/lib.rs` `on_app_quit` → engine
`shutdown` → `terminate` per chat — parked sessions die). But two paths
leave harness processes alive far past 30 min on a desktop:

- **Engine death without graceful shutdown** (crash, force-quit, OOM-kill,
  daemon restart): nothing kills the children — `kill_on_drop`/`Drop`
  need a live owner — so every pi/claude child + `roboco mcp` bridge
  stays alive forever on macOS/Linux. Windows is safe (Job Objects,
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`). No `PR_SET_PDEATHSIG` anywhere
  in the repo today.
- **AwaitingInput disarms the idle reaper** (a turn parked on a question
  it is still owed an answer to counts as busy — by design, no timeout).
  An agent-created side chat that asked a question nobody answers holds
  its pi process indefinitely.

→ ticket 03.

## Tickets

- `issues/01-engine-orphan-zombie-reaper.md` — reap orphaned descendants
  (PID-1-safe), the root fix for Finding 2.
- `issues/02-side-chat-warm-park-footprint.md` — bound the parked-session
  footprint without changing the experience (LRU budget + optional
  pressure trigger; default window unchanged).
- `issues/03-children-die-with-engine-and-unstranded-input.md` —
  PDEATHSIG crash net + unstrand awaiting-input side chats.

## Notes for implementers

- Tokio 1.53.1 `Child` on Linux is pidfd-based (`PidfdReaper`): its
  `poll` extracts status with `try_wait()` (waitpid on the pid). A naive
  engine-side `waitpid(-1, …)` loop would steal exits from tokio's
  children (their wait futures error with ECHILD, exit statuses lost).
  Use `waitid(P_ALL, WEXITED | WNOHANG | WNOWAIT)` to *peek* zombies
  without consuming, then `waitpid(pid, WNOHANG)` only pids the engine
  did not itself spawn (own-spawn registry; stale entries merely skip a
  reap — pid-reuse window is negligible).
- Windows already owns the tree via Job Objects
  (`crates/harness/src/windows_process.rs`) — keep the reaper unix-only.
- `kill the tree children-first` (descendants before the parent, so the
  alive parent reaps them) reduces bridge zombies but cannot eliminate
  them (SIGKILL escalation still orphans); the reaper is the robust fix.
