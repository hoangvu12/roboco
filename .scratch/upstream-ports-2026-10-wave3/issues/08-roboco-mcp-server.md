# 08 — `roboco mcp`: MCP server crate, subcommand, run injection

**What to build:** Port the zeron MCP server as `roboco-mcp`: a stdio
JSON-RPC MCP server (`initialize`/`ping`/`tools/list`/`tools/call`) that
proxies its tools into the engine's loopback IPC WebSocket (the same one
the headed app dials; env `ROBOCO_IPC_PORT` default 27654). The host engine
stamps its own MCP server onto every run it drives (`RunRequest.mcp`): the
`roboco mcp` subcommand of this same binary (`current_exe()`), with
`ROBOCO_CHAT_ID`/`ROBOCO_DEVICE_ID` identifying the originating chat. Claude
gets it via `--mcp-config` (merging with our existing `--strict-mcp-config`
args), ACP agents via `session/new mcpServers`, Codex via
`mcp_servers.roboco.*` overrides; title runs never carry it. A chat can ask
its agent to spawn side chats, attributed to it.

**Blocked by:** 07 (side-chat engine machinery + proto field).

**Status:** ready-for-agent

**Upstream SHAs:** `92153025` (crates/mcp + `mcp` subcommand in the app
binary — read `git show 92153025` for the original crate/main.rs wiring),
`731697b6` (engine stamping + harness injection: claude/claude-normalize/
claude-wire, acp/mod + acp/normalize, codex, cursor/shim, opencode,
jsonrpc.rs; `docs/mcp.md`), `e7ddbbe7` (sync-connection stability under
load — port only the mcp-crate hunks, skip the cloud sync-room parts).
Renames: `zeron-mcp`→`roboco-mcp`, `zeron_mcp`→`roboco_mcp`, `mcp`→`mcp`
subcommand name stays, `ZERON_IPC_PORT`/`ZERON_CHAT_ID`/`ZERON_DEVICE_ID`→
`ROBOCO_*`, `Zeron` struct→`Roboco`, `zeron.rs`→`roboco.rs`, codex key
`mcp_servers.zeron.*`→`mcp_servers.roboco.*`, `docs/mcp.md` adapted
(engine-local phrasing, roboco naming). Upstream's `acp/pi_mcp.rs/.mjs`
serve the community pi-acp bridge Roboco retired (ticket wave-2/21):
evaluate against Roboco's native PiHarness — port only if our driver has an
equivalent seam, else skip and record it.
Adaptation: roboco's loopback dial client — reuse the same
tokio-tungstenite dialing the app/engine-client use; `zeron sync` does not
exist here.

**Verification budget:** `cargo check -p roboco-mcp -p roboco-harness -p
roboco-engine -j 3`; targeted nextest: harness mcp-config tests (fixtures
`fake-claude.sh`/`fake-codex.sh` assert injection), engine side-chat
attribution tests from #498's `tests/side_chats.rs`; `wiregen --check` +
`pnpm -r build`.

- [ ] `roboco-mcp` crate (jsonrpc/tools/transcript/roboco) + `roboco mcp` subcommand (stderr logging)
- [ ] Engine stamps RunRequest.mcp on driven runs; title runs excluded
- [ ] Claude/ACP/Codex injection (+cursor/opencode where upstream has seams)
- [ ] Attribution: sends from injected agents land on the originating chat
- [ ] docs/mcp.md adapted
- [ ] Tests green; wiregen fresh
- [ ] Port commit records upstream SHAs + pi decision + exclusions
