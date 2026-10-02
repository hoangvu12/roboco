# 18 — Sidebar: Ctrl+N jump hints on one line in compact rows

**What to build:** The compact row's time slot is a fixed 30px and the
jump hint took its place unwrapped — "Ctrl+1" fit, "Ctrl+2"…"Ctrl+9" broke
after the `+` into two lines and pushed rows out of alignment. Give the
slot a fixed wider width while a text hint occupies it, stop both hint
variants from wrapping, and scale the slot with the UI font (the 42px
value becomes a floor in default-size rems, not fixed pixels).

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `546ecb68` (#641) — 1 file, `crates/ui/src/shell.rs`
(sidebar row rendering). Web: sidebar rows render in
`web/packages/app/src/components/chat-list.tsx` — check hint wrapping,
extend only if a gap exists.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
sidebar/chat-list tests.

- [ ] Hints stay on one line; rows aligned; slot scales with font
- [ ] Tests green; web gap check recorded
- [ ] Port commit records upstream SHA

## Comments
