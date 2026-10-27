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

**Status:** ready-for-agent

**Source:** `pass2-features-android.md` N2.

- [ ] Toggling an agent changes what New session offers (flow test against the fixture engine)
- [ ] The last enabled agent can't be turned off; the error copy and haptic match
- [ ] Commit records the `cc7da5c2a` providers slice

## Comments
