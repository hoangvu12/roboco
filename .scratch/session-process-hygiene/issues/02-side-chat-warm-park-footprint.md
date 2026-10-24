# 02 — Bound the parked-session footprint WITHOUT changing the experience

**What to build:** Bound the memory held by parked persistent sessions —
the "spawn a lot of side chats, all done, every harness CLI still alive"
shape — **with zero change to the normal experience**: same 30-min idle
window, warm routing, background-work and subagent deferral, notice
flows. Parking is by design (`crates/engine/src/sessions.rs` `drive_run`,
~3719-3748: warm child for zero-latency next message, idle reaper after
`ROBOCO_SESSION_IDLE_MS`, default 30 min, env-only). It applies to every
steerable harness (all of them). Per parked session: pi ≈ 140-200 MB +
`roboco mcp` bridge ≈ 14 MB; claude ≈ 315-370 MB + bridge; a parallel
wave of 8 side chats parks ~1.2 GB for 30 min. Evidence:
`.scratch/session-process-hygiene/spec.md` (Finding 1).

**Blocked by:** None.

**Status:** needs-triage (one product constant to pick: the budget)

**Why eviction is seamless (the premise of this design):** a retired
parked session keeps its session file; the next message re-dispatches and
the harness resumes it (`--session`, resume pointer rides
`SessionStarted/Done.session_id`). The only cost of a *wrong* eviction is
one spawn+resume (~1-2 s) instead of 0 ms — no error stamps, no lost
steers (the at-least-once ledger re-dispatches), nothing user-visible.
This path is already exercised constantly in production (every 30-min
idle reap of a chat the user later returns to).

**Design (recommended):**

1. **Keep the default window unchanged** (30 min for every chat kind).
   No shorter side-chat window by default — the warm experience for
   "parent iterates recent side chats" is preserved by (2) instead.
   (`ROBOCO_SIDE_SESSION_IDLE_MS` may still exist as an opt-in knob
   defaulting to the main window.)
2. **Warm-park budget (LRU):** cap the number of parked runtimes
   (`ROBOCO_WARM_SESSIONS`, default ~8). When a chat parks beyond the
   cap, retire the **oldest-idle** parked runtime through the existing
   clean interrupt path (same one the 30-min reaper uses). Newest chats —
   the ones the parent is actually still working through — stay warm;
   the stale tail is what gets evicted. Spawning 20 side chats now holds
   ~8 warm instead of 20.
3. **Memory-pressure trigger (optional, same mechanism):** in addition to
   the count cap, retire oldest-idle parked runtimes when the device's
   available memory drops below a floor (engine already reads device
   stats). Bounds the footprint by *need* rather than count, and never
   fires on a healthy machine — the common case is bit-for-bit identical
   to today.
4. **Never evict a session that is not truly idle** — reuse the reaper's
   own guards: `turn.background_live()` (claude/codex background agents,
   shells) defers; open subagent sinks (the 4-h stretch) defer;
   AwaitingInput chats are not parked at all. Eviction only ever takes a
   session the 30-min reaper would eventually take anyway.

**Experience checklist (what must stay identical):** next message into a
recent chat still routes into the live run (warm); background agents and
subagents never die early; awaiting-input questions never unparked; the
completion-notice → parent-follow-up flow still finds its side chat warm
inside the window (it is the newest, so the LRU keeps it); the only
observable differences are lower memory and no zombie pile-up
(ticket 01).

**Tests** (engine `sessions.rs` suite, following the existing
`ROBOCO_SESSION_IDLE_MS` test pattern):
- parking beyond the budget evicts the oldest-idle runtime cleanly, and
  a message to the evicted chat resumes it without an error stamp;
- a parked session with live background work or an open subagent sink is
  never the eviction victim;
- the completion-notice → parent-follow-up flow still finds the side
  chat warm inside the window (newest is never evicted);
- default behavior with ≤ budget parked sessions is unchanged (no
  evictions).

**Out of scope:** zombie reaping (ticket 01); harness-specific teardown.

## Comments

**2026-10-24 — implemented on `session-process-hygiene` (03800d7c7).**
Landed as the recommended design: default window unchanged everywhere,
`ROBOCO_WARM_SESSIONS` (default 8, `0` = no cap) retires the
oldest-idle parked runtime through the existing clean interrupt path
(`Inner::note_parked`/`clear_parked`; lock order: `parked` is never held
while `runs`/`statuses` are taken — see `remove_run`). Exemptions as
specified (`turn.background_live()`, `running_subagents > 0`,
`is_active`). The memory-pressure trigger was skipped (option 3 marked
optional; the count cap already bounds the "lots of side chats" shape).
The window-as-setting surface (option 3) remains open as a follow-up.
