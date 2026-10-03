# 22 — Hover tooltips on icon-only buttons

**What to build:** Several icon-only buttons had no tooltip, so their
purpose was only discoverable by clicking. Add tooltips using the existing
tooltip helpers the neighbouring buttons already use. Where a shared
helper built the button (`window_control_button`, `nav_history_button`,
`header_icon_button`, Changes header toggle, files header action), the
label becomes a parameter so every caller gets one. Toggle-style buttons
(expand/collapse, files panel, fold all) show the label for the action
they will perform.

**Blocked by:** 12, 14, 21, 23, 24 — the tooltip sweep runs LAST,
after the tickets that rewrite the button surfaces it parameterizes
(`composer.rs`, `shell.rs`, terminal, tabs), so the helper-label sweep
covers the final button set including 24's new mic button.

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
compile + existing tests. Web: web buttons already carry `title`/
aria-labels — sweep the same icon-only buttons
(`web/packages/app/src/components/`) and add the missing titles/aria
where the sweep finds a real gap.

- [x] Every icon-only button covered; shared helpers take a label
- [x] Toggle buttons label the pending action
- [ ] Tests green — deferred to the wave-final batched pass (user
      directive; no test execution in this lane)
- [x] Port commit records upstream SHA

## Comments

Port mapping (upstream `26e2b0dd` → this branch):

- All 16 upstream files ported; the shared helpers take a label parameter
  (`window_control_button`, `nav_history_button`, `header_icon_button`,
  `Changes::header_button`/`header_toggle`) and attach the tooltip
  themselves, so every caller is labeled. Toggle buttons label the pending
  action: `expand-changes` ("Collapse/Expand panel"), files panel
  ("Hide/Show files panel", aria_label now shares the same hoisted
  label), fold-all ("Expand/Collapse all files" via `fold_all_label()`).
  Titlebar window-control labels reuse `ShortcutId::label()` ("Toggle left sidebar", "New session", "Toggle right sidebar").
- Wave-created surfaces swept beyond upstream's file list: compact picker
  `compact-list-back` ("Back", PickerHint like its neighbours), theme
  import dialog close ("Close"), background-adjustment dialog close
  ("Close"). Already covered by their own lanes: dictation mic (ticket 24),
  queue rows/thumbnails (tickets 10/15), right-tab close (ticket 11),
  composer paperclip (ticket 24's attach/cancel tooltip), appshot strip,
  files `toolbar_button`/`header_action` label plumbing (the `header_action`
  tooltip hunk lands here), explorer search/visibility controls. Dropzones
  carry no buttons; file-tree context rows are text menu rows.
- Exclusions/adaptations: `settings/accounts.rs` upstream hunk targeted a
  per-row "more" (⋯) menu trigger ("Account actions") that Roboco removed
  with the cloud/WorkOS code — the equivalent icon-only button there is the
  Forget (trash) action, which now gets `"Forget account"`. Upstream's
  composer paperclip tooltip was already satisfied here by ticket 24's
  attach/cancel tooltip. No rebrand-relevant strings existed in this commit
  to map.
- Web parity (title attributes on the same icon-only buttons): composer
  send/stop ("Send message"/"Queue message"/"Stop") and attach
  ("Attach images"); markdown code copy ("Copy code"); notice chip and
  transcript copy ("Copy message"); star banner dismiss ("Dismiss");
  history columns ("Show column menu") and search close ("Clear search");
  right-surface-add ("New tab"); compact picker back ("Back") and model
  star ("Star/Unstar model"); sidebar section menu ("Section menu: …");
  review-comment remove/edit ("Remove comment"/"Edit comment"). Web
  surfaces with their own aria-labels but no hover gap beyond these were
  left as-is.

Verification: `cargo check -p roboco -j 3` (includes roboco-ui via the
workspace); `rustfmt --edition 2024` on the 18 touched Rust files;
`pnpm -r build` from web/; test execution deferred to the wave-final
batched pass (user directive).
