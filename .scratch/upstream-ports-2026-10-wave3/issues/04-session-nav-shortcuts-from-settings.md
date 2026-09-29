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

## Comments

**Branch:** `wave3/04-session-nav-settings` → merged `Merge wave3/04`. Commits `b2ba67fa` (desktop: overlay_owns_keyboard drops Route::Settings, cycle_session gate dropped, regression test) + `cd3f2978` (web: sessionNavFires pure helper in state/shortcuts.ts, app-shell guard relaxed, tests).

**Verification:** cargo check ui clean; nextest 59/59 (tabs/shortcut/navigation) + 274/274 (settings/shell sweep); web typecheck clean + shortcuts 59/59 + 7 app-shell suites 75/75. Post-merge re-verified: 183/183 + typecheck + shortcuts 59/59.

**Judgment calls:** archive stays chat-scoped on desktop (upstream's own asymmetry); web archive guard relaxed but inert from settings (chat id off URL); jump-session already route-free.
