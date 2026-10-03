# 08 — Settings → Devices splits "This device" / "Other devices"

**What to build:** Desktop partitions the devices page into labeled
sections — `local_block` under `section_label("This device")`
(devices.rs:~517-523), `others_block` with the empty state "Pair another
device to see it here." (:~525-536), assembled :541-551 — and **hides the
"Other devices" section entirely when the workspace scope is Local**
(:554-557, "a local-only workspace never has other devices to list"). The
web renders one flat card with a per-row "This device" badge
(settings-devices.tsx:246-268). Port the two-section split and drop the
badge (the header is the marker, as on desktop).

**Blocked by:** None.

**Status:** ready-for-human

**Research:** `.scratch/web-parity-next/research.md` (settings item 2).

**Desktop reference (for look-ups only):**
`crates/ui/src/settings/devices.rs:517-557`.

**Web files to touch:**

| File | Change | Owns |
| --- | --- | --- |
| `web/packages/app/src/routes/settings-devices.tsx` | edit | partition `devices` on `device.id === localDeviceId` into two sections (the `settings-section-header` pattern already used for "Engines", :232-236; two `settings-card`s; local first); "Other devices" empty state uses `settings-empty` with the desktop copy; drop the per-row badge |
| `web/packages/app/src/lib/` (or inline + export) | new | pure `partitionDevices(rows, localDeviceId)` |
| `web/packages/app/tests/` | new/edit | partition tests + a mounted-row test (pattern: `tests/settings-dialogs.test.ts`) |

## 1. Notes

- The local device is `session?.client.engineInfo?.deviceId`
  (settings-devices.tsx:75) — the engine session's own device, not a fleet
  field.
- `localDeviceId === null` before engineInfo loads — fallback: keep the
  flat list until known (decide in the ticket's implementation; a
  partition-unknown arm in the pure helper makes it testable).
- The Others-section scope gate: gate on the snapshot's workspace scope if
  `WatchSnapshot` exposes it, else always render (the browser can pair
  remote synced engines) — record whichever lands.
- The "Engines" card above is the documented web-only ticket-45 fold —
  untouched.

## 2. Tests

`partitionDevices`: local rows → first section; others → second; null
localDeviceId → the fallback arm; empty others → flagged for the empty
state. Mounted: headers render, badge gone, empty copy matches desktop.

## 3. Acceptance checklist

- [x] Two labeled sections, local first, badge dropped
- [x] Empty "Other devices" copy = "Pair another device to see it here."
- [x] Scope gate decided and recorded
- [x] Tests + full app suite green

## Comments

**Implemented (wpn-08, commit `db2fb5c0`, reviewed on branch
`ticket/wpn-08-devices-grouping`).** Pure `partitionDevices(rows,
localDeviceId)` lives in `src/lib/devices.ts` (discriminated union:
`unknown` fallback arm while engineInfo is not loaded — the flat card
stays; `known` → `local` / `others`). The route renders "This device"
(only when `local` is non-empty, the desktop's `when_some(local_block)`)
and "Other devices" via the `settings-section-header` + `settings-card`
idiom, local first, and drops the per-row badge and `DeviceRow.isLocal`.

**Scope gate (decided + recorded): always render the "Other devices"
section.** The web's `WatchCacheSnapshot`
(`engine-client/src/watch-cache.ts:73`) exposes only
generation/capabilities/chats/spaces/devices/statuses/connectivity — no
workspace scope — and the browser can pair remote synced engines, so the
desktop's hide-when-Local gate (`devices.rs:554-557`) has nothing to read
on the web. Recorded in the route's JSX comment and the mounted suite's
header. `session?.client.engineInfo?.workspaceScope` exists but was
deliberately not used: the ticket pinned the gate to the snapshot.

**Adjudicated visible-string change:** with engineInfo loaded and zero
devices, the page now shows "Other devices" + "Pair another device to
see it here." (the desktop's only zero-device state — `local_block` is
skipped when empty, `others_block`'s empty state is the pair copy, and
no "No devices registered" string exists on the desktop's Devices page)
instead of the old "No devices registered". That string survives only
in the `unknown` (pre-engineInfo) fallback arm, which the ticket mandates
as the flat list.

**Verification evidence** (`web/packages/app`):
- `pnpm exec vitest run tests/devices.test.ts
tests/settings-devices-sections.test.ts tests/settings-dialogs.test.ts
tests/command-palette.test.ts tests/remote-access-view.test.ts` →
60/60 passed (seams + every suite importing the touched modules).
- `pnpm exec vitest run` (full app suite) → **154 files, 2289/2289
passed**.
- `pnpm exec tsc --noEmit` → clean.
- TDD: partition unit tests and the mounted split tests were written red
first and driven green (mounted suite: headers render in order
`["Engines", "This device", "Other devices"]`, badge gone, exact empty
copy, unknown-arm flat list, local-empty section hiding).
