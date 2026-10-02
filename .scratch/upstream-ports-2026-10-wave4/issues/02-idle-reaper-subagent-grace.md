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

**Status:** ready-for-human

**Upstream SHAs:** `aaeede8b` (#637) — `crates/engine/src/sessions.rs`
(+33), `crates/engine/tests/subagent_idle_reap.rs` (+311, new). Our
`SubagentSink` map in `sessions.rs` (~line 1986) is the port target.

**Verification budget:** `cargo check -p roboco-engine -j 3`; targeted
nextest `-p roboco-engine subagent_idle_reap` (new) + existing
idle/session tests.

- [x] Live subagent sink bounds (not disarms) the reaper, clocked from last
      subagent event; lost subagent stamped failed
- [x] `ROBOCO_SESSION_IDLE_MS` overrides the window
- [x] New test file green alongside existing reaper tests
- [x] Port commit records upstream SHA

## Comments

- Ported `aaeede8b` (#637) by intent into `crates/engine/src/sessions.rs`:
  `SESSION_IDLE` const replaced by the env-driven `session_idle` +
  `subagent_silence = 8x` pair; reaper window now
  `max(parked_at, last_subagent_activity) + (live sink ? silence : idle)`;
  reaper log gains `live_subagents`; tagged `AgentEvent::Subagent` traffic
  stamps `last_subagent_activity` right after `publish`. The existing
  run-end cleanup already stamps open sinks `failed`, so the reap of a
  silent subagent marks its chip failed with no extra code — the
  `reaper_still_ends_a_session_whose_subagent_went_silent` test proves it.
- Test ported as `crates/engine/tests/subagent_idle_reap.rs` (rebrand:
  `roboco_*` crates, `ROBOCO_SESSION_IDLE_MS`; our `EngineCore::assemble`
  takes 3 args — upstream's 4th dropped).
- Exclusions: none — the upstream diff is engine-only.
- Verification: `cargo check -p roboco-engine --tests -j 3` clean; nextest
  `-p roboco-engine -E 'binary(subagent_idle_reap) or binary(turn_quiesce)
  or binary(self_continued_quiesce)'` → 8/8 pass (2 new + 6 existing
  park/quiesce regressions).
