# 74 — Rolling labels at ~30 Hz; compact model picker with provider tabs and a fixed trailing slot (#799 deferred half, slices c–e)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:**
1. **Rolling labels (battery).** `crates/ui/src/roll_text.rs:420` calls
   `request_animation_frame()` (display rate). Use upstream's ~30 Hz
   pulse lease, a `MAX_KEYS` bound, a reduced-motion mid-roll settle,
   and key `working-word-{id}` / `working-timer-{id}` by turn
   (`transcript.rs:6924,6940`). The glyph blur/tilt
   (`paint_glyph_transformed`) is in the zui pin already (`0966d06`).
2. **Compact model picker as provider tabs:** "Back · Esc",
   "Jump to {name}". Replace the Providers page in `pickers/compact.rs:782-811`.
3. **Compact sidebar row with one fixed trailing slot** (time / archive /
   legend) instead of `remote || corner_hovered` (`shell.rs:7939`).

Keep Roboco's recorded divergence "effort always muted" (`74abedd5a`,
the traits-summary chip).

**Upstream:** `dbb639be3` (#799) deferred half, slices (c)–(e).

**Roboco files:** `crates/ui/src/{roll_text,transcript,shell}.rs`,
`crates/ui/src/pickers/compact.rs`.

**Excluded:** Zeron Icons and the glyph morphs (declined).

**Proto change:** no.

**Blocked by:** 56 (`pickers` overlap), 65 (sidebar rows).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B #799 half; `part-05.md` `dbb639be3` (c–e).

- [ ] A working label ticks at ≤ 30 Hz (frame-count test); reduced motion settles mid-roll
- [ ] The compact picker shows provider tabs; Esc goes back (picker tests)
- [ ] The compact row trailing slot stays fixed on hover (UI test)
- [ ] Commit records `dbb639be3` (slices c–e)

## Comments
