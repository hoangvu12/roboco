# 17 — Copy transcript selections with Cmd/Ctrl+C again

**What to build:** Clicking the transcript to start a selection blurs the
composer, and the composer's Copy action was the only handler for the
shortcut, so the keystroke reached nothing. The main chat and right pane
now copy the markdown selection from their own key-down listener, running
in the bubble phase after key bindings — a focused composer keeps its own
Copy and a focused terminal still receives Ctrl+C as an interrupt.

**Blocked by:** 12 (navigation_focus.rs exists).

**Status:** ready-for-agent

**Upstream SHAs:** `e13b18de` (#599) — `crates/ui/src/shell.rs`,
`shell/navigation_focus.rs`, `shell/navigation_tests.rs`. Web: native
browser selection+copy already works wherever `user-select: text` applies —
verify + extend only if a gap exists (virtualized pooled rows may limit
cross-row selection; record findings, don't force parity).

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
navigation/copy tests; web: check existing markdown selection tests.

- [ ] Transcript selection copies via Cmd/Ctrl+C (desktop)
- [ ] Web gap check recorded
- [ ] Tests green
- [ ] Port commit records upstream SHA
