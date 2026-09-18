# 32 — Pairing remount: the first navigation after pairing remounts the app

**What to build:** A shell/session-level fix for the pairing-remount bug
ticket 15's Comments flagged: immediately after pairing an engine, the
app's FIRST navigation (the pair page's redeem-and-navigate to `/`) tears
the whole page down and remounts it mid-transition, killing the dock's
first glide ~40% in. After this ticket, pairing a fresh engine and landing
on the new-thread canvas produces the same clean first transition every
later navigation produces — no remount, no half-played animation — and
the gate/page phase machinery never remounts a live page subtree.

**Blocked by:** None — can start immediately (ticket 31's fleet registry
has landed; the trail below cites its current shapes).

**Status:** ready-for-agent

**Research:** None — this is a web-only defect with no desktop counterpart
to port (the desktop's shell never tears down its element tree on a phase
change). The primary source is ticket 15's implementer note:
`.scratch/web-parity/issues/15-new-thread-route.md` Comments →
"Implementer (2026-09-18)" → deviation 3.

**Desktop reference (for lookups only):** `crates/ui/src/shell.rs:238-240`
(`GatePhase` — the gate/page split this port mirrors), `shell.rs:1289-1295`
(`select_chat`'s commit only notifies; the transition is never coupled to
a remount), `composer.rs:6249-6258` (the `NewThreadTransitionStarted`
chain the remount interrupts).

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/root-layout.tsx` | edit | `GateAndPage`: the `phase` computation (:53) and the keyed `.page-fade` wrapper (:80) — the remount site |
| `web/packages/app/src/routes/pair-page.tsx` | edit (candidate) | the post-redeem `navigate({ to: "/" })` (:31-32 auto, :46-48 manual) — navigates before the registry's client has connected |
| `web/packages/app/src/state/session-provider.tsx` | edit (candidate) | the per-`fleet.engines` session reconcile effect (:59-86) — the arm ticket 15 recorded as "recreates the client" |
| `web/packages/app/src/state/fleet.ts` | read/verify | `pinDevice` write-back (:58-64) — rewrites the fleet entry on every connect, the recorded trigger |
| `web/packages/app/src/styles/app.css` | edit only if the animation moves | `.page-fade` / `rb-rise-in` (:2002-2010) |
| `web/packages/app/tests/` | new or edit | whatever pure seam the fix lands on (see Acceptance) |

## 1. Context a fresh session needs

- The root route wraps the router `Outlet` in ONE keyed fade:
  `routes/root-layout.tsx:80` renders `<div className="page-fade" key={phase}><Outlet /></div>`.
  The `key` makes any phase change REMOUNT the entire page subtree —
  that is deliberate for "recover from a gate" (the 500ms `phase-app`
  rise-in replays), but it also fires for ordinary phase transitions.
- `phase` (root-layout.tsx:53) is fleet-wide: `"loading"` while a paired
  engine has neither connected nor seeded rows from the offline cache,
  `"failed"` when every engine is parked, `"ready"` otherwise. `/pair`
  itself always reads `"ready"` (`onPair`).
- The pair page navigates to `/` the moment the REDEEM RPC resolves
  (pair-page.tsx:31-32 for the token flow, :46-48 for the paste flow) —
  that is before the registry's supervised client has dialed, so on a
  FIRST-EVER pairing (no offline cache rows to seed) the route lands in
  `"loading"`, renders only `.gate-loading`, and flips to `"ready"` when
  the engine connects — remounting the Outlet through the keyed fade at
  whatever moment the connect lands.
- The registry pins verified identities back into the pairing store on
  every connect (fleet.ts:58-64, `fleetStore.pinDevice`), so
  `fleet.engines` changes identity right at connect time and the session
  provider's effect (session-provider.tsx:59-86, keyed on `fleet.engines`)
  re-runs mid-boot. Today that effect KEEPS the session across a
  `pinDevice` rewrite (credential match, session-provider.tsx:64-68 — the
  registry owns the client, fleet.ts:34-38), so re-verify which arm of the
  original chain still fires before fixing.
- Ticket 15's recorded trail (Comments, deviation 3, 2026-09-18 — written
  against the pre-ticket-31 single-session provider, so its "recreates
  the client" arm is stale; the endpoint is not):

  > The engine session's `pinDevice` rewrites the fleet entry — the
  > session provider recreates the client — the status blips
  > "connecting" — `RootLayout`'s `page-fade` (keyed on phase) remounts
  > the whole Outlet, killing the first transition ~40% in. Nothing in
  > ticket 15 causes it (pre-15, no component spanned the routes to
  > notice); later navigations glide cleanly. A fix belongs to the
  > shell/session owner, not this ticket.

- Symptom repro (web only): pair a fresh engine (a first-ever pairing, no
  offline cache), watch the pair page navigate to `/` and the first dock
  choreography die partway — the remount replaces the mid-flight
  `ConversationPage` (and its dock clock, `routes/chat-page.tsx`'s
  `DockState`) with a fresh one wearing the 500ms fade. Later navigations
  glide cleanly because phase stays `"ready"`.
- The desktop never does this: `GatePhase` (shell.rs:238-240) swaps what
  the shell renders, but the shell's element tree — including the
  workspace and the transition machinery — is never keyed to a phase; the
  first transition after pairing glides like every other one.

## 2. Spec

The defect and its fix live in the shell/session layer; this ticket does
NOT change the dock, the composer, or the transition choreography itself
(ticket 15 owns those and they are correct — they merely get murdered
mid-flight).

Two candidate directions (pick one, or a better one the diagnosis
supports; do not do both blindly):

1. **Stop traversing a phase transition at first navigation.** The pair
   page could hold its navigation until the newly paired engine reaches a
   terminal state (connected, or parked-failed) — e.g. wait on the
   registry snapshot instead of the redeem RPC — so the route never lands
   in `"loading"` and the keyed fade never fires. Check what the phase
   machinery then shows during the wait (the pair page's own redeeming
   state is fine; a blank `.gate-loading` after redeem is NOT).
2. **Stop keying the page subtree to the phase.** Remount only the
   entrance animation, not the children: animate `.page-fade` on a
   recovery counter that ignores boot-after-pair (or re-trigger the CSS
   animation without changing the element identity), so `phase`
   transitions never unmount a live `Outlet`. Preserve the intended
   behavior the key encodes today: recovering from the `"failed"` gate
   still replays the 500ms rise-in on a healthy re-entry.

Either way the fix must be verified against BOTH phase transitions that
can occur after pairing: `loading → ready` (first-ever pair) and
`ready → failed → ready` (credential revoked then re-paired).

## 3. Pure logic to port

None — web-only. No desktop test names apply.

## 4. Gaps this ticket closes

| item | kind | desktop value | web value (file:line) | fix |
| --- | --- | --- | --- | --- |
| First navigation after pairing plays the full dock transition | behavior | glide completes (shell never remounts on phase; shell.rs:238-240, 1289-1295) | remount at `routes/root-layout.tsx:80` kills it ~40% in | one of §2's directions |
| A live page subtree is never unmounted by the gate/page split | invariant | shell element tree persists across `GatePhase` | `key={phase}` unmounts the Outlet subtree on every phase change | same |

## 5. Do not

- Do NOT change the dock/composer transition choreography (ticket 15's
  `lib/composer-dock.ts`, `routes/chat-page.tsx` dock clock) — it is
  correct; it is the victim.
- Do NOT remove the `phase-app` recovery fade itself — the 500ms rise-in
  on gate recovery is a deliberate parity behavior (root-layout.tsx's
  header comment); only its pairing-flow collateral is the bug.
- Do NOT add per-engine or per-route fade keys to work around the
  symptom — the fix belongs at the phase/remount boundary named above.
- Do NOT couple the pair page to engine internals beyond the registry
  snapshot it already renders through (`useFleetRegistry`); no new
  streams.

## 6. Acceptance

- [ ] Pair a FRESH engine (no prior offline cache) with the reduced-motion
      override OFF: the pair page navigates to `/` and the first dock
      choreography (canvas → chat, or the canvas's hero mount) plays to
      completion — no remount, no half-played fade.
- [ ] Revoke a credential (engine parked), land on the failed gate, then
      re-pair: the recovery fade still replays once on the healthy
      re-entry (the intended `phase-app` behavior survives).
- [ ] Engine switch / re-pair mid-session: the conversation page is not
      remounted by a phase blip it wasn't before.
- [ ] Unit test for whatever pure seam the fix lands on (e.g. the phase
      computation's transitions across a pair → connect → pinDevice
      sequence, or the new recovery-counter logic), in
      `web/packages/app/tests/`.
- [ ] `pnpm -r build` green; `web/packages/app` vitest green.
- [ ] web_smoke boot check unchanged (gate still renders, no error
      boundary).

## Comments

(Filed from ticket 15's implementer note, deviation 3, during the
web-parity wave-1 final review; the trail above is ticket 15's verbatim
record plus the current-shape line numbers.)
