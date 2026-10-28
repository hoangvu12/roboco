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
