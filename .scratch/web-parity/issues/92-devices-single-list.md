# 92 — Devices settings: one list per engine

**What to build:** Port zeron `779cc2e0`: remove duplicate `PairedEngineHostRow` + merged device double-list; one device-style row per engine via `fleetDeviceRows` (Forget / Pair again on row). Keep roboco pairing URL box (not WorkOS sign-in).

**Blocked by:** **87**

**Status:** ready-for-agent

**Zeron ref:** `routes/settings-devices.tsx`, `tests/settings-devices.test.ts`

**Acceptance checklist (added with the port; ticked items demonstrated by the suite below, not by a two-live-engine run):**

- [x] Two paired engines render exactly two device rows — one per engine via `fleetDeviceRows`, no second engine list above them (regression-guarded: a re-added engines card fails the test).
- [x] No extra "Roboco web on …" engine card block: the pair-session label never renders anywhere on the page; host rows carry the device row's name of record.
- [x] Engine actions stay on the host row: a parked engine shows "Pair again" (routes to `/pair`) and Forget (confirm-in-place) removes the engine from the fleet.

## Comments

### Implementer note (2026-10-08)

Ported zeron `779cc2e0` (PR #526) on top of integration base `1244fa69`;
one commit on `webparity-92-devices-single-list`.

**The IA was already in the base — the port's delta is the remainder of
the commit's intent.** Zeron's change removes the pairing-era "Engines"
card (ticket 45's folded drawer row) that listed every fleet engine again
above the device rows. Roboco's base no longer had it: `05faf5a0`
("unify engine identity and settings device selection") already deleted
that card and folded the engine actions into the device-style
`EngineHostDeviceRow` (tile + name + meta + "Active engine" badge / "Pair
again" / Forget), ticket 87 landed the `fleetDeviceRows`/`fleetHostDeviceIds`
dedup core, and ticket 91 renamed the host rows through
`settingsDeviceName`. No `PairedEngineHostRow` exists in roboco; nothing
was left to delete in the render tree. What actually landed:

- `web/packages/app/src/routes/settings-devices.tsx` — the page doc
  comment still described the removed engines card ("Above the device
  rows sits the engines card … connection state dot + label …") and the
  pre-05faf5a0 local-row wording (`engineInfo.deviceId`); rewritten to
  zeron's post-commit shape: "One row per engine, period", host rows from
  the fleet registry's projected device list, pairing URL box kept
  (zeron's WorkOS sign-in hint explicitly not ported — roboco pairs by
  URL). Code behavior unchanged.
- `web/packages/app/src/styles/app.css` — the commit's rhythm fix:
  `.settings-pairing-box` gains `margin-bottom: 16px`. In zeron the
  device-list card directly follows the pairing box once the section
  header leaves, so the two cards touched; in roboco the same shape is
  the flat pre-identity arm (`partitionDevices`' `unknown` fallback,
  where the device card immediately follows the pairing box). The
  sectioned ("This device"/"Other devices") arm is visually unchanged —
  the pairing box's 16px bottom margin collapses against the section
  header's 28px top margin between siblings. Verified by inspection;
  CSS layout is not jsdom-observable.
- NEW `web/packages/app/tests/settings-devices.test.ts` — the mounted
  reproducer zeron added with the commit, adapted to roboco's
  architecture and this ticket's acceptance shape: TWO engines over one
  device each (zeron's test mounts one), the fleet/registry/session/hooks
  doubled narrowly (real `engineStatesOf`/`fleetLocalDeviceId` via
  `...actual` spread, per the settings-devices-sections idiom). Test 1:
  exactly two `.settings-row`s, all device rows (tile/presence dot/name
  of record/id chip), the "This device"/"Other devices" split, exactly
  one "Active engine" badge, and no "Roboco web on" text anywhere (the
  stored engines carry those pair-session labels). Test 2: a parked
  engine's host row rides "Session revoked" on its meta line, "Pair
  again" navigates to `/pair`, Forget confirms in place then calls
  `forgetEngine(engine.baseUrl)`.

**Mutation check:** re-adding an engines card block above the device
rows (the pre-05faf5a0 shape, labels from the fleet store) makes test 1
fail — 4 rows instead of 2 and "Roboco web on" present in the page text —
so the reproducer guards the regression it documents. Reverted after the
check.

**Deviations from zeron 779cc2e0 (deliberate):**

- zeron's reproducer asserts one engine over one device and the row's
  "This device" badge; roboco's base no longer renders that badge (the
  wpn-08 section header replaced it) and this ticket's acceptance is the
  two-engine shape, so the test asserts two engines, the section split,
  and the "Active engine" badge instead.
- zeron replaced the pairing box's content with a WorkOS sign-in hint in
  this commit; not ported — roboco keeps the pairing URL box and its hint
  text untouched (plan: port patterns, not transport).
- zeron's post-commit page is a flat single card (no section split);
  roboco keeps the desktop-parity "This device"/"Other devices" split
  from wpn-08 — untouched.

**Verification:** `pnpm -C web/packages/app exec tsc --noEmit` clean;
focused suites green — `tests/settings-devices.test.ts` (2, new),
`tests/settings-devices-sections.test.ts` (4),
`tests/settings-dialogs.test.ts` (9). Full app suite deferred to the
orchestrator's integration pass, as instructed. /code-review deferred to
the follow-up round.
