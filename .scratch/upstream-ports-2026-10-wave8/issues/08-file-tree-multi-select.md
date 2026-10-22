# 08 — Select several entries in the file tree (upstream #774, `2c2963aae`)

**What to build:** Port `2c2963aae` (#774): multi-select in the files
tree — range and modifier-click selection in `files/model.rs`, bulk
context-menu actions (`files/context_menu.rs`), multi-entry drag
(`files/drag.rs`), bulk mutations and rename handling, plus the
`docs/file-tree-interactions.md` documentation update.

**Why now:** roboco holds the whole `crates/ui/src/files/` tree from
earlier ports; the change is self-contained UI (+968 across 11 files)
with no wire or pin involvement.

**Steps:**
1. `git show 2c2963aae` — model.rs +322, context_menu.rs +171,
   drag.rs +190, mutations.rs +121, rename.rs +113, tree.rs +198,
   mod.rs +61, shell/file_mutations.rs +107, files_panel.rs +9,
   shell.rs +11, test_support.rs +27, docs/file-tree-interactions.md.
2. Port in that order: selection model first, then the actions that
   consume it, then drag, then docs.
3. Adapt to roboco's file-mutation plumbing names where they drifted
   from upstream's.

**Excluded upstream hunks:** none expected.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-ui` + files tree tests
(upstream ships selection tests via `test_support.rs`). Source:
`.scratch/upstream-drift/2026-10-22.md` § medium tickets.

- [ ] Selection model + bulk actions + drag + rename ported
- [ ] `docs/file-tree-interactions.md` carried
- [ ] Commit records upstream SHA `2c2963aae` (#774)

## Comments
