# 18 — Sidebar: Ctrl+N jump hints on one line in compact rows

**What to build:** The compact row's time slot is a fixed 30px and the
jump hint took its place unwrapped — "Ctrl+1" fit, "Ctrl+2"…"Ctrl+9" broke
after the `+` into two lines and pushed rows out of alignment. Give the
slot a fixed wider width while a text hint occupies it, stop both hint
variants from wrapping, and scale the slot with the UI font (the 42px
value becomes a floor in default-size rems, not fixed pixels).

**Blocked by:** None.

**Status:** ready-for-human

**Upstream SHAs:** `546ecb68` (#641) — 1 file, `crates/ui/src/shell.rs`
(sidebar row rendering). **Web parity (deliverable):**
`web/packages/app/src/components/chat-list.tsx` + `state/jump-hints.ts` —
keep web hint chips on one line (no wrap) with a slot wide enough for
"Ctrl+9", scaled with the font.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
sidebar/chat-list tests; web `pnpm -r build`.

- [x] Hints stay on one line; rows aligned; slot scales with font
      (desktop + web)
- [x] Tests green (no sidebar-row unit tests exist on either side — the
      change is styling/geometry; execution deferred to the wave-final
      batched pass — user directive)
- [x] Port commit records upstream SHA

## Comments

- Ported `546ecb68` (#641) by intent, both of its commits.
- Desktop `crates/ui/src/shell.rs`: new `COMPACT_JUMP_HINT_WIDTH = 42.0`
  const; the non-compact jump badge gains `.whitespace_nowrap()`; the
  compact time slot (the `chat-time-{id}` div) computes `text_hint =
  compact_jump_label` longer than 3 chars and, while a text hint occupies
  it, swaps the fixed `.w(px(30.0))` for `.min_w(ui_rems(42.0))` — a floor,
  not content-sized, so "Ctrl+1" (a narrower glyph) doesn't nudge its
  row's badge off the others', and a longer rebound combo grows the slot
  instead of spilling over the title. The slot is always
  `.whitespace_nowrap()` now. Mac's "⌘9" (2 chars) keeps the 30px slot,
  exactly like upstream's chars().count() > 3 rule.
- Web parity (chat-list.tsx + app.css): the compact time span gains the
  `chat-row-time-hint` class when `jumpLabel.length > 3`; the CSS gives it
  `width: auto; min-width: calc(var(--rb-ui-size, 16) * 2.625px)` — 42px at
  the default UI size, scaling with the UI font exactly like the desktop's
  `ui_rems(42)` (web rems would be off: the web root is 14px at default).
  `.chat-row-time-compact` and `.chat-row-jump` (the non-compact badge
  variant) both gain `white-space: nowrap`.
- `state/jump-hints.ts` (named by the ticket) needed no change: it only
  supplies the held-modifier state and combos; the fix is pure geometry in
  the row and CSS.
- Exclusions: none — upstream's diff is shell.rs only; the web side is this
  ticket's named deliverable.
- Verification: `cargo check -p roboco -j 3` (clean; no new warnings);
  `rustfmt --edition 2024 --check` on touched files (shell.rs reports only
  the known pre-existing drift hunks on untouched regions — left alone);
  `pnpm -r build` from web/ (tsc --noEmit + vite, clean). Test execution
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
