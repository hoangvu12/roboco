# 05 — Docs + ADR 0011 amendment + agent guide

**What to build:** The decision record and user/agent documentation.

**ADR** (`docs/adr/0011-rooms.md`) gains a dated amendment (2026-10-27):
fleet rooms retain the secret at rest in the pairing DB domain (0600) —
a fleet secret is a group pairing token; introduction becomes symmetric
(`GetFleetInvite` after pairing) and hub-driven (`RoomJoin` push from
the desktop app); runtime reachability-only, direct dials, verifier
presentations, and rotate-then-revoke are unchanged; plain rooms keep
the full hostless semantics.

**User guide** (`docs/reference/remote-access.md`): §Rooms is rewritten
as **Fleet sharing** — the 5-line personal-fleet quickstart (pair
servers → scan the pairing QR on the phone → done), the symmetric
server-intro one-liner (`roboco engine pairing create --qr`), the PC 2
paste path, dedupe/revocation semantics (revoke sessions per engine,
`room leave`), and what "not in yet" means (no liveness probing by
design).

**Agent guide:** a short "fleet for agents" section — `pairing create
--qr` + phone auto-fetch, `room create` / `room invite`, `room list
--json` for state, stable `--json` shapes, and the exit-code contract.

Sweep the repo for user-facing "Rooms" language that survived the UI
deletion (docs/, help text, UI strings); update the glossary; verify the
AGENTS.md ADR references still read true.

**Blocked by:** 02, 03, 04

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-follow/spec.md`

- [ ] ADR 0011 amendment records the invariant change, its justification, and what is explicitly unchanged
- [ ] `remote-access.md` §Rooms → Fleet sharing: quickstart, symmetric intro, paste path, revocation, agents section
- [ ] No stale user-facing Rooms references (grep sweep: docs/, help text, UI strings)
- [ ] AGENTS.md / ADR cross-references accurate

## Comments
