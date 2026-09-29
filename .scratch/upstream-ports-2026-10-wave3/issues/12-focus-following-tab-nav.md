# 12 — Tab navigation follows focus between chat and right pane

**What to build:** Tab (cycle) navigation tracks focus: cycling sessions
when the main chat is focused, cycling right-pane tabs when the right pane
is focused; contextual navigation preserves native web content focus
(browser surface); focus recovery after tab close; the navigation shortcut
hint is formatted contextually. Adds `crates/ui/src/shell/navigation_focus.rs`.

**Blocked by:** 11 (its tests expect the most-recently-visited restore).

**Status:** ready-for-agent

**Upstream SHAs:** `a1ccea18` (#572) — 9 files, 814+ (navigation_focus.rs
new, tests, docs/regressions entry). Skip `apps/ios` if present. Web
parity: focus tracking between the chat route and right pane in the web
keymap/shortcut layer.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
nextest (navigation tests); web shortcuts vitest.

- [ ] Focus-following cycling (desktop)
- [ ] Web focus-following behavior
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments

**Branch:** `wave3/12-focus-following-tabs` → merged into main `Merge wave3/12`. Commits `10835369` (desktop, 9 files mirroring upstream's: navigation_focus.rs+tests new, shell.rs, tabs.rs, browser/{model,macos}.rs, settings{,/shortcuts}.rs, docs/regressions/contextual-tab-navigation.md) + `2f5a4ed6` (web: app-shell dispatch, right-pane aside tabIndex, strip close recovery, pure helpers, jsdom tests). Post-merge re-verified: ui 1315/1315 (-P ci), web 137 files/2062.

**Judgment calls:** navigation_overlay_open drops the cloud-sync overlay term; docked explorer cycles sessions on both platforms (matches regressions doc); native Tab normalization desktop-only (no web browser surface); free_port() TOCTOU flake pre-existing (ci profile retries cover it). Bare `cargo fmt -p roboco-ui` reformats unrelated files — don't.

**For 17:** NavigationFocus is pub(super) in shell's module tree; in_right(window,cx) is the classifier; capture_navigation_focus is the click seam; fallback returns right-pane scope as both handles when right_was_focused.
