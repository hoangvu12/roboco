# 22 — Android flow suite: the remaining phone flakes and two hygiene fixes

**What to build:** With the room (5cabff4bb) and preview (5843e3444) fixes on main, the phone `android-emulator` suite's remaining red is intermittent, and each one turns a whole ≈ 35-min run red (ticket 11 triage):

- `KeyboardFlowTest.interactiveDismissClosesPastHalfAndReopensAboveIt`: 10 s `waitUntil` timeout (39919545157, 40256496025, 40545477841).
- `SessionFlowTest.newSessionFromAccessory`: `CalledFromWrongThreadException`, a view touched from `DefaultDispatcher-worker` (39881479628, 39889382804). Looks like a real UI-thread bug in the accessory's new-session path.
- Compose-harness errors in `LiveEngineRoundTripTest` (40427324853), `WallpaperSettingsFlowTest` (40256510722) and `RunWatchServiceTest`.
- Hygiene: `SessionMenuTest.copyBuildsTheTextOffMain…` compares an exact thread name, and coroutine debug mode can append ` @coroutine#N` (39884973551). Nothing stops a stray `apps/android/?/` directory (it broke Windows checkout: 40472984016, 40473003282) from being committed again.

Decision (ticket 11): no quarantine. A small JUnit retry rule applies only to the named flaky tests, so PRs keep end-to-end coverage of the keyboard and new-session paths.

**Blocked by:** none

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md` (from ticket 11)

- [ ] `newSessionFromAccessory`'s root cause is fixed, or disproven with evidence
- [ ] `KeyboardFlowTest` uses a deterministic wait, or carries the retry rule with a reason
- [ ] The retry rule exists, is opt-in per test, and logs when a retry happened
- [ ] `SessionMenuTest` checks the thread name with `startsWith`
- [ ] `.gitignore` covers `/?/` (and `apps/android/?/`)
- [ ] 10 consecutive green `android-emulator` runs on main

## Comments

### 2026-10-29 — diagnosis (branch `ci/android-emulator-speed`)

Read from the `android-flow-artifacts` HTML reports and `logcat` of 40709013739, 40739293146, 40745813713, 40755175375 (plus 40545477841, 40256496025, 39919545157 for the keyboard test).

**`newSessionFromAccessory`: root cause found; it is the test harness, not the app.** Both failure forms have the same stack: `CalledFromWrongThreadException … Calling: DefaultDispatcher-worker-6` (40709013739) and `Detected multithreaded access to SnapshotStateObserver … DefaultDispatcher-worker-2/4` (40739293146, 40755175375).

- The stack: `PhoneShellAdapter.hideIme` (`FocusOwner.clearFocus`) ← `PhoneAppRouter.openSession` handoff lambda ← `PhoneAppRouter.resolve`'s coroutine, resumed by `kotlinx.coroutines.DispatchedCoroutine.afterResume` → compose-ui-test's `FrameDeferringContinuationInterceptor` → `ApplyingContinuationInterceptor`, on the IO worker that finished `openSessionRoute`'s `withContext(Dispatchers.IO)`.
- Every instrumented test built its rule with the deprecated v1 `AndroidComposeTestRule(activityRule, activityProvider)` constructor. That constructor runs composition, and so every `rememberCoroutineScope`/`LaunchedEffect`, on an `UnconfinedTestDispatcher`: a coroutine resumes inline on the thread that completed its suspension. In the app the same scopes run on `AndroidUiDispatcher.Main`, so production is not affected.
- The same mechanism explains the other "compose harness" errors in the triage: `performMeasureAndLayout called during measure layout` thrown by the **main** thread's draw while another thread was inside a layout pass (`cancelledSwipeBackKeepsStreaming`, 40745813713; `WallpaperSettingsFlowTest`, 40256510722). `TranscriptState.mainChoreographer` already documents a recomposition on such a worker (CI 88b067d0).
- Fix: `flows/FlowComposeRule.kt`'s `flowComposeRule(...)` = the **v2** `androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule`, used by all 12 instrumented compose test classes. v2 runs composition on a `StandardTestDispatcher`, and the test clock drains it inside `runOnUiThread` (`AbstractMainTestClock.runCurrent`/`advanceScheduler`), so effect coroutines resume on main, as in the app.
- `MarkdownImageLabTest` used the same v1 constructor (custom `ActivityScenarioRule`); it gets the same fix. Its one failure was not in any report I could still download (artefacts expire after 14 days), so I could not confirm the stack.

**`KeyboardFlowTest.interactiveDismiss…`: not explained.** All three phone failures time out at the 10 s wait for the capsule (`composer-chip-model` gone; `KeyboardFlowTest.kt:87` at the time). In 40545477841's logcat, the IME is hidden at 17:15:41.927 (`requestedVisibleTypes -9`). Then nothing happens until the timeout at 17:16:00: no exception, no focus change. The blur that collapses the composer is a `LaunchedEffect` in `ComposerMorph` keyed on `WindowInsets.isImeVisible`. Under the v1 harness that effect also ran on the unconfined dispatcher, so the v2 rule may cure it too, but nothing proves that. The wait already waits on the outcome itself, so there is no deterministic wait to swap in. It gets the retry (below), with its reason.

**Retry (separate commit, awaiting the user's OK):** `apps/android/scripts/flaky-retry.txt` lists opted-in tests with a reason; `flow-tests.sh` retries a failed test once, alone, in a fresh orchestrated process, and only when every failed test case is listed. A lost device fails no test case and never retries. Each retry prints a `::warning::` and a job-summary line; its report goes to `flow-artifacts/retry-<n>/`. Deviation from "JUnit retry rule": an in-process rule cannot re-evaluate the compose rule (its `TestScope.runTest` runs once) and would keep the first attempt's app data, while the suite's isolation (orchestrator + `clearPackageData`) is per process. Listed: only `KeyboardFlowTest.interactiveDismissClosesPastHalfAndReopensAboveIt`. `newSessionFromAccessory` is not listed, because its cause is fixed.

**Hygiene:** `SessionMenuTest` checks `startsWith("session-menu-copy")` (the message prints the actual name). `.gitignore` has `/\?/` and `apps/android/\?/`, escaped because a bare `?` matches any one character. `git check-ignore --no-index` matches `?/.android/analytics.settings` and `apps/android/?/.android/analytics.settings`, and not `a/b` or `apps/android/a/x`.
