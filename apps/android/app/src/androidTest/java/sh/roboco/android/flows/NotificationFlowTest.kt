package sh.roboco.android.flows

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity
import sh.roboco.android.RobocoApplication
import sh.roboco.android.notify.NotificationPrefs

/**
 * The notification flow (android-app#28, CI-only — no emulator in the
 * worker slots): the Android port of `testNotificationTapOpensSession`
 * (`iosui:apps/ios/ZeronUITests/SessionFlowTests.swift:174-206`), fully
 * local — the edge and APNs are gone, so the notification comes from the
 * phone's own driver:
 *
 * 1. the **slow** fixture engine is paired through the rig
 *    ([FixtureRule] mints a fresh pairing URL; `chat-long` streams a
 *    paced reply — long enough to background the app first);
 * 2. notifications are on (the pref) and the permission granted
 *    (`pm grant`);
 * 3. a run is started from the engine side (the fixture's `/run` — the
 *    core never notifies Done for the phone's own send), then
 *    `pressHome()`;
 * 4. `openNotification()`, wait for "Run finished", tap it, and the chat
 *    is open again (the `transcript` root on top).
 *
 * Run: `./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=sh.roboco.android.flows.NotificationFlowTest`.
 */
@RunWith(AndroidJUnit4::class)
class NotificationFlowTest {

    /** The slow engine (a paced run that outlives the trip to the home screen). */
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

    @Before
    fun setUp() {
        // The rig mints and pairs the slow engine before this runs; a
        // missing engine means the rig itself broke — fail loudly.
        assertTrue(
            "the flow rig's redeemed engine never appeared (start the android_fixture engines)",
            app.clientOrNull()?.engines().orEmpty().isNotEmpty(),
        )
        // Notifications on: the master + every kind.
        val prefs = NotificationPrefs.get(app)
        prefs.enabled = true
        for (kind in sh.roboco.android.notify.NotificationKind.entries) {
            prefs.set(kind, true)
        }
    }

    @Test
    fun notificationTapOpensTheSession() {
        // The chat is open and its state loaded.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        // Granted up front: this test is about the notification, not the
        // first-session ask (whose system dialog would pause the activity).
        device.executeShellCommand(
            "pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS",
        )
        assertTrue("POST_NOTIFICATIONS was not granted", granted())

        // The run comes from the ENGINE side (the fixture's `/run`), not a
        // phone send: by design the core never posts "Run finished" for the
        // phone's own send (crates/mobile-client/src/notify.rs
        // `send_pending`, ticket 28's acceptance rule) — the old phone-send
        // version could never pass (run 38552405016). Started while the app
        // is front, so the run watch picks it up before Home.
        val control = checkNotNull(launch.controlUrl(FixtureEngine.Slow)) { "the slow engine's control URL" }
        assertTrue("the fixture's /run failed", launch.run(control, "chat-long"))
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isNotEmpty()
        }

        // Then leave the app: no banner while the app is front (the
        // foreground gate).
        device.pressHome()
        // The slow run finishes; the notification appears.
        device.openNotification()
        // `wait(hasObject)` yields false (not null) on timeout.
        val appeared = device.wait(
            Until.hasObject(By.text("Run finished")),
            60_000,
        )
        assertTrue("the Done notification never appeared", appeared == true)
        // Tap it: the chat opens (cold or warm — the hub holds the link).
        // The tap delivers a NEW intent (onNewIntent → setIntent), which the
        // rule's ActivityScenario cannot track: tell its teardown.
        launch.newIntentDelivered = true
        device.findObject(By.text("Run finished")).click()
        device.wait(Until.hasObject(By.pkg(app.packageName)), 10_000)
        // The session screen is on top again (75/76's root).
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun granted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            app,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33
}
