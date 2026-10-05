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

**Status:** ready-for-agent

**Upstream SHAs:** `c78bb1c1` (9 files, +1023/−121: `shell.rs` +358,
`chat_rename_tests.rs` new, `spaces.rs` +193, `files/sections.rs`
+206, `composer.rs` +5, small files/mod/shell submodule deltas) +
`46bedeca` (27 test lines) → same paths here (ui crate un-prefixed).
Source: `.scratch/upstream-drift/2026-10-05.md` § #751.

**Verification budget:** deferred — wave-final batched pass:
`cargo nextest run -p roboco-ui --lib` (the ported rename tests) and
`pnpm -r build` (web twin).

- [ ] Double-click row (thread + side-chat) opens the inline field,
      name selected
- [ ] Menu Rename + `/rename` open the same field
- [ ] Enter/blur saves non-empty changes; Escape cancels
- [ ] `RenameChatDialog` and its overlay gate deleted
- [ ] Reveal-and-scroll for collapsed groups, Archived shelf paging,
      collapsed sidebar, explorer Chats section
- [ ] Cannot-show case: no field + sidebar notice with the reason
- [ ] Tests in both full and compact row modes
- [ ] Web dialogs replaced by the inline treatment
- [ ] Port commit records the upstream SHAs + exclusions
