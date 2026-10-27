# Part PRs-B — open upstream PRs, older half (123 PRs, #17–#742, from `prs-B.txt`)

Summary: 123 open PRs. FIX-CANDIDATE 22 (21 distinct; #629 duplicates #738), FEATURE-CANDIDATE 44, ALREADY 20, STALE/RISKY 21, SKIP 16 (cloud 6, upstream-android 5, ios 4, docs-only 1).

Audited against Roboco `HEAD` = 85be4485a and `upstream/main` = 5d2b91901. For each PR I read the metadata, body, comments and reviews (`gh pr view`). For any PR that wasn't plainly skippable I also read the diff (`gh pr diff`), checked `git grep upstream/main` for a merged equivalent, and checked Roboco HEAD (`git grep HEAD`, roboco-log.txt, `.scratch`). None of these PRs is by hoangvu12. No Roboco Windows-support PRs are in this half.

Classes: `FIX-CANDIDATE`: a small or medium bug fix or polish, with the bug confirmed in Roboco HEAD. `FEATURE-CANDIDATE`: a larger feature that needs a product call. `ALREADY`: Roboco has the behaviour or doesn't have the bug. `STALE/RISKY`: superseded, abandoned, rejected, or conflicting. `SKIP:<reason>`: excluded by policy.
In the table, *date* is the PR's last update. *Notes* starts with size and draft/conflicting state.

## Table

| PR | date | author | title | area | class | evidence (Roboco HEAD unless stated) | effort | notes |
|---|---|---|---|---|---|---|---|---|
| #742 | 2026-10-03 | notsapinho | Hide shell launch wrappers in command tool titles | ui-desktop | FIX-CANDIDATE | Roboco `proto/src/view.rs:530`, `ui/src/transcript.rs:864`, `mobile/src/layout/tools.rs:171` show `command.clone()` raw; no wrapper unwrapping anywhere | M | +601/-95; Windows: Codex Run titles read `"C:\…\powershell.exe" -Command '…'`. Review asked to stop matching shells by file name alone; apply that before porting |
| #738 | 2026-10-02 | notsapinho | Question panel: keep multi-line answers visible in the free-text input | ui-desktop | FIX-CANDIDATE | `composer.rs:9833` `render_wizard` reuses `self.input`; only the pill path writes `viewport_height` (`composer.rs:11134`) | S | +82/-0; Same bug as #629; take one (738 also clears resize/top-padding) |
| #736 | 2026-10-02 | yghffhgx | docs: add UI architecture guide, fix stale tab navigation notes | docs | SKIP:docs-only | ARCHITECTURE/ui-architecture doc draft | - | draft +368/-1 |
| #733 | 2026-10-02 | Act0r1 | Add a hidden-folders toggle to the New project palette | ui-desktop | ALREADY | Roboco reveals dot-folders when the query starts with `.`: `shell/spaces.rs:6034` + `engine/src/repos.rs:1570,1743` (`show_hidden`) | - | +258/-25; Different UX (typed `.` instead of a toggle) |
| #732 | 2026-10-02 | Act0r1 | Keep typed paths in the New project search | ui-desktop | ALREADY | Superseded by merged upstream #998 (77a3c0ff1), ported (path field + Tab; HEAD 77a8e30a6) | - | +240/-38 |
| #731 | 2026-10-02 | notsapinho | Live diff: show what an agent is changing above the composer | ui-desktop | FEATURE-CANDIDATE | No live-diff pill; needs engine/proto (run-scoped diff since Working) | L | +1424/-179; Overlaps #115 |
| #728 | 2026-10-02 | notsapinho | Let users dismiss agent update notices on Home | ui-desktop | FIX-CANDIDATE | Engine has `dismiss` (`engine/src/harness_updates.rs:1585`, RPC `DismissHarnessUpdate` rpc.rs:2453) but `ui/src/shell/harness_updates.rs` never calls it | S | +150/-3; Home agent-update chip cannot be hidden |
| #726 | 2026-10-01 | katulevskiy | Cloud: continue chats in a box in your own Cloudflare account | cloud | SKIP:cloud | Cloudflare boxes, localedge, cloud worker | - | +45935/-202 |
| #724 | 2026-10-02 | katulevskiy | Saved workflows: arguments, launcher, Settings page and /workflow (stacked on #719) | engine | FEATURE-CANDIDATE | Saved workflows (top of the workflows stack); nothing similar in Roboco | L | +50381/-266; Stacked on #719/#712/#708 |
| #723 | 2026-10-02 | katulevskiy | Workflows, goals and todos on mobile (stacked on #719) | android-parity | FEATURE-CANDIDATE | Mobile half of the workflows/goal stack | L | +43233/-302; Only after #712/#708; iOS half skip |
| #722 | 2026-10-01 | katulevskiy | Permissions: see, add and remove standing rules in Settings | engine | FEATURE-CANDIDATE | Standing permission rules UI; no permission modes in Roboco | L | +21205/-536; Stacked on #715 |
| #720 | 2026-10-01 | katulevskiy | Presets: named agents to start a chat as | engine | FEATURE-CANDIDATE | Presets (named agents); absent | L | +20625/-536; Stacked on the permission stack |
| #719 | 2026-10-02 | katulevskiy | Workflows: card, run pane, approval and artifact viewers on desktop (stacked on #712) | ui-desktop | FEATURE-CANDIDATE | Workflows desktop UI; absent | L | +38123/-263; Stacked on #712 |
| #718 | 2026-10-01 | katulevskiy | Plan mode: present a plan, approve it, continue in the mode you pick | engine | FEATURE-CANDIDATE | Plan mode; absent | L | +19107/-535; Stacked on #715 |
| #717 | 2026-10-01 | SinaKhalili | Terminal: open links with Cmd/Ctrl+click | terminal | FEATURE-CANDIDATE | No link detection in `ui/src/terminal/*` (no OSC 8, no URL hover/open) | M | +649/-8; Ctrl+click on Windows/Linux; scheme allow-list |
| #716 | 2026-10-01 | katulevskiy | Sandbox: run agents inside an OS sandbox | engine | FEATURE-CANDIDATE | OS sandbox for agents; absent | L | +22459/-544; Check Windows coverage first |
| #715 | 2026-10-04 | katulevskiy | Permissions: add Bypass, Auto, Accept edits, Ask and Plan modes | engine | FEATURE-CANDIDATE | Permission modes (Bypass/Auto/Accept edits/Ask/Plan); absent | L | +17373/-447; Base of the permission stack; its Codex `sh -lc` unwrapping overlaps #742 |
| #714 | 2026-10-01 | hideoutgames | iOS: fix composer attachment menu and iPad sidebar header | android-parity | ALREADY | Android slice ported: 99df89520 `ComposerAttachMenuButton` "composerControl marker (PR #714 ...)"; iPad header = ios | - | +115/-10 |
| #713 | 2026-10-01 | woosal1337 | Settings: add automatic steering for active chats | ui-desktop | FEATURE-CANDIDATE | No "automatically steer" setting | S | draft +330/-48; Draft; product call |
| #712 | 2026-10-02 | katulevskiy | Workflows: Starlark scripts orchestrate child agent chats, engine half (stacked on #708) | engine | FEATURE-CANDIDATE | Starlark workflows, engine half; no `crates/workflow` | L | +29412/-236; Stacked on #708 |
| #711 | 2026-10-01 | katulevskiy | Claude: label resolved defaults with live model names | harness | FIX-CANDIDATE | `harness/src/claude/catalog.rs:262-266`: the `default` row is pushed with its "Default (recommended)" label under the concrete id; the named row is then skipped as a duplicate | S | +168/-9; New Claude releases are labelled "Default (recommended)" |
| #710 | 2026-10-01 | kanishkaverma | harness: key CLI versions by the path run, so mise-shimmed tools don't share one | harness | FIX-CANDIDATE | `harness/src/executable.rs:259-261` keys versions by canonical path only, so mise/asdf/Volta shims share one version | S | +92/-8; Wrong Pi/Codex versions for mise users |
| #708 | 2026-10-04 | katulevskiy | Goal mode: /goal keeps a chat working until a verifier chat confirms (stacked on #707) | engine | FEATURE-CANDIDATE | Goal mode (/goal + verifier chat); absent | L | +10005/-97; CHANGES_REQUESTED; needs #707 Todo (merged upstream) |
| #705 | 2026-10-01 | katulevskiy | feat(desktop): add persistent UI scaling | ui-desktop | FEATURE-CANDIDATE | No interface-scale setting | M | +743/-22 |
| #704 | 2026-10-01 | katulevskiy | Chats: move a live chat, its workspace and agent session to another device | engine | STALE/RISKY | Moves a chat's workspace + agent session to another device over #670's relay | - | +33117/-108; Conflicts with ADR 0004/0011; needs the localedge relay |
| #703 | 2026-10-01 | Yi-111-a | Claude: gate --thinking-display on what the resolved CLI accepts | harness | FIX-CANDIDATE | `claude/mod.rs:422` always passes `--thinking-display summarized`; no version gate | S | +330/-8; Older Claude CLIs fail every run (and titles) with "unknown option" |
| #702 | 2026-10-24 | jsgrrchg | Drag terminal tabs from the composer drawer into the right sidebar | terminal | FEATURE-CANDIDATE | Drag drawer terminal tabs into the right sidebar; absent | M | +1900/-734; +1900/-734 in ui |
| #701 | 2026-10-01 | katulevskiy | Add configurable desktop sleep prevention | ui-desktop | FEATURE-CANDIDATE | No sleep inhibitor anywhere (no keep-awake / SetThreadExecutionState) | M | +823/-1; Valuable on Windows laptops |
| #700 | 2026-10-04 | katulevskiy | Android: on-device engine, sensory UI, usage and background activity | android | SKIP:upstream-android | Upstream on-device Android engine | - | +59274/-4530 conflicting |
| #699 | 2026-10-01 | katulevskiy | Android: haptics, sounds and the compact model picker (stacked on #675) | android | SKIP:upstream-android | Upstream haptics/sounds/picker; Roboco already borrowed #699's vibration vocabulary | - | +25284/-1017 conflicting |
| #698 | 2026-10-01 | katulevskiy | Add a "What's new" window after updates | updater | FEATURE-CANDIDATE | No "What's new" window | M | +2318/-0; Roboco ships on GitHub Releases, so the notes source fits |
| #690 | 2026-10-04 | RemingtonWilcox | Projects: open New project on the device already in context | ui-desktop | FIX-CANDIDATE | `shell/spaces.rs:6045` `open_add_space` always starts at `ProjectStep::Devices` | S | +64/-6; Start on the device in context |
| #689 | 2026-10-01 | katulevskiy | New session: default to the last picked project and device | ui-desktop | ALREADY | `AppState::canvas_target` (state.rs:788-791; tabs.rs:242-263; port 74abedd5a of upstream #471) reopens the canvas where it was left | - | +88/-2 |
| #688 | 2026-10-04 | RemingtonWilcox | Errors: Retry a failed turn by resending its prompt | ui-desktop | FIX-CANDIDATE | No Retry on a failed turn (no resend in composer/transcript) | S | +207/-17 |
| #687 | 2026-10-04 | RemingtonWilcox | Errors: offer "Sign in again" when an agent is signed out | ui-desktop | ALREADY | Superseded by upstream #967 "Log in from error cards", present at `transcript.rs:3549,9170` | - | draft +169/-5; Draft |
| #685 | 2026-09-30 | katulevskiy | Add folder creation to New project on desktop and iOS | files/editor | ALREADY | Desktop typed-path create-and-add (ccc77315a); Android New Folder via PrepareSpacePath (`FolderNameRules.kt`) | - | +783/-14; No CreateFolder RPC by design |
| #684 | 2026-10-04 | RemingtonWilcox | Claude: launches take turns through an expired login | harness | STALE/RISKY | Largely superseded by upstream #974 refresh lock, ported a59137538 (`claude/refresh_lock.rs`) | - | +308/-2; Author notes it must rebase on #748 |
| #680 | 2026-10-27 | app/github-actions | chore(cursor): update pinned SDK | harness | STALE/RISKY | Roboco pins `@cursor/sdk@1.0.32` (`cursor/mod.rs:60`); bot bump to 1.1.0 unmerged upstream | S | +1/-1; Only after a cursor-compat run |
| #679 | 2026-09-30 | uidops | Appearance: show the background image in all sessions | ui-desktop | FEATURE-CANDIDATE | No background scope setting (only `newThreadBackgroundEffect`) | S | +275/-39 conflicting; CONFLICTING upstream |
| #678 | 2026-09-30 | katulevskiy | Live updates (EXPERIMENTAL): install without asking, keep agents, commands and terminals running | updater | FEATURE-CANDIDATE | Live in-place updates that keep agents/terminals running; absent | L | +28043/-1896; EXPERIMENTAL, +28k |
| #675 | 2026-09-30 | katulevskiy | Android app features: developer tools, subagents, model picker, agent management (stacked on #609) | android | SKIP:upstream-android | Upstream Android features | - | +15284/-890 conflicting |
| #674 | 2026-09-30 | katulevskiy | Files: right-click menu on tree rows, editable path in the file viewer | files/editor | FEATURE-CANDIDATE | Tree right-click menu already present (upstream #514, port a6322f2aa, `files/context_menu.rs`); editable path bar in the file viewer absent | S | +923/-6; Port only the path-bar half |
| #673 | 2026-09-30 | katulevskiy | Composer: live-synced drafts across devices | cloud | SKIP:cloud | Live-synced drafts over edge draft rooms | - | +7683/-29 conflicting; Local persistence is #557 |
| #672 | 2026-09-30 | katulevskiy | Chats: refresh generated titles every 4 prompts | engine | FEATURE-CANDIDATE | No `titleAuto` / periodic title refresh | M | +776/-22 |
| #671 | 2026-09-30 | katulevskiy | MCP: agents spawn real top-level chats or side chats on any device | engine | ALREADY | Superseded by upstream #706, ported 119724896 (MCP `kind: chat/side`); cross-device creation excluded per ADR 0004 | - | +4207/-164 |
| #670 | 2026-09-30 | katulevskiy | Device-to-device file transfer: P2P tunnels with relay fallback, send_files / fetch_files for agents | engine | FEATURE-CANDIDATE | Device-to-device file transfer + send_files/fetch_files; absent | L | +14383/-19; Relay half is cloud (DeviceRoom); product call vs ADR 0011 |
| #667 | 2026-09-30 | katulevskiy | Monitor: live CPU, memory, GPU, network and disk usage per device | ui-desktop | FEATURE-CANDIDATE | No system monitor | M | +2553/-2 |
| #666 | 2026-09-30 | kanishkaverma | perf(engine): serve cached ListSkills and ListCommands while refreshing | engine | FIX-CANDIDATE | `engine/src/registry.rs:177-211` probes the harness on every ListCommands/ListSkills (Pi spawns `pi --mode rpc` each time) | S | +129/-24; Slash menu waits 2-4 s per open |
| #664 | 2026-09-30 | AndPuQing | fix(ios): apply model changes in open sessions | android-parity | ALREADY | Android a40c8564b "PR #664's selection, pick rule, failure pill and immediate refresh" | - | +72/-12 |
| #663 | 2026-10-04 | RemingtonWilcox | Projects: offer the OS folder dialog in New project | ui-desktop | FIX-CANDIDATE | New project has no native folder dialog (`prompt_for_paths` used only in composer/appearance) | S | +160/-12; "Browse in File Explorer…" row for the local device |
| #662 | 2026-10-04 | RemingtonWilcox | Notifications: show clickable desktop banners on Windows | ui-desktop | FIX-CANDIDATE | `ui/src/notify.rs:338-339` Windows `post_impl` is a no-op; the zui pin (0966d06) already has `show_system_notification` / `set_app_identity` / `on_system_notification_response` | S | +67/-9; HIGH: no desktop banners on Windows |
| #658 | 2026-09-29 | paulo1403 | fix(linux): use a single main category in the desktop entry | updater | FIX-CANDIDATE | `dist/roboco.desktop:11` `Categories=Development;Utility;` | S | +1/-1; One line; entry listed twice in GNOME/KDE |
| #656 | 2026-09-29 | SinaKhalili | WIP: Native Codex provider and persistent iOS workspace | ios | SKIP:ios | Native Codex on iOS (draft) | - | draft +24308/-133 |
| #654 | 2026-09-29 | woosal1337 | Add Vesper to the built-in theme catalog | ui-desktop | FEATURE-CANDIDATE | No Vesper theme | S | draft +177/-5; Draft |
| #653 | 2026-09-29 | SinaKhalili | Transcript: make web tool links clickable | ui-desktop | FEATURE-CANDIDATE | Tool header/output URLs are plain text (`transcript.rs:877`) | M | draft +403/-26; Draft |
| #649 | 2026-10-01 | katulevskiy | MCP: read subagent transcripts; keep resumed subagents on their spawn chip | engine | FEATURE-CANDIDATE | MCP `read_subagent` absent in `crates/roboco-mcp`; the Claude-normalizer half is already present (`claude/normalize.rs:557-610`) | S | +301/-2 |
| #643 | 2026-09-29 | dfrnoch | Transcript: double-click drags keep selecting whole words | ui-desktop | FIX-CANDIDATE | `markdown/selection.rs:109` `begin_with_span` has no unit; a drag after a double/triple-click falls back to char selection | S | +134/-10 |
| #640 | 2026-09-29 | katulevskiy | MCP: search_chats finds content across every chat | engine | FEATURE-CANDIDATE | No MCP `search_chats` | M | +874/-14; Pairs with #516 |
| #639 | 2026-09-30 | katulevskiy | Android: the phone runs its own Zeron engine and the real agent CLIs in a Linux guest (stacked on #675) | android | SKIP:upstream-android | Upstream on-device Linux guest | - | +37468/-1264 conflicting |
| #629 | 2026-09-29 | katulevskiy | Composer: let the question panel's answer input grow past one line | ui-desktop | FIX-CANDIDATE | Same bug as #738 | S | +68/-0; Duplicate; take one |
| #626 | 2026-09-29 | katulevskiy | Client: limit warm session residency by estimated size | mobile-core | FIX-CANDIDATE | `crates/mobile-client/src/session/mod.rs:210` `WARM_SESSION_CAP = 6`, count only | M | +342/-25; Android memory; adapt to Roboco's mobile-client |
| #625 | 2026-09-29 | katulevskiy | Mobile: add unit tests for the client_ffi conversion layer | mobile-core | STALE/RISKY | Tests for upstream `client_ffi`, which Roboco does not have; its mobile core has its own tests (b07a14767) | - | +1492/-0 |
| #624 | 2026-09-29 | katulevskiy | iOS: flush the core off the main thread under a background task | ios | SKIP:ios | iOS background-task flush; no core change | - | +69/-2 |
| #623 | 2026-09-29 | katulevskiy | Mobile: keep the layout worker alive when a layout pass panics | mobile-core | ALREADY | Adopted in b07a14767 ("#623 robustness adopted: guarded_pass ...") | - | +138/-23 |
| #622 | 2026-10-26 | jsgrrchg | Add native conversation fork/bifurcation into side chats or main threads | engine | ALREADY | Fork from any reply into a side chat or main chat exists (`shell/fork_dialog.rs` "Fork from here"; MCP `fork_chat` with `reply`) | - | +8242/-104 |
| #621 | 2026-10-07 | villatothesea | Android: add Android client | android | SKIP:upstream-android | Draft Android client; Roboco borrowed lenient.rs / FolderBrowser pieces | - | draft +66426/-646 |
| #618 | 2026-09-29 | zqkra | Transcript: add a selection action bar (add to chat, reply in side chat) | ui-desktop | FEATURE-CANDIDATE | No selection action bar (Add to chat / Reply in side chat) | M | +1885/-85 |
| #614 | 2026-09-28 | devshekhawat | Titles: follow-the-session from the session harness and model | harness | FIX-CANDIDATE | `harness/src/lib.rs:695` `supports_titles` excludes OpenCode; `engine/src/titles.rs:184` guesses `cheapest_model` instead of using the session's model | M | +204/-27; OpenCode chats never get generated titles |
| #610 | 2026-10-01 | gaelcado | Unified Zeron and agent updates across devices | updater | STALE/RISKY | Draft; agent-update-owner half superseded by upstream #978 | - | draft +4877/-531 |
| #608 | 2026-09-29 | zqkra | Agent orchestration: agents spawn, message and wait for Zeron chats | engine | STALE/RISKY | Draft; superseded by the merged MCP server (92153025), #706 and Roboco completion notices | - | draft +16251/-1078 |
| #583 | 2026-09-27 | hideoutgames | iOS: desktop model picker parity | ios | SKIP:ios | Superseded by upstream #966 phone model picker; Android picker ported (a40c8564b) | - | +1968/-218 |
| #581 | 2026-09-28 | dcdyouget | Fix scrolling in compact review comment inputs | ui-desktop | FIX-CANDIDATE | `comment_ui.rs:258` `.h(px(46.0))`, `files/preview.rs:3091` `.h(px(48.0))` on a plain `ComposerInput` (`changes.rs:2858`): long review comments cannot scroll | M | +360/-24 |
| #566 | 2026-09-26 | megascan | Add a Windows PowerShell installer | cloud | SKIP:cloud | Served by the edge worker at zeron.sh/install.ps1 | - | +371/-16; Roboco ships an Inno installer and a portable zip |
| #565 | 2026-09-28 | dcdyouget | Show full local reset dates below provider usage meters | settings | STALE/RISKY | Maintainer was "not a big fan of the UI"; hover reset times already ported (c32447cde, upstream #873) | - | +48/-69 |
| #562 | 2026-09-26 | lennihco | Render LaTeX math in Markdown | ui-desktop | STALE/RISKY | Superseded by merged upstream #912 (zatex, c02739da3), not yet ported | - | +2416/-68; Port #912, not this |
| #559 | 2026-09-26 | wingleeio | Cut CI wall-clock: parallel jobs, no LTO for tests, main-only caches | ci | STALE/RISKY | Upstream reworked CI separately (f4abe69b8, 878307a3d); Roboco workflows diverged | - | +129/-26 |
| #557 | 2026-09-26 | raisehin | Drafts in chat. | ui-desktop | FIX-CANDIDATE | `composer.rs:5964` `drafts: HashMap` lives only in memory | S | +65/-6; Half-written prompts lost on restart |
| #554 | 2026-09-26 | magndotcc | feat(devices): allow removing devices | settings | ALREADY | Roboco devices have "Forget" (`settings/devices.rs:511`); the PR targets the synced registry | - | +226/-5 |
| #543 | 2026-10-25 | jsgrrchg | Add persistent cross-device prompt drafts to the sidebar | cloud | SKIP:cloud | Cross-device drafts through the edge registry room | - | +6674/-167; Local-only persistence = #557 |
| #526 | 2026-10-02 | Gratenes | feat: react web client | web | ALREADY | Roboco ported selected commits into its own `web/` (web-parity tickets 86, 91-93) | - | draft +174855/-61; Draft upstream |
| #521 | 2026-09-25 | laurentcodes | feat: manage codex banked resets from account settings | settings | FEATURE-CANDIDATE | No Codex banked-reset UI | M | +749/-8 |
| #520 | 2026-09-30 | invokestring | feat: import local agent histories | engine | FEATURE-CANDIDATE | No import of local Codex/Claude/Cursor/OpenCode histories | M | +836/-0; Windows histories can be huge (reviewer: 9.9 GB) |
| #516 | 2026-09-30 | kanishkaverma | Transcript search | ui-desktop | FEATURE-CANDIDATE | No transcript full-text search | M | +946/-10; SQLite FTS in engine; pairs with #640 |
| #515 | 2026-10-25 | jsgrrchg | Add a remote-safe mark unread action to session menus | ui-desktop | FEATURE-CANDIDATE | No "Mark unread" session action | M | draft +937/-41; Draft |
| #496 | 2026-09-24 | wintergraham | Add transcript selection comments | ui-desktop | FEATURE-CANDIDATE | No transcript selection comments | L | +1457/-67; CHANGES_REQUESTED |
| #495 | 2026-09-23 | LoikStyle | feat(chat): fork a session, transcript and all | engine | ALREADY | Fork exists (see #622) | - | +1300/-7 |
| #493 | 2026-09-28 | Marve10s | Group the OpenCode model list under foldable providers | ui-desktop | FEATURE-CANDIDATE | OpenCode models not grouped by provider | M | +379/-21 |
| #491 | 2026-09-21 | app/devin-ai-integration | ios: make wipe lease test enter the dirty stop path | ios | SKIP:ios | iOS test tweak | - | +5/-1 |
| #478 | 2026-09-20 | AkaraChen | fix(ui): stabilize model picker when switching providers | ui-desktop | STALE/RISKY | Picker restructured: the model chip is now pinned beside Send (`pickers.rs:6242-6251`) | - | +1/-1 |
| #460 | 2026-10-10 | jsgrrchg | Add Arc-style hover reveal for the collapsed sidebar | ui-desktop | FEATURE-CANDIDATE | No hover reveal of a collapsed sidebar | M | +915/-18; Same idea as #386 |
| #458 | 2026-09-19 | dcdyouget | Fix text selection in non-first Markdown table cells / 修复 Markdown 表格非首列文本无法选中 | ui-desktop | ALREADY | Superseded by upstream #681 (6641095b), ported 33b33a7ce | - | +85/-8 |
| #451 | 2026-09-24 | liao666brant | feat(i18n): add English and Simplified Chinese desktop UI | ui-desktop | FEATURE-CANDIDATE | No i18n | L | +12573/-3091; +12.5k/-3k |
| #446 | 2026-09-22 | wintergraham | Format Devin Models for Reasoning and Improve Fusion UI Support. | harness | STALE/RISKY | Landed upstream inside the #498 squash (731697b6, `acp/devin_models.rs` FUSION); Roboco excluded that slice in wave 3 | - | +852/-37; Track via the commit audit |
| #432 | 2026-09-27 | freegamerskids | Add Oh My Pi as a supported harness | harness | STALE/RISKY | CONFLICTING; #87 is the smaller variant | - | +503/-19 conflicting |
| #430 | 2026-09-17 | derinbarutcu17 | fix(ui): expose composer value to accessibility clients | ui-desktop | FIX-CANDIDATE | `composer.rs:5233` sets `aria_placeholder` but not `aria_value` (gpui at the zui pin has it, div.rs:1333) | S | +4/-0; 4 lines |
| #421 | 2026-09-17 | UMCEKO | Import Claude Code sessions as chats | engine | STALE/RISKY | Author superseded it with a fork PR on top of #418; duplicate of #520 | - | draft +2379/-6 |
| #418 | 2026-09-16 | UMCEKO | Add Nix flake for NixOS installs and development | other | FEATURE-CANDIDATE | No Nix flake | S | +202/-0 |
| #417 | 2026-09-16 | skyhancloud | Fix OpenCode 2.0.5 model discovery via /api/status detection | harness | ALREADY | Roboco probes `/api/status` for V2 (`opencode/mod.rs:613`) | - | +94/-9 |
| #395 | 2026-09-22 | v14 | Support non-image file attachments on desktop | ui-desktop | ALREADY | Non-image attachments staged as bytes (`attachments.rs:372-430`, 100 MB) | - | +825/-141 |
| #393 | 2026-09-16 | wingleeio | Add synced side chats in the right sidebar | ui-desktop | ALREADY | Side chats in the right pane exist (upstream #498/#590 ports) | - | +1218/-24 |
| #388 | 2026-09-15 | ghostpyy | Agent rail: main and this session's subagents under the composer | ui-desktop | STALE/RISKY | Draft; superseded by the Explorer Subagents section and session card | - | draft +1218/-73 |
| #386 | 2026-09-15 | ghostpyy | Peek a collapsed pane by hovering its edge | ui-desktop | FEATURE-CANDIDATE | No hover peek of collapsed panes | M | +290/-18; Pick one of #386/#460 |
| #384 | 2026-09-15 | ghostpyy | Jump back to an earlier prompt with double-Escape | ui-desktop | FEATURE-CANDIDATE | No double-Escape prompt rewind | M | +464/-2 |
| #383 | 2026-09-16 | ghostpyy | Show the account's plan usage in the composer footer, with switching | ui-desktop | ALREADY | Superseded by upstream #547 usage ring, ported ea6b1821e | - | +988/-88 |
| #382 | 2026-09-15 | ghostpyy | Identity marks for projects and people, with an optional picture | ui-desktop | STALE/RISKY | Project monograms landed upstream (1ec74e40, 2f40dfad); only account pictures are new | - | +448/-51 |
| #381 | 2026-09-15 | ghostpyy | Make the frost depth adjustable | ui-desktop | FEATURE-CANDIDATE | No frost-depth setting | S | +178/-14 |
| #375 | 2026-09-15 | N30Yang | opencode: surface agents as agent/<name> picker rows | harness | ALREADY | Superseded by upstream 507015365; `/api/agent` Agent option present (`opencode/mod.rs:365,1333`) | - | +120/-5 |
| #372 | 2026-09-15 | zaesho | Add Factory Droid as a first-class ACP harness | harness | FEATURE-CANDIDATE | No Factory Droid harness | M | +799/-156; ACP |
| #363 | 2026-09-14 | webmatze | Forward the terminal wheel to programs that own the mouse or the alternate screen | terminal | FIX-CANDIDATE | `terminal/panel.rs:1814-1849`: the wheel always scrolls local scrollback; no mouse-mode/alt-screen routing | M | +485/-28; The wheel does nothing inside `claude`/vim/less |
| #356 | 2026-09-17 | wintergraham | Rework model selector with loadout-backed slots | ui-desktop | STALE/RISKY | Draft; the model picker was redesigned since (#405, #471, #749) | - | draft +7981/-500 |
| #330 | 2026-09-11 | labkita7 | Forward login-shell env vars (bashrc/zshrc exports) to harness children | harness | STALE/RISKY | Upstream #977 chose an allow-list (`shell_env.rs:40` CAPTURED_VARS) over forwarding the whole login env | - | +210/-41; Real Linux gap, deliberate upstream design |
| #319 | 2026-09-24 | Gratenes | Add authenticated shared web UI | cloud | SKIP:cloud | Authenticated web UI via edge/WorkOS | - | draft +45594/-1712 |
| #314 | 2026-09-10 | pekala | Add a setting for opening pull requests in Linear or Graphite | ui-desktop | FEATURE-CANDIDATE | No "open PRs in Linear/Graphite" setting | S | +421/-5 |
| #308 | 2026-09-10 | dcdyouget | Honor Claude Code's provider model mapping for Claude sessions | harness | STALE/RISKY | Largely superseded by upstream 6611d3c06: settings.json models are listed (`claude/catalog.rs:153-193`) | - | +483/-13; Alias remap is a product call |
| #252 | 2026-09-09 | SinaKhalili | Implement end-to-end encrypted workspace sync | cloud | SKIP:cloud | E2E encrypted workspace sync | - | +29210/-391 |
| #235 | 2026-09-16 | ghostpyy | UI polish: settings memory, frost depth, identity marks, account usage, rewind, hover-peek, agent rail | ui-desktop | STALE/RISKY | Umbrella draft, CONFLICTING; split into #381-#388 | - | draft +3727/-261 conflicting |
| #197 | 2026-08-21 | naveengovind | Build Linux releases against the oldest glibc we support (fixes load failure on Ubuntu 22.04) | ci | FIX-CANDIDATE | `release.yml:46-47` builds Linux on `ubuntu-24.04`, giving a GLIBC_2.39 floor; tarball fails on Ubuntu 22.04 | M | +103/-7 |
| #188 | 2026-08-20 | tobi404 | iOS: slash commands and @-mentions in the composer | ios | STALE/RISKY | Old SwiftUI app, CONFLICTING; upstream's current iPhone app has no slash menu either | - | +2218/-37 conflicting |
| #115 | 2026-10-09 | SinaKhalili | Transcript: end-of-turn edit summary card with click-through to a turn-diff tab | ui-desktop | FEATURE-CANDIDATE | No end-of-turn "Edited N files" card | M | +1010/-17; CHANGES_REQUESTED |
| #99 | 2026-08-15 | Thytu | Show an ErrorChip when a prompt turn is force-settled | engine | FIX-CANDIDATE | `engine/src/sessions.rs:5051-5061`: the quiesce watchdog parks a silent prompt turn as a clean finish | S | +92/-13; Product call |
| #89 | 2026-08-15 | kjj6198 | feat(ui): make mounted volumes reachable from the project picker | files/editor | ALREADY | `repos.rs:1685-1864` `list_drives` covers /Volumes, /media, /run/media, /mnt and Windows drives | - | +113/-76 |
| #87 | 2026-09-29 | Thytu | Add Oh My Pi as its own agent | harness | FEATURE-CANDIDATE | No Oh My Pi harness (`omp acp`) | S | +171/-11; Users asking in comments |
| #17 | 2026-08-04 | f1shy-dev | Bound Rust development cache growth | ci | STALE/RISKY | August draft; upstream dev profiles changed since (878307a3d) | - | draft +174/-1 |

## Candidates

### FIX-CANDIDATE, most valuable first

#### #662 Notifications: show clickable desktop banners on Windows (RemingtonWilcox), effort S
- **What it does:** Windows gets real toast banners for "run finished", "waiting on input", "run failed", the connection notice and agent updates. Clicking a banner opens that chat. A chat's newer toast replaces its older one, so they don't stack.
- **Bug in Roboco:** yes. `crates/ui/src/notify.rs:338-339` defines `post_impl` as an empty function on every platform except macOS and Linux. The module doc at `notify.rs:20` says "Windows: no-op for now". Only the chime plays.
- **Port feasibility:** the gpui APIs the PR uses already exist at our zui pin 0966d06: `App::set_app_identity`, `show_system_notification` and `on_system_notification_response` (`crates/gpui/src/app.rs:1428-1453` in the cargo checkout). No new dependency is needed.
- **Files:** `crates/ui/src/notify.rs`, the app start-up path that calls `set_app_identity("sh.roboco.app", "Roboco")`, `dist/windows/roboco.iss` (stamp the same AppUserModelID on the shortcuts), `scripts/test-windows-installer.ps1`.
- **Risks:**
  - Taskbar grouping: `set_app_identity` sets the process AUMID, so old pins won't group until re-pinned.
  - The portable zip may show a generic toast icon.
  - The PR was never clicked through on an installed build.

#### #742 Hide shell launch wrappers in command tool titles (notsapinho), effort M
- **What it does:** the compact "Run" title shows the script instead of `"C:\WINDOWS\…\powershell.exe" -Command '…'`, `pwsh -NoProfile -Command`, `cmd /c` or `bash -lc '…'`. The expanded block still shows the exact invocation for auditing. One shared `display_command` serves desktop and mobile.
- **Bug in Roboco:** yes, and Windows Codex users see it on every command.
  - `crates/proto/src/view.rs:530` returns `("Run", command.clone())`.
  - `crates/ui/src/transcript.rs:864` and `crates/mobile/src/layout/tools.rs:171` also show the raw command.
  - No harness strips the wrapper; `crates/harness/src/codex` has no unwrapping.
- **Files:** new `crates/proto/src/command_display.rs`, plus `proto/src/view.rs` (`tool_call_text`), `ui/src/transcript.rs`, `mobile/src/layout/tools.rs` and a proto test.
- **Review blocker:** the reviewer (CHANGES_REQUESTED) objected that a shell is recognised by file name alone, so any binary named `bash` gets unwrapped. Address that when porting.
- **Wire:** none. This is a display-only helper.

#### #363 Forward the terminal wheel to programs that own the mouse or the alternate screen (webmatze), effort M
- **What it does:** in the terminal, the mouse wheel now reaches full-screen TUIs. Claude Code, vim, less and htop can be scrolled with the wheel.
  - The wheel follows xterm precedence: mouse reporting (SGR, UTF-8 or X10 encoding), then alternate-screen arrow keys, then local scrollback.
  - Shift+wheel always scrolls local scrollback.
- **Bug in Roboco:** yes. `crates/ui/src/terminal/panel.rs:1814-1849` always turns wheel input into `scroll_active` on local scrollback. Inside `claude` running in a Roboco terminal, the wheel does nothing.
- **Files:** `ui/src/terminal/emulator.rs` (`wheel_route`, the encoders), `terminal/panel.rs`, `terminal/view.rs`.
- **Rebase note:** Roboco already has #615's fractional remainder (`scroll_remainder`). The PR's `WheelAccumulator` overlaps it, so rebase onto that.

#### #738 / #629 Question panel: keep multi-line answers visible (notsapinho / katulevskiy), effort S
- **What it does:** when an agent asks a question, the free-text answer box grows with its content. Today it is clipped to the single line the compact composer pill left behind.
- **Bug in Roboco:** yes.
  - `crates/ui/src/composer.rs:9833` `render_wizard` reuses `self.input`.
  - `viewport_height`, `settled_viewport_height` and `overflow_top_padding` are written only on the pill path (`composer.rs:11134-11136`).
- **Fix:** in `render_wizard`, cap the input at `TEXTAREA_MAX - TEXTAREA_PAD_V` (260 - 20) and clear `resizing` and `overflow_top_padding`. Take #738; it also adds a regression test.

#### #197 Build Linux releases against the oldest glibc we support (naveengovind), effort M
- **What it does:** release tarballs load on Ubuntu 22.04 (glibc 2.35).
- **Bug in Roboco:** yes. `.github/workflows/release.yml:46-47` builds on `ubuntu-24.04` and `ubuntu-24.04-arm`. That gives a hard GLIBC_2.39 version requirement, from Rust std's weak pidfd symbols.
- **Fix:** build on 22.04 runners and add a `GLIBC_MAX` assert to the packaging script.
  - The PR also adds an install.sh loader preflight. Skip that part: it lives in edge, which Roboco doesn't have.
  - Check that `libwebkit2gtk-4.1-dev` (Roboco's Linux browser) installs on 22.04. It does exist there.
- **Files:** `.github/workflows/release.yml`, `scripts/package-linux.sh`.

#### #614 Titles: follow-the-session from the session harness and model (devshekhawat), effort M
- **What it does:** OpenCode chats get generated titles instead of truncated prompts, and every title run reuses the session's own model.
- **Bug in Roboco:** yes.
  - `crates/harness/src/lib.rs:695-699` leaves OpenCode out of `supports_titles`, and there is no `OpencodeHarness::run_title`.
  - `crates/engine/src/titles.rs:184` falls back to `cheapest_model`. That can pick a model the account can't call.
- **Files:** `harness/src/opencode/mod.rs` (+tests), `harness/src/lib.rs`, `engine/src/titles.rs`, `engine/src/sessions.rs`.

#### #711 Claude: label resolved defaults with live model names (katulevskiy), effort S
- **What it does:** when Claude's `default` row resolves to a newly released model, the picker shows its real name (for example "Sonnet 5.5") instead of "Default (recommended)".
- **Bug in Roboco:** yes, in `crates/harness/src/claude/catalog.rs:246-293`.
  - The `default` entry is pushed with `label: text("displayName")`.
  - The later named row with the same `resolvedModel` is then dropped by the duplicate check at `:265`.
- **Files:** `harness/src/claude/catalog.rs`, `harness/tests/claude_models.rs`, and an optional read-only probe example.

#### #710 harness: key CLI versions by the path run, so mise-shimmed tools don't share one (kanishkaverma), effort S
- **What it does:** Pi and Codex installed behind one mise, asdf or Volta shim no longer report each other's version. An update made behind the shim is noticed after 30 s.
- **Bug in Roboco:** yes. `crates/harness/src/executable.rs:258-261` keys the cache by `(canonical, mtime, len)`. Model discovery also probes the canonical path.
- **Rebase note:** Roboco's cache has since gained the `Probe`/`expires` structure (`executable.rs:204-290`). Re-apply the intent: key by the invoked path plus the canonical path, and give dispatched entries a short TTL.

#### #728 Let users dismiss agent update notices on Home (notsapinho), effort S
- **What it does:** a ✕ on the Home agent-update chip and on each row. The engine remembers the dismissed version, and a failed row can be dismissed back to Current.
- **Bug in Roboco:** yes.
  - The engine RPC exists: `engine/src/rpc.rs:145,2453` `DismissHarnessUpdate` and `engine/src/harness_updates.rs:1585` `dismiss`.
  - Nothing in `crates/ui/src/shell/harness_updates.rs` calls it.
- **Files:** `ui/src/shell/harness_updates.rs`, `engine/src/harness_updates.rs` (the failure-dismiss tweak and its test).

#### #666 perf(engine): serve cached ListSkills and ListCommands while refreshing (kanishkaverma), effort S
- **What it does:** the slash menu opens at once from the last catalog, and a refresh runs in the background.
- **Bug in Roboco:** yes. `crates/engine/src/registry.rs:177-211` `discover_commands` / `discover_skills` re-probe on every call, and Pi spawns `pi --mode rpc` each time (2-4 s). It is worse when a fleet member hosts the chat.
- **Files:** `engine/src/registry.rs` (`DiscoveryCache` keyed by harness and workspace).

#### #663 Projects: offer the OS folder dialog in New project (RemingtonWilcox), effort S
- **What it does:** on the local device, New project's locations list ends with "Browse in File Explorer…". It opens the native folder picker, and the chosen folder is added directly.
- **Bug in Roboco:** the feature is missing. `cx.prompt_for_paths` is used only by `composer.rs:7328` and `settings/appearance.rs`. `shell/spaces.rs` has no such row.
- **Files:** `crates/ui/src/shell/spaces.rs` (split out `add_space_create` from `submit_add_space`).
- **Coordination:** pairs with #690. Both add the same `shell_with_devices` test helper.

#### #690 Projects: open New project on the device already in context (RemingtonWilcox), effort S
- **What it does:** New project opens on the selected device's locations instead of the device list. Back returns to the list.
- **Bug in Roboco:** yes. `crates/ui/src/shell/spaces.rs:6045` always sets `step: ProjectStep::Devices`. Fleet users pick a device every time, and so do single-device users.
- **Files:** `shell/spaces.rs` (`open_add_space` steps through `add_space_pick_device` when the device in context is online).

#### #557 Drafts in chat (raisehin), effort S
- **What it does:** half-written prompts survive an app restart. Text only, stored device-local in UiSettings.
- **Bug in Roboco:** yes. `crates/ui/src/composer.rs:5964` `drafts: HashMap<String, String>` lives only in memory.
- **Files:** `ui/src/settings.rs` (`drafts`), `ui/src/composer.rs` (seed at boot, save on switch/edit), `ui/src/shell.rs` (`sync_independent_settings`, so the save doesn't clobber drafts).
- **Related:** the cross-device variants #543 and #673 are cloud; this is the local-only slice.

#### #643 Transcript: double-click drags keep selecting whole words (dfrnoch), effort S
- **What it does:** after a double-click, dragging extends the selection by whole words. After a triple-click it extends by whole elements. The word no longer collapses back to a few letters.
- **Bug in Roboco:** yes. `crates/ui/src/markdown/selection.rs:109` `begin_with_span(key, text, range)` takes no unit. `update_drag` (`:165`) re-resolves character by character.
- **Files:** `markdown/selection.rs` (`Unit`), `markdown/render.rs`, `composer.rs`, and the tests.

#### #688 Errors: Retry a failed turn by resending its prompt (RemingtonWilcox), effort S
- **What it does:** the error that ends a chat's last turn gets a Retry button. It resends the prompt and its attachments through the normal `send`, and leaves any half-typed draft alone.
- **Bug in Roboco:** the feature is missing; nothing in `transcript.rs` or `composer.rs` resends.
- **Neighbour:** Roboco already has "Log in" on error cards (`transcript.rs:3549,9170`).
- **Files:** `ui/src/composer.rs` (`resend`), `ui/src/transcript.rs`, `ui/src/shell.rs` (routing for side chats).

#### #581 Fix scrolling in compact review comment inputs (dcdyouget), effort M
- **What it does:** long review comment drafts in the diff, Markdown preview and source editor can be scrolled with the wheel or the scrollbar rail, and the caret stays visible.
- **Bug in Roboco:** yes, the code shape is the same as upstream.
  - `comment_ui.rs:258` uses `.h(px(46.0))` and `files/preview.rs:3091` uses `.h(px(48.0))`.
  - Both wrap a plain `ComposerInput::new("Request a change…")` (`changes.rs:2858`) with no scrollable viewport.
- **Files:** `ui/src/comment_ui.rs`, `comments.rs`, `composer.rs` (`with_scrollable_viewport`), `files/preview.rs`, `changes.rs`.

#### #626 Client: limit warm session residency by estimated size (katulevskiy), effort M
- **What it does:** the phone keeps at most 6 warm sessions and also stays within a 32 MB estimated budget, evicting least-recently-used quiet sessions first. Sessions that are on screen or have pending sends are never evicted.
- **Bug in Roboco:** yes, on Android. `crates/mobile-client/src/session/mod.rs:210` `WARM_SESSION_CAP = 6` is a count only, so six huge chats stay resident.
- **Port adaptation:** the PR targets upstream `crates/client`, which Roboco doesn't have. Port the eviction plan, and move the doc-size estimate into `crates/doc` so the engine shares it.

#### #703 Claude: gate --thinking-display on what the resolved CLI accepts (Yi-111-a), effort S
- **What it does:** a Claude CLI that is too old to know `--thinking-display` still runs (without thinking summaries) instead of failing every run and every title with "unknown option".
- **Bug in Roboco:** yes. `crates/harness/src/claude/mod.rs:420-423` always passes the flag, and there is no minimum-version gate.
- **Approach:** a bounded probe launch (`claude/capabilities.rs`).
- **Value:** lower than the others, since only users on older CLIs are affected and Roboco offers CLI updates.

#### #430 fix(ui): expose composer value to accessibility clients (derinbarutcu17), effort S
- **What it does:** dictation and screen-reader tools (Spokenly, Windows Narrator) can read the composer's current text.
- **Bug in Roboco:** yes. `composer.rs:5233` sets `aria_placeholder` but not `aria_value`. `aria_value` exists in gpui at our pin (`elements/div.rs:1333`).
- **Size:** one added line.

#### #658 fix(linux): use a single main category in the desktop entry (paulo1403), effort S
- **What it does:** Roboco appears once, not twice, in GNOME, KDE and Xfce menus.
- **Bug in Roboco:** yes. `dist/roboco.desktop:11` has `Categories=Development;Utility;`. Change it to `Development;`.

#### #99 Show an ErrorChip when a prompt turn is force-settled (Thytu), effort S
- **What it does:** a prompt or steer turn ended by the quiesce watchdog reads as "Turn settled — agent went quiet." and shows Failed. Today it plays the done chime with no error.
- **Behaviour in Roboco:** identical to the PR's "before". `crates/engine/src/sessions.rs:5051-5061` parks the turn as Idle/Complete.
- **Product call:** upstream left this open since August. Self-continued turns stay Idle either way.

### FEATURE-CANDIDATE (product calls), roughly by value to Roboco

- **#701 Keep computer awake** (M). Settings → General with three modes (while running, the default). Absent in Roboco; nothing in the tree prevents sleep. High value on Windows laptops (SetThreadExecutionState) for long agent runs. Files: `crates/ui` plus a small platform layer.
- **#717 Terminal: open links with Ctrl+click** (M). OSC 8 and plain-text URLs, including ones wrapped across rows. Absent; `ui/src/terminal/*` has no link handling. Only safe schemes may be opened.
- **#731 Live diff pill above the composer** (L). Shows "N files changed +A −D" for the current run, with a per-file list. Absent. Engine and proto change (wiregen). Overlaps #115's end-of-turn card; pick one design.
- **#516 Transcript search + #640 MCP search_chats** (M each). Engine SQLite FTS over transcripts, desktop Cmd+K, and an MCP tool. Roboco's Cmd+K searches titles and files only. Build them together.
- **#705 Persistent UI scaling** (M). Interface scale from 50% to 200% with Ctrl+Shift+±. Absent.
- **#698 "What's new" window after updates** (M). Shows GitHub release notes since the last version seen. Absent. Fits Roboco's GitHub Releases flow.
- **#649 MCP `read_subagent`** (S). Lets an orchestrator read its subagents' transcripts. Absent from `crates/roboco-mcp`. The PR's Claude-normalizer half (resumed subagents keep their spawn chip) is already in Roboco (`claude/normalize.rs:557-610`).
- **#674 Editable path bar in the file viewer** (S). Port only this half: the right-click menu already came from upstream #514 (a6322f2aa, `files/context_menu.rs`).
- **#672 Refresh generated titles every 4 prompts** (M). Adds a `titleAuto` doc field, and a user rename always wins.
- **#618 Selection action bar** (M). "Add to chat" / "Reply in side chat" on selected transcript text. #496 (selection comments with annotations, L, CHANGES_REQUESTED) is the heavier alternative.
- **#384 Double-Escape prompt rewind** (M). Restores an earlier prompt into the composer.
- **#386 / #460 Hover-peek collapsed panes or sidebar** (M). Pick one.
- **#381 Adjustable frost depth** (S).
- **#679 Background image in all sessions** (S). Conflicting upstream.
- **#702 Drag terminal tabs into the right sidebar** (M).
- **#713 Automatically steer** (S). Draft. Send goes to Steer on active runs.
- **#493 OpenCode models grouped by provider** (M).
- **#653 Clickable URLs in tool headers and output** (M). Draft.
- **#667 System monitor** (M). Live CPU, memory, GPU, network and disk per device.
- **#521 Codex banked resets in account settings** (M).
- **#520 Import local agent histories** (M). Codex, Claude, Cursor, OpenCode. The reviewer warned that Windows Codex histories run to about 10 GB, so the import needs care.
- **#515 Mark unread** (M). Draft.
- **#314 Open PRs in Linear or Graphite** (S).
- **#87 Oh My Pi harness via `omp acp`** (S). Users are asking for it. #432 is a conflicting variant.
- **#372 Factory Droid ACP harness** (M).
- **#418 Nix flake** (S).
- **#654 Vesper theme** (S). Draft.
- **#451 English and Simplified Chinese i18n** (L). +12.5k/-3k lines, so a huge conflict surface.
- **#115 End-of-turn "Edited N files" card** (M). CHANGES_REQUESTED; review found click-through bugs.
- **#678 Live updates without interrupting running work** (L). Experimental, +28k.
- **#670 Device-to-device file transfer** (L). Its relay fallback is cloud (DeviceRoom). The P2P half would need a Roboco transport, and the whole thing has to be weighed against ADR 0011 ("reachability only, never data").
- **Permission, plan, sandbox and workflow stack** (all L, stacked, unmerged upstream; all absent from Roboco):
  - #715 permission modes, the base. Its review fixes unwrap Codex `sh -lc` the way #742 does.
  - #718 plan mode, #720 presets, #722 standing rules and #716 OS sandbox (check Windows coverage).
  - #708 goal mode, #712 Starlark workflows engine, #719 workflows desktop UI, #724 saved workflows, and #723 the mobile half for Android parity.
  - Wait for upstream to merge before considering any of these.

### Pointers (not candidates themselves)
- **#562 LaTeX:** superseded by the merged upstream #912 (zatex, c02739da3). Roboco hasn't ported it (drift note `.scratch/upstream-drift/2026-10-22.md:168`).
- **#446 Devin model folding / Fusion:** landed upstream inside the #498 squash (731697b6, `crates/harness/src/acp/devin_models.rs` FUSION). Roboco deliberately left that slice out in wave 3 (`.scratch/upstream-ports-2026-10-wave3/issues/09-side-chats-ui.md:43`), so Roboco's `devin_models.rs` lacks it. That is a commit-audit item.
- **#330 Login-shell env forwarding:** a real Linux gap (API keys exported in `~/.bashrc` don't reach agents when Roboco starts from the desktop). Upstream #977 deliberately kept an allow-list (`harness/src/shell_env.rs:40` CAPTURED_VARS), so this is STALE as written. Widen the list only on purpose.
