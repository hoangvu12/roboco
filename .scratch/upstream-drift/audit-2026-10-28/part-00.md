# Part 00 — chunk-00 (fd42e2ab3 .. 74558a2ca, 71 upstream commits, 2026-08-14 .. 2026-09-16)
Summary: 71 commits — PORTED 56, PARTIAL 2, MISSING 0, DECLINED 0, SUPERSEDED 8, SKIP 5, N/A 0

Method: every SHA was looked up in `roboco-log.txt` (most have a Roboco port commit that names the
upstream SHA). Each port was then checked in HEAD (`85be4485a`) with `git grep` for one or more
distinctive identifiers from the upstream diff (test names, consts, fns), with renames applied.
Tests that HEAD lacks were checked against `upstream/main` too. Where upstream itself dropped
them later, the gap doesn't count. Upstream PR numbers come from `gh api commits/<sha>/pulls`.
Upstream PR #313 is hoangvu12's own windows-native-support PR, extended by wing-anara.

## Table
| SHA/PR | date | upstream PR | title | area | class | evidence | effort | notes |
|---|---|---|---|---|---|---|---|---|
| fd42e2ab3 | 2026-08-14 | #90 | ui: add local reorderable pinned sessions | ui-desktop | PORTED | Roboco ef3df4523; `pinned_drop_index_quantizes_clamps_and_rejects_outside` crates/ui/src/shell/spaces.rs:575 | - | web parity also landed |
| 1db00587d | 2026-08-14 | #90 | ui: constrain pinned session dragging | ui-desktop | PORTED | Roboco df122492d; test spaces.rs:620 | - | Roboco 1af2ea135 later replaced the clamp with animated pinned/regular transfers (Roboco-only enhancement) |
| 3cad1c254 | 2026-08-15 | #90 | Preserve sidebar pins across workspace profiles | ui-desktop | PORTED | Roboco dca52e3bc; `local_synced_local_switch_restores_each_profiles_pins` crates/ui/src/settings.rs:3176; `sidebar_pin_profile_key` also in crates/mobile-client/src/workspace/sidebar.rs:21 | - | account-switch (auth) slice irrelevant (no auth) |
| 802358d91 | 2026-08-18 | #158 | feat(actions): add project action models and host-local storage | engine | PORTED | Roboco f2f23252c; crates/engine/src/project_actions.rs:737, :1073 | - | |
| e314de505 | 2026-08-18 | #158 | feat(actions): expose device-routed action management | engine / proto | PORTED | Roboco 85fafbf77; `local_project_action_space` crates/engine/src/rpc.rs:933; LIST_PROJECT_ACTIONS | - | device routing adapted to engine-local |
| ad95d98b8 | 2026-08-18 | #158 | feat(actions): launch actions in managed terminal sessions | engine / terminal | PORTED | Roboco 72b8eb487; RUN_PROJECT_ACTION rpc.rs:3344; test crates/engine/tests/m5_repos_diffs_terminals.rs:1831 | - | |
| 288d026f8 | 2026-08-18 | #158 | feat(ui): add the project actions control and editor | ui-desktop | PORTED | Roboco 0866174bd (+ web 77e0725de); crates/ui/src/shell/actions_ui.rs, crates/ui/src/project_actions.rs:369 | - | |
| c679de97f | 2026-08-18 | #158 | feat(actions): run setup actions for new worktrees | engine | PORTED | Roboco dc22718bf + ba1c73270 (durable handoff from upstream merge 6706facb); `launch_project_setup_action` crates/engine/src/doc_host.rs:24; proto test entities.rs:1751 | - | |
| 578df14ba | 2026-08-18 | #158 | test(actions): harden remote execution and version skew | engine (tests) | PORTED | Roboco 5201e6d0b | - | |
| 2d2830801 | 2026-08-18 | #158 | fix(actions): open action terminals in bottom drawer | ui-desktop / terminal | PORTED | Roboco ce33b4bd8; `reserve_tab_for_chat` actions_ui.rs:46 | - | |
| b14840158 | 2026-08-18 | #158 | fix(actions): compact control based on titlebar space | ui-desktop | PORTED | Roboco 914765cae; `ACTION_LABEL_MIN_TITLEBAR_WIDTH` project_actions.rs:242 | - | |
| 052b4b9af | 2026-08-18 | #158 | fix(actions): align control to titlebar right | ui-desktop | PORTED | Roboco 83c341b6e | - | one-line |
| 669d45bde | 2026-08-18 | #158 | style(actions): reduce titlebar control size | ui-desktop | PORTED | Roboco 69831efd0 | - | |
| f4365134d | 2026-08-18 | #158 | fix(actions): keep unavailable controls retryable | ui-desktop | PORTED | Roboco 2ae2a0d88; `visible_snapshot` project_actions.rs:133, test :433 | - | |
| a8aa4967e | 2026-08-18 | #158 | fix(actions): ignore stale mutation responses | ui-desktop | PORTED | Roboco 396d5ef39; `is_current_mutation` project_actions.rs:118, test :455 | - | |
| 751a210b6 | 2026-09-07 | #90 | Add synchronized sidebar preferences to registry | engine / proto | SUPERSEDED | Roboco c548e7bf0: engine-local `SidebarStateSnapshot` + `SetSidebarPins`/`WatchSidebarState` (crates/proto/src/entities.rs:1334, crates/engine/src/sidebar_state.rs, rpc.rs:3319/3341) | - | Roboco solved pin sharing per engine (desktop+phone+web mirror live). The cross-desktop registry/sync slice would be SKIP:cloud. Port commits recorded "deliberately NOT ported" |
| ed04b43a9 | 2026-09-07 | #90 | Sync pinned sidebar sessions across desktops | ui-desktop | SUPERSEDED | as 751a210b (Roboco c548e7bf0) | - | cross-desktop sync = cloud rooms |
| dfb72cb29 | 2026-09-07 | #90 | Add synchronized pinned sessions on iOS | android-parity | SUPERSEDED | Android has a Pinned section on engine-local pins: `WATCH_SIDEBAR_STATE` crates/mobile-client/src/engines/mod.rs:345; apps/android/.../sessions/CoreRowActions.kt:80; flow test SessionFlowTest.kt:138 | - | Swift slice is SKIP:ios |
| d43292575 | 2026-09-07 | #90 | Add cross-device pin synchronization coverage | engine / tests | SUPERSEDED | Roboco's own sidebar_state.rs tests (crates/engine/src/sidebar_state.rs:490) | - | registry tests target the cloud-synced row |
| a2e1cb806 | 2026-09-12 | #340 | refactor(files): separate explorer rendering from editor layout | files/editor | PORTED | Roboco 3fe0ee66e; test crates/ui/src/files/mod.rs:1375 | - | |
| 07aaa4185 | 2026-09-12 | #340 | feat(shell): add an independent right files panel | files/editor / ui-desktop | PORTED | Roboco 9f68aba86 (+ web db874e7ce); `FILES_PANEL_DEFAULT` crates/ui/src/settings.rs:42; crates/ui/src/shell/files_panel.rs:477 | - | |
| 5fed757ca | 2026-09-12 | #340 | fix(layout): coordinate files panel resizing and expanded mode | ui-desktop | PORTED | Roboco ee3a71523; `set_right_occlusion` crates/ui/src/browser/mod.rs:272 | - | its layout test was replaced upstream by 9033ff4 (absent there too) |
| e39a75c20 | 2026-09-12 | #340 | feat(files): synchronize explorer selection with file tabs | files/editor | PORTED | Roboco 25c078d45; tests crates/ui/src/files/search.rs:832, :898 | - | |
| be87e9c2e | 2026-09-12 | #340 | fix(files): match sidebar frost and blur floating explorer | ui-desktop | SUPERSEDED | upstream 9033ff4eb dropped the floating explorer; Roboco 8f4b173a7 then 5605606d2 the same way | - | |
| a4f6dca5a | 2026-09-12 | #340 | fix(layout): align surface tabs with the panel beside Files | ui-desktop | PORTED | Roboco 20719bf30; test crates/ui/src/shell/tabs.rs:585 | - | |
| dad1c86d9 | 2026-09-12 | #340 | fix(shell): keep the surface picker clear of the Files overlay | ui-desktop | SUPERSEDED | Roboco 0c10e48d0 ported it; upstream 9033ff4eb then removed the overlay (`files_overlay_width` gone in both upstream/main and HEAD) | - | |
| 9033ff4eb | 2026-09-12 | #340 | fix(layout): keep the explorer and editor in separate columns | ui-desktop | PORTED | Roboco 5605606d2; `right_pane_max_width` crates/ui/src/shell.rs:824; files_panel.rs:394 | - | |
| 86c27d794 | 2026-09-12 | #340 | fix(ui): draw the Files sidebar divider only once | ui-desktop | PORTED | Roboco d9f7183a3 | - | |
| 2012bf29d | 2026-09-12 | #340 | fix(ui): remove Files from the central surface menus | ui-desktop | PORTED | Roboco 1f2467795; `surface-card-files` absent from HEAD | - | |
| 5166d4199 | 2026-09-12 | #340 | test(browser): keep blur sampling inside compact menu | browser/previews (test) | PORTED | Roboco 994a2825d | - | |
| e41f3acde | 2026-09-13 | #158 | Fix project action command delivery and menu scrolling | engine / ui-desktop | PORTED | Roboco 2e6e06318; `open_with_command` + tests crates/engine/src/terminals.rs:1161/1222; crates/engine/tests/project_action_commands.rs:12; `menu_scroll` project_actions.rs:71 | - | the ui test `fifty_actions_scroll…` is gone in upstream/main too |
| f53a33be9 | 2026-09-13 | #340 | fix(files): reveal directories activated from search | files/editor | PORTED | Roboco 071133092; test search.rs:869 | - | |
| 3ebe2d8a8 | 2026-09-13 | #340 | fix(ui): match Files background and scroll Markdown padding | files/editor | PORTED | Roboco 7466d986f; `PREVIEW_VERTICAL_PADDING` crates/ui/src/files/markdown_preview.rs:26 | - | |
| af279d541 | 2026-09-14 | #366 | Add Zeroning easter egg to the working indicator | ui-desktop | PORTED | "Robocoing" crates/ui/src/transcript.rs:2311-2312; web/packages/app/src/lib/transcript.ts:1229 | - | iOS Motion.swift slice: SKIP:ios. upstream/main iOS no longer has flavour words, so there is no Android gap |
| 6d39f8c6a | 2026-09-14 | #368 | Fix appshot thumbnails bypassing transcript edge fades | ui-desktop | PORTED | identical comment + un-faded thumbnail crates/ui/src/transcript.rs:6562 | - | no port commit names it; the code is present |
| 9601a2440 | 2026-09-15 | #367 | feat: render generated images in conversation threads | engine / harness / ui-desktop | PARTIAL | engine/harness/proto/doc ported (Roboco 010747d03; sessions.rs:6425). Android renders them (crates/mobile/src/layout/rows.rs:431). **Desktop rendering missing**: transcript.rs:1725-1728 is a no-op `MessagePart::Image { .. } => {}` ("not ported yet") | M | Roboco db407106e: "decode_generated_image … NOT ported — out of scope". No user decision recorded. iOS/edge slices: SKIP |
| d353ebbfa | 2026-09-15 | #362 | Fix text selection in code blocks | ui-desktop | PORTED | Roboco e8a008296; crates/ui/src/markdown/render.rs:1363, :3071 | - | |
| e74f275e6 | 2026-09-15 | #364 | feat(ui): floating menu scrollbars, Linux CSD window chrome, and stacked error notices | ui-desktop (Linux) | PORTED | Roboco 3edfe53b2; `linux_csd_edges`/`render_linux_resize_borders` crates/ui/src/shell.rs:6916/6957; crates/ui/src/notice.rs; popover.rs:2427/2501 | - | |
| e4702fa68 | 2026-09-15 | #350 | Support projectless sessions on iOS | android-parity | PORTED | Android: `SessionTarget.Projectless` apps/android/.../newsession/NewSessionCreate.kt:83-87; host pick NewSessionProjectMenus.kt:149; crates/mobile-client/src/new_session.rs:37 | - | Swift slice SKIP:ios |
| 79b27ed68 | 2026-09-16 | #374 | Choose the terminal and code/diff fonts independently | settings / ui-desktop | PORTED | Roboco dd823f11c; crates/ui/src/typography.rs:757 | - | macOS/iOS CI hunks dropped |
| d8ce7b6eb | 2026-09-15 | #378 | Fix Codex account login binary resolution | harness | PORTED | Roboco bd3e65bdd (verify-only): `login_command` + `compose_child_path`; crates/engine/tests/codex_login_resolution.rs | - | Roboco's test isn't run in windows.yml (see 68ed902ed) |
| 67c960f4d | 2026-09-16 | #376 | fix(opencode): harden 1.18 session create + speak the 2.x wire | harness (opencode) | PORTED | Roboco c132bc596; `provider_catalog`/`commands_wire` crates/harness/src/opencode/mod.rs:362/377 | - | |
| bc7aa5494 | 2026-09-15 | #313 | Harden Windows batch arguments and keep unsupported in-app blur opaque | harness (Windows) | PORTED | Roboco 00aeaa8d2; `quote_batch_argument` crates/harness/src/process/windows/command.rs:323; test crates/harness/tests/windows_native.rs:595 | - | theme.rs "opaque on Windows" hunk deliberately skipped; upstream reverses it in 6a51747e |
| 2966c16e6 | 2026-09-15 | #313 | Require secure update feeds and patch Rustls handshake validation | updater/install | PORTED | Roboco 0d68e39de; `validate_release_override` crates/update/src/lib.rs:239; rustls 0.23.45 in Cargo.lock | - | |
| 68ed902ed | 2026-09-15 | #313 | Fail closed on unresolved Windows shells and exercise login and startup in CI | terminal (Windows) / ci | PARTIAL | code ported (Roboco 5a1897b20; crates/engine/src/terminals/windows.rs:266). **CI hunks missing**: windows.yml runs neither `--test codex_login_resolution` nor `./scripts/test-windows-startup.ps1`; the script exists in scripts/ | S | Roboco 5a1897b20 deferred them to "ticket 04", which never added them. Roboco removed the startup step in 2ec46c0c0 (pre-fork) |
| f247ed91d | 2026-09-15 | #313 | Cover Unicode batch paths and reject path expansion syntax | harness (Windows) | PORTED | Roboco bdd22dbb3; windows_native.rs:663 | - | |
| a65ae8cb9 | 2026-09-15 | #313 | Run native process security tests before the full Windows build | ci | PORTED | Roboco 065da1c35; windows.yml:75 (harness `--lib --test windows_native` in the early job) | - | |
| 25f561603 | 2026-09-15 | #313 | Exercise the Windows portable packaging script in PR validation | ci | PORTED | Roboco a06315e60; windows.yml:84-88 "Verify portable and installer packaging" | - | |
| 94cc67536 | 2026-09-15 | #444 | test(sync): reproduce stalled socket recovery failures | rpc / tests | PORTED | Roboco dae6cccff; crates/rpc/src/pump.rs:519/562/572, crates/rpc/src/lib.rs:680 | - | sync-socket tests: SKIP:cloud |
| 0c8663739 | 2026-09-15 | #313 | Capture and validate the Windows GUI executable version explicitly | ci / updater | PORTED | Roboco 9d6669f68; scripts/package-windows.ps1:105-111 | - | |
| 01bcb8ee4 | 2026-09-15 | #313 | Wait for browser menu dismissal in native tests and cover packaging changes | ci / browser | PORTED | Roboco 037d6cc39; `fixture_browser_menu_mounted` crates/ui/examples/browser-fixture/linux.rs:511; windows.yml:14 path filter | - | |
| a3c370b58 | 2026-09-15 | #444 | fix(transport): recover from stalled sockets with bounded session pumps | rpc / transport | PORTED | Roboco dae6cccff: crates/rpc/src/pump.rs (all WS RPC sessions); refined by 2f51b449a | - | sync/relay slice: SKIP:cloud |
| 62a429ecf | 2026-09-15 | #313 | Pin Windows generated-image paths and deny mutations during import | engine (Windows) | PORTED | Roboco 14da55c80; `open_generated_file` crates/engine/src/uploads.rs:745, test :1021 | - | |
| 67f08ace2 | 2026-09-15 | #313 | Use shared read access for Windows directory pinning | engine (Windows) | PORTED | Roboco 22c31247a | - | one-line |
| efa1a0752 | 2026-09-15 | #444 | Reduce redundant sync traffic and keep slow transfers progressing | rpc / transport | PORTED | Roboco dae6cccff: ProgressIo leases + fragmented sends in pump.rs; test pump.rs:619 | - | sync-traffic dedupe slice: SKIP:cloud |
| 3f19c19eb | 2026-09-15 | #313 | Run Windows font geometry against DirectWrite and isolate native test setup | ci / ui-desktop | PORTED | Roboco 3afadcce9 + e44a296be; windows.yml:140/147 split | - | |
| 0cc2f0778 | 2026-09-15 | #313 | Keep animation wakeups on the deterministic scheduler in UI tests | ui-desktop (tests) | PORTED | Roboco e44a296be; crates/ui/src/motion/windows_pulse.rs:90 | - | |
| 6eae0242d | 2026-09-15 | #390 | Update ZUI to audited Windows backdrop renderer | other (deps) | PORTED | Roboco 73dd97951 (pin c2d273dc; now further at 0966d065) | - | |
| 7f0cd1336 | 2026-09-16 | #390 | Align gpui-base pin to keep one GPUI dependency identity | other (deps) | SUPERSEDED | Roboco's `[patch."https://github.com/zeronsh/zui"]` redirect keeps one GPUI identity (Roboco 73dd97951 explains why it was skipped; AGENTS.md pin rule) | - | |
| 6a51747e7 | 2026-09-16 | #391 | Enable Windows in-app frost with the audited Direct3D renderer | ui-desktop (Windows) | PORTED | Roboco bf4364bc9 (core already present from ba4a3a9e) | - | |
| 25eae40ac | 2026-09-16 | #391 | Document Windows floating-surface frost support | ui-desktop / docs | PORTED | Roboco bf4364bc9 (theme.rs comment + docs reflow) | - | |
| 0d19bedcb | 2026-09-16 | #392 | Offer OS-aware desktop downloads with explicit platform choices | landing | SKIP:landing | apps/landing + deploy.yml only | - | |
| 3173649e2 | 2026-09-16 | #394 | Release v0.2.66 | - | SKIP:version-bump | Cargo.toml/lock only | - | |
| 4d741cfc1 | 2026-09-16 | #396 | Restyle the landing download section to match the page | landing | SKIP:landing | apps/landing only | - | |
| 7f04c898f | 2026-09-16 | #397 | Lay the landing downloads out as desktop and mobile rows | landing | SKIP:landing | apps/landing only | - | |
| f541ff0e8 | 2026-09-16 | #340 | Refine Files panel toggle with a custom tree icon | ui-desktop | PORTED | Roboco 2cc983139; `FILE_TREE` crates/ui/src/icons.rs:88 | - | |
| 97b31a8f6 | 2026-09-16 | #340 | Move Files search into the panel titlebar | files/editor | PORTED | Roboco 0afbd424c | - | its titlebar-search ui test is gone in upstream/main too |
| 7904e8a7a | 2026-09-15 | #400 | Restore device names in the sidebar project selector | ui-desktop | SUPERSEDED | upstream 672cc1444 (2026-10-10, "the tag is gone from the dropdown and the sidebar filter") removed the tag again. HEAD matches upstream/main: `(_, offline) = project_device_tag` crates/ui/src/shell/spaces.rs:3556, :3814 | - | **Stale ticket**: .scratch/upstream-ports-2026-10-wave7/issues/14-device-names-project-selector.md still says ready-for-agent. Close it as superseded |
| 1400f7ab9 | 2026-09-15 | #399 | Add Cmd+K palette for actions and chat history | ui-desktop | PORTED | Roboco d9dfb0bc8; crates/ui/src/shell/command_palette.rs:721/764 | - | |
| b73c12f9a | 2026-09-16 | #401 | Release v0.2.67 | - | SKIP:version-bump | Cargo.toml/lock only | - | |
| 74558a2ca | 2026-09-16 | #402 | Add command palette scroll padding and action shortcuts | ui-desktop / settings | PORTED | Roboco 62d4dff9c; `new_project_shortcut_migrates_and_persists` crates/ui/src/settings.rs:3596 | - | upstream b4dd24d7 (#407) and Roboco 50052a47d later removed `RESULTS_SCROLL_GUTTER` in favour of edge-faded results |

## Port candidates (MISSING + PARTIAL), most valuable first

### 9601a2440 (#367) feat: render generated images in conversation threads — desktop UI slice
- What it does for users: when Codex generates an image (image-generation tool), the image shows up
  inline in the conversation as a thumbnail. Click it to open the lightbox. Today Roboco's engine
  already imports the image into the profile uploads and stores a `MessagePart::Image` in the doc,
  and the **Android app already renders it** (crates/mobile/src/layout/rows.rs:431). The Windows/Linux
  desktop shows **nothing** for that part (crates/ui/src/transcript.rs:1725-1728, a no-op arm whose
  comment says "not ported yet"). So a phone shows images the desktop silently drops. That is a
  visible correctness gap on Roboco's main platform.
- Missing: upstream's crates/ui slice:
  - transcript.rs: `RowKind::GeneratedImage`, `render_generated_image`, owner-device candidate
    resolution (`generated_image_devices`/`generated_attachment_device_ids`), click → lightbox with
    focus restore, LRU retention instead of history-wide protection.
  - attachments.rs: generated-image cache keys/aliases, load claims (`begin_load_for`,
    `AttachmentLoadGuard`), the chunked reader via `READ_ATTACHMENT_CHUNK` aimed at the owner device.
  - image_media.rs: `decode_generated_image`, which checks the MIME against the sniffed format and
    downsamples to 2048 px, keeping the first frame only.
  - state.rs: a test client hook; fixture crates/ui/tests/fixtures/generated-images.json; about 10 ui tests.
  - Roboco already has the substrate: `read_attachment_bytes` (attachments.rs:675) and the
    `lightbox` (attachments.rs:1077).
- Roboco files touched: crates/ui/src/{transcript.rs,attachments.rs,image_media.rs}. Possibly also
  web/packages/app (the web transcript has no image-part rendering either; Roboco-only parity).
- Dependencies/risks: no wire change (proto `AgentEvent::GeneratedImage` and the doc part are
  already ported). Later upstream commits that touch GeneratedImage rows were excluded on the
  Roboco side (compact mode e7ab4b9b and others; roboco-log lines ~22013 and ~22442), so port
  against the upstream/main shape of those arms, not the 9601a244 shape. Owner-device resolution
  must use Roboco's engine registry (`EngineTarget`), not upstream's device routing.
- Effort M.

### 68ed902ed (#313) Fail closed on unresolved Windows shells — CI slice
- What it does: Windows CI also runs (a) `roboco-engine --test codex_login_resolution`, Roboco's own
  Windows-aware Codex login resolver suite that d8ce7b6e relies on, and (b)
  `./scripts/test-windows-startup.ps1`. That script checks the desktop exe is GUI-subsystem
  (no console flash at launch) and that `--help`/`--version`/bad flags still reach redirected
  stdout/stderr.
- Missing: both steps are absent from `.github/workflows/windows.yml`. The script is in the repo
  (scripts/test-windows-startup.ps1, already rebranded) and `codex_login_resolution.rs` exists,
  but nothing runs them. Only docs/reference/windows-development.md:110 mentions the test. The fail-closed
  shell code itself is ported (crates/engine/src/terminals/windows.rs:266).
- Change: windows.yml: add `--test codex_login_resolution` to the "Harness catalog tests" nextest
  line (:129). Add a step running `./scripts/test-windows-startup.ps1` after the release build,
  next to "CLI startup without HOME". This protects a Windows-only regression class (console
  allocation, lost CLI output) on Roboco's main platform.
- Dependencies/risks: none. The startup step needs the release `roboco.exe` already built in that job.
- Effort S.
