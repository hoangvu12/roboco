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

## Comments

**Branch:** `wave3/06-archive-faster` → merged `Merge wave3/06`. Commits `3027da3e` (desktop: chat_hover_resync + sync_chat_row_hover + canvas hit-test deferral, +60 shell/+122 spaces) + `1d74293c` (web: lib/still-pointer.ts rAF re-test, chat-list + archived-section register, new chat-list-archive suite). Post-merge re-verified: 37/37 + web 52/52 targeted.

**Judgment calls:** archived-section.tsx also registers (desktop shares the row renderer); jsdom rAF stubbed as deferred macrotask in tests; web hover already existed (wave-2) — the resync was the gap.
