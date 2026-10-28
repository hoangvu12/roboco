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

**Status:** done (2026-10-28, `wave10/android`) — Kotlin compiles in CI only

**Source:** wave-9 `issues/18-android-error-cards-delete.md` 2026-10-28
comment; `pass2-features-android.md` KNOWN table.

- [x] Tapping Log in on a signed-out card starts that agent's login (flow test opens the page; the start is a JVM test — see comments)
- [x] Limited + signed-out shows one coherent call to action (unit test on the presentation rule)
- [ ] Error-card golden recorded (not possible on CI any more — see comments)
- [x] Commit records `f30a8641b` (Log-in part); wave-9 18 is fully absorbed by 33 + 35

## Comments

2026-10-28 (wave10/android, lane 3): carried `f30a8641b` (#967)
`Session/SessionViewController.swift:89-99` (the error card's Log in,
`AgentAccountsViewController(signIn: true)`) by intent.

- `AgentAccountsRoute.signIn`: the page starts the focus agent's first
  login option (`AgentAccountsModel.startFirstLogin`, ticket 34's flow)
  as soon as its accounts are in — the iPhone's `signIn: true`, the
  desktop's `start_first_login`.
- Presentation rule `errorCardLogInRoute(host, harness, limited)`: a
  Limited chat's banner (orchestration v2) owns the failure-driven
  recovery — Resume, Resume on another account (which lists signed-out
  logins), Resume at reset — so there the card's Log in only opens the
  agent's accounts and never starts a second, competing sign-in. The
  engine contract (`provider_problem`, the card's `sign-in` action) is
  unchanged.
- Deviation: iOS presents the accounts in a medium/large sheet; Android
  keeps the pushed page (the usage chip's route), which already exists
  and is what the back stack expects.
- Tests: `AccountSignInTest.logInStartsTheFirstLoginUnlessTheChatIsLimited`,
  `startFirstLoginRunsTheAgentsFirstOptionOnce`; instrumented
  `SessionFlowTest.errorCardLogInOpensTheAgentsAccounts` (the mock's
  `/error Not logged in` on `chat-preview` → the card → Accounts & Usage;
  the mock agent signs in to nothing, so no login starts there).
- Golden: the CI record flow (`android-screenshots`/`record_goldens`)
  was dropped in `33e5590da`, so no error-card golden can be recorded.
  The card's layout is pinned by `crates/mobile/src/layout/tests.rs`
  (the notice row's `("sign-in", "Log in")` action) and the flow test
  saves `error-card.png` as an artifact.

2026-10-28 (PR #38 review fixes, one batch):
- Log in starts only once the accounts **and** the computer's agents are
  in, and only for an agent with a section, so its waiting row (with Tap
  to cancel) can always show; leaving the page still cancels.
  Test: `AccountSignInTest.startFirstLoginWaitsForAWaitingRowThatCanShow`.
