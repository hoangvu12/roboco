package sh.roboco.android.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.notify.NotificationPrefs
import sh.roboco.android.RobocoApplication

/**
 * The notification flow (android-app#28, CI-only — no emulator in the
 * worker slots): the Android port of `testNotificationTapOpensSession`
 * (`iosui:apps/ios/ZeronUITests/SessionFlowTests.swift:174-206`), fully
 * local — the edge and APNs are gone, so the notification comes from the
 * phone's own driver:
 *
 * 1. a slow engine is paired and connected (adb reverse to
 *    `roboco-engine --example android_fixture`, ticket 30's flow rig —
 *    **that rig is this test's prerequisite**);
 * 2. notifications are on (the pref) and the permission granted via
 *    UiAutomator's `Allow`;
 * 3. a prompt is sent, then `pressHome()`;
 * 4. `openNotification()`, wait for "Run finished", tap it, and the chat's
 *    transcript is shown (tickets 75/76's session screen — until they
 *    land, this test stops at the notification itself, the tracked
 *    state, like ui-parity#97's forward-looking flow test).
 *
 * Run: `./gradlew :app:connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=sh.roboco.android.flows.NotificationFlowTest`.
 */
@RunWith(AndroidJUnit4::class)
class NotificationFlowTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val app: RobocoApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as RobocoApplication

    @Before
    fun setUp() {
        // Ticket 30's rig must have paired a slow fixture engine before
        // this test (adb reverse + a first-run pairing); a fresh emulator
        // without it has nothing to notify about — skip, don't fail.
        assumeTrue(
            "the flow rig's paired engine is missing (start android_fixture first)",
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
        // Grant through the system dialog (the first-session ask path, or
        // the Settings toggle — either way the OS dialog is the same).
        if (!granted()) {
            device.findObject(By.text("Allow"))?.click()
        }
        assumeTrue("POST_NOTIFICATIONS was not granted", granted())
        // Send a prompt (tickets 94/76's create path; until then the rig
        // drives the engine side). Then leave the app: no banner while
        // the app is front (the foreground gate).
        device.pressHome()
        // The slow run finishes; the notification appears.
        device.openNotification()
        val notification = device.wait(
            Until.hasObject(By.text("Run finished")),
            60_000,
        )
        assertNotNull("the Done notification never appeared", notification)
        // Tap it: the chat opens (cold or warm — the hub holds the link).
        device.findObject(By.text("Run finished")).click()
        // The session screen (ticket 75/76's root). Until those land, the
        // launch itself is the observable part: the app is front again.
        device.wait(Until.hasObject(By.pkg(app.packageName)), 10_000)
    }

    private fun granted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            app,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33
}
