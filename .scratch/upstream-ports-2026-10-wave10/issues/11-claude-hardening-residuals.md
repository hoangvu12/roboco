# 11 — Claude: the #977 hardening wave-9 06 missed (A26 + A37 Claude slice)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M (five S pieces + tests).

Wave-9 06 reported #977 fully landed. Pass 2 found these Claude-adapter
slices absent (`pass2-big-1.md`, verified by hand).

**What to build:**
1. **A26: launch through `acp::child::configure`.** Claude run and probe
   launches (`crates/harness/src/claude/mod.rs:468-488` `build_command`,
   `:512-541` `probe_initialize`) call only `dies_with_parent`. Use
   `configure` like ACP and Pi do. It strips the 12 inherited Claude Code
   session markers (`CLAUDECODE`, `CLAUDE_CODE_SESSION_ID`,
   `…_MESSAGING_SOCKET`, `CLAUDE_EFFORT`, …), gives a Unix process group,
   and provides `shutdown_with`. Check Codex's launcher for the same env
   leak (A26 names both). Test:
   `processes_the_cli_started_end_with_the_run`.
2. **A crash in a later turn hangs Working.** `claude/mod.rs:1950` checks
   `!any_done` with a bare `try_wait`. Port `turn_open`, the 200 ms exit
   wait and `stderr_tail.wait_closed()`. Test:
   `a_crash_in_a_later_turn_ends_it_with_the_reason`.
3. **Unknown control requests get no reply.** `:2084-2090` only logs, so
   the CLI waits forever. Reply with `control_error_line`
   (`claude/wire.rs:683`, currently unused): "unsupported control request:
   …". Test: `unknown_control_requests_are_answered`.
4. **`managed-mcp.json`.** While an administrator's `managed-mcp.json`
   exists (Windows `C:\Program Files\ClaudeCode\managed-mcp.json`, the
   Linux/macOS system paths upstream checks), Claude refuses
   `--strict-mcp-config` and exits. Skip the flag when the file exists,
   at every site that passes it (title runs at `:942-953`, and check the
   main run args).
5. **`spawn_error` everywhere.** A deleted project folder reports "not
   installed" for Claude (`:543,1225`), OpenCode (`opencode/mod.rs:795`)
   and Pi (`pi/mod.rs:486,661`). Use `executable::spawn_error` like
   ACP/Codex/Cursor. Test: `missing_working_directory_is_not_a_missing_binary`.
6. **Test parity** (no behaviour change):
   `an_unreadable_result_still_ends_the_turn`,
   `a_refused_model_switch_is_shown_and_retried`,
   `a_failed_discovery_probe_reports_exit_and_stderr`,
   `a_session_without_conversation_is_not_resumed`, the
   `claude_refresh_lock` run test, and jsonrpc's
   `closing_stdin_writes_what_was_queued_then_sends_eof` plus the
   `transport_lost` table test.

**Upstream:** `2e151ca65` (#977) Claude slice (upstream
`claude/mod.rs:130-142,371` managed-mcp; the rest per `pass2-big-1.md`
§Claude).

**Roboco files:** `crates/harness/src/claude/{mod,wire}.rs`,
`crates/harness/src/acp/child.rs` (reuse), `codex/mod.rs` (launcher env),
`opencode/mod.rs`, `pi/mod.rs`, `crates/harness/tests/{claude,claude_refresh_lock}.rs`,
`crates/harness/src/jsonrpc.rs` tests, `fixtures/fake-claude.sh`.

**Excluded:** `config_root(&request.env)` (per-run `CLAUDE_CONFIG_DIR`
is cloud-only). `native_beside_npm_shim` is ticket 08.

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A26, A37 (Claude); `pass2-big-1.md` gaps 3, 3a–3c, 7, 8;
`pass2-features-desktop.md` N8(a).

- [ ] Engine started inside a Claude Code session: the agent's Claude sees none of the markers; CLI grandchildren end with the run (Unix test)
- [ ] Later-turn crash ends Errored with the stderr reason (ported test)
- [ ] Unknown control request answered (ported test)
- [ ] With a `managed-mcp.json` present, title and main runs start (test with a temp policy path)
- [ ] Missing workdir reports a workdir error for Claude/OpenCode/Pi (tests)
- [ ] Listed parity tests ported and green: `--test claude`, `--test claude_refresh_lock`, `--lib`
- [ ] Commit records `2e151ca65` (Claude slice) and corrects wave-9 06's "all landed"

## Comments
