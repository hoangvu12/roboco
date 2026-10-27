# 72 — Panels: per-surface adoption of shared width (wave-7 08 part 2, #677)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:** Move each surface onto 71's model, by intent:
`composer_dock*` (`observe_column`), `side_chats.rs`, `spaces.rs`,
`tabs.rs`, `files_panel.rs`, and the session card reserve (which
#677 predates; keep `ac4acbc21`'s right-reserve behaviour and ticket 24's
A38 diagram-width fix). Dragging a divider redistributes width
continuously. Opening or closing a column animates the others.

**Upstream:** `dace1a8ce` (#677), per-surface hunks (12 files,
+1844/−346 overall).

**Roboco files:** `crates/ui/src/shell/{composer_dock*,side_chats,spaces,tabs,files_panel,session_info}.rs`,
`crates/ui/src/shell.rs`.

**Excluded:** none known.

**Proto change:** no.

**Blocked by:** 71.

**Status:** ready-for-agent

**Source:** wave-7 `issues/08-panels-shared-width-transitions.md`.

- [ ] Files + transcript + queue share width while dragging; no surface clamps on its own (panel tests)
- [ ] Session card docked: the reading column and diagram width stay right
- [ ] Commit records `dace1a8ce` (part 2); absorbs wave-7 08 with 71

## Comments
