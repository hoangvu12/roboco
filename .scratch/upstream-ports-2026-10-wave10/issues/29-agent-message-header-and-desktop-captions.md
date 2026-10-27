# 29 — One agent-message header in proto; desktop captions over the bubble (#1052, #1062's proto module, A15 desktop)

**Lane:** 2 Messages. **Effort:** M.

**What to build:**
1. **A shared header writer and parser in `crates/proto`.** Move
   upstream's `crates/proto/src/agent_messages.rs` (from #1062) forward
   into this ticket, so every surface parses the routing header the same
   way. It must accept **Roboco's orchestration-v2 header variants**, not
   only upstream's: "[Message from Roboco chat …]", the side-chat
   variant "You are a side chat created by that chat…", and the reply
   instructions. The current desktop tests
   (`transcript.rs:17389-17405`) are the spec. `crates/roboco-mcp/src/tools.rs`
   writes headers through it (the #1062 `mcp/tools.rs` hunk). Upstream
   uses this writer in place of the full-id reply text from #819. Take
   the writer, not #819.
2. **Desktop captions (#1052).** An agent-sent message shows a caption
   *over* the bubble: `Message from [harness mark] Chat`. Clicking it
   opens the sender chat. Today "Message from **X**" is bold inside the
   bubble (`transcript.rs:1350` `agent_message_display`, `:8776`, `:8849`).
   The provider-switch seam becomes a right-aligned
   `[logo] From → [logo] To` caption over the message that switched,
   replacing the divider (`harness_switch_marker`, `:9310`). Port the
   `message-captions-fixture` example. Don't copy its screenshots.
3. **The seam lands with its message.** The doc_host queued path
   (`crates/engine/src/doc_host.rs:2816-2828`) writes the message without
   calling `note_provider_switch` first. That's upstream's one-line
   engine change (+ `sessions.rs` +8).
4. **A15 desktop leftovers:** the queue row while the target chat is busy
   (`queue.rs:246` `queue_row_text`) and the rail's prompt list
   (`rail.rs:86` `user_text`) still show the raw header. Run them
   through the parser.

**Upstream:** `325384d8b` (#1052); `crates/proto/src/agent_messages.rs`
+ `crates/mcp/src/tools.rs` hunk from `f65c5bc60` (#1062).

**Roboco files:** new `crates/proto/src/agent_messages.rs`,
`crates/proto/src/lib.rs`, `crates/ui/src/{transcript,queue,rail}.rs`,
`crates/ui/src/shell.rs`, `crates/engine/src/{doc_host,sessions}.rs`,
`crates/roboco-mcp/src/tools.rs`, `crates/ui/examples/message-captions-fixture.rs`,
`crates/ui/Cargo.toml` (example entry).

**Excluded:** screenshots (binary); `crates/client/src/demo/*` (cloud
demo); web (removal).

**Proto change:** no wire type. `agent_messages.rs` is a helper module
in `crates/proto`.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A15, §B captions, late-window note;
`part-07.md` `325384d8b`, `f65c5bc60`.

- [ ] Parser round-trip tests cover upstream's header and every Roboco orchestration-v2 variant; malformed headers fall back to plain text
- [ ] Desktop shows the caption over the bubble; a click opens the sender (UI test)
- [ ] The provider-switch caption sits over the switching message, including a queued send (engine + UI test)
- [ ] The queue row and the rail list show no raw header
- [ ] `roboco-mcp` writes headers through the shared writer (tool test)
- [ ] Commit records `325384d8b` and the `f65c5bc60` proto/mcp slice

## Comments
