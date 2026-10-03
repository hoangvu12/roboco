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

**Blocked by:** 13 — both touch `engine/workspace_files*` + proto +
`ui/src/files/`; the read-path extension lands before the mutation
contracts. (Wave 3 shipped the file tree, side chats and explorer
sections this builds on.)

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

- [x] Engine mutations: move-without-replacement, delete, published
      revisions, canonical Windows paths
- [x] Context menu actions (rename inline, delete confirm, copy path,
      add to chat) with destructive styling
- [x] DnD with scoped drop targets + origin checks; shared chat dropzone
      covers main + side chats; stale payloads attach nowhere
- [x] Editor/preview/syntax refresh after rename; revisions after saves
- [x] Wire types regenerated; web file tree gains actions + DnD moves
      against the fresh types; engine-client vitest extended
- [ ] Tests green
- [x] Port commit records upstream SHAs

## Comments

Implementation complete; test EXECUTION is deferred to the wave-final
batched pass (user directive: no per-ticket cargo test/nextest runs). All
test targets typecheck
(`cargo check -p roboco-ui -p roboco-engine -p roboco-proto --tests`), the
app builds (`cargo check -p roboco -j 3`), `pnpm -r build` is green, and
the web suites that DID run locally are green (app vitest 2108/2108,
engine-client unit 35/35).

Port mapping (upstream c72c66d8 = #514, e2caf64a = #612):

- `proto/entities.rs`: page `checkoutId`/`mutationCapabilities`, entry
  `mutationRevision`, change `operationId`, and the five mutation
  contract types — additive, older peers stay readable (tests pinned).
  Our proto derives `TS` (wire types regenerated via `wiregen`).
- `engine/workspace_files.rs` + new `workspace_files/mutations.rs`:
  delete/move RPCs with exclusive checkout gates (saves take them shared,
  re-resolving the target after the wait), metadata-revision consent,
  `RENAME_NOREPLACE`/`RENAME_EXCL`/`MoveFileExW` no-replacement moves,
  reparse-point-aware `is_link`, canonical-root comparison in
  `checked_directory` (the Windows subst/junction fix), semantic events
  published with the operation id, case-only rename staging through
  `.roboco-save-*.tmp` (our existing atomic-save temp spelling).
- `engine/rpc.rs`: `MoveWorkspaceEntry`/`DeleteWorkspaceEntry` handlers,
  un-timed (a recursive delete outlasts the 6s file RPC budget).
  Upstream's `forwardable()`/relay forwarding does NOT port: Roboco's
  engine fails closed on `targetDeviceId` and the UI's engine registry
  routes to the owning device (ADR 0004 shape). The upstream
  `device_routing.rs` forwarding test therefore has no equivalent here;
  remote addressing is covered by the UI client test
  (`structural_mutations_preserve_remote_addressing_and_never_retry_transport_errors`).
- `ui/src/files/*`: `mutations.rs` (origin/intent/reconciliation),
  `context_menu.rs`, `drag.rs` (scoped drop targets, 650ms hover expand,
  edge autoscroll, Windows row-drag regression), `rename.rs` (inline
  rename + delete dialog), `test_support.rs`, `WorkspacePathDrag`
  origin/source/revision, `relocate_subtree`/`invalidate_loads` in
  `model.rs`, watch deferral + semantic application + parent reload on
  Modified in `watch.rs`, editor retarget (highlighter reset, markdown
  flip, highlights dropped) + autosave/save gating in `preview.rs`.
- `ui/src/shell*`: `chat_dropzone.rs` (shared main/side-chat receiver
  with origin checks — #612's extraction plus #514's fix),
  `file_mutations.rs` (fan-out, save wait, revision refresh), side chats
  render through the shared dropzone, hidden explorers/files-panel close
  suspend tree interactions.
- Web: `files-client` move/delete, `file-tree` model mutations
  (capabilities/checkout from pages, `relocateSubtree`, semantic change
  application, mutation error row), panel context menu + inline rename +
  delete dialog + row/root HTML5 drop targets, `chat-insert.ts` +
  composer wiring for Add to chat (the web shape of
  `composer.add_workspace_path`), file-document/registry/tab rename flow
  for renamed events, engine-client method constants.

Exclusions (deliberate):

- `docs/research/windows-click-drag-audit.md` stays upstream (research is
  verbatim per the ticket); the behavior it records (row-wide drag on
  every platform, Windows threshold) is covered by the Windows-only UI
  regression and the ported `docs/file-tree-interactions.md`.
- Web parity adaptation: the desktop shell waits for in-flight saves and
  refreshes the source revision through the same surface's documents; the
  web tree panel and the open document live in different models, so the
  web relies on the engine's `sourceChanged` revalidation (and the
  modified→parent-reload revision refresh) instead of a client-side save
  wait. Recorded here as the one conscious behavioral divergence.
- No dev-server/visual verification (user directive); Windows filesystem
  interactions are compile-gated and covered by Windows CI.

Verification: `cargo run --locked -p wiregen` (regenerated types);
`cargo check -p roboco -j 3` (green, after the shared-target touch
ritual); `cargo check -p roboco-ui -p roboco-engine -p roboco-proto
--tests -j 3` (green — all ported test targets typecheck); `pnpm -r build`
from `web/` (green); targeted vitest before deferral: app
`tests/file-tree.test.ts` + `tests/files-client.test.ts` (51/51), full app
suite (2108/2108), engine-client unit project (35/35). Test execution
defers to the wave-final batched pass (user directive).
