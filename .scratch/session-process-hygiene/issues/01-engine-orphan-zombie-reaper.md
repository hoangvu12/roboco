# 01 — Engine: reap orphaned descendants (PID-1-safe orphan reaper)

**What to build:** A unix-only background reaper in the engine that reaps
zombie children the engine did not itself track, so `roboco headless`
running as PID 1 (container entrypoint) stops accumulating orphaned
descendants of harness sessions and terminal panes forever. Today every
`roboco mcp` bridge whose parent harness wins the exit race (one per
reaped pi session, observed), plus every tool process a mid-turn kill
orphaned (cargo/git/sccache/sleep/…), plus anything run inside terminal
panes (`pane-echo`, 209 observed) becomes a permanent zombie: reparented
to PID 1 = the engine, which never waits for children it didn't spawn.
~1,072 zombies after 8 days on the dev box. Evidence and design notes:
`.scratch/session-process-hygiene/spec.md` (Finding 2).

**Blocked by:** None.

**Status:** ready-for-agent

**Design sketch:**

- Start one task at engine assembly (alongside the existing
  `shutdown`/worker plumbing in `crates/engine/src/lib.rs`), unix only.
- Loop every N seconds (e.g. 5s; cheap) or driven by a SIGCHLD self-pipe
  if simpler: peek zombies without consuming via
  `waitid(P_ALL, WEXITED | WNOHANG | WNOWAIT)` (libc), collecting
  `si_pid`s; re-scan until it returns nothing.
- Maintain an **own-spawn registry** (`Mutex<HashSet<i32>>`) of pids the
  engine spawned directly and believes alive (harness `Child::id()` at
  spawn, terminal pty child, git/self-exec helper children — wrap the
  spawn sites; remove the entry once its owner has waited it, best
  effort). Reap (waitpid(pid, WNOHANG)) only peeked zombies NOT in the
  registry. Registry correctness needs are modest: a stale entry only
  makes the reaper skip a zombie whose pid got reused — acceptable.
- Do NOT use a plain `waitpid(-1, …)` loop: tokio 1.53.1 Command
  children are pidfd-based and extract their exit status with
  `try_wait()` on the specific pid — stealing an exit breaks their wait
  future (ECHILD). The WNOWAIT peek + registry filter avoids that
  entirely.
- Log at debug per reap (pid + comm), count at info periodically (or a
  metric if the engine has one); no user-facing surface.
- Windows: skip (Job Objects already own the tree).
- Test (unix): spawn a child that spawns a grandchild which outlives it,
  kill the child without waiting, assert the reaper clears the zombie;
  plus a test that a tracked (tokio Command) child's exit status is still
  read correctly by its owner while the reaper runs.

**Also in scope (small, same file area):** in
`crates/harness/src/acp/child.rs::shutdown`, signal the snapshot
`tree` (descendants) before group-TERMing the parent, so the alive
harness reaps its own children in the common path. Belt-and-braces only —
the SIGKILL escalation still orphans; the reaper remains the fix.

**Out of scope:** memory footprint of parked (live) sessions — ticket 02;
ops guidance for running headless under tini/dumb-init (one paragraph in
`docs/reference/` is fine to add if convenient).
