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

**Status:** ready-for-human

**Upstream SHAs:** `e96eccb1` (#754) — `crates/ui/src/motion.rs`
(+220), `crates/ui/src/loaders.rs` (±12),
`crates/ui/src/settings/appearance.rs` (+4),
`docs/reference/windows-development.md` (+6) → same paths here.
Source: `.scratch/upstream-drift/2026-10-05.md` § #754 (includes the
actual `activity_pulse_every` source excerpt).

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib`.

- [x] Loaders pulse gently under system-derived reduced motion
- [x] Explicit On and background pause keep loaders frozen
- [x] `ZERON_PULSE` renamed per rebrand (`ROBOCO_PULSE`)
- [x] Appearance helper text updated
- [x] Web CSS decision recorded and applied
- [x] Port commit records the upstream SHA

## Comments

Ported upstream `e96eccb1` (#754) near 1:1 — diff stat matches
(motion.rs +220, loaders.rs ±12, appearance.rs +4,
windows-development.md +6) plus the web CSS decision.

- `crates/ui/src/motion.rs`: `pulse_delta_every` factored
  (`pulse_phase` extraction), `pulse_lease_every` factored
  (`schedule_pulse_every`, the reduced-motion gate stays on the lease
  wrapper only), new `ActivityPulse` struct +
  `activity_pulse`/`activity_pulse_slow`/`activity_pulse_every`.
  Semantics exactly as upstream: subtle mode animates only when
  `MotionState` says system-derived (`preference == System && system
  && !(pause_in_background && !active)`), reads directly from
  `MotionState` (never the folded `resolve()`), drives
  `ROBOCO_PULSE` (2.4s) at stride 2; subtle opacity = `0.6 + 0.2 *
  pulse_wave(phase)` — no chase, no size change.
- **`ZERON_PULSE` → `ROBOCO_PULSE`: no rename needed** — our proto
  motion catalog already ships it rebranded (wave-4 #642 port); the
  spec's "only new identifier rename this wave" was already done.
- `crates/ui/src/loaders.rs`: `gradient_spinner` (3×3 matrix) and
  `mini_spinner_cells` (2×3 glyph/mono) swap
  `pulse_delta*(&GRADIENT_SPIN, ..)` → `activity_pulse*(..)`; the
  pulse loaders (`roboco_loader`, `roboco_mark_loader`) stay on
  `pulse_delta` (decorative, frozen) — matching upstream. All the
  drift-note call sites (shell status strip, transcript, changes,
  history, accounts) route through these two loaders and are covered
  automatically.
- All 4 upstream tests ported (subtle uniformity + brightness span;
  the 8-case preference/system/pause matrix against lease presence;
  cached-loader renewal at stride 2 incl. our identical MiniSpinner
  entity structure; system-RM loader keeps ticking).
- `crates/ui/src/settings/appearance.rs`: `reduce_motion_helper`
  System+true text updated per upstream.
- `docs/reference/windows-development.md`: upstream's +6 hunk folded
  by intent into our diverged doc ("Zeron's" → "Roboco's"), placed
  after the existing transparency/backdrop paragraph.
- **Web CSS decision (recorded):** match the desktop. Under
  `prefers-reduced-motion: reduce` with the pin not Off,
  `.glyph-spinner-cell` (the web twins of BOTH activity grids — mini
  2×3 and matrix 3×3) now runs a gentle uniform 2.4s brightness pulse
  (`rb-activity-pulse`: opacity 0.6 → 0.8 → 0.6, cosine ≈
  ease-in-out), with the inline per-cell chase delay zeroed
  (`animation-delay: 0s !important`) so no wave travels — exactly the
  desktop's subtle mode. `.mark-loader-cell`/`.roboco-loader-cell`
  (the pulse loaders the desktop keeps frozen) keep `animation: none`
  under reduced motion; pinned-On (`data-reduced-motion="on"`) and
  background pause keep everything frozen. Applied in
  `web/packages/app/src/styles/app.css`.
- Verification deferred to the wave-final batched pass.
