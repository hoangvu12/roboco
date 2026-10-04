# 89 — Add-space routes to selected engine

**What to build:** Port zeron `3e14656f` / `460b7c89`: `#sessionForDevice(deviceId)` for folder/browse/create RPCs; palette passes `useEngineSessions()` + merged devices; stop pinning wrong `engineKey` at `open()`.

**Blocked by:** **87** (merged device list)

**Status:** ready-for-agent

**Zeron ref:** `state/add-space.ts`, `add-space-palette.tsx`, `tests/add-space.test.ts`

**Acceptance:** Browse/create project on engine B uses B's client when device chip scoped to B.
