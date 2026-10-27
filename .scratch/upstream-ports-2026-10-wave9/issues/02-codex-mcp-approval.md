# 02 — Codex: approve our MCP tools (upstream #985, `d97756075`)

**What to build:** Port `d97756075` (7 lines): the injected MCP server
config gains `default_tools_approval_mode: "approve"`, so approval policy
`never` no longer rejects calls that need approval.

**Files:** `crates/harness/src/codex/mod.rs` (config + `mcp_injection_tests`).

**Port note:** rebrand `mcp_servers.zeron` → `mcp_servers.roboco`,
`ZERON_CHAT_ID` → `ROBOCO_CHAT_ID` in the test hunk.

**Excluded upstream hunks:** none.

**Blocked by:** none.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-harness` + codex tests.
Source: `.scratch/upstream-drift/2026-10-25.md` § A2.

- [ ] Approval mode + test ported
- [ ] Commit records upstream SHA `d97756075` (#985)

## Comments
