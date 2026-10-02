# 02 — Idle reaper spares a parked session's live subagents

**What to build:** The 30-min idle reaper no longer cancels an agent
process under a still-running subagent. While any subagent sink is live,
the reaper window stretches to 8× (4h default) measured from the LAST
subagent activity instead of disarming outright — one lost vendor terminal
signal (claude's untagged `task_notification`, grok's `subagent_finished`,
codex thread closure) must not pin a session forever — and a reap under a
live sink stamps the subagent failed. Also add the env override upstream
introduced for testability: `ZERON_SESSION_IDLE_MS` →
**`ROBOCO_SESSION_IDLE_MS`** (our reaper currently hardcodes
`SESSION_IDLE = 30min` with no override).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `aaeede8b` (#637) — `crates/engine/src/sessions.rs`
(+33), `crates/engine/tests/subagent_idle_reap.rs` (+311, new). Our
`SubagentSink` map in `sessions.rs` (~line 1986) is the port target.

**Verification budget:** `cargo check -p roboco-engine -j 3`; targeted
nextest `-p roboco-engine subagent_idle_reap` (new) + existing
idle/session tests.

- [ ] Live subagent sink bounds (not disarms) the reaper, clocked from last
      subagent event; lost subagent stamped failed
- [ ] `ROBOCO_SESSION_IDLE_MS` overrides the window
- [ ] New test file green alongside existing reaper tests
- [ ] Port commit records upstream SHA

## Comments
