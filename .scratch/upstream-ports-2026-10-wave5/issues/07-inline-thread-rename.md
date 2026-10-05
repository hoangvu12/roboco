# 07 — Rename threads and side chats inline instead of in a dialog

**What to build:** Port upstream `c78bb1c1` + `46bedeca` (PR #751,
merge `1c83cda2`): double-clicking a session row in the sidebar, or a
side-chat row under the file tree, swaps its title for an in-place
field with the name selected. The chat menu's Rename and the `/rename`
command open the same field. Enter or blur saves a changed, non-empty
title; Escape cancels. The Rename session modal is removed.

The hard part is reveal-and-scroll: a rename started without touching
the row first reveals it — the collapsed Pinned/Sessions/custom
section or device/project group opens with its usual motion, the
Archived shelf opens and pages to the row, a collapsed sidebar
expands, and the list scrolls the row clear of its edge fades. The
explorer's Chats section opens, pages, and scrolls the same way. When
the row cannot be shown (hidden by the project filter, or a side chat
of another chat), no field opens and a sidebar notice says why.

**Roboco state (verified):** the dialog flow is fully intact —
`RenameChatDialog` (`crates/ui/src/shell.rs:1374-1380`),
`open_rename_chat` (`shell.rs:4743-4767`), the modal render
(`shell.rs:8325-8369`), `submit_rename_chat` → `Mutate renameChat`
(`shell.rs:4779`), entry points: chat-menu "Rename…"
(`shell.rs:8104-8107`), `WorkspaceCommand::Rename` (`shell.rs:10558`);
side chats route through the same menu (`files/sections.rs:826` →
`files_panel.rs:240-245`). The engine mutation already exists
engine-locally (`crates/engine/src/rpc.rs` Mutate ops). Precedent to
mirror: the files tree's inline rename (`files/rename.rs` `TreeRename`,
comment at `files_panel.rs:281`).

**Carry by intent, not by patch:** `render_chat_row` is our
most-diverged UI function (drag-preview rows, palette-copy namespacing
via `search_query`, jump hints from `f779045e`, remote rows) — port
the field/reveal behaviors onto our row structure. `46bedeca`'s tests
(full + compact rows) map to new `shell/chat_rename_tests.rs`
(upstream: 374+27 lines) beside our shell/spaces test modules.

**Web parity:** the web still uses dialogs (`chat-menu.tsx:112`,
`surface-registry.tsx:475/547`, `chat-page.tsx:415/1459`) — give them
the same inline treatment in this ticket.

**Blocked by:** None (upstream merged #751 before #737; run this
before ticket 08).

**Status:** claimed

**Upstream SHAs:** `c78bb1c1` (9 files, +1023/−121: `shell.rs` +358,
`chat_rename_tests.rs` new, `spaces.rs` +193, `files/sections.rs`
+206, `composer.rs` +5, small files/mod/shell submodule deltas) +
`46bedeca` (27 test lines) → same paths here (ui crate un-prefixed).
Source: `.scratch/upstream-drift/2026-10-05.md` § #751.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (the ported rename tests) and
`pnpm -r build` (web twin).

- [x] Double-click row (thread + side-chat) opens the inline field,
      name selected
- [x] Menu Rename + `/rename` open the same field
- [x] Enter/blur saves non-empty changes; Escape cancels
- [x] `RenameChatDialog` and its overlay gate deleted
- [x] Reveal-and-scroll for collapsed groups, Archived shelf paging,
      collapsed sidebar, explorer Chats section
- [x] Cannot-show case: no field + sidebar notice with the reason
- [x] Tests in both full and compact row modes
- [x] Web dialogs replaced by the inline treatment
- [x] Port commit records the upstream SHAs + exclusions

## Comments

Ported upstream `c78bb1c1` + `46bedeca` (PR #751, merge `1c83cda2`) —
carried by intent onto our diverged `render_chat_row` as the ticket
required. Upstream SHAs recorded; rebrand needed only test fixture
names.

Desktop (diff stat: shell.rs +360, spaces.rs +200, sections.rs +207,
chat_rename_tests.rs new 374+27, plus the small files):

- `shell.rs`: `chat_title_editor` helper; `RenameChatDialog` ->
  `ChatRename { surface, reveal_until, _blur }`; the reveal-and-scroll
  machinery (`keep_rename_row_in_view` via a paint-time canvas child,
  `chat_rename_revealing`, COLLAPSE + 150ms grace); `open_rename_chat`
  resolves the surface (sidebar vs explorer by `parent_chat_id`),
  refuses hidden rows with sidebar notices ("Clear the project filter
  to rename this session" / "Open this side chat's parent to rename
  it"); `finish_rename_chat` commits only changed non-empty titles
  (unchanged Enter = no-op — upstream's addition over our old dialog);
  composer `focus_pending` cleared on open so the field isn't stolen;
  `focus_rename_chat` arms commit-on-blur; menu Rename ("Rename…"->
  "Rename") docks the explorer for side chats; the modal deleted; the
  escape arm routes through `finish_rename_chat(false)`; Render calls
  `focus_rename_chat`.
- `render_chat_row` (ours, diverged): palette copy never renames
  (`search_query_none` gate), double-click opens the editor, the
  title slot swaps `{row_id}-title` -> `{row_id}-title-editor`
  (namespaced ids preserved so the palette copy never matches), the
  reveal canvas rides the existing drag/hover-resync pattern.
- `spaces.rs`: ARCHIVED_* consts extracted; `sidebar_group_key`/
  `sidebar_group_collapse_key` factored from `sidebar_chat_data`;
  `reveal_sidebar_chat` (pinned/custom-section/group/sessions/
  archived-shelf paging/collapsed-sidebar); `queue_sidebar_reveal`
  (replaces the resort glide like a header click);
  `begin_queued_sidebar_reveal` wired into group, pinned, sessions,
  archived, and custom-section renders.
  - **Divergence adaptation:** custom sections are device-local
    UiSettings in Roboco (upstream drives a synced-section RPC); the
    reveal expands the settings bucket directly + schedule_save +
    push_sidebar_state, refusing when the profile key is not ready.
  - The extracted `archived_sidebar_chats` carries upstream's
    `parent_chat_id.is_none()` top-level rule — aligning our Archived
    shelf with our own active-list rule (state.rs:1727).
- `files/sections.rs`: `ExplorerSections.chat_rename`/`reveal` fields;
  `begin_reveal`; `FilesSurface::set_chat_rename` +
  `reveal_chat_row` (opens, pages, `scroll_to_item`); the Chats row
  double-click emits `RenameChildChat`; the title swaps for the shared
  `chat_title_editor`. Two upstream tests ported.
- `files/mod.rs` + `files_panel.rs`: `FilesEvent::RenameChildChat`
  routed to `open_rename_chat` (and the editor-surface ignore arm).
- `tabs.rs`: overlay gate `rename_dialog` -> `chat_rename`.
- `composer.rs`: `select_all_text` extraction.
- `shell/chat_rename_tests.rs`: all 10 upstream tests adapted to our
  shell (EngineBootConfig without edge/workos, `_state_observation`
  neutralized per the chat_dropzone_tests precedent, device-local
  custom-section fixture, `{row_id}-title-editor` selector scheme).

Web parity (the dialogs replaced by the inline treatment):

- NEW `state/chat-rename.ts`: the single active rename
  (`Shell::chat_rename` peer) — module-level store so `/rename`, the
  menu, and rows across trees share it.
- NEW `components/inline-chat-title-editor.tsx`: select-all on mount,
  Enter/blur commit (changed non-empty only), Escape cancels, click
  propagation stopped; mutations route through the owning engine like
  the old dialog.
- `chat-list.tsx`: row double-click + title swap (archived rows ride
  `ChatListRow` too); `chat-menu.tsx`: Rename row begins the inline
  edit, label "Rename…"-"Rename", rename dialog state removed;
  `chat-page.tsx` + `surface-registry.tsx`: `/rename` targets the
  sidebar row / the explorer's Chats row (side chats dock the files
  surface first — the web shape of the explorer-surface check);
  `files/explorer-sections.tsx`: the Chats row swaps its title and
  takes the double-click.
- `rename-chat-dialog.tsx` deleted (RenameChatDialog equivalent).
- **Recorded divergence (web):** the reveal-and-scroll machinery
  (disclosure tweens, shelf paging, scroll-into-view) is desktop-gpui
  specific; on the web the field opens on the row wherever it renders
  and collapsed-section reveal is not auto-driven. The sidebar's
  pinned/sessions/archived sections default open, so the common paths
  show the field; the notice path for filter-hidden rows rides the
  desktop only.

Nothing excluded from the upstream commit (no cloud/iOS hunks
existed). Verification deferred to the wave-final batched pass.
