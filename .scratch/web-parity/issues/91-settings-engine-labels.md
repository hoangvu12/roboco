# 91 — Settings engine labels from host device

**What to build:** Port zeron `97f86114`: `settingsDeviceName(engine, registry)` from host device's rename row; indicator shows engine host name, not pair-session label (`Roboco web on Windows`). Settings pages remount on engine key change.

**Blocked by:** None.

**Status:** ready-for-agent

**Zeron ref:** `lib/settings-engine.ts`, `settings-engine-indicator.tsx`, `tests/settings-fleet-routing.test.ts`

**Note:** Keep roboco `DeviceSwitcher` on Accounts when intra-engine `targetDeviceId` still applies; do not conflate with canvas engine chip.

**Acceptance checklist (added with the port; ticked items demonstrated by the suites below, not by a two-live-engine run):**

- [x] `settingsDeviceName(engine, registry)` names an engine from its own registry entry's WatchDevices row — renames included, blank names are missing names.
- [x] The engine's own row is never borrowed from a peer entry; the pairing store's pinned `deviceId` resolves the row before `EngineInfo` lands.
- [x] The settings engine indicator (trigger + popover rows) shows engine host device names, never the pair-session label.
- [x] Settings pages remount when the engine key changes (engine-local page state is dropped, loads re-issue against the new engine with no passthrough target).
- [x] The Accounts/Agents `DeviceSwitcher` still routes intra-engine `targetDeviceId` picks and is not conflated with the engine indicator / canvas engine chip.

## Comments

### Implementer note (2026-10-08)

Ported zeron `97f86114` (PR #526) on top of roboco `05faf5a0` — nothing
from that landing was reverted; one commit on
`webparity-91-settings-engine-labels`.

**What landed:**

- `web/packages/app/src/lib/settings-engine.ts` —
  `settingsDeviceName(engine, registry)` now takes the fleet engine
  (`baseUrl` + pinned `deviceId` + label, `Pick<StoredEngine, ...>` — same
  parameter type `engineDisplayName` takes) and names it from its OWN
  registry entry's WatchDevices row, resolving the host row through
  `entry?.info?.deviceId ?? engine.deviceId` (the pairing store's pinned
  identity finds the row before `EngineInfo` lands) and trimming the row's
  name so a blank name is a missing name. `settingsEngineLabel(fleet,
  registry)` names the active engine only when the fleet is plural AND the
  active key has a fleet engine — otherwise null, and the indicator hides.
- `web/packages/app/src/components/settings-engine-indicator.tsx` — popover
  rows name engines through `settingsDeviceName(engine, registry)` (the
  fleet engine object, not the key); trigger label unchanged (05faf5a0's
  no-`Engine ` prefix form kept).
- NEW `web/packages/app/src/components/settings-engine-page.tsx` —
  `SettingsEnginePage`: the engine-addressing settings pages render their
  bodies keyed on `settingsEngineKey(fleet)` (= `fleet.active`, the engine
  the `/settings/*` routes route to), so a settings-engine switch REMOUNTS
  the page body — an in-flight sign-in and its failure, install/update
  replies, expanded rows, error strips, and the DeviceSwitcher passthrough
  target are dropped with the engine they belong to, and the fresh body
  re-loads from the chosen engine. This is roboco's realization of zeron
  97f86114's “settings pages follow the selected engine” contract.
- `web/packages/app/src/routes/settings-accounts.tsx` /
  `settings-agents.tsx` — split into the thin keyed wrapper + `*PageBody`;
  the `useEffect(() => setTarget(null), [fleet.active])` reset effects were
  removed (subsumed by the remount). `settings-remote-access.tsx` — same
  split (its mount-load comment updated). CAUTION (b) honored: the
  `DeviceSwitcher` stays on Accounts AND Agents for intra-engine
  `targetDeviceId` switching (its cross-engine host pick routes through
  `fleetStore.setActive`); the canvas engine chip is untouched and distinct.
- `web/packages/app/src/routes/settings-devices.tsx` — `EngineHostDeviceRow`'s
  title call adapted to the new signature (the stored engine, or a synthetic
  `{baseUrl, label: "", deviceId: entry.info?.deviceId}` for a registry-only
  row). No remount there: the Devices page is fleet-scoped, not
  engine-addressed.

**Tests (TDD: the red-first cases were confirmed failing at base):**

- `web/packages/app/tests/settings-engine.test.ts` (extended, 3 new red
  cases): own-row naming vs the pair-session label and a peer's copy of the
  row; rename pickup; pinned-identity row resolution before `EngineInfo`
  lands; blank-name fallback; fleet-missing active → null; direct
  `settingsDeviceName` signature/fallback cases.
- `web/packages/app/tests/settings-engine-indicator.test.ts` (new, ported
  from zeron): trigger + both popover rows named from each engine's own
  registry row, never the pair label; a row click routes
  `fleetStore.setActive`; the label re-renders after the switch. Green at
  base (05faf5a0 already names via `info.deviceId` rows) — pins the
  contract.
- `web/packages/app/tests/settings-fleet-routing.test.ts` (new, zeron's
  suite on roboco's transport): Accounts/Agents re-list on the engine the
  indicator picked, with no `targetDeviceId` on the fresh body; Agents drop
  an engine-a sign-in failure on engine switch — RED at base (engine-b
  offers the same Antigravity row so stale state would reconcile without
  the remount); Accounts keep the DeviceSwitcher's intra-engine
  `targetDeviceId: "dev-b"` pick and switch engines on another engine's
  host-row pick.

**Verification:** `pnpm -C web/packages/app exec tsc --noEmit` clean (base
was clean); `pnpm build` (tsc + vite) green; focused vitest green
(settings-engine 6, settings-engine-indicator 1, settings-fleet-routing 4);
neighbor suites green 85/85 (settings-device-switcher,
settings-devices-sections, settings-dialogs, account-row, fleet-view,
session-provider, settings-completion, remote-access-view,
settings-dialog, settings-section). Full app suite deferred to the
orchestrator's integration pass, per process. The plan-level two-live-engine
acceptance run was not performed (no live engines in this environment);
the mounted suites are the demonstrated evidence.

**Deviations from zeron 97f86114 (deliberate):**

- Fallback chain: zeron falls back `name || engine.label || engine.baseUrl`;
  roboco falls back `name || engineDisplayName(engine)` (host · short device
  id, the 05faf5a0 identity) because roboco's `StoredEngine.label` IS the
  pair-session label ("Roboco web on Windows…") this ticket forbids showing.
- The trigger renders `{label}` without zeron's `Engine ` prefix —
  05faf5a0's deliberate form, kept.
- Zeron's removal of the `DeviceSwitcher` from Accounts/Agents and the
  `target` state was NOT ported: zeron's relay `setActiveDevice` transport
  has no intra-engine device addressing, roboco's pairing architecture
  does (CAUTION b).
- Zeron's empty-registry label test expectations were reshaped for the
  `engineDisplayName` fallback above.
