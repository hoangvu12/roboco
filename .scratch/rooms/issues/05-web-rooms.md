# 05 — Web client: paste the room invite once

**What to build:** The web side of ADR 0011. The engine-served web client
(web/packages/app) gains "Join room" next to the existing pair flow: paste the
`roboco-room:` invite once; for each member the client calls the origin
engine's `/pairing/room-redeem` (ticket 02), receives the member's session
grant plus `/tailcat-relay/{id}` base, and stores each engine pairing exactly
like today's relayed engines (session credentials in browser storage, scoped
to the origin; the room secret is stored the same way — same secret-at-rest
class as a session credential). Member engines then appear in the web
client's engine list with device labels, using the existing multi-engine
patterns. Room UI hides when the origin engine lacks `rooms-v1`. The
credential store and codec changes must stay lenient/additive
(`EngineMetaJson.kt` rules apply by analogy to the web codec).

**Blocked by:** 02 (room-redeem on the origin engine), 01 transitively.

**Status:** ready-for-agent

**Parent:** `.scratch/rooms/spec.md`

- [x] "Join room" paste flow redeems every member through the origin engine and lands the pairings
- [x] Room secret persisted in browser storage with the same care as session credentials; never logged, never in URLs sent beyond the redeem call
- [x] Member engines appear in the existing engines UI with device labels; offline members show the existing offline treatment
- [x] Room UI hidden when the origin engine lacks `rooms-v1`
- [x] Vitest suites: invite parsing, redeem codec against a scripted fake server; conformance run against the `web_conformance` engine example
- [x] `pnpm -r build` typecheck clean

Research: `.scratch/research/2026-10-25-multi-engine-chats-and-taildrop.md`
(relay analysis), `.scratch/research/2026-10-25-room-model-catches.md` (C5).

## Implementation evidence — 2026-10-25

- Branch `rooms-05` (commit `42fd9e3e9`), merged into `rooms` as the
  `Merge ticket 05` commit. Diff: 15 web files, +2954/−21 —
  web/packages/engine-client + web/packages/app only.
- `engine-client`: `room-invite.ts` mirrors `crates/proto/src/room.rs`
  byte-for-byte (frozen fields, same length/control rules in the same
  order, byte-equal refusal texts; cross-implementation pin: decodes a
  hardcoded Rust-produced invite and re-encodes it identically;
  `roomId` = domain-tagged SHA-256 in pure TS, cross-checked against
  node:crypto). `room-redeem.ts`: the `POST /pairing/room-redeem`
  {invite, target, label} codec against the origin engine — grant +
  `/tailcat-relay/{id}` baseUrl; every error status (400/401/404/429/502)
  surfaces the engine's label-only texts, and any body that echoes the
  invite or secret collapses to the bare status.
- `app`: the room record (id, name, secret, origin, members, landed
  engine keys) in localStorage `roboco.rooms.v1` — the secret at the
  same at-rest class as a session credential, scoped to the origin;
  lenient codec (unknown keys ignored, damaged bytes preserved with
  joins refused). `joinRoom`: parse once, one parallel redeem per
  unique label, land via `EngineStore.landRelayedGrant` (extracted from
  the tailcat pair path, unchanged behavior); failed members stay
  unjoined and render as offline rows in the existing devices list;
  joined engines carry `room <name> · <member label>` in their meta
  line — no grouping layer. "Join room" hides without `rooms-v1`.
- Tests: engine-client 69 (53 unit incl. grammar + redeem-vs-scripted-
  fake-server, 11 conformance incl. the real engine decoding a
  TS-encoded invite with label-only 404 on unknown target, 5 smoke);
  app +24 new (room-store 8, rooms-join 9 incl. the partial-failure
  flow, mounted devices-page 7 incl. capability hiding + offline rows).
- Orchestrator gates (main checkout, post-merge): `pnpm -r build` clean;
  engine-client 69/69; app 2559 passed + the 6 documented pre-existing
  failures (identical set since wave A, verified via stash).
- Deviations (accepted): the conformance suite pins the pre-dial
  surface (the one-engine harness cannot run the full redeem round-trip
  — the full flow is covered by the scripted fake server plus ticket
  02's `room_relay` engine rig); "Join room" lives in Settings →
  Devices only (not the /pair landing) per ADR 0011/story 16; offline
  member rows are display-only (re-paste re-joins — presentations are
  repeatable).
