# 20 — Terminal: preserve fractional scroll movement

**What to build:** Terminal scroll movement no longer rounds away
sub-line/fractional deltas — accumulated fractional scroll offsets carry
across frames so slow wheel/trackpad scrolling stays smooth.

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `e5be4822` (#615) — 1 file,
`crates/ui/src/terminal/panel.rs`. Web: the web terminal
(`web/packages/app/src/terminal/`) is xterm-based with native fractional
scroll — outcome already holds; record the check, fix only a real gap.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
terminal tests.

- [x] Fractional deltas preserved across frames
- [x] Tests green (slow_trackpad_scroll_accumulates_per_terminal ported;
      execution deferred to the wave-final batched pass — user directive);
      web check recorded below
- [x] Port commit records upstream SHA

## Comments

- Ported `e5be4822` (#615) by intent onto the post-5a76aa51 terminal dock
  (panel.rs was untouched by the dock restructure, so the fix lands on the
  same scroll-wheel listener shape upstream patched).
- `TerminalTab` gains `scroll_remainder: f32` (per terminal, seeded 0 in
  `reserve_tab_for_chat`). The scroll-wheel listener now resolves the
  active tab, resets the remainder on `TouchPhase::Started` (a fresh
  gesture never inherits the previous one's partial row), accumulates the
  fractional row delta, scrolls the truncated whole part, and keeps the
  fraction on the tab for the next event. `round()` → `trunc()` — every
  sub-half-row trackpad event used to round to zero and lose all movement;
  signed fractions now accumulate in reverse, and mouse-wheel `Lines`
  deltas stay exact. `cx.stop_propagation()` after the scroll, as upstream.
- Tests: slow_trackpad_scroll_accumulates_per_terminal ported (gpui::test,
  window-drawn grid geometry, simulate_event ScrollWheelEvents): 40×
  quarter-row scrolls move 10 lines, reverse fractions walk back 2, +3
  exact rows then a 0.75 remainder stays put at 11, a Started gesture
  resets to a 0.25 remainder, and a second tab never inherits the first's
  remainder (upstream selects tab key `1`; Roboco's tab keys count from 1,
  so the second tab is key `2` here).
- Web check: no gap — the web terminal is @xterm/xterm
  (web/packages/app/src/terminal/), whose viewport owns wheel handling
  with the browser's native fractional pixel scrolling; nothing in our
  web terminal code rounds wheel deltas to whole lines (no onWheel /
  deltaY handling exists there at all). Outcome already holds; nothing to
  fix, matching the ticket's record-only instruction.
- Exclusions: none — upstream's diff is panel.rs only.
- Verification: `cargo check -p roboco -j 3` (clean; no new warnings);
  `rustfmt --edition 2024 --check` on panel.rs (only the known pre-existing
  import-order drift on untouched lines, left alone). Test execution
  deferred to the wave-final batched pass (user directive).

- Wave-final batched verification (2026-10-03, merged main `cf94f415`): one
  batched pass over all lanes — ui lib 1521/1521; engine 529/530 (the one
  failure is the documented pre-existing
  `previews::preview_watch_follows_the_session_checkout_and_owning_device`
  baseline); harness 504/509 (the five failures are the documented
  environmental `#!/usr/bin/python3` fixture shebang and uid-1001
  user-database quirks; CI runs them); mcp 26/26; voice 18/18; theme 31/31;
  `wiregen --check` and `roboco-theme-export --check` fresh; web `pnpm -r
  build` green, app vitest 2122/2122, engine-client vitest green. The
  deferred test-execution criterion is demonstrated; closed by the
  wave-final pass.
