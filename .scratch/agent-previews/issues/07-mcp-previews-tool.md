# 07 — MCP previews tool: the agent can see and share its own preview links

**What to build:** A read-only MCP tool (name in-ticket, e.g. `previews`) on `crates/roboco-mcp` that returns the stable preview URLs of the services **the calling chat started**. Scoping comes free: the MCP server already knows its caller (`self.roboco.origin().chat_id` / `origin.device_id` — `crates/roboco-mcp/src/tools.rs:559-578`), and ticket 01's `chatId` attribution lets the engine filter the catalog to exactly that chat. Data path: the engine's preview catalog via the existing `WatchPreviews {chatId}` (first snapshot; a unary variant is fine if cheaper) — return `{name, url, port, startedAt}` rows where `url` is the engine-local form (`http://{hostname}:7331`). The tool is informational: on the engine's own machine the URL opens directly; a remote client's transcript link stays informational while the Ports card / browser pane (tickets 04/05) remains the canonical open path — a clickable transcript preview-link rendering (mention-link style, cf. `roboco-file:` in `crates/proto/src/file_mentions.rs`) is a possible follow-up, not this ticket.

**Why now:** closes the loop between agent and user — "dev server running at http://…" in the agent's reply instead of the user hunting for the Ports card; chat attribution (ticket 01) is the only prerequisite and lands first.

**Blocked by:** 01 — origin stamping and chat attribution.

**Status:** ready-for-agent
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-mcp` (tool returns the calling chat's services with stable URLs against a fake engine catalog; other chats' services and unattributed services don't leak; absent chat context → empty list, not an error).

- [ ] The tool returns only services attributed to the calling chat, with their stable hostnames
- [ ] Services started by other chats (same project, same device) do not leak into the result
- [ ] No chat context (no origin) returns an empty list, not an error; unknown tool name behaves as before for harnesses
- [ ] The tool is read-only — no create/delete surface (the engine mints hostnames on discovery; nobody creates links)

## Comments
