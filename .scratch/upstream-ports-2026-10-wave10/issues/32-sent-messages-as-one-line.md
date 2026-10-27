# 32 — An agent's `send_message` reads as one line: "Message to [mark] Chat" (#1068)

**Lane:** 2 Messages. **Effort:** M.

**What to build:** When an agent sends a message to another chat
(`send_message`, `send_messages`, `create_chat` with a prompt, and
`fork_chat` with a prompt, since Roboco has more send tools than
upstream), the sender's transcript shows one line, `Message to [mark]
Chat`, instead of a tool row. A click or tap opens the recipient. On
failure it reads "Couldn't message Chat". Desktop gets
`SentMessage`/`render_chat_caption`. Mobile gets the outgoing caption row
in `crates/mobile/src/layout/rows.rs`, with Android tap routing like 31.
Extend `proto::agent_messages` with upstream's recipient parsing
(+110).

**Upstream:** `d8a14b35b` (#1068): `crates/proto/src/agent_messages.rs`,
`crates/ui/src/transcript.rs` (+198),
`crates/mobile/src/layout/{rows,tests}.rs`,
`crates/ui/examples/message-captions-fixture.rs`.

**Roboco files:** the same, plus Android transcript routing
(`apps/android/.../transcript/*`) and `crates/roboco-mcp` tool names
for Roboco-only send tools.

**Excluded:** screenshots; `crates/client/src/demo/*`; iOS.

**Proto change:** no wire type.

**Blocked by:** 30 (desktop transcript), 31 (mobile rows, Android
routing).

**Status:** ready-for-agent

**Source:** `2026-10-28.md` §B captions; `part-07.md` `d8a14b35b`;
`pass3-ios-1.md` #1068.

- [ ] Each Roboco send tool renders as one line on desktop and Android (transcript + layout tests)
- [ ] Clicking or tapping opens the recipient; a failed send reads "Couldn't message …"
- [ ] Batch `send_messages` shows one line per recipient
- [ ] Commit records `d8a14b35b` and the Roboco-only tool coverage

## Comments
