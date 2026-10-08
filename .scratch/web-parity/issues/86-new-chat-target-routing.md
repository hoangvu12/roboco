# 86 — New-chat target module + canvas routing

**What to build:** Port zeron PR #526 `lib/new-chat-target.ts` (`b20e5bfd`, `4928e1b2`) and route blank canvas through `resolveNewChatTarget().engineKey` in `session-provider.tsx` (not duplicated precedence in chips).

**Blocked by:** None.

**Status:** ready-for-human

**Zeron ref:** `web/packages/app/src/lib/new-chat-target.ts` @ `4928e1b2`

**Roboco files:** `web/packages/app/src/lib/new-chat-target.ts`, `state/session-provider.tsx` (`routedEngineKey`), `tests/new-chat-target.test.ts`, `tests/session-provider.test.ts`

**Acceptance:**

- [x] Unit tests pass — `tests/new-chat-target.test.ts` (5/5) and `tests/session-provider.test.ts` (11/11) green at `301bef26`.
- [x] Canvas `/` session follows scoped project owner then device pick then `fleet.active` — `routedEngineKey("/", …)` resolves `resolveNewChatTarget().engineKey` with exactly that precedence, and the mounted provider swaps its live session when the composer chips remember engine B.
- [x] Chat routes unchanged — `routedEngineKey("/chat/…")` stays the chat's owning engine regardless of chip/active state.

## Comments

### Implementation (2026-10-08, ticket 86's own landing)

`lib/new-chat-target.ts` (`resolveNewChatTarget`,
`targetForDevicePick`, `targetForProjectPick`) and
`state/session-provider.tsx`'s `routedEngineKey` (blank-canvas routing
through the resolved engine key, per-engine sessions otherwise) landed
with ticket 86's branch (`9101f8c5` era), with
`tests/new-chat-target.test.ts` and the `new-thread target routing`
suite in `tests/session-provider.test.ts`.

### Completion record (2026-10-08, via ticket 88's wiring — status → ready-for-human)

Ticket 86's remaining deliverable was wiring the composer surfaces onto
the routing, explicitly deferred to ticket 88. That wiring landed in
`301bef26` (branch `webparity-88-composer-engine-chips`, base
`1244fa69`): `DeviceCard.pick` / `ProjectCard.pickSpace` /
`pickNoProject` write `targetForDevicePick` / `targetForProjectPick`
results into `composerDefaults` (projectless engine switches also clear
the visible sidebar filter via `rememberNoProject`), and the canvas
selector row passes its resolved effective device through
`ProjectChip`'s new `currentDeviceId`. The canvas therefore WRITES the
remembered target; `session-provider` routes `/` from it — the chips
never duplicate the precedence.

Verified at `301bef26`: `tests/new-chat-target.test.ts` 5/5,
`tests/session-provider.test.ts` 11/11 (including "routes chip-picked
engine B's device and space before the active/sidebar engine A" and
"updates the mounted session as the composer chip remembers engine B"),
plus ticket 88's `tests/browser-engine-picker.test.ts` 10/10 driving the
canvas chips end-to-end. All three acceptance clauses hold; the
plan-level two-live-engine run stays with the orchestrator's
integration pass.
