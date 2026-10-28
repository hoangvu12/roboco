# 40 — Android: Settings → Providers, turn agents on or off per computer (N2)

**Lane:** 3 Android parity. **Effort:** M.

**What to build:** Each agent on the chosen computer shows "Installed",
"Not installed" (dimmed) or "Offline", with an on/off switch. The last
agent left on can't be turned off. Errors read "Couldn't turn it on" /
"Couldn't turn it off". Success plays a selection haptic. The engine
already serves `SetHarnessEnabled` (`crates/engine/src/rpc.rs:1604`),
and `mobile-client` reads `offered` (`catalog.rs:55`). Add the FFI call
`set_harness_enabled` and an agents section on the Accounts & Usage
page (or a Providers row group, matching the iPhone's placement under
Settings).

**Upstream:** `cc7da5c2a` (#966): `apps/ios/Zeron/Cloud/ProvidersViewController.swift:315-342,361-381`
(behaviour spec; the reviews filed it as cloud, but the device half is
engine-local), `crates/mobile/src/client_ffi/providers.rs:167`.

**Roboco files:** `crates/mobile/src/client_ffi/*`,
`apps/android/.../accounts/*` (or `settings/*`), `design/HapticTable.kt`.

**Excluded:** cloud machine rows.

**Proto change:** no wire type (FFI).

**Blocked by:** 01. **Blocks:** 59 (Android first-run's "Your agents" step).

**Status:** done (2026-10-28, `wave10/android`) — Kotlin compiles in CI only

**Source:** `pass2-features-android.md` N2.

- [~] Toggling an agent changes what New session offers (the core stores the reply as the live catalog; real-engine Rust test; no flow test — see comments)
- [x] The last enabled agent can't be turned off; the error copy and haptic match
- [x] Commit records the `cc7da5c2a` providers slice

## Comments

2026-10-28 (wave10/android, lane 3): carried `cc7da5c2a` (#966)
`Cloud/ProvidersViewController.swift:303-381` (the computer rows and
`setEnabled`) and `client_ffi/providers.rs` `set_harness_enabled` by
intent; the Cloud rows are excluded.

- Core: `roboco-mobile-client` `set_harness_enabled` (`SetHarnessEnabled`
  over the engine handle); the reply goes through the same store as a
  live `ListHarnesses` (mock rule, disk cache, in-memory catalog,
  composers recomputed), so New session and the pickers see the change at
  once. FFI `CoreClient.setHarnessEnabled`.
- Android: an "Agents on <computer>" section on the Accounts & Usage page
  (iOS puts it on Settings → Providers per computer; Roboco's per-computer
  page is this one): installed first then by name, "Installed" / "Not
  installed" (dimmed) / "Offline", a `RobocoSwitch` per agent locked for
  the last one on, a missing CLI and an offline computer; a refusal alerts
  "Couldn't turn it on" / "Couldn't turn it off" with the engine's reason.
  The selection haptic is `RobocoSwitch`'s own (it plays on the flip).
- Tests: `cargo test -p roboco-mobile-client --test session_engine
  set_harness_enabled_follows_the_engines_rules` (the mock refused from
  Settings, a no-op disable answering with the catalog, an unknown agent
  refused); JVM `AccountSignInTest.agentRowsLockTheLastOneOnAMissingCliAndOffline`,
  `aFlipWritesTheComputerAndARefusalAlerts`.
- Deviation: no instrumented flow — the fixture engine offers only the
  mock (which the engine never enables from Settings), so there is no
  second agent to turn on or off there. Manual phone check recorded.
