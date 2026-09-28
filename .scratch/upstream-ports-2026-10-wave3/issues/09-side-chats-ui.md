# 09 — Side chats UI: right-pane chat surface + explorer sections

**What to build:** The UI half of upstream #498: side chats open in the
right pane as tabs (surface picker places the side chat below Terminal);
side-chat surface has a header with "+" (new chat in this session) and fork;
the file explorer docks two collapsible sections under the tree — Subagents
(the active chat's spawn chips with live status) and Chats (its children:
forks and agent-spawned side chats; the header's "+" starts a fresh side
chat); rows are the sidebar's compact session row without harness/project/
device icons; sections animate with the sidebar's collapse motion and only
re-render when contents change; side-chat history as a counted footer badge;
fork seam renders "This chat was forked from <title>"; includes #498's
fixups (opening the surface host from footer rows, collapse endpoints,
"Show N more" paging by ten, composer dock frame/column width, chip hover
fades, fading footer labels, PR badge on footer rows, 510px footer budget).

**Blocked by:** 07. (08 not required — the UI works without the injected
MCP server; agent-spawned chats simply don't occur yet.)

**Status:** ready-for-agent

**Upstream SHAs:** `731697b6` — ui subset: `crates/ui/src/shell/side_chats.rs`
(new), `crates/ui/src/files/sections.rs` (new), + hunks in composer,
files/{mod,tree}, motion, pickers, queue, settings/archived, shell,
shell/{files_panel,files_panel_workspace_tests,spaces,tabs}, state,
transcript. Skip `apps/ios/**`.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
nextest for side-chat/explorer-section suites; no wire changes expected
(engine types from 07 already regen'd).

- [ ] Side-chat surface: tabs, header "+"/fork, picker placement, history badge
- [ ] Explorer Subagents/Chats sections, paging, animations
- [ ] Fork seam rendering
- [ ] #498 UI fixups carried
- [ ] UI tests green
- [ ] Port commit records upstream SHA + exclusions
