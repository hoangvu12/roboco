# 07 — Cursor harness: load user MCP servers and plugins

**What to build:** The Cursor agent SDK loads no ambient settings unless
`local.settingSources` is set, so the agent only saw the injected server —
no `~/.cursor/mcp.json`, no plugin servers. Pass `settingSources` covering
**user, team, mdm and plugins only** — NOT "project": the SDK hardcodes
`ignoreApprovals` when loading MCP servers, so the project source would
spawn a repo's `.cursor/mcp.json` servers and `.cursor/hooks.json`
commands at chat start without the approval step Cursor itself requires.
The fake SDK records `settingSources` beside `mcpServers` (not inside the
server map) so tests can assert it.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `a7e505e6` (#616) — 4 files, 34 insertions:
`crates/harness/src/cursor/{mod,shim.mjs,...}` + fake SDK fixture. Our
`crates/harness/src/cursor/` matches (`catalog.rs`, `mod.rs`, `shim.mjs`,
`state.rs`); our fake SDK is `crates/harness/tests/fixtures/fake-cursor-sdk.mjs`
with `tests/cursor.rs` — keep them in sync.

**Verification budget:** `cargo check -p roboco-harness -j 3`; targeted
nextest cursor suites; run the cursor path in the fake SDK fixture to
assert `settingSources`.

- [ ] `~/.cursor/mcp.json` and plugin servers reach the agent
- [ ] Project source excluded (no unapproved repo servers/commands)
- [ ] Fake SDK asserts the setting source list
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments
