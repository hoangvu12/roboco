# 02 — Codex: approve our MCP tools (upstream #985, `d97756075`)

**What to build:** Port `d97756075` (7 lines): the injected MCP server
config gains `default_tools_approval_mode: "approve"`, so approval policy
`never` no longer rejects calls that need approval.

**Files:** `crates/harness/src/codex/mod.rs` (config + `mcp_injection_tests`).

**Port note:** rebrand `mcp_servers.zeron` → `mcp_servers.roboco`,
`ZERON_CHAT_ID` → `ROBOCO_CHAT_ID` in the test hunk.

**Excluded upstream hunks:** none.

**Blocked by:** none.

**Status:** ready-for-human

**Verification budget:** `cargo check -p roboco-harness` + codex tests.
Source: `.scratch/upstream-drift/2026-10-25.md` § A2.

- [x] Approval mode + test ported
- [x] Commit records upstream SHA `d97756075` (#985)

## Comments

- **2026-10-25 — ported on `wave9/engine`** (commit follows ticket 01,
  2f51b449a). Straight 7-line carry with the rebrand applied
  (`mcp_servers.roboco.*` / `ROBOCO_CHAT_ID` in the test hunk).
- **Verification:** `cargo test -p roboco-harness --lib mcp` — 10 passed,
  including the updated
  `codex::mcp_injection_tests::codex_mcp_overrides_use_the_dotted_mcp_servers_keys`.
  `cargo check -p roboco-harness` was exercised implicitly by the test
  build.
