# 23 — Tablet suite: run only what applies to a tablet, with its full rig

**What to build:** `android-emulator-tablet` has never been green. It fails the same 11 tests on every run (ticket 11 triage, 12/12 runs, latest main 40589753504), while the `@Tour` recordings and `TabletLayoutTest` pass, so the tablet shell itself works:

- `TailcatPairingFlowTest.aTailcatInvitePairsAndTheSessionsLoad`: the job never builds the Tailcat adapter or DERP relay ("Build the Tailcat smoke pieces" exists only in the phone job, `android.yml` ~420-480), and `RobocoTestRunner` (`RobocoTestRunner.kt:15-17`) reports the assumption failure as a failure.
- 9 phone-shaped `SessionFlowTest` cases (`cancelledSwipeBackKeepsStreaming`, `…_gesture`, `forgottenEngineChatRouteReturnsToSessions`, `unknownChatRouteReturnsToSessions`, `longDraftRestsAsWholeLinePreview`, `queueSurvivesNavigatingAway`, `toolGroupsRenderLikeDesktop`, `searchTabRoundTrip`, `tabsAndSearch`) assume a single-pane stack, tab bar and accessory.
- `KeyboardFlowTest.interactiveDismiss…`: the IME-hide wait times out at 1 s on tablet (`KeyboardFlowTest.kt:77`); cause unknown.

User decision (2026-10-28): the tablet suite runs nightly, not per push. It stays non-blocking until it has been green 3 nights. Land the rig and tagging fixes with or right after ticket 08's move.

**Blocked by:** none (coordinate with 08, which moves the job to nightly)

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md` (from ticket 11)

- [ ] The tablet job builds the Tailcat pieces, or the runner excludes rig-dependent tests when the rig is absent
- [ ] Every phone-only test is tagged (e.g. `@PhoneOnly`, excluded at ≥ 600 dp in `RobocoTestRunner`) or made shape-aware; a failure that turns out to be a real tablet bug gets its own ticket
- [ ] `KeyboardFlowTest` is fixed on tablet, or tagged phone-only with the reason
- [ ] 3 consecutive green nightly tablet runs

## Comments
