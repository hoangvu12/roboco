# Pass 2 — big-2 (slice audit of 14 commits marked PORTED)

Roboco HEAD `85be4485a`; upstream `refs/remotes/upstream/main` = `31d26eeb6` (plain `upstream/main` is
ambiguous in this clone, there is a local ref too). Method: each upstream diff split into behavioural
slices (new fns/consts/tests/copy/platform branches), each searched in HEAD after renames; then
`git log <sha>..refs/remotes/upstream/main -- <files>` for later refinements of the same feature.
Cloud / iOS / macOS-only slices and recorded declines ignored.

Verdict: 9 of 14 commits are fully ported (later refinements included, or the remaining misses are test-only):
3c7185b44*, 8646bde07*, 037f4c10d, c72c66d8a, b25dd404c, e74f275e6, 1f7b74a7f, a29956633*, dd0c36fa3.
(* = only test/doc misses or the gap is in a later refinement.)
Gaps in the commit itself: 35a9139a1 (Devin probe reap), e799aecc1 (Linux browser-fixture trio),
77a3c0ff1 (Windows path identity), c87daf244 (shim marker mode).
Gaps from later refinements: 01832f2ca (connected-aware default harness; Interrupted outcome via cc7da5c2a),
3c7185b44 (a75cf1a61 paste hardening, already PARTIAL in pass 1), c87daf244 (4a0d416cf, already MISSING in pass 1).

---

## 3c7185b44 — Add rich composer references and completion
Port commits: `944065d93`, `aade2ce02`, plus the follow-up chain `77a11cc0d`, `27c496654`, `685c85faa`,
`4d3275e24`, `407c952e0`, `4dbb8b742`, `7ac874196`, `d5c90f987`, `42cc2e4ca`, `8c8357905`, `746172255`.
296 added identifiers checked: 279 in HEAD, and the other 17 are either removed upstream too (b8c0ccea5) or renamed.

| slice | present? | evidence |
|---|---|---|
| proto `invocation.rs` (`harness_prompt`, `validate_harness_invocations` + tests) | yes | `crates/proto/src/invocation.rs`, fn set identical |
| proto `file_mentions.rs` (`roboco-file:`) | yes | fn set identical |
| harness `skills.rs` (SKILL.md discovery, claude/acp dirs, opencode plugins, codex `skills/list`) | yes | fn set identical |
| `Harness::skills(cwd)` / `commands_for(cwd)` in every harness + tests | yes | harness `lib.rs`; tests acp/claude/codex/opencode |
| Engine `LIST_SKILLS`, `catalog_root` (projectless → persisted cwd / home) | yes | `crates/engine/src/rpc.rs:1635,1663` |
| sessions dispatch/steer through `harness_prompt` | yes | `aade2ce02` |
| `engine/tests/rich_composer_delivery.rs` | yes | 4 tests identical |
| Composer `@` mentions: stale-response guard, error instead of "No matching files" | yes | `crates/ui/src/composer.rs:5804-5822,7864` |
| `/` commands + `$` skills menus, skeleton rows | yes | `composer.rs:5784,7491,7833,8068,8262` |
| Double-click word / triple-click line | yes | `composer.rs:254-257,3903`; tests `:13785,:13803` |
| Pasted-reference chipping | yes (base) | `composer.rs:5577,7452` (later hardening missing, see below) |
| Clipboard readable outside / lossless inside | yes | test `clipboard_is_readable_outside_roboco_and_lossless_inside` |
| `composer_markdown.rs` | yes | identical fn set |
| Workspace slash commands (`/model /new /resume /settings /diff /files /terminal /rename /stop`) | yes | `crates/ui/src/shell.rs:12083` + test |
| Settings `skill_completion_by_harness`, `skills_in_slash_menu` | yes | `crates/ui/src/settings.rs:1648`; rows on Shortcuts page (`settings/completion.rs`, `shortcuts.rs:934`) per 13cb6d7c |
| pickers menu budgets, popover skeleton rows, motion fast-tier sheen, `fast-tier.svg` | yes | present |
| `docs/harness-skill-completion.md` | yes, stale | line 48 says "Settings → Agents"; upstream says Settings → Shortcuts → Composer completion |
| composer-polish fixture, early chip constants/tests | gone in both | b8c0ccea5 / Roboco `746172255` |
| Relay forward-list for `LIST_SKILLS`, `edge_url` test fields | excluded | cloud / ADR 0004 |

Later upstream refinements: da2adfe84, 8f34f3c90, ea253ebbd, 7a009a8f3, 216558ed0, 1c2cebfc9, ec4ba8148, f530da6fe,
d1ae659a0, 13cb6d7c5, f6ba2a57d, b8c0ccea5, 23a41f99d, 730c609e3 — present. 9b7566472 (#588) present (`67c6005d4`).
73bd3c842/d5c1cdc12 present (`1ed398d68`). b65b47536 present (`f199cf67d`).
**a75cf1a61 — partly missing** (catalog refresh ported in `946af475a`; paste hardening not; already PARTIAL in pass 1).
b782d0437 (completion rows per provider) — Roboco keeps them on Shortcuts deliberately (roboco-log "completion stays
here (ticket 10, decision 18)"); no decision-18 record found in `.scratch`. cce9f252d/4be4e0fda doc wording — missing (the stale doc line above).

## 35a9139a1 — Add agent update monitoring and controls (#389)
Port commits: `7811c20f0` (engine), `24ea8e236` (desktop), `d77f5793b` (web), `bd40a022f`. 240 added names checked; 224 present, and the 16 absent are accounted for below.

| slice | present? | evidence |
|---|---|---|
| Engine coordinator, policies, prefs file, Watch/Check/Apply/Cancel/Dismiss/SetPolicy RPCs | yes | `crates/engine/src/harness_updates.rs`; `rpc.rs:2406` |
| Grok last-version-token parse; periodic check races shutdown | yes | `harness_updates.rs:3053-3091` |
| Registry execution gates / `discover_models_with_lease` | yes | `crates/engine/src/registry.rs:137,161,237` |
| Titles + discovery under lease | yes | `titles.rs:172,189,221` |
| Steering deferred by pending update ("held until the agent update finishes") | yes | `sessions.rs:58,862,1469,1539`; `doc_host.rs:2045-3079` |
| Unix instance lock explicit unlock + test | yes | `instance_lock.rs:33,211` |
| `Harness::executable_path`, `RunControls.execution_lease` held until child reaped (+ codex lease test, fixture) | yes | `harness/src/lib.rs:187`; `tests/codex.rs:39`; `fixtures/fake-codex.sh:190` |
| **Devin model probe: on timeout reap the child (`shutdown_child` 250 ms) before releasing the lease; test `timed_out_catalog_probe_is_reaped_before_returning`** | **no** | HEAD `crates/harness/src/acp/devin_models.rs:95` still `tokio::time::timeout(timeout, cmd.output())` (drop-kill, no reap); upstream `devin_models.rs:153,734` (added by 35a9139a1 itself) |
| pi-acp `executable_path` tests | N/A | Roboco runs Pi natively (df0cd2980) |
| Desktop update card (compact/expanded, Escape collapses, rail, actions) | yes | `crates/ui/src/shell/harness_updates.rs`; `shell.rs:9383,9920` |
| Multi-device update inventory | adapted | ADR 0004: per-engine card; toast "Agent update: {error}" (`shell/harness_updates.rs:737`) |
| New-release banner, deduped, reserved notify target | yes | `shell.rs:2521-2558`; `notify.rs:32` |
| Settings → Notifications "Agent updates" toggle | yes | `settings/notifications.rs:318-332` |
| Settings → Agents "Ignore version"/"Disable update monitoring" copy | superseded | removed upstream (9d3cc8b2d, 54954e4d2); decline recorded wave3 ticket 03 |
| `composer_surface_border`, `HARNESS_UPDATES_V1` capability | yes | `theme.rs:1030`; web `methods.ts` |
| Windows ConPTY test infra (launcher `process_id`, `OpenProcess`/`GetExitCodeProcess` liveness, `wait_for_shell_prompt`) | no (test-only) | HEAD `crates/engine/src/terminals.rs:871,931` still types `PID:{0}:END` into PSReadLine and spawns PowerShell per poll |
| UI tests on `new_current_thread` (`spaces.rs`) | no (test-only, trivial) | 0 hits |
| iOS keyboard hunks, `cursor-compatibility.yml` | skip | ios / upstream-ci |

Later refinements: 9d3cc8b2d present; d1dc29f6c PARTIAL (recorded); 651aa7a95 present (`aad9524e9`); df0cd2980 PARTIAL
(recorded); 54954e4d2 present (`a3aa1d696`); dd0c36fa3 present; 987bb2f87 present (`b21e9f39b`, Muse DECLINED).

## dd0c36fa3 — Claude logins that stay signed in, sturdier agent CLI updates (#974)
Port commit: `a59137538` (wave-9 #05, `.scratch/upstream-ports-2026-10-wave9/issues/05-claude-refresh-lock.md`). 77 names checked; 74 present; the other 3 are adapted or refactored.

| slice | present? | evidence |
|---|---|---|
| `claude/refresh_lock.rs` (LIVE_WINDOW, TOUCH_EVERY, SETTLE_MAX, settle, takeover, 7 tests) | yes | matches upstream line for line after renames |
| `config_dir_for` per-run env | adapted | no `RunRequest.env`; global `config_dir()` used (recorded in port) |
| Claude child: retire probes after settle, stdin EOF waits, SIGTERM/KILL settle, final guard | yes | `crates/harness/src/claude/mod.rs:554,628,1351,1495,1881-1887,1964` |
| `tests/claude_refresh_lock.rs` | yes | `a_live_holder_is_waited_out_and_a_dead_one_is_not` |
| agent_accounts locks, `claude_login_is_older`, `claude_slot_is_not_live`, `refreshTokenExpiresAt`, spent-login refusal + 6 tests | yes | `crates/engine/src/agent_accounts.rs` (Keychain arms cfg(macos)) |
| Version re-read under exclusive lease; standalone installer refuses older `current` | yes | `harness_updates.rs:1355,1951` |
| Binary watch 5 min, recheck when rows open | yes | `harness_updates.rs`; `rpc.rs:2406` |
| One updater per installation owner | yes | `harness_updates.rs:132` |
| Failure kind check/install, 1 MiB output cap, tail summary | yes | `harness_updates.rs:2675-2714`; web `HarnessUpdateFailureKind.ts` |
| Desktop "Update failed" / "Couldn't check for updates" / "Retry update" | yes | `settings/harnesses.rs:115,177,1512`; `shell/harness_updates.rs:77-86` |
| Parked session replaced when CLI binary changed | yes | `sessions.rs:201,236,763,1050` |
| Version probe per-identity gate, 5 s timeout, 60 s failure TTL | yes (refactored) | `crates/harness/src/executable.rs:173-346` |
| Codex npm repair timeouts / owning prefix | yes | `codex/npm_repair.rs:109,162,235` |
| Pre-release ordering | yes | test `a_release_outranks_its_prereleases_but_not_build_suffixes` |

Later refinements: 987bb2f87 present; f725384bd present (`fc016cf4e`); 2e151ca65 launcher hardening is the
known #977 gap (not this commit). Roboco-only web parity note: the web app does not distinguish install failures (see Gaps).

## 8646bde07 — Cut startup memory 700→200 MB (#818)
Port commits: `f6b6fdf2a`, `024f1bf60`, `83c15ed71`, `b67b502eb`, `1df33a216`. All 175 non-client fns were checked; the only misses are in the table.

| slice | present? | evidence |
|---|---|---|
| Registry `coalesce_ops` (LWW fold, 16 KiB cap, in-flight untouched) | yes | `crates/doc/src/registry.rs:310,824-901` |
| `set_local_only`, compaction on load, 18 tests | yes | `registry.rs:523,627`; `registry/tests.rs` |
| Engine opens registry local-only | yes (hardcoded) | `workspace_host.rs` (all profiles local) |
| Store `journal_size_limit` 64 MiB, WAL truncate at open | yes | `crates/sync/src/store.rs:112,973` |
| Thresholded VACUUM after boot save | yes | `store.rs:567,661,682`; `workspace_host.rs:193-197` |
| `persist_snapshot` size + warn | yes | `workspace_host.rs:190,856` |
| Run journal backward read (`RevLines`, torn tail) | yes | `run_journal.rs:289,372,392` |
| `journal_harness_session` newest-first early stop | yes | `b67b502eb` |
| FsWatchHub shared watcher, batch race fix, rescan on replace | yes | `crates/engine/src/fs_watch.rs`; `diff_sync.rs:56,773` |
| diff_sync skips unviewed archived chats, `retain_chat` 30 s linger | yes | `diff_sync.rs:168-239,394`; `rpc.rs:2123` |
| Discards serialized on one lock | yes | `diff_sync.rs:459+` |
| UI `apply_*` changed-ness, notify only on visible change | yes | `state.rs:3163`, tests `:5381-5441` |
| UI clock-deadline redraws (`shell_clock_wake`, `ClockRedraw`) | yes | `shell.rs:1628-1660,2195`; `composer.rs:12906` |
| Test `local_profile_compacts_a_bloated_registry_and_never_queues` | yes (renamed) | `workspace_host.rs:1138` |
| Test `boot_compaction_reclaims_the_freed_store_pages` | **no** (test-only) | only store-level reclaim tests exist |
| `apply_auth*`, synced-profile test, crates/client replica; `/private/var` fix | n/a | cloud / macOS |

Later refinements: none to the #818 mechanisms (run_journal changes in 2e151ca65 belong to #977).

## 037f4c10d — Turn snapshots out of the checkout's object DB (#820)
Port commit: `81a030608` (+ `b67b502eb`).

| slice | present? | evidence |
|---|---|---|
| `turn_snapshot.rs` private object dir + alternates | yes | byte-identical apart from one doc line |
| Budgets (8 MiB file, 10k files / 256 MiB dir), largest units out first | yes | same file |
| 120 s `SNAPSHOT_TIMEOUT`, `kill_on_drop` | yes | `turn_snapshot.rs:271,385`; `diff_sync.rs:1005` |
| Stale stores swept at start | yes | `diff_sync.rs:297` |
| `snapshot_tree_onto` layering | yes | `diff_sync.rs:1916` |
| `note_turn_start` drops stale base | yes | `diff_sync.rs:418-450` |
| Reconcile drops gone/unviewed-archived snapshots | yes | `diff_sync.rs:598-611` |
| `capture_git_in` generic | yes | `diff_sync.rs:977,987` |
| rpc file-text `TreeSnapshot`, "no turn recorded" error | yes | `81a030608` |
| 15 unit tests | yes | `turn_snapshot.rs` tests |

Later refinements: only cc7da5c2a cloud sidecar filter (n/a).

## e799aecc1 — Remote sends stranded by mobile suspension / lost host wakes (#777)
Port commits: `74257f957` (Linux capture fixture), `1d4953d20` (mobile-client working-from-host-status).

| slice | present? | evidence |
|---|---|---|
| Durable remote-wake / viewer-delivery jobs, `hostDevice` hint, edge chat-room wakes, crates/client outbox | n/a (cloud) | Confirmed: every hunk routes through edge/chat2/device-room nudge. No `chat2_host.rs`/`sync_jobs.rs` in Roboco, and Android sends are direct engine round trips (ADR 0004) |
| Mobile session `working` only from host status / streaming tail | yes | `crates/mobile-client/src/session/composer.rs:5-8` |
| Session list: no in-flight-send → Working override | yes | `crates/mobile-client/src/workspace/view.rs:620-626` |
| iPhone "Sending to host…" banner | partial (copy) | Behaviour present (`StatusBanner.kt:124-125`, `Banner.Sending`); copy is "Sending…" (`StatusPill.kt:111`); no ticket records the choice |
| iOS background task "Finish message delivery" | n/a | iOS mechanism; Roboco's `on_background` keeps feed/call (`crates/mobile-client/src/lib.rs:771`) |
| Linux `capture_frame` (refresh/draw/`present_if_needed`/50 ms) + `wait_for_visible_window` | yes | `crates/ui/examples/browser-fixture.rs:69-90`; `browser-fixture/linux.rs:10` |
| **`crates/ui/Cargo.toml` `browser-fixture = ["gpui/bench"]`** | **no** | HEAD `crates/ui/Cargo.toml:15` `browser-fixture = []`, yet HEAD `browser-fixture.rs:81` calls `present_if_needed` (bench-gated in zui). `.scratch/agent-previews/issues/01-port-origin-stamping.md:44` already records the example failing to build ("present_if_needed gone") — this is the cause |
| `transcript_links.rs` uses `capture_frame` on Linux | **no** | HEAD `crates/ui/examples/browser-fixture/transcript_links.rs:51-61` old on_next_frame + 250 ms path |
| `scripts/test-linux-browser.sh` waits 15 s (`seq 1 150`) | **no** | HEAD lines 47, 83 `seq 1 100` |

Later refinements: ec0897feb (#1066, sidebar row adopts pending send) already MISSING in `part-07.md`;
c31124aa9 (#1039 WPE) separate feature, also needs `gpui/bench`.

## c72c66d8a — File tree context actions and drag-and-drop moves (#514)
Port commits: `a6322f2aa` (+#612), `f08bdd585` (#774).

| slice | present? | evidence |
|---|---|---|
| Proto mutation contracts, `MOVE_/DELETE_WORKSPACE_ENTRY` | yes | engine rpc arms; 105/105 fns minus relay test |
| Per-checkout mutation gate; saves re-check target | yes | `crates/engine/src/workspace_files.rs` |
| No-replace moves: Linux `RENAME_NOREPLACE`, Windows `MoveFileExW` w/o replace, case-only rename staging + rollback | yes | `crates/engine/src/workspace_files/mutations.rs:460,470,483-497` |
| Windows canonical paths (subst/junction), reparse points refused, `.git`/root protected | yes | `mutations.rs:248-252` + tests |
| Delete with recursion consent, partial-failure resync, op-id events | yes | port |
| Context menu (Add to chat, Copy path in host format, Rename, red Delete, icons, keyboard skips disabled) | yes | `crates/ui/src/files/context_menu.rs:203-216`; all 36 UI literals present |
| Inline rename (F2/Enter/Esc, tabs/editor follow) | yes | `files/rename.rs`, `shell/file_mutations.rs` |
| Delete confirmation copy, Cancel preselected | yes | strings present |
| DnD targets, 650 ms hover-expand, 16 ms edge autoscroll, cancel on Esc/leave/switch | yes | `crates/ui/src/files/drag.rs:327` |
| Shared chat dropzone with origin checks | yes | `shell/chat_dropzone.rs` + tests |
| All tests incl. Windows row-jitter | yes | fns present |
| Relay forwarding test | excluded | ADR 0004 |

Later refinements: 2c2963aae (#774) present; 2d40f8df6 (#821) present (#966 stuck-band follow-up already MISSING in part-06);
b65b47536 present; cc7da5c2a cloud.

## b25dd404c — Color file tree entries with Git status (#340)
Port commits: `c4113076d`, `db874e7ce` (web).

| slice | present? | evidence |
|---|---|---|
| Porcelain parse, literal renames, symlink-safe listing | yes | `crates/engine/src/diff_sync/git_status.rs` identical after renames |
| Revisioned cache, `WatchWorkspaceGitStatus` scoped to checkout | yes | `diff_sync.rs`, rpc |
| Proto `GitFileState`/`GitFileStatus`/`CheckoutGitStatus`/frame | yes | `proto/src/entities.rs` |
| Theme-token colours, folder aggregation, partial ≠ clean | yes | `crates/ui/src/files/git_status.rs:26-54,483` |
| Frame accepted only on engine/target/checkout match | yes | `files_panel.rs` (+ Roboco paired-engine extension) |
| Tests | yes | 33/34 |
| Relay test | excluded | ADR 0004 |

Later refinements: 03b67beb8 (#508) present (`files/git_status.rs:308`); cc7da5c2a cloud.

## 77a3c0ff1 — New project: type a folder's path, ⇥ completion (#998)
Port commits: `a39f89e37`, `77a8e30a6`.

| slice | present? | evidence |
|---|---|---|
| Ctrl+L / pen / click empty trail end opens field on current folder (`~`-spelled, selected) | yes | `crates/ui/src/shell/spaces.rs:7227-7233` |
| ⇥ ghost completion | yes | `spaces.rs:7006,7048` |
| ↵ open, Ctrl+↵ open + add, `edit.adding \|= add` | yes | `spaces.rs:7065-7150` |
| Home answers last (join) | yes | same block |
| Esc returns trail, keeps New project open (through Shell Esc capture) | yes | `shell.rs:9293`; `spaces.rs:6901` |
| Tab/Shift+Tab stay in palette | yes | `spaces.rs:7243-7246,7271-7275` |
| Pasted folder becomes path | yes | test `spaces.rs:2356` |
| Error copy ("No folder at …", "… isn't a folder", "Type the folder's path", access copy) | yes | `spaces.rs:2522` etc. |
| Placeholders `C:\code` / `~/code` | yes | `spaces.rs:6819-6821` |
| Windows path grammar (`\dir`, `D:dir`, `//nas/share`, `\\?\`) | yes | `crates/ui/src/pickers.rs:352-364` + test |
| Relative paths from loading folder | yes | test `spaces.rs:2403` |
| **Windows path identity via `folder_path_key` (drive-letter case fold, `\\?\` = plain) in `same_folder_path`/`path_under`/`tilde_path`** | **no** | HEAD `pickers.rs:677` trims slashes only; `spaces.rs:2546-2564` lexical; Roboco test `spaces.rs:2481` pins `c:\Users\ana\code` as NOT under `C:\Users\ana`. (`folder_path_key` arrived upstream with #856 `3487b507f`, itself missing; this slice can be ported alone) |
| `release_home_folder_search` | n/a | depends on #856 home search |
| Cloud arms | skip | cloud |
| `new-project-path-fixture` example + feature | yes | `crates/ui/examples/new-project-path-fixture.rs`; `Cargo.toml:13,45` |
| Tests `tab_keys_keep_the_keyboard_in_the_palette`, `enter_during_a_mod_enter_open_still_adds_the_project`, `a_typed_path_lands_under_home_when_home_answers_last` | no (test-only) | behaviour present, tests not carried |

Later refinements: daf047b7f (#1048 "Show hidden folders" tooltip) present (`spaces.rs:7757`); others unrelated.

## e74f275e6 — Floating menu scrollbars, Linux CSD chrome, stacked notices (#364)
Port commit: `3edfe53b2` (+ web parity).

| slice | present? | evidence |
|---|---|---|
| 65 new fns | 64 by name, 1 refactored | `on_font_list_hover` → `settings/appearance.rs:1916` `set_list_hovered` (upstream did the same in 79b27ed68) |
| Scrollbar linger 1400 / fade 260 / 16 ms, hover-freeze, drag ghost, tests | yes | `crates/ui/src/popover.rs:1487,1489` |
| Rails on all settings pages | yes | `settings/*.rs`; `appshots.rs:249-325` |
| Rails on mention/slash popups, pickers, spaces menu, terminal panel, file tree, font chooser | yes | `composer.rs`, `pickers.rs`, `shell/spaces.rs`, `terminal/panel.rs`, `files/tree.rs` |
| Linux CSD resize strips (EDGE 6 / CORNER 14, tiling gate), corner radius 10, hairline trim | yes | `crates/ui/src/shell.rs:394,6916,6926,6957,6969` (byte-identical) |
| Notice chip (header, wrapping body, copy button) | yes | `crates/ui/src/notice.rs:32-122` |
| Copy strings ("No devices match.", "You", "No refs found.", "Restore defaults") | yes | `pickers.rs:3013,3074,4336`; `settings/shortcuts.rs:1048` |

Later refinements: f30a8641b (#967) present (`notice.rs` = upstream); 26e2b0dd3 (#628) present.

## 1f7b74a7f — Todo panel (#707)
Port commit: `6871828a2`.

| slice | present? | evidence |
|---|---|---|
| 55 new fns | yes | all present |
| `TodoStatus` serde-defaulted, unknown → derive from `done` | yes | `crates/doc/src/schema.rs:2063`; `proto/src/agent.rs` |
| In-progress mapping acp/claude/cursor/opencode/codex | yes | grep per harness |
| Codex `turn/plan/updated` | yes | `codex/mod.rs:1677`; `codex/normalize.rs:490,1034` |
| `ROBOCO_MOCK_TODO` | yes | `harness/src/mock.rs:534-538` |
| Panel (n/m header, glyphs, 3-item window, "N earlier/later" folds, per-chat open, dismiss when idle) | yes | `crates/ui/src/todo_panel.rs` (12-line rebrand diff vs upstream) |
| Transcript `[~]` | yes | `transcript.rs:887,17013` |
| Docs + screenshots | yes | `docs/todo-panel.md` |
| client demo / edge | n/a | cloud |

Later refinements: b65b47536 present (`todo_panel.rs:267,312`).

## 01832f2ca — MCP standalone sessions on device/project (#706)
Port commit: `119724896` (wave4 ticket 31).

| slice | present? | evidence |
|---|---|---|
| `kind` chat/side on create_chat(s), legacy default, effective kind reported | yes | `crates/roboco-mcp/src/tools.rs:133` |
| Guards before write (side can't create; kind chat no parent) | yes | `tools.rs:781,796`; tests `:2517,3029` |
| Host-first resolution, hardened `resolve_space_in` | yes | `roboco-mcp/src/roboco.rs:418,454,632` |
| Catalog validation with device-tagged errors | yes (engine-local) | `tools.rs:855,1054` |
| Waits match by message id; completed w/o reply times out | yes | test `tools.rs:3413` |
| Send baseline kept across `wait:false` | yes | tests `:3331,:3367` |
| Server instructions text | yes | `roboco-mcp/src/jsonrpc.rs` |
| Tool/param descriptions | yes (reworded engine-local) | `tools.rs:51-146` |
| `mcp_standalone_smoke` / engine standalone test | yes | `crates/engine/tests/device_routing.rs:100,260` |
| `targetDeviceId` remote catalog/transcript, two-engine tests | excluded | ADR 0004; ADR 0011 (`docs/adr/0011-rooms.md:40`) rejects on-behalf-of forwarding |

Later refinements:
- **cc7da5c2a (#966) connected-aware delegation — missing.** Upstream `HarnessInfo.signed_in` (from `ListAgentAccounts`), `connected()`; `list_harnesses` returns `connected`/`signedIn`; `default_harness` prefers connected claude-code → any connected → available. HEAD `tools.rs:1668-1677` picks first *available*; no `signed_in`/`connected` in `crates/roboco-mcp`. Not listed on the cc7da5c2a row in part files.
- **cc7da5c2a wait outcome `Interrupted` + stale-liveness guard — missing.** HEAD `roboco.rs:97-106` has Completed/AwaitingInput/Errored/TimedOut only; proto lacks `last_turn_outcome`/`session_stale`.
- 4a5fb8b44 (#819) full origin id, 1bc74d5da (#876) scratch cwd — missing, already recorded on their rows.

## c87daf244 — fix(cursor): recover abandoned runs, stabilize model discovery
Port commit: `1f10d3841`.

| slice | present? | evidence |
|---|---|---|
| Credential-scoped catalog (last-good, single-flight, 3 retries, auth/rate-limit short-circuit, 20 s, `credential_context`) | yes | identical (later moved to shared `catalog.rs`) |
| `cursor/state.rs` OS lease, `ROBOCO_CURSOR_STATE_DIR`, unix 0o700/0o600 | yes | `state.rs:53,68,98`; `cursor/mod.rs:395,403` |
| shim `claimStore` + owner PID markers, parent-PID watchdog, `recoverInterruptedRun`, bounded `exitAfterFlush`, default-variant models, closing/cancel races | yes | `crates/harness/src/cursor/shim.mjs:66,204,216,233,351,415-418` |
| **shim agent-dir marker written `{mode: 0o600}`** | **no** | HEAD `shim.mjs:195` `fs.writeFileSync(tmp, dir)` (other three state writes have 0o600) |
| `cursor/mod.rs` probe fatal frames, lease in run(), fatal-during-interrupt → Interrupted | yes | diffs vs upstream are rename / Roboco extras |
| `adapter_install.rs` immutable sha-named shim + concurrency test | yes | `adapter_install.rs:431,917` |
| Tests (cursor.rs, cursor_shim.rs stress/recovery/orphan), fixtures (exec bit 100755) | yes | `tests/cursor.rs:519,556`; `tests/cursor_shim.rs:206,277,304` |
| Probe example, stress proxy | yes | Roboco probe has more |

Later refinements: 15a31cf7a, 23bc81834, 0682bd632, 65b5c586e, 18cca528e, 568d78935, a7e505e6b, 3b2f78027 present;
1d00f2960 PARTIAL (recorded); **4a0d416cf (#1051, 30 s first-frame timeout) missing**, already MISSING in pass 1
(`tests/cursor_shim.rs:187-190` flat 5 s; this suite runs on Windows CI, `windows.yml:81`).

## a29956633 — fix(harness): retain catalogs by credential and binary context
Port commit: `b012fd082`.

| slice | present? | evidence |
|---|---|---|
| Shared `catalog.rs` (`get_with_timeout`, cooldowns, force/coalesce, reject post-login-change response) | yes | identical |
| `model_context.rs` (hashing, never logged) | yes | identical; `ROBOCO_MODEL_CONTEXT_TEST_ROOT` |
| Trait `model_context`/`fallback_models`/`model_catalog(force)` | yes | `harness/src/lib.rs:226-232` |
| ACP/Claude/Codex/Cursor/OpenCode/Pi adopt the shared catalog | yes | `acp/mod.rs:2289`, `claude/mod.rs:741`, `codex/mod.rs:664`, `cursor/mod.rs:341`, `opencode/mod.rs:452`, `pi/mod.rs:778-788` |
| opencode offline-refresh-retains assertion (dropped at port) | yes, landed since | `opencode/tests.rs:2525` |
| `tests/model_catalog.rs` | yes, minus Pi row | Pi entry came with df0cd2980 (recorded PARTIAL there) |

Later refinements: b3ec6b7e5 present (`d75157981`); e0b62774c present (`b91df89a9`).

---

## Gaps

| # | commit | slice | user-visible effect | files | effort |
|---|---|---|---|---|---|
| 1 | 01832f2ca → refinement cc7da5c2a | MCP connected-aware `default_harness` + `connected`/`signedIn` in `list_harnesses` | An agent calling `create_chat` without a harness can be handed a signed-out claude-code (or other installed provider), so the child chat fails at once. `list_harnesses` gives no login hint either. | upstream `crates/mcp/src/zeron.rs` (`HarnessInfo.signed_in`, `connected()`, ListAgentAccounts fetch), `crates/mcp/src/tools.rs` (`default_harness`, test `connected_means_installed_enabled_and_not_known_signed_out`); Roboco `crates/roboco-mcp/src/roboco.rs`, `tools.rs:1668` | S |
| 2 | 35a9139a1 | Devin model probe reaped on timeout before the execution lease is released (+ test `timed_out_catalog_probe_is_reaped_before_returning`) | A timed-out `devin models list` can outlive its lease, so a queued update can then try to replace the Devin CLI while the probe is still running. On Windows that fails with "file in use". | upstream `crates/harness/src/acp/devin_models.rs:153,734`; Roboco same file `:95` | S |
| 3 | e799aecc1 | `browser-fixture = ["gpui/bench"]` Cargo feature | The browser-fixture example does not build (it calls bench-gated `present_if_needed`), so Linux browser captures and screenshots can't be produced. This is the unexplained breakage already noted in `.scratch/agent-previews/issues/01-port-origin-stamping.md:44`. | `crates/ui/Cargo.toml:15` | S |
| 4 | e799aecc1 | `transcript_links.rs` Linux screenshot via `capture_frame` | Transcript-link captures can show a stale frame on a loaded Linux runner. | `crates/ui/examples/browser-fixture/transcript_links.rs:51-61` | S |
| 5 | e799aecc1 | `test-linux-browser.sh` window wait 15 s (`seq 1 150`) | The fixture script flakes on slow runners (10 s wait). | `scripts/test-linux-browser.sh:47,83` | S |
| 6 | 77a3c0ff1 | Windows path identity (`folder_path_key`: drive-letter case fold, `\\?\` treated as plain) | On Windows, a typed or pasted `c:\users\me\code` or `\\?\C:\…` path is not recognised as inside Home. It isn't spelled from `~`, the crumb trail roots it at the drive, and the same-folder check (which keeps the git seed) fails across spellings. | upstream `crates/ui/src/pickers.rs` `folder_path_key` (from #856 `3487b507f`); Roboco `crates/ui/src/pickers.rs:677`, `crates/ui/src/shell/spaces.rs:2546-2564`, test assertion `spaces.rs:2481` flips | S |
| 7 | 01832f2ca → refinement cc7da5c2a | `wait_for_turn` reports `interrupted`; stale liveness not taken as progress | An interrupted child turn is reported as completed, or the wait runs to timeout. A stale "working" status can also be read as progress. | upstream `crates/mcp/src/zeron.rs:111-124,570-600` + proto `last_turn_outcome`/`DoneStatus::Interrupted`/`session_stale`; Roboco `crates/roboco-mcp/src/roboco.rs:97,498`, `crates/proto` (wire → wiregen) | M |
| 8 | 3c7185b44 → refinement a75cf1a61 (already PARTIAL in pass 1) | Paste hardening: 32-attempt bound in `pasted_reference_tokens`; projectless chats still resolve `/command` and `$skill` | Pasting text full of `@`/`$`/`/` look-alikes scans every token and can stall the composer. In a projectless chat, pasted references never become chips. | Roboco `crates/ui/src/composer.rs:5577,7463-7465` | S |
| 9 | dd0c36fa3 (Roboco web parity, not upstream) | Web: install failure labelled "Update failed" with a Retry action | The web app shows every failure as "Update check failed" and offers no retry after a failed install. | `web/packages/app/src/lib/harnesses.ts:522-558`, `settings-agents.tsx` | S |
| 10 | e799aecc1 | Android pending-send copy "Sending to host…" | The phone shows "Sending…" where the iPhone app says "Sending to host…". Minor copy drift; behaviour matches. Port it or record the choice. | `apps/android/.../session/StatusPill.kt:111` + `StatusPillContentTest.kt`, `StatusPillScreenshotTest.kt` | S |
| 11 | c87daf244 | Cursor shim agent-dir marker `{mode: 0o600}` | On Linux the marker holding the store path is created 0644; the other state files are 0600. Hygiene only. | `crates/harness/src/cursor/shim.mjs:195` | S |
| 12 | c87daf244 → refinement 4a0d416cf (already MISSING in pass 1) | cursor_shim first frame gets 30 s | Windows CI flakes on a cold `node` start (flat 5 s). Test-only. | `crates/harness/tests/cursor_shim.rs:187-190` | S |
| 13 | 35a9139a1 | Windows ConPTY test infra (launcher pid, native liveness, `wait_for_shell_prompt`) | Flaky or slow Windows terminal tests. Test-only; needs adapting to Roboco's `terminals/windows.rs`. | `crates/engine/src/terminals.rs:871-935` | S |
| 14 | 3c7185b44 | Doc settings path | `docs/harness-skill-completion.md:48` says "Settings → Agents"; it should say Settings → Shortcuts → Composer completion. | that file | S |
| 15 | tests only | Unported regression tests | No user effect. Missing: `boot_compaction_reclaims_the_freed_store_pages` (8646bde07); the three #998 tests (Tab trap, ↵ during Ctrl+↵, home answers last; 77a3c0ff1); the Pi row in `tests/model_catalog.rs` (df0cd2980); `new_current_thread` in `spaces.rs` tests (35a9139a1). | respective test modules | S |

Not gaps (intentional): multi-device update card and MCP `targetDeviceId` (ADR 0004 / ADR 0011 line 40);
`config_dir_for` (no `RunRequest.env`); completion rows staying on the Shortcuts page (roboco-log "decision 18", no `.scratch` record found).
