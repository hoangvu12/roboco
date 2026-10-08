# 88 — Composer engine/project chips (not settings-only)

**What to build:** Port zeron `4510bab2` + `4928e1b2`: `DeviceCard` uses `targetForDevicePick` / `targetForProjectPick`; labels **Select engine** / **Selected engine** (not "This device"/"You"); `engineStatesOf(registry)` for presence; `ProjectChip` gets `currentDeviceId`.

**Blocked by:** **86** (target helpers)

**Status:** ready-for-human

**Zeron ref:** `composer-footer.tsx`, `new-thread-selectors.tsx`, `tests/browser-engine-picker.test.ts`

**Roboco files:** same paths under `web/packages/app`

**Acceptance checklist (added with the port; ticked items demonstrated by the suites below, not by a two-live-engine run):**

- [x] Picking engine B on the canvas switches the routed session WITHOUT opening Settings — the canvas chip press writes `composerDefaults` through `targetForDevicePick` (`browser-engine-picker`), and the mounted provider routes the session from that write (`session-provider`'s "updates the mounted session as the composer chip remembers engine B", landed with 86).
- [x] A foreign project is cleared on engine switch — the pick lands `{device: B, project: null, noProject: true}` AND the visible sidebar filter with it (`rememberNoProject`), while `lastSpaceId` stays navigation state; the chips relabel to "Engine B" / "No project".
- [x] Labels: the device card tags the EFFECTIVE row "Selected engine" (never "You"/"This device"); the canvas's no-host state reads "Select engine"; presence comes from the real `engineStatesOf(registry)` (connected engine online, absent engine falling to last-seen → exactly one offline glyph).
- [x] Picking a project routes to its OWNING engine (`targetForProjectPick`, pointer + same-owner keyboard retention).
- [x] "Don't work in a project" keeps the engine the chips SHOW (`currentDeviceId`), never the remembered device of a stale host.

## Comments

### Implementer note (2026-10-08)

Ported zeron `4928e1b2` + `4510bab2`'s composer files on top of the
integration base (`1244fa69`, tickets 86 + 87 + 91 landed) — one commit,
`301bef26`, on `webparity-88-composer-engine-chips`.

**What was already there (do not re-port):** the base already carried most
of `4510bab2`'s composer side and all of 86's policy — `fallbackLabel =
"Select engine"` (chip + canvas), the "Selected engine" row tag keyed on
`effectiveDeviceId`, `ownDeviceId` no longer passed to `DeviceCard`,
`engineStatesOf(useFleetRegistry())` presence, and `pickSpace` already
writing `targetForProjectPick`. The ticket's remaining delta was exactly
`4928e1b2`'s wiring:

- `DeviceCard.pick` now branches on `targetForDevicePick`'s noProject
  result — a projectless engine switch goes through `rememberNoProject`
  (clears the visible sidebar filter; last-project history remains
  navigation state), a same-owner project is retained via
  `rememberTarget`.
- `ProjectChipProps`/`ProjectCard` take `currentDeviceId` (the resolved
  engine the chips show); `pickNoProject` prefers it over the remembered
  `composerDefaults.device` so opting out never silently restores an
  older host. The draft footer passes `effectiveDeviceId`
  (`space?.deviceId ?? ownDeviceId`); the canvas selector row passes
  `target.effectiveDeviceId`.
- Comment ports from `4510bab2`: `DeviceChipProps.fallbackLabel` doc
  ("Browser-safe label when no execution engine is known"), the
  `render_new_thread_target_selectors` doc ("engine name or
  \"Select engine\"").

**Tests (TDD — the two new reconciliation cases were confirmed failing
at base first):** NEW `tests/browser-engine-picker.test.ts` (11 tests —
10 from the port plus the review round's keyboard no-project case), the
zeron `4510bab2` suite ported onto roboco's mounted-suite idioms and
extended with the canvas pick interactions:

- identity (both viewports — phone sheet carries the same rows): the
  "Selected engine" tag, never "You"/"This device"; one offline glyph
  with the REAL `engineStatesOf` over a doubled registry (engine A
  connected, engine B falling to its 3-day-old last-seen); canvas
  no-host label "Select engine".
- canvas reconciliation (desktop): engine A→B clears the foreign project
  + sidebar filter (RED at base — the filter survived; fixed by the
  `rememberNoProject` branch); no-project keeps the visible engine (RED
  at base — the target flipped to the remembered stale host; fixed by
  `currentDeviceId`); project pick routes to the owning engine and
  keyboard same-owner retention (green at base — pins 86's policy).

**Verification (at `301bef26`):** `pnpm -C web/packages/app exec tsc
--noEmit` clean (base clean); focused vitest green —
`browser-engine-picker` 10/10, `new-chat-target` 5/5,
`session-provider` 11/11 (86's routing), `new-thread-git-selectors`
5/5, `composer-footer-git` 11/11, `composer-draft` 32/32. Full app
suite and the plan-level two-live-engine run deferred to the
orchestrator's integration pass, per process.

**Deviations from zeron (deliberate):**

- `4510bab2`'s non-composer files (`account-row.tsx`, `lib/accounts.ts`,
  `settings-accounts/agents/devices.tsx` + their tests) are OUT of this
  ticket's declared file scope; roboco still shows "Stored on this
  device" / "this device" copy there. That label sweep belongs to the
  settings lane (91 landed `97f86114`; 92 is open) — flagged for the
  orchestrator, not silently skipped.
- Zeron's `new-chat-selection.test.ts` (the 356-line full-`Composer`
  mount) was not ported whole; its pick-policy coverage is distilled
  into `browser-engine-picker`'s canvas suite at the
  `NewThreadTargetSelectors` surface (the ticket names only
  `browser-engine-picker.test.ts`). The composer-draft/attachment
  preservation cases ride ticket 86's already-landed routing and the
  composer suite's own coverage.
- The canvas interaction suite runs desktop-only (identity suite runs
  both viewports): the pick policy is viewport-free and the phone sheet
  has its own suite (`picker-card-phone`).
- Roboco's `DeviceChip` row ordering stays ticket 87's dedup semantics
  (name/id sort over merged host rows); zeron's own-device-first sort
  was not adopted — not in this ticket's scope.
- `ComposerFooter`'s `ownDeviceId` prop is still passed by callers
  (roboco keeps the prop; the tag no longer reads it) — matches zeron's
  post-`4510bab2` shape minus the sort use.

### Review round (2026-10-08)

Two-axis review over `git diff 1244fa69..HEAD` (commits `301bef26` +
`8ec91e89`; the code-review skill's subagent machinery is unavailable
in this session, so both axes were performed directly — ticket 91's
recorded fallback).

**Standards — 1 actionable finding, fixed in the review commit
(`fix(web): guard the engine pickers' keyboard activation in tests`):**

- `browser-engine-picker.test.ts`'s keyboard-retention case pre-seeded
  `composerDefaults` with the pick's exact result — it pinned the
  retention policy but passed vacuously against a broken Enter
  activation path (`useCursorList` → `onActivate` → `pick`), which no
  other test in the file exercised with a state change. The pick RUNNING
  is now observable (the trigger's `aria-expanded` collapses — the
  repo's established trigger assertion, account-row/changes-surface),
  and zeron's keyboard no-project case (ArrowDown + Enter) landed as an
  11th test covering the project card's keyboard path with a real state
  transition. Mutation-verified: killing either card's cursor list
  (`enabled: false`, reverted) fails exactly the corresponding keyboard
  test.

Non-actionable, noted: the per-file jsdom stub block duplicates the
repo convention (91's review already ruled it convention);
`mountCanvas`'s one-line wrapper is the named-seam idiom; the identity
suite's `!` non-null assertions are zeron-verbatim with the guarded
idiom on the critical paths.

**Spec — no findings.** All ticket-88 requirements present at their
zeron call sites (the footer wiring matches zeron line-for-line:
`effectiveDeviceId = space?.deviceId ?? ownDeviceId` feeding
`currentDeviceId`; no residual "This device"/"You" strings in the
composer scope); no scope creep beyond the process-required ticket
docs; ticket 86's closure is NOT invalidated — the routing module and
session-provider are untouched by the diff and their suites are green.

**Post-fix verification:** `pnpm -C web/packages/app exec tsc --noEmit`
clean; focused vitest green — `browser-engine-picker` 11/11,
`new-chat-target` 5/5, `session-provider` 11/11,
`new-thread-git-selectors` 5/5, `composer-footer-git` 11/11,
`composer-draft` 32/32 (75/75). Both acceptance criteria remain
demonstrated; status → ready-for-human. 86 stays ready-for-human.
Full app suite and the plan-level two-live-engine run remain with the
orchestrator's integration pass.
