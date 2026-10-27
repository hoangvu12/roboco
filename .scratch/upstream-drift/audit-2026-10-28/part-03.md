# Part 3 — chunk-03 (8f98653b6 … c2230f073, 2026-09-19 → 2026-09-25, zeron v0.2.81 → v0.2.86)
Summary: 76 commits — PORTED 48, PARTIAL 5, MISSING 2, DECLINED 0, SUPERSEDED 4, SKIP 17, N/A 0

Method: every commit was read with `git show --stat`, then its hunks. Presence in Roboco `HEAD` (85be4485a) was checked by grepping distinctive identifiers, strings and test names after the renames. For the big commits (3c7185b4, 7a009a8f … ea253ebb, b782d043, a75cf1a6) I listed every `fn` the commit adds and checked each one against both HEAD and `upstream/main`. A fn missing from both was removed by a later upstream commit. Most of this window landed in Roboco's wave 2 (2026-09-26..28). `roboco-log.txt` names the port commits, and the code confirms them.

## Table
| SHA/PR | date | upstream PR | title | area | class | evidence | effort | notes |
|---|---|---|---|---|---|---|---|---|
| 8f98653b6 | 2026-09-21 | — | fix(harness): redact quoted and whitespace-padded credentials | harness | PORTED | 4753ca31e; `crates/harness/src/lib.rs:525` (trim quotes/ws), test at :555 | — | |
| 08376d23a | 2026-09-21 | — | fix(harness): bound OpenCode submission and confirm ambiguous idle turns | harness | PORTED | 25757587c; `opencode/mod.rs:1622` idle_confirmations, :2449 status_backoff poll, :3152 spawned prompt POST | — | |
| ceb8d8925 | 2026-09-21 | — | fix(harness): make scratch roots private and retain guards during cancellation | harness | PORTED | f40b5dda0; `scratch.rs:56` mode 0o700, :168 test; `acp/mod.rs:1301` `(_scratch, mut child, …)` drop order | — | |
| 76e076997 | 2026-09-21 | — | style(harness): format adapter regression tests | harness | PORTED | cdf010d21 (rustfmt-only) | — | |
| 6f2e04dfa | 2026-09-21 | — | fix(harness): ignore JSON objects that are not RPC responses | harness | PORTED | 141318660; `jsonrpc.rs:314` jsonrpc!="2.0" skip, :326 result/error check; fixture `fake-robust-acp.py:81` | — | |
| 0e7070ee7 | 2026-09-21 | — | test(harness): expose event status in the OpenCode HTTP fixture | harness | PORTED | 957f9a212; `tests/opencode.rs:37` `statuses` | — | |
| 14da35974 | 2026-09-21 | — | refactor(harness): name the stdout observer callback type | harness | PORTED | 92922e493; `jsonrpc.rs:45` `type StdoutObserver` | — | |
| 17d7cfb95 | 2026-09-21 | — | fix(harness): require explicit Antigravity installation | harness | PORTED | 770944cd7; `acp/mod.rs:858` can_install, :862 install_harness, :1087 hint, :1549 find_server | — | |
| 8cecb37b7 | 2026-09-21 | — | feat(settings): install Antigravity explicitly on the selected device | settings / engine | PORTED | 50540d353; `registry.rs:36` `can_install`, `rpc.rs:1574` INSTALL_HARNESS, test `rpc.rs:3840` | — | Routing re-homed to engine-local (log 20974) |
| ba210ae52 | 2026-09-21 | — | test(harness): expose Antigravity detection in the live probe | harness | PORTED | d1d211725; `examples/antigravity_acp_probe.rs:15` `--detect-only` | — | |
| db50cd37d | 2026-09-21 | — | fix(opencode): version permission reply bodies at 2.0.4 | harness | PORTED | e72711b48; `opencode/mod.rs:3639-3641` `"decision"` key ≥2.0.4 | — | |
| 48c8aeeb1 | 2026-09-21 | — | fix(opencode): bind both task and subagent spawn tools | harness | PORTED | b1d34f222; `opencode/mod.rs:3830,4014,4045,4332` `"task" \| "subagent"` | — | |
| 53c7fe3e2 | 2026-09-21 | — | test(opencode): allow explicit permission answers in the live probe | harness (test probe) | MISSING | `examples/opencode_turn_probe.rs:50` still `panic!("probe must not ask for input")`; no `OPENCODE_PROBE_ANSWER_YES` | S | Dev-only live probe. Mostly moot since adb63378 (permissions no longer prompt), but upstream/main still carries it |
| 3cb03faab | 2026-09-21 | — | test(engine): handle partial archive request headers | engine (test) | MISSING | `crates/engine/src/rpc.rs:3881-3882` still reads only once with `socket.read(&mut buf)` | S | Flake fix for `explicit_install_rpc_verifies_archive_and_refreshes_descriptors`: the fake server answers before the request headers fully arrive |
| dc4d75866 | 2026-09-21 | — | test(engine): require the Antigravity server for enablement | engine (test) | PORTED | 5237a472f; `registry.rs:1248` `antigravity_server_path_controls_detection_and_enablement` | — | |
| 394f925f8 | 2026-09-21 | — | Bump version to v0.2.82 | other | SKIP:version-bump | — | — | |
| 3c7185b44 | 2026-09-19 | — | Add rich composer references and completion | ui-desktop / harness / proto | PORTED | 944065d93 + aade2ce02; `proto/src/invocation.rs`, `harness/src/skills.rs`, `ui/src/composer_markdown.rs`, `engine/tests/rich_composer_delivery.rs`, `icons/fast-tier.svg`. All 267 new fns are present (renamed) or also gone upstream | — | `examples/composer-polish-fixture.rs` not carried; upstream deletes it in b8c0ccea |
| da2adfe84 | 2026-09-19 | — | Fix rich-reference recovery for Cursor | engine | PORTED | aade2ce02; `engine/src/sessions.rs:4398` convert-before-JSON, :4728 Cursor excluded, test :6487 | — | |
| 8f34f3c90 | 2026-09-19 | — | Validate portable rich invocation delivery | proto / engine | PORTED | aade2ce02; `proto/src/invocation.rs:190` validate_harness_invocations, :444 test; `sessions.rs:994,1493` | — | |
| ea253ebbd | 2026-09-19 | — | Preserve OpenCode command intent through delivery | harness | PORTED | 8c8357905; `opencode/mod.rs:1656` selected_native_command, :3167 native_command_request | — | |
| 7a009a8f3 | 2026-09-19 | — | Stabilize adaptive composer menus | ui-desktop | PORTED | 77a11cc0d; `ui/src/pickers.rs` model_menu_height, open_model_card_keeps_height…, MENU_ITEM_RADIUS | — | The `centered_…opens_below` test is gone upstream too (replaced in f6ba2a57) |
| 216558ed0 | 2026-09-19 | — | Verify Cursor rich-reference recovery envelopes | engine (test) | PORTED | aade2ce02; `engine/tests/rich_composer_delivery.rs:200` assert_delivered | — | |
| 5a213ee25 | 2026-09-19 | — | Document rich composer visual evidence | docs | SUPERSEDED | b8c0ccea5 deletes `docs/screenshots/rich-composer/` | — | |
| 1c2cebfc9 | 2026-09-20 | — | Preserve commands and skip unreadable skills | harness | PORTED | d5c90f987; `skills.rs` unreadable_skill_files_do_not_hide…, `tests/claude.rs` ultrathink_preserves_selected_commands… | — | |
| ec4ba8148 | 2026-09-20 | — | Gate composer references and harden discovery | ui-desktop / proto / harness | PORTED | 7ac874196; `proto/src/workspace.rs` COMPOSER_REFERENCES_V1, `composer.rs` references_require_update / unsupported_host_preserves… | — | |
| f530da6fe | 2026-09-20 | — | Improve command discovery and mention chips | harness / ui-desktop | PORTED | 4dbb8b742; `skills.rs` CommandDiscovery + command_discovery_shares_overlap… | — | Chip constants were later removed upstream (b8c0ccea) and are absent in both |
| d1ae659a0 | 2026-09-20 | — | Refine Composer Mention Chips | ui-desktop | PORTED | 42cc2e4ca | — | Its chip-well code was removed upstream by b8c0ccea and is absent in both. Final state matches |
| b3ac8b631 | 2026-09-20 | — | Refresh rich composer screenshots and review notes | docs | SUPERSEDED | b8c0ccea5 deletes the screenshots and the fixture | — | |
| 13cb6d7c5 | 2026-09-20 | — | Move composer completion preferences to Shortcuts for active agents | settings | PORTED | 407c952e0; `ui/src/settings/completion.rs` | — | |
| 361bc4a3b | 2026-09-21 | — | Animate the compact work fold's body under TOOL_FOLD | ui-desktop | PORTED | 524faa5e1; `transcript.rs` compact_body_height, compact_fold_keeps_body_mounted… | — | |
| f6ba2a57d | 2026-09-21 | — | Fix rich composer review regressions | harness / ui-desktop | PORTED | 4d3275e24; `skills.rs` is_shared_skill, `tests/acp.rs` shared_acp_skills_require…, `pickers.rs` new_thread_model_menu_prefers_below… | — | |
| cce9f252d | 2026-09-21 | — | Correct composer completion settings documentation | docs | SKIP:docs-only | `docs/harness-skill-completion.md:48-49` still says "Settings → Agents … each production harness" | S | The doc is stale: the controls live under Shortcuts → Composer completion (13cb6d7c, ported). A one-line fix if anyone cares |
| 4be4e0fda | 2026-09-21 | — | Clarify Harness Completion Settings Documentation | docs | SUPERSEDED | b8c0ccea5 deletes `docs/harness-command-audit.md` | — | |
| e9d5004c1 | 2026-09-21 | — | Add explicit platform-aware agent CLI installers | harness | PORTED | 21ab109d9; `harness/src/install.rs` (manual_command :150, post_install :206; all 29 fns present) | — | |
| 45beb3da8 | 2026-09-21 | — | Route and cancel explicit installs on the selected device | engine | PORTED | b284610d0; `rpc/src/lib.rs:68` CANCEL_INSTALL, `engine/src/rpc.rs:1583` | — | Routing re-homed (no engine relay) |
| ae5f62ed4 | 2026-09-21 | — | Offer Install and Cancel for every eligible agent | settings | PORTED | df715f906; `settings/harnesses.rs:296` install_label, :996 "Cancellation failed —" | — | |
| 52cf341ab | 2026-09-21 | — | Document audited agent installation commands | docs (harness README) | PORTED | f6ed7610a; `crates/harness/README.md:14` | — | |
| 0b48258be | 2026-09-21 | — | Preserve device switching and manual installation hints | settings | PORTED | ef9e9357b; `settings/harnesses.rs:290` `"{hint}. Install with …"` | — | |
| 075c445fd | 2026-09-21 | — | Install OpenCode 2.x from @opencode/cli when npm is the fallback | harness | PORTED | 000be6af3; `install.rs:74,77,156` | — | |
| b8c0ccea5 | 2026-09-22 | — | Restore simple reference chips and trim composer review artifacts | ui-desktop | PORTED | 746172255; `icons/magic-stick-3.svg`, `icons.rs` | — | |
| 23a41f99d | 2026-09-22 | — | Consolidate composer and reference test fixtures | ui-desktop / proto (tests) | PORTED | 685c85faa | — | |
| 730c609e3 | 2026-09-22 | — | Bound skill discovery and reject special files | harness | PORTED | 27c496654; `skills.rs` | — | |
| a75cf1a61 | 2026-09-22 | — | Refresh completion catalogs and harden pasted references | ui-desktop | PARTIAL | 946af475a ported the catalog refresh (`composer.rs:7415` catalog_params, :8010 refresh_catalog). Missing: the attempts bound in `pasted_reference_tokens` (`composer.rs:5623` still caps only accepted tokens) and projectless paste resolution (`resolve_pasted_references` :7463 still bails on `file_search_params(...)=None`) | S | Port commit deferred the hardening to "ticket 10", which never landed it. See candidates |
| 3e621c492 | 2026-09-21 | — | Fix Add action button hover corners | ui-desktop | SUPERSEDED | Ported as f5ae478e3. The segment was later rebuilt by upstream ac4acbc21 (Session card), ported as Roboco 189c3d5a3, and `has_imports` is gone in both | — | |
| 9ba378b49 | 2026-09-22 | — | Bump version to v0.2.83 | other | SKIP:version-bump | — | — | |
| 2529cfd31 | 2026-09-21 | #500 | fix(files): keep explorer available for archived chats | files/editor | PORTED | c7b59913c; `files_panel.rs` has no `!chat.archived` filter; `files_panel_workspace_tests.rs:484` | — | |
| d721f301a | 2026-09-22 | #453 | Apply the conversation width setting to the composer | ui-desktop | PORTED | b0f6de488; `composer.rs` dock_reflow_* / conversation_resize_reflows… tests, `composer_dock.rs` snap_reflow | — | |
| 44dde6a2e | 2026-09-22 | — | Replace Opus 5 with Opus 5.5 in the Claude catalog | harness | PORTED | 8200e29f4; `claude/catalog.rs:345` | — | |
| 4ef4251db | 2026-09-22 | — | Fix reduced-motion dock test after the snap_reflow merge | ui-desktop (test) | PORTED | rode b0f6de488; `composer_dock.rs:1260` | — | |
| a54c19fa6 | 2026-09-22 | — | Bump version to v0.2.84 | other | SKIP:version-bump | — | — | |
| 258c80a95 | 2026-09-22 | — | Point the claude_models live-union fixture at Opus 5.5 | harness (test) | PORTED | dcc9836d5; `tests/claude_models.rs:21-33` | — | |
| 9b7983762 | 2026-09-22 | — | docs: acknowledge The Context Company and invite sponsorships | docs | SKIP:docs-only | README sponsor block | — | |
| a9a78d768 | 2026-09-22 | — | docs: remove redundant top-level sponsor links | docs | SKIP:docs-only | README | — | |
| 6c40f728b | 2026-09-23 | — | landing: swap troll tweets for real testimonials | landing | SKIP:landing | apps/landing | — | |
| 64f3e5df3 | 2026-09-23 | — | landing: show GitHub star count on Star buttons | landing | SKIP:landing | apps/landing | — | |
| 68ca9a855 | 2026-09-23 | — | landing: star count next to the nav GitHub icon | landing | SKIP:landing | apps/landing | — | |
| 3366a3800 | 2026-09-23 | — | landing: agent icons above the headline, add Used by developers from logos | landing | SKIP:landing | apps/landing | — | |
| 6c552f652 | 2026-09-23 | — | landing: Supported by section with sponsor button under the main shot | landing | SKIP:landing | apps/landing | — | |
| 0bbc1c5c8 | 2026-09-23 | — | landing: restyle Supported by as a quiet sponsor row | landing | SKIP:landing | apps/landing | — | |
| cbec8cd07 | 2026-09-23 | — | landing: remove Supported by section | landing | SKIP:landing | apps/landing | — | |
| c914b319e | 2026-09-23 | — | landing: Used by logos rest in the harness-icon faint color | landing | SKIP:landing | apps/landing | — | |
| adb63378a | 2026-09-24 | #533 | fix(opencode): auto-approve tool permissions as a temporary workaround | harness | PORTED | e920e9336; `opencode/mod.rs:3660` `let reply = "once"` | — | |
| 5731a0dcc | 2026-09-24 | — | Bump version to v0.2.85 | other | SKIP:version-bump | — | — | |
| b782d0437 | 2026-09-24 | #449 | Polish settings, providers and sign-in | settings / engine / ui-desktop | PARTIAL | Ported across about 12 commits (c60efe665, 849f0b4f9, a8032f3f2, 961066c0f, 269cbad20, f1eaa892e, 876b946ff, 9e1e7cc67, dd2e7940a, e3fa574da, 20187dc1a); the engine account/usage half landed via bbf590c8. Missing: (1) `is_openable_login_url`/`open_login_url` guard (Roboco `accounts.rs:932,1527`, `account_usage.rs:332,825`, `harnesses.rs:794` call `cx.open_url` on engine-supplied URLs unchecked); (2) Accounts page ignores `AgentLoginPoll.url` (`accounts.rs:1019-1075`); (3) `$`-skills + separate `/` default for every harness (`settings.rs:834` still Codex-only) | S–M | Roboco deliberately keeps its own grouped Accounts page, so the compact-list/⋯-menu redesign is not a gap. The sidebar account identity footer is cloud (SKIP). Engine→engine login forwarding was skipped per ADR 0004 |
| db2b06919 | 2026-09-24 | — | Bump version to v0.2.86 | other | SKIP:version-bump | — | — | |
| d0b0e89d9 | 2026-09-25 | #504 | Honor system proxy settings in packaged desktop apps | engine / harness / browser | PORTED | 7326f517d; root `Cargo.toml:161` `system-proxy`; `opencode/mod.rs:1166` no_proxy + test `tests.rs:800`; `browser/mod.rs:661` | — | Windows-relevant |
| d268830bd | 2026-09-24 | #541 | Reopen Settings on the section last viewed | settings | PORTED | 7d8964d93; `settings.rs:2138`, `shell.rs:716` reopenable | — | |
| e3f6f594a | 2026-09-25 | #527 | Retry the Windows identity lock through sharing violations | engine (Windows) | PORTED | Roboco bd20d842b (originated here); `engine/src/lib.rs:1069-1075` | — | Upstream's Windows-only regression test `acquire_retries_through_sharing_violations` was not carried (test-only) |
| 096f361f9 | 2026-09-24 | #511 | Stabilize iOS CI animation and persistence tests | ios / ci | SKIP:ios | apps/ios test seams (DocSaverScheduling) + macOS `ios-tests` job gating in ui-tests.yml | — | No Android-parity behaviour: `SessionStore.stop()` only returns its persistence Task, for tests |
| 5f60293c0 | 2026-09-25 | #529 | Show the text cursor over selectable chat text | ui-desktop | PARTIAL | 3904a3d30: `markdown/render.rs:1408` selectable_text_wrap (I-beam), :3800 test, `transcript.rs:8956` user bubbles. Missing: `.cursor(CursorStyle::Arrow)` on the code-block horizontal scrollbar hit strip (`render.rs:2489-2497`) | S | Hovering a code block's scrollbar shows an I-beam instead of an arrow |
| 88a07863f | 2026-09-25 | #528 | Fix Windows terminals starting in C:\Windows instead of the project checkout | terminal (Windows) | PARTIAL | Roboco `engine/src/terminals/windows.rs:292` has a simpler `plain_current_dir` (case-sensitive `\\?\UNC\`, strips any `\\?\`, returns String). Missing: lowercase `\\?\unc\`, rejection of `\\?\Volume{…}`/GLOBALROOT/`\\.\`/`\??\`/malformed UNC with InvalidInput, and both tests | S | Windows hardening. Today an odd verbatim path is passed through and cmd.exe silently falls back to C:\Windows |
| 87ef6c8ff | 2026-09-25 | #536 | Restore tab drag-reorder on Windows | ui-desktop (Windows) | PORTED | Roboco 2bf73c60e (originated here); `click_activation_drag_enabled` gone; `shell.rs:16011` surface_tab_drag_reorders_the_strip; zui pin carries the Windows drag threshold (AGENTS.md) | — | |
| 23e258ffc | 2026-09-24 | #474 | Open terminals from the new-chat canvas | terminal / engine / ui-desktop | PORTED | 524cd3cd9 + 4d10c7c52; `engine/src/rpc.rs` resolve_open_terminal_cwd + test; `ui/src/state.rs` canvas_panel_key, canvas_terminal_uses_the_selected_project_folder | — | |
| 94304283a | 2026-09-25 | #535 | fix(update): check for app updates without workspace sync | updater/install | PARTIAL | The core intent was solved Roboco-side: `engine/src/lib.rs:565-578` checks unconditionally, `update/src/lib.rs:258` RELEASES_PAGE, `app_update.rs:568` "download from GitHub". Missing: release-metadata size/time caps (`RELEASE_METADATA_MAX_BYTES`/`_TIMEOUT`, streamed bounded read; `update/src/lib.rs:179` still uses unbounded `resp.json()`/`.text()`); quiescence re-check right before the swap (`apply_inner(require_quiescent)`; Roboco `update/src/lib.rs:1158-1171` checks once, then fetches and stages, then swaps) | S | Both pieces still exist in upstream/main (`update/src/lib.rs:78,1273,1331`) |
| 18cca528e | 2026-09-24 | #539 | chore(cursor): update pinned SDK | harness | PORTED | e7539755e; `cursor/mod.rs:60` `@cursor/sdk@1.0.32` (= upstream/main) | — | |
| c2230f073 | 2026-09-24 | #311 | Add file browser button to diff file headers | git/source-control | PORTED | 3dc42d22d; `changes.rs:3622` diff-open-file, `shell.rs:3862` | — | |

## Port candidates (MISSING + PARTIAL), most valuable first

### 88a07863f (#528) Fix Windows terminals starting in C:\Windows instead of the project checkout — hardening remainder
- For users: on Windows, a terminal opened for a checkout whose canonical path is a verbatim form Roboco's helper doesn't handle starts in the wrong folder. Examples: `\\?\unc\…` in lower case, a volume-GUID path, a malformed UNC path. cmd.exe silently falls back to C:\Windows. Upstream's version refuses such paths with a clear error. Windows is Roboco's main platform.
- Missing: Roboco's `plain_current_dir` (`crates/engine/src/terminals/windows.rs:292`) is the early, simpler cut that came in with 72b8eb487. It lacks the case-insensitive `UNC\` prefix, the `\\.\` / `\??\` / other-`\\?\` namespace rejection, `io::Result` and the call-site `?`, and the two tests (`plain_current_dir_strips_verbatim_prefixes`, `plain_current_dir_rejects_other_verbatim_namespaces`). Upstream file: `crates/engine/src/terminals/windows.rs` (upstream/main:267-390).
- Dependencies: none. No wire change. Risk: low. A valid drive or UNC path behaves the same; only exotic paths now error instead of landing in C:\Windows.
- Effort S

### b782d0437 (#449) Polish settings, providers and sign-in — three small remainders
- For users:
  1. **Login URL guard (security).** The desktop opens whatever sign-in URL an engine hands back. With fleet/paired engines (ADR 0011), a remote engine's URL is opened on this machine. Upstream only opens `https://` or loopback `http://` URLs (`is_openable_login_url` / `open_login_url`).
  2. **Late sign-in page on the Accounts page.** Antigravity's sign-in page is published through `PollAgentLogin.url` after the start reply, which has an empty `url`. On Linux the engine installs a no-op browser (`agent_accounts.rs:2272-2284`, `cli_opens_browser=false`), so the desktop must open the poll's URL. Roboco's Accounts page poll loop (`crates/ui/src/settings/accounts.rs:1019-1075`) ignores `poll.url`. Connecting Antigravity from Settings → Accounts on Linux therefore never opens the browser, and a remote engine has the same problem. `settings/harnesses.rs:792` and `account_usage.rs` do handle `poll.url`, and the web client does too (`settings-agents.tsx:417`).
  3. **Skill-completion default.** Upstream turned `$` skills plus a separate `/` menu on for every harness (`SkillCompletionPreferences::for_harness`). Roboco `crates/ui/src/settings.rs:834` keeps the old Codex-only default. Saved preferences are unaffected; only the default changes. The web settings should mirror whatever is chosen.
- Files: upstream `crates/ui/src/settings/accounts.rs:119-145` (guard), :985-987 (poll URL in `apply_poll`), `crates/ui/src/settings.rs:1021`. Roboco: `settings/accounts.rs`, `account_usage.rs`, `settings/harnesses.rs`, `settings.rs`, plus web equivalents (`web/packages/app/src/lib/accounts.ts`, `routes/settings-agents.tsx`) for the guard.
- Dependencies: none. No wire change (the `AgentLoginPoll.url` field already exists, `proto/src/entities.rs:1244`). The default flip is a product choice. It also needs `docs/harness-skill-completion.md` updated, and that doc is already stale (see cce9f252d).
- Effort S (items 1 and 2 are S; item 3 is a decision plus S)

### 94304283a (#535) fix(update): check for app updates without workspace sync — hardening remainder
- For users: an update check can't hang or buffer an unbounded response from a broken or hostile release feed. Before a managed Linux engine swaps itself, it checks again that no run or terminal started while it was downloading and staging.
- Missing in `crates/update/src/lib.rs`:
  - `fetch_release_metadata[_with_limits]`: a 1 MiB cap on Content-Length and on the streamed body, plus a 60 s total deadline, used for both `manifest.json` and `latest.txt`. Roboco uses `resp.json()` and `.text()`, bounded only by connect/read inactivity timeouts.
  - `apply_inner(require_quiescent)`: the auto-apply loop re-checks quiescence after staging, right before the symlink swap, and loops back to waiting when activity began. Upstream test: `release_metadata_has_size_and_total_time_limits`.
- Dependencies: none (Roboco already has the durable-update core from 109b39f3). No wire change. Risk: low.
- Effort S

### a75cf1a61 Refresh completion catalogs and harden pasted references — pasted-reference remainder
- For users:
  1. Pasting a large snippet with thousands of `@foo`/`$x`/`/y` candidates that get rejected (code, links) can stall the UI. Upstream bounds parse attempts, not only accepted chips.
  2. Pasting `$skill /command` text into a projectless chat (no project, device selected) does nothing in Roboco. Upstream resolves commands and skills through `catalog_params` even without a file-search context, and drops the result if the target device changed meanwhile.
- Missing: `crates/ui/src/composer.rs:5577-5625` (`pasted_reference_tokens` still counts only accepted tokens) and `resolve_pasted_references` (:7452), which still requires `file_search_params` and keys its context on harness instead of skill preferences. Upstream tests: the `"@example ".repeat(4_000)` bound case and `projectless_paste_discovers_invocations_and_rejects_changed_targets`.
- Dependencies: b8c0ccea (ported). No wire change.
- Effort S

### 5f60293c0 (#529) Show the text cursor over selectable chat text — scrollbar arrow
- For users: hovering a code block's horizontal scrollbar shows an I-beam instead of an arrow.
- Missing: `.cursor(CursorStyle::Arrow)` on the `{key}-scrollbar` hit strip in `crates/ui/src/markdown/render.rs` (~2489). Upstream/main:2844.
- Effort S (one line)

### 3cb03faab test(engine): handle partial archive request headers
- For users: none directly. It removes a CI flake in `explicit_install_rpc_verifies_archive_and_refreshes_descriptors`: the fake archive server must read until `\r\n\r\n` before replying.
- Missing: `crates/engine/src/rpc.rs:3879-3882`. Effort S (6 lines).

### 53c7fe3e2 test(opencode): allow explicit permission answers in the live probe
- For users: none. It adds an `OPENCODE_PROBE_ANSWER_YES` mode to the manual `opencode_turn_probe` example. Since adb63378 (ported) permissions are auto-answered "once", so the probe rarely needs this. It still matters if a `question.asked` arrives.
- Missing: `crates/harness/examples/opencode_turn_probe.rs:50`. Effort S. Lowest value.

## Notes
- Two of the "Vu Nguyen" upstream PRs in this window were upstreamed from Roboco: #527 identity lock (bd20d842b) and #536 tab drag (2bf73c60e). Both are present. The I-beam port (#529) arrived through 3904a3d30 (web-bugs wave), which carried the desktop half too.
- `docs/harness-skill-completion.md:48-49` in Roboco is stale: it points at "Settings → Agents" instead of "Settings → Shortcuts → Composer completion", and fixing it is the content of cce9f252. Classed docs-only per the brief, but it is a two-line fix.
