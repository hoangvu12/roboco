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

**Status:** claimed

**Parent:** `.scratch/rooms/spec.md`

- [ ] "Join room" scans/pastes a `roboco-room:` invite; each member gets its own session and engine entry
- [ ] Room secret stored only as a Keystore `room:` blob; never in plain metadata, never logged
- [ ] UniFFI `PairedEngine` room field + metadata codec stays additive (old metadata reads fine)
- [ ] Book union + dial-verify + `UpdateRoomBook` push implemented in the Rust core
- [ ] Missing members retried on app open / network change; partial join persists and heals without user action
- [ ] Room UI hidden when an engine lacks `rooms-v1`
- [ ] Flow tests against `android_fixture` engines (join, re-present, offline member heals) via `apps/android/scripts/flow-tests.sh`
- [ ] Battery envelope unchanged: no new probe cadence or wake locks (assert in review; the service rules of ADR 0010 are untouched)

Research: `.scratch/research/2026-10-25-room-model-catches.md` (C1 phone
costs, C6 join atomicity, FFI notes), spec Implementation Decisions.
