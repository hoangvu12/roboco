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
match, implementer's call by intent), `terminal/mod.rs`.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
composer_dock/terminal tests (new).

- [ ] Terminal constrained below composer without dock-motion change
- [ ] Update notices render behind the terminal
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments
