# 12 — Codex: parent-loop hardening, Windows liveness, background-command prune (A37 Codex slice, A45)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** M.

**What to build** (all in `crates/harness/src/codex/mod.rs` unless noted):
1. **Retry notices show as errors.** A `willRetry` "Reconnecting… 2/5"
   notice should only be logged, and the final error held until the turn
   ends (`:1919-1931` emits each as `AgentEvent::Error`, which can even
   prompt sign-in). The hold rule exists only for subagent ChildStream
   (`normalize.rs:946-956`). Use `settle_turn_error` at the parent turn
   end (`:1724`) too. Test:
   `retry_notices_stay_out_of_the_transcript_and_the_final_error_lands_once`.
2. **`turn/status == "interrupted"` ends Interrupted**, not Completed
   (`:1745-1751`).
3. **`item/permissions/requestApproval`** is routed to approval instead
   of answered `-32601` (`:2431-2438`). Test:
   `permission_and_elicitation_requests_get_valid_replies`.
4. **Windows `process_alive`.** It returns `false` off Unix (`:2281`), so
   on Windows every background command counts as dead after an
   interrupt. Use `OpenProcess`/`GetExitCodeProcess`.
5. **A45 / N8c: background-command prune.** After a stop, ask
   `thread/backgroundTerminals/list` and drop `open_commands` entries
   (`:1442`) that Codex no longer runs. Today a stale entry keeps the
   idle reaper from ever retiring the app-server.
6. **Resume classification** (`:2239-2253` matches a bare "not found"):
   an empty or corrupt rollout gives a fresh thread with the existing
   notice; OS/IO wording (`os error`, `permission denied`, `locked`)
   keeps the id.
7. **`crash_report`:** a crash's Done carries the last final error
   (200 ms reap + stderr drain) instead of a bare `try_wait`
   (`:2135-2150`).
8. **`MAX_PENDING_SERVER_REQUESTS = 32`** with an overload refusal.
9. Test parity: `a_stopped_turn_keeps_the_app_server_for_the_next_prompt`
   (fixture arm exists, `fake-codex.sh:554`).

**Upstream:** `2e151ca65` (#977) Codex slice (upstream
`codex/mod.rs:2246-2310,2373-2400` for the prune); `91fd851c7` (#748) test.

**Roboco files:** `crates/harness/src/codex/{mod,normalize}.rs`,
`crates/harness/tests/codex.rs`, `crates/harness/tests/fixtures/fake-codex.sh`.

**Excluded:** nothing. `account/rateLimits/updated` stays on Roboco's
`limit_reset` flow (wave-9 06 5/5).

**Proto change:** no.

**Blocked by:** 01. Ticket 09 also edits `codex/mod.rs`. Land this one
first if both are open.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A37 (Codex), A45; `pass2-big-1.md` gap 2;
`pass2-features-desktop.md` N8(c); `pass2-big-3.md` gap 2.

- [ ] Each item above has a test (fixture modes as needed); `--test codex` and `--lib codex` green
- [ ] On Windows, a still-running background command survives an interrupt (run on the Windows checkout)
- [ ] An idle app-server is retired after its commands end (test)
- [ ] Commit records `2e151ca65` (Codex slice) and `91fd851c7` (test)

## Comments
