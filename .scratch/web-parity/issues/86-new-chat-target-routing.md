# 86 — New-chat target module + canvas routing

**What to build:** Port zeron PR #526 `lib/new-chat-target.ts` (`b20e5bfd`, `4928e1b2`) and route blank canvas through `resolveNewChatTarget().engineKey` in `session-provider.tsx` (not duplicated precedence in chips).

**Blocked by:** None.

**Status:** in-progress (module + routing landed; wire `new-thread-selectors` + composer-footer in **88**)

**Zeron ref:** `web/packages/app/src/lib/new-chat-target.ts` @ `4928e1b2`

**Roboco files:** `web/packages/app/src/lib/new-chat-target.ts`, `state/session-provider.tsx` (`routedEngineKey`), `tests/new-chat-target.test.ts`, `tests/session-provider.test.ts`

**Acceptance:** Unit tests pass; canvas `/` session follows scoped project owner then device pick then `fleet.active`; chat routes unchanged.
