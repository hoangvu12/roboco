# 20 — Terminal: preserve fractional scroll movement

**What to build:** Terminal scroll movement no longer rounds away
sub-line/fractional deltas — accumulated fractional scroll offsets carry
across frames so slow wheel/trackpad scrolling stays smooth.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `e5be4822` (#615) — 1 file,
`crates/ui/src/terminal/panel.rs`. Web: the web terminal
(`web/packages/app/src/terminal/`) is xterm-based with native fractional
scroll — outcome already holds; record the check, fix only a real gap.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
terminal tests.

- [ ] Fractional deltas preserved across frames
- [ ] Tests green; web check recorded
- [ ] Port commit records upstream SHA

## Comments
