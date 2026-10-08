# 89 — Add-space routes to selected engine

**What to build:** Port zeron `3e14656f` / `460b7c89`: `#sessionForDevice(deviceId)` for folder/browse/create RPCs; palette passes `useEngineSessions()` + merged devices; stop pinning wrong `engineKey` at `open()`.

**Blocked by:** **87** (merged device list)

**Status:** ready-for-agent

**Zeron ref:** `state/add-space.ts`, `add-space-palette.tsx`, `tests/add-space.test.ts`

**Acceptance:** Browse/create project on engine B uses B's client when device chip scoped to B.

- [x] Browse/create project on engine B uses B's client when device chip scoped to B (demonstrated at the unit seam: `tests/add-space.test.ts` "browses and creates on the selected engine, not the routed one" — routed engine A's client receives zero calls, B's client receives ListDrives + ListFolders + createSpace with the raw device id).

## Comments

**Landed** (commit `fix(web): route add-space browsing and creation to the picked engine`):

- `state/add-space.ts`
  - `AddSpaceContext.registry` → `devices: readonly Device[]` — the merged device list (ticket 87's `fleetDeviceRows` output, the same rows the palette renders). `#devices()` now consumes it directly; the store-side re-derivation (hosts + "extra" non-host rows of the routed engine) is gone, which also removes the store/render list mismatch the old derivation carried.
  - `#sessionForDevice(deviceId)`: ported zeron `460b7c89`'s host check — the scoped device's engine resolves through `context.sessions`, and the resolved engine's own device id (`client.engineInfo`) must equal the raw id, else null ("Device is not connected", never a silent fallback to the routed engine). Zeron reads `session.engine.deviceId` (their StoredEngine always carries it); roboco's `StoredEngine.deviceId` is a post-verify pin, so the port reads `client.engineInfo?.deviceId` — the same source `fleetDeviceRows` builds host rows from.
  - `#submitBrowsed`: existing-space dedup now compares the RAW device id against the owning session's cache rows (base compared the scoped id — never matched); the optimistic row's `id`/`deviceId` and the landing/rollback ids are scoped with the session resolved at call time. Base's `#scope` never actually scoped anything — `parseScopedId` returns `{engine: null}` (no throw) for unscoped ids, so the old "already scoped" passthrough short-circuited every call and optimistic rows landed raw in the merged (scoped) sidebar namespace. `#scope(id, session)` is now zeron's blind encode over raw ids.
  - `attach`: zeron `460b7c89`'s soft reset replaces the force-close. Base closed the whole palette whenever the flow's picked engine differed from the ROUTED session — with registry ticks re-running attach, picking engine B while routed to A slammed the palette shut mid-flow. Now the flow only resets (revision bump, `listing: { error: "Device connection changed. Browse again to continue." }`, `submitBusy: false`) when the PICKED engine's client was replaced.
  - `pickDevice`: no more `fleetStore.setActive(engineKey)` global retarget (add-space is not a settings switch — the plan's rule) and no more wrong-engine fallback (`engine.baseUrl` pin) on a malformed scoped id. `flow.engineKey` remains as a staleness key for `isStaleResponse` only — RPC routing and scoping never read it.
  - ListFolders/ListDrives no longer send `targetDeviceId`: the resolved session IS the picked device's engine (its own host), so `#rpcTargetDeviceId` is dead and removed. PrepareSpacePath keeps its `targetDeviceId` (raw id) — zeron `3e14656f` kept it too, and the wave-5 Windows drive-path manual-prepare flow is untouched.
- `components/add-space-palette.tsx`: `attach` passes `devices: fleetSnapshot.devices.rows` (the merged list, already what the component renders); effect deps follow zeron (`[session, sessions, devices.rows]`). Presence dots/engine states unchanged.
- `tests/add-space.test.ts`: fake sessions carry an engine key + host device id + spaces cache; ported zeron's "browses and creates on the selected engine, not the routed threadripper" and "does not browse a missing scoped engine through the active engine", plus `460b7c89`'s "rejects a device id that does not match the selected connection, even when scoped"; new attach-lifecycle tests (reset on client replacement, survival through routed-engine churn); new dedup-by-raw-id and optimistic-rollback tests (the rollback now also locks the scoped-id contract); the old "closes an open flow when its owning engine changes" test was rewritten to the ported reset semantics; single-engine ladder tests moved to the host-device model (fake host = the picked device, as zeron's own suite does).

**Deliberately not ported** from `460b7c89` (out of ticket scope, would regress desktop parity):
- The manual-path validation rewrite (PrepareSpacePath → ListFolders-based `inspectProjectFolder`), the `createProjectRepository`/CreateRepo managed-repo form, `resetPrivateState`/`private-session-generation`, and the project-relay integration tests. Roboco's engine keeps `PrepareSpacePath` (no `CreateRepo` method exists in `@roboco/engine-client`), the wave-5 drive-path manual-prepare is the current desktop-parity surface, and "private owner-scoped engine sessions" is a zeron-only concept. The ticket's acceptance is engine routing, which is fully covered.
- 460b7c89's `submit()`/`#guard` rework around `ProjectRequest` — kept base's identity guard + `StaleGuard` (roboco's `engineKey` staleness field stays; it is redundant with the scoped `deviceId` but harmless and its `is_stale` unit tests are desktop-mirrored).

**Tests:** `pnpm -C web/packages/app exec vitest run tests/add-space.test.ts` — 39/39 (6 of the new/rewritten ones red at base, verified via stash before implementing). Typecheck `pnpm -C web/packages/app exec tsc --noEmit` clean. Adjacent suites re-run green: shortcuts, command-palette, fleet-view, devices, sidebar-view, session-provider, engine-store, sidebar-view-menu, flyout-side, view-menu-phone (115 + 50 + 17 tests). Full app suite deferred to the orchestrator per instructions.

**Residual risks:**
- `pickDevice` no longer retargets `fleetStore.active`. If any surface relied on that side effect (none found — the routed engine comes from `resolveNewChatTarget`/scoped ids, and the settings Devices page owns `setActive`), it will surface in the two-live-engine acceptance pass.
- The attach soft reset keeps the flow open with an error row when the picked engine's client is replaced mid-flow; the user must re-browse. Zeron's integration tests cover this against a real relay; roboco's coverage is unit-level only (no relay fixture exists yet).
