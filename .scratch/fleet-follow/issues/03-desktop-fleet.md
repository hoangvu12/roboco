# 03 — Desktop fleet: Remote-access fleet card, auto push, no Rooms page

**Port reality (2026-10-27):** your layer's v0.13.0 files are absent from
this branch (evil merge, see spec) — including the old Rooms settings page.
DO NOT port the page back; its deletion is already done. Port only what the
fleet card needs: `crates/engine-client/src/rooms.rs` (client join
machinery, `mint_room_secret` now lives in proto — update imports) and the
`RoomOps` internals from `crates/ui/src/rooms.rs` of the tag, reworked for
fleet. Then build the Fleet card fresh.

**What to build:** The desktop rework in `crates/ui` (plus the settings
wiring in `apps/roboco`).

**DELETE** the old Rooms page requirement is moot (see port reality above):
never re-land `crates/ui/src/settings/rooms.rs` from the tag. Fleet is the
only rooms surface the desktop gets.

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

### 2026-10-27 — design decisions and deviations (recorded before building)

1. *Registry dedupe lives in the shared adopt tail* (`EngineRegistry::adopt_engine`,
   used by `pair` and `join_room_member`): after reading `EngineInfo`, an
   existing REMOTE entry with the same `device_id` is reused. Room joins:
   a live duplicate is linked and the freshly presented room session is
   revoked best-effort (no second entry, no extra live session); a dead
   duplicate (e.g. a Tailcat-paired engine after a desktop restart — its
   forwarder died) is *reattached* under its existing key with the fresh
   forwarder + credential, so its chats/tabs keep their ids. `pair` re-pairing
   an already-paired remote engine reattaches the same way (re-pair is
   idempotent). Pairing the local engine's own identity is left as today.
2. *`RoomMemberSession.session_id` becomes `Option`*: `None` = the room is
   linked to an entry it did not adopt (hand-paired, or a dedupe link).
   Leave revokes/forgets ONLY room-adopted entries; hand-paired engines are
   never touched. Retiring a stale room-owned record no longer forgets the
   entry first — the re-dial reattaches it under the same key.
3. *Member addresses* come from the C4a-guarded harvest of `ac713514`:
   `GetRemoteAccess` must show the Tailcat `Serves` shape before the
   one-second `CreateTailcatInvite` (token never redeemed); the address is
   cached in the client fleet record per engine key, so app-open re-syncs do
   not re-mint. A probe seam (`FleetProbe`) sits beside the dialer seam for
   tests (test engines cannot run Tailcat).
4. *Lazy mint trigger*: a sync mints the fleet (store `create("Fleet", …)`,
   kind=fleet, secret retained) only when at least one eligible member exists
   (paired, connected, rooms-v2, Tailcat `Serves`, not opted out) — or when the
   user asks for "Show fleet invite". Sync runs on app open (first local
   connect), whenever a remote engine connects for the first time this
   process (covers new pairings from any flow), on Retry, and on opt-out
   changes; single-flight with one coalesced re-run.
5. *Paste conflict (`FleetConflict`) decision*: if this machine's current
   fleet is solo (no book member other than this machine), it is left
   silently and the pasted fleet joined. Otherwise the paste is refused with
   a confirm ("This computer is already in a fleet…" + "Leave it and join"),
   which re-runs with an explicit replace (leave = revoke+forget only
   room-adopted entries, drop the local store row, drop the client record).
6. *Plain-room invites are refused* in the fleet paste field (fixed text
   pointing at `roboco engine room join`); plain rooms stay a CLI path.
7. *Opt-out is forward-only*: there is no remote-leave RPC in the frozen
   contract, so an opted-out engine is never pushed and never contributes its
   address, but one that already joined keeps the fleet until
   `roboco engine room leave` runs on it (books only grow). Opt-outs are
   keyed by engine device id in `fleet-v1.json` beside `rooms-v1.json`
   (atomic, 0600 on unix).
8. *No Leave button on the card* — the card keeps the two flows the ticket
   lists; `RoomOps::leave` backs the switch flow, and the CLI keeps
   `room leave`. Rotate UI is gone (CLI path).
9. *Old-engine skip is tested on the pure survey* over a synthetic registry
   snapshot: every real test engine advertises rooms-v2, so an old engine
   cannot be stood up over loopback.
10. *The local engine is driven through `RoomStore` directly* (same data dir,
    WAL-coordinated — the tag's pattern), which keeps `FleetConflict`
    downcastable; the card hides entirely when the local engine lacks
    rooms-v2 (capability hiding, never an error).
