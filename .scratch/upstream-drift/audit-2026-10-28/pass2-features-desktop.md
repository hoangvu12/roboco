# Pass 2: desktop, engine and CLI feature inventory (upstream `31d26eeb6` vs Roboco `HEAD` `85be4485a`)

Method: this pass compares features, not commits. Both trees were exported read-only with `git archive` to a temp dir, with renames applied (zeron→roboco, Zeron→Roboco, ZERON_→ROBOCO_). Then these sets were diffed mechanically:

- gpui `actions!` / `#[derive(Action)]` names and `KeyBinding::new` combos
- `ShortcutId` defaults, the `SettingsSection` enum, `UiSettings` fields, and `*Settings` structs
- proto type names, enum variants and struct fields of shared types, and RPC method constants (`crates/rpc/src/lib.rs`)
- MCP tool names and schema property names
- CLI `clap` subcommands
- env vars read
- user-visible string literals in `crates/ui/src`
- per-file `fn` names in shared files of ui, engine, harness, proto, markdown, text and preview

Each upstream-only hit was traced to its commit with `git log -S` and checked against `2026-10-28.md` and the `part-*.md` rows.

Exclusions: cloud, accounts-sync and billing (`settings/cloud.rs`, `billing.rs`, `cloud.rs`, Vault, WorkOS, orgs, edge, `crates/client` cloud paths); iOS; macOS-only code; recorded declines. The recorded declines are Muse (wave-9 08), zatex/LaTeX math (#912), commit credit (wave-9 24), Android terminals and the mobile-core terminal (wave-9 22/23), and Zeron Icons. The PR board (#484/#866), native Luau visuals (#900) and native Codex voice (#819/#834) are decision-gated per §F of the report, so they are listed but not counted as gaps.

---

## NEW: not in the 2026-10-28 report

### N1. Background engine process: sessions keep running after the app closes (#966, `cc7da5c2a`), L
- **What:** the desktop app no longer embeds the engine. It starts a detached `roboco-engine` process, or `roboco engine` on Windows (hidden CLI subcommand), and attaches to it. If you close or quit the window, running agents carry on. The next app launch re-attaches. After an update, the engine restarts into the new version once it is idle (`zeron-version` file).
- **Roboco today:**
  - The headed app embeds the engine in-process (`apps/roboco/src/main.rs:287-298`), so quitting kills every running turn.
  - On Windows there is no other way to keep runs alive: `roboco daemon` is systemd/launchd only (`apps/roboco/src/daemon.rs:1-4`).
  - The only detached engine is the Tailcat CLI helper (`apps/roboco/src/tailcat_setup.rs:110-118`).
- **Upstream:**
  - `crates/engine/src/managed.rs:1-60` (`engine_command`, `MANAGED_ENV`, `APP_ENV`, 60 s handoff)
  - `apps/zeron/src/background.rs:1-25`
  - `apps/zeron/src/bin/zeron-engine.rs:1-25`
  - `apps/zeron/src/main.rs:34-39` (hidden `Engine` subcommand)
  - `crates/ui/src/state.rs:421-432` (`managed::launch` / `reap`), plus `launch_background_engine`, `record_engine_loss`, and "Roboco's engine keeps stopping unexpectedly" (`state.rs:3739`)
  - `crates/update/src/windows.rs` `VERSION_FILE` / `installed_version`
- **Report:** not listed. §B lists only "engine-posted desktop notifications while the window is closed" (`desktop_notify.rs`), which runs on top of this process. Port N1 first.
- **Why it matters:** Windows is the main platform. A long agent run currently dies with the window. This must coexist with Roboco's remote-access listener, Tailcat and the fleet. All of them live in the engine, so they move into the background process unchanged.

### N2. Localhost IPC requires a per-start bearer token (#966, `cc7da5c2a`), M
- **What:** on every start the engine mints a 32-byte token and writes it to `{data_dir}/ipc-token` (owner-only, atomic replace). Any `ws://127.0.0.1:{port}` handshake without `Authorization: Bearer …` is refused.
  - Clients resolve the token in this order: `ROBOCO_IPC_TOKEN`, then `ROBOCO_IPC_TOKEN_FILE` (which the engine hands to the `mcp` servers it injects into agents), then the file.
- **Roboco today:** the IPC listener's only defence is rejecting an `Origin` header (`crates/rpc/src/server.rs:146`, `reject_cross_origin`). Any local process, or any other user on a shared Linux box, can drive the engine: create chats, run agents, read transcripts.
- **Upstream:**
  - `crates/rpc/src/ipc_auth.rs:1-40`
  - `crates/rpc/src/server.rs:139-191` (`serve_ws_listener(.., token)`, `bearer_matches`)
  - `crates/rpc/src/client.rs` `connect_ipc` / `dial`
  - env vars `ZERON_IPC_TOKEN` / `ZERON_IPC_TOKEN_FILE`
- **Report:** not listed (security).
- **Touches:** the engine, `roboco mcp` injection env, the desktop client, `roboco status`, CLI helpers (pairing, Tailcat), and web/test fixtures that dial IPC. Android and remote clients are unaffected because they use the paired remote listener.

### N3. MCP `wait_for_turn` reports "interrupted" (#966, `cc7da5c2a`), S–M (wire field)
- **What:**
  - The engine stamps `Session.last_turn_outcome = {status, finishedAt}` whenever a native turn ends (Completed, Interrupted or Errored), and clears it when a new turn starts.
  - `wait_for_turn` returns a new `Interrupted` outcome, and it does so even when the watch coalesced the Working frame away.
  - A stale liveness heartbeat is not treated as completion.
- **Roboco today:** `TurnOutcome` has no `Interrupted` (`crates/roboco-mcp/src/roboco.rs:97-106`). An interrupted child turn either reads as `Completed` (`roboco.rs:557-569`, `saw_working`) or times out. An orchestrating agent can't tell "stopped" from "done".
- **Upstream:**
  - `crates/mcp/src/zeron.rs:113-121` and `:565-600`
  - test `coalesced_repeated_interrupts_need_fresh_native_outcome_evidence` (`zeron.rs:735`)
  - `crates/proto/src/entities.rs:357` (`NativeTurnOutcome`), `:371`
  - `crates/engine/src/sessions.rs:2109-2120`
  - `proto/src/view.rs:45` `session_stale`
  - tool description `crates/mcp/src/tools.rs:165`
- **Report:** not listed. Part-06 files #966 as cloud plus slices (a)–(d); this is not one of them.

### N4. A phone (or a desktop managing a remote engine) can sign a computer's agent in: loopback callback relay (#966, `cc7da5c2a`), M–L
- **What:** the engine of the device that runs the agent starts the sign-in. The browser opens on the phone, and the OAuth redirect lands on the phone's own loopback port (e.g. Codex `localhost:1455`). The phone listens on that port and relays the callback path to the host over `RelayAgentLoginCallback`. The host replays it as one GET to the port it registered, so the CLI or engine finishes the login as if the browser had been local.
- **Roboco today:** no relay method. A Codex, ChatGPT or Antigravity loopback sign-in started from Android, or from a desktop pointed at another fleet engine, cannot complete.
  - Report §C "Error card Log in doesn't start first login" covers only the Android button.
  - ADR 0011 allows reachability-only links. This relays a callback to the device that owns the login, not data.
- **Upstream:**
  - `crates/rpc/src/lib.rs:243-246`
  - `crates/engine/src/agent_accounts.rs:2552-2600` (`relay_callback`: only a live login this requester started, only its port, only a plain path)
  - `crates/engine/src/rpc.rs:4587-4600`
  - phone side `crates/client/src/providers.rs:155-215` (`relay_login_callback`: "Port {port} is in use on this phone…")
- **Report:** not listed. Part-06 #449 notes "engine→engine login forwarding skipped per ADR 0004", which is a different mechanism.

### N5. Devin Fusion in the model picker (#498 squash `731697b66`, also open PR #446), M
- **What:**
  - One picker entry stands for every Devin Fusion pair.
  - Hovering it opens a card laid out like Devin's own panel: Lead, Effort, Sidekick, Fast Mode.
  - Devin model variants are folded, and reasoning words are parsed (`split_effort`, `speed_option`).
  - Rows that share a label show their description (`mark_ambiguous`).
- **Roboco today:**
  - `crates/harness/src/acp/devin_models.rs` is 195 lines versus 763 upstream.
  - There is no `FUSION`, `configured_in_place`, `SettingScope` or `mark_ambiguous`. Only test fixtures in `pickers.rs:8574-8603` mention lead/sidekick.
- **Upstream:**
  - `crates/harness/src/acp/devin_models.rs:91-101` (FUSION), plus `devin_effort_values` / `selection` in `acp/mod.rs`
  - `crates/ui/src/pickers.rs:785` (`mark_ambiguous`), `:838-850` (`SettingScope`, `configured_in_place`), `render_config_card`, `card_order`
- **Report:** not listed. Wave-3 09 excluded it as "unrelated squash content" (`.scratch/upstream-ports-2026-10-wave3/issues/09-side-chats-ui.md:43`). That was an agent's judgment, not a recorded user decline. `part-prs-B.md:317` calls it "a commit-audit item", but no part row lists it as MISSING.

### N6. Pi chats have no slash menu (#630 `df0cd2980`), M
- **What:** upstream Pi's `commands()` and `skills()` read `get_commands`, which covers Pi's commands, skills, prompt templates and extension commands. It also adds built-ins:
  - `/compact [instructions]`
  - `/session` (stats)
  - `/name <title>`
  - `/export [path]` (HTML)
  - `/autocompact on|off`
  - `/steering <mode>`
  - `/follow-up <mode>`
- **Roboco today:**
  - `crates/harness/src/pi/` has no `commands`/`commands_for`/`skills`. It falls back to the trait default, which is empty (`crates/harness/src/lib.rs:240-242`).
  - The wave-4 #30 decision record keeps Roboco's own driver and does not mention commands.
  - Report §A "slash menu re-runs discovery each open, 2–4 s for Pi (#666)" is moot for Pi: Roboco never asks Pi for commands.
- **Upstream:** `crates/harness/src/pi/catalog.rs:80-123` (`builtin`), `pi/mod.rs:333-346`, `:123`, `:605` (`get_commands` query).
- **Report:** not listed. These are RPC calls on the existing wire, so it fits Roboco's `steer`-queue driver. `/steering` and `/follow-up` touch Pi's global modes, so skip those two per the wave-4 #30 item 8 rationale.

### N7. Settings → Archived pages 40 rows at a time (#449 `b782d0437`), S
- **What:** archived sessions are shown 40 per page with Previous/Next ("Previous archived sessions" / "Next archived sessions"). The page index is clamped after an unarchive or delete.
- **Roboco today:** `crates/ui/src/settings/archived.rs` renders every archived row in one scroll. Hundreds of rows mean one big GPUI element tree.
- **Upstream:** `crates/ui/src/settings/archived.rs:23-27` (`ARCHIVE_PAGE_SIZE`, `archive_page`), `:475` (pager), test `:638`.
- **Report:** not listed. Part-03's #449 PARTIAL names three other remainders.

### N8. Three #977 hardening slices the audit marked PORTED but which are absent (`2e151ca65`), S each
- **(a) Claude `managed-mcp.json` guard.** While an administrator's `managed-mcp.json` exists, Claude refuses `--strict-mcp-config` and exits, so every run fails. Upstream skips the flag when the file is present.
  - Upstream: `crates/harness/src/claude/mod.rs:130-142`, `:371`. The Windows path is `C:\Program Files\ClaudeCode\managed-mcp.json`.
  - Roboco always passes the flag (`claude/mod.rs:948`).
- **(b) Native `claude.exe` beside the npm shim (Windows).** Newer npm packages ship `node_modules/@anthropic-ai/claude-code/bin/claude.exe`. Upstream launches it directly instead of the `.cmd` shim.
  - Upstream: `claude/mod.rs:84-113`.
  - Roboco's `process/cmd_shim.rs` already unwraps shims to `node cli.js`, so this matters only when the package carries the exe and not `cli.js`. Verify against a current Claude Code npm install.
- **(c) Codex background-command prune.** After a stop, upstream asks `thread/backgroundTerminals/list` and drops `open_commands` entries Codex no longer runs. Without that, a stale entry keeps the idle reaper from ever retiring the app-server.
  - Upstream: `crates/harness/src/codex/mod.rs:2246-2310`, `:2373-2400`.
  - Roboco tracks `open_commands` (`codex/mod.rs:1442`) but never prunes them.
- **Report:** none of the three is listed. A26 is a different #977 slice, and `part-07` marks #977 PORTED. Wave-9 06 "remaining" item 2 named (a) and (b) (`.scratch/upstream-ports-2026-10-wave9/issues/06-harden-providers.md:289-295`), but the 7/7 landing notes do not cover them. `git grep managed-mcp HEAD` and `native_beside_npm_shim` both return nothing.

---

## 1. Settings

Pages (`SettingsSection`): upstream has `Devices Cloud Billing Harnesses Agents Appearance Files Notifications Voice Shortcuts General Appshots Archived`. Roboco has the same set, with RemoteAccess in place of Cloud and Billing. Rebindable shortcuts (`ShortcutId`, 16 ids and their defaults) match exactly.

Upstream-only `UiSettings` keys (`crates/ui/src/settings.rs`):

| Item | Upstream | Report | Effort |
|---|---|---|---|
| `smoothScrolling`: Appearance → "Smooth scrolling" (mouse wheels glide) | `settings.rs:1274`, `smooth_scroll.rs` | §B late window #1005 | M |
| `onboardingCompleted`: first-run onboarding | `settings.rs:1181`, `onboarding.rs` | A27 | M |
| Settings search page and sidebar ⌘F (Ctrl+F) | `settings/search.rs`, `shell/settings_search.rs` | §B late #1018 | M–L |
| Settings → Worktrees card (per-device custom worktree locations) | `settings/worktrees.rs`, `shell/spaces.rs` `MenuAlign` | §B #530 | M |
| Providers → native-subagents switch (`NativeSubagentSettings`, `Get/SetNativeSubagents`) | `settings/delegation.rs`, `rpc/src/lib.rs:60` | §B #1046 | M |
| Archived pagination | `settings/archived.rs:23` | **N7** | S |
| `renderMath` / `mathFont` / `mathSize` | `settings.rs:1211` | DECLINED (zatex #912) | — |
| `pullRequestDestination` / `pullRequestStars` / `pullRequestPaneWidth` / `lastPullRequest*` / "Open pull requests in" | `settings.rs:1235` | §F gated (#484/#866) | — |
| `codexVoice` / `codexVoiceDevice`, Voice host list | `settings.rs:1277`, `shell.rs:6084` | §F gated (#819/#834) | — |
| Billing / Cloud pages, API-key cards, "Turn Cloud on…" | `settings/{billing,cloud}.rs`, `accounts.rs` `cloud_*` | SKIP cloud | — |

Checked with no gap:
- Notifications, Files, Composer (completion), Shortcuts (Escape stops agent, send behaviour), Appshots and Wallpaper keys.
- Harnesses page: Roboco has "Check now". Upstream's refresh button also re-scans for newly installed CLIs (`harnesses.rs:334`, #966). That is minor and not filed.
- Accounts page: Roboco deliberately keeps its own grouped page (part-03 #449).
- Engine device settings: Roboco's `EngineSettings` and `RemoteAccessSettings` are Roboco-only. Upstream's only extra device setting is `NativeSubagentSettings` (#1046).

## 2. Actions and shortcuts

- **gpui actions:** the 72 `actions!` names are identical. The only upstream-only `#[derive(Action)]` structs are:
  - `SelectPaletteTab` (#856, report §B)
  - `OpenPullRequest`, `ClosePullRequest`, `OpenPullRequestInBrowser`, `StartPullRequestSession`, `AddPullRequestProject`, `OpenPrImage` (PR board, §F gated)
- **Keybindings:** the 36 `KeyBinding::new` combos are identical.
- **Key-equivalents localisation** (AZERTY ⌘& → ⌘1, `settings.rs:1876-1960`, `shell.rs:558`): the doc comments say it is empty off macOS, so it is macOS-only (report §B late, #1013 SKIP confirmed).
- **Command palette:** upstream has tabs (All / Threads / Commands / In Files), Recent Files, file and content rows, and an "Archive thread" command (`shell/command_palette.rs:226-300`). All of it is #856 (§B, L). Roboco's commands are New chat, New project, Open settings and Theme.
- **Built-in slash and `$` completions:** UI-side lists are the same. The harness-side gap is Pi (**N6**). Claude's `/compact` and local commands render empty (§A smaller, #802/#787).

## 3. Menus and controls

| Item | Upstream | Report | Effort |
|---|---|---|---|
| Transcript image right-click menu (Copy image, Copy path) | `transcript.rs` `open_image_menu`/`copy_image` | §B #813 slices | M |
| Sidebar "No project" group `+` (`NewSessionTarget::NoProject`, "New chat on {}") | `shell/tabs.rs:86`, `spaces.rs:6037` | §B `81adbdabf` | S |
| Sidebar "● N" running-subagents pill | `running_pill.rs`, `loaders.rs` `pulse_glow` | §B #647 | S |
| Session hover card | `shell/session_hover_card.rs` | §B #903 | M |
| Tooltips and aria labels on icon-only buttons (caption Maximize/Restore, Hide/Show comment, favourites star, Account actions…) | many | §B #1048 | S |
| New-project picker: "Anywhere under Home" folder search (`SearchHomeFolders`, `ReleaseHomeFolderSearch`, `FolderRow`, "Searching Home…") | `proto/src/entities.rs:556-588`, `rpc/src/lib.rs:187`, `engine/src/repos.rs` `search_home_folders` | inside §B #856 (part-03 notes `home_index.rs` waits on it) | in #856 L |
| Compact model picker with provider tabs ("Back · Esc", "Jump to {name}") | `pickers/compact.rs` | §B #799 half | M |
| Devin Fusion hover card | `pickers.rs:838` | **N5** | M |
| Composer: "Close without answering · Esc" on questions | `composer.rs` `wizard_dismiss` | §B #1022 | L (with wire) |
| Composer: browser-annotation chips ("Fix … now", "Tighten [Annotation 1]") | `composer.rs` `add_browser_annotation` | §B late #814 | L |
| Composer remote-link notice ("Can't reach {host} — messages will send when it's reachable") and calm fork pending/retry ("Forking on {host}…", "Couldn't fork on {host}") | `composer.rs:639`, `shell/side_chats.rs:499-516` | part-07 #1038 SKIP cloud (the UI idea is noted there as redoable for fleet relays) | M if wanted |
| Right-pane tab types | only `RightSurface::PullRequest` / `NavEntry::PullRequest(s)` are upstream-only | §F gated | — |
| Titlebar Pull requests button, sidebar account footer, sync overlay, org gate | `shell.rs` | gated / cloud | — |

Context menus on sidebar rows, project groups, transcript messages, tool chips, the file tree, tabs and the composer: no upstream-only menu labels besides those above (string-literal diff).

## 4. Transcript rendering

| Item | Upstream | Report | Effort |
|---|---|---|---|
| Sender and provider-switch captions (`render_agent_sender`, `switch_marker`, "Message from/to", "Couldn't message") | `transcript.rs:6854`, `:9922` | §B #1052/#1068, A15 | M |
| Markdown in user and agent bubbles (`render_user_markdown`, `plain_markdown`) | `transcript.rs:6793` | §B late #1067 | M |
| Question chip with recorded answers (`InputOutcome`, `AnswerLine`, "header → Not answered") | `proto/src/view.rs:873-889`, `transcript.rs:9959` | §B #1022 | L |
| Tool-call image strip and selectable output with line cap (`render_tool_image_strip`, `output_line`) | `transcript.rs:6337`, `:884`, `tool_images.rs` | §B #813 | M |
| Codex-generated images (`render_generated_image`) | `transcript.rs:7293`, `image_media.rs` | A18 | M |
| Markdown images in replies, `show_image` (`ReadChatImageChunk`, `PublishImage`) | `reply_images.rs`, `rpc/src/lib.rs:256,262` | §B #1036 | L |
| Live subagent text, typed `/skill` fork chip and tail (`claude/normalize.rs` `fork_*`, `jsonl_tail.rs`, `claude/fork_tail.rs`) | harness | §B Claude subagents chain | M×3 |
| Transcript rail, one tick per prompt with preview card | `rail.rs` | §B #958/#1049 | M |
| Smooth mouse-wheel glide | `transcript.rs` `on_scroll_wheel`, `smooth_scroll.rs` | §B #1005 | M |
| Cold-remote-chat loader ("Syncing…", `TranscriptLoading`) | `transcript.rs` `render_loader`, `state.rs:796` | part-07 #1038 SKIP cloud | — |
| LaTeX math (`markdown/math.rs`, `crates/markdown` dollar rewriting, `crates/text` inline boxes) | — | DECLINED (#912) | — |
| Luau visuals (`AgentEvent::Visual`, `show_visual`, `visuals.rs`) | — | §F gated (#900) | — |

Proto message kinds: the variants of shared enums match except `AgentEvent::{PlanUsage (cloud vault usage), Visual (gated)}` and `HarnessId::Muse` (declined). Roboco-only: `AgentEvent::TurnRef`, `Indicator::Limited`.

## 5. Engine, proto, MCP, CLI, harnesses, env

- **RPC methods:**
  - Upstream has 166 method constants; Roboco has 120.
  - Most of the upstream-only ones are cloud, billing, Vault, auth, GitHub-connect, voice-v2, sync or change-request (gated).
  - The non-cloud ones:

| Method / type | What | Report | Effort |
|---|---|---|---|
| `RelayAgentLoginCallback` | phone/remote sign-in callback relay | **N4** | M–L |
| `SetViewportFocus` | banners hold back while a window is focused | §B #966 (c) | M |
| `Session.last_turn_outcome` (`NativeTurnOutcome`) | interrupted-turn evidence | **N3** | S–M |
| `Session.terminal_activity`, `ProgramStatus` (OSC 7501) | terminal programs report idle/working/blocked | §B #966 (b) | M |
| `GetNativeSubagents` / `SetNativeSubagents`, `McpServer::SIDE_CHATS_ONLY_ENV` | providers' native subagents off | §B #1046 | M |
| `PublishImage`, `ReadChatImageChunk` | show_image / Markdown images | §B #1036 | L |
| `SearchHomeFolders`, `ReleaseHomeFolderSearch`, `SearchWorkspaceContent`, `WarmWorkspaceSearch` | fff workspace and home search | §B #856 | L |
| `UserInputQuestion.prefill` / `multiline` (`proto/src/agent.rs:710-712`) | Pi/Codex question defaults | §B #630 | M |
| `TerminalSession.host_replies` (`entities.rs:1511`) | host answers terminal queries | §B #975 | in L |
| `Chat.harness_session_harness` | resume-id ownership after provider switch | SUPERSEDED by Roboco's `RunRequest.provider_fork` (7ad2288ad) | — |
| `ChatConnectivity.{delivery_live,sync_state}`, `Space.github_repo`, `FocusChat` | chat2 / cloud | SKIP | — |

- **MCP tools:**
  - Upstream: `archive_chat create_chat get_chat interrupt_chat list_chats list_devices list_harnesses list_models list_projects read_chat respond_to_input send_message wait_for_turn whoami`, plus `show_image` (§B #1036) and `show_visual` (gated).
  - Roboco has those plus `fork_chat`, `previews`/`stop_previews` and the batch tools.
  - The only upstream-only schema property is `cloud`.
  - Behaviour gap: **N3**.
- **CLI:**
  - Upstream subcommands: `headless engine(hidden) login logout status sync quiesce(hidden) appshot(linux) mcp daemon update`.
  - `engine` is **N1**. `login`, `logout`, `sync` and `quiesce` are cloud.
  - Roboco adds `engine <pairing…>`, `headless --network…` and the Tailcat flags. Note that `roboco engine` is already a pairing subcommand group, so N1 needs another name (for example a separate `roboco-engine.exe`, or `ROBOCO_MANAGED_ENGINE` plus a hidden `background` subcommand).
  - `--noop-browser`: Roboco uses a `.noop-browser` script instead (`acp/mod.rs:3303`). The Windows case is §B #617.
- **Harnesses:**
  - Same `HarnessId` set except Muse (declined).
  - Upstream-only harness behaviour: Pi slash commands (**N6**), Devin Fusion (**N5**), #977 residuals (**N8**), Claude subagent forks (§B), Codex `agent_tools_off` (#1046, §B).
  - Pi native RPC rewrite: DECLINED (wave-4 #30 decision record).
- **Env vars read** (non-test, non-cloud, upstream-only):

| Env var | Feature | Report |
|---|---|---|
| `ROBOCO_IPC_TOKEN`, `ROBOCO_IPC_TOKEN_FILE` | IPC bearer token | **N2** |
| `ROBOCO_MANAGED_ENGINE`, `ROBOCO_APP`, `ROBOCO_EMBED_ENGINE` | background engine | **N1** |
| `ROBOCO_SIDE_CHATS_ONLY` | native subagents off | §B #1046 |
| `ROBOCO_BROWSER_ENGINE`, `ROBOCO_BROWSER_DMABUF`, `ROBOCO_WPE(_HELPER)` | WPE browser | §B #1039 |
| `ROBOCO_BROWSER_STORAGE_*` | persistent browser profiles | §B #573 |
| `ROBOCO_PI_MCP` | Pi native | declined |
| `ROBOCO_CALLBACK_PORT`, `ROBOCO_WORKOS_*`, `ROBOCO_EDGE_*`, `ROBOCO_CLOUD_*`, `ROBOCO_RUNNER_ENROLL`, `ROBOCO_DEVICE_PLATFORM` | — | cloud |

## 6. Terminal, browser, files/editor, git, updater

| Area | Item | Upstream | Report | Effort |
|---|---|---|---|---|
| Terminal | Overhaul: IME/marked text, mouse and focus reporting, contrast floor, cursor blink, copy flash, predictive echo, host replies | `terminal/panel.rs:2263`, `terminal/view.rs:416,453,499`, `ui/src/terminal/predict.rs` | §B #975 (wave-9 17) | L |
| Terminal | Wheel inside full-screen TUIs | open PR #363 / #975 `wheel_arrows` | A13 | M |
| Terminal | Program status (OSC 7501) to sidebar and MCP | `engine/src/terminals/program_status.rs` | §B #966 (b) | M |
| Browser | Windows WebView2 in-app browser | `browser/windows_webview.rs` | §B #813 | L |
| Browser | Linux WPE WebKit and dma-buf | `browser/linux/wpe.rs` | §B late #1039 | L |
| Browser | Element annotations | `browser/annotate.js`, `view.rs` "Annotate an element" | §B late #814 | L |
| Browser | Persistent profiles | `browser/profile.rs` | §B #573 | M |
| Browser | Local-file hint for `C:\…` / `\\server\…` | `browser/model.rs` `local_file_address` | §B #1012 | S |
| Files | Content search and recent files (fff) | `files/client.rs` `search_content`/`warm_search`, `files_panel.rs` `recent_files` | §B #856 | L |
| Files | Stale-highlight crash after external reload | `files/editor_adapter.rs` `record`/`take` | A5 | S |
| Files | Shared-width columns | `shell/panel_layout.rs`, `composer_dock.rs` `observe_column` | §B #677 | L |
| Git | Branch-vs-base totals on the session card | `changes.rs` `for_branch`, `session_info.rs` `ensure_branch_totals` | §B #950 | S–M |
| Git | Repository identity by root commit | `engine/src/repos.rs` `trunk_root_commit` | §B #799 half | M |
| Git | PR board, SSH-alias repository resolution, review threads | `engine/src/source_control.rs` (+2710 lines) | §F gated | — |
| Git | Clone from GitHub in the new-project picker | `repos.rs` `clone_repo_in`, "Clone here" | SKIP cloud (GitHub App listing) | — |
| Updater | Metadata size/time caps; managed-Linux re-check | `crates/update/src/lib.rs` `fetch_release_metadata_with_limits` | §B #535 | S |
| Updater | Background engine restarts into the new image once idle (`VERSION_FILE`, `set_managed_restart`) | `crates/update/src/{lib,windows}.rs` | **N1** (part of it) | in L |

## Deliberate differences confirmed (not gaps)

- **Home agent-update island:** upstream tracks every device ("Disconnected · reconnect to update", `shell/harness_updates.rs:54-71`). Roboco tracks the connected engine only, per ADR 0004 (`shell/harness_updates.rs` header).
- **Sidebar pins and sections:** upstream syncs them through the registry (`SidebarPreferences`, `WatchSidebarPreferences`). Roboco moved them engine-side (`SetSidebarPins` / `WatchSidebarState`; part-01 SUPERSEDED).
- **macOS-only:** key-equivalent localisation (#1013), `browser/macos.rs` precise-wheel work, mimalloc.
