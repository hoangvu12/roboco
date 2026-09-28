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
