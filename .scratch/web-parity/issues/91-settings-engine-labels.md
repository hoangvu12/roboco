# 91 — Settings engine labels from host device

**What to build:** Port zeron `97f86114`: `settingsDeviceName(engine, registry)` from host device's rename row; indicator shows engine host name, not pair-session label (`Roboco web on Windows`). Settings pages remount on engine key change.

**Blocked by:** None.

**Status:** ready-for-agent

**Zeron ref:** `lib/settings-engine.ts`, `settings-engine-indicator.tsx`, `tests/settings-fleet-routing.test.ts`

**Note:** Keep roboco `DeviceSwitcher` on Accounts when intra-engine `targetDeviceId` still applies; do not conflate with canvas engine chip.
