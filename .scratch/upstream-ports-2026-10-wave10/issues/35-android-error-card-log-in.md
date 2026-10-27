# 35 — Android: the error card's Log in starts the first login; Limited-banner reconciliation; card golden (wave-9 18, rest)

**Lane:** 3 Android parity. **Effort:** S–M.

**What to build:** what's left of wave-9 ticket 18 after PR #34 landed
the card rendering (DANGER_WASH/DANGER_SOFT, `row-notice`,
`WidgetKind.Action` capsule; `TranscriptActions.perform("sign-in")`
pushes the Accounts page focused on the chat's agent):
- **Log in starts the agent's first login on arrival**, the iPhone's
  `signIn: true` and the desktop's `start_first_login`
  (`settings/accounts.rs:896`). Use ticket 34's flow in a medium/large
  sheet.
- **Reconcile with Roboco's Limited banner** (orchestration v2). The
  banner owns the failure-driven presentation. Keep the engine contract,
  and make sure a chat that is both Limited and signed out doesn't show
  two competing calls to action.
- **Error-card golden.**

**Upstream:** `f30a8641b` (#967) iOS parts:
`Session/SessionViewController.swift:89-99` (behaviour spec).

**Roboco files:** `apps/android/.../transcript/TranscriptActions.kt`,
`apps/android/.../accounts/*`, the Limited-banner composable, goldens.

**Excluded:** iOS code.

**Proto change:** no.

**Blocked by:** 34.

**Status:** ready-for-agent

**Source:** wave-9 `issues/18-android-error-cards-delete.md` 2026-10-28
comment; `pass2-features-android.md` KNOWN table.

- [ ] Tapping Log in on a signed-out card starts that agent's login (flow test)
- [ ] Limited + signed-out shows one coherent call to action (unit test on the presentation rule)
- [ ] Error-card golden recorded
- [ ] Commit records `f30a8641b` (Log-in part); wave-9 18 is fully absorbed by 33 + 35

## Comments
