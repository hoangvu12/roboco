# 25 — File tree context actions and drag-and-drop moves

**What to build:** Workspace entry mutations end-to-end: proto mutation
contracts; engine moves entries without replacement and deletes entries,
publishing mutations with revisions; open surfaces (tree, editor tabs,
previews, git status) coordinate through mutation revisions — refreshed
after completed saves, editor presentation and syntax refreshed after
rename, canonical workspace path comparisons on Windows; the file tree
gains context menus (rename inline with caret and preserved input focus,
delete with confirmation showing only the entry name, copy full path from
the owning workspace, add to chat), drag-and-drop with scoped drop
targets, drag-origin preservation, Windows drag handles, polished drag
feedback and navigation; destructive delete styled and labeled; the chat
dropzone is shared by the main chat and side chats with origin checks —
workspace payloads attach to the target composer, origin-less (stale)
payloads attach nowhere. Follow-up #612 (folded in): side chats accept
file drag-and-drop through the same shared dropzone.

**Blocked by:** None. (Wave 3 shipped the file tree, side chats and
explorer sections this builds on.)

**Status:** ready-for-agent

**Upstream SHAs:** `c72c66d8` (#514) — 26 files: `proto/src/entities.rs`
(contracts), `engine/src/workspace_files.rs` +
`engine/src/workspace_files/mutations.rs` (new submodule of our single
file — modern layout allows `workspace_files.rs` + `workspace_files/`
sibling), `engine/tests/{workspace_files,device_routing}.rs`, `rpc/src/lib.rs`,
`ui/src/files/{client,context_menu,drag,mod,model,mutations,preview,rename,
search,test_support,tree,watch}.rs` (several new), `ui/src/shell/{chat_dropzone,
chat_dropzone_tests,file_mutations,files_panel}.rs`, `docs/file-tree-interactions.md`
(port with rebrand; skip `docs/research/windows-click-drag-audit.md` —
research stays verbatim upstream). `e2caf64a` (#612) — `shell.rs`,
`shell/{chat_dropzone,chat_dropzone_tests}.rs`, `shell/side_chats.rs`.
Windows is first-class here (Roboco is Windows-native): keep the Windows
drag handles + canonical path hunks intact.

**Wire surface:** the mutation contracts are proto changes — run
`wiregen` FIRST, then build the web side on the fresh types.
**Web parity (deliverable):**
`web/packages/app/src/components/files/file-tree-panel.tsx` +
`lib/file-tree.ts` + `lib/files-client.ts` — port the context actions
(rename inline, delete confirm, copy path, add to chat) as web menus, and
the moves via native HTML5 drag-and-drop against the mutation RPCs; web
editor/preview tabs refresh on mutation revisions the same way.

**Verification budget:** `cargo check -p roboco-engine -p roboco-ui -j 3`;
nextest engine workspace_files + device_routing, ui files mutation/drag
suites; `pnpm -r build` after wiregen.

- [ ] Engine mutations: move-without-replacement, delete, published
      revisions, canonical Windows paths
- [ ] Context menu actions (rename inline, delete confirm, copy path,
      add to chat) with destructive styling
- [ ] DnD with scoped drop targets + origin checks; shared chat dropzone
      covers main + side chats; stale payloads attach nowhere
- [ ] Editor/preview/syntax refresh after rename; revisions after saves
- [ ] Wire types regenerated; web file tree gains actions + DnD moves
      against the fresh types; engine-client vitest extended
- [ ] Tests green
- [ ] Port commit records upstream SHAs

## Comments
