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

**Status:** done (ported in f08bdd585)

**Verification budget:** `cargo check -p roboco-ui` + files tree tests
(upstream ships selection tests via `test_support.rs`). Source:
`.scratch/upstream-drift/2026-10-22.md` § medium tickets.

- [x] Selection model + bulk actions + drag + rename ported
- [x] `docs/file-tree-interactions.md` carried
- [x] Commit records upstream SHA `2c2963aae` (#774)

## Comments

- f08bdd585_PLACEHOLDER
- Ported in commit f08bdd585, ported in the ticket's order: model.rs
  selection model first, then tree.rs modifier clicks + context menu
  actions, drag.rs multi-entry drops, rename.rs multi-item delete,
  mutations.rs batching, shell fan-out (MutateMany queue in
  shell/file_mutations.rs), test_support.rs set_root_entries, and
  docs/file-tree-interactions.md's "Selecting several entries"
  section. All 8 upstream tests carried (3 model, 2 tree, 1 context
  menu, 2 drag, 1 mutations, 2 file_mutations) plus the 3 model
  selection tests; files:: suite 154/154, shell::file_mutations 4/4,
  cargo check -p roboco-ui clean.
- Adaptations (not exclusions): roboco_proto rename;
  `batch_failure_message` etc. carried verbatim; roboco's
  engine-target routing inside run_file_mutation_queue kept as the
  single-operation path the queue replays. No excluded hunks.
