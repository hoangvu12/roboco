# 06 — Archive chats faster

**What to build:** Faster archive path from the sidebar Spaces view: the
row's hover affordance swaps straight to an Archive action instead of the
slow route through the chat menu.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `f1ea80d7` — `crates/ui/src/shell.rs`, `crates/ui/src/shell/spaces.rs`
(184 added). Read the upstream diff and carry its mechanics (hover
status→Archive pill swap semantics). Web parity: `web/packages/app/src/components/chat-list.tsx`
rows gain the hover archive corner (`setChatArchived` already exists in
`lib/chat-actions.ts`).

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
nextest `test(spaces) or test(chat_status_hover) or test(archive)`; web
chat-list vitest.

- [ ] Fast archive affordance (desktop + web)
- [ ] Tests green
- [ ] Port commit records upstream SHA
