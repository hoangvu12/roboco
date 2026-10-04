# 90 — Block send when canvas target unresolved

**What to build:** Port zeron `f180fcb1` / `d57b27fd`: `targetUnavailable` from resolved target + fleet snapshot; composer/chat-page refuse send when selected project not loaded on owner engine.

**Blocked by:** **86**, **88**

**Status:** ready-for-agent

**Zeron ref:** `composer.tsx`, `new-thread-selectors.tsx`, `tests/new-chat-selection.test.ts`

**Acceptance:** Cannot send on canvas with cross-engine project row missing from owner cache.
