# Part 02 — upstream b0f25de53 .. c054705e8 (2026-09-18 → 2026-09-21, 69 commits)
Summary: 69 commits — PORTED 57, PARTIAL 2, MISSING 0, DECLINED 0, SUPERSEDED 4, SKIP 6, N/A 0

Method: every SHA was grepped in `roboco-log.txt` (Roboco port commits); each cited Roboco commit
was confirmed to be an ancestor of HEAD `85be4485a` (`git merge-base --is-ancestor`, all 62 pass);
then a distinctive marker of each upstream diff was looked up in HEAD (`git grep HEAD`). Port-commit
"Excluded:" notes were read and each exclusion re-checked against HEAD and `upstream/main`.

## Table
| SHA/PR | date | upstream PR | title | area | class | evidence | effort | notes |
|---|---|---|---|---|---|---|---|---|
| b0f25de53 | 2026-09-18 | — | Rename sidebar location option and update globe artwork | ui-desktop | PORTED | a046d8306; `crates/ui/src/shell/spaces.rs:3210` "Location" | — | screenshot PNG skipped |
| f562f5d2b | 2026-09-18 | — | fix(actions): preserve project identity for queued worktree setup | engine | PORTED | c22db05e5 + ba1c73270; `crates/engine/src/doc_host.rs:3349` `setup_action(space_id, Path::new(&space.path))`, also `rpc.rs:3226` | — | |
| b58af6279 | 2026-09-18 | — | Improve monogram hover contrast and show project tooltips | ui-desktop | PORTED | 0d45ad20f; `crates/ui/src/shell/project_icon.rs:213` `ProjectIconTooltip`, 350ms delay `:276` | — | |
| ec2b32302 | 2026-09-18 | — | Show compact-mode "Worked for Xm Ys" after the turn settles | ui-desktop / engine | PORTED | 5d387db50; `duration_ms` in `crates/doc/src/schema.rs`; `crates/ui/src/transcript.rs:2376` `worked_for_label` | — | gen_fixture/local_import hunks N/A (files deleted in Roboco) |
| 378a1945f | 2026-09-18 | — | Simplify project icons, add device tooltip, and split compact mode | ui-desktop | PORTED | ea6fbf926; device line in ProjectIconTooltip; `REMOTE_SERVER` icon | — | Layout section later reshaped by f9563394 (also ported) |
| 4dba1c8b7 | 2026-09-18 | — | Match archived "Show more" height to compact rows | ui-desktop | PORTED | 673d20f05; `crates/ui/src/shell/spaces.rs:5856` `more_height` | — | |
| 6518adf6a | 2026-09-18 | — | Persist compact-mode turn duration and replace the work summary | ui-desktop | PORTED | 3fc7f892b (net with d9d7deea); `transcript.rs:2380` crossfade header | — | sidecar intentionally not taken (d9d7deea removed it) |
| d9d7deea0 | 2026-09-18 | — | Drop guessed compact durations and the turn-duration sidecar | ui-desktop | PORTED | 3fc7f892b (final state) | — | |
| 63f3945e1 | 2026-09-19 | — | chore: release v0.2.79 | other | SKIP:version-bump | Cargo.toml/lock only | — | |
| e56680ca7 | 2026-09-19 | — | Remove Cursor SDK diagnostics from device settings | settings | PORTED | 1b14b3227 (satisfied by omission); `crates/ui/src/settings/devices.rs` has no "Cursor SDK" | — | |
| e7ab4b9b5 | 2026-09-19 | — | Expand compact "Worked for" into the normal transcript layout | ui-desktop | PORTED | 02f31505a; `compact_fold` (51 hits in `crates/ui/src`) | — | GeneratedImage arm N/A (row kind absent in Roboco) |
| 1de3b4bae | 2026-09-19 | #340 | Make the explorer a docked portion of one right pane | ui-desktop | PARTIAL | c287c9cc0 (docked pane, `set_surfaces_open`); CSD corner hunks excluded and never landed — `crates/ui/src/shell/files_panel.rs` has no corner rounding | S | Linux floating CSD windows only; see candidates |
| fe45a1cd6 | 2026-09-19 | #340 | Let the pane toggle drive only the surface host | ui-desktop | PORTED | 41854e2e1 ("Ported clean") | — | |
| c4d5f98ea | 2026-09-19 | — | chore: ignore agents.md | other | PORTED | 606c935cc; `.gitignore:16` | — | |
| c86d9345f | 2026-09-19 | — | chore: ignore .agents directory | other | PORTED | 606c935cc; `.gitignore:15` `.agents/` | — | Roboco keeps both entries on purpose |
| 6ecea0558 | 2026-09-19 | #468 | ios: fix transcript follow, normalize model catalogs, desktop networking parity | android-parity | SUPERSEDED | apps/ios only; every touched Swift file (ChatRoomClient, DocDisk, SessionStore, NativeTranscriptTable, HarnessCatalog, ComposerView, SessionView) deleted upstream by fe46a971a (#570 iOS rewrite on Rust core), which Roboco's Android app mirrors | — | sync-room client layer is cloud; scroll/follow behaviour re-implemented in #570 |
| 7083e1800 | 2026-09-20 | #340 | Give the explorer a secondary header for search and visibility | files/editor | PORTED | c03d9d1cb; `crates/ui/src/files/mod.rs:1226` `render_explorer_header`, `files-explorer-header` | — | |
| f9563394f | 2026-09-20 | #465 | Compact sidebar account control and nest view options | ui-desktop | PORTED | 1be111de2; `crates/ui/src/popover/hover_intent.rs`; `pickers.rs:1152` HoverIntent | — | web parity 2b713bf2d |
| 86249cf04 | 2026-09-20 | #486 | Add custom sidebar sections with session drag and drop | ui-desktop | PORTED | 75ae96cd6; `crates/ui/src/shell/sidebar_sections.rs`; `more-horizontal` icon | — | |
| 850238fe9 | 2026-09-21 | — | chore: release v0.2.80 | other | SKIP:version-bump | | — | |
| 94268229f | 2026-09-20 | #487 | Sync sidebar sections across devices | engine / proto | SUPERSEDED | Roboco re-homed sections+pins engine-side: c548e7bf0 `crates/engine/src/sidebar_state.rs` (`SetSidebarSections`/`WatchSidebarState`, all clients of one engine share it); ordered sections via wave7/06 ce4447c20 | — | cross-device registry/edge reconcile slice = SKIP:cloud (ADR 0004) |
| 7f1db6b14 | 2026-09-21 | — | chore: release v0.2.81 | other | SKIP:version-bump | | — | |
| 92153025b | 2026-09-21 | #498 | Add zeron mcp server with parent-linked chats | engine / harness | PORTED | 20fbbab5c `crates/roboco-mcp/`; `state.rs:1871` visible_chats hides `parent_chat_id`; `spaces.rs:5814` archived filter | — | Pi tool injection skipped there, later added (`crates/harness/src/pi/mcp.rs`, wave3/21) |
| b9b356650 | 2026-09-21 | — | Add Mod+E files-panel shortcut and space the pane toggles | ui-desktop / settings | PORTED | 803fbc440; `ShortcutId::ToggleFiles` | — | |
| 060923813 | 2026-09-21 | — | Drop the doubled hairline over the explorer seam | ui-desktop | PORTED | c0a5cfbb5; `crates/ui/src/shell/tabs.rs:487` | — | |
| a1f3fd1a2 | 2026-09-20 | #479 | ios: durable outbox + send watchdog, fix transcript scroll jumps, session UX polish | android-parity | SUPERSEDED | apps/ios only; Swift Sync/Transcript layer deleted by fe46a971a (#570) | — | chat2 outbox/room durability is sync-room (cloud); UI bits (centered jump-to-latest, composer chip fade, connectivity in strip, composer growth under keyboard) belong to #570-era Android parity audit |
| 52af27280 | 2026-09-21 | — | Separate Antigravity detection and authentication from server availability | harness | PORTED | 2207836f8 | — | agy-CLI swap excluded (upstream reverted it) |
| 3f8f159b6 | 2026-09-21 | — | Make Antigravity enablement follow detection and expose explicit sign-in | harness / settings | PORTED | 44fbb90b4; `crates/ui/src/settings/harnesses.rs:66` `offers_sign_in`, `:729` `start_sign_in` | — | |
| 95a9e4d97 | 2026-09-21 | — | Record Antigravity validation status and disk-limited follow-ups | docs | SKIP:docs-only | added then deleted by 855f8b4be | — | |
| 1251321cd | 2026-09-21 | — | fix(acp): own process groups and preserve Pi crash context | harness | PORTED | 63159d1aa; `crates/harness/src/acp/child.rs` (Windows keeps job objects) | — | Pi-specific fixture later retired with Roboco's native Pi driver (fe87137a1) |
| 29018a3e8 | 2026-09-21 | — | fix(pi): preserve descendant cleanup grace and verify durable resume | harness | PORTED | a73fafae8; `crates/engine/tests/pi_resume.rs` | — | pi_live.rs/fake-pi-acp.sh removed by fe87137a1 (native Pi driver; Roboco-first decision ticket 21) |
| 855f8b4be | 2026-09-21 | — | Remove repository-local validation status report | docs | SKIP:docs-only | nets 95a9e4d97 to zero | — | |
| 549cb3ba7 | 2026-09-21 | — | fix(acp): reject requests after EOF and retain the latest stderr tail | harness | PORTED | 8c7cfab32; `is_closed` in `crates/harness/src/jsonrpc.rs` | — | |
| 0e3239d97 | 2026-09-21 | #490 | ios: bound snapshot write cadence and store residency by bytes | android-parity | SUPERSEDED | apps/ios DocDisk/SessionStore only; deleted by fe46a971a (#570) | — | Loro snapshot residency of the old sync-room store |
| fd188cfdb | 2026-09-21 | — | Consume complete HTTP request headers in catalog test fixtures | harness | PORTED | f782db82d | — | test-only |
| dcb62f083 | 2026-09-21 | — | Let the engine surface Antigravity launch errors uniformly | harness | PORTED | ef95d47eb | — | |
| c84f9b6bb | 2026-09-21 | — | Allow Antigravity cold discovery up to ninety seconds | harness | PORTED | 7308519c3; `ANTIGRAVITY_DISCOVERY_TIMEOUT` | — | |
| 7bf44ece7 | 2026-09-21 | — | fix(harness): detect current OpenCode server versions and auth failures | harness | PORTED | 137421f01; `ServerVersion` in `crates/harness/src/opencode/` | — | |
| fb01b9512 | 2026-09-21 | — | fix(harness): version OpenCode commands and surface HTTP rejection | harness | PORTED | 8360f24d7; `BusMsg::CommandFailed` | — | |
| 507015365 | 2026-09-21 | — | feat(harness): select OpenCode agents through cached model options | harness | PORTED | 044760e37 | — | |
| 11e2574a5 | 2026-09-21 | — | fix(harness): normalize OpenCode status retry and tool progress events | harness | PORTED | d585a30a8; `session.retry.scheduled` normalization | — | |
| 035521813 | 2026-09-21 | — | docs(harness): record OpenCode compatibility and extend isolated probes | harness | PARTIAL | module doc refresh present (`crates/harness/src/opencode/mod.rs:12-27`); probe extensions absent: no `OPENCODE_PROBE_WORKSPACE/AGENT/INTERRUPT_MS` in `examples/opencode_turn_probe.rs`, no `options:` print in `opencode_models_probe.rs` | S | dev tooling only, low value |
| f671cb59f | 2026-09-21 | — | Drop reference-integration name from ACP comments | harness | PORTED | e4f214ab9 | — | comment-only |
| 6611d3c06 | 2026-09-21 | — | fix(harness): select newest CLI and honor configured model metadata | harness | PORTED | 4f14e71f4; `semver` in `crates/harness/Cargo.toml` | — | |
| a7b86272b | 2026-09-21 | — | fix(harness): finish portable version probes and diagnostics | harness | PORTED | 73f530043 | — | |
| a29956633 | 2026-09-21 | — | fix(harness): retain catalogs by credential and binary context | harness | PORTED | b012fd082; `crates/harness/src/model_context.rs`, `catalog.rs` | — | |
| d8ea030f1 | 2026-09-21 | — | fix(engine): persist contextual model catalogs and refresh in background | engine | PORTED | 0c5308294; `crates/engine/src/model_catalogs.rs` | — | |
| 212a6ce86 | 2026-09-21 | — | fix(ui): retain model rows after refresh errors and send force | ui-desktop | PORTED | cc81a0cc6; `pickers.rs:1796` force param, `:1854` `apply_model_catalog`; `rpc.rs:109` | — | |
| 9048b6449 | 2026-09-21 | — | feat(claude): share initialize discovery for live models and commands | harness | PORTED | dcc9836d5; `crates/harness/src/claude/discovery.rs` `InitializeCache`; `tests/claude_models.rs` | — | |
| b3ec6b7e5 | 2026-09-21 | — | fix(models): classify discovery failures and reject unauthorized stale catalogs | harness / ui | PORTED | d75157981; `crates/harness/src/catalog_failure.rs` | — | |
| e0b62774c | 2026-09-21 | — | fix(codex): reject empty catalogs and retire discovery children | harness | PORTED | b91df89a9 | — | |
| ad95708f0 | 2026-09-21 | — | fix(ui): preserve saved models absent from refreshed catalogs | ui-desktop | PORTED | 0ccacbb6e; `selected_only` rows in `pickers.rs` | — | |
| 568d78935 | 2026-09-21 | — | test(harness): align refresh regression with last-good catalogs | harness | PORTED | 312edbbb0; `tests/model_refresh.rs` | — | test-only |
| 4c52a970c | 2026-09-21 | — | test(opencode): retain same-context catalogs on empty refresh | harness | PORTED | 8cf8bc79f; `tests/opencode.rs:1516` | — | test-only |
| 0c834f65b | 2026-09-21 | — | docs(models): record full validation and live discovery results | docs | SKIP:docs-only | docs/research only | — | |
| 248fe26f0 | 2026-09-21 | (port of #475) | fix(harness): own archive adapter temporary roots through shutdown | harness | PORTED | 38ac6d7ca; `crates/harness/src/scratch.rs` `ScratchDir` | — | |
| 8e3ab3d0d | 2026-09-21 | — | fix(engine): allow remote adapter catalog discovery to finish | engine / ui / web | PORTED | 606d02307: relay deadline re-homed to client deadlines (`crates/ui/src/engine_registry.rs` 100s ListModels/ListCommands; web engine-client) | — | relay forward_deadline hunk N/A |
| 1e035a86e | 2026-09-21 | (port of #452) | fix(harness): strip echoed wakeup blocks from Antigravity streams | harness | PORTED | dea92cf41; `crates/harness/src/acp/system_message.rs` | — | |
| 1d0155863 | 2026-09-21 | — | test(harness): prove cancel notifications omit request ids | harness | PORTED | 965339e8d | — | test-only |
| 371d79b1f | 2026-09-21 | — | test(harness): preserve large frames amid stdout noise and CRLF | harness | PORTED | d8fb81fb9; `tests/fixtures/fake-robust-acp.py` | — | test-only |
| 2cc8ded31 | 2026-09-21 | — | test(harness): drain a full update queue before turn completion | harness | PORTED | 55f3f27bf | — | test-only |
| f9233a530 | 2026-09-21 | — | test(harness): empty assistant chunks produce no segment events | harness | PORTED | f6a87f0de | — | test-only |
| d1ccdb276 | 2026-09-21 | — | fix(harness): retain bounded startup command and mode metadata | harness | PORTED | d7c481aeb | — | |
| f7ef28214 | 2026-09-21 | — | fix(harness): reject foreign session permissions and ignore their traffic | harness | PORTED | cb1ace105 | — | |
| 62334b725 | 2026-09-21 | — | fix(harness): redact credentials in crash diagnostics | harness | PORTED | c6e67bf63; `redact_secrets` in `crates/harness/src/lib.rs` | — | |
| 55b09da63 | 2026-09-21 | — | fix(harness): discard prompt settlement after cancel escalation | harness | PORTED | 2ea912d78 | — | |
| a3f367278 | 2026-09-21 | — | test(harness): dropping an idle stream reaps its warm adapter | harness | PORTED | 5a524be21 | — | Linux-only test |
| 01bce7181 | 2026-09-21 | — | fix(harness): configure Antigravity siblings and capture stdout sign-in URLs | harness | PORTED | 1ff59896c; `RpcClient::with_stdout_observer` | — | |
| c054705e8 | 2026-09-21 | — | test(harness): exercise Pi steering without the extension | harness | PORTED | b363c8776; retargeted to hermes by fe87137a1 (`tests/acp.rs:1701` `steer-live`/`steer-idle`) | — | |

## Port candidates (MISSING + PARTIAL), most valuable first

### 1de3b4bae (#340) Make the explorer a docked portion of one right pane — CSD corner slice
- What it does for users: on a floating Linux window with client-side decorations (rounded
  corners, `LINUX_WINDOW_CORNER_RADIUS`), whichever column is rightmost paints the window's rounded
  right corners. With the explorer docked it is the rightmost column, so it should carry them.
  Windows is unaffected (`window_corner_radius` returns 0 there).
- What is missing in Roboco: c287c9cc0 excluded "CSD corner hunks" because CSD support had not
  landed yet. CSD has since landed (`crates/ui/src/shell.rs:6944` `window_corner_radius`), but the
  three hunks were never added:
  1. `crates/ui/src/shell/files_panel.rs` explorer column: `.when(corner > 0.0, |el|
     el.rounded_tr(..).rounded_br(..))` (upstream/main `files_panel.rs:550-566`).
  2. `crates/ui/src/shell.rs:~10916` surface panel: round only when
     `self.files_visible_width(cx) <= 0.0` (Roboco rounds it every time, even when the explorer
     sits to its right).
  3. `crates/ui/src/shell.rs:10358` terminal dock: `br = window_corner && !right_pane_open &&
     !files_panel_open` (Roboco omits the files check, so with only the explorer open the terminal
     still rounds a corner that is not at the window's edge).
- Dependencies: none. No wire change. Risk: only visible on a floating Linux window.
- Effort S.

### 035521813 docs(harness): record OpenCode compatibility and extend isolated probes — probe slice
- What it does: developer-only live probes. `opencode_turn_probe` gains
  `OPENCODE_PROBE_WORKSPACE` (use an existing configured dir), `OPENCODE_PROBE_AGENT` (select agent
  via `model_options.agent`), `OPENCODE_PROBE_INTERRUPT_MS` (cancel after the first text delta and
  expect an Interrupted outcome). `opencode_models_probe` prints each model's `options`.
- Missing in Roboco: both example changes (`crates/harness/examples/opencode_turn_probe.rs`,
  `opencode_models_probe.rs`). The module doc refresh is already in
  `crates/harness/src/opencode/mod.rs:12-27`.
- Dependencies: 507015365 (agent model option; ported). No wire change. No user-visible effect.
- Effort S (low value).
