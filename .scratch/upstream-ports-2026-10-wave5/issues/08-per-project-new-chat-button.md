# 08 — Per-project new-chat button and archive tooltip

**What to build:** Port upstream `e2a7706f` (#737) — two small sidebar
affordances:

1. Hovering a project group in the thread list reveals a `+` before
   the collapse chevron that opens the new-chat canvas already homed
   on that project, instead of using the titlebar `+` and then picking
   the project. `open_new_session` takes an optional project that wins
   over the sidebar filter; existing callers pass `None`. The button
   only appears for real projects when grouping by project.
2. The Archive/Unarchive pill that replaces a thread row's time on
   hover gets a tooltip ("Archive session"/"Unarchive session"), ABOVE
   the pill rather than below, where gpui's default placement would
   cover the next row — via a new `text_tooltip_above` helper
   (`text_tooltip` anchored above the pointer). The `+` button gets
   the same above-tooltip (below the pointer the chip would cover the
   group's first row).

**Roboco gap (verified):** group headers render only the disclosure
toggle (`crates/ui/src/shell/spaces.rs:4509-4517`); every new-chat
entry is global (titlebar `shell.rs:5452`, palette
`Entry::NewChat` `command_palette.rs:209`, `mod-n`); the new-chat draft
already carries project/device selection (`pickers.rs:93`) so the
button is a preselect trigger; the archive pill has no tooltip and
wave-4's tooltip sweep (ticket 22, upstream `26e2b0dd`) doesn't cover
it. Archive machinery exists (`set_chat_archived`, `shell.rs:4786`).

**Tooltip helper:** port `text_tooltip_above` into
`crates/ui/src/settings/widgets.rs` (+48 upstream), reusing the
wave-4 tooltip helper pattern.

**Web parity:** the same `+` on group headers and the archive tooltip
on `web/packages/app/src/components/chat-list.tsx` and
`archived-section.tsx` (sidebar-sections twins as needed).

**Blocked by:** 07 (same sidebar row/header surfaces; upstream merged
#751 first, then #737).

**Status:** ready-for-human

**Upstream SHAs:** `e2a7706f` (#737) —
`crates/ui/src/settings/widgets.rs` (+48), `shell.rs` (+83),
`shell/command_palette.rs` (+2), `shell/spaces.rs` (+55), `shell/tabs.rs`
(±10) → same paths here; web twins named above. Source:
`.scratch/upstream-drift/2026-10-05.md` § #737.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (corner-pill behavior tests,
`spaces.rs:394-505` style) and `pnpm -r build`.

- [x] `+` on project group headers, opens new-chat homed on that
      project (only real projects, project-grouping mode)
- [x] `open_new_session` optional-project param, existing callers
      pass `None`
- [x] `text_tooltip_above` helper ported
- [x] Archive/Unarchive tooltip above the pill; `+` tooltip above
- [x] Web twins (chat-list + archived-section)
- [x] Port commit records the upstream SHA

## Comments

Ported upstream `e2a7706f` (#737), on top of ticket 07's sidebar lane
(blocked-by respected). Diff stat matches upstream across the same five
files plus the web twins.

Desktop:

- `settings/widgets.rs`: `TextTooltip` gained the `above` flag
  (`TOOLTIP_ABOVE_GAP` = 20.0 — a zero-height end-aligned box makes the
  chip overflow upward); new `text_tooltip_above` builder. No other
  consumer of the tuple constructor existed.
- `shell/tabs.rs`: `open_new_session(project: Option<String>, cx)` —
  the explicit project wins over the sidebar filter
  (`project.or_else(space_filter)`); all nine existing callers pass
  `None`.
- `shell/spaces.rs`: `sidebar_disclosure_header` gained the `action`
  slot (between the rule and the chevron, preserving our diverged
  `with_rule` parameter); the device/project group header gets the
  hover-revealed `+` (group_hover, 20px, PLUS icon, "New chat in
  project" aria + above-tooltip, click stop-propagation ->
  `open_new_session(Some(project))`) — only for REAL projects when
  grouping by project (`space_row(&key).is_some()`), exactly
  upstream's gate. Pinned/Sessions/Archived pass `None`.
- `shell.rs`: the Archive/Unarchive corner pill gained the
  above-anchored tooltip ("Unarchive session"/"Archive session" via
  `ShortcutId::ArchiveSession.label()`), covering both compact and
  full rows (the one corner element).
- `command_palette.rs`: `Entry::NewChat` passes `None`.
- The upstream regression test
  `new_session_in_project_homes_the_canvas_on_that_project` ported
  into `mod exit_regressions` (our 3-field EngineBootConfig; the
  explicit project beats a standing filter).

Web twins (chat-list + archived-section as named by the ticket; the
archived rows ride `ChatListRow` so one pill covers both):

- `chat-list.tsx`: the project group header's hover-revealed `+`
  (`.sidebar-group-new-chat`, opacity-revealed on header hover, never
  display — no reflow) opens the new chat homed on that project via
  `rememberTarget(space.deviceId, space.id, false)` + navigate("/") —
  the remembered target wins over the sidebar filter exactly like
  `open_new_session(Some(..))`. The archive pills (compact + full
  corners) gained tooltips (the web family tooltip already places
  ABOVE the trigger, so no placement work was needed).
- `sidebar-disclosure.tsx`: the header gained an `action` slot; with
  an action it renders as a role=button div (nested interactive
  elements are invalid in a button — the explorer section-header
  pattern).
- `archived-section.tsx` needed no change (rows reuse ChatListRow).
- CSS: `.sidebar-group-new-chat` hover reveal + hover wash.

Verification deferred to the wave-final batched pass.
