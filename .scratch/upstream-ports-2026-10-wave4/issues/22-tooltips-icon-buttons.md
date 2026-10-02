# 22 — Hover tooltips on icon-only buttons

**What to build:** Several icon-only buttons had no tooltip, so their
purpose was only discoverable by clicking. Add tooltips using the existing
tooltip helpers the neighbouring buttons already use. Where a shared
helper built the button (`window_control_button`, `nav_history_button`,
`header_icon_button`, Changes header toggle, files header action), the
label becomes a parameter so every caller gets one. Toggle-style buttons
(expand/collapse, files panel, fold all) show the label for the action
they will perform.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `26e2b0dd` (#628) — 16 files across
`crates/ui/src/`: `browser/view.rs`, `changes.rs`, `comment_ui.rs`,
`composer.rs`, `files/preview.rs`, `files/sections.rs`, `history.rs`,
`markdown/render.rs`, `notice.rs`, `settings/accounts.rs`, `shell.rs`,
`shell/actions_ui.rs`, `shell/sidebar_sections.rs`, `shell/tabs.rs`,
`terminal/panel.rs`, `transcript.rs`. All exist here 1:1.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
on the touched surfaces (window controls, history, files header, changes
header); tooltips are string-parameter changes — behavior verified by
compile + existing tests.

- [ ] Every icon-only button covered; shared helpers take a label
- [ ] Toggle buttons label the pending action
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments
