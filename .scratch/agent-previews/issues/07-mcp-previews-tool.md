# 07 — MCP previews tool: the agent can see and share its own preview links

**What to build:** A read-only MCP tool (name in-ticket, e.g. `previews`) on `crates/roboco-mcp` that returns the stable preview URLs of the services **the calling chat started**. Scoping comes free: the MCP server already knows its caller (`self.roboco.origin().chat_id` / `origin.device_id` — `crates/roboco-mcp/src/tools.rs:559-578`), and ticket 01's `chatId` attribution lets the engine filter the catalog to exactly that chat. Data path: the engine's preview catalog via the existing `WatchPreviews {chatId}` (first snapshot; a unary variant is fine if cheaper) — return `{name, url, port, startedAt}` rows where `url` is the engine-local form (`http://{hostname}:7331`). The tool is informational: on the engine's own machine the URL opens directly; a remote client's transcript link stays informational while the Ports card / browser pane (tickets 04/05) remains the canonical open path — a clickable transcript preview-link rendering (mention-link style, cf. `roboco-file:` in `crates/proto/src/file_mentions.rs`) is a possible follow-up, not this ticket.

**Why now:** closes the loop between agent and user — "dev server running at http://…" in the agent's reply instead of the user hunting for the Ports card; chat attribution (ticket 01) is the only prerequisite and lands first.

**Blocked by:** 01 — origin stamping and chat attribution.

**Status:** implemented
**Parent:** `.scratch/agent-previews/spec.md`

**Verification budget:** `cargo test -p roboco-mcp` (tool returns the calling chat's services with stable URLs against a fake engine catalog; other chats' services and unattributed services don't leak; absent chat context → empty list, not an error).

- [x] The tool returns only services attributed to the calling chat, with their stable hostnames
- [x] Services started by other chats (same project, same device) do not leak into the result
- [x] No chat context (no origin) returns an empty list, not an error; unknown tool name behaves as before for harnesses
- [x] The tool is read-only — no create/delete surface (the engine mints hostnames on discovery; nobody creates links)

## Comments

**Implementation (2026-10-27, branch `ap/07-mcp-previews-tool`):**

- Tool name: **`previews`** (the ticket's suggested name). It sits next to `whoami` as a no-argument, self-scoped tool: unlike the `list_*` family it takes no filters because its scope *is* the caller — the origin chat injected by the engine. Catalog entry, dispatch arm and the method live in `crates/roboco-mcp/src/tools.rs` (`previews()`); the read is `Roboco::previews(chat_id)` in `crates/roboco-mcp/src/roboco.rs` — the first `WatchPreviews {chatId}` snapshot, then cancel (the crate's standard one-shot watch read; no engine or wiregen change was needed, so no unary variant was added).
- Scoping: origin `chat_id` → `WatchPreviews {chatId}` snapshot → **client-side retain on `service.chat_id == Some(origin)`**. The retain is required, not redundant: the engine's `WatchPreviews` also lists same-checkout services the chat did not start (the Ports card's cwd arm, `crates/engine/src/rpc.rs:2237-2242`), so the tool narrows to strictly-started-by to honor this ticket's contract.
- Rows are `{name, url, port, startedAt}` with `url = service.url(snapshot.proxy_port)` (engine-local `http://{hostname}:7331`; `proxy_port` comes from the snapshot, i.e. `PREVIEW_PROXY_PORT`). The snapshot's proxy-bind `error` rides the result as a top-level `error` field (dead URLs are surfaced, not swallowed). No origin → `{"previews": []}` without dialing the engine. A vanished origin chat surfaces as a tool error (the engine refuses the watch; unchecked watch streams end without a snapshot, so the message is the transport's, not the engine's "Project session not found").
- Read-only: the tool only reads; no create/delete/open/stop tools exist (asserted in tests).
- `initialize` instructions gained one paragraph teaching the tool (share the URL; Ports card lists the same links); `docs/mcp.md` gained the tool-table row and a `previews` paragraph.
- Tests (in-crate, `World` fake extended with a preview catalog and an engine-faithful `WatchPreviews` handler — chat-must-exist, device + `robocoOwned` + attribution-or-checkout filter): `previews_returns_only_the_calling_chats_services` (proves the raw engine snapshot carries 3 services incl. another chat's and an unattributed one, then the tool keeps exactly Alpha's with its stable URL; Beta's side symmetric), `previews_without_chat_context_is_an_empty_list` (empty, no error, engine never dialed — `preview_reads` empty), `previews_is_read_only_and_unknown_names_stay_unknown` (catalog surface, no-args schema, unknown-name errors unchanged at `-32602`, vanished-chat error), `previews_surfaces_the_proxy_error_and_rides_jsonrpc` (bind error + full JSON-RPC `structuredContent` shape), plus instruction assertions in `instructions_teach_notice_driven_orchestration`.

**Evidence (2026-10-27):**

- `cargo test --locked -p roboco-mcp` → **45 passed, 0 failed** (41 before this branch; 4 new tests + instruction assertions).
- `cargo clippy --locked -p roboco-mcp --all-targets` → **no new warnings**: the warning set is byte-identical to the base commit `eb3eba6a3` (6 pre-existing `await_holding_lock` warnings in pre-existing tests, plus pre-existing proto/doc dependency warnings).
- Extra (beyond budget, real-engine wire check): `cargo run -p roboco-engine --example mcp_standalone_smoke` with `tools/call previews` over stdio against the assembled engine → `{"previews": []}`, `isError: false`; `initialize` instructions contain the previews paragraph. Confirms the real engine's `PreviewSnapshot` serialization deserializes through the new read path.
- Commit: see branch tip (`ap/07-mcp-previews-tool`) — `feat(mcp): previews tool returning the calling chat's preview URLs (ticket 07)`.

**Residuals / not in this ticket:**

- Clickable transcript rendering of preview links (mention-link style) — deliberately deferred (follow-up, per ticket).
- A vanished origin chat reports the transport-level error ("WatchPreviews: stream ended before its first snapshot") rather than the engine's "Project session not found": unchecked `roboco_rpc` watch streams end without surfacing the server's error string. Rare edge (chat deleted mid-agent-run); fixing it would mean an engine-side change (unary variant or checked subscribe), out of this ticket's scope.
