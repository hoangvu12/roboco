# 04 — Session navigation shortcuts work from Settings

**What to build:** next/prev-session (and archive-session) shortcuts fire
while Settings has focus instead of being swallowed by the settings surface;
the main chat and right pane keep their existing behavior.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `08965a1e` — `crates/ui/src/shell.rs`, `crates/ui/src/shell/tabs.rs`.
Web parity: relax the route guard in `web/packages/app/src/components/app-shell.tsx`
(~:350-357) so `next-session`/`prev-session`/`archive-session` also fire on
the settings route (chat-page guard analog of the desktop focus fix).

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
nextest `test(tabs) or test(shortcut)`; web `tests/shortcuts.test.ts`.

- [ ] Shortcuts fire from Settings focus (desktop)
- [ ] Web route guard relaxed + test
- [ ] Tests green
- [ ] Port commit records upstream SHA
