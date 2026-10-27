# Part prs-A — open upstream PRs (123, #1086 … #746, newest first)

Audited against Roboco `HEAD` = `85be4485a` (feature/fleet-follow). All 123 PRs are OPEN and unmerged in
`zeronsh/zeron`. None has a maintainer approval; nearly all are `REVIEW_REQUIRED` with no human review.
Because these are open PRs, the classes are adapted from the brief:

- `FIX-CANDIDATE`: a bug fix or polish item. The bug was verified present in Roboco HEAD.
- `FEATURE-CANDIDATE`: a larger feature, and a product call.
- `ALREADY`: Roboco already has the behaviour, or doesn't have the bug.
- `STALE/RISKY`: abandoned, or the author or reviewers pushed back.
- `SKIP:<reason>` / `N/A`: N/A means it builds only on an upstream feature Roboco doesn't have.
- `DECLINED`: a user decision is on record.
- `SUPERSEDED`: replaced by a later change.

No Roboco commit or `.scratch` ticket references any of these PR numbers, except #817 (ADR 0009 target behaviour, done) and #891 (Muse, declined).

**Summary: 123 PRs — FIX-CANDIDATE 35, FEATURE-CANDIDATE 38, ALREADY 13, STALE/RISKY 7, DECLINED 1, SUPERSEDED 1, N/A 8, SKIP 20**
(SKIP: cloud 7, upstream-android 6, ios 3, upstream-ci 1, version-bump 1, translation-docs 1, macos-only 1.)

Four mixed PRs are counted by their portable slice: #1076 and #1081 as FIX, #940 as STALE/RISKY, #944 as SKIP. Their extra slices are listed in Candidates.

## Table

| PR | date | author | title | area | class | evidence | effort | notes |
|---|---|---|---|---|---|---|---|---|
| #1086 | 2026-10-27 | alexandre-vl | Cloud: an Anthropic API key can name its LLM gateway | cloud | SKIP:cloud | vault-sealed `{key, baseUrl}`, edge/vault, Cloud settings dialog | - | All of it is Cloud-vault plumbing. A local `ANTHROPIC_BASE_URL` per account would be a separate idea |
| #1084 | 2026-10-27 | anaisbetts | Fix the Windows Icon to not be macOS'y | updater/install | ALREADY | `dist/windows/roboco.ico` is Roboco's own art from `scripts/generate-app-icons.py` (f34cb0cf8); the alpha bbox fills the canvas at all 7 sizes | - | Binary-only PR |
| #1081 | 2026-10-27 | katulevskiy | CI: LLD and a Dev Drive for Windows builds (~11% faster) | ci | FIX-CANDIDATE (slice) | Roboco `windows.yml:50-147` builds with default `link.exe` on C:. No `rust-lld` and no Dev Drive anywhere in `.github/` | S | Stacked on #1077, but the two composite actions (`fast-linker`, Dev Drive in `windows-ci-setup`) are separable. Measured −11% on cold Windows builds |
| #1080 | 2026-10-27 | NoboKik | Find in thread (⌘F) and message search in the command palette | ui-desktop/engine/proto | FEATURE-CANDIDATE | no `transcript_search`/`SearchTranscripts`/`find.rs`; `shell/command_palette.rs` has no Messages tab | M-L | New RPC (wiregen); FTS5 index is engine-local (fits ADR 0004). Demo only, no review |
| #1079 | 2026-10-27 | app/zeronsh | Tasks and side chats: chats report back when they're done | engine/mcp/ui | FEATURE-CANDIDATE (Coordinator/Tasks slice) | Side-chat report-back is ALREADY in Roboco: `crates/engine/src/completion_notices.rs:1`, `roboco-mcp/src/jsonrpc.rs:48` (notify default), commits ccf29200d, fdb637ba0, 7faa32803. No `Coordinator`, `rename_chat` or `NO_REPLY` in HEAD | L | Stacked on #1076 (Cloud); no review |
| #1077 | 2026-10-27 | katulevskiy | CI: one entry workflow with admission, reuse, priority/locks | ci | SKIP:upstream-ci | Built for upstream's shared runner pool. Non-CI hunks: `credentials.rs` git identity (Cloud-only, absent in Roboco); a `ZERON_STRESS_ROUNDS` knob for `cursor_shim.rs` stress test (Roboco `crates/harness/tests/cursor_shim.rs:206` still loops 100) | - | Ideas worth stealing: stress-round env knob, merging small `tests/*.rs` into one `tests/misc` binary (link time) |
| #1076 | 2026-10-27 | app/zeronsh | Agents reach sleeping Cloud machines, Cloud machines stay up | mcp/engine (+cloud) | FIX-CANDIDATE (MCP robustness slice) | Codex gets no `tool_timeout_sec` for the injected server (`crates/harness/src/codex/mod.rs:117-126`), so Codex aborts `wait_for_turn` (DEFAULT_WAIT 600 s, `roboco-mcp/src/tools.rs:27`) after its 60 s default. No `notifications/progress` and no reply-size cap in `roboco-mcp/src/jsonrpc.rs`. MCP command from bare `current_exe()` (`engine/src/sessions.rs:3062`) gives `… (deleted)` after a Linux update | S-M | Cloud wake/heartbeat/edge parts are skipped. Bad-input-line skip is ALREADY (`jsonrpc.rs:115-125`) |
| #1075 | 2026-10-27 | app/zeronsh | Settings search: Windows test no longer expects the Appshots page | ui-desktop (tests) | N/A | Roboco has no settings search (#1018 not ported) | - | Take it together with #1018 if that is ported (`appshots::is_desktop()` is false on Windows, `appshots.rs:27`) |
| #1072 | 2026-10-27 | app/zeronsh | Visuals: tabs that switch, livelier charts, script reference | visuals | N/A | no `crates/visual`/mlua in Roboco | - | Only matters if Luau Visuals (#900) is ported |
| #1071 | 2026-10-26 | ClemannD | edge: reduce redundant Durable Object storage writes | cloud | SKIP:cloud | `edge/src/*` only | - | |
| #1059 | 2026-10-27 | Gratenes | Android: smoother Sessions page while agents work | android | SKIP:upstream-android | upstream's own Android app (ADR 0009: reference only) | - | Perf idea for our Sessions screen; not checked in depth |
| #1058 | 2026-10-26 | Gratenes | Android: open chat history with a swipe | android | SKIP:upstream-android | same | - | Roboco Android uses predictive back (`apps/android/parity/CHECKLIST.md:122`) |
| #1057 | 2026-10-26 | Gratenes | Android: a usage pill in the composer with a quick account switcher | android (+mobile FFI) | SKIP:upstream-android | Built on upstream `crates/client` `device_agent_usage` (Roboco uses `mobile-client`). ADR 0009 drops provider accounts on Android | - | Its lenient decoder for unknown agents is a good idea for `mobile-client` |
| #1056 | 2026-10-26 | Gratenes | Android: long pickers open as a searchable, sortable sheet (draft) | android | SKIP:upstream-android | same | - | |
| #1055 | 2026-10-26 | Gratenes | Android: Sessions groups show their first 5 chats | android | SKIP:upstream-android | Roboco Android has no "Show all N" cap (no hits in `apps/android`) | - | Small UX idea, not iPhone behaviour |
| #1053 | 2026-10-26 | ClemannD | Sidebar: show persistent provider usage cards (draft) | ui-desktop | FEATURE-CANDIDATE | usage only in composer ring popover (`crates/ui/src/account_usage.rs`) and Settings → Accounts; sidebar footer has only `failover_card` (`shell.rs:5116`) | M | DRAFT, unreviewed |
| #1050 | 2026-10-26 | xzebra | Projects: choose checkout icons with image previews (draft) | ui-desktop/engine/proto | FEATURE-CANDIDATE | `crates/ui/src/shell/project_icon.rs:6-24` auto-detects only | M-L | DRAFT; wire/doc change; iOS slice maps to Android parity |
| #1033 | 2026-10-26 | gaelcado | Transcript: show when a reply finished, not when it started (draft) | doc/ui | FIX-CANDIDATE | `crates/ui/src/transcript.rs:1851` `last.timestamp = Some(entry.created_at)`; no `finished_at` in `crates/doc/src/schema.rs` (only `duration_ms` :62) | S-M | DRAFT. Cheap port: `created_at + duration_ms` |
| #1006 | 2026-10-24 | freethinkel | Terminal: libghostty-vt, sprite glyphs, ligatures, graphemes | terminal | FEATURE-CANDIDATE (risky) | Roboco terminal = `alacritty_terminal` 0.26 (`Cargo.toml:153`, `crates/ui/src/terminal/emulator.rs`) | L | Needs Zig 0.16 on Windows/Linux dev and CI, +6k lines, unreviewed. The sprite-glyph renderer alone is the low-risk slice |
| #989 | 2026-10-25 | katulevskiy | Experimental: edit agent memory, stop one command/subagent, system prompt | harness/engine/ui | FEATURE-CANDIDATE | none of the three exists in proto/settings | L | +9480, no review. System-prompt setting is the smallest slice (S-M) |
| #987 | 2026-10-24 | katulevskiy | Side chats: move one into the sidebar as its own session | engine/ui | FEATURE-CANDIDATE | no parent-detach RPC / "Move to sidebar" | M | no review |
| #986 | 2026-10-25 | hideoneric | Titlebar: order the pane and explorer toggles like the columns they open | ui-desktop | FIX-CANDIDATE | `crates/ui/src/shell/tabs.rs:495` `toggle-files-panel` before `:510` `toggle-changes` (reversed vs columns) | S | pure child swap |
| #979 | 2026-10-23 | app/zeronsh | chat3: transcript plane behind a labs flag | cloud/sync | SKIP:cloud | edge chat-room/transcript plane, sync | - | |
| #976 | 2026-10-23 | dependabot | Bump the github-actions group (10 updates) | ci | SKIP:version-bump | Roboco pins `actions/checkout@v4`, `upload-artifact@v4`, `download-artifact@v4` (`windows.yml:53,187,203`) | - | Bump these ourselves before GitHub removes Node 20 actions |
| #965 | 2026-10-24 | jsgrrchg | Session notes: a synced Markdown scratchpad per main chat | doc/engine/ui | FEATURE-CANDIDATE | no `SessionNotes`/`WatchSessionNotes` | L | outbox/ChatRoom sync parts N/A; an engine-local slice would port |
| #964 | 2026-10-26 | Master0fFate | Add Lua scripts | engine/mcp/ui | FEATURE-CANDIDATE | no `mlua`/luau dep in any Cargo.toml | L | reuses upstream's visuals runtime Roboco lacks |
| #963 | 2026-10-20 | jsgrrchg | Drag side-chat tabs into a composer to mention them | ui-desktop | FEATURE-CANDIDATE | `RightTabDrag` (`shell.rs:1241-1246`) has no chat id; `shell/chat_dropzone.rs:55`; `composer.rs:6686 add_chat_mention` reusable | S | no wire change |
| #960 | 2026-10-20 | CrochetFeve0251 | Settings: choose when idle sessions close | engine/settings | FEATURE-CANDIDATE | hardcoded 30 min `crates/engine/src/sessions.rs:5010` | S-M | |
| #959 | 2026-10-20 | arafatamim | OpenCode: wait for the settled command catalog and route /compact | harness (opencode) | FIX-CANDIDATE | `crates/harness/src/opencode/mod.rs:499-510`, `:1884-1891` read `/api/command` once; no compact route; `native_command_request` `:3167` | M | only a quota-failed Copilot review |
| #955 | 2026-10-19 | xzebra | Engine: run git with the login-shell PATH so worktree hooks find user tools | engine | FIX-CANDIDATE | `crates/engine/src/repos.rs:304` bare `Command::new("git")`; helper `roboco_harness::compose_login_shell_path` exists (`harness/src/lib.rs:311`, already used by `source_control.rs:791`) | S | 2 lines; on Windows also merges registry user PATH |
| #952 | 2026-10-19 | nomomon | Claude: stop doubling a foreground subagent's opening prompt (draft) | harness (claude) | FIX-CANDIDATE | `claude/normalize.rs:800-819` seeds from spawn `prompt`; `:913-921` re-emits the tagged echo as a UserMessage, no dedupe (CLI ≥2.1.296) | M | DRAFT; confirm with a live capture |
| #951 | 2026-10-19 | CrochetFeve0251 | Docs: add French README | docs | SKIP:translation-docs | README translations | - | |
| #949 | 2026-10-19 | Sushants-Git | Lightbox: fix image flicker on open | ui-desktop | FIX-CANDIDATE | `crates/ui/src/image_viewer.rs:201-202` reset zeroes bounds, `:217-218` fits to 0×0 on first frame | S | +35/-2 with test; upstream main unchanged |
| #946 | 2026-10-18 | jsgrrchg | Composer: drop a browser tab in as a web page mention | ui-desktop/proto/browser | FEATURE-CANDIDATE | no `web_mentions`/favicon cache; `RightTabDrag` carries no URL | M | proto prompt addition (`roboco-web:` scheme); touches Linux `helper.c` |
| #945 | 2026-10-18 | jsgrrchg | Prototype: split view for chat multiplexing (draft) | ui-desktop | STALE/RISKY | author: "not for merge" | - | exploration |
| #944 | 2026-10-17 | UzenUPoZiTiV4ik | Computer use, desktop pets, and video wallpapers | ui-desktop/mcp | SKIP:macos-only (pets slice FEATURE) | computer use (cua-driver, `dist/macos`) and AVFoundation video wallpapers are `cfg(macos)`; `ui/src/pets.rs` is not OS-gated | (pets M) | author never compiled Windows/Linux; novelty |
| #940 | 2026-10-17 | rabden | UI polish: titlebar, settings, sidebar, context popover | ui-desktop (+mobile/audio drift) | STALE/RISKY (portable slices = FIX) | diff drifted to +3424/-1179 over 60 files incl. `crates/mobile`, `crates/audio`. Applicable: titlebar top-pad-only off macOS (`shell/tabs.rs:525`, `TITLEBAR_TOP_PAD=4` `proto/src/layout.rs:48`); context popover compact counts | S/slice | maintainer ping unanswered; cherry-pick ideas, not the branch |
| #939 | 2026-10-25 | jsgrrchg | Right pane: on-demand split panes | ui-desktop | FEATURE-CANDIDATE | flat `right_tabs`; no `right_layout`/`right_panes` | L | +2.4k, unreviewed |
| #938 | 2026-10-17 | jsgrrchg | Markdown: neutral inline code styling | ui-desktop | FIX-CANDIDATE (taste) | `crates/ui/src/markdown/render.rs:990-994` accent `code_text`/`code_wash` | S | author: "personal preference" |
| #937 | 2026-10-16 | laurentcodes | feat(ios): home screen, lock screen and control centre widgets | ios | SKIP:ios | WidgetKit/Live Activity only | - | |
| #936 | 2026-10-24 | jsgrrchg | Composer: drag pull request tabs in as mention chips | ui-desktop | N/A | Roboco has no PR tabs/board (`ui/src/change_requests.rs` = badges only) | - | depends on upstream PR board |
| #935 | 2026-10-16 | sheauhuu | feat(browser): visualize design controls and local widget state (draft) | browser | FEATURE-CANDIDATE (risky) | absent; stacked on #934/#933 | M | macOS-validated only; low value |
| #934 | 2026-10-16 | sheauhuu | feat(browser): preview session HTML content references (draft) | browser/markdown | FEATURE-CANDIDATE | no codex `visualize` marker handling | M-L | WebView2/Linux unverified by author |
| #933 | 2026-10-16 | sheauhuu | fix(markdown): render Codex file citations as file links (draft) | markdown (mobile-core) | FIX-CANDIDATE | `git grep codex-file-citation HEAD` empty, so `:codex-file-citation{path=…}` renders raw | S-M | fixes desktop and Android at once; closes upstream #424 |
| #932 | 2026-10-16 | peelar | Fix shifted word deletion in text inputs | ui-desktop | FIX-CANDIDATE | `crates/ui/src/composer.rs:1630-1640`, `:1704-1737` bind only `ctrl-backspace` (exact-modifier match) | S | mostly a macOS complaint; small Windows value |
| #930 | 2026-10-26 | fauzanazz | Add Oh My Pi as a native RPC harness | harness (new) | FEATURE-CANDIDATE | `HarnessId` has no omp (`crates/proto/src/agent.rs:8-30`) | L | wire change; Muse was declined, so same product call |
| #928 | 2026-10-16 | katulevskiy | Claude in Chrome: per-device setting, toggle + MCP | harness/engine/ui | FEATURE-CANDIDATE | no `--chrome`/`claudeChrome` in `crates/` | M | stacked on #926/#927; drop relay `targetDeviceId` parts |
| #927 | 2026-10-16 | katulevskiy | fix: slash menu loads in ~1s, then instantly | harness/engine | ALREADY | `claude/mod.rs:623-633` probe reply not gated on reap; `claude/discovery.rs` InitializeCache 120 s; `mod.rs:816-819` workspace_commands cache | - | Roboco lacks stale-while-revalidate past 2 min (minor) |
| #926 | 2026-10-16 | katulevskiy | mcp: launch args for agent chats | proto/mcp/harness | FEATURE-CANDIDATE | no `launch_args` on ChatConfig/RunRequest | M | wire + capability; base of #928 |
| #925 | 2026-10-16 | jsgrrchg | Chat chips show their session's harness mark | ui-desktop | FIX-CANDIDATE (polish) | `crates/ui/src/composer/chip.rs:165-176` `ChipKind::Chat` always `CHAT_ROUND_LINE` | S | author blames unrelated flaky test for CI |
| #924 | 2026-10-16 | jsgrrchg | Luau art: home visual + generative new-thread background (draft) | visuals | N/A | needs `crates/visual`/`visual-gpu` | - | |
| #923 | 2026-10-26 | jsgrrchg | Pull requests: Show commits in the activity rail | git | N/A | extends upstream PR board (#484 afca583c1), absent in Roboco | - | the PR board itself is the gap (main-branch audit) |
| #922 | 2026-10-16 | katulevskiy | fix: codex chats fail on omarchy (mise) | harness | ALREADY | Roboco never canonicalizes the codex run exe (`codex/mod.rs:761` uses `resolve_executable()`), so `mise app-server` can't happen; mise hints present | - | leftover: `model_context.rs:81-84` canonicalizes before `binary_version` (affects only a context-cache key) |
| #916 | 2026-10-15 | gaelcado | Voice: dot-matrix glyph everywhere, phone dictation through host (draft) | voice/ios | SKIP:ios | `crates/orb`, `apps/ios`, remote voice. Roboco has no orb, and ADR 0009 drops remote voice | - | +8384/-5901 |
| #914 | 2026-10-27 | gaelcado | Pull request pane follows the shared panel layout (draft) | ui-desktop | N/A | reworks PR board pane, absent | - | |
| #911 | 2026-10-24 | jsgrrchg | Markdown preview: run zeron-visual fences as native visuals | files/editor | N/A | needs Luau visuals (`show_visual`, #900) — `git grep show_visual HEAD` empty | - | |
| #910 | 2026-10-15 | Master0fFate | Add toggle to keep wallpaper visible during sessions | settings/ui | FEATURE-CANDIDATE | `crates/ui/src/shell.rs:9925` drops background layer once a chat is selected | S | overlaps #838 (pick one) |
| #908 | 2026-10-14 | jsgrrchg | Add "Side chat" to the right pane's new-tab menu | ui-desktop | FIX-CANDIDATE (polish) | `+` menu `crates/ui/src/shell.rs:11494-11568` lacks it; `create_child_chat(None, cx)` exists (`shell/session_lanes.rs:402`) | S | +18 lines |
| #904 | 2026-10-24 | jsgrrchg | MCP: add_project lets agents register a folder on their own device | mcp/engine | FEATURE-CANDIDATE | no `add_project` in `crates/roboco-mcp` | S-M | new RPC |
| #898 | 2026-10-14 | wingleeio | Visual replies: agents show HTML pages inline | browser | STALE/RISKY | maintainer's own approach, replaced upstream by Luau `show_visual` (#900 8a0fded89, merged) | - | |
| #897 | 2026-10-14 | Master0fFate | Start new projects from a GitHub repository | engine/ui | FEATURE-CANDIDATE | clone exists (`crates/engine/src/repos.rs:534` `CloneRepo`); no gh repo listing/palette row | M | spam comment only |
| #891 | 2026-10-13 | vierja | Add Muse support as a native harness | harness | DECLINED | `.scratch/upstream-ports-2026-10-wave9/issues/08-muse-harness.md` (wontfix 2026-10-25) | - | upstream later merged Muse via #978 |
| #888 | 2026-10-13 | Gratenes | Android: swipe from the left edge for chat history | android | SKIP:upstream-android | whole upstream Android app (+11263) | - | |
| #887 | 2026-10-13 | Master0fFate | Settings: forget an old device | settings | ALREADY | Roboco forgets paired engines: `crates/ui/src/settings/devices.rs:164,509` | - | upstream targets cloud device registry |
| #886 | 2026-10-13 | Master0fFate | Fix tool-chip image previews for screenshots outside chat cwd | engine | ALREADY | bug absent: `crates/engine/src/uploads.rs:14` "There is no path jail"; `image_refs.rs:18` | - | |
| #884 | 2026-10-13 | wingleeio | Providers: add any ACP agent (registry + custom command) | harness/engine/ui | FEATURE-CANDIDATE | ACP harness covers fixed agents only (`harness/src/acp/mod.rs:6-7`); registry used only for Antigravity pin (`:518`) | L | maintainer-authored, still unmerged; wire change |
| #882 | 2026-10-13 | woosal1337 | Show GitHub activity on the new-chat canvas | ui-desktop | FEATURE-CANDIDATE (low) | absent | M | new RPC |
| #880 | 2026-10-12 | danialyermekov | Support multiple Antigravity accounts with isolated profiles | engine/harness | FEATURE-CANDIDATE | `crates/engine/src/agent_accounts.rs:35-39` single unswitchable login | M | quota-failed Copilot review only |
| #879 | 2026-10-24 | pasteley | Command palette shortcut rebindable in settings | settings | FIX-CANDIDATE (polish) | `crates/ui/src/shell.rs:645-647` "Fixed: ⌘K", `platform_combo("mod-k")` with no keymap entry | S | |
| #878 | 2026-10-12 | wingleeio | Zeron MCP: async delegation with background turn-end events | mcp/engine | ALREADY | Roboco completion notices + `wait:false` + `notify` default (`roboco-mcp/src/jsonrpc.rs:48-79`; ccf29200d, 0f09fe7d6) | - | reviewer reproduced a P1 in the PR |
| #869 | 2026-10-11 | katulevskiy | Fix flaky tests seen in CI | doc/engine/ui | FIX-CANDIDATE | `crates/doc/src/queue.rs:144,162` no Loro `OutOfBound` retry; `crates/ui/src/settings/composer.rs:81` plain `read_to_string` (Windows rename window gives defaults); `diff_sync/turn_snapshot.rs:681` test helper doesn't skip `maintenance.lock` | S | edge/doc_host_delivery parts N/A |
| #868 | 2026-10-10 | katulevskiy | Desktop: reconnect to the engine daemon after it restarts | ui/rpc | ALREADY | `crates/ui/src/state.rs:278` `Reconnect::Url`; redial `crates/engine-client/src/registry.rs:1161`; 736e9b07d | - | |
| #865 | 2026-10-13 | nomomon | PR board: provider seam + GitLab adapter | engine/ui | FEATURE-CANDIDATE | `change_requests.rs` is gh-only | L | +4685; Roboco also lacks the PR board UI itself |
| #864 | 2026-10-10 | katulevskiy | CI: stop pull requests and tags saving Rust caches | ci | FIX-CANDIDATE | Roboco has the same pattern: no `save-if` on any of the 15 `Swatinem/rust-cache@v2` uses (`windows.yml:62`, `ui-tests.yml:51,92,231`, `android.yml:110,183,346,438`, …). `release.yml:61,130,195` saves a tag-only cache per release | S | the voice-tests half is upstream-only |
| #859 | 2026-10-10 | nomomon | Sidebar: open the project menu from a hover button | ui-desktop | FIX-CANDIDATE (polish) | `crates/ui/src/shell/spaces.rs:3771` right-click only; rows `:3890` no `⋯` | S | discoverability |
| #854 | 2026-10-09 | Master0fFate | Open the terminal folder as a project with zeron | app/installer | FEATURE-CANDIDATE | `apps/roboco/src/main.rs:21-26` CLI takes only a URL; no single-instance hand-off; `dist/windows/roboco.iss` adds no PATH | M | Windows-relevant (named pipe, installer PATH); macOS menu slice skipped |
| #852 | 2026-10-09 | catfogtoad | Keep iOS voice calls active during video playback | ios | SKIP:ios | AVAudioSession only; ADR 0009 drops remote voice | - | |
| #851 | 2026-10-10 | nomomon | Transcript: resolve bare file names only in the linking chat's folder | ui-desktop/files | FIX-CANDIDATE | `crates/ui/src/state.rs:1255-1282` `file_link_roots` falls through to every project root on the device | S-M | port together with #796 (`workspace_links.rs`) |
| #850 | 2026-10-09 | JulioDevEnviagora | ci(release): build Intel macOS DMG | ci | SKIP:macos-only | macOS release | - | |
| #849 | 2026-10-09 | Master0fFate | Claude: map an aborted turn result to interrupted | harness (claude) | ALREADY | `claude/normalize.rs:113-118` `aborted`, `:1080` (from upstream #977 port) | - | |
| #847 | 2026-10-09 | jakoritarleite | fix: opencode background subagent lifecycle | harness (opencode) | FIX-CANDIDATE | `opencode/mod.rs:3826-3846` `task_completion` treats any completed task part as child end (no `metadata.status=="running"` check); `:3544` latches `done`; no child `session.idle` handling | M | fixes upstream issue #845 |
| #840 | 2026-10-08 | Master0fFate | Stop tool-group shimmer on dead runs | ui-desktop | ALREADY | `crates/ui/src/transcript.rs:8075-8077` gated on `shimmer_live` (`:7974-7983`) | - | |
| #839 | 2026-10-08 | and-human | Claude: end the command probe's input before reaping it | harness (claude) | ALREADY | `claude/mod.rs:627-633` drops stdin before `shutdown_child`, reply not gated on reap | - | |
| #838 | 2026-10-08 | axelgitas | Appearance: add opt-in ambient chat wallpapers | settings/ui | FEATURE-CANDIDATE | no in-session wallpaper/opacity (`shell.rs:9925`) | M | superset of #910 |
| #836 | 2026-10-15 | and-human | Explorer: fold the file tree under a Files header | files | SUPERSEDED | built on `files/sections.rs` footer that upstream #895 (591824881) deleted; Roboco moved lanes to `shell/session_lanes.rs` | - | |
| #830 | 2026-10-07 | Master0fFate | Stream dictation and add optional GPU acceleration | voice | FEATURE-CANDIDATE | `crates/voice/Cargo.toml` `parakeet-rs = "=0.3.8"` no `directml`; no partial transcripts | M-L | DirectML.dll / Dawn must ship in Windows zip / Linux tarball |
| #822 | 2026-10-11 | Master0fFate | Add sidebar music player | ui-desktop | FEATURE-CANDIDATE (low) | absent | L | community: "should be an extension"; off-mission |
| #817 | 2026-10-06 | woosal1337 | iOS: Render Markdown images inline (draft) | android-parity | ALREADY | ADR 0009 target, done: 7bf80f561 "android #137: inline markdown image blocks"; `crates/mobile/src/layout/markdown.rs:305`, `apps/android/.../widgets/ImageTileStatus.kt` | - | |
| #815 | 2026-10-06 | dcdyouget | Composer: show device names beside projects | ui-desktop | ALREADY (different design) | `crates/ui/src/pickers.rs:2888-2935` per-device checkout list, `:3839-3855` device chip | - | |
| #808 | 2026-10-15 | and-human | Accounts: show Claude's per-model weekly limits (Fable) | engine/ui | FIX-CANDIDATE | `crates/engine/src/agent_accounts.rs:4438-4455` reads only `five_hour`/`seven_day`, ignores `limits[].weekly_scoped`; `crates/ui/src/account_usage.rs:704` `.take(2)` | S | no wire change |
| #807 | 2026-10-05 | matsvarn | Delegated tasks: async results, nesting, status and cancel | mcp/engine | ALREADY (core) | completion + needs-input notices, `interrupt_chat`, loop cap (fdb637ba0) | - | +16204; nesting deliberately absent ("side chats cannot create chats") |
| #802 | 2026-10-15 | and-human | Show Claude /compact in the chat and reset the context ring | harness (claude) | FIX-CANDIDATE (partial) | ring reset ALREADY (`claude/normalize.rs:661-673`); missing "Context compacted." TextDelta + AssistantMessageCompleted (Codex has it, `codex/mod.rs:1600`) | S | a Claude `/compact` turn renders empty |
| #798 | 2026-10-05 | wingleeio | Edge: stop storing push head and backup flag (stage 2, draft) | cloud | SKIP:cloud | `edge/src/*` | - | |
| #797 | 2026-10-05 | AndPuQing | iOS: add session organization and project controls | android-parity | FEATURE-CANDIDATE | Android has only Group by Project (`apps/android/.../core/Prefs.kt:44`); no device grouping, sort, row details, scoped search, archive filter, project rename/delete | L | not an ADR 0009 target PR; unreviewed |
| #796 | 2026-10-04 | RemingtonWilcox | Transcript: Windows drive paths in replies are file links | ui-desktop (Windows) | FIX-CANDIDATE | `crates/ui/src/workspace_links.rs:193-197` "File links are POSIX-only", links only `starts_with('/')` | M | high Windows value; Roboco's `workspace_links.rs` diverged (cc84b5f9a, 4927527af), so this is a hand port |
| #795 | 2026-10-04 | RemingtonWilcox | Sessions: the title's project tag opens its folder | ui-desktop | N/A | Roboco titlebar has no `project @ device` tag (`shell/tabs.rs:288-303`) | - | |
| #794 | 2026-10-04 | RemingtonWilcox | Transcript: wheel direction decides tail-follow | ui-desktop | FIX-CANDIDATE | `crates/ui/src/transcript.rs:4082-4083` `should_restick(distance, previous_distance)`, `:4152` stale-distance compare; no `wheel_dy` | S | |
| #793 | 2026-10-04 | RemingtonWilcox | Rail: the bottom mark stands for the newest prompt | ui-desktop | FIX-CANDIDATE | `crates/ui/src/rail.rs:486` `unwrap_or(start)`: last bucket shows its first prompt | S | |
| #792 | 2026-10-04 | RemingtonWilcox | Engine: settle live runs together on shutdown | engine | FIX-CANDIDATE | `crates/engine/src/sessions.rs:2664-2668` sequential `self.terminate(&chat_id).await` | S | ~3 s × N live turns on daemon stop |
| #791 | 2026-10-04 | RemingtonWilcox | Projects: match saved folders across slash styles | ui-desktop (Windows) | FIX-CANDIDATE | `crates/ui/src/shell/spaces.rs:6647` exact `s.path == path`; `pickers.rs:677` `same_folder_path` only trims trailing separators | S | mixed-separator projects exist from before 22ba72f8a |
| #790 | 2026-10-15 | and-human | Right pane: Ctrl+1…9 jump to tabs | ui-desktop | FEATURE-CANDIDATE | no `JumpRightTab`; Ctrl+1…9 are session jumps (`shell.rs:649-660`) so off-macOS default is Alt+1…9 | S-M | Alt+digit clash risk in terminal tabs |
| #788 | 2026-10-04 | ahmedadamio | Add refresh button to the files explorer | files | FIX-CANDIDATE (polish) | `crates/ui/src/files/mod.rs:506-521` "Refresh now" only in watcher-error banner; header `:1273` has only ignored toggle | S | |
| #787 | 2026-10-25 | avienor | Claude: show replies to local slash commands | harness (claude) | FIX-CANDIDATE | `claude/normalize.rs:792-796` keeps only `tool_use` from top-level assistant frames (drops `<synthetic>` text); `:841-860` zero usage resets ring; `crates/doc/src/parts.rs:542` ignores `Done.result` | S | `/context`, `/cost`, `/usage`, `/mcp` show empty |
| #785 | 2026-10-25 | and-human | Claude: open each Workflow agent's live transcript | harness/engine/ui | FEATURE-CANDIDATE | nothing for Workflow / `workflow_progress` in `crates/` | L | stacked on #784; collides with #1041 `is_chat_spawn` gate |
| #784 | 2026-10-15 | and-human | Claude: show Workflow runs as subagents with live progress | harness/engine/ui | FEATURE-CANDIDATE | `Workflow` tool decodes as generic tool; no `SubagentProgress` | M | in-process event; `subagentTail` already in doc schema |
| #783 | 2026-10-04 | RemingtonWilcox | Usage: show each chat's token totals (draft) | harness/doc/ui | FEATURE-CANDIDATE | no `tokenUsage` | M | DRAFT |
| #782 | 2026-10-06 | RemingtonWilcox | Composer: long pastes become chips | ui-desktop | FEATURE-CANDIDATE | no `ChipKind::Paste`; chip base `composer/chip.rs:27,245` | M | built on upstream #775 chip style; verify first |
| #781 | 2026-10-04 | AndPuQing | Fix missing window control icons on Windows 10 | ui-desktop (Windows) | FIX-CANDIDATE | `crates/ui/src/shell.rs:6751` `.font_family("Segoe Fluent Icons")` PUA glyphs; font absent on Win10, so blank buttons. SVGs already bundled (`icons.rs:198-201`, used by Linux captions) | S | MERGEABLE; closes upstream #770; HIGH value |
| #780 | 2026-10-05 | AndPuQing | Fix stale branch labels in the composer | engine/proto/ui | FIX-CANDIDATE | label reads `chat.branch` (`crates/ui/src/pickers.rs:4033`), stamped only on create/switch/rename/worktree; `CheckoutGitStatus` (`proto/src/entities.rs:899`) has no `branch`; the "HEAD-watcher" claimed at `workspace_host.rs:748` doesn't exist | M | additive wire field, so wiregen |
| #779 | 2026-10-04 | katulevskiy | Desktop: open links and files externally with Ctrl+click | ui-desktop/files | FEATURE-CANDIDATE | no ctrl-click in `markdown/link_interaction.rs` (only shift `:207,213,345`) | M-L | also fixes focused-link click swallow; no WebView2 counterpart |
| #776 | 2026-10-03 | rynfar | Worktrees: track created branches with a git config marker | engine | FEATURE-CANDIDATE (low) | `crates/engine/src/repos.rs:1547` deletes any `roboco/*` branch | S | only matters with configurable prefix |
| #773 | 2026-10-03 | nmb4 | Surface OpenCode V2 shell and Code Mode tool calls as chips | harness (opencode) | FIX-CANDIDATE | `opencode/mod.rs:4282` maps only `"bash"` to Exec; V2 `shell` / Code Mode `execute` become "Tool · shell" | S (shell) / M | not fixed upstream either |
| #769 | 2026-10-03 | katulevskiy | Fix stale subagent running status and add individual Stop controls | engine/harness/ui | STALE/RISKY | author: "shit design, need to change" | - | stale-Running-after-Codex-idle may still exist in Roboco; recheck if reported |
| #765 | 2026-10-03 | katulevskiy | Fix OpenCode launcher process leaks on Unix | harness (Linux) | FIX-CANDIDATE (residual) | graceful paths ALREADY (`harness/src/lib.rs:596-613`); no `process_group(0)` and no Drop group-kill (`opencode/mod.rs:790-794` only `kill_on_drop`), so a cancelled startup orphans the npm/bun server | S | copy `acp/child.rs:19,75-109` pattern |
| #762 | 2026-10-04 | katulevskiy | Bound sync and terminal memory retention | rpc/sync/terminal | STALE/RISKY | no `FRAME_QUEUE_CAP`/`TerminalEvent::Gap` | L | author: "need to optimize valgrind ci… before merging"; reviewer flags 256→8 queue stutter risk, no Windows validation. Mine terminal/UI leak slices only |
| #761 | 2026-10-06 | leighstillard | Per-device "Disable local execution" switch | engine/ui | STALE/RISKY | absent | L | +3158, no review; low fit for engine-local Roboco |
| #759 | 2026-10-03 | katulevskiy | Recover initial Claude authentication failures on the run host | harness (claude) | FIX-CANDIDATE (low) | `claude/normalize.rs:18,57` every `authentication_failed` → signed out; no retry | M | Roboco's `refresh_lock.rs` already removes the main cause |
| #758 | 2026-10-03 | katulevskiy | Harden device clocks and fix cross-device renaming | sync/edge | SKIP:cloud | registry clocks, edge tombstones, sync leases | - | |
| #756 | 2026-10-03 | katulevskiy | Show account usage reset countdowns and banked resets | engine/ui | STALE/RISKY | author: "will redo design"; Roboco shows resets in tooltip (`account_usage.rs:696-698`) | - | |
| #755 | 2026-10-03 | katulevskiy | Harden OpenCode credentials, discovery, and Windows startup | engine/harness (opencode, Windows) | FIX-CANDIDATE | logins written only to `auth.json` (`engine/src/agent_accounts/stores.rs:107-108,845`); XDG fallback `~/.local/share` (`:67-68`); no SQLite store | L | big unreviewed bundle; verify OpenCode 2.x claim; port in slices |
| #753 | 2026-10-03 | katulevskiy | Automatically use a new worktree when draft branch checkout conflicts | engine/proto/ui | FEATURE-CANDIDATE | no `SwitchRefOutcome`/`worktree_required` | M | new wire type |
| #747 | 2026-10-02 | rayorole | Show a check on the error notice copy button after copying | ui-desktop | FIX-CANDIDATE (polish) | `crates/ui/src/notice.rs:119-128` icon always `COPY` | S | |
| #746 | 2026-10-05 | wingleeio | Edge: cut ChatRoom and RegistryRoom push writes 5 → 3 (stage 1) | cloud | SKIP:cloud | `edge/src/*` | - | |

## Candidates

### FIX-CANDIDATE (most valuable first)

#### #781 Fix missing window control icons on Windows 10 (S)
- **What it does:** the Windows caption buttons (min, max, restore, close) draw bundled SVGs instead of Segoe Fluent Icons glyphs. That font doesn't ship with Windows 10, so on Win10 the buttons are blank.
- **Reproduces in Roboco:** yes. `crates/ui/src/shell.rs:6737-6772` still uses `.font_family("Segoe Fluent Icons")` with `\u{e921}`/`\u{e922}`/`\u{e923}`/`\u{e8bb}`. `windows_caption_button` (`:11871`) takes a glyph string. The SVGs already exist (`icons.rs:198-201`) and the Linux captions use them (`shell.rs:6873-6883`).
- **Files:** `crates/ui/src/shell.rs`, `crates/ui/src/icons.rs`. Hover and press colours move to `group_hover`/`group_active`.
- **Risk:** check the red close-button hover on Win11 visually. The upstream PR is MERGEABLE.

#### #796 Transcript: Windows drive paths in replies are file links (M)
- **What it does:** `C:\…` and `C:/…` paths (with `:12` or `#L12`) in agent replies and inline code become file links when a session root contains them. Matching is case-insensitive.
- **Reproduces in Roboco:** yes. `crates/ui/src/workspace_links.rs:193-197` says "File links are POSIX-only" and links only paths that start with `/`.
- **Files:** `workspace_links.rs` (`is_drive_path`, root containment, `file:///C:/` form) and the absolute-path check in `shell.rs`. Roboco's `workspace_links.rs` diverged (cc84b5f9a, 4927527af), so this is a hand port.
- **Notes:** keep out-of-root drive paths plain. Port together with #851.

#### #1076 (slice) Roboco MCP robustness under long waits (S-M)
- **What it does:** four fixes for Roboco MCP. The rest of the PR is Cloud wake and heartbeat, which is skipped.
  1. Codex gets `tool_timeout_sec` longer than the longest wait, plus `startup_timeout_sec = 30`, for the injected server.
  2. Long tool calls send `notifications/progress` every 30 s when the client gave a `progressToken`. Claude Code aborts a call that is silent for 30 min.
  3. Replies are cut below 8 MiB, because Claude Code drops the stdio connection past 16 MiB and never restarts it.
  4. The MCP command path strips a Linux `… (deleted)` suffix after the binary was replaced.
- **Reproduces in Roboco:**
  - Codex timeout: `crates/harness/src/codex/mod.rs:117-126` sets only command, args, env and approval mode. Roboco's `wait_for_turn` defaults to 600 s and allows up to 3600 s (`roboco-mcp/src/tools.rs:27-28`), so Codex's 60 s default kills synchronous waits.
  - Progress and reply cap: `roboco-mcp/src/jsonrpc.rs` has neither.
  - `(deleted)` path: `crates/engine/src/sessions.rs:3062` uses a bare `current_exe()`.
  - Bad-input-line skip: already present (`jsonrpc.rs:115-125`).
- **Files:** upstream `crates/mcp/src/jsonrpc.rs`, `crates/harness/src/codex/mod.rs`, `crates/engine/src/managed.rs` and `sessions.rs` map to `crates/roboco-mcp/src/jsonrpc.rs`, the same codex file and `crates/engine/src/sessions.rs`.
- **Risk:** low. No wire change.

#### #955 Engine: run git with the login-shell PATH (S)
- **What it does:** git that the engine spawns (worktree add, which runs `post-checkout` hooks that call npx and the like) gets the login-shell PATH. Without it the hooks fail when the app was launched with a bare PATH, and the first message of a new worktree chat is rejected silently.
- **Reproduces in Roboco:** yes. `crates/engine/src/repos.rs:304-316` builds `Command::new("git")` with no PATH composition. The helper `roboco_harness::compose_login_shell_path` is already used in `source_control.rs:791`. On Windows it merges the registry user PATH.
- **Change:** 2 lines in `repos.rs::git`.

#### #791 Projects: match saved folders across slash styles (S, Windows)
- **What it does:** re-adding `C:\x/y` no longer creates a duplicate of `C:\x\y`.
- **Reproduces in Roboco:** yes. `crates/ui/src/shell/spaces.rs:6647` compares `s.path == path` exactly. `pickers.rs:677` `same_folder_path` only trims trailing separators. Projects saved before 22ba72f8a can hold mixed separators.
- **Change:** widen `same_folder_path`. The engine-side `create_space` dedupe has the same exact compare, and the PR doesn't fix that.

#### #792 Engine: settle live runs together on shutdown (S)
- **What it does:** `join_all` the interrupts instead of awaiting them in sequence. Daemon stop or a runtime switch with N live turns then costs about 3 s total, not about 3 s × N, which fixes "Could not stop the engine".
- **Reproduces in Roboco:** yes, `crates/engine/src/sessions.rs:2664-2668`.

#### #787 Claude: show replies to local slash commands (S)
- **What it does:** `/context`, `/cost`, `/usage` and `/mcp` answer with one `<synthetic>` assistant frame and no deltas. The PR emits that frame's text when nothing streamed, and stops the frame's zero usage from resetting the context ring.
- **Reproduces in Roboco:** yes. `crates/harness/src/claude/normalize.rs:792-796` keeps only `tool_use` blocks, `:841-860` takes ContextUsage from zero usage, and `crates/doc/src/parts.rs:542` ignores `Done.result`.
- **Notes:** a reviewer flagged one conflicting hunk in the `result` branch.

#### #802 Claude: show /compact in the chat (S, partial)
- **What it does:** on `compact_boundary`, the PR emits "Context compacted." and an AssistantMessageCompleted.
- **In Roboco:** the context-ring reset is already present (`normalize.rs:661-673`, test at `:2021`). Codex shows the same note (`codex/mod.rs:1600`), but a Claude `/compact` turn renders empty.

#### #808 Accounts: Claude per-model weekly limits (S)
- **What it does:** parses `/api/oauth/usage` `limits[]` entries with `kind:"weekly_scoped"` (for example Fable) into a third meter.
- **Reproduces in Roboco:** yes. `crates/engine/src/agent_accounts.rs:4438-4455` reads only the 5-hour and 7-day windows, and `crates/ui/src/account_usage.rs:704` has `.take(2)` (the Settings accounts list caps at 2 too).
- **Risk:** no wire change.

#### #773 OpenCode V2 shell and Code Mode tool chips (S / M)
- **What it does:** maps `shell` to Exec. Code Mode `execute({code})` is unpacked to `tools.shell({command})`, and nested `metadata.toolCalls` become chips.
- **Reproduces in Roboco:** yes. `crates/harness/src/opencode/mod.rs:4282` maps only `"bash"`.
- **Effort:** the `shell` mapping is a one-liner (S); `execute` parsing and nested chips are M.

#### #847 OpenCode background subagent lifecycle (M)
- **What it does:** a `task` part that completes with `metadata.status=="running"` no longer settles the child. The child is settled on its own `session.idle`, `session.error` or `session.interrupted`, and a prompt-less wake is armed so the parent settles.
- **Reproduces in Roboco:** yes. `opencode/mod.rs:3826-3846` has no status check, `:3525-3550` latches `done`, and there is no child-terminal handling.
- **Files:** `opencode/mod.rs` only. Skip the PR's `docs/issues/*`.

#### #959 OpenCode: settled command catalog + /compact (M)
- **What it does:** command discovery polls `/api/command` until it is non-empty and every MCP server has left `pending` (5 s cap). `/compact` is listed and posted to OpenCode's compaction endpoint.
- **Reproduces in Roboco:** yes. `opencode/mod.rs:499-510` and `:1884-1891` read the list once, and there is no compact route. MCP prompt commands typed early go to the model as plain text (`:3167`).

#### #765 OpenCode launcher leaks on Unix (S, residual)
- **What it does:** runs the OpenCode server in its own process group and group-kills it on Drop or a cancelled startup.
- **In Roboco:** graceful shutdown is already covered (`harness/src/lib.rs:596-613`). Cancelled or abandoned startups still orphan the npm/bun-spawned server: `opencode/mod.rs:790-794` has only `kill_on_drop`, and Linux pdeathsig covers only the direct child.
- **Change:** copy the `acp/child.rs` pattern. Linux only.

#### #869 Fix flaky tests (portable slices) (S)
- **What it does:** three fixes.
  - Retries Loro `OutOfBound` in queue `push_container`/`insert_container` (`crates/doc/src/queue.rs:144,162`). This is a real race on `queue_message`, not just a test flake.
  - Retries transient non-NotFound reads in composer-settings `load` (`crates/ui/src/settings/composer.rs:81`). In the Windows rename window the read fails and settings silently fall back to defaults.
  - The `turn_snapshot` test helper ignores `maintenance.lock` (`diff_sync/turn_snapshot.rs:681`).
- **Not applicable:** the edge and doc_host_delivery parts.

#### #949 Lightbox: fix image flicker on open (S)
- **What it does:** the lightbox opens already fitted, without a one-frame flash at natural size.
- **Reproduces in Roboco:** yes, `crates/ui/src/image_viewer.rs:201-202` and `:217-218`.
- **Change:** pass a `viewport_hint` from `attachments.rs:1113` (`max_w`/`max_h` are already computed at `:1098`) and `None` from `files/image_preview.rs:204`.

#### #794 Transcript: wheel direction decides tail-follow (S)
- **What it does:** wheel-up breaks the bottom pin while a reply streams, and wheel-down within 70 px re-sticks. This replaces a stale `last_scroll_distance` compare that broke the pin by itself.
- **Reproduces in Roboco:** yes, `crates/ui/src/transcript.rs:4082-4083`, `:4121`, `:4133`, `:4152`, `:4160`.
- **Risk:** listener order vs the list's wheel handler; touchpad momentum.

#### #793 Rail: bottom mark = newest prompt (S)
- **What it does:** with more than 12 prompts, the last rail bucket previews and jumps to the newest prompt instead of the bucket's first.
- **Reproduces in Roboco:** yes, `crates/ui/src/rail.rs:486`.

#### #851 Transcript: bare file names resolve only in the linking chat's folder (S-M)
- **What it does:** `` `CONTEXT.md` `` no longer opens another project's file of the same name.
- **Reproduces in Roboco:** yes. `crates/ui/src/state.rs:1255-1282` falls through to every project root on the device.
- **Files:** `FileLinkRoot` gains an `own` flag, across `markdown/{inline_code_links,link_interaction,link_presentation}.rs`, `state.rs`, `transcript.rs` and `workspace_links.rs`. Do it together with #796.

#### #1033 Transcript: reply time = finished, not started (S-M, draft)
- **Reproduces in Roboco:** yes. `crates/ui/src/transcript.rs:1851` shows `entry.created_at`.
- **Options:** the cheap port derives the time from `created_at + duration_ms` (`crates/doc/src/schema.rs:62`). The full port adds `finishedAt` in the doc, `read_chat` and mobile. Show no time for aborted replies.

#### #780 Fix stale branch labels in the composer (M)
- **What it does:** the composer branch label follows checkouts made outside the chat, such as a terminal `git checkout`.
- **Reproduces in Roboco:** yes. The label renders `chat.branch` (`crates/ui/src/pickers.rs:4033`), which is stamped only on create, switch, rename or worktree. `CheckoutGitStatus` (`crates/proto/src/entities.rs:899`) has no branch. `workspace_host.rs:748` mentions a HEAD-watcher that doesn't exist.
- **Files:** `proto/src/entities.rs` (wiregen and web types), `engine/src/diff_sync/git_status.rs`, `ui/src/files/git_status.rs`, `ui/src/pickers.rs`.

#### #933 Markdown: render Codex file citations as file links (S-M, draft)
- **What it does:** `:codex-file-citation{path="…"}` directives from Codex PDF and document skills become filename links instead of raw text.
- **Reproduces in Roboco:** yes. `git grep codex-file-citation HEAD` returns nothing.
- **Files:** `crates/markdown/src/{content_reference.rs (new), parser.rs, lib.rs}`. This is mobile-core, so Android benefits too. Parser offset remapping needs the tests the PR includes.

#### #986 Titlebar: order pane and explorer toggles like their columns (S)
- **What it does:** swaps the two toggles so they match the column order.
- **In Roboco:** `crates/ui/src/shell/tabs.rs:495` (files) comes before `:510` (changes).

#### #908 "Side chat" in the right pane's + menu (S)
- **In Roboco:** the row is missing from `crates/ui/src/shell.rs:11494-11568`. `create_child_chat(None, cx)` already exists (`shell/session_lanes.rs:402`). Show the row only while a chat is selected.

#### #879 Command palette shortcut rebindable (S)
- **In Roboco:** the binding is fixed at `crates/ui/src/shell.rs:645-647`. The fix adds a `ShortcutId` to the Panels group in `settings/shortcuts.rs:882`, plus a serde-default keymap field.

#### #788 Files explorer refresh button (S)
- **In Roboco:** "Refresh now" is only in the error banner (`crates/ui/src/files/mod.rs:506-521`). The header (`:1273`) needs the button, and an `icons::REFRESH` asset (check it exists).

#### #859 Sidebar project row hover `⋯` menu (S)
- **In Roboco:** the menu is right-click only (`crates/ui/src/shell/spaces.rs:3771`, `:3890`).

#### #747 Error notice copy button shows a check (S)
- **In Roboco:** `crates/ui/src/notice.rs:119-128` always shows `COPY`. The fix adds a key arg to `notice_chip`, and the `composer.rs`/`transcript.rs` callers pass a unique key.

#### #925 Chat chips show harness mark (S)
- **In Roboco:** `crates/ui/src/composer/chip.rs:165-176` always shows `CHAT_ROUND_LINE`. The fix needs a `chat_harness` lookup in `state.rs`, plus changes in `composer.rs`, `queue.rs` and `transcript.rs`.

#### #932 Shift+Ctrl+Backspace deletes word (S)
- **In Roboco:** only `ctrl-backspace` is bound (`crates/ui/src/composer.rs:1636`, `~1732`). Low value on Windows.

#### #938 Markdown: neutral inline code styling (S, taste)
- **In Roboco:** `crates/ui/src/markdown/render.rs:990-994` uses the same accent styling. This is a pure design call.

#### #940 (slices) UI polish (S each)
- **What still applies:**
  - Windows/Linux titlebar content sits 2 px low: top pad only, `shell/tabs.rs:525`; `shell.rs:6603/6718/6866`; `TITLEBAR_TOP_PAD=4` at `proto/src/layout.rs:48`. Add a matching bottom pad off macOS.
  - Context popover compact counts (`11.7k / 200k`), one used/remaining row, horizontal bar (`ui/src/context_usage.rs`).
  - Collapsed-sidebar 1 px sliver: Roboco keeps it on purpose as a grab affordance (`shell.rs:7084`), so this is a design call.
- **How to port:** by hand. The branch has drifted into unrelated mobile and audio changes.

#### #1081 (slice) CI: LLD + Dev Drive for Windows builds (S)
- **What it does:** links with `rust-lld` instead of `link.exe` via a `fast-linker` composite action. `./target` goes on a ReFS Dev Drive (`samypr100/setup-dev-drive`, pinned). Upstream measured −11% on cold Windows test and app builds.
- **In Roboco:** `.github/workflows/windows.yml` (`windows-2022`, timeout 90) uses neither. Roboco just raised the ui-tests timeout because of slow CI (85be4485a), so faster CI has value.
- **Notes:** separable from #1077. Check that GPUI/WebView2 links cleanly with LLD.

#### #864 CI: only main saves Rust caches (S)
- **What it does:** pull requests and tags restore caches but don't save them, so main's caches stop being evicted from the 10 GB Actions cache.
- **In Roboco:** none of the 15 `Swatinem/rust-cache@v2` steps sets `save-if`. `release.yml:61,130,195` saves a tag-only cache on every release, which no later run can restore.
- **Change:** add `save-if: ${{ github.ref == 'refs/heads/main' }}` and drop or disable the release caches.

#### #759 Recover initial Claude authentication failures (M, low)
- **What it does:** on the first `authentication_failed`, respawns Claude once.
- **In Roboco:** no retry (`claude/normalize.rs:18,57`). `refresh_lock.rs` already fixes the main known cause, so this is only worth doing if users report it.

#### #952 Claude: foreground subagent opening prompt doubled (M, draft)
- **In Roboco:** `claude/normalize.rs:800-819` seeds the opening from the spawn input, and `:913-921` re-emits the child's echo. This is likely to double on CLI ≥ 2.1.296.
- **Before porting:** confirm with a live capture. The draft is unverified on older CLIs.

#### #755 Harden OpenCode credentials, discovery, Windows startup (L, slices)
- **In Roboco:**
  - Credentials are written only to `auth.json` (`engine/src/agent_accounts/stores.rs:107-108,845`).
  - The XDG fallback is `~/.local/share` on all OSes (`:67-68`).
  - There is no OpenCode 2.x SQLite credential store, no health-vs-child-exit race, and no empty-provider catalog filter.
- **How to port:** in slices.
  1. Windows startup and health race.
  2. Empty-provider filter.
  3. Path resolver.
  4. SQLite credentials, after confirming that OpenCode 2.x ignores `auth.json`.

### FEATURE-CANDIDATE (product calls; rough value order for Roboco)

#### Higher value

##### #854 Open the terminal folder as a project (M)
- **What it does:** `roboco <path>` opens that folder as a project. A second launch hands its request to the running window (named pipe or Unix socket, guarded by `headed.lock`), which also covers Start-menu relaunches and `roboco://`. The Windows installer adds the app to the user PATH.
- **In Roboco:** `apps/roboco/src/main.rs:21-26` accepts only a URL, and `dist/windows/roboco.iss` adds no PATH entry.

##### #1080 Find in thread (Ctrl+F) and message search (M-L)
- **What it does:** a find field over the chat with highlights and ↑/↓/Enter navigation, plus a palette Messages tab backed by an engine-local SQLite FTS5 index.
- **Cost:** a new RPC (wiregen). Check that Ctrl+F doesn't clash with other bindings.

##### #784 / #785 Claude Workflow runs as subagents / live per-agent transcripts (M / L)
- **What it does:** the `Workflow` tool becomes a spawn chip with per-phase rows, and each workflow agent opens its own live transcript.
- **Notes:** #785 is stacked on #784 and collides with #1041's `is_chat_spawn` gate.

##### #926 + #928 MCP launch args for chats, then Claude in Chrome toggle (M each)
- **What it does:** #926 adds per-chat CLI args with a blocklist (Claude) and an allowlist (Codex). #928 adds `--chrome` driven by a device preference.
- **Notes:** wire change plus a capability. Drop the relay parts.

##### #779 Ctrl+click opens links and files externally (M-L)
- **What it does:** Ctrl+click opens a link in the system browser regardless of the in-app setting, or a file in its system app or file manager.
- **Notes:** touches the Linux WebKit helper. There is no WebView2 counterpart.

##### #830 Streaming dictation + optional GPU (DirectML/WebGPU) (M-L)
- **What it does:** live partial transcripts while speaking. GPU lets recordings run longer than 1 minute.
- **Cost:** DirectML.dll and Dawn must ship in the release packages.

##### #797 Android-parity slice of iOS session organization (L)
- **What it adds:** device grouping, sort order, row-detail options, project-scoped search with an archive filter, and project rename/delete.
- **In Roboco:** Android has only Group by Project (`Prefs.kt:44`).
- **Notes:** rename/delete needs RPCs over the paired client. This PR is not on the ADR 0009 target list.

##### #960 Idle-session close setting (S-M)
- **What it does:** the hard-coded 30 min at `crates/engine/src/sessions.rs:5010` becomes a pref with a Settings select.

##### #963 Drag side-chat tab into composer → mention (S)
- **What it does:** reuses `add_chat_mention`. No wire change.

##### #782 Long pastes become chips (M)
- **What it does:** a paste of 1,500 characters or more becomes a "Pasted text · N chars" chip that can be expanded again.
- **Check first:** whether Roboco has upstream #775's chip style.

##### #790 Right-pane tab number shortcuts (S-M)
- **What it does:** number shortcuts jump to right-pane tabs. Off macOS the default is Alt+1…9.
- **Risk:** Alt+digit can clash with input in terminal tabs.

#### Medium value

##### #1053 Sidebar provider usage cards (M, draft)
- **What it adds:** persistent usage cards in the sidebar.

##### #946 Browser tab → web page mention chip (M)
- **What it adds:** dragging a browser tab into the composer inserts a web page mention.
- **Notes:** a proto/prompt addition, `roboco-web:`.

##### #939 Right-pane split panes (L)
- **What it adds:** splitting the right pane.

##### #987 Move a side chat into the sidebar (M)
- **What it adds:** detaching a side chat into its own session.

##### #965 Session notes (engine-local slice) (L)
- **What it adds:** a per-chat notes surface, ported as an engine-local slice.

##### #904 MCP `add_project` (S-M)
- **What it adds:** an MCP tool that registers a project.

##### #897 New project from a GitHub repo (M)
- **What it adds:** a gh repo list plus a palette row.
- **Notes:** clone already exists.

##### #753 Auto new worktree on checkout conflict (M)
- **What it adds:** when the checkout conflicts, switch to a new worktree automatically.
- **Notes:** needs a new `SwitchRefOutcome` wire type.

##### #884 Any ACP agent (registry + custom command) (L)
- **What it adds:** any ACP agent, from the registry or a custom command.
- **Notes:** maintainer-authored but unmerged.

##### #880 Multiple Antigravity accounts (M)
- **What it adds:** more than one Antigravity account.

##### #783 Per-chat token totals (M, draft)
- **What it adds:** a token total for each chat.

##### #1050 Project icon picker (M-L, draft)
- **What it adds:** choosing a project icon.
- **Notes:** a wire change, plus Android parity.

##### #1079 Coordinator/Tasks slice (L)
- **What it adds:** the Coordinator/Tasks slice.
- **Notes:** stacked on Cloud #1076. Roboco already has side-chat report-back.

##### #989 Agent memory editing / stop one tool / system prompt (L)
- **What it adds:** editing agent memory, stopping a single tool, and a system prompt.
- **Notes:** the system prompt alone is S-M.

##### #910 or #838 Wallpaper during sessions (S / M; pick one)
- **What it adds:** the wallpaper shown during sessions.

#### Low value or risky

##### #1006 libghostty-vt terminal (L)
- **Risk:** needs a Zig toolchain on every platform.
- **Alternative:** port only the sprite glyphs.

##### #930 Oh My Pi harness (L)
- **Note:** same product call as Muse, which was declined.

##### #865 GitLab adapter (L)
- **Note:** Roboco also lacks the PR board UI.

##### #934 / #935 Codex visualize HTML previews (M-L, draft)
- **Note:** validated on macOS only.

##### #882 GitHub activity heatmap (M)

##### #776 Worktree branch marker (S)
- **Note:** only matters with a configurable branch prefix.

##### #822 Music player (L)

##### #944 Desktop pets (M)
