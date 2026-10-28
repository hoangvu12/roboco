# 37 — Android: one-row bottom bar, no tab shell (wave-9 19, #967 + #971)

**Lane:** 3 Android parity. **Effort:** M (golden re-capture).

**What to build:** Carried unchanged from wave-9 ticket 19. It's a 1:1
port of the iPhone redesign. The tab bar (`shell/GlassTabBar.kt`,
`shell/MainTabShell.kt`) is replaced by a single row of toolbar items on
the Sessions list: Settings, Search (filters the list in place), and a
prominent New session. Settings pushes over the list. The bar belongs to
the front page alone, and steps aside for sessions and folders.
#971 rides it: no leftover nav scaffolding that brings the old bar back.
Toasts anchor over the new bar (upstream `Toast.swift`, #967).

**Upstream:** `f30a8641b` (#967) + `4cd159175` (#971) iOS parts
(behaviour spec).

**Roboco files:** `apps/android/.../shell/{MainTabShell,GlassTabBar,AppRouter,AppRoot}.kt`,
`apps/android/.../sessions/*`, `design/Toast.kt` (anchor),
`apps/android/app/src/test` goldens.

**Excluded:** the voice item on the iPhone bar (voice is dropped, ADR
0009). iOS code.

**Proto change:** no.

**Blocked by:** 33 (shared sessions screen + goldens).

**Status:** done (2026-10-28, `wave10/android`) — Kotlin compiles in CI
only; goldens not re-captured (see comments).

**Source:** wave-9 `issues/19-android-bottom-bar.md`; `2026-10-28.md` §C;
`part-06.md` `f30a8641b`/`4cd159175`.

- [x] The bar is one row; Search filters in place; Settings pushes; no tab bar can come back (#971 test)
- [x] Toasts anchor above the bar
- [ ] Sessions goldens re-captured; parity gates green (no CI record flow any more)
- [x] Commit records `f30a8641b` + `4cd159175` (iOS bar parts); absorbs wave-9 19

## Comments

2026-10-28 (wave10/android, lane 3): carried `f30a8641b` (#967) and
`4cd159175` (#971) iOS bar parts by intent.

- `SessionsBottomBar` (new): one 48 dp row — Settings (`bottom-settings`),
  the search field (ticket 29's `SearchTabField` at its settled search
  shape) and a prominent filled New session (`new-session`, the iOS id).
  No voice item (voice dropped, ADR 0009); the old accessory's live
  counts went with the accessory, as on iOS.
- `MainTabShell(oneRowBar = true)` — what `RobocoAppContent` always
  passes: the bar replaces the tab chrome, Sessions is the only stack
  (`PhoneShellAdapter.selectTab` maps Settings → push `SettingsRoute`
  over the list, dismissing a sheet first; Search → front page + activate
  the field), so no tab can be selected and no tab bar can come back.
  System back with a query clears it before leaving.
- "The bar belongs to the front page alone": `NavStack.chromeOnRootOnly`
  + `hidesChrome(entry)`, read by every chrome reader (the slot fade,
  `NavStackHost` layers, ticket 30's `ChromeVisibilityController`); the
  phone turns it on for the Sessions stack, so Settings, folders and
  everything they push hide the bar (iOS `hidesBottomBarWhenPushed`).
- Search filters in place: the Sessions root gets the field's query
  (`LocalBottomBarSearch` → `SessionsScreen(query = …)`, the sidebar's
  in-place results path).
- Toasts: the bar publishes its top through the same
  `ChromeVisibilityController.updateChromeTop`, so toasts sit above it;
  the list padding is the bar's (`oneRowBarContentPadding`).
- Deviation / follow-up: the legacy tab chrome (`GlassTabBar`,
  `TabChromeLayout`, the search morph, `NewSessionAccessory`) stays
  behind `oneRowBar = false` for its existing component tests and
  galleries only; production never mounts it. Deleting it and porting
  those tests is a follow-up (it is ~4 k lines plus their goldens).
- Tests: `OneRowBarShellTest` (one row, no tab cells; Settings pushes,
  the bar steps aside and returns; the root-only chrome policy);
  instrumented `SessionFlowTest.settingsFromBottomBar` /
  `bottomBarSearch` (replacing `tabsAndSearch` / `searchTabRoundTrip`),
  `TransitionTourTest` updated.
- Goldens: CI's `record_goldens` job was dropped in `33e5590da`; no
  Sessions golden re-capture is possible on CI.

2026-10-28 (PR #38 review fixes, one batch):
- A push over the list lets go of the search field's focus and hides the
  IME (`SearchTabField(held = …)`, fed from the Sessions stack's top); only
  the field's own focus is cleared.
- The list's bottom padding under the bar includes the IME the bar rides
  on, so the last filtered results clear the keyboard.
- `OneRowBarShellTest` / `SessionFlowTest.settingsFromBottomBar` check that
  the hidden bar isn't **displayed** (it stays composed, unplaced, #30)
  rather than gone from the tree; new `aPushLetsGoOfTheSearchFieldsFocus`.

2026-10-28 (PR #38 android-emulator device loss): the CI emulator dropped
off adb 7–10 min into the flow suite on every run of this branch, then hung
to the job timeout. A bisect with `workflow_dispatch` runs (flow-tests
diagnostics cherry-picked) put it here: 33 and 35 keep the device (35 twice);
37, 39, 42, 43, 36 lose it. Two experiments on 37 isolate it: the bar switched
off (`oneRowBar = false`) keeps the device, and so does the bar with its
search field replaced by a spacer — the always-composed `BasicTextField`
in the bar is the trigger (it was never composed outside the Search tab
before). Host memory never ran low and no OOM/ANR/crash line was logged;
the guest froze shortly after returning to the front page.

Fix: `SearchTabField(lazyText = true)` for the bar — the real text field
exists only while it is in use (front page, and tapped, focused or holding
a query); otherwise a static placeholder with the same look and the
`search-field` tag stands in, and a tap / focus request brings the field in,
focused. JVM: `OneRowBarShellTest.theBarsTextFieldExistsOnlyWhileInUse`.

Also found by the bisect's one run that reached `bottomBarSearch` (36):
the phone list never filtered — `SessionsScreen` ran the query search only
with the sidebar's pinned accessory. `SessionsScreen(filters = …)` now runs
it for the bar too (`ShellRouteContent` passes `filters = true` under the
one-row bar).
