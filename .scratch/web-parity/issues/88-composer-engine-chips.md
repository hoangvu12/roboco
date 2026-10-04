# 88 — Composer engine/project chips (not settings-only)

**What to build:** Port zeron `4510bab2` + `4928e1b2`: `DeviceCard` uses `targetForDevicePick` / `targetForProjectPick`; labels **Select engine** / **Selected engine** (not "This device"/"You"); `engineStatesOf(registry)` for presence; `ProjectChip` gets `currentDeviceId`.

**Blocked by:** **86** (target helpers)

**Status:** ready-for-agent

**Zeron ref:** `composer-footer.tsx`, `new-thread-selectors.tsx`, `tests/browser-engine-picker.test.ts`

**Roboco files:** same paths under `web/packages/app`

**Acceptance:** Picking engine B on canvas switches routed session without opening Settings; foreign project cleared on engine switch.
