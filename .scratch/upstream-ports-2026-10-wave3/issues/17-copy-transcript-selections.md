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

## Comments

**Branch:** `wave3/17-copy-selections` → merged into main `Merge wave3/17`. Commit `7bd37bdb` (byte-identical carry: shell.rs dropzone on_key_down ×2, navigation_focus.rs copy_transcript_selection +19, 4 tests +87). Post-merge re-verified within boundary run: ui 1322/1322, web 137 files/2068.

**Web findings (no change needed):** no web analog of the bug (no mod-c binding, native copy handles DOM selection; terminal Ctrl+C policy already correct); virtualization limits cross-row native selection (pre-existing; porting the app-level selection model to web = separate future feature, recorded).
