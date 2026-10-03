# 21 — Terminal overlap with composer and update notices

**What to build:** The terminal drawer is constrained below the composer
without changing the dock motion, and agent update notices stay behind the
terminal (z-order), so the terminal never overlaps either surface when the
composer grows/shrinks or a notice appears.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `d90b9c6a` (#620) — 5 files:
`crates/ui/src/composer_dock.rs`, `composer_dock/terminal_tests.rs` (new
test module dir), `shell.rs`, `terminal/dock.rs` (upstream splits this
out here — our terminal is `{emulator, mod, panel, view}`; carry the
dock-region logic into `terminal/mod.rs` or create `terminal/dock.rs` to
match, implementer's call by intent), `terminal/mod.rs`. Web: the dock is
CSS-flex (`lib/composer-dock.ts`, `dock-glide.ts`, update strips) —
verify the composer/notice z-order and spacing and fix only a real gap.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
composer_dock/terminal tests (new).

- [x] Terminal constrained below composer without dock-motion change
- [x] Update notices render behind the terminal
- [ ] Tests green (terminal_clearance regression ported; execution deferred to
      the wave-final batched pass — user directive)
- [x] Port commit records upstream SHA

## Comments

- Ported `d90b9c6a` (#620) by intent. New `crates/ui/src/terminal/dock.rs`
  (`pub(crate) mod dock;` in `terminal/mod.rs`): `Geometry`/`SharedGeometry`
  same-frame budget, the `terminal()` deferred-layout element (reads the
  budget the composer resolved during request_layout, stores the frame's
  final geometry into the shell's persistent `measured` cell, requests an
  animation frame on >0.5px change), and `above_terminal()` (constrains
  transcript/notice layout+hitboxes to the same measured height in prepaint).
- `composer_dock.rs`: `DockState::position_at` extracts the prepaint
  position step (sampling — leaves route state untouched) +
  `DockState::terminal_limit`; `DockedComposer` gains `reserve_terminal()` —
  request_layout then measures the child as a layout root (`layout_as_root`
  MaxContent), shrinks the geometry's limit to viewport-room, pixel-snaps
  the reservation, bounds `height` by the composer's animated clearance
  (floor-rounded towards free space), and swaps in an identical centered
  slot div so `prepaint_at` can carry the persistent child + hitboxes to the
  spring position. `RequestLayoutState` is now `bool` (measured).
- `shell.rs`: `terminal_geometry: SharedGeometry` persistent field;
  `render_main` builds a fresh per-frame geometry (tweened target, saved
  content height, `min(viewport*TERMINAL_MAX_VH, viewport-chrome)` limit),
  hands it to `docked_composer().reserve_terminal()`, wraps the transcript
  underlay in `above_terminal` (replacing the stale `.bottom(term_h)`), and
  moves the harness-update notice layer BEFORE the chrome stack, clipped by
  its own `above_terminal` — z-order now behind the terminal AND clipped at
  the same measured edge. `render_terminal_container` takes the geometry,
  resets the persistent cell when closed, anchors drag start at the painted
  height, and builds through `dock::terminal()` (border only when height > 0,
  inner height from `content_height`); `toggle_terminal`/drag-limit/
  transcript-clearance all read `terminal_geometry`.
- `composer_dock/terminal_tests.rs`: `terminal_clearance_preserves_
  composer_motion_and_same_frame_layout` — two-column fixture, one with
  budgeting and one unconstrained baseline; asserts the composer's
  trajectory is unaltered, composer bottom ≤ terminal top, transcript and
  terminal share the same height each frame, painted height equals
  geometry, through dock/undock/resize/attachment-growth frames, idle
  toggles, and bounded cases.
- Web: verified, no real gap — the web dock is CSS-flex
  (`chat-page.tsx`'s bottom-stack: status strip → composer → TerminalDock as
  flow siblings, so the terminal can never overlap the composer), and web
  update notices live in the sidebar (`AgentUpdateStrip` in
  `sidebar-body.tsx`), not over the main column. Upstream's commit touched
  no web files either.
- Exclusions: none — the whole upstream diff is the 5 crates/ui files.
- Verification: `cargo check -p roboco -j 3` (clean, no new warnings vs
  33b33a7c); `rustfmt --edition 2024` on touched files (drift on untouched
  regions reverted); test execution deferred to the wave-final batched pass
  (user directive).
