# 04 — Mobile fleet: auto-fetch after pairing, silent join, dedupe

**Port reality (2026-10-27):** your layer's v0.13.0 files are absent from
this branch (evil merge, see spec). Port `crates/mobile-client/src/rooms.rs`
and the `client_ffi`/Kotlin room-join surfaces from
`git show v0.13.0:<path>`, adapting to the post-wave-9 mobile tree, then
add the fleet behavior below.

**What to build:** Android + `crates/mobile-client`.

After a **successful engine pairing** (the existing Pair page flows), the
client calls `GetFleetInvite` on the freshly paired engine (`rooms-v2`
gate; absent capability or a null answer → silent no-op, the pairing
looks exactly like today). With an invite in hand: run the existing
silent room join (`crates/mobile-client/src/rooms.rs` — the
parse/join/heal machinery) with a progress toast, then **dedupe**: member
engines already paired on this device (match by engine identity, else by
member address) are skipped so no engine appears twice; joined members
appear as ordinary paired engines.

Re-pairing the same engine re-runs the fetch idempotently (upsert the
room record, no duplicate sessions). The existing heal-on-open covers
late members and book unions. Keystore secret storage (the `room:` blob)
is unchanged.

**Blocked by:** 01

**Status:** ready-for-human

**Parent:** `.scratch/fleet-follow/spec.md`

- [ ] Post-pairing auto-fetch: rooms-v2 + invite → silent join; null/absent → no-op; no error toasts — *Rust half demonstrated against real engines (`fleet_engine.rs`); the Kotlin trigger + "no error toast" tests (`PairPageModelTest`) are written but could not be compiled or run on this host (no Android SDK) — CI*
- [ ] Dedupe: already-paired members skipped; no duplicate engine rows (PairedEngineStore-level test) — *Rust dedupe demonstrated against real engines; `PairedEngineStoreTest.aFleetFollowAndItsReRunsNeverDuplicateEngineRows` written, not run here — CI*
- [x] Idempotent re-pair/re-join: upsert, no session churn — *real-engine session counts in `fleet_engine.rs`; the store's upsert-by-id is pre-existing tested behaviour*
- [ ] Secrets stay in the Keystore `room:` blob; never logged, never in plain metadata (existing test patterns in `PairedEngineStoreTest.kt`) — *the `room:` blob code is unchanged from v0.13.0; Rust `Debug` redaction of `FleetOffer` is tested; the Kotlin at-rest/metadata assertions are in the new store test, not run here — CI*
- [ ] JVM unit tests green on this host (Robolectric is CI-only here); instrumented expectations documented for CI — *this host has no Android SDK/NDK at all, so neither Gradle nor any JVM test runs here; expectations documented below*

## Comments

### 2026-10-27 — implementation evidence (Windows 11 host, worktree `roboco-ff-04`, branch `ff-04-mobile`)

**Port.** `git cherry-pick -n 600d1b9d 5cabff4b` applied cleanly: every mobile file
at `d275c021` was byte-identical to `600d1b9d^`, apart from
`session_engine.rs`, which auto-merged. After the cherry-pick, the room
surface under `crates/mobile-client`, `crates/mobile`, `apps/android` and
`android_fixture.rs` matches `v0.13.0` exactly. That includes ScanPayload's
`roboco-room:` recognition, which is unchanged and adds no composite QR.

**Decision: where orchestration lives.** In Rust (`crates/mobile-client`), so
it is tested against real engines:
- `Client::fetch_fleet(engine_id) -> Option<FleetOffer>` waits up to 20 s for
  the link to come up. It then gates on `rooms-v2`, calls `GetFleetInvite` and
  accepts only `kind=fleet`. Every failure returns `None` silently.
- `Client::join_fleet(offer)` runs the ordinary `join_decoded`.
- `follow_fleet` combines both steps.

The FFI side exposes `CoreClient.fetchFleet(engineId): FleetOffer?`, where
`FleetOffer` is an opaque UniFFI object (name and member labels only), plus
`CoreClient.joinFleet(offer)`. The invite string therefore never crosses into
Kotlin; only the join result's `RoomRecord` does, as before. On the Kotlin
side, `PairPageModel` launches the follow in the app scope after a pairing has
persisted and the page has closed. It shows a progress toast, persists exactly
like a room join (the `room:` blob first, then upserts by engine id), and then
shows an "Added N engines" toast when N > 0. Every failure path only logs the
exception class: no error phase, no error toast.

**Deviations** (decided during implementation; recorded here together with
the evidence instead of before building on them, which breaks the ticket's
ordering rule. Flagging that.):
1. **Skip rule for member joins, now for all rooms:** any live entry already
   dialing a book member's address is skipped. This holds whatever room the
   entry was tagged with, so the phone never presents to it again and never
   mints a second session. An untagged entry (a plain pairing, usually the
   introducing engine) is *adopted by tag*: no presentation, and the record is
   returned for an upsert. v0.13.0 re-presented to entries with no room or a
   different room ("adopt on rotation"). Rotation now leaves the old tag in
   place, which is harmless since those are ordinary paired engines.
   `Parked{Revoked}` entries are still re-presented.
2. **A plain re-pair keeps the entry's room tag.** v0.13.0 reset it to `None`,
   which made the store sweep the `room:` blob in between and forced a
   re-adopt.
3. **The identity half of the dedupe happens only after a presentation.**
   Engines answer nothing unauthenticated, so a book address that differs from
   the pairing address (for example a LAN link vs a book address) is
   presented. `adopt_engine` then folds the presentation into the existing row
   by `device_id`, so there is still one row and the engine id is kept. Cost:
   one room session on that engine (reused per room and label afterwards,
   with no churn on re-runs). The pairing session it supersedes stays active
   but unused on the engine. It is **not** revoked, because the revoke RPC
   lives in the remote-access controller group and we avoid it for C4a hygiene
   and lockout risk. This is a follow-up if the desktop's Devices list must
   show exactly one row. When the addresses match (Tailcat invite ↔ Tailcat
   book address, the desktop's normal case), no session is minted at all.
4. The progress toast shows whenever a fleet is offered, even when every
   member is already paired. The count toast shows only when N > 0.
5. No instrumented fleet flow test was added (`android_fixture` has no fleet
   endpoints). See the CI expectations below.

**Verification on this host:**
- `cargo test -p roboco-mobile-client --test fleet_engine`: 5 passed.
  - `pairing_one_member_brings_the_fleet_along_and_re_pairing_is_idempotent`:
    3 engines, distinct device ids; the introducer adopted with the same id
    and credential; 1 phone session per engine; a re-follow persists nothing
    and creates no new sessions; a re-pair folds into its row, keeps the tag,
    and the re-follow mints nothing.
  - `already_paired_members_are_skipped_and_a_second_address_folds_by_identity`
  - `an_engine_without_a_fleet_answers_null_and_the_pairing_stays_plain`:
    no fleet, and plain room only → `None`; no events; unknown id → `None`.
  - `a_late_member_heals_into_the_fleet_on_the_next_trigger`
  - `the_heal_adopts_an_untagged_paired_member_without_a_session`
- `cargo test -p roboco-mobile-client`: lib 197 passed, including the new
  `fleet_offers_never_print_their_secrets` and
  `records_dedupe_by_engine_id_keeping_the_last_write`. Also passed: accounts
  3, fleet 5, images 1, notify 9, real_engine 19 (1 ignored), rooms_engine 8
  (all v0.13.0 room tests still green under the new skip rule), side_chats 5,
  tailcat 2, workspace 7. session_engine: 20 passed and 1 failed in the
  full-suite run (`a_frozen_transcript_connection_is_replaced`, a stall-proxy
  timing test on an unrelated code path). It then passed 2/2 in isolation, so
  it looks flaky under load.
- `cargo check -p roboco-mobile`: clean. `cargo test -p roboco-mobile --lib
  rooms`: 3 passed (FFI redaction, mapping, parse).
- `cargo check -p roboco-engine --example android_fixture`: compiles (ported
  rig). Its warnings come only from pre-existing preview/harness/engine
  sources.
- `cargo xtask android-core --skip-ndk --skip-tailcat --profile mobile`:
  host library plus generated Kotlin bindings. Confirmed
  `suspend fun fetchFleet(engineId: String): FleetOffer?`,
  `suspend fun joinFleet(offer: FleetOffer): RoomJoinResult`, and
  `FleetOffer.name()/memberLabels()/destroy()`, matching the Kotlin call
  sites.

**Not run here (CI-only):** this host has no Android SDK
(`ANDROID_HOME=C:\Android\sdk` does not exist, and there is nothing under
`%LOCALAPPDATA%\Android`), so `./gradlew :app:assembleDebug
:app:testDebugUnitTest` cannot run. The JDK 17, cargo-ndk 4.1.2 and Android
rust targets are present. Written and awaiting CI:
- `PairPageModelTest`:
  - `aPairingFollowsTheFleetAfterItPersistsAndUpsertsInPlace`
  - `noFleetIsASilentNoOp`
  - `aFailedFleetJoinNeverSurfacesAnError`
  - `aFleetStoreFailureRollsBackOnlyTheNewMembersSilently`
- `PairedEngineStoreTest.aFleetFollowAndItsReRunsNeverDuplicateEngineRows`
- the ported v0.13.0 room JVM tests (incl. the `5cabff4b` CI fixes).

**Instrumented expectations (CI `android-emulator`, not implemented here):**
pair against an `android_fixture` engine whose data dir holds a fleet
containing a second fixture engine (adb-reversed loopback addresses). Expected
result:
- the Pair page closes as on a normal pairing;
- the toast "Adding the rest of your fleet…" appears, then "Added 1 engine
  from your fleet";
- the engine list shows exactly 2 engines;
- re-pairing the first engine leaves 2 engines and shows no "Added" toast;
- a fixture engine without a fleet shows no toast at all.

`RoomFlowTest` (ported) keeps covering the manual `roboco-room:` join and the
offline-member heal.
