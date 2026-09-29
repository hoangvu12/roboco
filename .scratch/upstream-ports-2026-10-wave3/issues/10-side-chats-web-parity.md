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

## Comments

**Branch:** `wave3/10-side-chats-web` → merged into main `Merge wave3/10`. Commit `938baed4` (20 files, +2869/−14): RightSurface sidechat kind + addSideChatSurface (dedupe/reattach), SideChatSurface (own TranscriptStore+Composer, jump pill, file surfaces keyed to side chat, context-ring-only footer), draft mirror via state/side-chats.ts (survives unmount — better than desktop's per-pane mount), explorer-sections (pure lib + component, all desktop consts/copy/paging, fingerprint-gated), forkMarker rows + 'Forked from' divider, methods.FORK_SIDE_CHAT + forkSideChat/createChildChat + side_chat_creating guard, wireParams decodes sourceChatId (web scopes ids uniformly). Post-merge re-verified: full web 136 files/2050, ui 1303/1303, wiregen fresh.

**Judgment calls:** draft push-mirror vs hidden mounts; fork errors surface in sidebarNotice (web divergence, noted in comments); session-header New-side-chat/Fork have no web host (chrome contract identity+onNewSession only) — deferred; tab context menus have no web host (Chats rows' right-click covers row-level).

**Notes for later tickets:** ticket 14 must mirror first-send deferral if ported (web mints up front today); SubagentSurface frozen-blob scoped-id bug (pre-existing, one-line fix candidate — decode before FETCH_TOOL_BLOB) recorded for triage.
