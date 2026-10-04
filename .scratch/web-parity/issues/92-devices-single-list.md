# 92 — Devices settings: one list per engine

**What to build:** Port zeron `779cc2e0`: remove duplicate `PairedEngineHostRow` + merged device double-list; one device-style row per engine via `fleetDeviceRows` (Forget / Pair again on row). Keep roboco pairing URL box (not WorkOS sign-in).

**Blocked by:** **87**

**Status:** ready-for-agent

**Zeron ref:** `routes/settings-devices.tsx`, `tests/settings-devices.test.ts`

**Acceptance:** Two paired engines → two rows, no extra "Roboco web on …" engine card block.
