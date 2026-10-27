# 55 — Providers: a switch to turn native subagents off (delegate through Roboco side chats) (#1046) — PROTO

**Lane:** 4a Features: engine + agents. **Effort:** M.

**What to build:** A per-device switch (Settings → Providers → Claude
Code, and Codex where it applies). When it's off, top-level chats launch
the agent without its native subagent tools (Claude:
`--disallowedTools=Agent,Task,Workflow`; Codex: `agent_tools_off`) and
delegate through Roboco side chats instead. That fits Roboco's
orchestration-v2 direction. Port:
- `settings/delegation.rs` (+297) UI;
- engine-local persistence (registry/workspace_host);
- `GetNativeSubagents` / `SetNativeSubagents`;
- `McpServer::SIDE_CHATS_ONLY_ENV` → `ROBOCO_SIDE_CHATS_ONLY`;
- the MCP tool-description tweak;
- `switches_native_subagents`;
- parked-session replacement (a toggle applies to the next turn, by
  replacing parked sessions);
- tests in `restart_resume.rs` and `side_chats.rs`.

**Upstream:** `b8ed9d9c2` (#1046).

**Roboco files:** `crates/ui/src/settings/delegation.rs` (new),
`crates/engine/src/{rpc,sessions,workspace_host}.rs`,
`crates/rpc/src/lib.rs`, `crates/roboco-mcp/src/tools.rs`,
`crates/harness/src/{claude,codex}/mod.rs`,
`crates/engine/tests/{restart_resume,side_chats}.rs`.

**Excluded:** cloud registry sync. The setting is engine-local (ADR 0004).

**Proto change:** yes (`Get/SetNativeSubagents`).

**Blocked by:** 16, 47 (`sessions.rs`, Claude launch args). **Blocks:**
61 (settings search anchors).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B #1046; `part-07.md` `b8ed9d9c2`.

- [ ] Off: a new Claude chat has no Agent/Task/Workflow tools; the MCP description steers toward side chats (fixture assertions)
- [ ] Toggling replaces parked sessions on the next turn (restart_resume test)
- [ ] Setting persists per device; the env var is renamed
- [ ] Commit records `b8ed9d9c2`

## Comments
