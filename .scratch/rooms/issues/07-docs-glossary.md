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

**Status:** ready-for-agent

**Parent:** `.scratch/rooms/spec.md`

- [ ] `CONTEXT.md` Room entry with avoid list exactly as decided in the design interview
- [ ] `docs/reference/remote-access.md` "Rooms" section with per-platform join flows and the eviction runbook
- [ ] Gate catalogue lists `rooms-v1` and `uploads-100mb-v1`
- [ ] User-facing strings use "Room" per the glossary (Settings surfaces; no sidebar strings in v1)

References: ADR 0011, `.scratch/rooms/spec.md`.
