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

**Status:** ready-for-agent. Golden re-capture uses the
`record_goldens` dispatch (a human triggers it on GitHub) and the G1–G5
parity gates (android-app#30).

**Source:** wave-9 `issues/19-android-bottom-bar.md`; `2026-10-28.md` §C;
`part-06.md` `f30a8641b`/`4cd159175`.

- [ ] The bar is one row; Search filters in place; Settings pushes; no tab bar can come back (#971 test)
- [ ] Toasts anchor above the bar
- [ ] Sessions goldens re-captured; parity gates green
- [ ] Commit records `f30a8641b` + `4cd159175` (iOS bar parts); absorbs wave-9 19

## Comments
