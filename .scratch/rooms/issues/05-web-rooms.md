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

- [ ] "Join room" paste flow redeems every member through the origin engine and lands the pairings
- [ ] Room secret persisted in browser storage with the same care as session credentials; never logged, never in URLs sent beyond the redeem call
- [ ] Member engines appear in the existing engines UI with device labels; offline members show the existing offline treatment
- [ ] Room UI hidden when the origin engine lacks `rooms-v1`
- [ ] Vitest suites: invite parsing, redeem codec against a scripted fake server; conformance run against the `web_conformance` engine example
- [ ] `pnpm -r build` typecheck clean

Research: `.scratch/research/2026-10-25-multi-engine-chats-and-taildrop.md`
(relay analysis), `.scratch/research/2026-10-25-room-model-catches.md` (C5).
