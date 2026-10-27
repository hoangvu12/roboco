# Pass 2 — big-1 (15 commits first-passed as PORTED)

Roboco = HEAD `85be4485a`; upstream/main = `31d26eeb6`. Read-only audit.

**Method.** Each commit's added lines were renamed (zeron→roboco) and checked against HEAD and upstream/main trees. A line missing from HEAD but still present on upstream/main counts as a candidate gap; a line also gone from upstream/main counts as superseded. Every candidate was then read in context.

The large harness, accounts, dictation, file-link and actions commits were checked file by file and slice by slice. Later upstream commits on the same files were also checked (`git log <sha>..upstream/main -- <files>`).

Spot-verified by hand: `acp/mod.rs:3495` `exits_on_stdin_eof` = `id != Opencode` (upstream: `!matches!(Grok|Hermes)`); `codex/mod.rs:2281` non-unix `process_alive`; `claude/mod.rs:1950` `!any_done`; `opencode/mod.rs:2922,2947` `format!("file://{path}")`; no `user_stops`/`abandon_retry` in engine; no `permissions/requestApproval`/`backgroundTerminals`/`MAX_PENDING_SERVER_REQUESTS` in codex.

**Result.** 1 commit is really PARTIAL (`2e151ca65`) and 1 has a tiny own-slice miss (`ac4acbc21`). The other 13 are confirmed ported, with gaps only in later commits that are already tracked elsewhere.

---

## 2e151ca65 (#977) Harden every provider — **PARTIAL** (first pass said PORTED)

Ported via 16012431c, ea0387e9f, 17a24e7a4, 524e7ab20, 3917211bd, c8f85f112, f8a2e1b65, e1523bcac, e8d4b50ad and f5fd8a3d5. The ticket is `.scratch/upstream-ports-2026-10-wave9/issues/06-harden-providers.md`. It records no declines. Its recorded exclusions are the cloud per-run env map, the sign-out revival gate, and the deferred `an_unfinished_restore…` test.

Files fully present (0 missing lines after renames): `process.rs`, `process/cmd_shim.rs`, `process/windows/*`, `windows_process.rs`, `owned.rs`→`acp/child.rs`, `shell_env.rs`, `executable.rs`, `lossy_lines.rs`, harness `lib.rs`, `adapter_install.rs`, `archive_install.rs`, `codex/npm_repair.rs`, `claude/wire.rs`, `cursor/shim.mjs`, `run_journal.rs`, `doc_host.rs`.

Files with large gaps: `opencode/mod.rs` (~428/641 added lines missing), `codex/mod.rs` (206/307), `claude/mod.rs` (120/325), engine `sessions.rs` (232/596, mostly refactor plus one missing sub-feature).

### Process and Windows substrate
- [present] cmd_shim whitelist parser, 8191-char failure, UNC warning — `process/windows/command.rs:207 CMD_LINE_LIMIT`
- [present] Spawn retry 100/300/1000 ms on sharing violations; `block_in_place` hand-off — `process.rs`
- [present] Missing cwd → NotADirectory on Windows; NUL in cwd → InvalidInput — `process/windows/*`
- [present] Kill grace before job terminate; wait for EOF only when stdin was closed — `lib.rs:631 shutdown_child`, `process/windows/mod.rs:76-99`
- [present] Native process table, `Tracked{pid,start}`, reused-pid guard — `process.rs`
- [present] `lossy_lines` framing
- [present] jsonrpc: dead stdin fails pending requests, void reply resolves null, `transport_lost` classifier — `jsonrpc.rs:52-67`
- [MISSING tests] `closing_stdin_writes_what_was_queued_then_sends_eof`, the `transport_lost` table test
- [present] `owned::configure` → `acp/child.rs:17-40` (strips 12 Claude-session env markers, Unix process group, `shutdown_with`). Used by ACP `acp/mod.rs:1721`, Pi `pi/mod.rs:479,651,1293`, install `install.rs:217`.
- **[MISSING]** Claude run and probe launches through `configure`. Roboco `claude/mod.rs:468-488 build_command` and `:512-541 probe_initialize` call only `dies_with_parent`, and `claude/` has no `configure(`. So there is no process group and no `CLAUDECODE`/`CLAUDE_CODE_*` strip. Test `processes_the_cli_started_end_with_the_run` is absent.
- [present] shell_env TLS/SSH/locale/`__MISE_DIFF`, registry PATH, nushell; version-probe budgets (5 s, 10 s on Windows), 30 s retry, coalescing, USERPROFILE home
- [present] Install rename retry on Windows sharing violations
- **[partial]** `executable::spawn_error` (a missing workdir is not a missing binary). Used by ACP, Codex and Cursor. Claude (`claude/mod.rs:543,1225`), OpenCode (`opencode/mod.rs:795`) and Pi (`pi/mod.rs:486,661`) still map NotFound → NotInstalled.
- [present] windows.yml portable suites (opencode, cursor_shim, pi_rpc) — `windows.yml:81`

### Claude
- [present] Result classification (is_error, terminal_reason, api_error_status, abort → Interrupted), surrogate repair, unreadable-result fallback, api_retry/compaction liveness — `claude/wire.rs`, `normalize.rs`
- [present] Stdin replay; relaunch fresh on "No conversation found"; `--replay-user-messages`; control deadlines and refusals shown once; `StdinMsg::Close`; refresh-lock settles at every teardown site
- [present] Probe: hooks off, IDE attach off, 25 s timeout, redacted stderr, EPIPE → crash message — `claude/mod.rs:529-537,610`
- **[MISSING]** A crash in any turn ends it. Upstream uses `turn_open`, a 200 ms exit wait and `stderr_tail.wait_closed()`. Roboco `claude/mod.rs:1950` still checks `!any_done` with a bare `try_wait`. Test `a_crash_in_a_later_turn_ends_it_with_the_reason` is absent.
- **[MISSING]** Unserved control requests get an error reply ("unsupported control request: …"). Roboco `claude/mod.rs:2084-2090` only logs; `control_error_line` (`wire.rs:683`) has no caller. Test `unknown_control_requests_are_answered` is absent.
- **[MISSING]** Title runs drop `--strict-mcp-config` when `managed-mcp.json` exists (Windows `C:\Program Files\ClaudeCode\managed-mcp.json`). Roboco `claude/mod.rs:942-953` always passes it; no match for `managed-mcp`.
- **[MISSING]** Windows `claude.cmd` → sibling `claude.exe` (`native_beside_npm_shim`). Lower impact because Roboco's cmd_shim already bypasses cmd.exe.
- [skip:cloud] `config_root(&request.env)`: a per-run `CLAUDE_CONFIG_DIR` only comes from cloud.
- [MISSING tests] `an_unreadable_result_still_ends_the_turn`, `a_refused_model_switch_is_shown_and_retried`, `a_failed_discovery_probe_reports_exit_and_stderr`, `missing_working_directory_is_not_a_missing_binary`, `a_session_without_conversation_is_not_resumed`, the `claude_refresh_lock` run test

### Codex
- [present] Notification opt-out with retry; archived → unarchive; setup-crash stderr; MIN_CODEX_VERSION 0.146.0; elicitation; `codex_error_text`/SIGNED_OUT; npm repair
- **[MISSING]** Parent-thread `error` handling: a willRetry notice ("Reconnecting… 2/5") should only be logged and the final error held to turn end. Roboco `codex/mod.rs:1919-1931` emits every one as `AgentEvent::Error`. The hold rule exists only for subagent ChildStream (`normalize.rs:946-956`), and the turn end at `:1724` doesn't use `settle_turn_error`. Test `retry_notices_stay_out_of_the_transcript_and_the_final_error_lands_once` is absent.
- **[MISSING]** `crash_report`: a crash's Done carries the last final error (200 ms reap plus stderr drain). Roboco `:2135-2150` uses a bare `try_wait`.
- **[partial]** Resume classification. Roboco `:2239-2253` matches a bare `"not found"`. It lacks: an empty or corrupt rollout → fresh thread, and OS/IO wording (`os error`, `permission denied`, `locked`) → keep the id.
- **[MISSING]** `turn/status == "interrupted"` ends Interrupted; Roboco `:1745-1751` reports Completed.
- **[MISSING]** `thread/backgroundTerminals/list` as the authority on still-running commands after an interrupt.
- **[MISSING]** Windows `process_alive` (OpenProcess/GetExitCodeProcess). Roboco `:2281` returns false off Unix, so on Windows every background command counts as gone.
- **[MISSING]** `item/permissions/requestApproval` routed to approval; Roboco `:2431-2438` answers -32601. Test `permission_and_elicitation_requests_get_valid_replies` is absent.
- **[MISSING]** `MAX_PENDING_SERVER_REQUESTS = 32` with overload refusal.
- [adapted] `account/rateLimits/updated` uses Roboco's `limit_reset` flow (ticket 06 5/5).

### Cursor
- [present] Everything (12 s stop, startup stops, force retry, 4/8 MiB image caps, CURSOR_API_KEY counts as installed, 10-minute silence bound, abandoned send muted). The shim matches upstream apart from Roboco's `replaceFile`.

### ACP (Grok, Devin, Hermes, Antigravity)
- [present] 12 s stop, PendingQuestions cancel, Grok cancel `_meta`, 15 s config bounds, handshake crash watch, restore_method load/resume, 90 s load budget, `session_unrestorable`, auth default method, inline images — `acp/mod.rs`, `tests/acp_hardening.rs` (10 tests)
- **[MISSING]** `exits_on_stdin_eof = !matches!(id, Grok | Hermes)`. Roboco `acp/mod.rs:3495-3496` is `id != HarnessId::Opencode`, so every Grok/Hermes teardown waits out the full kill grace (Hermes never exits on EOF; Grok takes >2 s).
- [MISSING tests] `an_unknown_session_starts_fresh_with_a_notice`, the unit tests `only_a_missing_session_is_unrestorable`, `restore_prefers_what_the_agent_advertises`, `a_run_signs_in_only_with_the_agents_own_default_method`, and the grok cancel test. `an_unfinished_restore_errors_and_keeps_the_session` is deferred per ticket 06.

### Pi
- [present] 32 MiB frame bound, surrogate repair, noise tolerance, dialog registry, non-fatal extension_error, settle after compaction/retry, idle-queue recovery (Roboco's own design), crash waits for exit and stderr, `close_input_for_teardown` (`pi/wire.rs:97`), Rust pi_rpc peer
- **[partial]** 30 s bound on a mid-session model change ("Pi did not apply the model change within 30s"). Roboco `pi/mod.rs:2663,2942` awaits `configure_live` unbounded; `PiClient::request` has no timeout.
- [N/A] Windows `\\?\` stripping for `--session`: Roboco never canonicalizes the pointer.

### OpenCode
- [present] Startup retries (database locked, EADDRINUSE), readiness probe, version-first `detect_preferring`, probe from home — e1523bcac
- **[MISSING]** Retry of the first-start migration race ("Unexpected error"/"Failed query:" + DDL). No match for `Failed query`. Tests `startup_retries_a_lost_migration_race` and `a_lost_migration_race_is_transient` are absent.
- **[MISSING]** Spec-compliant incremental SSE decoder (CR/LF/CRLF, comments, joined data lines, lossy UTF-8). Roboco `opencode/mod.rs:4900-4914` splits on `\n\n` and drops a frame on invalid UTF-8.
- **[MISSING]** `BUS_SILENCE` 45 s, `BUS_PROBE_TIMEOUT` 5 s, 150 s wedge budget. A hung server leaves the turn Working for about 31 minutes.
- **[MISSING]** Reconnect budget resets only after a framed stream; Roboco `:4852` resets on any 2xx.
- **[MISSING]** Server crash noticed within 500 ms (`SERVER_POLL`), plus between-turn crash handling. Roboco learns of a crash only at `BusMsg::Disconnected` after the reconnect budget (`:2482`).
- **[MISSING]** A crash during Stop settles Interrupted (`:2496-2504`).
- **[MISSING]** Prompt delivery that survives a lost answer (landed check by message id, single re-post, 4xx fails at once, cancel token). Roboco `post_prompt :3154-3158` errors on any POST failure.
- **[MISSING]** Stall clock starts at prompt acceptance; Roboco `TurnState::begin` arms it at once.
- **[MISSING]** Permission/question replies retried 3 × 10 s; an undeliverable reply aborts and errors the turn. Roboco `:3645-3679` does one detached POST.
- **[MISSING]** Sweep of pending permissions/questions after a reconnect, and a reconnect doesn't settle a turn whose prompt is unanswered. Roboco's `Connected` handler (`:2467-2481`) only re-syncs idle.
- **[MISSING]** Stop also aborts subagent child sessions (`abort_children`, 15 s). Roboco aborts the root only.
- **[MISSING]** Stall/retry aborts bounded at 5 s.
- **[MISSING]** Attachment file URLs: percent-encoded, `file:///C:/…`, UNC `file://server/share`, `\\?\` stripped. Roboco `:2922,2947` still uses `format!("file://{path}")`. Test `attachment_urls_survive_reserved_characters_and_windows_paths` is absent.
- [MISSING tests] 27 `opencode/tests.rs` fns and 3 `tests/opencode.rs` fns, including `windows_directories_encode_like_encode_uri_component` and the opt-in live test.

### Engine run recovery
- [present] Revival continuation carries the request (`sessions.rs:4065`), never-revive rules, native_command grammar, 12 h ledger, 3-in-a-row cap, shutdown cut marking, `{chat}.stopped` marker, transient-start backoff 250 ms/1 s/3 s, `cli_replaced_recently`, rejected-resume tombstone and fresh-start notice, dead-command sweep — `run_journal.rs`, `doc_host.rs`, `tests/run_recovery.rs` (20 tests)
- **[partial]** Start retries and revivals re-check Stop, shutdown and newer sends. Roboco checks Stop only during the backoff sleep (`sessions.rs:4800-4831`) and a live run only once, outside the run lock (`:1205-1210`).
  - Missing: `user_stops`/`stops_seen`/`abandon_retry`, the `superseded` check (a newer user message), and the re-check under the run lock.
  - Absent tests: `a_stop_between_a_failed_start_and_its_retry_ends_the_turn`, `a_shutdown_during_a_start_retry_starts_nothing_and_revives_on_boot`, `a_revival_yields_to_a_message_sent_while_it_was_on_its_way`.
- [skip:cloud] Sign-out revival gate: Roboco keeps `forgo_revival()`.
- [MISSING unit tests] `tail_of`/`turn_tail_*`, `only_transient_start_failures_retry`, `resume_rejection_is_read_narrowly`, `a_leading_path_is_not_a_native_command`, `revival_prompt_carries_the_request…`

### Later upstream refinements
- `4a0d416cf` (#1051) gives the cursor_shim test's first `ready` frame 30 s (Windows CI flake fix): **MISSING**. Roboco runs cursor_shim on Windows CI (`windows.yml:81`).
- `d97756075` (#985) → c3dac8f23; `987bb2f87` (#978) → b21e9f39b (slice); `a9e6c48b7` (#1023) → b6b7c8aa2: present.
- Other commits on these files are separate features audited in other parts.

---

## bbf590c85 (#542) Agent accounts for Grok, Devin, OpenCode, Pi, Hermes — **confirmed PORTED**

Ported via 9b72d332d (harness), ad5cd41b5 (engine/proto), 103423577 (desktop), e6d4da7e2 (web) and e3fa574da (requester remainder).

- [present] `agent_accounts/{oauth,stores,usage,provider_tests}.rs`: every upstream fn name exists (oauth 6/6, stores 65/65, usage 16/16), and so does every 12+ character string literal.
- [present] Grok `login --device-auth` in a throwaway GROK_HOME, issuer-scoped merge, billing usage. Roboco uses its more general `quiet_cli_browser` instead of `#[cfg(unix)] ensure_noop_browser` (ticket 47, `agent_accounts.rs:4617`).
- [present] Devin credentials.toml incl. `%APPDATA%\devin` (`stores.rs:95`), `devin-browser` sign-in, callback port, GetUserStatus (Roboco adds 401/403 → SignedOut).
- [present] OpenCode/Pi per-provider entries (ChatGPT PKCE on port 1455, Copilot device flow, Pi lockfile, OpenCode lock test `provider_tests.rs:1865`).
- [present] Hermes pool listed read-only, `hermes auth add`, ChatGPT and Nous usage.
- [present] Security hardening (`0o700` 7/7, `ghe.com` 17/17, `DEVIN_DOMAINS`, `umask 077`, `write_file_atomic`, `try_lock`, `merge_json_entry`, `adopt_if_live`, `sign_out`).
- [present] `redact.rs` is byte-identical; all four `redact_output` call sites exist.
- [present] Harness `SignInOptions`/`sign_in_with`/`cli_command`; 3 acp tests; `fake-devin-auth.sh`.
- [present] Proto `AgentAccount.provider`, `StartAgentLogin.provider` (`rpc.rs:614`); m5c round-trip test.
- [present] UI `switches_accounts`/`provider_note`/`login_options`/`add_option_label`/`login_copy`/`mark_switched`, skeleton rows, 3 UI tests (`settings/accounts.rs`).
- [declined/ADR 0004] Requester/remote-device login forwarding (`forward_agent_login`, `cancel_remote_login`; recorded in ad5cd41b5 and e3fa574da).

### Later upstream refinements
`6eb5917d` (#546), `f30a8641b` (#967), `dd0c36fa3` (#974), `f725384bd` (#970), `09beace00` (#873), `a408a8140` and `cc7da5c2a` (portable slice) appear as ports in roboco-log; each is its own row. Muse from `987bb2f87` is DECLINED (wave-9 ticket 08). `bf6d71aaa` (#1018) `has_escape_surface` is missing but belongs to the #1018 row. `26e2b0dd3` (#628) "Account actions" tooltip is N/A: Roboco's grouped page has its own "Forget account" tooltip.

## 67c960f4d (#376) OpenCode 1.18 session create + 2.x wire — **confirmed PORTED**

Ported via c132bc596.

- [present] `create_session` retries once on 5xx (`opencode/mod.rs:2623-2647`) with both tests.
- [present] A 5xx names the opencode log (`:1206-1209`).
- [present, refined] `Protocol::detect` → `detect_preferring` + `/api/info` (`:607,612`); the refinement came later upstream.
- [present] v2 session create with `location.directory`, `/api/session/{id}/model`, `prompt_body_v2` (`:2961`), `/api/event` with the SSE Accept header (`:4835`), `normalize_v2_frame`, `GET /api/model`, `{data}` unwrap, `/interrupt`, `x-opencode-directory` header (`:1022`).
- [present] Event integrity, owned-session permission scoping; all `tests/opencode.rs` tests.
- [superseded] "Allow this OpenCode request once?" was removed upstream by `adb63378a` (#533); Roboco followed in e920e9336.
- [present] CI: `ui-tests.yml:54-55`, `windows.yml:81/125`. The `opencode_turn_probe` example is present.

### Later upstream refinements
Present: 7bf44ece, fb01b951, 50701536, 11e2574a, 08376d23, a2995663, 4c52a970, 0e7070ee, db50cd37, 48c8aeeb, ea253ebb, adb63378, 27480d99/a86f0587, 9609f994, d840f280, d0b0e89d, plus the run-env slice of cc7da5c2a and `agent_ports::reserve` from a9e6c48b7.

Not present:
- `64a26d35` (#1022) opencode hunk: no `!answers.is_empty()` guard → `/question/{id}/reject` (`mod.rs:3739`). Belongs to the #1022 row.
- `53c7fe3e`/`03552181` probe env knobs (`OPENCODE_PROBE_*`): dev tool only.

## 80b946b15 (#591) On-device dictation — **confirmed PORTED**

Ported via fc58ea330 + cf94f4155.

- [present] `crates/voice` (roboco-voice): `lib.rs`, `resample.rs`, `session_tests.rs`, `model.json`, `NOTICE.md` and `examples/verify.rs` are byte-identical after renames (SHA-256 checks, partial-download cleanup, cpal on every sample format, 16 kHz rubato, capture during model load, serialized worker with panic containment, header timeout).
- [present] `dictation/{glass,meter,waveform}.rs` are byte-identical.
- [present] Voice card in `dictation/model.rs` (opt-in, download/remove, mic list refresh, shortcut row). Its error strip became a toast (a408a8140 → 64ea2a7e2).
- [skip:macos-only] Permission bridge, `permission.m`, clang build block. Off macOS, permission = 1, matching upstream (`dictation.rs:39-49`; ticket wave4/24:94).
- [present] Composer: hold-to-talk, morph, Escape/undo/IME/paste, Send waits for the transcript, ⌘D passthrough. All 49 added symbols are in HEAD, with the same call counts (e.g. `cancel_dictation` 22/22).
- [present] Question-takeover stash (cf94f4155); `composer_dictation_tests.rs`; queue-edit hooks (`queue.rs:1666…1758`).
- [present] Settings `dictation_enabled`/`dictation_input`, `ShortcutId::ToggleDictation` "Hold to dictate" `mod-d` (`settings.rs:858,862,1233,1261`), `merge_changes`.
- [present] Shell Voice section, 3-way `pull_settings`, shortcuts page recording/conflicts, microphone icon.
- [present] Packaging licence copy (Linux, Windows), ALSA hint, `libasound2-dev` in `release.yml:57`/`ui-tests.yml:227`, docs.
- [skip] macOS dist/packaging, edge install.sh, voice CI workflows, design doc (ticket 24:94).

### Later upstream refinements
`9782693b8` (#740) present. `a408a8140` present. `4a5fb8b44` (#819) and `8930cd103` (#834) native Codex voice: DECLINED (c2e5a5dff, ADR 0003). `bf6d71aaa` (#1018) Voice search anchors: missing, tracked under #1018. `crates/voice` is unchanged upstream since.

## 79b27ed68 (#374) Independent terminal / code fonts — **confirmed PORTED**

Ported via dd823f11c.

- [present] `typography.rs` matches upstream/main except a test helper from declined #912: separate families/sizes, fixed-width list verified by i/m/W/0 widths, proportional font → Geist Mono fallback, `reinstall_theme`.
- [present] `theme.rs` `font_terminal`/`code_font_size`/`terminal_font_size` (`:748,1339,1529,1596`).
- [present] Terminal row height follows the live font (`terminal/view.rs:29,453,583`); pixel scroll uses the live row height (`terminal/panel.rs:1823`).
- [present] Per-surface scaling, diff width cached per file, related tests (`native_diff_font_geometry` etc.).
- [present] Four font fields clamped to 8–32; `filesEditorFontSize` folded in (`settings.rs:1839`); "Editor font size" row removed.
- [superseded] Hand-built size menus were replaced upstream by b782d0437 (#449), which Roboco ported (20187dc1a). All 8 appearance tests are present.
- [skip:upstream-ci] macOS ui-tests step. Roboco covers it in `windows.yml:140` and the ubuntu `--lib` run.

### Later upstream refinements
`c02739da3` (#912 math font) DECLINED (wave8 spec.md:32). `bf6d71aaa` (#1018) `FontKind::setting()` anchors: missing, tracked under #1018. `71033cc96` (#975) terminal smooth scroll: missing, tracked in part-07.

## 2a38baa4e (#606) Encoded and out-of-folder file links — **confirmed PORTED**

Ported via cc84b5f9a (with #633). Every added line of all 18 files matches after renames, except reformatting, cloud boot-config fields, and code that #633 later refactored.

- [present] Engine absolute reads (`MAX_ABSOLUTE_PATH_BYTES`, `AbsoluteRead`, `resolve_absolute_read`, `read_absolute_file_blocking`; editable inside the folder, read-only outside) — `workspace_files.rs:460,483`. Absolute image reads; 6 unit tests plus the e2e test.
- [present] Wire `WorkspaceReadOnlyReason::OutsideWorkspace` with a round-trip test and web type.
- [present] Markdown parser rewrites spaced absolute destinations to `<…>`; code and unclosed links are skipped (`crates/markdown/src/parser.rs:144,215`; 7 tests). This is shared with Android via crates/mobile.
- [present] Link grammar in `workspace_links.rs`: decode once, `file://` host rule, `#L`/`:line:col`, root widening; 11 tests.
- [present] Inline-code file links (`InlineCodeLinkCache`, LRU, `PathProbes`); 8 tests.
- [present] Link menu: "Open in Roboco", "Open with default app", "Show in folder", "Copy file path"; system rows only for local chats (`link_interaction.rs:427-464`).
- [present] Trailing file glyph slot, `strip_outside_links`, preview copy "File not found." / "Read-only: outside this chat's folder." (`files/preview.rs:3364,3382`).
- [present] Outside files skip the checkout probe and watch; `file_link_roots`/`chat_is_local`; shell `first_root_owning`.
- Note (not a port gap): absolute-path and inline-code links are POSIX-only upstream too (`workspace_links.rs:193`, engine requires a leading `/`). On Windows, a `C:\…\x.md` link or inline-code path is not a file link. That would be Roboco's own work.

### Later upstream refinements
`bc1368670` (#633) present. `ea3dcc811` (#1026) outside-press link card fix: MISSING (`markdown/link_interaction.rs:259,273` still bumps `epoch`), already PARTIAL in part-07. `c02739da3` (#912) math arms: DECLINED feature.

## 288d026f8 (#158) Project actions control and editor — **confirmed PORTED**

Ported via 0866174bd (+ web 77e0725de).

- [present] Six action icons (`action-configure.svg` uses currentColor, a deliberate Roboco change f34653ea6).
- [present] `project_actions.rs` controller (`preferred_action`, `draft_from_action`, generation-guarded loads) with 3 tests.
- [present] Editor overlay: name/command validation (80 chars / 16384 bytes / required), icon picker, setup flag, "Saving…"/"Save action", delete confirmation (`shell/actions_ui.rs:498-689`), mounted at `shell.rs:9590`.
- [present] Run flow: reserved bottom-drawer terminal tab, `fail_reserved_tab` (`terminal/panel.rs:555`), "(setup)" title.
- [present] `last_project_action_by_space_id` (`settings.rs:903`).
- [superseded] Titlebar control and dropdown moved to the session card by ac4acbc21 (Roboco followed). `show_action_label` is leftover dead code in Roboco (`project_actions.rs:244`); upstream deleted it.
- [adapted] EngineTarget routing instead of "Engine not connected" (ticket-24 decision in 0866174bd).

### Later upstream refinements
f4365134d, a8aa4967e, 2d2830801, b14840158, 669d45bde, 052b4b9a, e41f3acde, 6706facb4, c679de97f, 831edf8a6, 3e621c49, 67524512a and 26e2b0dd3 are all present. `daf047b7f` (#1048) icon-picker tile tooltip and a11y role/label/selected: MISSING (`actions_ui.rs` ~580-603), already tracked in part-07.

## ac4acbc21 Session card behind one toggle — **PORTED with one tiny own-slice miss**

Ported via 189c3d5a3 (superset with 6774a37a, c8c717862, 591824881).

- [present] Session card with device/project/branch and +N −M totals, project actions (run, edit, import, add, retry) — `shell/session_info.rs`. The `actions_ui.rs` hunk is present (0/6 missing).
- [present] Titlebar toggle replaces +, fork, run-action and the project@device tag — `shell/tabs.rs`
- [superseded] `list-rows.svg` icon (upstream replaced it with 6774a37a's corner-card glyph). The `settings.rs` card fields were later reshaped by #867 (`session_info_hidden`, `settings.rs:1031`).
- [present] Composer follows the column left by half the reserve; `transcript.set_right_reserve` with padding `.pr(48 + right_reserve)` (`transcript.rs:7597`); `render_session_title_bar` (`shell.rs:6504`); `session_info_frame` layout (`shell.rs:9883`).
- **[MISSING]** Diagram raster width ignores the card reserve. Upstream: `self.content_width.min((list_width - self.right_reserve).max(1.0))` (upstream/main `transcript.rs:11175`). Roboco `transcript.rs:10392` is `self.content_width.min(list_width)`. So while the card is docked, Mermaid/diagram rasters are sized for a column wider than the one shown.
- [present] Sidebar-sections card hunks (`sidebar_sections.rs` tests use `session-info-fork` etc.).

## 591824881 (#895) Session card: side chats and subagents — **confirmed PORTED**

Ported in 189c3d5a3.

- [present] `shell/session_lanes.rs`: 475/478 added lines match. The three "missing" lines are refactors: `("session-info-fork", FORK, "Fork chat")` is at `session_lanes.rs:390-392`.
- [present] Lanes: side chats newest first; subagents running longest-first; settled ones folded into "N finished"; paging 8/10 rows with `popover::rail_for` (`popover.rs` 0/40 missing); New side chat / Fork chat on the lane heading; inline rename reveal.
- [present] The toggle counts running subagents plus working side chats. Upstream's inline closure is refactored to `session_lanes::running_lanes` (`session_lanes.rs:211`), used at `tabs.rs:369,401` with `running_pill::mark_titlebar_button` (`running_pill.rs:75`, 0/7 missing).
- [present] `files/sections.rs` and `files/subagents.rs` deleted; FilesEvent variants gone.
- [adapted] The device of the diff watch uses EngineTarget instead of `state.engine()`.
- [excluded-by-port] Panel-lifecycle tests in `panel_interaction_tests.rs` (4 lines; ride #677 / wave-7 08). Only the card tests were ported.
- [excluded-by-port, dev-only] `harness/src/mock.rs` `/fanout [N]` mock knob (spawns N subagents, settles them `ROBOCO_MOCK_DELAY_MS` apart, every 7th fails) and its 2 tests, plus the `scripts/dev-demo.sh` fan-out session and second-demo dir/port knobs (`ROBOCO_DEMO_DIR`/`ROBOCO_DEMO_PORT`). Roboco has `scripts/dev-demo.sh` but no `fanout`. The 189c3d5a3 message records this as excluded ("roboco's tests drive state directly"); there is no user decision. It is dev tooling only.
- [missing, docs-only] `docs/mcp.md` "Forks and the session card" paragraph.

### Later refinements (ac4acbc21 and 591824881)
- `c8c717862` (#867) present.
- `4443e6b58` (#1028) snap on chat switch: MISSING, tracked in part-07.
- `bc892abd0` (#950) branch totals = branch over base: MISSING, tracked in part-07.
- `a9e6c48b7` (#1023) Ports card present.
- `00cc2e059` (#1038) SKIP:cloud.
- `cc7da5c2a` cloud slices.

## 6851fc34b Animated drag transfers pinned ↔ regular — **confirmed PORTED**

Ported via 1af2ea135. In `spaces.rs`, 595/623 lines match; the misses are `super::super::SIDEBAR_SESSION_SLOT` path renames and sync-layer test asserts (`sidebar_pins_for_profile`, `edge_url`/`workos`), excluded per the port message.

- [present] `SidebarSessionDrag`, begin/cancel/finish transfer, `sidebar_session_transfer_is_valid`, movement layer, source collapse, `sidebar_transfer_extra_gap` (`shell.rs:8252,8536`), Escape cancels, `SidebarSessionReturn` (`shell.rs:1328`), "Drop here to unpin", `pull_request_badge_preview` (`change_requests.rs:135`), preview flag on `render_chat_row`, FLIP paused during a transfer.
- [N/A] `command_palette.rs` one-liner: Roboco's palette doesn't use `render_chat_row`.
- [superseded] The drag_over washes were removed upstream by 0f152647, which Roboco ported.

## 68306a172 Sidebar pins with per-item ordering keys — **confirmed PORTED (desktop slice)**

Ported via d6785eec8.

- [present] `SidebarPinChange` Pin/Move/Unpin + `project` (`shell/sidebar_pins.rs:26,52`), `MAX_SIDEBAR_PINS = 200` (`:20`), `validate_sidebar_pin_update` + "You can pin up to 200 sessions" (`:76,85`), `PendingSidebarPins` queue, `apply_sidebar_pin_change`, move-only `reorder_visible_pins`.
- [present] Cleanup of deleted pins waits for authority (`chats_synced`) and keeps archived pins (`shell.rs:2815-2840`, `retain_known_pins`).
- [skip:cloud / ADR 0004] proto `sidebar_pins.rs` ordering keys, doc registry rows, engine `changeSidebarPin` mutate, edge workerd, iOS RegistryCore, the RPC/remote-pin UI tests and the sync-layer half of `docs/regressions/sidebar-pin-integrity.md`.

## 86249cf04 (#486) Custom sidebar sections + session DnD — **confirmed PORTED**

Ported via 75ae96cd6 + web 2b713bf2d, later re-homed engine-side in c548e7bf0 (`engine/src/sidebar_state.rs`; see part-02 row 94268229f).

- [present] `shell/sidebar_sections.rs`: CRUD dialog, rename, delete, "Edit section / Archive all / Delete" menu (`:828`), Archive all with failure count (per-chat EngineTarget instead of `state.engine()`), disclosure rendering and drop targets, optimistic membership mask.
- [present] `SidebarSessionDrop::Section`; pinning clears section membership; Create Section in the view menu; `more-horizontal` icon (currentColor variant, f34653ea6).
- [superseded/adapted] `UiSettings.sidebar_sections_by_profile` moved to the engine sidebar store. The Synced/remote-pin rejection tests are cloud.

## 672cc1444 Searchable popovers, ordered sections, titlebar sidebar options — **confirmed PORTED**

Ported via f382151fd.

- [present] `popover.rs` (0/39 missing): shared `search_row`, picker rows, edge fades and rails.
- [present] `pickers.rs` `ProjectIcons` (`shell/project_icon.rs:355`, `pickers.rs:919,3142`); device tag dropped from the spaces dropdown.
- [present] Unified `sidebar_disclosure_header` with a collapsed count badge (`spaces.rs:1571`); `section_insert_index` (`sidebar_sections.rs:10`) and placed order; `render_sidebar_options_titlebar`; Project filter toggle; SIDEBAR_MIN 242.
- [present] Tool-group summary rolls (`transcript.rs:8372` `rolling(...)`); harness_updates pill; `changes.rs` (0/19 missing).
- [skip:cloud / ADR 0004] doc registry `orderKey`/HLC placement, proto anchors (local equivalent present), `crates/client` hunks.
- [rides parked #484] `pull_requests.rs` popover hunks: Roboco has no `pull_requests.rs`. afca583c1 is MISSING/parked in part-06.

### Later refinements (sidebar files)
`26c8e8404` (#909): desktop present, Android parity PARTIAL (part-06). `94268229f` (#487) SUPERSEDED. `c78bb1c12` (#751) present. `daf047b7f` (#1048): section dialog Close tooltip/aria missing, tracked in part-07.

## 09d04b297 (#598) Wallpaper shuffle, preload, adaptive colours — **confirmed PORTED**

Ported via 8c9972f27.

- [present] `settings/wallpaper.rs` 295/296 lines match (a comment differs). Folder rotation, recency cooldown, LOOKAHEAD-3 preload, managed copies cleaned on Drop, orphan retirement, `stage_file_verbatim` (`:61`, includes the #739 follow-up).
- [present] `wallpaper_colors.rs` 140/145 match (doc comments only): 4096-bin dominant colour, contrast, backfill.
- [present] `WALLPAPER_CROSSFADE` (`motion.rs:478`), crossfade hold (`new_thread_background_effects.rs:33,453`), `Readiness::frame` (`shell.rs:9860`).
- [present] `RandomWallpaper` mod-u shortcut (`settings.rs:1182`) in the Shortcuts page's Appearance group; "Match wallpaper colors" toggle with helper copy (`appearance.rs:3505-3556`); theme overlay keeps manual choices.

## 626bccc85 (#660) Background positioning and zoom — **confirmed PORTED**

Ported via 8c9972f27.

- [present] `NewThreadBackgroundAdjustment` focal/zoom healing; mask `fitted_geometry`/`pan_adjustment`/`zoom_adjustment_around` (206/208 lines match; the 2 misses are a refactored test tuple).
- [present] Adjust dialog: preview, drag/scroll/pinch/keys, `BACKGROUND_ZOOM_STEP` 0.1, Reset/Cancel/Apply/Close, "Adjust background", "Preparing preview…" (`appearance.rs:81,2659-3045`).
- [present] Adjust button (`appearance.rs:3656`). Roboco adds a `stored_background.is_some()` gate because its bundled default has no framing.
- [present] The warm switch keeps the decoded lookahead (key compares the path only).

Later refinements: `f9a4a18be` (#739) present. `bf6d71aaa` (#1018) search anchors tracked under #1018. `cc7da5c2a` wallpaper use is on the cloud page (skip).

---

## Gaps

All new unless marked "(tracked)".

| # | Commit | Slice | User-visible effect | Files | Effort |
|---|---|---|---|---|---|
| 1 | 2e151ca65 | OpenCode runtime hardening | A crashed server leaves the turn Working for minutes, and a hung one for about 31 minutes. One lost POST fails the turn. A failed permission reply strands the agent. Questions asked during a reconnect are lost. Stop leaves subagent sessions running and spending tokens. The first-start migration race isn't retried. | `harness/src/opencode/{mod.rs,tests.rs}` (`bus_task`/`stream_bus` ~4826-4930, `run_session` ~1900-2510, `post_prompt` 3019-3158, permission/question 3600-3770), `tests/opencode.rs` | L |
| 1a | 2e151ca65 | OpenCode attachment file URLs | Attachments with `#`, `?`, `%` or spaces in the name, and any Windows drive or UNC path, resolve wrong. This matters most on Windows. | `opencode/mod.rs:2922,2947` | S |
| 1b | 2e151ca65 | OpenCode SSE decoder | CR/CRLF or comment frames break, and a frame with invalid UTF-8 is dropped whole. | `opencode/mod.rs:4900-4914` | S |
| 2 | 2e151ca65 | Codex parent-loop wiring | "Reconnecting… n/5" retry notices show as errors and can prompt sign-in. A turn interrupted elsewhere reads as completed. `permissions/requestApproval` is refused (-32601). **On Windows, every background command counts as dead after an interrupt** (`process_alive`). Empty or corrupt rollouts break the chat. A crash's Done loses the real error. Pending server requests are unbounded. | `harness/src/codex/mod.rs:1700-1760,1919-1931,2125-2150,2239-2287,2370-2440`, `tests/codex.rs`, fake-codex fixture | M |
| 3 | 2e151ca65 | Claude launcher via `acp::child::configure` | An engine started inside a Claude Code session passes `CLAUDECODE`/`CLAUDE_CODE_*` (session id, IDE port, effort) to its Claude runs. On Unix there is no process group, so CLI grandchildren outlive the run. | `harness/src/claude/mod.rs:468-541` | S |
| 3a | 2e151ca65 | Claude crash in a later turn | A persistent-session CLI that dies after its first turn never gets an Errored Done; the chat hangs Working. | `claude/mod.rs:1950-1959` | S |
| 3b | 2e151ca65 | Claude unknown control requests | No reply is sent, so the CLI waits on the request. | `claude/mod.rs:2084-2090` (wire `control_error_line` already exists) | S |
| 3c | 2e151ca65 | Claude title runs vs `managed-mcp.json` | Title generation fails on machines with a managed MCP policy, including the Windows `C:\Program Files\ClaudeCode` path. | `claude/mod.rs:942-953` | S |
| 3d | 2e151ca65 | `claude.cmd` → `claude.exe` | npm-installed Claude on Windows runs through the shim instead of the native exe. Low impact given Roboco's cmd_shim. | claude executable resolution | S |
| 4 | 2e151ca65 | Engine retry/revival race guards | A Stop between a failed start and its retry is lost, so the work restarts. Boot revival can re-send an old request after the user sent a newer one. | `engine/src/sessions.rs` (~940-1230 `dispatch_inner`, 4785-4866, `note_user_stop` 1614-1660), `tests/run_recovery.rs` | M |
| 5 | 2e151ca65 | ACP Grok/Hermes `exits_on_stdin_eof` | Every Grok or Hermes stop or teardown waits the full kill grace (Hermes never exits on EOF), so Stop feels slow. | `harness/src/acp/mod.rs:3495-3496` | S |
| 6 | 2e151ca65 | Pi model-change 30 s bound | An unanswered `set_model` freezes the run loop, Stop included. | `harness/src/pi/mod.rs:2663,2942` | S |
| 7 | 2e151ca65 | `spawn_error` for Claude, OpenCode, Pi | On Linux, a deleted project folder reports "not installed". | `claude/mod.rs:543,1225`, `opencode/mod.rs:795`, `pi/mod.rs:486,661` | S |
| 8 | 2e151ca65 | Test parity, plus follow-up `4a0d416cf` (#1051) | Missing coverage (jsonrpc close_stdin/transport_lost, ACP restore helpers, refresh-lock run test, engine unit tests). #1051's 30 s first-frame wait fixes a Windows CI flake in `cursor_shim`, which Roboco runs on `windows.yml:81`. | `harness/tests/*`, `tests/cursor_shim.rs` | S each |
| 9 | ac4acbc21 | Diagram raster width with the card docked | While the session card is docked beside the transcript, Mermaid/diagram rasters are sized for the full list width rather than the narrower reading column. | `crates/ui/src/transcript.rs:10392` (one-line change: `.min((list_width - self.right_reserve).max(1.0))`) | S |
| 10 | 591824881 | Mock `/fanout N` + dev-demo fan-out session | Dev/demo only: there is no data-side way to fill and scroll the subagent lane or watch it drain. Port-time exclusion, not a user decision. | `crates/harness/src/mock.rs`, `scripts/dev-demo.sh`, `docs/mcp.md` | S |

Tracked elsewhere (found again in this pass, not new):
- #1026 link-card outside press (`link_interaction.rs:259,273`)
- #1048 tooltips (action icon picker, section dialog Close)
- #1028 card snap on chat switch
- #950 branch totals
- #1018 settings search anchors (Voice, fonts, accounts `has_escape_surface`)
- #1022 OpenCode empty-answer reject guard
- #975 terminal smooth scroll
- #909 Android drag between sections

Confirmed fully ported, with no gaps in their own slices: bbf590c85, 67c960f4d, 80b946b15, 79b27ed68, 2a38baa4e, 288d026f8, 591824881 (apart from the dev-only #10), 6851fc34b, 68306a172, 86249cf04, 672cc1444, 09d04b297, 626bccc85.
