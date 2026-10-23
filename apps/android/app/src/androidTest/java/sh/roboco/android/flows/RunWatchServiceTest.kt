package sh.roboco.android.flows

import android.content.ComponentName
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity
import sh.roboco.android.RobocoApplication
import sh.roboco.android.background.RunWatchController
import sh.roboco.android.background.RunWatchService
import sh.roboco.android.notify.NotificationPrefs

/**
 * The run-watching foreground service's presence (android-app#28,
 * CI-only — no emulator in the worker slots), the acceptance's
 * `adb shell dumpsys activity services sh.roboco.android` check, run
 * through UiDevice's shell — now both sides of it, on ticket 30's rig
 * (the pre-rig version could only verify the absent state):
 *
 * - while a run is Working on the **slow** fixture engine the service is
 *   present;
 * - after the last run leaves Working and the 2 min linger (ADR 0010)
 *   runs out, it is gone.
 */
@RunWith(AndroidJUnit4::class)
class RunWatchServiceTest {

    /** The slow engine: a paced run that holds Working for seconds. */
    private val launch = FixtureRule(
        routeFor = { "chat:chat-long" },
        enginesFor = { setOf(FixtureEngine.Slow) },
    )

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val app: RobocoApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as RobocoApplication

    @Test
    fun theServiceIsAbsentWhenNothingIsWorking() {
        // The fixture is idle; a run from an earlier test in this process
        // may still hold the 2 min linger (ADR 0010), so wait it out.
        app.prefs.setStayConnected(false)
        compose.waitUntil(timeoutMillis = RunWatchController.LINGER_MS + 20_000) { !servicePresent() }
        assertFalse("the service must be gone once the linger ran out", servicePresent())
    }

    @Test
    fun theServiceIsPresentWhileWorkingAndGoneAfter() {
        // The rig's engine is paired; notifications must be on (the
        // controller's gate) — grant the permission directly.
        assertTrue(
            "the flow rig's redeemed engine never appeared (start the android_fixture engines)",
            app.clientOrNull()?.engines().orEmpty().isNotEmpty(),
        )
        val prefs = NotificationPrefs.get(app)
        prefs.enabled = true
        device.executeShellCommand(
            "pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS",
        )

        // Drive a slow run from the session screen.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick()
            .performTextInput("Write a long reply please.")
        compose.onNodeWithTag("composer-send").performClick()

        // While the run is Working the service is up (the run is paced at
        // 150 ms per event — several seconds; poll for the service).
        compose.waitUntil(timeoutMillis = 15_000) { servicePresent() }
        assertTrue("the service must be present while a run is Working", servicePresent())

        // The run settles, the 2 min linger passes, the service is gone.
        compose.waitUntil(timeoutMillis = RunWatchController.LINGER_MS + 45_000) { !servicePresent() }
        assertFalse("the service must be gone after the last run leaves Working", servicePresent())
    }

    /**
     * `dumpsys activity services sh.roboco.android` mentions the service.
     * dumpsys prints the component in its SHORT form
     * (`sh.roboco.android/.background.RunWatchService`), which never contains
     * the fully-qualified class name the old check searched for: the service
     * ran (the system logged its FGS start) but this check never saw it
     * (run 38567067554), and the "absent" test passed vacuously. Both forms
     * are matched.
     */
    private fun servicePresent(): Boolean {
        val dump = device.executeShellCommand(
            "dumpsys activity services sh.roboco.android",
        )
        val component = ComponentName(app, RunWatchService::class.java)
        return dump.contains(component.flattenToShortString()) || dump.contains(component.flattenToString())
    }
}
