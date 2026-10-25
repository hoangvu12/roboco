# 03 — Desktop: create, join, and manage rooms

**What to build:** The desktop side of ADR 0011, in Settings (no sidebar
changes in v1). Create room: mint the secret (OS CSPRNG), seed the book from
the local engine plus selected paired engines (`EngineRegistry`,
`crates/engine-client/src/registry.rs`), serialize the
`roboco-room:` invite, and show it as a QR + copyable string — the invite is
secret-bearing and follows ADR 0008 display rules (never logged, clipboard
warnings). Join room: paste an invite, dial each member directly through the
existing paired-engine machinery, present per member, store the room secret
in the desktop client config beside `paired-engines-v1.json`. The desktop
engine itself joins via the ticket-01 surface (Settings action + the CLI for
headless boxes). Rotate room secret: mint a new secret, show a guided
checklist of members (engines and clients) that have re-presented, keep the
old verifier rejecting until manually dropped. Leave room: forget + best-effort
own-session revokes per member. Room UI is hidden when a member engine lacks
`rooms-v1` (capability check, not error toasts).

**Blocked by:** 01 (engine surface). The rotate flow also depends on 04/05
existing for the checklist to be meaningful, but ships against engine
verifiers alone.

**Status:** claimed

**Parent:** `.scratch/rooms/spec.md`

- [ ] Settings → Rooms: create (pick member engines), join (paste), leave, rotate — full flow
- [ ] Invite shown as QR + string; never written to logs; engine join consumes it and stores only the verifier
- [ ] Joining a room surfaces member engines in the existing Devices/sidebar flow with no new grouping layer
- [ ] Composer/space palette device picker lists room members like any paired engine (no routing changes — per-engine, `ScopedId` untouched)
- [ ] Rotate flow tracks re-presented members and old-secret rejection
- [ ] Room UI hidden when `rooms-v1` is absent on the local engine
- [ ] Manual test on Windows: room of desktop + fixture headless engine; phone-less dry run of join/rotate/leave

Research: `.scratch/research/2026-10-25-room-model-catches.md` (C5 unknown-method
UX, C6 ScopedId seams), spec Implementation Decisions.
