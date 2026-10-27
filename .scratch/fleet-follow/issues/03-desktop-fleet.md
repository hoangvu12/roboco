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

**Status:** ready-for-human

**Parent:** `.scratch/fleet-follow/spec.md`

- [x] Settings → Rooms page gone: no page, nav entry, tests, or user-facing strings survive
- [x] Fleet card on Remote access: lazy mint, per-engine In / Not in yet / opt-out, plain language, quiet rooms-v2 degradation
- [ ] Auto push on pairing events + app-open heal: `RoomJoin` per member, book unions, idempotent (no session churn, no duplicate registry entries) — *push/heal/idempotency demonstrated on `RoomOps::sync` and the trigger rule on `FleetSync::note_connected`; the AppState glue between them (registry watch → `request_fleet_sync`) compiles but has no end-to-end test — needs the manual app run*
- [x] Local engine holds the fleet room (store, kind=fleet, secret retained)
- [x] "Show fleet invite" + "Join a fleet with an invite" work; secrets never logged (existing test patterns)
- [x] `RoomOps` create/join/sync/leave reworked; rotate UI removed without losing CLI parity
- [x] UI tests (fake dialer, `RoomOps::with_dialer` pattern) cover: lazy mint, opt-out skip, dedupe on join (already-paired member), heal after late join, old-engine skip

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

### 2026-10-27 — implementation evidence (Windows 11 host)

**Toolchain:** this host's default `stable` is Rust 1.93, on which the pinned
gpui (zui fork) does not compile (`std::hint::cold_path` unstable). Every
`roboco-ui` / `roboco` command below ran as `cargo +1.99.0 …` (already
installed; no toolchain settings changed). `roboco-engine-client` builds on
either.

**What landed**

- `crates/engine-client/src/rooms.rs` (+ tests), ported from v0.13.0:
  `RoomSecretFile` (`rooms-v1.json`, atomic, 0600, fixed-text load errors),
  `present_room_member`, `join_room_members`, `heal_room_books`,
  `revoke_room_sessions`, `fetch_room_book`/`push_room_book`, plus fleet
  additions `push_room_join` (`RoomJoin`), `is_fleet_conflict`,
  `ClientRoom.kind`, `ClientRoom::link`. `mint_room_secret` is imported from
  `roboco_proto::room` (the local copy is gone).
- `crates/engine-client/src/registry.rs`: shared `adopt_engine` tail with
  identity dedupe (decision 1) — `pair` and `join_room_member` both reattach
  an engine the registry already holds instead of adding it twice.
- `crates/ui/src/rooms.rs` (+ tests): `RoomOps` reworked for fleet —
  `create` (lazy fleet mint via `RoomStore::create("Fleet", …)`), `sync`
  (probe → mint → address harvest → local merge → `RoomJoin` push → book
  heal → present/adopt → report), `join` (paste, with the decision-5
  conflict handling), `leave`, `fleet_invite`, `set_opt_out`; `TailcatProbe`
  (C4a guard), `FleetPrefs` (`fleet-v1.json`, 0600), the pure `survey`,
  plain `FleetState` captions, `FleetSync` scheduling. No rotate code.
- `crates/ui/src/state.rs`: `fleet_ops` (rooms-v2 + data dir + registry
  gate), `local_engine_supports`, single-flight `request_fleet_sync`, and the
  registry-watch trigger (`note_fleet_engines`: app open + first connect of
  each engine).
- `crates/ui/src/settings/remote_access.rs` + `remote_access/fleet.rs`: the
  Fleet card — subtitle, one row per engine (status dot, label, plain
  caption, Retry on "Not in yet", include switch when the engine has an
  identity), "Show fleet invite" (QR tile + invite + Copy + the ADR 0008
  caption), "Join a fleet with an invite" (paste field, confirm/cancel when
  switching away from a shared fleet). Hidden entirely without local
  rooms-v2.
- No Rooms page, nav entry, tests, or strings exist (`grep` for
  `SettingsSection::Rooms|RoomsPage|settings::rooms|"Rooms"` over
  `crates/ui/src` and `apps/roboco/src`: no matches).
- Separate trunk repair so the ui test target compiles at all:
  `HarnessUpdateStatus`/`HarnessUpdateFailure` test initializers in
  `settings/harnesses.rs` and `state.rs` lacked `compatibility`/`held_back`/
  `kind` (the same merge casualty ticket 01 fixed in proto).

**Verification (this host)**

- `cargo test -p roboco-engine-client --lib rooms` → 11 passed (incl.
  `an_already_paired_member_is_reattached_never_duplicated`,
  `a_dead_entry_is_reattached_under_its_key_without_session_churn`,
  `room_join_pushes_idempotently_and_reports_fleet_conflicts`);
  `cargo test -p roboco-engine-client --lib` → 24 passed.
- `cargo +1.99.0 test -p roboco-ui --lib -- rooms remote_access` → 17 passed:
  `the_fleet_is_minted_lazily_and_pushed_idempotently` (lazy mint, kind=fleet,
  secret retained, `RoomJoin` push, no duplicate entry, no room session,
  address cached, C4a `remote-access.json` never written),
  `opted_out_engines_are_skipped_until_switched_back_on`,
  `joining_dedupes_a_member_already_paired_by_hand`,
  `a_late_joiner_is_healed_in_on_the_next_pass`,
  `pasting_switches_from_a_solo_fleet_and_asks_before_leaving_a_shared_one`
  (solo auto-leave, `FleetSwitchNeeded`, confirmed switch revokes+forgets
  only adopted entries, plain-room and junk invites → fixed text, invite
  Debug redacted), `the_survey_skips_old_opted_out_offline_and_duplicate_engines`
  (old-engine skip, decision 9), `the_probe_never_mints_against_engines_without_a_tailcat_route`
  (C4a, `ac713514` regression), `fleet_captions_are_plain_language`,
  `fleet_events_fire_once_per_engine_connect`, prefs/labels, and the gpui
  page test `the_fleet_card_shows_plain_rows_only_on_rooms_v2_engines`; the
  5 pre-existing Remote access page tests stay green.
- `cargo +1.99.0 check -p roboco` (package `roboco`, `apps/roboco`) → clean.
- Full `cargo +1.99.0 test -p roboco-ui --lib` → 1631 passed, 7 failed.
  None are fleet work: 5 `shell::spaces::path_field_tests` fail
  deterministically in isolation on Windows path separators (`\` vs `/`
  assertions, `crates/ui/src/shell` untouched by this ticket);
  `state::cache_tests::paired_history_survives_disconnect_and_client_restart`
  and `shell::spaces::manual_path_interactions::manual_remote_path_enter_and_create_use_selected_engine`
  timed out under the 10-minute parallel run and pass when rerun alone.

**Not demonstrated / CI-only / manual**

- The AppState trigger glue (registry watch → `request_fleet_sync`) has no
  end-to-end test; the rule and the pass are tested separately.
- Production dialing over real Tailcat forwarders and the real
  `TailcatProbe` against a Tailcat-serving engine — test engines cannot run
  Tailcat; a manual desktop + server + phone run is needed.
- 0600 assertions for `rooms-v1.json` / `fleet-v1.json` are `#[cfg(unix)]`
  (Linux CI).
- Known limit (decision 1): when a dead hand-paired entry is reattached with
  the fleet's presentation session, the old hand-pairing session stays
  valid-but-unused on that engine (its id is not known to the client);
  revoke it from that engine's Remote access page if desired.
