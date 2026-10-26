# 07 — Docs: glossary entry, remote-access guide, gate catalogue

**What to build:** The documentation half of ADR 0011 once the wire names
land. Add the Room entry to `CONTEXT.md` with the decided footnote
("a named group of engines a client joins once to be introduced to all of
them; a room shares reachability, never data"; avoid list: *sync room, Loro
room — zeron's removed cloud concept; `room_gen` is an unrelated legacy wire
name*). Add a "Rooms" section to `docs/reference/remote-access.md` covering
the join flows per platform (desktop QR, Android scan, web paste), the
hostless model, presentation semantics (repeatable, throttled), the
eviction runbook (rotate + per-engine revokes), and the secrecy rules for
`roboco-room:` invites. Register `rooms-v1` and `uploads-100mb-v1` in the
gate catalogue. Cross-link ADR 0011 from the ADR index conventions.

**Blocked by:** 01 (wire names settle; lands with or after the first
engine implementation).

**Status:** resolved

**Parent:** `.scratch/rooms/spec.md`

- [x] `CONTEXT.md` Room entry with avoid list exactly as decided in the design interview
- [x] `docs/reference/remote-access.md` "Rooms" section with per-platform join flows and the eviction runbook
- [x] Gate catalogue lists `rooms-v1` and `uploads-100mb-v1`
- [x] User-facing strings use "Room" per the glossary (Settings surfaces; no sidebar strings in v1)

References: ADR 0011, `.scratch/rooms/spec.md`.

## Implementation evidence — 2026-10-25

- Branch `rooms-07` (commit `4832fbbcf`), merged into `rooms` as
  `9ae06701c`. Diff: 4 files, +134/−33, docs only.
- `CONTEXT.md`: Room entry in the Remote access section with the decided
  wording verbatim ("a named group of engines a client joins once to be
  introduced to all of them; a room shares reachability, never data") and
  the exact avoid list (*sync room, Loro room — zeron's removed cloud
  concept; `room_gen` is an unrelated legacy wire name*).
- `docs/reference/remote-access.md`: the Rooms stub expanded to the full
  guide — hostless model (verifier-only engine state, secret with clients,
  0600 book at `rooms/{id}/book.json`), the invite grammar and ADR 0008
  secrecy rules, per-platform joins (CLI flags verified against
  `apps/roboco/src/pairing_cli.rs`: `join <INVITE> [--json]`, `list
  [--json]`, `leave ROOM_ID`; desktop QR / Android scan / web paste
  documented as designed), presentation semantics (repeatable, 5
  refusals/60 s with verify-before-throttle, shape-only errors),
  `GetRoomBook`/`UpdateRoomBook` union semantics, the rotate-then-revoke
  eviction runbook, and `rooms-v1` hiding. Gate catalogue rows verified
  (`uploads-100mb-v1` gained its legacy caps).
- `AGENTS.md`/`ARCHITECTURE.md`: ADR 0011 cross-linked where the other
  ADRs are introduced.
- Strings sweep: zero user-facing room strings exist yet (tickets 03–05
  land them) — only layout comments and the frozen `room_gen` wire name,
  both correctly left alone.
- One precision call (accepted): the eviction runbook makes "drop the
  old room" (`leave`) an explicit gated step — `room_id` derives from the
  secret, so rotation creates a new room row and the old verifier keeps
  accepting presentations until the old room is left. Matches the shipped
  engine code.
