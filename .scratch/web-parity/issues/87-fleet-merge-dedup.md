# 87 — Dedup merged fleet devices and spaces

**What to build:** Port zeron `5cd23bd7`: `fleetDeviceRows()` / `fleetSpaceRows()` so sidebar and composer pickers show each host device and each synced project once (drop mirror rows on non-owner engines).

**Blocked by:** None.

**Status:** ready-for-agent

**Zeron ref:** `lib/view.ts`, `lib/devices.ts`, `state/fleet.ts` @ `5cd23bd7`; tests `fleet-view.test.ts`, `devices.test.ts`

**Roboco files:** `lib/view.ts`, `lib/devices.ts`, `state/fleet.ts`, new tests

**Acceptance:** Two engines with mirrored workspace → one space row in `useFleetSnapshot()`; device list one row per engine host.
