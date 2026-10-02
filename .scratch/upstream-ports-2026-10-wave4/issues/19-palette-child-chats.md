# 19 — Command palette: exclude child chats

**What to build:** Child chats (agent-spawned side chats) no longer appear
as separate palette results — only their parents do.

**Blocked by:** None. (Side chats + child-chat concept shipped in wave 3.)

**Status:** ready-for-agent

**Upstream SHAs:** `c74978ab` (#651) — 1 file,
`crates/ui/src/shell/command_palette.rs`. **Web parity (deliverable):**
`web/packages/app/src/lib/command-palette.ts` builds the chat rows —
filter out chats with a `parentChatId` (child chats) the same way; the
proto field already exists on web.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
command_palette tests; web `pnpm -r build`.

- [ ] Child chats excluded from palette results (desktop + web)
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments
