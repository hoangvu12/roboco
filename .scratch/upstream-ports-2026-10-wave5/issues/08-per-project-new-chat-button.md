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

**Status:** ready-for-agent

**Upstream SHAs:** `e2a7706f` (#737) —
`crates/ui/src/settings/widgets.rs` (+48), `shell.rs` (+83),
`shell/command_palette.rs` (+2), `shell/spaces.rs` (+55), `shell/tabs.rs`
(±10) → same paths here; web twins named above. Source:
`.scratch/upstream-drift/2026-10-05.md` § #737.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (corner-pill behavior tests,
`spaces.rs:394-505` style) and `pnpm -r build`.

- [ ] `+` on project group headers, opens new-chat homed on that
      project (only real projects, project-grouping mode)
- [ ] `open_new_session` optional-project param, existing callers
      pass `None`
- [ ] `text_tooltip_above` helper ported
- [ ] Archive/Unarchive tooltip above the pill; `+` tooltip above
- [ ] Web twins (chat-list + archived-section)
- [ ] Port commit records the upstream SHA
