# Part 6 — chunk-06 (`d5c1cdc12` #816 … `4cd159175` #971, 2026-10-06 → 2026-10-22, 72 commits)
Summary: 72 commits — PORTED 35, PARTIAL 8, MISSING 11, DECLINED 1, SUPERSEDED 0, SKIP 17, N/A 0

Audited against `HEAD` = `85be4485a` (feature/fleet-follow). Each PORTED row was checked in code (symbol/test/string
present at HEAD, and the Roboco port commit is an ancestor of HEAD). Stale ticket labels checked against code:
wave-7 08/20/21/22, wave-8 13 and wave-9 04/10/18/19/20 below. GPUI pins at HEAD are still zui `0966d065` /
gpui-component `4764fd00` (`Cargo.toml:91-99`, patch revs `:168-171`). No pin sync has happened.

## Table
| SHA/PR | date | upstream PR | title | area | class | evidence | effort | notes |
|---|---|---|---|---|---|---|---|---|
| d5c1cdc12 | 2026-10-06 | #816 | Pin chip padding to Geist | ui-desktop | PORTED | `crates/ui/src/composer/chip.rs:21` `CHIP_PAD_FAMILY="Geist"`, `chip_label_offset`/`chip_pad_overrides`, tests `composer_chip_padding_is_shaped_in_geist`, `bubble_chip_padding_is_shaped_in_geist`; Roboco `1ed398d68` (#775+#816) | — | — |
| 8e6da9590 | 2026-10-06 | #826 | iOS: no fixture conversation when a session fails to open | android-parity | PORTED | `apps/android/.../shell/AppRouter.kt:6` `OPEN_ERROR_TOAST = "Couldn't open session. Try again."`; `PhoneAppRouter.kt:113,126,182` route null → `showSessionOpenError()` | — | iOS-only files; the behaviour is what Android mirrors |
| 4a5fb8b44 | 2026-10-07 | #819 | "Internal session plumbing and packaging cleanup" (native Codex voice) | harness/engine/ui | MISSING | No `audio`/`orb`/`veil`/`voice-session`/`voice-media` crates, no `codex/realtime*`, no `shell/voice_stage.rs`. MCP chat/side kinds came earlier via `119724896` (#706). ID-based reply correlation is there (skew test `crates/roboco-mcp/src/tools.rs:3420`). The full-id reply instruction is not: Roboco still writes `short(origin_id)` at `tools.rs:1447-1448` | L | Product decision still open ("wave anchor behind a product decision", 2026-10-07 note; wave-6 spec:53). No user decision recorded. Linux LICENSE/NOTICES packaging slice is already in `scripts/package-linux.sh:67-68`. Upstream later replaced the full-id reply with `agent_messages::agent_message` (`f65c5bc60` #1062); port that one instead |
| 9e83ceea2 | 2026-10-06 | — | Bump version to v0.2.105 | other | SKIP:version-bump | Cargo.toml/Cargo.lock only | — | — |
| 970f41fa7 | 2026-10-07 | #835 | Fold dated Claude snapshot IDs into curated rows | harness | PORTED | `crates/harness/src/claude/catalog.rs:323` `snapshot_base`, test `:412`; Roboco `02d6dca7` | — | — |
| 8930cd103 | 2026-10-07 | #834 | Voice helper compat with Codex 0.161 | harness | MISSING | All hunks are in #819's new files (`voice-media`, `codex/realtime.rs`), and none of them exist | S (with #819) | Blocked on #819. Not portable on its own |
| 916cb1ccb | 2026-10-07 | — | Bump version to v0.2.106 | other | SKIP:version-bump | — | — | — |
| 8646bde07 | 2026-10-08 | #818 | Cut startup memory 700→200 MB | engine/ui | PORTED | `coalesce_ops`/`set_local_only` in `crates/doc/src/registry.rs`; `crates/engine/src/fs_watch.rs` `FsWatchHub`; `journal_size_limit`/`wal_autocheckpoint`/VACUUM in `crates/sync/src/store.rs`; `rev_lines` in `run_journal.rs`; `apply_chats -> bool` `state.rs:1152`; `shell_clock_wake` `shell.rs:1631`. Roboco `f6b6fdf2`, `b67b502eb`, … | — | crates/client hunks are cloud |
| e5466c533 | 2026-10-07 | #813 | Windows browser, image previews in tool calls, selectable tool output | browser/ui-desktop/engine | PARTIAL | Engine path-jail + blocking read ported (`fed992a35`). Missing: `browser/windows_webview.rs` (browser dir has only linux/macos), `tool_images.rs`, the tool-images fixture, `render_tool_image_strip`/`open_image_menu` ("Copy path"/"Copy image"), selectable tool-call text (`expanded_tool_call_text_drag_selects`), `OUTPUT_LINE_MAX_CHARS` output cap, and the question answer-card growth (`answer_cap`, absent from `composer.rs:9839`) | L (browser) + M (rest) | Wave-7 ticket 22 is still open. Only the WebView2 host needs the pin sync. Tool images, selectable output, the line cap and the answer card do not touch the pin and can port now |
| 037f4c10d | 2026-10-08 | #820 | Turn snapshots out of the checkout's object DB | engine | PORTED | `crates/engine/src/diff_sync/turn_snapshot.rs` (GIT_OBJECT_DIRECTORY/alternates); Roboco `81a03060` | — | — |
| 91fd851c7 | 2026-10-08 | #748 | Harden Claude/Codex adapters | harness/engine | PORTED | `TurnControl`, `stops_turn_in_place`, `reconfigures_in_place` (`engine/src/sessions.rs`, `harness/src/{acp,claude}/mod.rs`), `command_lifecycle` (`claude/wire.rs`), `clientUserMessageId` (`codex/mod.rs`), `engine/tests/runtime_survival.rs` | — | Seven-slice perf-wave port |
| 565b49c52 | 2026-10-08 | — | Bump version to v0.2.107 | other | SKIP:version-bump | — | — | — |
| e799aecc1 | 2026-10-08 | #777 | Remote sends stranded by mobile suspension / lost host wakes | browser/android-parity | PORTED | Linux capture-fixture fix in `74257f957`. iOS "Sending to host…" parity in `crates/mobile-client/src/session/composer.rs:5-8` and Android `StatusBanner.kt:124-125`, `StatusPill.kt:111` | — | The rest (edge chat-wakes, crates/client, chat2, doc_host_delivery, iOS background task) is SKIP:cloud |
| 0c4835d2b | 2026-10-08 | #647 | Explorer: group finished subagents, running counts | proto/ui-desktop | PARTIAL | Wire `Session.running_subagents` (`proto/src/entities.rs:336`, `view.rs:78`), engine publish, titlebar badge (`running_pill::mark_titlebar_button`, `shell/tabs.rs:402`) are all in `189c3d5a3`. Missing: the "● N" pill on sidebar chat rows. Roboco's `running_pill.rs` has no `running_pill()` fn, and `running_subagents_for` is read only by `session_lanes.rs:221`. Upstream main still has it (`shell.rs:8776`) | S | The files-panel Finished dropdown is dead upstream (deleted by #895), so it is correctly not ported |
| 1074bc540 | 2026-10-08 | #522 | Open tool-call files in files panel / default editor | ui-desktop | PORTED | `ToolFileOpener` in `transcript.rs`; `open_with_system` + "Open in default editor" in `files/preview.rs`; Roboco `90eb9c0ce` | — | — |
| afca583c1 | 2026-10-10 | #484 | Repository-scoped PR board and native inspection | git/ui-desktop | MISSING | No `pull_requests.rs` (only `change_requests.rs`) | L | Wave-7 21 is PARKED (`needs-triage`). No user decision recorded. This commit bumps zui to `361ff20b`, so it needs the pin sync |
| b65b47536 | 2026-10-09 | #855 | Queue polish; drag a chat to mention it | ui-desktop/proto | PORTED | `crates/proto/src/chat_mentions.rs`; Roboco `f199cf67d` | — | — |
| 672cc1444 | 2026-10-10 | — | Searchable popovers, ordered sidebar sections, titlebar sidebar options | ui-desktop/proto | PORTED | `render_sidebar_options_titlebar` `shell.rs:6694`, `section_insert_index` `shell/sidebar_sections.rs:10`; Roboco `f382151fd` | — | Placed-order adapted engine-locally (no orderKey/HLC, by ADR 0004) |
| 05f5f2bff | 2026-10-10 | — | Drop ellipsis from New project | ui-desktop | PORTED | `pickers.rs:3237` "New project"; `settings.rs:1243` | — | Folded into `f382151fd` |
| a408a8140 | 2026-10-10 | — | Sonner toasts, nested Copy menu, picker-style repository menu | ui-desktop | PORTED | `crates/ui/src/toast.rs`; Roboco `64ea2a7e2` | — | The repository-menu hunks live in `pull_requests.rs` and ride #484 |
| 344436efb | 2026-10-10 | — | Bump version to v0.2.108 | other | SKIP:version-bump | — | — | — |
| ae19fe31b | 2026-10-10 | — | Widen sidebar minimum to 242 | ui-desktop | PORTED | `crates/ui/src/settings.rs:37` `SIDEBAR_MIN: f32 = 242.0` | — | — |
| 9e17e4017 | 2026-10-10 | — | Bump version to v0.2.109 | other | SKIP:version-bump | — | — | — |
| d84ef73af | 2026-10-10 | — | Drop focus rings from PR views | ui-desktop | MISSING | Touches only `pull_requests.rs` views, which Roboco does not have | S (with #484) | Rides #484 (wave-7 21) |
| ac4acbc21 | 2026-10-10 | — | Session card behind one toggle | ui-desktop | PORTED | `crates/ui/src/shell/session_info.rs`; Roboco `189c3d5a3` (ticket-07 superset) | — | — |
| dace1a8ce | 2026-10-10 | #677 | Panels: content columns share width, continuous transitions | ui-desktop | MISSING | No `shell/panel_layout.rs`; none of `horizontal_panel_fit`/`HorizontalPanelFit`/`PANEL_RESIZE`/`panel_content_tween`/`files_settled_width` | L | Wave-7 08 still says ready-for-agent and is truly open. The panel-lifecycle suite of `panel_interaction_tests.rs` was held for it |
| 6774a37a0 | 2026-10-10 | — | Titlebar: frame-style card toggle, folder-tree explorer toggle | ui-desktop | PORTED | `icons.rs:285` `corner_card_glyph`; `crates/ui/assets/icons/folder-tree.svg` | — | — |
| 73051ec71 | 2026-10-10 | #863 | Fall back to fetch URL when push URL disabled | git | PORTED | `engine/src/source_control.rs:1248-1276`; Roboco `fb08088f8` | — | — |
| ec28627ce | 2026-10-10 | — | Bump version to v0.2.110 | other | SKIP:version-bump | — | — | — |
| 3487b507f | 2026-10-10 | #856 | Unified workspace search on fff, tabbed cmd+K | engine/ui-desktop/proto | MISSING | No `fff-search` dep, no `engine/src/workspace_search.rs`, no `shell/search_warm.rs`/`thread_search.rs`. `home_index.rs:11-17` waits on this port with `allow(dead_code)` | L | Wave-7 20 is open. The commit itself does not touch the gpui pin. It is authored on #484's `361ff20b` tree, so watch for API drift |
| 40091d469 | 2026-10-10 | — | Bump version to v0.2.111 | other | SKIP:version-bump | — | — | — |
| 057522d23 | 2026-10-11 | #870 | Spanish README | docs | SKIP:docs-only | README.es + switchers | — | — |
| 488c03582 | 2026-10-11 | #872 | Mouse back/forward walk route history | ui-desktop | PORTED | `shell.rs:12321` `MouseButton::Navigate(Back)`; `browser/view.rs:656-657`; Roboco `3daee261f` | — | — |
| 54954e4d2 | 2026-10-11 | — | Quieter Providers page, stable rows | settings | PORTED | `engine/src/harness_updates.rs:3474` `toggling_one_agent_leaves_the_others_settled`; Roboco `a3aa1d696` | — | — |
| edb23db4b | 2026-10-11 | #875 | iOS: Group by Project setting | android-parity | PORTED | `apps/android/.../core/Prefs.kt:44` `GROUP_BY_PROJECT`; "Group by Project" switch rows | — | — |
| 1bc74d5da | 2026-10-11 | #876 | Projectless chats get own folder; home never indexed | engine/ui-desktop/proto | PARTIAL | Engine, proto and home_index ported (`b6ab205e5`, `proto/src/projectless.rs`, `engine/src/home_index.rs`). Missing UI slice: projectless labels still read `~`/"Home" (`shell/spaces.rs:2538,4999`, `command_palette.rs:300`, `project_icon.rs:295,373`). Files header shows the raw cwd (`files/mod.rs:403`), which is now `~/.roboco/scratch/<date>-<id>`. Docked composer still uses `.left(-session_reserve/2)` (`shell.rs:10041,10202`), not `shift_x`. MCP `create_chat` does not name the scratch cwd. `workspace_search.rs` hunks are held for #856 | S | The scratch path leaks into the UI on every new projectless chat |
| 50e33711a | 2026-10-12 | #843 | MCP bridge on two worker threads | engine | PORTED | `apps/roboco/src/main.rs:265` `.worker_threads(2)`; Roboco `3f96c5e62` | — | — |
| 9d943019f | 2026-10-12 | #842 | Opt out of macOS one-time-code AutoFill | other | SKIP:macos-only | `dist/macos/Info*.plist` only | — | Roboco carries the plists, but there is no macOS build |
| e54c3a861 | 2026-10-12 | #812 | Dropped files from any folder attach on macOS | other | SKIP:macos-only | entitlements + macOS scripts | — | — |
| b40ac5ee3 | 2026-10-11 | — | Bump version to v0.2.112 | other | SKIP:version-bump | — | — | — |
| c8c717862 | 2026-10-11 | #867 | Session card beside roomy transcript, floats over tight | ui-desktop | PORTED | `settings.rs:1031` `session_info_hidden`; `transcript.set_right_reserve` `shell.rs:9886` | — | In `189c3d5a3` |
| 09beace00 | 2026-10-12 | #873 | Usage reset times on hover | ui-desktop | PORTED | `settings/accounts.rs:120` `usage_resets`, used at `account_usage.rs:697`; Roboco `c32447cde` | — | — |
| 053ce2eac | 2026-10-12 | #573 | Persist embedded browser sessions across tabs/restarts | browser | MISSING | No `browser/profile.rs`, no `BrowserProfile`/`for_profile`/`persistent_profiles_supported`, no `linux/startup_tests.rs`, no `browser-fixture/storage.rs` | M | Linux helper.c/mod.rs slice has no pin dependency. Windows persistence would follow ticket 22 |
| 66524017f | 2026-10-13 | #866 | Open PRs in side panes | git/ui-desktop | MISSING | No `shell/pull_request_pane.rs`/`chat_pull_requests.rs` | L (with #484) | Rides parked #484 |
| 2d40f8df6 | 2026-10-13 | #821 | Overscroll bounce | ui-desktop | PORTED | `crates/ui/src/overscroll.rs:55`; Roboco `106f36a38` | — | Follow-up fix inside #966 is missing (see #966 row) |
| 2c2963aae | 2026-10-13 | #774 | Multi-select in file tree | files/editor | PORTED | `tree_action_paths` (`files/context_menu.rs`, `tree.rs`), `destination_paths`/`tree_drag_items` (`files/drag.rs`); Roboco `f08bdd585` | — | — |
| 01a399db1 | 2026-10-13 | #530 | Per-device custom worktree locations | engine/settings | PARTIAL | Engine/proto ported in `c40b42f32`: `worktree_settings.rs`, `validate_windows_worktree_path`, GET/SET RPCs `rpc/src/lib.rs:193-194`, windows.yml step. Missing: the UI, i.e. upstream `settings/worktrees.rs` (+638), `popover/contained.rs`, `widgets.rs`. No UI calls the RPCs, so users can only set `ROBOCO_WORKTREES_DIR` | M | Windows-relevant: the engine already handles the long-path two-step add. The commit message says "no roboco UI consumes these RPCs yet" |
| 75b72602a | 2026-10-13 | #725 | Harness icons in idle side chat/subagent tabs | ui-desktop | PORTED | Roboco `63495938b` | — | — |
| 81e04e1c5 | 2026-10-15 | #901 | Sidebar toggle for Archived | ui-desktop | PORTED | `settings.rs:893` `sidebar_show_archived` | — | — |
| 2a209cc6c | 2026-10-15 | #890 | Cmd+K Recent Files flicker | engine | PORTED | `engine/src/repos.rs` `workspace_checkout_accepts_every_linked_worktree`; Roboco `783027f5b` | — | — |
| 4141c7619 | 2026-10-15 | #861 | Delete every archived session at once | settings | PORTED | `settings/archived.rs` `request_delete_all`/`render_delete_all_dialog`; Roboco `3e34ed018` | — | — |
| 8a0fded89 | 2026-10-14 | #900 | Native Luau visuals rendered by GPUI | ui/mcp/mobile-core | MISSING | No `crates/visual*`, no `ui/src/visuals.rs` | L | Parked: "#900 stays parked" (2026-10-25 note:61; wave-9 spec:37). Not declined. Also carries an iPhone/mobile-core slice |
| 6ac885999 | 2026-10-14 | — | Bump version to v0.2.113 | other | SKIP:version-bump | — | — | — |
| 9609f994a | 2026-10-15 | #833 | OpenCode v2: answer question-tool forms | harness | PORTED | `opencode/mod.rs` `v2_question_form_payload`, `form_answer_body`; Roboco `7789a2f67` | — | — |
| 3b2f78027 | 2026-10-15 | #920 | Cursor model defaults for plus subscriptions | harness | PORTED | `cursor/mod.rs` `fill_catalog_defaults`/`catalog_for_defaults`; Roboco `d45a46263` | — | macOS dev-plist/script hunks are skipped |
| 26c8e8404 | 2026-10-17 | #909 | Reorder sidebar sections by dragging header | ui-desktop/android-parity | PARTIAL | Desktop folded into `f382151fd`. Missing iPhone parity: long-press dragging a session between Pinned / a section / Recent (`SessionListController` drag+drop, FFI `pin_session_at`/`place_session`). Android has in-Pinned reorder only (`sessions/PinReorder.kt`), and `crates/mobile` has `move_pin`/`assign_section` but no placement | M | `f382151fd` says it held the mobile FFI "for a mobile follow-up". No ticket exists for it |
| 840fda748 | 2026-10-17 | #903 | Session hover card beside each sidebar row | ui-desktop | MISSING | No `shell/session_hover_card.rs` | M | Wave-8 13 "gated" on wave-7 07. 07 has landed (`189c3d5a3`), so the gate is cleared |
| 591824881 | 2026-10-17 | #895 | Session card: side chats and subagents | ui-desktop | PORTED | `shell/session_lanes.rs` (+tests), `files/sections.rs` deleted; Roboco `189c3d5a3` | — | — |
| c02739da3 | 2026-10-17 | #912 | LaTeX math with zatex | ui/markdown | DECLINED | `.scratch/upstream-ports-2026-10-wave8/spec.md:32` "#912 zatex DECLINED (user, 2026-10-22)"; drift 2026-10-22:258 | — | — |
| 2e8f02ec2 | 2026-10-18 | — | Bump version to v0.2.114 | other | SKIP:version-bump | — | — | — |
| c526d6f7c | 2026-10-18 | #942 | iOS: adapt to iPhone Duo (fold/unfold, uneven safe areas) | android-parity | PARTIAL | Live fold switch with no fade: `shell/RootKind.kt:47-52` `chooseRoot` re-evaluated on width. Not found: laying the transcript out between uneven side insets (no horizontal-inset handling in `transcript/`). Composer-level bullet is refined by #1014, tracked in wave-9 21 | S | Low value: Android split is at 840 dp, so the 280 pt narrow-sidebar rule rarely applies |
| 911ec56e4 | 2026-10-19 | #953 | ACP effort levels lowest→highest | harness | PORTED | `acp/mod.rs` `grok_thought_levels_order_from_lowest_to_highest`; Roboco `c2d0db230` | — | — |
| cc7da5c2a | 2026-10-22 | #966 | Zeron Cloud, provider switching, phone model picker | engine/android-parity/ui | PARTIAL | Between-turns provider switch ported in `7ad2288ad`; desktop picker switch already existed (`pickers.rs:2373`). Missing: (a) the phone tabbed model picker; Android has no ModelPicker (wave-9 20 open). (b) **Not ticketed:** OSC 7501 terminal program status (`engine/src/terminals/program_status.rs` +513, `proto/program_status.rs`, `Session.terminal_activity`); Roboco has no `ProgramStatus`. (c) **Not ticketed:** engine-posted desktop notifications (`crates/notify`, `engine/src/desktop_notify.rs` +744, `SetViewportFocus`); Roboco has no `desktop_notify`. (d) **Not ticketed:** overscroll stuck-band fix (`TOUCH_TIMEOUT`, stretch cap + 4 tests); absent from `overscroll.rs` | M each | Cloud (vault, billing, machines, onboarding, crates/client, `settings/cloud.rs`/`billing.rs`) is SKIP:cloud. Also bumps the GPUI pins (zui `0dc69aa25`). The 2026-10-25 note called everything except the switch "cloud", which missed (b)–(d) |
| b0d47b2de | 2026-10-22 | — | Bump version to v0.2.115 | other | SKIP:version-bump | — | — | — |
| acd7c9ba7 | 2026-10-22 | — | RPC: fragment large messages | proto/wire | PORTED | Roboco `2f51b449a` (512 MiB cap, accept config, close_reason, test `websocket_carries_messages_past_the_default_frame_cap`) | — | The ui/state.rs banner rewording maps to the registry's `last_error`, by design |
| 69b67d338 | 2026-10-22 | — | Bump version to v0.2.116 | other | SKIP:version-bump | — | — | — |
| f30a8641b | 2026-10-22 | #967 | Log in from error cards, one-row iOS bottom bar, session delete | ui-desktop/proto/android-parity | PARTIAL | Desktop, proto and mobile-core ported in `5a7f0acf4`: `proto/src/run_errors.rs`, `start_first_login` `settings/accounts.rs:896`, mock `/error`. Android card rendering landed in PR #34 (wave-9 18 comment). Missing on Android: starting the first login on arrival, session Delete (no delete in `sessions/`), title + "project @ device" in the ⋯ menu header, Limited-banner reconciliation, card golden, and the one-row bottom bar (`MainTabShell.kt`/`GlassTabBar.kt` still in use) | M + M | Wave-9 18 and 19 are open |
| d74456364 | 2026-10-22 | — | Bump version to v0.2.117 | other | SKIP:version-bump | — | — | — |
| d6849cee9 | 2026-10-22 | #969 | Repair Codex installs; engine on wake; group Cloud projects | harness | PORTED | `crates/harness/src/codex/npm_repair.rs`; Roboco `6e4836d7f` | — | Engine-on-wake and Cloud project grouping are SKIP:cloud |
| f725384bd | 2026-10-22 | #970 | Claude logins after update; app starts engine; credentials fallback | engine/updater | PORTED | Roboco `fc016cf4e`: Keychain→`~/.claude/.credentials.json` fallback, `MANAGED_RESTART_READY` gate | — | The stop-don't-orphan half has no surface in Roboco: the engine is embedded, and restarts go through systemd/launchctl. Wave-9 04 still reads ready-for-agent but is effectively done (its Comments say so) |
| feed9105b | 2026-10-22 | — | Bump version to v0.2.118 | other | SKIP:version-bump | — | — | — |
| 4cd159175 | 2026-10-22 | #971 | iOS: bottom bar's shell has no tab bar to come back | android-parity | MISSING | Android still has the tab shell (`shell/MainTabShell.kt`, `GlassTabBar.kt`) | S (rides wave-9 19) | Only meaningful with #967's bottom bar |

## Port candidates (MISSING + PARTIAL), most valuable first

### e5466c533 (#813) Windows browser, tool-call image previews, selectable tool output
- **For users:** on Windows, the browser tab renders pages inside the app (WebView2). Expanded tool calls show the images they touched. Right-click on a transcript image gives Copy path / Copy image. Paths, commands and output in tool calls become drag-selectable and copyable. Very long output lines are capped. A multi-line answer to an agent's question grows the card instead of spilling below it. Roboco is Windows-first and today has no in-app browser on Windows.
- **Missing:** `crates/ui/src/browser/windows_webview.rs` (+1360) and its deps (`webview2-com 0.38`, `windows 0.61` features, `raw-window-handle`), `crates/ui/src/tool_images.rs` (+902), `examples/tool-images-fixture.rs`, the transcript hunks (`render_tool_image_strip`, `open_image_menu`/`copy_image`, selectable tool text, `OUTPUT_LINE_MAX_CHARS`), `image_media::raster_limits` plumbing, and the composer `answer_cap` block in the question panel (`crates/ui/src/composer.rs` near `:9839`). Also the `docs/reference/windows-development.md` WebView2 notes.
- **Dependencies:** only the WebView2 host needs the pin sync (zui#15 `dce5c1f` overlay plane and later: wave-7 19 / wave-9 11; target now zui `0dc69aa25`). The tool-image, selectable-output, line-cap and answer-card slices do not, and can port now as their own ticket. No wire change.
- **Effort:** M for the non-browser slice, L for WebView2 (plus Windows host verification).

### 01a399db1 (#530) Settings → Worktrees page
- **For users:** choose where new worktrees go from Settings. Today Roboco honours it only through the `ROBOCO_WORKTREES_DIR` env var. This matters on Windows because the default root can hit the 260-char launch limit, which the engine now validates.
- **Missing:** upstream `crates/ui/src/settings/worktrees.rs` (+638), `popover/contained.rs` (+57), `settings/widgets.rs`, the `settings.rs`/`shortcuts.rs` registration. The engine RPCs `GetWorktreeSettings`/`SetWorktreeSettings` (`crates/rpc/src/lib.rs:193-194`) and the wire types (`WorktreeSettings*.ts`) already exist, so this is UI-only. Use one engine, not a per-device picker.
- **Dependencies:** none, and no wire change.
- **Effort:** M.

### 1bc74d5da (#876) Projectless UI slice
- **For users:** new projectless chats now run in `~/.roboco/scratch/<date>-<id>`. The desktop UI still labels them by raw cwd: the files header reads "Files in ~/.roboco/scratch/2026-…", and sidebar/palette rows say `~`/"Home". Upstream says "No project", or names only the device.
- **Missing:** the `files/mod.rs:403` header rule (`is_projectless_cwd`), the `spaces.rs:2538,4999`, `command_palette.rs:300` and `project_icon.rs:295,373` labels, the `state.rs` +9 lines, the composer naming the scratch cwd on first send (Roboco relies on the doc_host redirect instead, which is fine but drifts), the MCP `create_chat` scratch cwd, and the `DockedComposer::shift_x` fix. Without that fix, a docked terminal drops the composer's session-card centering (`shell.rs:10041,10202` still use `.left(...)`).
- **Dependencies:** none. The `workspace_search.rs` hunks still wait on #856.
- **Effort:** S.

### 0c4835d2b (#647) Sidebar "● N" running-subagents pill
- **For users:** a sidebar row shows how many subagents are running under that chat. Without it you have to open the chat to see.
- **Missing:** `running_pill::running_pill()` (canvas pill) and the sidebar-row wiring: upstream `shell.rs` `subagent_pill` (it takes the timestamp's place when compact and leads the status otherwise). The data path (`running_subagents_for`) already exists.
- **Dependencies:** none, and no wire change.
- **Effort:** S.

### cc7da5c2a (#966) Non-cloud slices nobody ticketed, plus the phone model picker
- **(a) Android tabbed model picker** (iPhone `ModelPickerViewController` +847) gives Android parity with the iPhone: wave-9 20, open. M.
- **(b) OSC 7501 terminal program status:** programs inside Roboco terminals (agent TUIs) report idle/working/done/blocked/error and permission/question/auth input. The engine tracks this beside the PTY (`engine/src/terminals/program_status.rs`, `SUPPORT_REPLY`), and `Session.terminal_activity` feeds the sidebar and MCP status. Wire change (proto `program_status.rs`, `Session`), so wiregen must run. M.
- **(c) Engine-posted desktop notifications** (`crates/notify` +531, `engine/src/desktop_notify.rs` +744, `SetViewportFocus`): banners keep coming with the app window closed, and chime and banner share baselines. First check whether Roboco keeps an engine alive with no window (embedded engine vs headless daemon). M.
- **(d) Overscroll stuck-band fix:** a touch that never sends `Ended` is let go after `TOUCH_TIMEOUT`, and the stretch is capped so scrolling back always unwinds (4 tests). Rides on our #821 port. S.
- Everything else (vault, Stripe, Cloud machines, onboarding, `crates/client`, Cloud settings) is SKIP:cloud. The commit also moves the GPUI pins (zui `0dc69aa25`, gpui-component `6ffe5e575`).

### f30a8641b + 4cd159175 (#967/#971) Android parity: error cards, session delete, one-row bottom bar
- **For users (Android = iPhone 1:1):** "Signed out" / "Limit reached" cards whose Log in starts the provider's first login. Delete from the Sessions long-press and ⋯ menus with confirmation, and Archive no longer red. The title and "project @ device" sit in the ⋯ menu header. The tab bar is replaced by one toolbar row (Settings, Search, New session).
- **Missing:** wave-9 18 open items (auto-start login on arrival, Delete, menu header, Limited-banner reconciliation, golden) and wave-9 19 (`MainTabShell.kt`/`GlassTabBar.kt` replacement, golden re-capture). The engine-side delete already exists on desktop.
- **Effort:** M (18) + M/L (19, goldens).

### 840fda748 (#903) Session hover card on sidebar rows
- **For users:** hovering a sidebar chat shows a card with the session's project, device, branch, side chats and subagents, without opening the chat.
- **Missing:** `crates/ui/src/shell/session_hover_card.rs` (+729), `shell.rs` +126, `spaces.rs` +36, `change_requests.rs`. Wave-8 13's gate (ticket 07) has landed (`189c3d5a3`), so the ticket can start now.
- **Effort:** M.

### dace1a8ce (#677) Panels share width, continuous transitions
- **For users:** files, transcript and side panes share the window width and animate continuously when opened, closed or resized. Today each clamps on its own.
- **Missing:** `shell/panel_layout.rs` (+651), the `files_panel.rs`/`tabs.rs`/`composer_dock*`/`motion.rs` (`PANEL_RESIZE`) hunks, and the panel-lifecycle half of `panel_interaction_tests.rs`. Wave-7 08 is open.
- **Risk:** large `shell.rs` (+538) surface, already diverged by the session-card port.
- **Effort:** L.

### 3487b507f (#856) Unified workspace search on fff + tabbed cmd+K
- **For users:** one tabbed palette for commands, thread search, and fff file/content search across the workspace.
- **Missing:** everything. Add the `fff-search = "=0.11.0"` dependency, then port `engine/src/workspace_search.rs` (+865), the `repos.rs`/`workspace_files.rs` rework, proto entities (+106, a wire change), `command_palette.rs` (+2401), `search_warm.rs`, `thread_search.rs` and `spaces.rs` (+1046). Unifying with `home_index.rs` is pre-planned there.
- **Dependencies:** the commit does not move the pin, but it was authored on the `361ff20b` tree, so check gpui APIs. Wave-7 20 is open.
- **Effort:** L.

### 053ce2eac (#573) Persistent embedded browser profiles
- **For users:** browser logins and cookies survive tab closes and app restarts.
- **Missing:** `browser/profile.rs`, `browser/mod.rs` +109, linux `helper.c`/`mod.rs` persistence with `startup_tests.rs`, `shell.rs` +139, the storage fixture and `scripts/test-browser-storage.py`, plus the ui-tests.yml step.
- **Dependencies:** the Linux slice is independent of the pin. Windows comes with #813's WebView2 port.
- **Effort:** M.

### 26c8e8404 (#909) Android: drag a session between sections
- **For users (iPhone parity):** long-press a session row and drop it into Pinned, a custom section, or Recent, at a position.
- **Missing:** FFI `pin_session_at`/`place_session` in `crates/mobile/src/client_ffi` (Roboco has `move_pin` and `assign_section`), and Android drag/drop across the list sections. Today `sessions/PinReorder.kt` reorders within Pinned only. No ticket exists for this.
- **Effort:** M.

### c526d6f7c (#942) Android: uneven side insets
- **For users:** in landscape or on cutout devices, transcript text stays clear of an uneven left or right inset.
- **Missing:** horizontal safe-area layout for the transcript. Live fold switching is already present. The composer-level bullet is covered by #1014 (wave-9 21).
- **Effort:** S, low priority.

### afca583c1 + 66524017f + d84ef73af (#484/#866) PR board, PR side panes, focus-ring polish
- **For users:** a repository-scoped pull-request board with native inspection, PR panes beside the chat, and the picker-style repository menu from `a408a814`.
- **Missing:** all of it, roughly 23k lines.
- **Dependencies:** a product decision (ADR 0003; wave-7 21 parked with no decision recorded) and the pin sync (#484 itself bumps zui to `361ff20b`).
- **Effort:** L.

### 8a0fded89 (#900) Native Luau visuals
- Agent-authored interactive visuals: `crates/visual`, `crates/visual-gpu`, `ui/src/visuals.rs`, the MCP tool, and the mobile-core `visual/*` slice for iPhone and Android.
- Parked by decision (stays parked per 2026-10-25). Not declined.
- **Effort:** L.

### 4a5fb8b44 + 8930cd103 (#819/#834) Native Codex voice
- **For users:** subscription-backed realtime voice through the Codex helper, with an orb stage.
- **Missing:** everything voice-related. Waiting on a product decision; none recorded.
- The only small non-voice leftover is the MCP reply line carrying the full origin chat id (Roboco still uses `short(origin_id)`, `crates/roboco-mcp/src/tools.rs:1447-1448`). Upstream since reworked it into `agent_messages::agent_message` (`f65c5bc60` #1062), so port #1062 rather than this hunk.
- **Effort:** L (voice); S (reply id via #1062).

## Stale-label notes (verified against code)
- wave-7 08 (#677), 20 (#856) and 22 (#813 UI half): still open in code. Labels are correct.
- wave-7 21 (#484): still parked (`needs-triage`), with no decision in the repo.
- wave-8 13 (#903): label says "gated", but the gate (wave-7 07) landed in `189c3d5a3`, so it is really ready-for-agent.
- wave-9 04 (#970): label says ready-for-agent, but it is effectively done (`fc016cf4e`). The remainder is N/A by architecture, per the ticket's own Comments.
- wave-9 10 (#966 switch): done in `7ad2288ad`, though the label says ready-for-human.
- wave-9 18 (#967 Android): partly done (PR #34 rendering). 19 and 20 are open.
- Missing from every ticket: #909's Android cross-section drag, the #530 Settings UI, the #876 UI slice, the #647 sidebar pill, and #966's (b), (c) and (d) slices.
