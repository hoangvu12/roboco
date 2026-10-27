# Part 07 — `e680d5c98`..`5d2b91901` (2026-10-22 → 2026-10-27, v0.2.119–v0.2.126 + post-126 tail)
Summary: 65 commits — PORTED 7, PARTIAL 1, MISSING 26, DECLINED 4, SUPERSEDED 1, SKIP 26, N/A 0

Audited against `HEAD` = `85be4485a` (feature/fleet-follow). Wave-9 port commits used as evidence
(`faca1d333`, `5a7f0acf4`, `a39f89e37`, `327fef69d`, the `wave9/engine` lane) are all ancestors of HEAD
(`git merge-base --is-ancestor` checked). Wave-9 ticket labels were not trusted: every row below was checked
in code (`git grep … HEAD`). Commits after `534a5742c` (rows 30–65) had no prior review; none of them is
referenced anywhere in `roboco-log.txt` or `.scratch/` and none of their code is in HEAD.

## Table
| SHA/PR | date | upstream PR | title | area | class | evidence | effort | notes |
|---|---|---|---|---|---|---|---|---|
| `e680d5c98` | 10-22 | #972 | Cloud commits by the user, co-authored by zeronsh[bot] | engine | DECLINED | `.scratch/upstream-ports-2026-10-wave9/issues/24-commit-credit.md` (wontfix 2026-10-25); no `commit_credit.rs` in HEAD | — | The #971 iOS text in the body is message-only; the diff is commit credit alone |
| `c04487045` | 10-22 | — | Bump version to v0.2.119 | other | SKIP:version-bump | 2 files, version only | — | |
| `dfc38967d` | 10-22 | #973 | Cloud: per-user machine size (xlarge) | cloud | SKIP:cloud | `edge/src/cloud/*` only | — | |
| `dd0c36fa3` | 10-23 | #974 | Claude logins that stay signed in; sturdier CLI updates | harness/engine | PORTED | `crates/harness/src/claude/refresh_lock.rs`, `crates/harness/tests/claude_refresh_lock.rs`; wave-9 #05 notes (agent_accounts locks, harness_updates +371, `HarnessUpdateFailureKind`) | — | `config_dir_for` per-run env adapted away (no `RunRequest.env` then) |
| `2e151ca65` | 10-23 | #977 | Harden every provider: crash/stall detection, Windows-safe launching, run recovery | harness/engine | PORTED | `crates/harness/tests/acp_hardening.rs`, `crates/harness/tests/pi_rpc.rs` + `fixtures/pi_rpc.rs`, `crates/engine/tests/run_recovery.rs`, `pi/wire.rs:97 close_input_for_teardown`; wave-9 #06 "06 9/9 … remaining-queue is now empty" | — | Landed across 9 commits; windows.yml runs `--test pi_rpc` |
| `5b8edb2de` | 10-23 | #980 | iOS: don't crash Providers when there's no device to pick | android-parity | SKIP:ios | UIKit-only abort (`.popUpMenu` with no element). Compose has no such abort and `accounts/AgentAccountsScreen.kt:146` already keys on `devices.isNotEmpty()` | — | Wave-9 #21 lists it; nothing to port — tick that item off |
| `6f2721fab` | 10-23 | #981 | iOS: keep the session through iPhone Duo fold and unfold | android-parity | MISSING | `transcript/TranscriptViewport.kt:133-141 onFrameApplied` still springs on every following reflow (no width-change pin); `shell/CarryOver.kt` rebuilds the session "on the next frame" (= pre-#981 iOS) | S–M | Width-change pin (S) also fixes phone rotation; reuse-same-session across the 840dp breakpoint (M) |
| `a2c68b097` | 10-23 | #982 | iOS core build: find cargo where the caller's PATH does | ci | SKIP:ios | `scripts/ios/build-core.sh` only | — | |
| `987bb2f87` | 10-23 | #978 | Agent CLI updates through the real owner, known-bad list, Muse Code | engine | PORTED | `crates/engine/src/harness_updates/ownership.rs`, `compatibility.rs`, `compatibility.json`; wave-9 #07 | — | Muse DECLINED (wave-9 #08); edge compatibility feed dropped by design |
| `1ec71a318` | 10-23 | — | Bump version to v0.2.120 | other | SKIP:version-bump | version only | — | |
| `2d6895cbb` | 10-23 | #983 | TestFlight: build with Xcode 27.1 | ci | SKIP:upstream-ci | `.github/workflows/testflight.yml` | — | |
| `71033cc96` | 10-23 | #975 | Terminal: no added latency, P2P to other devices, predictive echo | terminal | MISSING | No `crates/ui/src/terminal/predict.rs`, no `crates/engine/src/terminals/{replies,link}.rs`, no `host_replies`, no TCP_NODELAY in HEAD; wave-9 #17 still ready-for-agent | L | Skip the WebRTC P2P slice (`remote_terminals.rs`, `terminals/link.rs`, `preview/peer.rs`) per #17. Port: instant key writes, 4ms flood pacing, IME/dead keys, mouse/focus reporting, DECSCUSR, sync output, contrast floor, host-answered queries (matters for fleet remote terminals), resize-on-open. Wire: `entities.rs` `host_replies` |
| `4231cb7b0` | 10-23 | — | Bump version to v0.2.121 | other | SKIP:version-bump | version only | — | |
| `fd7d268ca` | 10-24 | #988 | iOS: terminals, side chats and subagents beside every session | android-parity | DECLINED | wave-9 #23 (Android terminals/CompanionStrip wontfix) + #22 (mobile-core terminal) | — | Side chats + subagent viewer already exist in their older shape (`sidechats/SideChatsSheet.kt`, `sidechats/SubagentViewer.kt`, `transcript/widgets/SubagentLink.kt`). The `crates/term` extraction only serves the mobile terminal |
| `dfbb6876c` | 10-24 | #1007 | iOS terminals: direct WebRTC link to the host | android-parity | DECLINED | wave-9 #23; WebRTC/edge relay never ported (ADR 0004) | — | `term-link` crate and `preview/viewer.rs` go with it |
| `3c97a05cf` | 10-24 | #1010 | iOS: paint terminal box/block glyphs; fix subagents chip icon | android-parity | DECLINED | wave-9 #23 (glyph painter); chip icon fix lives in `CompanionStrip.swift` (iOS SVG-at-1pt rasterization bug), and Android has no CompanionStrip | — | Wave-9 #21's "chip icon" item has no Android target; tick it off |
| `0e1b891f9` | 10-24 | — | Bump version to v0.2.122 | other | SKIP:version-bump | version only | — | |
| `d97756075` | 10-24 | #985 | Fix Codex approval policy for MCP tools | harness | PORTED | `crates/harness/src/codex/mod.rs:125 default_tools_approval_mode = "approve"` (+ test at :2881) | — | |
| `4e5c05702` | 10-24 | #1016 | Cloud: live subagent transcripts; engine download that can't hang | cloud | SKIP:cloud | `edge/src/cloud/install-script.ts`, `edge/src/runner-access.ts` only | — | |
| `2ea4e3a05` | 10-24 | #1014 | iOS: rest the composer level with the sidebar toolbar | android-parity | MISSING | Android uses a fixed `BottomStackMetrics.KeyboardGap = 8.dp` (`session/BottomStackMetrics.kt:30`, `SessionBottomStack.kt:235`). No sidebar-toolbar measurement or beside-sidebar rest gap | S | Tablet split only: centre the resting composer on `SidebarToolbar`'s row |
| `ddf931624` | 10-24 | #958 | Transcript rail: prompts land below the titlebar, one tick per prompt | ui-desktop | MISSING | HEAD `crates/ui/src/rail.rs` differs from pre-#958 by 12 lines; still has `tick_buckets`/`bucket_of` downsampling (`rail.rs:145-159, 462-469`); wave-9 #12 ready-for-agent | M (with d460e8fe1) | Port together with d460e8fe1 (the final rail.rs) |
| `77a3c0ff1` | 10-24 | #998 | New project: type a folder's path, with ⇥ completion | ui-desktop | PORTED | `a39f89e37` (ancestor of HEAD); `crates/ui/examples/new-project-path-fixture.rs`; Windows-separator follow-up `77a8e30a6` | — | |
| `2ca102402` | 10-24 | #1020 | CI: cargo-nextest into a run-scoped bin dir on macOS | ci | SKIP:upstream-ci | `.github/workflows/macos.yml` | — | |
| `f6d4b1a01` | 10-24 | #992 | Composer: keep each project's checkout pick across switches | ui-desktop | PORTED | `faca1d333` (ancestor of HEAD) | — | |
| `684becf0f` | 10-24 | #609 | Android app on the shared Rust mobile core | other | SKIP:upstream-android | `apps/android/**`, upstream android.yml, landing | — | ADR 0009: reference only |
| `ea3dcc811` | 10-25 | #1026 | Pull request links: Default browser / Open links in Zeron / In-app browser | ui-desktop | PARTIAL | Linux test fail-fast ported (`327fef69d`, wave-9 #16). No `pullRequestOpenIn`/`PullRequestDestination` in HEAD; PR badge click is `cx.open_url` (`change_requests.rs:~176`) | S–M | Most of it targets the PR view and board that Roboco lacks (wave-7 #21 PARKED). Portable without that: an "In-app browser / Default browser" choice for sidebar PR badges, and the "outside press only hides the link card" fix in `markdown/link_interaction.rs` |
| `8ae969ad9` | 10-24 | #1029 | Android: Gmail-style Sessions page | other | SKIP:upstream-android | `apps/android/**` only | — | |
| `a9e6c48b7` | 10-25 | #1023 | Previews: only ports Zeron started; list them beside the chat | browser/previews | PORTED | `ROBOCO_ORIGIN` in `crates/preview/src/discovery.rs`, `crates/proto/src/preview.rs`; Ports card `SessionPreviews` (`shell/session_info.rs:58`); test `project_action_commands.rs` | — | Main's agent-previews port `b6b7c8aa` plus the wave-9 merge; edge `preview-room` excluded |
| `534a5742c` | 10-24 | #1030 | Bump version to v0.2.123 | other | SKIP:version-bump | version only | — | |
| `79fd52198` | 10-24 | #1032 | Releases: Android APK stable name uncached; unique build names | ci | SKIP:upstream-android | upstream `android.yml` R2 upload + `edge/src/releases.ts` | — | Roboco ships APKs via `release.yml` and the GitHub Release |
| `4443e6b58` | 10-25 | #1028 | Session card: snap on chat switch instead of fading over the next chat | ui-desktop | MISSING | `shell/session_info.rs:290-306` has no `session_info_snap`; tween still runs on a switch | S | Near-verbatim: field + one line in `shell.rs` select + `session_info_frame` change + test |
| `187b40243` | 10-25 | #1031 | Syntax: Luau highlighting | files/editor | MISSING | No `luau` anywhere in `crates/**/*.{rs,toml}` | S | `tree-sitter-luau` 1.2.0 dep + rewritten query in `crates/syntax/src/lib.rs`, icon in `file_icons.rs`, fence alias in `transcript.rs` |
| `a4187fd51` | 10-25 | #1034 | Transcript rail: ten ticks at most; hover opens prompt list | ui-desktop | SUPERSEDED | Reverted by `d460e8fe1` (#1049), which keeps only #1034's band centring and pinned-to-bottom lighting fix | — | |
| `c04e37498` | 10-25 | — | Bump version to v0.2.124 | other | SKIP:version-bump | version only | — | |
| `d460e8fe1` | 10-25 | #1049 | Transcript rail: one tick per prompt; long chats scroll the rail | ui-desktop | MISSING | Same as ddf931624; no `rail-fixture` example in HEAD | M | Take upstream's final `rail.rs` (+ `transcript.rs` hooks, `examples/rail-fixture.rs`) |
| `255c540d6` | 10-25 | — | Bump version to v0.2.125 | other | SKIP:version-bump | version only | — | |
| `85006f4b4` | 10-25 | #894 | Files editor: drop stale syntax highlights after an external reload | files/editor | MISSING | `crates/ui/src/files/editor_adapter.rs:29 RobocoInputHighlighter` has no `unverified_source` / `source_matches_edited_text` | S | **Crash fix.** Stale ranges cut a multi-byte char and panicked in line layout. Cross-platform: the stale-range bug is generic; only the panic site was on macOS |
| `4f465702d` | 10-25 | #907 | Keep the fork marker title from painting over popovers | ui-desktop | MISSING | `transcript.rs:9251-9261` still uses `.w_full().truncate().text_center()` | S | 10-line flex-centre swap |
| `bc892abd0` | 10-25 | #950 | Session card: branch row shows the branch's changes, not the working tree | ui-desktop / git | MISSING | No `ensure_branch_totals` / `Changes::for_branch` / `add_branch_diff_surface` in HEAD; dependencies `changes.rs:937 default_base_ref` and `DiffScope::Branch` are present | S–M | `session_info.rs` SessionDiffs reshaped; click opens the Diff pane on Branch changes |
| `325384d8b` | 10-25 | #1052 | Transcript: agent senders and provider switches as captions over the message | ui-desktop / engine | MISSING | HEAD renders `Message from {name}` bold inside the bubble (`transcript.rs:8776 agent_message_display`, :8849) and a divider seam (`harness_switch_marker`, :9310). The doc_host queued path (`doc_host.rs:2816-2828`) writes the message without `note_provider_switch` first | M | Caption `Message from [mark] Chat` (click opens sender), right-aligned `[logo] From → [logo] To` seam over the message, plus a 1-line engine change so the seam lands with the message. Parser must handle Roboco's orchestration-v2 header variants |
| `f65c5bc60` | 10-26 | #1062 | Mobile: agent senders as a caption over the message (iOS + Android) | android-parity / mobile-core | MISSING | No sender handling in `crates/mobile/src/layout` or `apps/android` (no `Message from` outside `proto/agent.rs` doc and the desktop). Android bubbles show the raw `[Message from Roboco chat …]` routing header | M | Adds `crates/proto/src/agent_messages.rs` (header writer/parser moved into proto), `layout/rows.rs` caption row, tap opens the sending chat (phone: push over this chat; tablet: main column), `brand:<harness>` transcript icons |
| `962a3207c` | 10-26 | — | Bump version to v0.2.126 | other | SKIP:version-bump | version only | — | |
| `d58474f0f` | 10-26 | #1069 | Cloud: per-account caps on awake machines and chats | cloud | SKIP:cloud | `edge/src/cloud/*` | — | |
| `98abb77f1` | 10-26 | #993 | Vault: keep the canary tests offline | cloud | SKIP:cloud | `edge/vault/test/*` | — | |
| `189115f36` | 10-26 | #1012 | Codex view_image shows in the chat; Browser says where local files open | harness / browser | MISSING | No `imageView` arm in `crates/harness/src/codex/normalize.rs`; no `local_file_address` / "Local files can't be opened" in `crates/ui/src/browser/model.rs` | S | Two independent halves. The browser half covers `C:\…`, `c:/…` and `\\server\share` paths (Windows) |
| `978e8dacb` | 10-26 | #1042 (+#1041 code) | Claude subagents: forward a foreground subagent's text and thinking | harness/engine | MISSING | No `CLAUDE_CODE_FORWARD_SUBAGENT_TEXT`, no `is_chat_spawn` spawn gate in HEAD | M | Also carries #1041's code: sinks only for this chat's spawn chips (fixes a ● badge over an empty lanes card), forked Skill → `Agent: /name` spawn, tagged thinking → subagent reasoning. Touches `doc/schema.rs`, `engine/sessions.rs`, `claude/{mod,normalize,wire}.rs`, `proto/agent.rs` |
| `d8a14b35b` | 10-26 | #1068 | Messages an agent sends other chats read as one line naming the recipient | ui-desktop / android-parity / mobile-core | MISSING | No `SentMessage` row / `render_chat_caption` / `Message to` in HEAD; `send_message` calls still show as tool rows | M | Depends on 325384d8b (shared caption) and f65c5bc60 (`proto/agent_messages.rs`). Mobile: new caption row in `layout/rows.rs` |
| `ec0897feb` | 10-26 | #1066 | Sidebar: a session left before its message lands shows its run, not Failed | ui-desktop | MISSING | `state.rs:653 PendingSend` exists, but no `adopt_pending_sends` / `adopted_by` / `begin_first_send` | M | Visible bug: a chat left before its host writes the first message (fresh worktree checkout) reads Working, loses its done chime, then shows "Failed" at 120s mid-run. Also a Linux browser-fixture typing-race fix (`examples/browser-fixture/linux.rs`) |
| `2514e3edf` | 10-26 | #1041 | Claude subagents: count and open only this chat's spawns | docs | SKIP:docs-only | `docs/media/subagent-spawn-gate/*` only | — | Its code landed squashed inside 978e8dacb (#1042), so port it from there |
| `e1d714daf` | 10-26 | — | Landing: a privacy policy at zeron.sh/privacy | landing | SKIP:landing | `apps/landing/**` | — | |
| `45bea1cf3` | 10-26 | — | Landing: privacy contact | landing | SKIP:landing | `apps/landing/**` | — | |
| `44d9beb1e` | 10-26 | #1065 | Session card: a row click switches sessions while the card is open | ui-desktop | SKIP:macos-only | The fix targets zui's scene overlay, which only `browser/macos.rs:330 enable_scene_overlay()` turns on, and AppKit's release ordering. Roboco also lacks the hover card itself (`session_hover_card.rs` absent; wave-8 #13 gated) | — | When wave-8 #13 ports #903, port the tooltip-layer form directly (upstream's current shape) |
| `ae1ec6ad8` | 10-26 | #1045 | Fork: settle the running subagent chips a fork copies | engine | MISSING | `crates/engine/src/rpc.rs:1959-1965` copy loop only resolves `Input` parts; a `Running` `subagent_status` is copied as-is | S | Without it, a forked chip and its lanes row spin forever. Add `side_chats.rs` tests (already in ui-tests.yml `--test side_chats`) |
| `daf047b7f` | 10-26 | #1048 | Tooltips on the icon-only buttons that had none | ui-desktop | MISSING | `comment_ui.rs:34` has `aria_label("Add comment")` but no tooltip; no `PickerHint("Back · Esc")` in `pickers/compact.rs` | S | Skip the PR/billing/cloud buttons. Keep comments "+", model-picker tabs/star/Back, settings swatches/close/zoom, actions icon picker, voice-error dismiss, crumb, Linux caption buttons + a11y names |
| `95b294658` | 10-26 | #1043 | Claude subagents: give a typed /skill's fork a chip and a tab | harness/engine | MISSING | No `forked-command-` handling in `claude/normalize.rs`; no `crates/engine/tests/claude_subagents.rs` | M | Needs 978e8dacb's spawn gate first |
| `81adbdabf` | 10-26 | — | Sidebar: start a new chat from a "No project" group | ui-desktop | MISSING | `shell/tabs.rs:220 open_new_session(project: Option<String>)`; no `NewSessionTarget`. Roboco groups project-less chats as `home:<device>` (`spaces.rs:2538`) with no `+` | S | Useful for fleet (one "No project" group per device). Gate the `+` on the composer device picker's list |
| `4a0d416cf` | 10-26 | #1051 | Cursor shim tests: give a shim's first frame time for node to start | ci (Windows) | MISSING | `crates/harness/tests/cursor_shim.rs:190` still uses a 5s timeout for every frame; no `Frames` reader | S | **Windows CI flake**: `windows.yml:81` runs `--test cursor_shim`. First frame gets 30s |
| `4879a4754` | 10-26 | #1025 | Compact mode: stop clipping subagent cards in the tool fold | ui-desktop | MISSING | `transcript.rs:8241-8260` row heights budget spawn links at `TOOL_TREE_ROW_HEIGHT` under a compact fold; no `is_spawn_link → CHIP_HEIGHT` early return (helper exists at :395) | S | 5-line fix + test (+ optional `subagent-fold-fixture`) |
| `fbdda0a0c` | 10-27 | #997 | macOS browser: clicks outside the page take the keyboard back | browser | SKIP:macos-only | AppKit first-responder fix; the commit says zui already does it on Windows (`reclaim_focus_from_native_child`) | — | |
| `b8ed9d9c2` | 10-27 | #1046 | Providers: a switch to turn native subagents off | settings / harness / engine | MISSING | No `SIDE_CHATS_ONLY`, `GetNativeSubagents`, `switches_native_subagents` or `settings/delegation.rs` in HEAD | M | Fits Roboco's orchestration-v2 side chats well. Wire: `rpc` Get/SetNativeSubagents, `McpServer::SIDE_CHATS_ONLY_ENV` → `ROBOCO_SIDE_CHATS_ONLY`; MCP tool-description tweak; Claude `--disallowedTools=Agent,Task,Workflow`; parked-session replace |
| `180a51088` | 10-27 | — | Landing: drop rauchg from the sponsor chip | landing | SKIP:landing | `apps/landing/**` | — | |
| `64a26d359` | 10-27 | #1022 | Questions: show what was answered, and close one without answering | engine / ui-desktop / android-parity / proto | MISSING | `crates/doc/src/schema.rs:968 resolve_input(&self, request_id)` takes no answers; no `answers` on `Input` parts; no corner close on Android `composer/QuestionCard.kt` | L | Wire: `proto/agent.rs` `answers: Option<Vec<UserInputAnswer>>`, `proto/view.rs` (+270), doc schema. Harness decline semantics (`lib.rs`: empty answers = decline; claude/opencode). Desktop chips show answers; Android/iOS question panel gets a "Close without answering" capsule. Skip `muse/*` and `edge/*` hunks |
| `d5a015226` | 10-27 | #1044 | Claude subagents: fill a typed fork's tab while the CLI holds it | harness | MISSING | No `crates/harness/src/{jsonl_tail.rs, claude/fork_tail.rs}`; `TailReader` still private in `acp/subagent.rs` | M | Needs 95b294658. Moves the Grok `TailReader` to a shared `jsonl_tail` (Roboco has the Grok ACP reader) |
| `00cc2e059` | 10-27 | #1038 | Remote chats: show the sync, not an outage, while a room catches up | cloud | SKIP:cloud | Edge chat2 sync-room cursor and room-dial stall clock (`chat2_host.rs` absent in HEAD; `chat_persistence.rs` header: "no chat2 room cursor exists engine-locally"). Roboco's `ConnectivityState` stays `Disabled` without edge | — | The UI idea (loader + "Syncing…" for a cold remote chat; calm fork-failure empty state with Retry) could be redone for fleet relays, but none of this code applies |
| `5d2b91901` | 10-27 | #1036 | Agents can show local images in chat: show_image, and Markdown images in replies | engine / ui-desktop / mcp | MISSING | No `show_image`, `ReadChatImageChunk` or `reply_images.rs` in HEAD | L | Wire: rpc method + MCP tool. Folder-jailed reads (`uploads.rs` +424), the Windows root-refusal test fix, right-click Copy image, aspect-correct scaling. Android shows `show_image` output for free (it reuses the generated-image part) |

## Port candidates (MISSING + PARTIAL), most valuable first

### 85006f4b4 (#894) Files editor: drop stale syntax highlights after an external reload
- For users: an open, clean file that changes on disk (an agent edits it) can crash the app or paint wrong colours. Upstream's crash was a highlight range ending inside a multi-byte char such as `→`. On Windows/Linux the stale ranges still colour the new text wrongly, and the panic risk depends on the text system.
- Missing: `unverified_source` on the highlighter, `source_matches_edited_text`, the first-update check in `update()`. Upstream file `crates/ui/src/files/editor_adapter.rs` maps to Roboco `crates/ui/src/files/editor_adapter.rs` (`RobocoInputHighlighter`).
- Deps: none. Tests: the test-only `probe` module and the files-surface reload test. Risk low.
- Effort S

### 4a0d416cf (#1051) Cursor shim tests: first frame gets 30 s
- Why: Roboco's `windows.yml` runs `--test cursor_shim`. On Windows runners `node` startup alone can exceed the 5s frame timeout, so the test flakes. Upstream hit it again on #1036 ("Retrigger CI: cursor_shim frame timeout on the Windows runner").
- Missing: the `Frames` reader in `crates/harness/tests/cursor_shim.rs` (first frame 30s, later frames 5s). Keep `ROBOCO_CURSOR_STATE_DIR`.
- Effort S

### ec0897feb (#1066) Sidebar: a send left before it lands shows its run, not "Failed"
- For users: start a chat (the host first checks out a fresh worktree), switch away before the first message lands. The row reads Working whatever happens, the done chime is lost, and it turns "Failed" 120s in while the run is fine. Common in a multi-chat workflow.
- Missing: `adopt_pending_sends`, `adopted_by`, `begin_first_send`/`begin_send` in `crates/ui/src/state.rs` (+431), and the composer hook (`composer.rs` +7). Roboco's `PendingSend` and `ack_pending_send_from_transcript` exist (`state.rs:653, 1502`), so this is an extension, not new substrate.
- Deps: none; no wire change. Risk: Roboco's state.rs has fleet/EngineTarget routing; the adoption rules key on session rows, which Roboco has.
- Effort M

### 189115f36 (#1012) Codex view_image in the chat; Browser refuses local paths with a hint
- For users: (a) Codex's `view_image` calls are invisible today, so the model "sees" an image the transcript never shows. (b) Typing `C:\Users\me\page.html` or `\\server\share\x.html` in the Browser address bar loads `https://users/...`. Windows users hit (b) directly.
- Missing: the `imageView` thread-item arm in `crates/harness/src/codex/normalize.rs` (mapped to ReadFile) + the `fake-codex.sh` fixture + test; `local_file_address` + `LOCAL_FILE_ADDRESS` in `crates/ui/src/browser/model.rs` + unit test.
- Deps: none. Effort S

### ae1ec6ad8 (#1045) Fork: settle the running subagent chips a fork copies
- For users: forking a chat while a subagent runs leaves a forever-spinning chip and lanes-card row in the fork.
- Missing: the `MessagePart::Tool { subagent_status: Running } → Failed` arm in the fork copy loop (`crates/engine/src/rpc.rs:1961`). Also check Roboco's other fork paths (orchestration-v2 `fork_chat` / fork-from-reply / Codex thread fork, `41ff18d03`) for the same copy. Tests in `crates/engine/tests/side_chats.rs` (already run by ui-tests.yml).
- Effort S

### 978e8dacb (#1042, carries #1041) → 95b294658 (#1043) → d5a015226 (#1044) Claude subagent fidelity
- For users: a foreground Claude subagent's tab shows only tool calls (no text or thinking). The ● N badge can count spawns the lanes card never lists. A typed `/skill` that runs in a fork shows nothing until it ends.
- Missing: `CLAUDE_CODE_FORWARD_SUBAGENT_TEXT=1` in the run env; `is_chat_spawn` spawn gate in `engine/sessions.rs`; forked Skill → `Agent: /name` spawn and tagged thinking → reasoning in `claude/normalize.rs`; typed-fork chip from `task_started` without a tool id plus `forked-command-<name>` routing (#1043); a live tail of `<project>/<session>/subagents/agent-<task>.jsonl` (`claude/fork_tail.rs`, shared `jsonl_tail.rs` extracted from `acp/subagent.rs`) (#1044). New `crates/engine/tests/claude_subagents.rs`, `fake-claude.sh` additions, three live fixtures.
- Deps: strictly in order (1042 → 1043 → 1044). `doc/schema.rs` + `proto/agent.rs` touch (additive). Roboco's Claude normalize has its own background-agent logic (`normalize.rs:313-369, 598`), so expect reconciliation.
- Effort M each (L together)

### 64a26d359 (#1022) Questions: record what was answered; close without answering
- For users: the transcript says which options were picked. The question panel gets a corner ✕ ("Close without answering") that tells the agent the questions went unanswered, instead of leaving the run blocked.
- Missing: `answers` on doc `Input` parts and `resolve_input(request_id, answers)` (`crates/doc/src/{schema,parts}.rs`); `proto/agent.rs` `answers`; `proto/view.rs` (+270); engine `doc_host`/`rpc`/`sessions`; harness decline semantics (`lib.rs`, `claude/mod.rs`, `opencode/mod.rs`); `mcp/transcript.rs`; desktop `composer.rs` close + `transcript.rs` answer chips; `crates/mobile/src/layout/rows.rs`. **Android parity**: the iOS `QuestionPanel.swift` adds the 36pt glass capsule close and text that wraps clear of it, so port that to `composer/QuestionCard.kt` / `QuestionActions.kt`.
- Deps: wire change, so run wiregen. Skip the `muse/*` and `edge/*` hunks.
- Effort L

### 5d2b91901 (#1036) show_image MCP tool + Markdown images in replies
- For users: agents can put screenshots and plots in the chat (`show_image {path}`), and `![alt](path)` in a reply renders inline, read through a folder jail. Android picks up `show_image` output with no app change (it reuses the generated-image part).
- Missing: everything: `engine/src/uploads.rs` (+424), `rpc.rs` ReadChatImageChunk, `rpc/src/lib.rs` method, `mcp/src/tools.rs` (Roboco `crates/roboco-mcp/src/tools.rs`), `ui/src/reply_images.rs`, `attachments.rs`, `markdown/render.rs`, `transcript.rs`, `docs/mcp.md`.
- Deps: wire (rpc method; `proto/agent.rs` +12). The included test fix refuses the filesystem root portably (Windows). It touches `pull_request_media.rs`, which Roboco lacks; skip that.
- Effort L

### 325384d8b (#1052) + f65c5bc60 (#1062) + d8a14b35b (#1068) Agent message captions (desktop + Android)
- For users: an agent-sent message shows `Message from [mark] Chat` over the bubble (click/tap opens the sender), and an agent's own `send_message` call reads as one line, `Message to [mark] Chat`, instead of a tool row. The provider-switch seam becomes a right-aligned caption over the message that switched. On Android the routing header currently shows **raw inside the bubble**: a clear parity gap.
- Missing: desktop `render_agent_sender`/`render_chat_caption`/SentMessage row (`crates/ui/src/transcript.rs`); `crates/proto/src/agent_messages.rs` (header writer/parser, also used by `roboco-mcp/src/tools.rs`); `crates/mobile/src/layout/rows.rs` caption rows; Android tap routing (phone: push over this chat; tablet: main column) and `brand:<harness>` transcript icons; the engine's `note_provider_switch` before the canonical write on the queued path (`doc_host.rs:2816`).
- Deps: 1052 → 1062 → 1068. Roboco's header has orchestration-v2 variants ("You are a side chat created by that chat…", `transcript.rs:17389-17405` tests), so the proto parser must accept them.
- Effort M each

### b8ed9d9c2 (#1046) Providers: Native subagents switch
- For users: a per-device switch (Settings → Providers → Claude Code). Off: top-level chats launch Claude without Agent/Task/Workflow and delegate through Roboco side chats. That matches Roboco's orchestration-v2 direction.
- Missing: `settings/delegation.rs` (+297), registry/workspace_host persistence, `GetNativeSubagents`/`SetNativeSubagents`, `McpServer::SIDE_CHATS_ONLY_ENV` (rename `ROBOCO_SIDE_CHATS_ONLY`), `switches_native_subagents`, the Claude launch flag, the MCP prompt tweak, parked-session replacement, tests in `restart_resume.rs`/`side_chats.rs`.
- Deps: wire. Effort M

### 71033cc96 (#975) Terminal overhaul (non-P2P slice) — wave-9 #17
- For users: zero-latency typing, IME/dead keys, mouse and focus reporting, cursor shapes, synchronized output, smoother scrollback. For fleet remote terminals, the host answers DA/CPR queries so agent CLIs don't read stray escapes, and the size is sent on open.
- Missing: `ui/src/terminal/predict.rs`, `emulator.rs`/`panel.rs`/`view.rs` rework, `engine/src/terminals/replies.rs`, `entities.rs` `host_replies`, TCP_NODELAY on IPC. Roboco has `engine/src/terminals/windows.rs` (ConPTY), so test the write path on Windows.
- Deps: wave-9 #11 (GPUI pin sync) per the wave plan. Wire: `host_replies`. Effort L

### ddf931624 (#958) + d460e8fe1 (#1049) Transcript rail — wave-9 #12
- For users: every prompt has its own tick (Roboco still buckets past 12, so most prompts are unreachable). A click lands the prompt below the titlebar, and long chats scroll the rail.
- Missing: upstream's final `crates/ui/src/rail.rs` (1015 lines vs Roboco's 807), `transcript.rs` hooks, `examples/rail-fixture.rs`. Skip a4187fd51 (reverted).
- Effort M

### bc892abd0 (#950) Session card: branch totals = branch over base
- For users: the branch row's `+N −M` counts only uncommitted lines today. Upstream counts the branch over its default base and opens that diff on click.
- Missing: `SessionDiffs` rework (`shell/session_info.rs`), `Changes::for_branch`, `Shell::add_branch_diff_surface`. Deps present (`default_base_ref`, `DiffScope::Branch`). Route LIST_BRANCHES/diff through the chat's EngineTarget (fleet).
- Effort S–M

### 4879a4754 (#1025) Compact fold clips subagent cards
- Missing: the `is_spawn_link → CHIP_HEIGHT` early return in the fold's row-height budget (`transcript.rs:~8249`), plus the `debug_selector` and test. Effort S

### 4443e6b58 (#1028) Session card snaps on chat switch
- Missing: the `session_info_snap` field, its set in the chat-select path, and the `session_info_frame` change + test. Effort S

### 81adbdabf "No project" group `+`
- Missing: `NewSessionTarget::{Project, NoProject(device)}` in `shell/tabs.rs` and the group header `+` for `home:<device>` groups (`spaces.rs`). More useful with fleet devices. Effort S

### daf047b7f (#1048) Icon-button tooltips + a11y names
- Missing: tooltips/aria on about 20 icon-only buttons (skip PR/billing/cloud). Effort S

### 4f465702d (#907) Fork marker title over popovers
- Missing: flex-centre instead of `text_center()` in `fork_marker` (`transcript.rs:9251`). Effort S (trivial)

### 187b40243 (#1031) Luau highlighting
- Missing: `tree-sitter-luau` + the rewritten query + icon + fence alias. Effort S

### 6f2721fab (#981) Android: keep the session through fold/unfold; pin on width change
- Android parity: (1) when a reflow for a new width lands while following, pin to the tail at once instead of springing. This is `TranscriptViewport.onFrameApplied` and helps phone rotation too. (2) Optional: move the live session between the split column and the phone stack without a rebuild (`shell/CarryOver.kt` currently re-pushes on the next frame).
- Effort S (1) / M (2)

### 2ea4e3a05 (#1014) Android tablet: composer level with the sidebar toolbar
- Android parity: beside the sidebar, centre the resting composer on `SidebarToolbar`'s row instead of the fixed 8dp gap (`BottomStackMetrics.KeyboardGap`). Effort S

### ea3dcc811 (#1026) PARTIAL: PR link destinations
- Ported: the Linux test fail-fast (`327fef69d`). Remaining portable without the parked PR view (wave-7 #21): an In-app browser / Default browser choice for sidebar PR badges (today `cx.open_url`), and the "outside press only hides the destination card" fix in `markdown/link_interaction.rs`. The rest (Pull request view destination, settings key migration `pullRequestOpenIn`) waits on the PR-board decision.
- Effort S–M
