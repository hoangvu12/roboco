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

## Comments

**Branch:** `wave3/09-side-chats-ui` (rebased onto 47b70705 = 07's port commit, ancestor of main) → merged into main `Merge wave3/09`. Commit `f793862a` (15 files, +2803/−143): side_chats.rs (587), files/sections.rs (1217), hunks across composer/files/pickers/queue/settings/shell/state/transcript; ForkMarker divider; state.rs parent filter + pending_side_chat. Post-merge re-verified: full ui nextest 1299/1299, wiregen fresh.

**Judgment calls:** Devin-Fusion model-picker rework excluded (unrelated squash content); surface-picker card superseded by upstream's end state (+/fork on session header + explorer Chats header); fixed 510px footer; MCP attribution template renamed Roboco — ticket 08's roboco-mcp emits the matching prefix (upstream template 731697b6:crates/mcp/src/tools.rs:934); set_ipc_port state propagation landed with 08.

**TOPOLOGY NOTE (2026-09-28):** mid-wave, the user merged PRs #13+#14 into origin/main (closing PR #15 — its base branch was deleted with #13's merge) and integrated the wave-3 ticket branches onto LOCAL main themselves (reflog: merge wave3/01..07). Local main is now the integration branch (strictly ahead of origin/main; #14's wss fix included). I follow that pattern from here: ticket branches merge into local main; main is NOT pushed (user's call); PR #15's checklist tracking continues in this file + ORCHESTRATOR-STATE.md; a replacement PR gets cut at program end (or the user pushes main directly).
