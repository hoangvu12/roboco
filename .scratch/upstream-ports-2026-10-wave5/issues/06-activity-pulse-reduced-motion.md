# 06 — Keep activity loaders pulsing with system reduced motion

**What to build:** Port upstream `e96eccb1` (#754): under reduced
motion every loader currently freezes at phase 0, so "working"
indicators look dead. After the port, **system-derived** reduced
motion keeps a gentle brightness pulse; **explicit On** and
**background pause** keep them frozen — exactly the semantics
`loaders.rs` should document: "Activity grids retain a gentle
brightness pulse with system reduced motion; explicit On and
background pause keep them still."

**Why this ports near 1:1 (verified in the actual diff):** upstream's
`activity_pulse_every` reads the `MotionState` fields directly —
`preference == ReduceMotion::System && state.system && !
(state.pause_in_background && !state.active)` — rather than going
through a folded `resolve()` flag, and Roboco already carries the
identical `MotionState { preference, pause_in_background, system,
active }` from the #642 port (`dfca8c02`,
`crates/ui/src/motion.rs:695-870`). The gap is real: our
`pulse_delta_every` (`motion.rs:118-150`) returns 0.0 under
`cx.reduce_motion()` and `loaders.rs:4-6` documents the snap; call
sites: `shell.rs:9280` (composer status strip), `transcript.rs:6552`,
`changes.rs:5031/5084`, `history.rs:4279`,
`settings/accounts.rs:1422`, boot splash `loaders.rs:336-357`.

**How:** port the +220 `motion.rs` machinery (`ActivityPulse` struct,
`activity_pulse`/`activity_pulse_slow`/`activity_pulse_every`, the
subtle-pulse scheduling with the `ZERON_PULSE` constant — rename to
`ROBOCO_PULSE` per the rebrand), swap the loader call sites
`pulse_delta*` → `activity_pulse*`, update the appearance helper text
(`reduce_motion_helper`: "Following reduced system motion. Activity
indicators use a gentle brightness pulse."), and fold upstream's
`docs/reference/windows-development.md` (+6) hunks into our diverged
doc by intent.

**Web decision (record it in the ticket, like ticket 27 did):**
`web/packages/app/src/styles/app.css:2147-2160` snaps
`.glyph-spinner-cell/.mark-loader-cell/.roboco-loader-cell` to
`animation: none` under `prefers-reduced-motion`/pin — decide
gentle-pulse-vs-frozen for the web twins, record the decision, apply
it. Upstream's title scopes the exemption to system-derived motion;
pin-to-On web users stay frozen.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `e96eccb1` (#754) — `crates/ui/src/motion.rs`
(+220), `crates/ui/src/loaders.rs` (±12),
`crates/ui/src/settings/appearance.rs` (+4),
`docs/reference/windows-development.md` (+6) → same paths here.
Source: `.scratch/upstream-drift/2026-10-05.md` § #754 (includes the
actual `activity_pulse_every` source excerpt).

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib`.

- [ ] Loaders pulse gently under system-derived reduced motion
- [ ] Explicit On and background pause keep loaders frozen
- [ ] `ZERON_PULSE` renamed per rebrand (`ROBOCO_PULSE`)
- [ ] Appearance helper text updated
- [ ] Web CSS decision recorded and applied
- [ ] Port commit records the upstream SHA
