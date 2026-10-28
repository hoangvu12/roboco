# 23 — Tablet suite: run only what applies to a tablet, with its full rig

**What to build:** `android-emulator-tablet` has never been green. It fails the same 11 tests on every run (ticket 11 triage, 12/12 runs, latest main 40589753504), while the `@Tour` recordings and `TabletLayoutTest` pass, so the tablet shell itself works:

- `TailcatPairingFlowTest.aTailcatInvitePairsAndTheSessionsLoad`: the job never builds the Tailcat adapter or DERP relay ("Build the Tailcat smoke pieces" exists only in the phone job, `android.yml` ~420-480), and `RobocoTestRunner` (`RobocoTestRunner.kt:15-17`) reports the assumption failure as a failure.
- 9 phone-shaped `SessionFlowTest` cases (`cancelledSwipeBackKeepsStreaming`, `…_gesture`, `forgottenEngineChatRouteReturnsToSessions`, `unknownChatRouteReturnsToSessions`, `longDraftRestsAsWholeLinePreview`, `queueSurvivesNavigatingAway`, `toolGroupsRenderLikeDesktop`, `searchTabRoundTrip`, `tabsAndSearch`) assume a single-pane stack, tab bar and accessory.
- `KeyboardFlowTest.interactiveDismiss…`: the IME-hide wait times out at 1 s on tablet (`KeyboardFlowTest.kt:77`); cause unknown.

User decision (2026-10-28): the tablet suite runs nightly, not per push. It stays non-blocking until it has been green 3 nights. Land the rig and tagging fixes with or right after ticket 08's move.

**Blocked by:** none (coordinate with 08, which moves the job to nightly)

**Status:** implemented on `ci/linux-android-tiers` (with 06 and 08, one PR); verified by a dispatched tablet run on the branch (below); 3 green nights after merge outstanding

**Parent:** `.scratch/ci-speed/spec.md` (from ticket 11)

- [ ] The tablet job builds the Tailcat pieces, or the runner excludes rig-dependent tests when the rig is absent
- [ ] Every phone-only test is tagged (e.g. `@PhoneOnly`, excluded at ≥ 600 dp in `RobocoTestRunner`) or made shape-aware; a failure that turns out to be a real tablet bug gets its own ticket
- [ ] `KeyboardFlowTest` is fixed on tablet, or tagged phone-only with the reason
- [ ] 3 consecutive green nightly tablet runs

## Comments

**2026-10-29 (implementation, branch `ci/linux-android-tiers`):**

The failing set moved since the triage. Wave-10 37's one-row bottom bar (PR #38) renamed `searchTabRoundTrip`/`tabsAndSearch` to `bottomBarSearch`/`settingsFromBottomBar`, and `modelPickerInSession` is new. The latest main tablet run, **40725884775** (main 3395065c0, 2026-10-28), failed **12 of 58**: the Tailcat smoke, KeyboardFlowTest, and 10 SessionFlowTest cases. `TabletLayoutTest`, the tours and the other 46 tests passed. Failure messages were read from the `android-tablet-artifacts` HTML report.

| test | failure on the split | classification | change |
|---|---|---|---|
| `TailcatPairingFlowTest.aTailcatInvitePairsAndTheSessionsLoad` | `AssumptionViolatedException: the flow rig is absent` (`FixtureRule.kt:117`) | rig missing in the job | tablet job builds the Tailcat adapter + local DERP relay (same step as `android-emulator`), so `flow-tests.sh` starts the Tailcat fixture and passes `fixtureTailcat` |
| `KeyboardFlowTest.interactiveDismissClosesPastHalfAndReopensAboveIt` | 1 s `waitUntil { !imeVisible() }` (`:77`) | **test geometry**: the drags and the tap ran at x = 8 dp from the *window's* left edge, which on the split is the sidebar, so the drag never reached the IME | x is now 8 dp inside the `transcript` node's left edge (`getBoundsInRoot`): the same x on a phone (the transcript starts at 0), the session column on the split. Not tagged |
| `SessionFlowTest.settingsFromBottomBar` | `bottom-settings` never appears (10 s) | phone-only: the one-row bar is `MainTabShell`'s; the split has its sidebar toolbar | `@PhoneOnly` |
| `SessionFlowTest.bottomBarSearch` | no `search-field` node | phone-only (same bar) | `@PhoneOnly` |
| `SessionFlowTest.longDraftRestsAsWholeLinePreview`, `queueSurvivesNavigatingAway`, `toolGroupsRenderLikeDesktop` | "No compose hierarchies found" | phone-only: Back pops the session to the list on the phone stack; on the split Back finished `MainActivity` | `@PhoneOnly` |
| `SessionFlowTest.cancelledSwipeBackKeepsStreaming` (+ `_gesture`, `@Tour`) | "no New session accessory over the session" | phone-only: asserts no displayed `new-session` while a session is open; the split shows New session in the sidebar beside it | `@PhoneOnly` |
| `SessionFlowTest.modelPickerInSession` | `model-picker` still present 5 s after the pick (`:862`) | **unknown, possibly a real tablet bug** | `@PhoneOnly("suspected tablet bug, ci-speed ticket 25: …")` + ticket **25** |
| `SessionFlowTest.unknownChatRouteReturnsToSessions`, `forgottenEngineChatRouteReturnsToSessions` | a `transcript` still present 2 s after the failed open (`:1045`) | **unknown** (the split's detail column may keep a chat on purpose, or a failed open presents one) | same, ticket **25** |

Mechanism: new `flows/PhoneOnly.kt`, `annotation class PhoneOnly(val reason: String)`, so every tag says why. `RobocoTestRunner` adds it to `notAnnotation` at a ≥ 600 dp smallest width, the mirror of `@TabletOnly`. The phone job is unaffected: below 600 dp nothing new is excluded. The Tour/TransitionTourTest KDocs now say "nightly/dispatch".

Not done here (outside the ticket's rows): `TabletLayoutTest.sidebarOpensSessionsBesideIt`'s 5 s flake (5/12 in the triage) was green in 40725884775; watch it in the nightlies. A tablet-shaped variant of the three Back-based flows (switch rows in the sidebar instead of Back) would bring that coverage to the split; ticket 25 can pick it up if wanted.
