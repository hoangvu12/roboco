# 67 — Terminal, view half: predictive echo, IME, mouse and focus reporting, wheel in TUIs, cursors, synchronized output, smooth scrollback (wave-9 17 part 2, #975, A13)

**Lane:** 4b Features: desktop UI. **Effort:** M–L. If it runs long,
commit predictive echo separately from input/mouse/scrollback.

**What to build:** the UI half of #975's local slice:
- **Predictive echo:** new `crates/ui/src/terminal/predict.rs` (+405).
- **Platform text input:** IME/marked text and dead keys.
- **Mouse and focus reporting** to programs that ask for it.
  **A13:** the wheel goes to programs that own the mouse or the
  alternate screen (`claude`, vim, less). Today it always scrolls local
  scrollback (`terminal/panel.rs:1814-1849`). That's open PR #363's bug,
  fixed by #975's `wheel_arrows`. Take #975's shape.
- **DECSCUSR cursor shapes** with typing-aware blink, **synchronized
  output**, a contrast floor, a copy flash, and **pixel-smooth
  scrollback** (`panel.rs` +918, `view.rs` +608, `emulator.rs`).

**Upstream:** `71033cc96` (#975) UI hunks; open PR #363 as the reference
for A13.

**Roboco files:** `crates/ui/src/terminal/{panel,view,emulator,predict (new)}.rs`.

**Excluded:** P2P/remote-terminal UI.

**Proto change:** no (uses 66's).

**Blocked by:** 66.

**Status:** ready-for-agent

**Source:** wave-9 `issues/17-terminal-overhaul.md`; `part-07.md` `71033cc96`;
`2026-10-28.md` A13; `pass2-features-desktop.md` §6.

- [ ] Predictive echo shows typed text immediately and reconciles on output (predict tests)
- [ ] IME composition and dead keys work (Windows check)
- [ ] The wheel scrolls inside vim/less/claude; plain shells still scroll scrollback (tests with alt-screen + mouse-mode fixtures)
- [ ] Cursor shapes, synchronized output, smooth scrollback (tests where upstream had them)
- [ ] Commit records `71033cc96` (UI half) and PR #363 as superseded; absorbs wave-9 17 with 66

## Comments
