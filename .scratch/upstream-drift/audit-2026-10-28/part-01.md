# Part 01 — chunk-01.txt (48950003b .. ffaa3102b, upstream 2026-09-16 .. 2026-09-18, v0.2.68 -> v0.2.78)
Summary: 74 commits — PORTED 57, PARTIAL 1, MISSING 0, DECLINED 0, SUPERSEDED 1, SKIP 15, N/A 0

Method: `git show --stat` + diff hunks per commit; every `fn` added in .rs/.mjs hunks was grepped in
HEAD (crates/, apps/). Each gap was checked against upstream/main (gone upstream as well = later
upstream refactor, not a gap) and against the Roboco port commit message in `roboco-log.txt`. All
Roboco port commits cited below were verified as ancestors of HEAD (85be4485a).
SKIP breakdown: version-bump 11, landing 2, cloud 2.

## Table
| SHA/PR | date | upstream PR | title | area | class | evidence | effort | notes |
|---|---|---|---|---|---|---|---|---|
| 48950003b | 2026-09-16 | #403 | Unify popup glass surfaces and redesign New project navigation | ui-desktop | PORTED | Roboco cc7fb1ba0 (+ palette hunks in d9dfb0bc8); `popover::surface_bg` popover.rs:324, `palette_search_icon` popover.rs:1047, crates/ui/examples/new-project-fixture.rs; 9/9 added fns present | - | Roboco chose MENU_BLUR 44 instead of 16 on purpose |
| dd0d7c3a7 | 2026-09-16 | - | Release v0.2.68 | other | SKIP:version-bump | Cargo.toml/lock only | - | |
| bb4c13c25 | 2026-09-16 | (#340) | Refine Files tree icon geometry | files/editor | PORTED | Roboco 31cafb675; crates/ui/assets/icons/file-tree.svg is byte-identical to upstream (md5 match) | - | |
| 9a4757be0 | 2026-09-16 | #405 | Improve glass popup text and nest model picker options | ui-desktop | PORTED | Roboco 3d977bb82; nested `setting_hover`/ModelSetting machinery pickers.rs:876; 11/12 fns present (the 1 missing test name is also gone upstream; Roboco ported its final form) | - | |
| 601ceb058 | 2026-09-16 | - | Release v0.2.69 | other | SKIP:version-bump | | - | |
| 76dc6a465 | 2026-09-16 | #410 | Fix nested model menu toggle and parent clicks | ui-desktop | PORTED | Roboco 357d43ce0; `nested_model_menu_mouse_paths_work_on_both_sides` test present | - | |
| ff6beb367 | 2026-09-16 | - | Release v0.2.70 | other | SKIP:version-bump | | - | |
| e0c1e9361 | 2026-09-16 | #413 | Refine composer layout, transitions, and glass popup text | ui-desktop | PORTED | Roboco 9b5a70b62 (desktop) + ed3f8a23d/c0caaf341 (web); `CLUSTER_Y_DELTA` composer.rs:399, `cancel_setting_hover` pickers.rs:5110 | - | `model_handoff`/`submenu_corridor` fn names are missing, but upstream/main dropped them too (later refactor) |
| 801b1ef18 | 2026-09-16 | - | Release v0.2.71 | other | SKIP:version-bump | | - | |
| 12b032875 | 2026-09-16 | #419 | Refine glass dialogs, scroll buttons, and submenu hover dismissal | ui-desktop | PORTED | Roboco 694143b45 (dialogs, jump pill, scrim); corridor hover-dismissal landed with the e0c1e936 line: test text "Leaving the safe corridor closes the child" at pickers.rs:8177, HoverIntent pickers.rs:876 | - | |
| 55b95b1ca | 2026-09-16 | - | Release v0.2.72 | other | SKIP:version-bump | | - | |
| 62c523291 | 2026-09-16 | - | Fix command palette session row arguments | ui-desktop | PORTED | Folded into Roboco d9dfb0bc8 (palette built with the final render_chat_row argument list) | - | |
| e42a7b61b | 2026-09-16 | - | Add Devin and OpenCode to landing page agent lineup | other | SKIP:landing | apps/landing only | - | |
| 92e80c855 | 2026-09-16 | - | Restore uncluttered six-agent landing diagram | other | SKIP:landing | apps/landing only | - | |
| 8ee7a6222 | 2026-09-17 | #412 | Stop Enter from interrupting a live run | ui-desktop | PORTED | Roboco b1a2819a1; composer.rs:8720 ("Enter never stops a run"), test `enter_on_empty_composer_during_a_live_run_never_interrupts` composer.rs:12851 | - | |
| c4d63fa8c | 2026-09-17 | (#340) | Add theme-aware indentation guides to file trees | files/editor | PORTED | Roboco 92cfa7cf8; `with_indent_guides` present in files/ | - | |
| cc754b46a | 2026-09-17 | (#340) | Make file tree titlebar search adapt to narrow panels | files/editor | SUPERSEDED | Ported as Roboco 1ac88d013, then replaced by upstream 7083e180 ("explorer secondary header"), ported as Roboco c03d9d1cb; `render_explorer_header` files/mod.rs:1226 | - | the four titlebar-search fns are gone both here and upstream |
| 6a476117c | 2026-09-17 | - | test(ui): reproduce activity replay after batched transcript catch-up | ui-desktop | PORTED | Roboco d63ee9789; test `tool_group_revisit_skips_batched_history_but_animates_live_arrivals` present | - | |
| b25dd404c | 2026-09-17 | (#340) | Color file tree entries with shared remote-safe Git status | files/editor, engine, proto | PORTED | Roboco c4113076d (+ web db874e7ce); crates/engine/src/diff_sync/git_status.rs, crates/ui/src/files/git_status.rs; 33/34 fns present | - | Relay-forwarding test `git_status_is_computed_on_the_checkout_host_through_the_relay` dropped on purpose (no relay, ADR 0004) |
| 787f58311 | 2026-09-17 | #349 | Add Antigravity ACP, improve context UI, and persist window geometry | harness, ui-desktop, proto, android-parity | PORTED | Roboco cf2ad5f8e; acp/antigravity_paths.rs, archive_install.rs, antigravity-mark.svg, window geometry lib.rs:307-343, `token_counts_are_grouped_by_thousands` context_usage.rs:215; Android: BrandMarks.kt:56 ANTIGRAVITY + AccountsFormat.kt:59 | - | Its opt-in/sign-in fns are missing, but upstream/main removed them too (#617 rework; Roboco wave-4 ticket 09 restored the later shape) |
| be01ed7a6 | 2026-09-17 | - | fix(transcript): skip replay animations for historical activity | engine, ui-desktop, proto | PORTED | Roboco 8a4f5bfd9; `replay_baseline` in engine rpc.rs/doc_host.rs, TranscriptBaseline in wiregen | - | chat2/chat_client/edge sink hunks are cloud and were left out on purpose |
| faac7432f | 2026-09-17 | #433 | Add configurable shortcut to open model picker | ui-desktop, settings | PORTED | Roboco fb905b234; `OpenModelPicker` action used 13x in crates/ui/src | - | |
| 853872d36 | 2026-09-17 | #352 | Fix token refresh storms and preserve sessions during transient DNS failures | engine | PORTED | Roboco 76d2fe2a3; crates/engine/src/http_error.rs `describe_http_error`:8; Claude usage probe now treats DNS errors as temporary (does not force a token refresh) | - | WorkOS auth.rs refresh sharing, device_room TokenSource and edge parts are cloud and stay out (ADR 0004). 9/29 fns: the rest are cloud-only |
| 55c8d9dfc | 2026-09-17 | - | fix(transcript): track replay incrementally and preserve live animations | engine, ui-desktop | PORTED | Roboco f5fcb1438; crates/engine/src/transcript_history.rs | - | chat2 `import_row` sink is cloud |
| b4dd24d73 | 2026-09-17 | #407 | Polish command palette and add quick theme action | ui-desktop | PORTED | Roboco 50052a47d; command_palette.rs:57-58 "Switch to light/dark theme" | - | |
| cbf2ad844 | 2026-09-17 | - | feat(ui): add configurable conversation width in appearance settings | ui-desktop, settings | PORTED | Roboco 2e7ea2e56; `conversation_width` shell.rs:409, test appearance.rs:4441 | - | |
| 75ba2764f | 2026-09-17 | - | fix(transcript): retain opening history across replay updates | engine, ui-desktop | PORTED | Roboco 354b59eb6 | - | |
| 01b705fe1 | 2026-09-18 | #415 | Fade overflowing sidebar labels | ui-desktop | PORTED | Roboco 02c240172; crates/ui/src/edge_fade.rs label fade, used in shell.rs/spaces.rs; 8/8 fns | - | |
| 14c397494 | 2026-09-17 | - | Release v0.2.73 | other | SKIP:version-bump | | - | |
| d840f2803 | 2026-09-17 | #436 | Fix truncated Cursor catalogs and refresh harness models | harness | PORTED | Roboco ccbf46107; `acp_refreshes_large_catalogs_coalesces_and_recovers` tests/model_refresh.rs:65 | - | The cursor/opencode refresh test names were later reworked upstream and are not on upstream/main either |
| 38a8f013c | 2026-09-17 | - | Add collapsible pinned sidebar section with animated divider | ui-desktop | PORTED | Roboco 2e98ba836; `render_pinned_section` spaces.rs:5647, `pinned_open` | - | `render_pinned_divider` was later removed upstream by adc290e3 |
| b84c6d066 | 2026-09-17 | - | chore: release v0.2.74 | other | SKIP:version-bump | | - | |
| 9abe0167e | 2026-09-17 | #438 | Fix keyboard focus when opening the model selector | ui-desktop | PORTED | Roboco 22608d36d; test `model_shortcut_focuses_mounted_picker_and_routes_navigation` | - | |
| fb3da2333 | 2026-09-18 | - | chore: release v0.2.75 | other | SKIP:version-bump | | - | |
| 6851fc34b | 2026-09-17 | - | Add animated drag transfers between pinned and regular sessions | ui-desktop | PORTED | Roboco 1af2ea135; `sidebar_session_transfer` (82 refs); 20/20 fns | - | |
| 0f1526478 | 2026-09-17 | - | Remove sidebar section drag highlights | ui-desktop | PORTED | Roboco 7c5ee070a | - | |
| adc290e36 | 2026-09-17 | - | Simplify pinned and archived section headers | ui-desktop | PORTED | Roboco 42aff0be6 | - | |
| 7c7b574bb | 2026-09-17 | - | Right-align pinned and archived disclosure chevrons | ui-desktop | PORTED | Roboco 55bb4a4cf; spaces.rs:5881 comment + flex spacer | - | |
| da041aab2 | 2026-09-17 | - | Reduce spacing above archived sessions | ui-desktop | PORTED | Roboco e9a433d88 | - | |
| c87daf244 | 2026-09-18 | - | fix(cursor): recover abandoned runs and stabilize model discovery | harness | PORTED | Roboco 1f10d3841; crates/harness/src/cursor/catalog.rs, cursor/state.rs; 29/29 fns | - | |
| 7f73ad790 | 2026-09-17 | - | fix(ui): align elapsed timer with working indicator text | ui-desktop | PORTED | Roboco e652112cc (1px nudge) | - | |
| 998b51446 | 2026-09-17 | - | fix(ui): format elapsed time in hours and days | ui-desktop | PORTED | Roboco e652112cc; `format_elapsed` transcript.rs:2363, test transcript.rs:17338 | - | |
| 15a31cf7a | 2026-09-18 | - | test(cursor): stress steering bursts and prioritize cancellation | harness | PORTED | Roboco 45c665cd8 (biased interrupt select); `steering_spam_preserves_every_turn_in_order_and_closes_cleanly` tests/cursor.rs:579 | - | The cancel-burst test was reshaped by the native-steer port 4bd1cea9d. It is gone on upstream/main too |
| 53da54171 | 2026-09-18 | - | chore: release v0.2.76 | other | SKIP:version-bump | | - | |
| 75850fad1 | 2026-09-18 | - | Fix blank session revisits and transcript attachment race | engine, ui-desktop | PORTED | Roboco e1b321b4d; docs/regressions/whale-session-revisit.md | - | |
| 776adb6fe | 2026-09-18 | - | Show a bounded local transcript tail before rebuilding whale history | engine, ui-desktop, doc | PORTED | Roboco 657ab6718; `read_opening_tail` crates/doc/src/schema.rs:740, `openingTail` rpc.rs:2094 | - | |
| 57a3a7c1c | 2026-09-18 | - | Verify opening previews and cached revisits never replay tool animations | ui-desktop | PORTED | Roboco f1d6f293e | - | |
| a67871f82 | 2026-09-18 | - | Prevent whale replay from starving desktop networking | engine, updater/install, ci | PORTED | Roboco 6bde29f3d; crates/engine/src/chat_persistence.rs, `http_client_with_timeouts` update/src/lib.rs:217, ui-tests.yml:75 runs roboco-update lib | - | chat2/chat_client/edge cursor-amnesty hunks are cloud |
| ded8dbd6f | 2026-09-18 | - | Prepare whale transcript presentation off the UI thread | ui-desktop | PORTED | Roboco 503b6bc6f | - | |
| 10c9d3e28 | 2026-09-18 | - | chore: release v0.2.77 | other | SKIP:version-bump | | - | |
| acfd17186 | 2026-09-17 | - | Prevent replaying sidebar movement after successful drops | ui-desktop | PORTED | No message reference, but the code is present: spaces.rs:4848-4850 ("Drag previews already animated this move. Establish a fresh layout baseline…" + `sidebar_prev_order.clear()`) | - | Arrived with the sidebar-transfer port line |
| bf14eca44 | 2026-09-18 | - | Reconcile sidebar pins against authoritative registry snapshots | engine, doc | SKIP:cloud | Synced-workspace registry `reconcile_sidebar_pins`, remote pin migration (tests use WorkspaceScope::Synced + AuthState::SignedIn) | - | Roboco keeps pins in UI-local UiSettings per profile (dca52e3bc, d6785eec8). It has no registry authority to reconcile against |
| 647cfbf2c | 2026-09-18 | - | Validate sidebar pin changes consistently before accepting drops | ui-desktop | PORTED | Local slice: `validate_sidebar_pin_update` sidebar_pins.rs:76, `MAX_SIDEBAR_PINS` :20 (folded into Roboco d6785eec8) | - | Proto/registry-side validation is sync-layer (cloud) |
| 4f8df8480 | 2026-09-18 | - | Serialize optimistic pin writes and reconcile acknowledged state | ui-desktop | PORTED | Local slice: `PendingSidebarPins` sidebar_pins.rs:96, queue/finish (Roboco d6785eec8); docs/regressions/sidebar-pin-integrity.md | - | Engine RPC write loop and acknowledgement plumbing are sync-layer and stay out |
| 8f2488cc7 | 2026-09-18 | - | Exercise pin write transport and migration persistence failures | engine, ui-desktop | SKIP:cloud | Tests only, for the RPC pin-write transport and registry migration durability. Roboco has no pin RPC (writes to local settings happen synchronously) | - | |
| dfd02cd7a | 2026-09-18 | - | Clear pin write notices after successful recovery | ui-desktop | PORTED | `set_pin_write_notice`/`clear_pin_write_notice` sidebar_pins.rs:104-113, cleared on Ok :210 | - | |
| 68306a172 | 2026-09-18 | - | Sync sidebar pins with per-item ordering keys | ui-desktop (+ cloud) | PORTED | Roboco d6785eec8: SidebarPinChange intents, move-only reorder (desktop + web) | - | Skipped slices: proto ordering keys, registry rows, edge workerd, iOS RegistryCore (cloud: pin sync across devices) |
| 23bc81834 | 2026-09-18 | - | fix(cursor): preserve SDK failure diagnostics and update to 1.0.31 | harness | PORTED | Roboco 66670c1cb; pin since moved on to `@cursor/sdk@1.0.32` (cursor/mod.rs:60, same as upstream/main) | - | |
| 0682bd632 | 2026-09-18 | - | test(cursor): verify token refresh against aged live SDK sessions | harness | PORTED | Roboco 0b5fe8c86 (fatal-frame warn log, auth-clock fixture, probe mode) | - | |
| 1d00f2960 | 2026-09-18 | - | ci(cursor): automate SDK updates and gate releases on auth compatibility | harness, proto, ci | PARTIAL | Roboco 37426f06b ported `cursor_sdk_version` on EngineInfo/Device (wire + doc rows). MISSING: `.github/workflows/cursor-compatibility.yml`, `cursor-sdk-update.yml`, release.yml gate, `scripts/test-cursor-sdk-auth.mjs`, `scripts/update-cursor-sdk.py`, `scripts/test-update-cursor-sdk.py` | S | Left out by the wave-3 spec policy (`.scratch/upstream-ports-2026-10-wave3/spec.md:113`, "Upstream CI workflows … are not ported"). That is a spec rule, not a user wontfix. The devices.rs SDK row was later removed upstream (e56680ca) |
| 65b5c586e | 2026-09-18 | - | Preserve Cursor user context through steering and startup interruption | harness, engine, doc | PORTED | Roboco 3dbcb011f; 24/24 fns (user receipt, `cursor_unstarted_history`, steer/interrupt supersession) | - | Its +7 lines in cursor-compatibility.yml belong to the 1d00f29 CI slice |
| 1e0c783e5 | 2026-09-18 | - | chore: release v0.2.78 | other | SKIP:version-bump | | - | |
| 78e9e6ae1 | 2026-09-18 | - | feat(ui): add configurable compact sidebar and project icons | ui-desktop, settings | PORTED | Roboco db407106e (+ web 432d412ed); crates/ui/src/shell/project_icon.rs; 19/19 fns | - | ZERON_SIDEBAR_ACCOUNT fixture hunk is cloud |
| deaf2c4ef | 2026-09-18 | - | Refine sidebar accordions and compact row layout | ui-desktop | PORTED | Roboco 8347f9592 | - | |
| e6e69fbbb | 2026-09-18 | - | Reorder sidebar icons and use terminal remote icon | ui-desktop | PORTED | Roboco 52f020b04 | - | Remote icon later became Earth/global (4f9a5fd5, 378a1945) |
| dfd2fc0cc | 2026-09-18 | - | Render archived sessions with shared sidebar data and layout | ui-desktop | PORTED | Roboco 100084232 | - | |
| 6772044a0 | 2026-09-18 | - | Let sidebar titles use empty action space | ui-desktop | PORTED | Folded into Roboco 6407e4eca | - | |
| 1ec74e405 | 2026-09-18 | - | Use frosted project monograms and refine sidebar action icons | ui-desktop | PORTED | Roboco 6407e4eca | - | |
| 82cdfe904 | 2026-09-18 | - | Refine project badge colors and open sidebar options to the right | ui-desktop | PORTED | Roboco d3928d016; `anchored_menu_right` in popover.rs | - | |
| 94dbfc6da | 2026-09-14 | - | Add compact transcript mode: one collapsed work accordion per turn | ui-desktop, settings | PORTED | Roboco af57d9850 (+ web 04b1867c7); `transcript_compact_mode` settings.rs:630 | - | |
| 2f40dfad4 | 2026-09-18 | - | Select project monograms from a fixed curated palette | ui-desktop | PORTED | Roboco 35b1eb08f; `MONOGRAM_PALETTE` in project_icon.rs | - | |
| 4f9a5fd52 | 2026-09-18 | - | Use Earth icon for remote sessions | ui-desktop | PORTED | Folded into Roboco 96beaa935; later moved to the Solar `global.svg` glyph (upstream 378a1945 → Roboco ea6fbf926) | - | |
| 831edf8a6 | 2026-09-18 | - | fix(actions): bound project file reads and reject special files | engine | PORTED | Roboco 505ce8b91; `MAX_PROJECT_FILE_BYTES` project_actions.rs:28, special-file rejection :559-609; 10/10 fns (incl. Windows device test) | - | |
| ffaa3102b | 2026-09-18 | - | Discover monorepo project icons and default to compact sidebar | ui-desktop, settings | PORTED | Roboco 96beaa935 (compact default ON) | - | Monorepo icon discovery was removed again upstream by 378a1945 (Roboco ea6fbf926), so its 5 fns are missing in both places |

## Port candidates (MISSING + PARTIAL), most valuable first

### 1d00f2960 ci(cursor): automate SDK updates and gate releases on auth compatibility
- What it does for users: nothing visible. It is maintainer tooling. A daily job checks npm for a new stable
  `@cursor/sdk`, opens a PR bumping `CURSOR_SDK_PIN`, and runs a credential-free contract test. That test
  instruments the real SDK bundle and checks token expiry, stream invalidation and transient auth recovery.
  A Cursor SDK release that breaks token refresh therefore fails CI before shipping. Value for Roboco: Roboco
  pins the same SDK (1.0.32 in both repos) and bumps it by hand. The contract test would catch an SDK
  auth-behaviour change that broke Roboco's Cursor harness on Windows and Linux. Nothing platform-specific.
- What is missing: `.github/workflows/cursor-compatibility.yml` (ubuntu, node 22, no secrets),
  `.github/workflows/cursor-sdk-update.yml` (scheduled PR bot), the `needs: cursor-compatibility` gate in
  `release.yml`, `scripts/test-cursor-sdk-auth.mjs`, `scripts/update-cursor-sdk.py`,
  `scripts/test-update-cursor-sdk.py`. Roboco already has the code/wire slice (`cursor_sdk_version`).
- Port notes: take the files from upstream/main (the scripts were revised after this commit). Rename
  `.zeron-auth-contract-*` → `.roboco-auth-contract-*`. Upstream's `rust-tests` job calls
  `scripts/ci/test-core.sh` and `.github/actions/linux-ci`, which Roboco may not have, so adapt it to
  Roboco's ui-tests job. The contract test patches minified SDK internals and will need upkeep on every SDK
  bump (that is its purpose). Adding it contradicts the wave-3 spec rule that Roboco's workflow set is
  closed (`.scratch/upstream-ports-2026-10-wave3/spec.md:113`), so it needs a user decision first. No wire
  changes.
- Effort S
