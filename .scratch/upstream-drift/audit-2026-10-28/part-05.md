# Part 05 — chunk-05 (`d1dc29f6c` … `3d4bfd11b`, 2026-09-29 → 2026-10-05, 69 commits)

Summary: 69 commits — PORTED 44, PARTIAL 3, MISSING 7, DECLINED 0, SUPERSEDED 0, SKIP 15, N/A 0

Audited against `HEAD` = `85be4485a` (feature/fleet-follow). Every PORTED row was checked against
a distinctive symbol, test name or string in `git grep HEAD`, not only against the port commit's
message. Window = waves 4–6 (`.scratch/upstream-ports-2026-10-wave4|5|6`) + drift notes
`2026-10-02.md` / `2026-10-05.md`.

Headline: the wave plans ported this window almost completely. The real gaps are:
(1) the parts of `dbb639be` (#799) that wave-5 deferred. The 10-07/10-09 drift notes called these
"unmerged branch follow-ups", but they are inside the merged squash commit. One of them is a live
regression: the per-project `+` is gone for every git project.
(2) Antigravity's Windows sign-in-browser suppression.
(3) Pi question prefill/multiline.
(4) The upstream Linux/Windows CI speed-ups, which the wave-4 spec scoped out as a group. That was
a scope call, not a recorded user decline.

## Table
| SHA/PR | date | upstream PR | title | area | class | evidence | effort | notes |
|---|---|---|---|---|---|---|---|---|
| d1dc29f6c | 2026-09-29 | #617 | fix: restore antigravity detection, updates and sign-in suppression | harness | PARTIAL | Roboco `bf9a1011c`; `ensure_noop_browser` `crates/engine/src/agent_accounts.rs:4600`; Build-label parsing `crates/engine/src/harness_updates.rs:3021`; Windows `noop_browser()` returns Err `crates/harness/src/acp/mod.rs:3308` | S | Windows slice missing. Upstream adds a `--noop-browser` early-exit in `apps/zeron/src/main.rs` and launches the app exe as BROWSER on Windows. Roboco skipped it (ticket 09 "Deliberate deviations"; ticket-level, not a user decision), so on Windows a stale Google login still opens a browser behind the user. Prompt watchers still stop the run. |
| 11c910894 | 2026-09-29 | #627 | Linux: install a launcher entry and icon with both installers | updater/install | PORTED | `55fe4873f`; `install_desktop_entry` `scripts/package-linux.sh:107`; `scripts/test-linux-desktop-entry.sh`; `StartupNotify=true` in `dist/roboco.desktop` | — | Excluded on purpose: the edge curl-installer copy and `linux-installer.yml`. |
| f843f1cec | 2026-09-29 | #638 | Explorer: list running subagents first, longest-running on top | ui-desktop | PORTED | `03173c883`; test `running_subagents_lead_longest_running_first` `crates/ui/src/shell/session_lanes.rs:867` | — | Web twin also ported. |
| aaeede8b4 | 2026-09-29 | #637 | Engine: idle reaper no longer kills a parked session's live subagents | engine | PORTED | `36222bb25`; `ROBOCO_SESSION_IDLE_MS` `crates/engine/src/sessions.rs:5006`; `crates/engine/tests/subagent_idle_reap.rs` | — | |
| bc1368670 | 2026-09-29 | #633 | Transcript: show file names for unlabeled file links | ui-desktop | PORTED | `cc84b5f9a`; `span_labels` + test `a_nested_path_span_shows_its_file_name_and_keeps_the_path_for_copy` `crates/ui/src/markdown/inline_code_links.rs:308,571` | — | Ported together with #606. |
| c74978abf | 2026-09-29 | #651 | fix: exclude child chats from the command palette | ui-desktop | PORTED | `fa5bb9cc3`; `.filter(\|chat\| chat.parent_chat_id.is_none())` `crates/ui/src/shell/command_palette.rs:150`; web `lib/command-palette.ts:149` | — | |
| 546ecb685 | 2026-09-29 | #641 | Sidebar: keep Ctrl+N jump hints on one line in compact rows | ui-desktop | PORTED | `f779045ed`; `COMPACT_JUMP_HINT_WIDTH` `crates/ui/src/shell.rs:1125,7991` | — | |
| 4a7ef2cca | 2026-09-29 | #635 | Engine: trim glibc malloc arenas on Linux | engine | PORTED | `f0bd0afd1`; `spawn_malloc_trimmer` `apps/roboco/src/main.rs:113` | — | Linux-gnu cfg. |
| 66226055f | 2026-09-29 | #650 | Hide queue attachment filenames and fix hover overflow | ui-desktop | PORTED | `09f8892b8`; `queue_hidden_attachments_label` `crates/ui/src/queue.rs:277` | — | |
| a86f0587d | 2026-09-30 | #634 | OpenCode: attribute and clear 2.x context usage correctly | harness | PORTED | `a93cc1935`; `session.step.started` model attribution `crates/harness/src/opencode/mod.rs:4357` | — | |
| 92be0f262 | 2026-09-29 | #556 | Fix transcript selection leaking through popups | ui-desktop | PORTED | `33b33a7ce`; `copies_transcript_selection` `crates/ui/src/composer.rs:1932`; `crates/ui/src/modal_selection_tests.rs` | — | |
| e9e00e212 | 2026-09-30 | #636 | iOS: thinking renders as markdown in the tool-group accordion | mobile-core / android-parity | PORTED | `b07a14767` (roboco-mobile layout "as of … e9e00e21"); `THOUGHT_BYTES` `crates/mobile/src/layout/tools.rs:374`; tests `thinking_renders_styled_markdown_not_markers`, `huge_single_paragraph_thought_stops_reshaping` `crates/mobile/src/layout/tests.rs:290,533` | — | The diff only touches `crates/mobile`, so Android inherits it through the core. |
| 54b499490 | 2026-09-30 | #642 | Appearance: reduce motion, and pause animations in background | ui-desktop | PORTED | `dfca8c02a`; `SPI_GETCLIENTAREAANIMATION` `crates/ui/src/motion.rs:982` | — | The macOS NSWorkspace read was dropped (macOS-only). |
| 26e2b0dd3 | 2026-09-29 | #628 | UI: add hover tooltips to icon-only buttons | ui-desktop | PORTED | `9d91ded24` (all 16 files); e.g. "Close tab" `crates/ui/src/shell.rs:9541` | — | The accounts "more" menu went with cloud code, so the tooltip went to Forget instead. |
| df0cd2980 | 2026-09-29 | #630 | Replace Pi ACP adapter with native RPC integration | harness | PARTIAL | Production driver: user decision in `812cf4fa4` / ticket `wave4/issues/30`. Adopted slices: 120s startup `37c485cb3`, dropped-resolver error `5aec52ef0`. Wizard draft guards present (`crates/ui/src/composer.rs:8541`). `UserInputQuestion` has no `prefill`/`multiline` (no `git grep` hit in `crates/proto/src`). Pi `editor` dialog maps without prefill: `crates/harness/src/pi/normalize.rs:387` | M | Roboco's own native Pi driver stays, by decision. Missing portable slice: question `prefill` + `multiline` (ticket 30 finding #12, "not adopted; wave-5 candidate", never picked up). Without it Pi `editor`/`input` dialogs open blank and multiline answers get trimmed. |
| ed3b1aae4 | 2026-09-29 | — | Bump version to v0.2.99 | other | SKIP:version-bump | Cargo.toml/lock only | — | |
| 4aceec160 | 2026-09-30 | #665 | Fix file tree edge fades at scroll boundaries | files/editor | PORTED | `17de869b3`; `tree_scroll_overflow` `crates/ui/src/files/tree.rs:117` | — | |
| 6641095b5 | 2026-09-30 | #681 | Fix Markdown drag selection across table columns | ui-desktop | PORTED | `33b33a7ce`; `registry_point` `crates/ui/src/markdown/render.rs:1570` | — | |
| 0fab030fc | 2026-09-30 | #669 | Question panel: paint an opaque background | ui-desktop | PORTED | `2213db68e`; `crate::frost::frosted(COMPOSER_RADIUS, 16.0, panel)` `crates/ui/src/composer.rs:10041` | — | Final state of the PR (frost). |
| 651aa7a95 | 2026-09-30 | #661 | Providers: update Homebrew-installed CLIs with brew | engine | PORTED | `aad9524e9`; test `homebrew_layout_selects_cask_or_formula_upgrade` `crates/engine/src/harness_updates.rs:4143` | — | Mostly helps Linuxbrew users. |
| cc8b147a3 | 2026-09-30 | #676 | Fix subagent lifecycle identity and restart recovery | engine / harness | PORTED | `711b6ed24`; `agent_tool_spawns` `crates/harness/src/claude/normalize.rs:319` | — | |
| 626bccc85 | 2026-10-01 | #660 | Appearance: add background positioning and zoom | ui-desktop | PORTED | `8c9972f27`; `NewThreadBackgroundAdjustment` (`crates/ui/src/new_thread_background_effects.rs:2`) | — | |
| 710b06ff8 | 2026-09-30 | — | CI: seed the Rust cache from main and stop cancelling main runs | ci | MISSING | Roboco: no `save-if`, `cancel-in-progress: true` unconditional (`windows.yml:42`, `ui-tests.yml:41`, `preview-tests.yml:12`); `windows.yml` is pull_request + dispatch only | S | Wave-4 spec put upstream CI out of scope (spec.md:269-271, a scope call, not a user decline). The fix applies by intent inside our existing workflows. `85be4485a` shows the symptom: ui-tests main runs get cancelled and caches are cold. |
| f4abe69b8 | 2026-09-30 | — | CI: run Windows tests in three parallel jobs on a cheaper profile | ci | MISSING | Roboco `windows.yml`: one `tests` job, `timeout-minutes: 90` | M | Upstream splits into ui-tests / crate-tests / app jobs via a `windows-ci-setup` composite action. Roboco already uses nextest + `-P ci` on Windows, so this is a job split. |
| 52a3481dc | 2026-09-30 | — | CI: add a macOS workflow with one compile and parallel jobs | ci | SKIP:upstream-ci | macos.yml + run-macos-fixture.sh | — | |
| c286d118b | 2026-09-30 | — | CI: remove the macOS jobs from ui-tests and preview-tests | ci | SKIP:upstream-ci | macOS jobs only (Roboco never had them) | — | |
| 366b3c9e2 | 2026-09-30 | #632 | Fix drag-select failing when several transcripts share a frame | ui-desktop | PORTED | `33b33a7ce`; `TwoPaneSelectionHarness` `crates/ui/src/markdown/render.rs:2910` | — | |
| a2a593a5d | 2026-09-30 | — | Bump version to v0.2.100 | other | SKIP:version-bump | | — | |
| c168ba5b4 | 2026-09-30 | — | windows: run the crate suites as two parallel jobs | ci | MISSING | Roboco `windows.yml` single job | S | Port together with f4abe69b. |
| fd3b2c066 | 2026-09-30 | — | footer | other | SKIP:landing | `apps/landing/public/index.html` only | — | |
| 9cb1de50f | 2026-09-30 | — | CI: add Linux test helpers (setup action, nextest, apt with retries) | ci | MISSING | Roboco ubuntu jobs use plain `cargo test` (`ui-tests.yml:54-80,233`); no `scripts/ci/`, no apt-retry helper | M | Groundwork for 878307a3. Roboco already has `.config/nextest.toml`. |
| 878307a3d | 2026-09-30 | — | CI: run Linux tests with nextest on a dev profile and one shared build | ci | MISSING | `ui-tests.yml:233` still `cargo test --release … -p roboco-ui --lib`, 45-min job (`85be4485a`) | M | Highest-value CI item: going from release to a dev profile with nextest and one shared build targets exactly the 27–30 min ui-tests runs. cursor-compatibility/linux-installer hunks: N/A. |
| c72c66d8a | 2026-10-01 | #514 | Add file tree context actions and drag-and-drop moves | files/editor | PORTED | `a6322f2aa`; `RENAME_NOREPLACE` `crates/engine/src/workspace_files/mutations.rs:460` | — | The relay-forwarding test was excluded (ADR 0004). |
| b3d7f48b5 | 2026-10-01 | #682 | Fix command and skill labels in the message queue | ui-desktop | PORTED | `0f0878891`; `queue_row_text` `crates/ui/src/queue.rs:246` | — | |
| 42926c802 | 2026-10-01 | #587 | Right panel tabs: close button on the right | ui-desktop | PORTED | `9358c754e`; test `right_tab_close_sits_at_the_trailing_edge` `crates/ui/src/shell.rs:15917` | — | |
| a73fa8fe4 | 2026-10-01 | #471 | Add optional compact model picker and effort controls | ui-desktop | PORTED | `74abedd5a`; `reasoning_by_model` `crates/ui/src/settings/composer.rs:56`; `compact_model_picker` setting | — | Hover-card `configured_in_place`/`card_order` hunks don't apply (Roboco's tree never got hover-card settings). |
| b42fc2b8f | 2026-10-01 | — | Bump version to v0.2.101 | other | SKIP:version-bump | | — | |
| 5d5d93599 | 2026-10-01 | — | CI: cover cursor paths in ui-tests, verify nextest after cache restore, nightly release-profile run | ci | MISSING | No release-profile nightly; ui-tests paths do not include cursor-sdk scripts | S | Only matters once 878307a3 moves Linux to the dev profile. The nightly is a new workflow (wave-4 "7 policy workflows" rule). Could fold into ui-tests as a schedule trigger instead. |
| 7aed43b47 | 2026-10-01 | — | CI(macos): run CoreText tests on macOS, drop /etc/hosts patch, harden nextest download | ci | SKIP:upstream-ci | macos.yml + run-macos-fixture.sh only | — | |
| a5c00e0b5 | 2026-10-01 | — | CI(macos): run the macOS jobs on macos-latest, as before | ci | SKIP:upstream-ci | | — | |
| 27480d99b | 2026-10-01 | #686 | OpenCode 2.x: fix instant "Run failed" (cold version probe), show start failures | harness | PORTED | `a93cc1935`; `OPENCODE_PASSWORD` `crates/harness/src/opencode/mod.rs:748` | — | |
| 80b946b15 | 2026-10-02 | #591 | Add opt-in on-device dictation to the desktop composer | ui-desktop | PORTED | `fc58ea330` + `cf94f4155`; `crates/voice` (roboco-voice) | — | macOS permission/packaging + voice CI skipped (macos-only). |
| ae4181f51 | 2026-10-02 | #721 | Fix the compact panel's model name; test settings survive navigation | ui-desktop | PORTED | `74abedd5a`; `enum ModelName` `crates/ui/src/pickers.rs:725` | — | |
| 64ad6f6ef | 2026-10-02 | — | Bump version to v0.2.102 | other | SKIP:version-bump | | — | |
| 9782693b8 | 2026-10-02 | #740 | Scope composer mic and attach hover fades per instance | ui-desktop | PORTED | `fc58ea330`; `composer-dictation-{id}` `crates/ui/src/composer.rs:10195` | — | |
| 01832f2ca | 2026-10-02 | #706 | MCP: create standalone sessions on a selected device and project | engine (mcp) | PORTED | `119724896`; `kind` enum `crates/roboco-mcp/src/tools.rs:133`; `crates/engine/tests/device_routing.rs` | — | Remote-catalog branches cut down to engine-local (ADR 0004). |
| c14d45796 | 2026-10-02 | #744 | Let the composer's session branch label use free width and fade on overflow | ui-desktop | PORTED | `9ab5a364d`; `sidebar_faded_label` `crates/ui/src/pickers.rs:3793` | — | |
| 542febc10 | 2026-10-02 | #745 | Toggle fast mode with F in the compact model picker | ui-desktop | PORTED | `9ab5a364d`; `"f" if !…modified()` `crates/ui/src/pickers/compact.rs:847` | — | |
| 1f7b74a7f | 2026-10-02 | #707 | Todo: dedicated panel for the agent's checklist | ui-desktop / proto / harness | PORTED | `6871828a2`; `crates/ui/src/todo_panel.rs` (1148 lines); `TodoStatus` `crates/doc/src/schema.rs:2063` | — | edge/client hunks are cloud. |
| 55f5ed4c8 | 2026-10-02 | — | CI(macos): give macos-native the old job's 45 minute limit | ci | SKIP:upstream-ci | | — | |
| e94c49ad8 | 2026-10-02 | #749 | Fix compact model picker browsing across providers | ui-desktop | PORTED | `9ab5a364d`; `ModelRail::All` `crates/ui/src/pickers/compact.rs:334` | — | |
| 56dc5ff3a | 2026-10-02 | — | CI: pin every action to a commit SHA, keep pins current with Dependabot | ci | MISSING | Roboco workflows use tags (`actions/checkout@v4`, `Swatinem/rust-cache@v2`, `taiki-e/install-action@v2`); no `.github/dependabot.yml` | S | Drift 2026-10-02 called this "worth adopting independently someday". It matters because `release.yml` holds the Android signing secrets. |
| c78bb1c12 | 2026-10-02 | (#751) | Rename threads and side chats inline instead of in a dialog | ui-desktop | PORTED | `930e1d13f`; `crates/ui/src/shell/chat_rename_tests.rs` (399 lines) | — | |
| 46bedeca0 | 2026-10-02 | (#751) | Test the inline rename in both full and compact sidebar rows | ui-desktop | PORTED | `930e1d13f` ("all 10 upstream tests adapted") | — | |
| 2a884777a | 2026-10-02 | #757 | Fix Shift+Backspace in composer and palette inputs | ui-desktop | PORTED | `a8934e11c`; `KeyBinding::new("shift-backspace", …)` `crates/ui/src/composer.rs:1597,1717` | — | |
| 612df5126 | 2026-10-02 | #763 | fix(preview): leave authentication callback listeners unprobed | browser/previews | PORTED | `fe6a2f732`; `is_authentication_command` `crates/preview/src/discovery.rs:47` | — | |
| e96eccb1a | 2026-10-02 | #754 | Keep activity loaders pulsing with system reduced motion | ui-desktop | PORTED | `82f05b155`; `activity_pulse` `crates/ui/src/motion.rs:174` | — | |
| edac0d7d9 | 2026-10-03 | #727 | Fix Windows drive paths in the project folder picker | ui-desktop | PORTED | `22ba72f8a`; test `typed_path_target_accepts_windows_drive_paths` `crates/ui/src/pickers.rs:9468` | — | Windows-critical; web twin too. |
| f9a4a18be | 2026-10-03 | #739 | Convert BMP attachments to PNG so agents can read them | ui-desktop | PORTED | `56cefb77e`; `stage_file_verbatim` `crates/ui/src/attachments.rs:414` | — | Windows-critical. |
| e2a7706fa | 2026-10-03 | #737 | Add per-project new chat button and archive tooltip to the sidebar | ui-desktop | PORTED | `5f2565ad7`; `text_tooltip_above` `crates/ui/src/settings/widgets.rs:1740`; `+` at `crates/ui/src/shell/spaces.rs:5555-5578` | — | Ported, but the `+` is now broken for git projects. The later #799 port re-keyed groups to `repo:<id>`, and the `+` still gates on `space_row(&key)`. Fix belongs to dbb639be (below). |
| 9e1a11158 | 2026-10-03 | #760 | Render Mermaid diagrams in chat replies | ui-desktop | PORTED | `cab3f28bf`; `crates/ui/src/markdown/mermaid_cache.rs` (366 lines) | — | Web keeps mermaid-as-source (recorded divergence). |
| dbb639be3 | 2026-10-05 | #799 | Group projects by git identity, plus Zeron Icons and rolling labels | engine / ui-desktop | PARTIAL | `5e1788233` ported the first-commit subset. **Missing:** (a) trunk-root identity: no `commit:`/`trunk_root_commit` in `crates/engine/src/repos.rs:437-478`, still remote-or-local, no `--show-prefix` subfolder suffix; `spaces.rs:218-223` sets `None` on error instead of keeping the last id. (b) `+` button: `spaces.rs:5555-5557` uses `space_row(&key)` where key = `repo:<id>` → no `+` on git projects (upstream resolves `project_key(space)==key`, local-first member). (c) `crates/ui/src/roll_text.rs:420` `request_animation_frame()` instead of the ~30Hz pulse lease; no `MAX_KEYS` bound; no reduced-motion mid-roll settle; `working-word-{id}` / `working-timer-{id}` not keyed by turn (`transcript.rs:6924,6940`); no blur/tilt (`paint_glyph_transformed` exists in our zui pin `0966d06`). (d) Compact picker still has a Providers page (`pickers/compact.rs:782-811`), no provider tabs. (e) Compact sidebar row trailing cluster: still `remote \|\| corner_hovered` (`shell.rs:7939`), no fixed time/archive/legend slot. | M (sum; slices S–M) | **DECLINED:** Zeron Icons, sidebar glyph morph, fork/worktree glyphs (user decision 2026-10-05, `wave5/spec.md:52-56,173`). **Recorded Roboco divergence:** "effort always muted" (`74abedd5a` keeps the traits-summary chip). **Already done:** catalog/harness staleness (`harnesses_stale` `pickers.rs`, via `1ed398d68`); Android repo grouping (`crates/mobile-client/src/new_session.rs:139`). |
| 9b3773082 | 2026-10-05 | — | Bump version to v0.2.103 | other | SKIP:version-bump | | — | |
| 73bd3c842 | 2026-10-05 | #775 | Composer: mention images and files as chips | ui-desktop / mobile-core / android-parity | PORTED | `1ed398d68` (+#816, +1390e621); `crates/ui/src/composer/chip.rs`, `attachment_chip_tests.rs`; mobile layout via `b07a14767`; Android image-chip open `roboco-preview://image` `apps/android/.../transcript/LinkRoute.kt:60` | — | The iOS image-chip viewer behaviour is mirrored on Android. |
| 3ef98ec2a | 2026-10-05 | — | Bump version to v0.2.104 | other | SKIP:version-bump | | — | |
| 16d42a635 | 2026-10-05 | — | Simplify README and refresh its screenshot | docs | SKIP:docs-only | README.md, README.zh-CN.md, screenshot | — | |
| 9ac03a54b | 2026-10-05 | — | Add Korean and Japanese READMEs | docs | SKIP:docs-only | README.{ja,ko}.md | — | |
| c8eb7524e | 2026-10-05 | — | README: replace broken DeepWiki badge with a text link | docs | SKIP:docs-only | READMEs | — | |
| 3d4bfd11b | 2026-10-05 | #811 | Unify the sidebar project filter by repository identity | ui-desktop | PORTED | `e514c5467`; `project_device_tag` `crates/ui/src/state.rs:2163`; filter via `project_key` `crates/ui/src/shell/spaces.rs:3811` | — | Menu rows render no per-row host tag (recorded deviation). |

## Port candidates (MISSING + PARTIAL), most valuable first

### dbb639be3 (#799) Group projects by git identity — the deferred half
- **What it does for users:** wave-5 ported the remote-URL grouping and simple rolling labels. The
  rest of the merged squash was deferred as "unmerged branch follow-ups", and later drift notes
  (10-07, 10-09) kept checking the unmerged branch for it. It is not there: all of it is in
  `dbb639be` on `upstream/main` (see the squash message, "Identify repositories by their trunk's
  root commit" … "Don't report 'No agents available'"). Five independent slices:
  1. **Per-project `+` broken for git projects (bug, S).** `crates/ui/src/shell/spaces.rs:5555`
     shows the `+` only when `state.space_row(&key)` exists. Since ticket 11, by-project group keys
     are `repo:<repositoryId>` (`crates/proto/src/view.rs:264`), so every git checkout (remote or
     `local:` fallback) lost the `+` that ticket 08 / #737 added; non-git folders keep it.
     Upstream (`dbb639be:crates/ui/src/shell/spaces.rs:5038-5053`) finds the space whose
     `project_key == key` and opens on `project_members(space).first()`, i.e. this device's
     checkout first. Web is fine (`spaceForProjectKey`). Needs a regression test with
     `repository_id: Some(..)`. Worth a runtime check, but the code path is unambiguous.
  2. **Repository identity (M, engine).** `Repos::repository_identity`
     (`crates/engine/src/repos.rs:437-478`) is the remote-or-local scheme. Upstream changes:
     - Uses `commit:<first-parent root sha>` (cached per HEAD in `trunk_roots`), falling back to the
       remote and then the local hash.
     - Appends `rev-parse --show-prefix` as `:<subfolder>`, so monorepo siblings or a folder inside
       an enclosing repo stay **separate projects**. Today Roboco merges them into one group.
     - Changes the stamping caller to keep the previous id on error instead of writing `None`
       (`crates/engine/src/spaces.rs:218-223`), so a transient git hiccup doesn't regroup the
       sidebar.
     - Also fixes the split when checkouts spell their remote differently (SSH alias, renamed or
       transferred repo).
     - Wire: `repositoryId` stays an opaque string, so no proto change and no wiregen. Existing ids
       change form once. Tests: upstream's `m5_repos_diffs_terminals.rs` +149 lines.
  3. **roll_text runtime (S).** `crates/ui/src/roll_text.rs` requests display-rate frames
     (`:420`); upstream rides the shared ~30Hz pulse lease. The working timer rolls for most of
     every turn, so this is a battery/CPU cost on laptops. Also missing:
     - Bounded `ROLLS` memory (`MAX_KEYS`).
     - Settle-at-once when reduced motion switches on mid-roll.
     - Per-turn keys for the working trailer word/timer and the "Sending" bridge
       (`crates/ui/src/transcript.rs:6924,6940` key only on entity id, so a new turn rolls out of
       the last turn's values).
     - Optional polish: Scritto blur + 2° tilt via `paint_glyph_transformed`, which our zui pin
       `0966d065` already has (`~/.cargo/git/checkouts/zui-*/0966d06/crates/gpui/src/window.rs`).
       Roboco's module doc still says "gpui has no glyph blur or rotation", which is out of date.
  4. **Compact model list provider tabs (M).** Upstream brings back the tab strip: Starred first,
     then each provider; a tab jumps to its group, the top group lights its tab, edge fades. The
     separate provider page and the panel's provider button go away. Roboco still has
     `CompactPage::Providers` (`crates/ui/src/pickers/compact.rs:782-811`). Upstream files:
     `pickers/compact.rs` (−433/+…), `pickers.rs`. Web `CompactCard` twin needed.
  5. **Compact sidebar row trailing cluster (S–M).** One fixed 30px slot shows the time, swaps to
     the archive glyph on hover, and shows the Cmd/Ctrl+N legend while the modifier is held. The PR
     badge sits left of it and the remote globe is dropped. Columns line up and nothing resizes on
     hover. Roboco: `crates/ui/src/shell.rs:7939` (`remote || corner_hovered`). Touches
     `render_chat_row`, Roboco's most diverged function, so carry it by intent.
- **Not candidates:** Zeron Icons, the sidebar morph and the fork/worktree glyphs were DECLINED
  (wave-5 spec, user 2026-10-05). The muted-effort chip is a recorded Roboco divergence
  (`74abedd5a`). Catalog staleness is already in via `1ed398d68`.
- **Dependencies:** none on other upstream commits. Later upstream commits may refine roll_text or
  identity; check the later chunks before porting.
- **Effort:** (1) S, (2) M, (3) S, (4) M, (5) S–M.

### d1dc29f6c (#617) Antigravity — Windows sign-in browser suppression
- **What it does:** a stale Google login makes the antigravity ACP server open a browser for every
  background probe and chat turn. Upstream sets `BROWSER` to `<app exe> --noop-browser %s` on
  Windows, and the app exits immediately on that argument.
- **Missing in Roboco:** `noop_browser()` on Windows returns an error
  (`crates/harness/src/acp/mod.rs:3304-3313`), so the browser is not suppressed. Roboco's main
  platform is the one left out. The prompt watchers still cancel the run, so the impact is a stray
  browser window, not a hang.
- **Upstream files:** `apps/zeron/src/main.rs` (+3 early-return before `Cli::parse`),
  `apps/zeron/tests/noop_browser.rs`, the `windows_noop_browser` helper (backslash doubling) in
  `crates/harness/src/acp/mod.rs`.
- **Roboco files:** `apps/roboco/src/main.rs`, `crates/harness/src/acp/mod.rs`. Also consider
  reusing it for the engine's `quiet_cli_browser` Windows posture
  (`crates/engine/src/agent_accounts.rs:4617`).
- **Risks:** the harness needs the app exe path (`std::env::current_exe()`); the headless engine
  binary is the same exe. Excluded only at ticket level (`wave4/issues/09` Comments), with no user
  decision.
- **Effort:** S

### 878307a3d + 9cb1de50f + 5d5d93599 Linux tests on nextest + dev profile
- **What it does:** faster, less flaky Linux CI. Upstream changes:
  - One shared build for harness/engine/sync/update/doc/preview.
  - nextest with retries for timing-sensitive tests.
  - Dev profile instead of `--release`.
  - Checksum-pinned nextest, apt installs with bounded retries.
  - A nightly `--release` backstop.
- **Missing:** Roboco's ubuntu jobs run `cargo test` per crate (`ui-tests.yml:54-80`) and the UI
  job builds `--release` (`ui-tests.yml:233`). Per `85be4485a` it takes 27–30 min warm and was
  just raised to 45. Roboco already has `.config/nextest.toml` (`ci` profile used by Windows).
- **Risks:** the wave-4 spec (`spec.md:269-271`) keeps "7 policy workflows". Carry this inside
  `ui-tests.yml`/`preview-tests.yml`, and fold the nightly into an existing workflow's `schedule`
  rather than adding `release-profile-tests.yml`.
- **Effort:** M

### 710b06ff8 Seed the Rust cache from main; don't cancel main runs
- **Missing:** no `save-if: ${{ github.ref == 'refs/heads/main' }}` anywhere. The concurrency
  groups cancel main runs (`cancel-in-progress: true` unconditional). `windows.yml` has no `push`
  trigger at all, so its cache is never seeded from main. Note that AGENTS.md says "PR + push".
- **Fix:** add `save-if` to every rust-cache, make cancel conditional on non-main refs, and add a
  main-push trigger to `windows.yml`.
- **Effort:** S

### f4abe69b8 + c168ba5b4 Windows tests as parallel jobs
- **Missing:** Roboco `windows.yml` is one 90-minute job (`windows.yml:48-50`). Upstream splits it
  into ui-tests / crate-tests (two shards) / app jobs, using a `windows-ci-setup` composite action
  (toolchain, fxc lookup, rust-cache).
- **Value:** faster Windows PR feedback on the main platform. Roboco's step list differs (nextest,
  mobile-core crates, cursor shim), so re-shard by intent.
- **Effort:** M

### df0cd2980 (#630) Pi — question prefill + multiline
- **What it does:** Pi extension `input`/`editor` dialogs open with the extension's prefilled text,
  and multiline answers are kept verbatim instead of trimmed.
- **Missing:** `UserInputQuestion` has no `prefill`/`multiline` (proto). The Pi normalizer drops
  prefill (`crates/harness/src/pi/normalize.rs:387-400`). The composer wizard can't prefill
  (comment at `crates/ui/src/composer.rs:8537-8539` acknowledges it).
- **Upstream:** `crates/proto/src/agent.rs` (+4), `crates/ui/src/composer.rs` prefill/multiline
  arms + test `wizard_editor_preserves_prefill_whitespace_and_empty_edits`.
- **Wire:** additive serde-defaulted fields, so wiregen + web composer. Android question sheet
  parity is optional.
- **Not candidates:** the rest of #630 is DECLINED (Roboco's own driver; PROTOCOL.md and ACP
  session-map shim by user decision, `5aec52ef0`/`37c485cb3`/ticket 30). Ticket 30 finding #11
  (draft clobbering) looks fixed: `wizard.is_none()` guard and stash/restore at
  `composer.rs:8541`.
- **Effort:** M

### 56dc5ff3a Pin GitHub Actions to SHAs + Dependabot
- **Missing:** every Roboco workflow uses floating tags. `release.yml` runs next to the Android
  signing secrets.
- **Fix:** pin each `uses:` to its tag's commit (version in a trailing comment) and add
  `.github/dependabot.yml` (github-actions ecosystem, weekly, grouped).
- **Effort:** S

## Surprising
- **The deferred #799 "follow-ups" are not unmerged.** The squash `dbb639be` already contains the
  trunk-root identity, provider tabs, review rounds and blur/tilt. The 10-07/10-09 drift notes
  watched the dead branch tip `c3af3110` and concluded "nothing landed", so these were never
  re-triaged.
- **The wave-5 #799 port broke the #737 per-project `+` for every git project.** The group key
  changed to `repo:<id>`, but the button still looks it up as a space id
  (`crates/ui/src/shell/spaces.rs:5555`). This is a code-reading finding; worth a quick runtime
  confirmation.
- **`windows.yml` has no `push` trigger**, despite AGENTS.md's "(PR + push)".
