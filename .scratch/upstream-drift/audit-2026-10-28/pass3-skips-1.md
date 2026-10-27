# Pass 3: re-check of pass-1 SKIP / SUPERSEDED rows (recheck-skips-1.tsv, 30 commits)

Method: `git show --stat` plus every non-asset hunk for each commit. Each slice was checked against
Roboco `HEAD` (with renames) and `upstream/main`, and against `2026-10-28.md` A1–A45 / B–G and the `pass2-*.md` files.
None of the 30 commits is an ancestor of the fork point or of HEAD. All are side-branch work that
reached upstream later, so "present in Roboco" was verified in code, not by ancestry.

Summary: 30 commits. 28 CORRECT-SKIP, 2 HIDDEN-SLICE: one is new and minor, one is already tracked in §G.

## Table

| SHA | pass-1 class | verdict | evidence |
|---|---|---|---|
| 751a210b6 | SUPERSEDED | CORRECT-SKIP | Registry `SidebarPreferences` + `WatchSidebarPreferences` is cloud registry state. Roboco has engine-local `SidebarStateSnapshot`/`WatchSidebarState` (c548e7bf0). The 200-pin cap is present: `crates/engine/src/sidebar_state.rs:34`, `crates/ui/src/shell/sidebar_pins.rs:20` ("You can pin up to 200 sessions"), `crates/mobile-client/src/workspace/sidebar.rs:14`. |
| ed04b43a9 | SUPERSEDED | **HIDDEN-SLICE (minor, new)** | The sync/migration slice is cloud, and Roboco does it differently (UiSettings cache + `sidebar_state_sync.rs`). The non-cloud slice is that `pinned_drag_snapshot_is_valid` was tightened from "every snapshot pin still exists" to "the pinned order is identical". Upstream/main `spaces.rs:2187` has the strict form. Roboco HEAD `crates/ui/src/shell/spaces.rs:1420` still has the membership-only form, with its tests at `:743-746`. Since c548e7bf0, pins are shared live by desktop, Android and web, so a reorder from another client can land mid-drag (`apply_engine_sidebar_state` applies frames at any time, `sidebar_state_sync.rs:203`). See New gaps G1. |
| dfb72cb29 | SUPERSEDED | CORRECT-SKIP | Old SwiftUI pin swipe and row glyph, rewritten in #570. Android has Pin/Unpin: `session/SessionMenu.kt:106`, `sessions/CoreRowActions.kt:190`; icons `PinFill`/`PinSlash`. |
| d43292575 | SUPERSEDED | CORRECT-SKIP | Comments plus registry/engine tests for the cloud pin row only. |
| be87e9c2e | SUPERSEDED | CORRECT-SKIP | The overlay frost is gone (the floating explorer was removed upstream in 9033ff4eb and in Roboco 5605606d2). The docked wash survives: HEAD `files_panel.rs:353-354` `.bg(theme.panel_bg())` matches upstream/main `:563`. |
| dad1c86d9 | SUPERSEDED | CORRECT-SKIP | `files_overlay_width` is absent in both HEAD and upstream/main. Ported as 0c10e48d0 before the removal. |
| 7f0cd1336 | SUPERSEDED | CORRECT-SKIP | Roboco pins `hoangvu12/gpui-component` 4764fd00 and redirects through `[patch."https://github.com/zeronsh/zui"]` at the same rev as gpui (`Cargo.toml:91-99,168-184`), so there is one GPUI identity. |
| 7904e8a7a | SUPERSEDED | CORRECT-SKIP | The device tag in the project selector was removed again by upstream 672cc1444, which is PORTED (f382151fd). Ticket hygiene is already in §G. |
| cc754b46a | SUPERSEDED | CORRECT-SKIP | `search_owns_titlebar` / `SearchControlsChanged` are absent in both. `render_explorer_header` is in both (`crates/ui/src/files/mod.rs`). |
| bf14eca44 | SKIP:cloud | CORRECT-SKIP | `reconcile_sidebar_pins`, `MigrateSidebarPinnedSessions`, and the shell migration task are all Synced-scope. The Local branch (prune only after `chats_synced`) is already in Roboco: `shell.rs:2821-2833`. Roboco's import-once-if-engine-bucket-absent logic is at `sidebar_state_sync.rs:213-231`. |
| 8f2488cc7 | SKIP:cloud | CORRECT-SKIP | Tests for the registry pin RPC queue and the migration SQLite durability, plus a regressions-doc line. Roboco's write path is the single-flight dirty-flag bridge (`sidebar_state_sync.rs:76-120`), a different design with nothing to port. |
| 6ecea0558 | SUPERSEDED | CORRECT-SKIP | Old Swift transcript, ChatRoomClient and DocDisk were deleted by #570. The model-catalog normalization (`[1m]` fold, curated labels, resolve) survives as Rust and is present: `crates/mobile-client/src/catalog.rs:434,467` (= upstream `crates/client/src/catalog.rs:450,483`). Transcript anchor/follow is re-implemented in the Android port of TranscriptListView (`transcript/TranscriptState.kt:476-528`, `ScrollAnchoring`). |
| 94268229f | SUPERSEDED | CORRECT-SKIP | The sections queue in the registry, `SidebarSectionChange` and the edge test are cloud. Roboco re-homed sections engine-side (c548e7bf0 `sidebar_state.rs` `SetSidebarSections`, 50-section cap at `:38`, name cap `SIDEBAR_SECTION_NAME_MAX_CHARS`), and placed order came via f382151fd. |
| a1f3fd1a2 | SUPERSEDED | CORRECT-SKIP | The chat2 outbox, watchdog and DocDisk are sync-room code. The UI bits (centered Jump-to-latest, chip fade, offline/reconnecting strip, composer growth) were old SwiftUI views replaced by #570. Android mirrors the #570 versions (`session/JumpToLatestButton.kt`). The connectivity strip depends on cloud connectivity, which Roboco reports as `Disabled`. |
| 95a9e4d97 | SKIP:docs-only | CORRECT-SKIP | Status doc for Antigravity commits 52af27280 / 3f8f159b6, both PORTED. All the tests it lists exist in HEAD (`harness/tests/acp.rs:1710,1821,1835`; `engine/src/rpc.rs:4029`). |
| 855f8b4be | SKIP:docs-only | CORRECT-SKIP | Deletes the 95a9e4d97 doc, so the pair nets to zero. |
| 0e3239d97 | SUPERSEDED | CORRECT-SKIP | The Swift DocDisk/SessionStore byte residency code is gone since #570. The Android-side idea (a byte budget for the session cache) is already tracked as open PR #626 under "Smaller confirmed fixes → Android". |
| 0c834f65b | SKIP:docs-only | CORRECT-SKIP | `docs/research`. All 11 implementation commits it records are in HEAD: every named test was found (`codex/mod.rs`, `claude/catalog.rs`, `pickers.rs`, `tests/opencode.rs`, `tests/model_catalog.rs`), as were `harness/src/catalog.rs`, `catalog_failure.rs` and `executable.rs`. |
| 5a213ee25 | SUPERSEDED | CORRECT-SKIP | Screenshot README; deleted upstream by b8c0ccea5. |
| b3ac8b631 | SUPERSEDED | CORRECT-SKIP | Fixture-only `/review` chip example plus screenshots. `composer-polish-fixture` is absent in both trees. |
| cce9f252d | SKIP:docs-only | HIDDEN-SLICE (known, §G) | Roboco's `docs/harness-skill-completion.md:48-49` still says "Settings → Agents … each production harness". The controls live in Settings → Shortcuts → Composer completion (`crates/ui/src/settings/completion.rs:1,79`) and list only installed, enabled agents (`active_agents`, `:10`). That matches this commit's text exactly. Already listed in §G; not new. Note: upstream later moved the controls under Providers (#449/#1018), and Roboco keeps them in Shortcuts. |
| 4be4e0fda | SUPERSEDED | CORRECT-SKIP | `docs/harness-command-audit.md` is absent in both trees. |
| 3e621c492 | SUPERSEDED | CORRECT-SKIP | Ported as f5ae478e3. The segment was rebuilt by the Session card (Roboco 189c3d5a3); `project-action-add` is absent in both, and "Add action" now lives in `session_info.rs` in both. |
| 9b7983762 | SKIP:docs-only | CORRECT-SKIP | README sponsor block (zeronsh). |
| a9a78d768 | SKIP:docs-only | CORRECT-SKIP | README sponsor links. |
| 096f361f9 | SKIP:ios | CORRECT-SKIP | iOS test seams (`DocSaverScheduling`, `stop()` returning its Task) plus a `changes` path-filter job gating only the macOS `ios-tests` job in ui-tests.yml. Roboco has no iOS job. |
| b362693bc | SKIP:upstream-ci | CORRECT-SKIP | Only `scripts/test-cursor-sdk-auth.mjs`, which is not in Roboco. Nothing in HEAD matches on the SDK's minified `executor-common` internals (git grep empty). The Cursor SDK compat job is already a §D user call. |
| 45d346781 | SKIP:cloud | CORRECT-SKIP | Every hunk keys on chat2 room state (`delivery_live`, `ChatSyncState`, `sync_requested`). Roboco's `doc_host.rs:1074` only publishes `Connectivity::default()` (= `Disabled`), and `state.rs:1363` returns false on `Disabled`, so the sidebar pill and the degraded path can't fire. |
| 24d5e4917 | SKIP:docs-only | CORRECT-SKIP | Upstream CONTRIBUTORS.md (zeronsh process, `comet` infra names). Roboco has its own `docs/reference/windows-development.md`. |
| 198777a4a | SKIP:upstream-ci | CORRECT-SKIP | `testflight.yml` plus the iOS README. |

## New gaps

### G1 — A desktop pinned-session drag is not cancelled when another client reorders pins mid-drag (from ed04b43a9)
- **Effect:** Pins are now shared live by desktop, Android and web on the same engine. If the phone or web reorders pins while the user drags a pin on the desktop, the desktop drag continues against its frozen snapshot. The drop then re-applies the move by anchor id onto the new order. The result is coherent and loses no data: the other client's reorder survives, with the dragged pin placed next to its anchor. Upstream instead cancels the drag as soon as the visible pinned order differs from the snapshot. This is low value and arguably a design choice. Roboco's merge behaviour is defensible, so take it only if "cancel on remote change" is the wanted UX.
- **Files:** `crates/ui/src/shell/spaces.rs:1420` (`pinned_drag_snapshot_is_valid` → take `&[String]` and compare equality); `:4333-4358` (build `current_ids` as the ordered visible pins, not a set); tests at `:743-746`. The upstream form is at upstream/main `spaces.rs:2187`.
- **Effort:** XS. No wire change.

(The cce9f252d doc fix is already §G and is not repeated here.)
