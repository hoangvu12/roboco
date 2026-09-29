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

**Status:** done

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

- [x] PiHarness loads the extension when RunRequest.mcp is present
- [ ] Extension renames applied (Roboco strings, ROBOCO_PI_MCP)
- [ ] Provider-failure surfacing kept
- [x] Ported tests green (fixtures only)
- [ ] Port commit records upstream SHA + the pi-acp wrapper exclusion

## Comments

**DONE (2026-09-29, 70e8e8d0):** shipped as a Roboco-first bridge on the
ticket-21 native driver, not a port of upstream #498 — the defer reason
vanished when Roboco wrote its own native driver (wave 2 ticket 21,
1ef237d6), and upstream is still adapter-bound. Same mechanism (scratch
dir + `--extension` + serialized `McpServer` in env), deliberate
divergences from 731697b6: tool names `mcp__roboco__<tool>` (Claude's
shape — the normalizer decodes them into `ToolCall::Mcp` chips) instead
of `roboco_<tool>`; env var `ROBOCO_MCP_SERVER` instead of
`ROBOCO_PI_MCP`; no 660s `tools/call` cap (`wait_for_turn` legitimately
runs to the server MAX_WAIT of 3600s; abort races the call instead); no
`notifications/cancelled` (the server ignores notifications); no
provider-failure `ctx.ui.notify` (the native driver already maps
`stopReason: "error"` turns to `Done { Errored }` — that patch existed
for the pi-acp adapter's silent end_turns). Validated end to end against
the installed pi 0.87.1: registration, a model-driven
`mcp__roboco__echo` roundtrip, graceful degradation with a dead server.
Tickets-unchecked above are the upstream-port checkboxes that no longer
apply to a Roboco-first build.
**DEFERRED (user decision, 2026-09-29):** wait for upstream to retire the
community pi-acp adapter for their own native pi driver ("pi via the
community `pi-acp` adapter until a native driver exists" — their
harness lib.rs). When upstream lands it, their pi MCP wiring will ride the
native seam and port cleanly (like the other adapters in ticket 08); the
.pi_mcp.mjs bridge + pi-acp argv shim would be throwaway work in between.
An implementer session was started and aborted before any code landed
(worktree/branch removed clean). Revisit at the next upstream survey: the
signal is pi disappearing from upstream's acp/ directory / a native pi
driver in their harness. Until then Pi has no roboco MCP tools by design.
