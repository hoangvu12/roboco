# 03 — Desktop fleet: Remote-access fleet card, auto push, Rooms page deleted

**What to build:** The desktop rework in `crates/ui` (plus the settings
wiring in `apps/roboco`).

**Delete** the Settings → Rooms page: `crates/ui/src/settings/rooms.rs`,
its `mod` wiring, page tests, and any orphaned strings/entries. No
"Rooms" user-facing surface survives on desktop.

**Add a Fleet card to Settings → Remote access**
(`crates/ui/src/settings/remote_access.rs`): plain-language per-engine
state — In / Not in yet (Retry) / an opt-out switch per engine. The fleet
room is minted lazily on the first fleet event (default name "Fleet",
one per data dir). Membership = local engine + every paired engine in the
Tailcat `Serves` shape, minus opt-outs (persisted beside
`rooms-v1.json`).

**Auto push:** on new engine pairing and on app open, `RoomJoin` each
member engine (`rooms-v2` gate; engines without it show "update that
engine") and run the silent book-union heal. The local engine holds the
fleet room (kind=fleet, secret retained) so phones pairing to this
machine get the fleet via `GetFleetInvite`.

**Keep two flows on the card:** "Show fleet invite" (QR + copy, ADR 0008
caption) and "Join a fleet with an invite" (paste field — the PC 2 case,
the old join flow).

`crates/ui/src/rooms.rs` `RoomOps` survives reworked: `create` → fleet
mint (kind=fleet, one per data dir), `join` (paste), `sync` (silent
heal), `leave`; the rotate-checklist UI goes (CLI path, documented in
ticket 05). The three-axis status jargon dies; statuses are plain
language only.

**Blocked by:** 01

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-follow/spec.md`

- [ ] Settings → Rooms page gone: no page, nav entry, tests, or user-facing strings survive
- [ ] Fleet card on Remote access: lazy mint, per-engine In / Not in yet / opt-out, plain language, quiet rooms-v2 degradation
- [ ] Auto push on pairing events + app-open heal: `RoomJoin` per member, book unions, idempotent (no session churn, no duplicate registry entries)
- [ ] Local engine holds the fleet room (store, kind=fleet, secret retained)
- [ ] "Show fleet invite" + "Join a fleet with an invite" work; secrets never logged (existing test patterns)
- [ ] `RoomOps` create/join/sync/leave reworked; rotate UI removed without losing CLI parity
- [ ] UI tests (fake dialer, `RoomOps::with_dialer` pattern) cover: lazy mint, opt-out skip, dedupe on join (already-paired member), heal after late join, old-engine skip

## Comments
