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

**Status:** ready-for-agent

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

- [ ] Two labeled sections, local first, badge dropped
- [ ] Empty "Other devices" copy = "Pair another device to see it here."
- [ ] Scope gate decided and recorded
- [ ] Tests + full app suite green
