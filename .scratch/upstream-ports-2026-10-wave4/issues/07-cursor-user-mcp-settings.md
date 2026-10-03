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

- [x] `~/.cursor/mcp.json` and plugin servers reach the agent
- [x] Project source excluded (no unapproved repo servers/commands)
- [x] Fake SDK asserts the setting source list
- [ ] Tests green
- [x] Port commit records upstream SHA

## Comments

**Port mapping (upstream a7e505e6, #616):** all four upstream files map
1:1 onto ours — `crates/harness/src/cursor/shim.mjs` (the `local`
object gains `settingSources: ["user", "team", "mdm", "plugins"]` with
upstream's approval-gating rationale comment; rebranded "zeron server" →
"roboco server" in the comment),
`crates/harness/tests/fixtures/fake-cursor-sdk.mjs` (records
`{mcpServers, settingSources: local.settingSources}` beside the server
map, not inside it — create and resume),
`crates/harness/tests/cursor_shim.rs` (assertions re-pathed under
`options["mcpServers"]["roboco"]` plus the settingSources list assertion
with the never-"project" comment), and `docs/mcp.md` (Cursor table row,
`mcpServers.roboco` spelling kept).

**Exclusions:** none — upstream's #616 is exactly these four files; no
CI/edge/iOS hunks existed.

Verification: `rustfmt --edition 2024 crates/harness/tests/cursor_shim.rs`
(clean — no drift); reading-only verification otherwise per the wave's
build economy; test execution deferred to the wave-final batched pass
(user directive).
