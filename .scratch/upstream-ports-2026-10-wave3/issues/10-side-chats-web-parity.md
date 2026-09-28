# 10 — Side chats web parity

**What to build:** Mirror side chats on web: a chat-typed surface kind in
the right-pane store (the pane host, tab strip, drag, glides, and per-chat
pane state are reusable — subagent surfaces are the ancestor), explorer
Subagents/Chats sections in the docked files column, and the fork seam row.
Scope: opening a side chat tab from the explorer, sending through the
composer surface, and fork display. Tab close/restore (ticket 11) and
first-send persistence (ticket 14) extend this.

**Blocked by:** 09.

**Status:** ready-for-agent

**Upstream SHAs:** none (roboco-native parity work; desktop references are
`731697b6`'s ui + wave-2 web patterns). If new watch/RPC surfaces are needed
beyond 07's regen'd types, add them to
`web/packages/engine-client/src/methods.ts` + wiregen.

**Verification budget:** `pnpm --filter @roboco/app exec tsc --noEmit`;
touched vitest suites (right-pane, files, new side-chat suite);
`wiregen --check`.

- [ ] Chat surface kind in right-pane store + tab strip
- [ ] Explorer sections on web
- [ ] Fork seam row
- [ ] Vitest suites green; typecheck green
