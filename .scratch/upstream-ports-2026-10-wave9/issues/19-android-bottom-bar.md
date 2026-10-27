# 19 — Android: one-row bottom bar (upstream #967 + #971, `f30a8641b` + `4cd159175`)

**What to build:** 1:1 iOS-parity port of the bottom-bar redesign: the tab
bar (roboco's `shell/GlassTabBar.kt`) is replaced by a single row of
toolbar items on the Sessions list — Settings, Search (filtering the list
in place), voice, prominent New session. Settings pushes over the list; the
bar belongs to the front page alone and steps aside for sessions and
folders. #971's fix rides it: the shell container keeps no tab bar that
UIKit could bring back (in roboco: no leftover nav scaffolding that
resurrects the old bar).

**Port note:** this re-captures the sessions goldens — fresh iOS reference
captures, `record_goldens` dispatch, G1–G5 gates (android-app#30).

**Blocked by:** 18 (shared sessions screen + goldens; error cards land first).

**Status:** ready-for-agent

**Verification budget:** screenshot tests + parity gates after golden
re-capture. Source: `.scratch/upstream-drift/2026-10-25.md` § D-a.

- [ ] Bottom bar ported 1:1, tab bar gone (incl. #971's no-tab-bar shell)
- [ ] Sessions goldens re-captured + parity gates green
- [ ] Commit records upstream SHAs `f30a8641b` + `4cd159175` (#967/#971)

## Comments
