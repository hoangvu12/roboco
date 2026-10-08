# 87 — Dedup merged fleet devices and spaces

**What to build:** Port zeron `5cd23bd7`: `fleetDeviceRows()` / `fleetSpaceRows()` so sidebar and composer pickers show each host device and each synced project once (drop mirror rows on non-owner engines).

**Blocked by:** None.

**Status:** ready-for-human

**Zeron ref:** `lib/view.ts`, `lib/devices.ts`, `state/fleet.ts` @ `5cd23bd7`; tests `fleet-view.test.ts`, `devices.test.ts`

**Roboco files:** `lib/view.ts`, `lib/devices.ts`, `state/fleet.ts`, new tests

**Acceptance:**

- [x] Two engines with mirrored workspace → ONE space row in `useFleetSnapshot()`
- [x] Device list shows one row per engine host

## Comments

Landed in `2ed91164` (TDD: the new `fleetSpaceRows` cases failed red at
base first).

**What was already there.** Ticket 86's landing (`9101f8c5`) had carried
`fleetDeviceRows`/`fleetSpaceRows` and the `useFleetSnapshot()` wiring
over, but `fleetSpaceRows(spaces)` was a behavioral no-op: it compared
the ENGINE SCOPES of `space.id` and `space.deviceId`, and
`projectRegistrySnapshot` scopes both with the reporting engine's key
(`scopeSpace`), so every projected row passed the filter — mirrors
survived and the sidebar/composer pickers showed N copies of every
synced project. The missing piece was the owner check, which needs the
registry.

**The fix.** `fleetSpaceRows(registry, spaces)` (signature now matches
`fleetDeviceRows`): a projected space survives only when the device named
in its owner field (`parseScopedId(space.deviceId).rawId` — wire
semantics, `Space.device_id` is the immutable owner) is the reporting
engine's HOST device (`engine.info.deviceId`). This is the direct
adaptation of zeron's `rawId === source`: zeron keys engines BY device
id, roboco keys by `baseUrl`, so the host comes from engine info. Rows
whose engine host is unknown (info null — the seeded-offline-cache
window before the first handshake, registry.ts `#seed`) are kept: an
unprovable mirror is better shown twice than a project vanished.
`state/fleet.ts` passes the registry at the one call site.

**`fleetDeviceRows`** needed no change — one row per engine host was
already correct at base. Its new tests pin it, including the deliberate
deviation from zeron's `?? engine.key` fallback (a baseUrl is never a
device id, so an info-less engine contributes no row).

**Tests.** `tests/fleet-view.test.ts` and `tests/devices.test.ts`
(`pnpm -C web/packages/app exec vitest run tests/fleet-view.test.ts
tests/devices.test.ts`); focused safety ring over view-consumers
(view, sidebar-view, new-chat-target, settings-device-switcher,
add-space, settings-devices-sections);
`pnpm -C web/packages/app exec tsc --noEmit` clean — counts below are
post-review (see the review round). Full app suite and the
two-live-engine browser check are deferred to the orchestrator's
integration pass.

**Deviations.** (1) `fleetSpaceRows` takes the registry — upstream's
one-argument form only works because zeron engine keys are device ids.
(2) Zeron's `?? engine.key` host fallback is dropped for the same
reason. (3) Same upstream edge kept verbatim: a space whose owner's
engine is not in the fleet drops from every engine's copy (upstream head
`6e4f3633` behaves identically). `05faf5a0`'s engine-identity work in
`lib/view.ts` is untouched.

## Review round

Two-axis review (Standards + Spec) of `dc306b44…HEAD`; findings and
fixes landed in the review commit on top of `2ed91164`:

- **Spec (the one actionable finding):** acceptance criterion 1 names
  `useFleetSnapshot()`, but the first landing demonstrated it only at
  the pure seam — the hook's merge assembly (`mergedRowSet`, the empty
  guard, the assembly) was tsc-pinned, never executed. `state/fleet.ts`
  imports cleanly in the node test environment (its singletons are
  no-op-safe: `EngineStore` falls back to `memoryStorage`,
  `IndexedDbEngineCache` no-ops by design, the `window` hooks are
  guarded), so the memo body moved verbatim into an exported pure
  `mergedFleetSnapshot(registry, active)` and a new test drives the
  ticket's exact acceptance through it: two mirrored engines → one
  space row per project AND one device row per engine host in the merged
  snapshot. The extraction is pure movement — `useFleetSnapshot` now
  wraps it in the same `useMemo` with the same deps. That test also
  caught a fixture incoherence while landing (space owner ids vs device
  row ids were two different worlds; `fleetDeviceRows` correctly found
  no hosts) — fixed by making the fixture one coherent world.
- **Standards (minor):** the registry-fake builders were duplicated
  across the two test files (fine at two copies, upstream duplicated
  them too; third copy pending with the merge test) — extracted to
  `tests/helpers/fleet-fixtures.ts` (`fleetEngine`/`fleetRegistry`),
  the repo's established shared-fixture home.
- **Standards (minor):** the defensive `catch` arm of
  `fleetSpaceRows` (malformed scoped ids pass through, upstream has no
  equivalent) was undocumented and untested — one doc-comment line and
  one test added.

Final results: `fleet-view.test.ts` 15/15 + `devices.test.ts` 13/13
(28/28), safety ring 109/109, `tsc --noEmit` clean. Both acceptance
criteria are demonstrated through the merge the hook memoizes; the
remaining untested link is the 3-line `useFleetSnapshot` wrapper
itself (subscription + memo, no data logic), which tsc pins. The
plan-level two-live-engine browser check stays with the orchestrator's
integration pass.
