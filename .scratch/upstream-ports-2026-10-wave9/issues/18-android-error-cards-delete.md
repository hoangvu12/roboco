# 18 — Android: error cards + session delete (upstream #967 iOS parts, `f30a8641b`)

**What to build:** 1:1 iOS-parity port (ADR 0009) of the iPhone app's #967
gains into roboco's `apps/android`:
- **Red alert error cards** ("soft red wash, lighter red text") in the
  transcript, with the Log in action — rendered through the mobile layout
  rows ported in ticket 14; roboco's sign-in opens the engine-local
  accounts sheet.
- **Session delete** from the Sessions list long-press menu and the ⋯ menu
  (with the desktop's confirmation); Archive no longer styled destructive.
- Session title + "project @ device" move into the ⋯ menu header — adapted
  to roboco's single paired-engine naming.

**Port note:** roboco's Limited banner (orchestration v2) owns the
failure-driven presentation — reconcile the error-card look with it, keep
the engine contract. Delete must exist engine-side (desktop already has it).

**Blocked by:** 14 (run_errors wire + layout rows).

**Status:** ready-for-agent

**Verification budget:** app unit tests; a golden for the error card.
Source: `.scratch/upstream-drift/2026-10-25.md` § D-a.

- [ ] Error cards + delete + menu header ported 1:1
- [ ] Commit records upstream SHA `f30a8641b` (#967, iOS parts)

## Comments

- 2026-10-28 (PR #34 CI fixes, "Android: render wave 9's error-card
  roles…"): the error card's **rendering** landed so the app compiles
  against ticket 14's core — DANGER_WASH/DANGER_SOFT (Palette.swift:96-97
  values), the `row-notice` tag, and `WidgetKind.Action` as a DANGER_SOFT
  capsule button (a control: no row menu, no composer dismiss).
  `TranscriptActions.perform("sign-in")` pushes the Accounts & Usage page
  focused on the chat's agent. Still open here: starting the agent's first
  login on arrival (the desktop's `start_first_login`, iOS's
  `signIn: true`), the Limited-banner reconciliation, the error-card
  golden, session delete and the ⋯ menu header.
