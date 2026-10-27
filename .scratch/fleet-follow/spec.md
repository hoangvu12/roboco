# Fleet follow: pairing brings the engines

Status: ready-for-agent
Spec date: 2026-10-27

## Problem Statement

Rooms v0.13.0 works exactly as designed and still confused its only user.
The report: "i created a room, okay there is an invite, but the member does
not joined or does not serve secret — like its so confusing." Root causes
from the 2026-10-27 debugging session (full context:
`.scratch/rooms/handoff-2026-10-27.md`):

- **Create ≠ joined.** The create flow mints the invite and hands it over;
  each member engine must then be joined separately (CLI on the servers) and
  the client must present the secret again on Reconnect. Four manual steps
  hide behind a one-click-looking button.
- **Status jargon.** "Waiting — share the invite; Reconnect once its engine
  joins", "the member refused the room secret or does not serve rooms",
  "This engine is not a member": three membership axes (book, verifier,
  session) surfaced as one flat list of fixed-text riddles.
- **Mental-model mismatch.** The user's model is "my desktop knows my
  fleet; connect a phone anywhere and the fleet comes along." The shipped
  model is hostless invite distribution, which nobody asked for.

## Solution

**Fleet follow.** The room machinery survives as a protocol; the room
concept disappears from the UI.

1. A **fleet room** is a room of a new `kind` whose secret is retained at
   rest by member engines (pairing DB domain, 0600) — an amendment to ADR
   0011's "no room secret at rest on engines" invariant, justified because
   engines already mint and hold pairing credentials: a fleet secret is a
   group pairing token.
2. The desktop app keeps exactly one fleet room per data dir, minted lazily
   the first time fleet sharing does anything. Membership defaults to every
   paired engine that serves its Tailcat route, with a per-engine opt-out.
   The app pushes membership to each engine via a new session-authed
   `RoomJoin` RPC (remote join; idempotent; book unions) and maintains book
   unions silently through the existing client heal pass.
3. A phone that pairs to **any** engine in the fleet gets the fleet
   automatically: after pairing it calls the new `GetFleetInvite` RPC
   (session-authed) and silently runs the existing mobile room join. Every
   member engine then appears as an ordinary paired engine. Engines without
   a fleet room answer null and the pairing behaves exactly like today.
4. The Rooms settings page is deleted. Plain-language fleet state moves to
   the Remote access page; a single "Join a fleet with an invite" field
   covers desktops joining a fleet minted elsewhere (the PC 2 case).
5. CLI stays first-class for humans and agents: existing
   `roboco engine pairing create --qr` becomes a complete fleet
   introduction (the phone auto-fetches), `roboco engine room
   create/invite` bootstrap and share fleets from headless boxes,
   `join/list/leave` keep working, and every command speaks `--json`.

## User Stories

1. As the fleet owner, I pair my servers to my desktop app (already works
   today) — no room is created, nothing to think about.
2. As the fleet owner, I pair my phone to my desktop by scanning the
   existing pairing QR: the servers come along automatically, and they
   appear as ordinary engines in my list.
3. As the fleet owner, I pair my phone to a headless server instead
   (`roboco engine pairing create --qr` in a terminal): the desktop and
   the other servers come along too (symmetric introduction).
4. As the fleet owner, I can opt an engine out of the fleet and see plain
   per-engine state: In / Not in yet.
5. As a desktop user, I join a fleet minted elsewhere by pasting one invite
   (the PC 2 case).
6. As an agent chat on any member engine, I can introduce a new device
   with one command and inspect fleet state machine-readably.
7. Duplication is handled: re-pairing, re-joining, and already-paired
   members never produce duplicate engine entries or second rows.

## Implementation Decisions (locked 2026-10-27)

- **Symmetric introduction** (any member engine can introduce a new
  client) over desktop-only introduction.
- **All online engines by default**, per-engine opt-out; no per-invite
  scoping (follow-up if ever needed).
- **No composite QR.** The pairing link stays exactly as it is; the fleet
  arrives via `GetFleetInvite` after pairing. QR density, the scanner, and
  `ScanPayload` stay untouched.
- **One fleet room per data dir**, minted lazily, default name "Fleet".
- **`kind` travels in the invite** (`"kind":"fleet"`, absent = plain
  room); plain rooms keep the full v0.13.0 semantics (verifier-only
  engines, CLI/manual path). Old invites without `kind` are plain rooms by
  construction.
- **`rooms-v2` capability** gates the new RPCs; clients degrade silently
  (old engine = no fleet, never an error surface).
- **C4a holds**: `RoomJoin` / `GetFleetInvite` never mutate remote-access
  settings.
- **Backward compatibility is explicitly ignored** (user decision,
  2026-10-27: "ignore backward compatiblity, no one uses this anyway").
  Old engines simply lack `rooms-v2`; existing plain room rows keep
  working as plain rooms. No data migration beyond IF-NOT-EXISTS tables.
- **Secret discipline** (ADR 0008): fleet secrets and invites never appear
  in logs, `Debug` impls, or error text; the pairing DB stays 0600; stdout
  may carry an invite only where a human asked for it
  (`roboco engine room invite`).

## Out of scope

- Web relay room flow (`room-redeem`) — unchanged, power path.
- Secret-rotation UI — revoke per-engine (ordinary sessions) + CLI
  `room leave` + re-create; a "reset fleet" button is a follow-up.
- Liveness probing — unchanged by design (books heal through clients).
- Per-pairing scoped invites — follow-up.

## Verification

Each ticket carries its acceptance criteria and focused tests; the finish
phase runs the cross-stack integration check (mint → push → phone-style
fetch+join → dedupe → late-member heal) and one final code review from the
feature base to the integration head.
