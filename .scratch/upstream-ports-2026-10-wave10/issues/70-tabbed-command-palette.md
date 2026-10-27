# 70 — Tabbed Ctrl+K palette: All / Threads / Commands / In Files, recent files, thread content search (wave-7 20 part 2, #856)

**Lane:** 4b Features: desktop UI. **Effort:** M.

**What to build:** the UI half of #856 on top of 69's engine search:
- `command_palette.rs` (+2401): tabs All / Threads / Commands / In
  Files, `SelectPaletteTab`, file and content rows, Recent Files, and an
  "Archive thread" command.
- `shell/search_warm.rs` (+174): warm the index off the interaction path.
- `shell/thread_search.rs` (+166): thread content search.
- The `spaces.rs` hunks (+1046); the New project picker's "Anywhere under
  Home" row ("Searching Home…").
- Fleet: Threads search covers chats from every engine in the registry;
  In Files searches the current chat's engine.

**Upstream:** `3487b507f` (#856) UI hunks; docs
`docs/performance-workspace-search.md` and the command-palette README
section (text only).

**Roboco files:** `crates/ui/src/shell/{command_palette,search_warm (new),thread_search (new),spaces}.rs`,
`crates/ui/src/files/{client,files_panel}.rs` (recent files, `warm_search`).

**Excluded:** screenshots; cloud.

**Proto change:** no (uses 69's).

**Blocked by:** 69.

**Status:** ready-for-agent

**Source:** wave-7 `issues/20-workspace-search-cmdk-palette.md`;
`pass2-features-desktop.md` §2.

- [ ] Each tab lists the right rows; Tab/Shift+Tab switches tabs (palette tests)
- [ ] In Files content hits open at the line
- [ ] Warm-up keeps the first query under the upstream budget (perf test or recorded measurement)
- [ ] `cargo nextest run -p roboco-ui --lib` green
- [ ] Commit records `3487b507f` (UI half); absorbs wave-7 20 with 69

## Comments
