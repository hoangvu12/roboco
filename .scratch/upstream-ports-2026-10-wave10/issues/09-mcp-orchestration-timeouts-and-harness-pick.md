# 09 — MCP orchestration: Codex tool timeout, `create_chat` skips signed-out agents, robust MCP server (A2, A30, #1076 slice)

**Lane:** 1c Fixes: agents + orchestration. **Effort:** S–M.

**What to build:**
1. **A2: Codex aborts long Roboco tool calls after 60 s.** The injected
   Roboco MCP server gets no `tool_timeout_sec`
   (`crates/harness/src/codex/mod.rs:117-126`), so Codex gives up on
   `wait_for_turn` (default wait 600 s, `crates/roboco-mcp/src/tools.rs:27`)
   at its own 60 s default. Set a timeout above the longest wait the
   tools accept, with margin.
2. **#1076's other portable pieces** (open PR, MCP robustness slice):
   `notifications/progress` while a long call waits (where the client
   sent a progress token), a cap on reply size in
   `crates/roboco-mcp/src/jsonrpc.rs`, and the MCP command path. Today
   it comes from bare `current_exe()` (`crates/engine/src/sessions.rs:3062`),
   which reads `… (deleted)` after a Linux in-place update. Resolve the
   stable install path.
3. **A30: `create_chat` with no harness can pick a signed-out Claude
   Code**, and the child fails at once. Port the connected-aware
   `default_harness` (installed + enabled + not known signed out, using
   the agent-accounts list), and add `connected`/`signedIn` to
   `list_harnesses` so an orchestrator can see it. Test:
   `connected_means_installed_enabled_and_not_known_signed_out`.

**Upstream:** open PR #1076 (portable slice only); `cc7da5c2a` (#966)
refinement of `01832f2ca` (#706): upstream `crates/mcp/src/zeron.rs`
(`HarnessInfo.signed_in`, `connected()`), `crates/mcp/src/tools.rs`
(`default_harness`).

**Roboco files:** `crates/harness/src/codex/mod.rs`,
`crates/roboco-mcp/src/{tools,roboco,jsonrpc}.rs`,
`crates/engine/src/sessions.rs` (mcp command resolution).

**Excluded:** #1076's Cloud wake/heartbeat/edge parts. Bad-input-line
skipping already exists (`jsonrpc.rs:115-125`).

**Proto change:** no. The `list_harnesses` MCP output gains fields
(MCP schema, not wire); update `docs/mcp.md`.

**Blocked by:** 01. Ticket 12 also edits `codex/mod.rs`.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A2, A30; `part-prs-A.md` #1076;
`pass2-big-2.md` gap 1.

- [ ] A Codex chat's `wait_for_turn` of 5 minutes completes (fake-codex config assertion on `tool_timeout_sec`)
- [ ] Progress notifications sent when a token is present; oversize replies capped with a clear marker (jsonrpc tests)
- [ ] MCP command path survives a replaced binary on Linux (unit test on the resolver)
- [ ] `create_chat` without a harness never picks a signed-out one; `list_harnesses` shows `connected`/`signedIn` (ported test)
- [ ] Commit records PR #1076 (slice) and `cc7da5c2a`'s MCP refinement

## Comments
