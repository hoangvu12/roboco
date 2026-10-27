# 04 — Android: scan once, join the room, self-heal

**What to build:** The Android side of ADR 0011. Pair-engine flow gains
"Join room": scan the desktop's QR (or paste the `roboco-room:` invite), then
per book member dial directly through the existing per-engine `TailcatRoute`
machinery (`crates/mobile-client/src/engines/route.rs`), call
`POST /room/present` with a device label, and store the resulting
`PairedEngine` per member (transport: Direct | Tailcat as today). The room
secret is a new Keystore-encrypted blob class (`room:`) beside the existing
`cred:`/`tc:` blobs (`PairedEngineStore.kt`, `KeystoreSecretCipher.kt`);
`PairedEngine` grows a room field — a UniFFI surface change, so core and app
release together (same APK). Book merge: union books from all reachable
members, dial-verify, push merged books back via `UpdateRoomBook`.
Self-healing: missing members are retried on app start and network change
(ride the existing `Verify`/supervisor machinery — no liveness probing, no
new wake sources); offline members show as offline engines, exactly like
today's unreachable paired engines. Partial joins are normal: a member that
was asleep during join appears after the next heal pass.

**Blocked by:** 01 (engine surface; the FFI surface grows in the same repo).

**Status:** resolved

**Parent:** `.scratch/rooms/spec.md`

- [x] "Join room" scans/pastes a `roboco-room:` invite; each member gets its own session and engine entry
- [x] Room secret stored only as a Keystore `room:` blob; never in plain metadata, never logged
- [x] UniFFI `PairedEngine` room field + metadata codec stays additive (old metadata reads fine)
- [x] Book union + dial-verify + `UpdateRoomBook` push implemented in the Rust core
- [x] Missing members retried on app open / network change; partial join persists and heals without user action
- [x] Room UI hidden when an engine lacks `rooms-v1`
- [x] Flow tests against `android_fixture` engines (join, re-present, offline member heals) via `apps/android/scripts/flow-tests.sh`
- [x] Battery envelope unchanged: no new probe cadence or wake locks (assert in review; the service rules of ADR 0010 are untouched)

Research: `.scratch/research/2026-10-25-room-model-catches.md` (C1 phone
costs, C6 join atomicity, FFI notes), spec Implementation Decisions.

## Implementation evidence — 2026-10-25

- Branch `rooms-04` (commit `600d1b9d1`), merged into `rooms`. Diff:
  ~46 files, +3753/−255 — crates/mobile-client, crates/mobile,
  apps/android, plus the accepted test-only deviation
  `crates/engine/examples/android_fixture.rs` (+ the flow scripts).
- Rust core (`crates/mobile-client/src/rooms.rs`, ~950 lines):
  `Client::join_room(invite)` — decode via the landed grammar, join
  every book member in parallel (address classification → existing
  Route/TailcatRoute machinery; `POST /room/present`; grant adopted via
  the pairing's own `adopt_engine`, extracted from `pair_engine`).
  Partial joins persist and heal (no rollback). The heal pass dials
  members without an engine entry, unions `GetRoomBook` from connected
  room engines, dial-verifies, pushes the merged book back via
  `UpdateRoomBook`; `Parked{Revoked}` members re-present. Two real
  races found and fixed: an in-flight `JoinGuard` (one presentation
  per (room, address)) and trigger-time room snapshots for the
  start-heal. `RoomEngineJoined` events are never coalesced.
- FFI (`crates/mobile`): `PairedEngine.room` / `EngineView.room`
  (additive; old metadata reads fine), `CoreClient::new` takes stored
  rooms, `join_room`, `parse_room_invite` (name + member labels only —
  secret/addresses never cross), `RoomError`/`RoomJoinResult`, uniffi
  renames for the Kotlin `Throwable.message` clash.
- Kotlin: `room:` Keystore blob (AES-GCM, own AAD `…v1:room:<id>`;
  secret + member addresses encrypted; missing/corrupt blob degrades
  to no-heal, never crashes); additive `EngineMetaJson` codec;
  `CoreStartup` loads rooms + persists `RoomEngineJoined`; the Pair
  page parses `roboco-room:` invites (target line, Join room capsule,
  persist-first with per-engine rollback); `classifyScan` accepts room
  QR payloads; Engine detail Room row gated on the engine's
  `rooms-v1`.
- Battery envelope (asserted): the heal runs ONLY on the existing
  client-start and network-change triggers, coalesced (one pass in
  flight, one rerun max), dials bounded (15 s present, ~20 s tunnel);
  no timers, no probes, no wake locks, no service changes — ADR 0010
  untouched.
- Tests: implementer — mobile 58, mobile-client 198 lib +
  `rooms_engine` 8+ with real engines (join, partial heal on network
  change, book union push-back, old-engine 404 silent skip, revoked
  re-present, refused-secret leak check, start heal on restored
  room), real_engine 19, session 21, all suites green; `assembleDebug`
  built both APKs; JVM 2218 non-Robolectric pass (Robolectric
  host-limited). Orchestrator gates (main checkout, post-merge):
  mobile 58, mobile-client 197 lib + the documented `burst_coalescing`
  timing flake (passes in isolation), rooms_engine/real_engine/
  session_engine/tailcat/workspace/notify/images/accounts/side_chats
  suites all green; `assembleDebug` both APKs (arm64 84 MB, x86_64
  86 MB); testDebugUnitTest — every failure is the linux-aarch64
  Robolectric limitation (2895 direct + the `ShadowNativeBitmapFactory`
  cascade counted 8 more; ~2238 non-Robolectric pass). Final
  close-out: workspace `cargo build` EXIT 0, wiregen `--check` fresh.
- Deviations (accepted): `android_fixture.rs` gained test-only
  additive control endpoints (`POST /room-invite`, `/room/room-join`
  via the CLI's RoomStore path) — without them the acceptance's flow
  tests are impossible; the landed engine surface is untouched and the
  flow test skips on rigs without the endpoints. Robolectric-based
  Kotlin tests are CI-only on this host (logic covered Rust-side);
  flow tests need an emulator (no KVM here — CI-only, skip cleanly).
  Bonus forensics: pre-existing `real_engine` zero-dial flake
  root-caused to the box's PID-1 `roboco headless` engine probing new
  loopback listeners with `HEAD /`; the counting listener now ignores
  HEAD probes (the client only dials WS upgrades/POSTs).
