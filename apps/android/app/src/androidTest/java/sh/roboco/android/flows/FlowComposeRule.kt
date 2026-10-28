package sh.roboco.android.flows

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import org.junit.rules.TestRule

/**
 * The compose rule of every instrumented test that hands the rule its own
 * activity rule ([FixtureRule], an `ActivityScenarioRule`): the **v2**
 * `AndroidComposeTestRule`, whose composition runs on a
 * `StandardTestDispatcher` that the test clock drains on the main thread.
 *
 * The deprecated v1 constructor ran composition on an
 * `UnconfinedTestDispatcher`, so a `rememberCoroutineScope` /
 * `LaunchedEffect` coroutine resumed on whichever thread finished its
 * suspension. After a `withContext(Dispatchers.IO)` that was an IO
 * worker, and the app's main-only code ran there (ci-speed 22):
 * `PhoneAppRouter`'s handoff cleared focus off main
 * (`newSessionFromAccessory`: `CalledFromWrongThreadException`, or
 * "multithreaded access to SnapshotStateObserver"), and recomposition and
 * layout raced the main thread ("performMeasureAndLayout called during
 * measure layout", the `TranscriptState.mainChoreographer` note). The app
 * itself runs those scopes on `AndroidUiDispatcher.Main`, so the bug was
 * the harness's, not the app's.
 */
fun <R : TestRule, A : ComponentActivity> flowComposeRule(
    activityRule: R,
    activityProvider: (R) -> A,
): AndroidComposeTestRule<R, A> =
    androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule(activityRule, activityProvider)
