# Pass 2 — big-3 (slice audit of 14 PORTED commits)

Roboco HEAD `85be4485a`; upstream/main `31d26eeb6`. Method: each upstream diff split into slices
(every added fn/const/test/fixture arm/copy string/platform branch); each searched in HEAD with
renames applied, by behaviour where code moved. Later upstream commits on the same files checked
via `git log <sha>..upstream/main -- <files>` and cross-checked against part-0*.md rows.

Verdict: all 14 confirmed PORTED for user-visible behaviour. Remaining gaps are test-coverage
holes, one Roboco-only Windows shortcut fix (upstream has the same bug), and refinements already
tracked under their own SHAs.

---

## 91fd851c7 (#748) Harden Claude/Codex adapters: no early "done", Stop kills, sends never do
Roboco ports: `1140aeccb` (foundation), `3321c76b9` (pi), `33ded9af9` (claude), `1afe3a7cc` (codex),
`389f1f57f` (acp), `1966306fe` (opencode), `3e60edf87` (engine), `97e39f74a` (tests),
`4bd1cea9d`/`b1ecfc406` (cursor). 183/186 added identifiers and every fixture `scenario:*` arm present.

- [present] harness `lib.rs`: `SteerMessage.attachments/config` + `text()` (`lib.rs:68`), `TurnControl` stop/background (`:87`, `background_live` `:111`), `same_model_options` (`:119`), trait defaults `stops_turn_in_place`/`reconfigures_in_place`/`same_runtime` (`:208-219`), `RunControls.turn`
- [present] `shutdown_agent` — unix descendant snapshot + `terminate_tree`; non-unix job object (`lib.rs:596-612`)
- [present] `process.rs` `descendants()`/`terminate_tree()` + test `tree_teardown_reaches_shells_in_their_own_process_group` (`process.rs:308,504,768`); Roboco already has the later #977 PID-reuse-safe `Tracked` form
- [present] `process/owned.rs` `shutdown()` descendant snapshot → Roboco `crates/harness/src/acp/child.rs:81-125` (also used by pi). This commit added nothing to `owned::configure` (that miss is 2e151ca65's, big-1)
- [present] Claude: uuid per stdin line, `command_lifecycle` Commands tracker, now/next priority, `perTaskStopAffordance`, `STOP_SETTLE`, `cancel_queued`, stale late-result drop, `background_tasks_changed`, live `set_model` + `apply_flag_settings`, `same_runtime`/`reconfigures_in_place` (`claude/mod.rs:725-736`), descendant teardown (`:1873,1971`); wire overage fields, `blocks()`, `MessageFrame.uuid`, `SystemFrame.tasks`
- [present] Codex: `TurnRouter.settles()` stale-completion gate, `clientUserMessageId` steer confirm, stop waits for `turn/started`, `COMMAND_EXIT_GRACE`, config only on `turn/start`, `same_runtime`/`reconfigures_in_place` (`codex/mod.rs:636-652`), teardown (`:2075,2159`)
- [present] ACP: stop-in-place (`acp/mod.rs:2204`), `select_session_config` + `ConfigRules` (`:3838`), mid-turn config deferral, steering-call drop on stop, `set_config_option` reply without `configOptions` records `currentValue` (`:3926-3934`), interrupt descendant snapshot (`:5218,5374`)
- [n/a] ACP `DevinCatalog` live re-selection — wraps `devin_models::Catalog::selection`/`FUSION`, which Roboco never took (wave-3 ticket 09 exclusion, `.scratch/upstream-ports-2026-10-wave3/issues/09-side-chats-ui.md:43`)
- [present] OpenCode: stop-in-place (`opencode/mod.rs:437`), rejected/unacked stop tears down with mailbox closed first, v1 `/global/event` envelope unwrap, images on queued prompts, model/effort on prompt (2.x), `shutdown_agent` (`:1033`)
- [present] Pi: `thinking_selected`, `reconfigures_in_place` (`pi/mod.rs:764`), `configure_live` on config-carrying steers, attachments on parked steers
- [present] Cursor + `shim.mjs`: `{"op":"stop"}`, `stopping`→Interrupted, `modelChanged`/`steerableNow`/own-turn selection rule, descendant teardown (`cursor/mod.rs:330-335,922-938`), test `a_model_switch_names_its_model_from_its_own_send_on`
- [present] Engine `doc_host.rs`: `continues_with_sent_message`, `await_earlier_prompts` (2s/15s), `await_earlier_controls` (30s), `cancel_held_sends` (`doc_host.rs:1963`), `cancelled_by_stop`, `held_sends` replay with original request, Stop ends a parked runtime holding background work
- [present] Engine `sessions.rs`: `retiring` under ledger lock, `stop_turn` with terminate fallback, `cancel_sent_messages`, orphan re-dispatch, reaper defers on `background_live` (+#831 extra window), canonical-cwd `resume_for` (`sessions.rs:3147-3157`), recent user message treated as fresh on crash recovery
- [present] `rpc.rs` deleteSpace → `terminate` (`rpc.rs:1148`); `titles.rs` `turn: TurnControl::default()` (`:291`); engine dev-deps `test-util`/`tracing-subscriber`
- [present] Tests: `runtime_survival.rs` 18/18, `restart_resume.rs` 23 (≥18), `runtime_survival_live.rs` 5/5, `adapter_chaos_live.rs` 11/11; field updates across windows_native/acp_quiet/acp_stall/managed_install/pi_live/real_*
- [partial] `tests/acp.rs` `a_stopped_turn_keeps_the_agent_for_the_next_prompt` → renamed `a_stopped_prompt_settles_cancelled_and_the_session_takes_the_next` (`acp.rs:2136`) — equivalent
- [missing] `tests/codex.rs` `a_stopped_turn_keeps_the_app_server_for_the_next_prompt` — fixture arm exists (`fake-codex.sh:554`) but no test drives it (verified: HEAD `codex.rs:1975` #748 block has no stop-in-place test)
- [excluded on purpose] voice/realtime control field; prompt-guard mailbox backpressure / `command_drain_lock` (Roboco uses try_send ledger + Interrupt guard, `97e39f74a`; residual tracked as 731697b66 PARTIAL in part-04)

Later upstream refinements: 2e151ca65 (#977) `Tracked` teardown present; its `owned::configure` env-strip/process-group gap belongs to big-1. 987bb2f87 only touches Muse (declined). 95b294658 (#1043) already MISSING in part-07. Nothing else touches #748 symbols.

## 987bb2f87 (#978) Agent CLI updates through the real owner, known-bad release list, Muse Code
Roboco port `b21e9f39b` (wave-9 ticket 07). Muse slices DECLINED (wave-9 ticket 08) and excluded.

- [present] `ownership.rs` (npm own-prefix `--allow-scripts`, pnpm 10.4+ build approval, bun/yarn/Volta, mise shim/install/wrapper, Homebrew mise, project `node_modules` manual) — byte-identical after rename, 4/4 unit tests (`harness_updates/ownership.rs:338,395,433,455`)
- [present] `compatibility.rs` + `compatibility.json` identical, 4/4 tests (`compatibility.rs:184,231,242,254`)
- [present] `PlanContext`, `update_plan` refuses mise/project installs, `can_apply_update_for(installed)`, `Manual::Project`
- [present] `UpdatePlan::Package` + pnpm `--version` probe (`pnpm_version`)
- [present] Homebrew detection skips mise kegs
- [present] apply refuses held-back release (`harness_updates.rs:1255`)
- [present] install changed since check → re-check ("installation changed since it was checked")
- [present] `apply_compatibility` on every checked row incl. failed; held-back resets Available/ManualActionRequired → Current (`harness_updates.rs:796-801`)
- [present] proto `HarnessUpdateStatus.compatibility`/`held_back`, `HarnessCompatibility{,Status}` (`proto/src/agent.rs:195-217`), wiregen regenerated
- [present] settings copy: danger-tone compatibility message, "v{x} held back: not supported yet" (`settings/harnesses.rs:134,151`)
- [present] test timeout 1s→10s
- [by design] edge `releases/harness-compatibility.json` hourly feed / `set_compatibility_feed` — no edge (ADR 0003/0004)
- [missing] 5 `#[cfg(unix)]` coordinator tests: `a_release_known_not_to_work_is_held_back_and_never_installed`, `an_installed_release_known_not_to_work_says_so_and_a_withdrawn_policy_clears`, `an_npm_install_is_updated_by_its_own_prefixs_npm`, `installs_zeron_cannot_prove_it_owns_stay_manual_with_the_reason`, `an_install_that_changed_since_its_check_is_checked_again_not_updated`
- [missing] proto assertion that old peers neither send nor expect `compatibility`/`heldBack` (Roboco test `proto/src/agent.rs:895` sets fields, never asserts omission)
- [missing, trivial] `tracing::info!("agent sign-in cancelled")` in `agent_accounts.rs` cancel path — no user effect

Later upstream refinements: none to ownership/compatibility (only unrelated #1018, #1036, #1022, #1046, #1048, #1042 — all already rows in part-07).

## 73bd3c842 (#775) Composer: mention images and files as chips
Roboco ports `1ed398d68` (+#816 Geist padding, +1390e621 intent), `b07a14767` (mobile layout, proto).

- [present] proto `attachment_mentions.rs` all fns (`image_label`, `attachment_mention_link`, `is_image_path`, `attachment_display_name`, `attachment_mention_prompt`, `demote_unattached_mentions`, `names_attachment`) (`:80-189`)
- [present] invocation.rs image chips → plain label for providers; test `image_chips_reach_every_provider_as_their_plain_label`
- [superseded by Roboco design] engine `read_chunk` non-image uploads: Roboco has no path jail; any readable file returns typed from leading bytes (`engine/src/uploads.rs:287-305`, tests `:669-673`, `m5c_accounts_uploads_titles.rs:885`). Upstream's "other roots stay image-only" test correctly not carried
- [present] ui `attachments.rs`: `AttachmentContent::File`, `stage_plain_file` (`:427`), `pair_with_chips` (`:109`), `unchipped_attachments` (`:122`), `read_attachment_file` (`:725`), `FILE_ATTACHMENT_ONLY_TEXT` "See the attached file(s).", `seed_staged*`, `FileRpc`, 6/6 tests incl. `folders_and_oversize_files_are_refused_with_a_message`
- [present] `composer/chip.rs`: `ChipKind`, `ChipIcon`, `paint_chip`, `chip_text`, `CHIP_ICON_SLOT`, `CHIP_TRAILING_PAD`, `ChipAttachment`
- [present] composer substrate: all 65 added fns/tests (draft numbering, `@`-list pins, two-way chip/attachment sync + undo, queued-edit reconciliation, image-chip click `OpenAttachment` with pointer cursor `composer.rs:1795,3994,5077`)
- [present] multiple refusals from one drop → one notice; external `FileDropEvent` test in `chat_dropzone_tests.rs`
- [present] `queue.rs`: `queue_row_text`, `queue_edit_text`, `clear_queue_edit_local`, `swap_in_queued_draft` (`:1625`), file-icon tiles, test `queue_rows_label_attachment_chips_and_file_only_sends`
- [present] `transcript.rs`: `ChipOverlays`, `CHIP_STATUS_SIZE`, `upload_status`, `open_chip_image` (`:6157`), `user_file_pill` (`:9120`), both tests
- [present] `loaders::upload_progress_arc` (`:311`), `file_icons::raster` (`:213`), `gallery.svg`; every non-test copy string
- [present] mobile core `layout/rows.rs`: `user_chips` (`:608`), `UserChip`, `FILE_PILL`, `prepare_user_text`, `image_preview_link` (`:592`), chipped attachment leaves strip (`:525`); `markdown.rs` badge pill with `ToolWell` (`:40`); mobile-client placeholder hiding (`crates/mobile-client/src/attachments.rs:41,247`, test `:714`)
- [present] Android parity of iOS tap-image-chip viewer: `LinkRoute.kt:60` `roboco-preview` → `LinkRoute.Preview` (+ tests), pills via `TOOL_WELL` (`TranscriptPalette.kt:70`)
- [n/a] `crates/client/src/demo/transcripts.rs` (no demo mode in Roboco); macOS CI filter

Later upstream refinements: #816 present; #855 PORTED (`f199cf67d`); others touching these files are separate features already classified (#1036, #1022, #1068, #1062, #967, #966, #912, #900, #813).
NOTE: pass 1 range ended at `5d2b91901`; `5d2b91901..31d26eeb6` adds `b7942ee8b` (#814 browser annotations — "Annotation N" composer chip, `composer.rs` +332, `chip.rs` +3) and `46d4e42b3` (#1067 Markdown in user messages). These need rows in a delta pass.

## a73fa8fe4 (#471) Add optional compact model picker and effort controls
Roboco port `74abedd5a` (#471 + #721); `9ab5a364d` (#745/#749).

- [present] `pickers/compact.rs`: all constants (except `LIST_HEADER`, see #799), `CompactPage`, `EffortStops`, `ProviderRow`, `CompactEffort`, `CompactMotion`, `ScalarTransition`, `Shimmer`, `PickerHint`, `render_compact_model_panel` (`:906`), `pick_effort_at` (`:700`), `effort_fraction/fill`, `cycle_compact_provider`, `show_compact_starred`, `reset_compact_search`, 2/2 unit tests
- [present] keyboard (`compact.rs:574,843-893`): panel Esc / Up-Down opens list / Enter-Space / Tab-Shift-Tab providers / Left-Right-Home-End effort; provider page Esc/Up/Down/Enter; list ⌘⇧F star (`pickers.rs:3295-3304`)
- [present] `pickers.rs`: `fast_mode_values`, `model_chip_width`, `resizing_chip_text`, fixture helpers, 26pt compact rows, `compact_option_visible`; 9 tests (`compact_all_models_keeps_favorites_first…`, `compact_fast_button_toggles_cursor_fast…`, `compact_list_hides_loading_and_failed_harnesses`, `compact_panel_shortcuts_drive…` `:8407`, `compact_picker_shares_selection_reset…`, `compact_slider_drives_cursor_effort_option`, `every_fast_mode_encoding…` `:7075`, `open_model_trigger_follows_the_selection_while_open`, `switching_provider_restores_its_last_model_and_effort`)
- [partial, recorded divergence] `configured_in_place`/`card_order`/`CONFIG_CARD_WIDTH` hover-card hunk + test `compact_panel_lists_settings_of_models_configured_in_place` — absent; belongs to excluded Devin-Fusion slice of #498 (`part-prs-B.md:317`, wave-3 ticket 09). Adapted test at `pickers.rs:~8570-8605`. Part-05 claim verified
- [present] settings: `UiSettings.compact_model_picker` (default true, `settings.rs:423`), test `compact_model_picker_is_default_and_opt_out_persists`, General row "Compact model picker" / "Adjust effort with a slider, then open the model list when needed." / `compact-model-picker-toggle` (`settings/shortcuts.rs:407-422`)
- [present] per-model effort memory `reasoning_for`/`remember_reasoning` (`settings/composer.rs:151,158`)
- [present] canvas target `CanvasTarget`/`current_canvas_target`/`restore_canvas_target` (`state.rs:1904`), `tabs.rs:252` restores branch, both tests
- [present] `motion::fast_tier`/`menu_in_from`/`menu_out_toward` (`motion.rs:497,509,1648`), `popover::MENU_TRAVEL`/`menu_motion_from` (`:528`), `edge_fade::fade_scroll_x` (`:148`)
- [present, refactored] glass plates folded into `frost.rs` (`light_plate`/`accent_plate`/`thumb_plate` `:262,287,318`)
- [present] `haptics::selection_step/step_count` (macOS-only compile, as upstream); `fast-tier-bold.svg`; `compact-picker-fixture` example behind feature; chip glide beside Send
- [skip] screenshots (docs), Cargo pin bumps
- [Windows issue, NOT a port gap — upstream identical] ⌘⇧F star and ⌘1…⌘9 row picks test `modifiers.platform` (`pickers.rs:3296,3362,3372`) = Win/Super key on Windows/Linux, not Ctrl; hints hard-coded `"⌘⇧F on the highlighted model"` (`compact.rs:211`) and `"⌘{n}"` (`pickers.rs:5020`) despite Roboco's platform-aware `settings::display_combo`/`badge_combo_on`

Later upstream refinements: #721/#745/#749 PORTED; `672cc1444` PORTED; `dbb639be3` (#799 provider tab strip, `LIST_HEADER` 40→80) not in HEAD (`compact_providers` still at `pickers.rs:864`) — already part-05 PARTIAL slice (d); `daf047b7f` (#1048 "Back · Esc" hint + star/tab tooltips) already MISSING in part-07.

## 787f58311 (#349) Add Antigravity ACP, improve context UI, persist window geometry
Roboco port `cf2ad5f8e`, reshaped by later Antigravity ports (`770944cd7`, `50540d353`, `2207836f8`, `ef95d47eb`, `7308519c3`, `dea92cf41`, `1ff59896c`, `5237a472f`, `d1d211725`, `20187dc1a`, `bf9a1011c`, `20ea7ca8c`). iOS hunks skipped; Android `BrandMarks.kt` ANTIGRAVITY present.

- [present] `archive_install.rs` identical to upstream/main (rename + one moved fn)
- [present] `acp/antigravity_paths.rs` incl. Windows `user_home`/`named_home` arms — identical
- [present] all 25 Antigravity items in `acp/mod.rs` (archive, skill dirs, auth aliases, `ConfiguredAuthMethod`, settings HJSON, `sign_in_auth_method`, skill commands/frontmatter, `ANTIGRAVITY_LINUX_ARGS`, `antigravity_spec`, sign-in/out timeouts, `SignInProgress`, `sign_in_url`, `sign_in`/`sign_out`, `validate_config_model_selection`, `is_model_config_option`, `is_auth_required`, effort grouping, `settings_holding`) + 23/23 unit tests
- [present] `tests/acp.rs` additions + `fake-antigravity-*.sh` fixtures (the one absent test was removed upstream too)
- [present] `examples/antigravity_acp_probe.rs`, README row
- [present] engine sign-in task flow `LoginFlow::Task`, `TaskLoginState`, `start_antigravity_login`, `poll_task_login`, cancel aborts (`engine/src/agent_accounts.rs:451,473,2681,2739`)
- [present] proto `AgentLoginPoll.url` (`entities.rs:1244`), `HarnessId::Antigravity`; `update_harness_enabled` sign-out-on-disable (`rpc.rs`); lazy registry descriptor
- [n/a, verified] opt-in enablement fns absent in both upstream/main and HEAD (replaced by detection-driven enablement 3f8f159b6/52af27280, both ported) — confirms part-01 note
- [present] UI marks (`icons.rs:232`, pickers, accounts); sign-in phase copy (Roboco keeps more than upstream/main — recorded divergence)
- [present] context UI `with_separators`, `has_window`, tooltip `whitespace_nowrap`, tests `indicator_needs_a_reported_window`, `token_counts_are_grouped_by_thousands` (`context_usage.rs:98,113,162,172,215`) — file identical to upstream/main
- [present] window geometry `WindowGeometry{is_valid, from_bounds, restore (display UUID → primary fallback), fit (clamp, keep titlebar visible)}`, 7/7 settings tests (`settings.rs:750-2307`), `restored_main_window_bounds`, `save_main_window_geometry` (skips fullscreen/maximized), `observe_main_window_geometry`, save+flush on close (`ui/src/lib.rs:305-454`), shell persistence test (`shell.rs:14494-14549`)
- [n/a] `shell.rs` `window_geometry = current.window_geometry` — removed upstream later (80b946b15), absent in Roboco too
- [present] `adapter_install.rs` visibility changes

Later upstream refinements: 109b39f30 (#595 X11 close panic) present (`lib.rs:343,453`); 79b8d84c8 (#547) PORTED (`ea6b1821e`); the 10 Antigravity follow-ups PORTED. d1dc29f6c (#617) PARTIAL — Windows `--noop-browser` missing (`acp/mod.rs:3308` returns Err on Windows), already recorded in part-05. b782d0437 (#449) PARTIAL already recorded. 731697b66 Devin model-first deferred `set_config_option` pass absent (`acp/mod.rs:3943`), already part-02 PARTIAL.

## 92153025b (#498) Add zeron mcp server with parent-linked chats
Roboco port `20fbbab5c` (`crates/roboco-mcp`); Roboco's MCP now exceeds the upstream commit.

- [present] `roboco mcp` subcommand, logs to stderr (`apps/roboco/src/main.rs:176-178,258`)
- [present] JSON-RPC layer (initialize, ping, tools/list, tools/call) `roboco-mcp/src/jsonrpc.rs`; test `initialize_and_list_over_jsonrpc`
- [present] env config `ROBOCO_IPC_PORT` (27654), origin `ROBOCO_CHAT_ID`/`ROBOCO_DEVICE_ID` (`roboco-mcp/src/lib.rs:32-54`; engine stamps at `engine/src/sessions.rs:3068-3070`)
- [present] all 15 tools with all upstream params (whoami, list_devices, list_projects, list_harnesses, list_models, list_chats, get_chat, create_chat, read_chat, send_message auto/run/steer/queue, wait_for_turn, interrupt_chat, respond_to_input, archive_chat) (`tools.rs:72-220`); Roboco adds previews, fork_chat, batch tools
- [present] send attribution + self-message refusal, `deliver`, `await_turn`, `default_harness`, `last_pending_input` (`tools.rs:1425ff`)
- [present] chat/project resolution by id/prefix/title (+path/name/suffix), `SNAPSHOT_TIMEOUT`, `RESUBSCRIBE_DELAY` (`roboco-mcp/src/roboco.rs`)
- [present] all upstream unit tests (catalog_is_well_formed … space_resolution_*)
- [present] `Chat.parent_chat_id` → `parentChatId` (`doc/src/registry.rs:1242,1646`, `workspace.rs:288`); `create_chat_with_parent` ignores blank (`engine/src/workspace_host.rs:492,555`, `rpc.rs:715,1102`)
- [present] sidebar/jump slots/Archived hide parented chats (`ui/src/state.rs:1871`, `shell/spaces.rs:5814`, test `visible_chats_hide_spawned_children` `state.rs:5480`)
- [present] `docs/mcp.md`
- [cloud-skip] `crates/sync/tests/registry_*`

Later upstream refinements: #706 PORTED; 731697b66 PARTIAL (part-02); #1036 `show_image`, #1022, #1046, #1062 (sender caption — upstream now uses `agent_messages::agent_message(label, origin_id, text)`, Roboco still writes a text header with `short(origin_id)` at `tools.rs:1440-1449`), #900, #819 — all already MISSING rows.

## e7ddbbe72 (#550) Keep active chat sync connections stable under load
- [cloud-skip] `engine/src/doc_host.rs` sync admission (`ACTIVE_SYNC_CAP` 12→28, `SYNC_CATCH_UP_QUANTUM_MS`, `focus_chat`, `sync_protected`, fairness victims, wake catch-up) — runs on edge chat2 rooms; Roboco's doc_host has none of it
- [cloud-skip] 13 tests in `doc_host_sync_tests.rs`, `sync_resources.rs` limits, relay part of `device_routing.rs`
- [cloud-skip] `FOCUS_CHAT` RPC + UI `focus_chat_sync`/`focus_subagent_sync`/`set_right_active` hook (sync priority only)
- [cloud-skip] `crates/sync` budget/chat_client/store; `docs/sync-*.md`
- [n/a] engine `rpc.rs` peer-forwarding `subscribe_scoped` (Roboco UI dials paired engines directly)
- [present] `RpcClient::subscribe_scoped` (`crates/rpc/src/client.rs:273`)
- [present] `subscribe_checked` builds subscription before send (`client.rs:318-333`)
- [present] MCP one-shot reads use `subscribe_scoped` (`roboco-mcp/src/roboco.rs:181,186`), test `one_shot_reads_release_quiet_watches` (`:693`)
- [present] UI transcript/queue/subagent watches via `subscribe_checked` (`ui/src/state.rs:3462,3610,3661`)
- [missing] rpc unit test `scoped_subscription_returns_before_first_item_and_cancels_silence` + `"Silent"` method on `CancelAwareService` (`crates/rpc/src/lib.rs`) — verified no hit

Later upstream refinements: acd7c9ba7 PORTED (`2f51b449a`); 71033cc96 (#975 TCP_NODELAY) and afca583c1 (#484) already MISSING.

## efa1a0752 (#444) Reduce redundant sync traffic and keep slow transfers progressing
Roboco port `dae6cccff` onto `crates/rpc/src/pump.rs` (all WS RPC: client dials, IPC, paired listener).

- [present] `ProgressIo`/`Progress` activity clock (`record`, `latest`, `writing` guard, `while_progressing`) (`pump.rs:69-226`)
- [present] `FRAGMENT_BYTES`=4096 fragmented sends with pings between (`pump.rs:37,228`)
- [present] read/write leases extend while bytes move; "stopped progressing" log (`pump.rs:274ff`)
- [present] `PING_INTERVAL` 15s / `SILENCE_LEASE` 45s (`pump.rs:31-32`)
- [present] reliability tests (dead peer, stalled write, consumer drop, inbound deadline, pong-not-forever, slow link order, slow upload/download, buffered upload pongs, idle traffic bounded) (`pump.rs:502-898`)
- [n/a] `sync/src/dial.rs` / `rpc/device_room.rs` edge relay transport wrapping
- [cloud-skip] `chat_client.rs` `push_pending` dedupe + tests; `transport_live`, wrangler, scripts, docs

Later upstream refinements: none.

## a9e6c48b7 (#1023) Previews: only ports Zeron started, listed beside the chat
Roboco ports `b6b7c8aa2`, wave-9 `55b30eddc`.

- [present] `ORIGIN_ENV`=`ROBOCO_ORIGIN`, `chat_origin`, `terminal_origin`, `PreviewService.chat_id` (`proto/src/preview.rs`)
- [present] agent runs stamp `chat:<id>` (`engine/src/sessions.rs:4756`); terminals stamp `terminal:<id>` before shell start (`terminals.rs:255`); terminal→chat memory `REMEMBERED_TERMINAL_CHATS`=4096 (`:100,411,419`)
- [present] `Origins` cache (pid, start time), ancestor walk ≤64, stops at engine/pid≤1, unreadable env retried not cached, dead pids dropped, exact `origin_marker` (`preview/src/discovery.rs:158-246`)
- [present] Linux `/proc/net/tcp*` missing → None, sockets before cwd/cmdline, `/proc/<pid>/environ` (`discovery.rs:294-385`)
- [present, extra] Windows branch (`tcp_sockets`, PEB env via `sysinfo_environment`, `filetime_epoch_seconds`, `same_process`) — upstream returns empty on Windows (`discovery.rs:648-920`)
- [present] macOS `command_output`/`procargs_environment`
- [present] `Scope{chats, terminals, reserved_ports}`, owned-only `candidates()`, proxy/reserved exclusion, out-of-tree → chat checkout, failed scan keeps last (`preview/src/service.rs:121-173,339-344`)
- [present] `catalog.rs` 128-char `chat_id` cap
- [present] `harness/src/agent_ports.rs` + test; opencode holds `_port` (`opencode/mod.rs:560,849`); engine wiring (`engine/src/lib.rs:560,94`)
- [present] `WATCH_PREVIEWS` filters `roboco_owned` + chat/checkout (`engine/src/rpc.rs:2349-2358`)
- [present] Ports card (`SessionPreviews`, `PORTS_INITIAL`=3, `PORTS_PAGE`=10, "Ports", ":port" mono, URL / "device · url" tooltip, greyed+error when unavailable, "Show N more", third-of-window scroll cap with rail, hidden when empty) (`ui/src/shell/session_info.rs:58-75,161,436,549-660`); Roboco also routes paired-engine chats
- [present] `BrowserContext::register_preview` (macOS-gated) (`ui/src/browser/mod.rs:82`)
- [present] all discovery unit + integration tests, `actions_mark_what_they_start_with_their_terminal`, preview fixture
- [cloud-skip] `edge/src/preview-room*.ts`

Later upstream refinements: 4443e6b58 (#1028) and bc892abd0 (#950) touch `session_info.rs` — already MISSING in part-07; e16cc3e99 (#1060) cloud.

## a408a8140 Sonner-style toasts, nested Copy menu, picker-style repository menu
Roboco port `64ea2a7e2` (wave-7 ticket 09).

- [present] `toast.rs` — HEAD identical to `a408a8140:crates/ui/src/toast.rs` (stack, hover fan-out, 4s timer pausing on hover/inactive window, swipe-right dismiss, repeat restarts front, `take_error`); mounted per window (`shell.rs:12777`)
- [present] 4/4 tests (`toaster_shows_dedupes_and_closes`, `toasts_expire_pause_while_hovered_or_inactive_and_leave_after_exit`, `glide_restarts_from_its_current_value`, `copying_from_the_copy_submenu_shows_a_toast`)
- [present] nested chat Copy submenu: `CHAT_MENU_ROW_*` (`shell.rs:185-190`), hover intent, `render_chat_copy_trigger/submenu` (`:4660-4820`); items Path, "Roboco conversation link", harness link, "Harness session ID" (`:4860-4876`); toast copy (`:5309-5360`)
- [present] `show_notice` replaces `sidebar_notice`; deep-link Info, "Engine not connected", "Open this side chat's parent to rename it", mutation failure toasts (`shell.rs:2588,5799,5858,6166`)
- [present] call-site migrations: actions_ui, harness_updates, sidebar_pins (`:104`), sidebar_sections ("Could not archive {failed} sessions"), spaces; settings accounts/appearance/archived/devices/harnesses/thread_naming + `dictation/model.rs` (devices keeps engine-local `configuration_error` inline — deliberate)
- [cloud-skip] "Sidebar is still syncing…", "Could not cancel sign-in"/"Sign in failed", synced pin-write strings
- [missing, rides #484] picker-style repository menu in `pull_requests.rs` ("Recent repositories", "Enter a repository as owner/name.", "Finding repository…", "Couldn't detect this project's repository…", offline toasts, `toast_page_error`, 11 toast sites) — Roboco has no `pull_requests.rs`; PR board #484 (`afca583c1`, wave-7 ticket 21 PARKED) already MISSING in part-06. Not a new gap

Later upstream refinements: `cc7da5c2a` (#966) `toast::show_with_action` primitive (only caller cloud "Add credits" — skip; reusable if Roboco wants toast buttons); #861 PORTED (`3e34ed018`); #974 belongs to harness-updates audits.

## 48950003b (#403) Unify popup glass surfaces and redesign New project navigation
Roboco port `cc7fb1ba0` (+ `d9dfb0bc8` palette hunks).

- [present] all 9 added fns (`surface_bg`, `palette_search_icon`, `add_space_devices/locations/goto_location/back_to`, `fixture_project_responses`, fixture main, test `devices_locations_folders_and_back_clear_stale_state`)
- [present] Devices → Locations → Folders ladder + all copy ("Search devices…/locations…/folders…", "No devices found", "No locations found", "No folders here", "No folders match", "Loading locations…", "Device is not connected", Home/Desktop/Documents/Downloads, "Add project"/"Adding…", "Retry")
- [present] Devices-step back crumb returns to cmd-K palette (`spaces.rs:7978`)
- [present] `surface_bg` adopted in change_requests, files/preview, queue, shell, command_palette, spaces
- [present, deliberate] `frost::MENU_BLUR` 44 vs upstream 16 (`frost.rs:26`)
- [present] `new-project-fixture` with `ROBOCO_PALETTE_LIGHT`

Later upstream refinements: #549, #727 (Windows drive paths), `672cc1444`, #998 all PORTED; #856 already MISSING; #484 PARKED; `cc7da5c2a` cloud.

## 9e1a11158 (#760) Render Mermaid diagrams in chat replies
Roboco port `cab3f28bf` (wave-5 ticket 09).

- [present] all 46 added fns: byte-budget LRU `mermaid_cache.rs`, request-while-laying-out, settled-fence-only while streaming, source fallback with diagnostic marker, source toggle, Copy, lightbox (`image_viewer.rs`), `image_media` raster accounting + `preview_within` re-check
- [present] style test renamed `diagrams_take_roboco_style_and_keep_explicit_colors` (`markdown/mermaid.rs:355`); Spanish flowchart fixture
- [present] `docs/markdown-preview.md` sections
- [recorded divergence] web renders mermaid as source

Later upstream refinements: none to mermaid code; #813/#1036 touch image_media for other features (already PARTIAL/MISSING).

## 2c2963aae (#774) Select several entries in the file tree
Roboco port `f08bdd585` (wave-8 ticket 08).

- [present] all 40 added fns + literals (`toggle_selected`, `select_range_to`, `extend_selection`, `clear_selection`, `collapse_selection`, `selection_roots`, `tree_action_paths`, `destination_paths`, `tree_drag_items`, `is_tree_empty_space`, `MutateMany`, `start_file_mutations` queue, multi-delete subject, ghost "N items", `set_root_entries`)
- [present] Windows modifiers: toggle via `modifiers.secondary()` = Ctrl (`files/tree.rs:214,365-368`); Shift+Up/Down extend (`:595-599`); Escape collapses
- [present] `docs/file-tree-interactions.md` "Selecting several entries"

Later upstream refinements: none in the tree files; `files/mod.rs`/`files_panel.rs` only touched by #814, #966 (cloud), #895 (own rows).

## 23a41f99d Consolidate composer and reference test fixtures
Roboco port `685c85faa`. Tests only, no behaviour change.

- [present] invocation.rs `skill(name, path)` helper (`proto/src/invocation.rs:292`); composer_markdown `rendered()` (`:676`)
- [present] composer helpers `with_composer_input` (`composer.rs:11727`), `completion_token` (`:14423`), renamed `workspace_commands_preserve_native_commands_and_avoid_collisions` (`:12301`); cmd/ctrl-enter binding test (`:13825`)
- [present] codex.rs shared `run_to_end` (`:140`), selected-review link fixture (`:1771-1784`), `selected_compact` refusal loop (`:1813-1817`)
- [present] pasted-reference tests deferred by `685c85faa` have since landed (`composer.rs:13336,3505,13537,14464`)

Later upstream refinements: none.

---

## Gaps

New gaps found in this pass (none user-visible except the Windows shortcut issue, which is Roboco-only since upstream has the same code):

1. **a73fa8fe4 — compact/model picker shortcuts use ⌘ on Windows/Linux** (Roboco-only fix, not a port).
   - Effect: on Windows, "star highlighted model" needs Win+Shift+F and row picks need Win+1…9 instead of Ctrl; tooltip "⌘⇧F on the highlighted model" and `⌘{n}` row hints show Mac glyphs.
   - Files: `crates/ui/src/pickers.rs:3296,3362,3372,5020`, `crates/ui/src/pickers/compact.rs:211`; use `modifiers.secondary()` + `settings::display_combo`/`badge_combo_on`.
   - Effort S.
2. **91fd851c7 — codex stop-in-place regression test missing** (`a_stopped_turn_keeps_the_app_server_for_the_next_prompt`).
   - Effect: none for users; there is no check that a Codex Stop keeps the app-server and the next prompt resumes. Fixture arm already exists (`fake-codex.sh:554`).
   - Files: upstream `crates/harness/tests/codex.rs:1797` → Roboco `crates/harness/tests/codex.rs`. Effort S.
3. **987bb2f87 — 5 unix-gated harness-update coordinator tests missing** (hold-back, withdrawn policy, npm own prefix, unprovable owner stays manual, changed-since-check re-check).
   - Effect: none for users; Linux CI would not catch regressions in update ownership or hold-back.
   - Files: `crates/engine/src/harness_updates.rs` test module. The two policy tests need rework onto the built-in/injected feed (no edge `policy_server`). Effort S.
4. **987bb2f87 — proto omission assertion for `compatibility`/`heldBack` missing.**
   - Effect: none (wire back-compat coverage).
   - File: `crates/proto/src/agent.rs:895`. Effort S.
5. **e7ddbbe72 — rpc test `scoped_subscription_returns_before_first_item_and_cancels_silence` + `"Silent"` method on `CancelAwareService` missing.**
   - Effect: none (coverage for silent-stream cancellation of `subscribe_scoped`).
   - File: `crates/rpc/src/lib.rs` tests. Effort S.

Already-tracked gaps re-confirmed here (not double-counted): d1dc29f6c Windows `--noop-browser`
(part-05); b782d0437 (part-05); 731697b66 Devin model-first config pass / prompt-guard backpressure
(part-02/04); dbb639be3 provider tab strip (part-05); daf047b7f, 95b294658, 4443e6b58, bc892abd0,
f65c5bc60 and other #10xx rows (part-07); afca583c1 #484 PR board incl. a408a8140's repository menu
(part-06); 2e151ca65 `owned::configure` launcher env-strip (big-1).

Adjacent, unrecorded as a candidate: Devin Fusion catalog-group selection (`acp/devin_models.rs`
`selection`/`FUSION` + #748's `DevinCatalog` live re-selection + #471's `configured_in_place` hover
card) — excluded as a judgment call in wave-3 ticket 09, not a user decline. Effect: no Fusion
entry in Devin's picker. Effort M.

Coverage note: pass 1 stopped at `5d2b91901`; `5d2b91901..31d26eeb6` includes at least
`b7942ee8b` (#814 browser annotations, adds an "Annotation N" composer chip) and `46d4e42b3`
(#1067 Markdown in user messages), which need rows in a delta pass.

## Confirmed fully ported (portable behaviour)
73bd3c842, 787f58311, 92153025b, efa1a0752, a9e6c48b7, a408a8140 (minus #484-dependent menu),
48950003b, 9e1a11158, 2c2963aae, 23a41f99d. With test-only gaps: 91fd851c7, 987bb2f87, e7ddbbe72.
a73fa8fe4 is ported; its Windows shortcut issue is inherited from upstream.
