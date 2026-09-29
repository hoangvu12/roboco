# 21 — Pi: roboco MCP tools via pi's own `--extension` (follow-up to 08)

**What to build:** Give Pi the roboco MCP tools (side chats, transcript
reads, send_message) that claude/codex/ACP/cursor/opencode already got in
ticket 08. Port upstream #498's pi bridge onto Roboco's NATIVE PiHarness:
when a run carries `RunRequest.mcp`, write the extension script to a
scratch dir, spawn pi with `--extension <path>`, and pass the serialized
`McpServer` config via env (`ROBOCO_PI_MCP`). The extension spawns the
stamped `roboco mcp` child (command/args/env), speaks stdio JSON-RPC
(initialize → tools/list), and registers every tool into pi via
`pi.registerTool` (name `roboco_<tool>`, label `Roboco: <tool>`). Keep the
extension's provider-failure surfacing (`message_end` + stopReason error →
`ctx.ui.notify`) — it fixes pi's silent end_turns on provider failures.
Title runs (mcp: None) load nothing.

**Blocked by:** None (08 is merged; v0.5.0 is out).

**Status:** ready-for-agent

**Upstream SHAs:** `731697b6` — `crates/harness/src/acp/pi_mcp.mjs` (the
extension; port with Zeron→Roboco renames, clientInfo "roboco-pi", env
`ZERON_PI_MCP`→`ROBOCO_PI_MCP`), `crates/harness/src/acp/pi_mcp.rs`
(reference for configure() intent + scratch handling; the pi-acp argv
wrapper is NOT ported — Roboco's native driver controls pi's argv directly
at pi/mod.rs:261-264), `crates/harness/tests/pi_mcp.rs` + fixtures
`pi-mcp-test.mjs` (adapt to Roboco's native-driver test rig). Upstream
still ships pi via the community pi-acp adapter ("until a native driver
exists") — their wiring is adapter-bound; only the extension mechanism is
pi-CLI-native and that is all we port. Roboco's wire already handles pi
extension events (`extension_ui_request`/`extension_error`,
extension-initiated runs) — this is the first extension we load.

**Verification budget:** `cargo check -p roboco-harness -j 3`; targeted
nextest: the ported pi-mcp tests + existing pi suites (fixtures only, no
live probes); `wiregen --check` untouched-fresh (no wire changes).

- [ ] PiHarness loads the extension when RunRequest.mcp is present
- [ ] Extension renames applied (Roboco strings, ROBOCO_PI_MCP)
- [ ] Provider-failure surfacing kept
- [ ] Ported tests green (fixtures only)
- [ ] Port commit records upstream SHA + the pi-acp wrapper exclusion
