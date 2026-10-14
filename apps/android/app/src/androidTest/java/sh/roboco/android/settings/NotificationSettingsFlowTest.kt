package sh.roboco.android.settings

import android.os.Build
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity
import sh.roboco.android.design.toggleTag
import sh.roboco.android.flows.FixtureRule
import sh.roboco.android.flows.mountedActivity

/**
 * The instrumented port of the Settings half of `testNotificationTapOpensSession`
 * (`iosui:SessionFlowTests.swift:184-194`, android-ui-parity #99): Settings →
 * tap `toggle-notify:enabled` (if the kinds are not already shown) → tap the
 * system "Allow" button → `toggle-notify:done` must exist ("kinds shown once
 * on"). The iOS test is host-gated (`XCTSkipUnless`, `:178-180`); this one
 * runs on a plain API 34 emulator.
 *
 * Needs ticket 36 (the `route` launch extra, debuggable builds) and ticket
 * 103 (`settings-screen`, the Settings list) merged; until 103 lands this
 * test times out at the first wait — that is the tracked state, not a
 * regression (the Wallpaper flow test's precedent). CI-only: no emulator
 * runs in the worker slots or locally.
 *
 * The system dialog is tapped with UiAutomator
 * (`com.android.permissioncontroller:id/permission_allow_button`, falling
 * back to the text "Allow") — Compose cannot reach into another process's
 * window.
 *
 * android-app#30: the launch now goes through the flow rig's
 * [FixtureRule] (the `main` fixture engine paired via the debug `pair`
 * extra, `route more`) — the Settings tab only exists behind a shell,
 * and the shell only exists with a paired engine.
 */
@RunWith(AndroidJUnit4::class)
class NotificationSettingsFlowTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Launched at rule application: the Settings tab (ticket 36's route extra), the rig's engine paired. */
    private val scenario = FixtureRule(routeFor = { "more" })

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(scenario) { rule -> rule.mountedActivity() }

    @Test
    fun notificationToggleShowsTheKindsOnceAllowed() {
        // The system POST_NOTIFICATIONS dialog only exists on API 33+;
        // below that the switch never asks.
        assumeTrue("the runtime permission dialog needs API 33+", Build.VERSION.SDK_INT >= 33)

        // iOS waits 10 s for the "Settings" nav bar; we wait for ticket
        // 103's root tag.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("settings-screen")).fetchSemanticsNodes().isNotEmpty()
        }

        // iOS scrolls the Notifications row into view first.
        val settingsList = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("settings-screen")))
        settingsList.performScrollToNode(hasTestTag("settings-notify:enabled"))

        val masterSwitch = compose.onNodeWithTag(toggleTag("notify:enabled"), useUnmergedTree = true)
        // While the system dialog (another app's window) still has focus,
        // the Compose test finds no hierarchy and throws instead of
        // answering false, and waitUntil does not catch it. Run a2590960
        // failed that way: polled at 19:53:47.485 with the permission
        // controller still focused, before its CLOSE transition at 48.05.
        // "No hierarchy yet" is "not shown yet".
        val kindsShown = {
            try {
                compose.onAllNodes(hasTestTag(toggleTag("notify:done")), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            } catch (e: IllegalStateException) {
                false
            }
        }

        // "tap toggle-notify:enabled if its value isn't 1": the kinds are
        // already there when the permission was granted before.
        if (!kindsShown()) {
            masterSwitch.performClick()

            // The system dialog's Allow button, by resource then by text.
            // Wait for it, and tap until the dialog is gone: the same run's
            // first tap landed during the dialog's open transition and the
            // InputDispatcher dropped it ("Not sending touch gesture to …
            // ActivityRecordInputSink … GrantPermissionsActivity").
            val dialog = By.pkg(Pattern.compile(".*permissioncontroller"))
            val allowSelector = By.res(Pattern.compile(".*:id/permission_allow_button"))
            var gone = false
            for (attempt in 1..3) {
                val allow = device.wait(Until.findObject(allowSelector), 5_000)
                    ?: device.findObject(By.text("Allow"))
                if (attempt == 1) assertNotNull("the system Allow button was not found", allow)
                if (allow == null) break // the dialog closed between attempts
                allow.click()
                gone = device.wait(Until.gone(dialog), 5_000) == true
                if (gone) break
            }
            assertTrue("the permission dialog did not close after Allow", gone || !device.hasObject(dialog))
            device.waitForIdle()
        }

        // "toggle-notify:done must exist" ("kinds shown once on"): the
        // result callback + ON_RESUME land within a few frames.
        compose.waitUntil(timeoutMillis = 10_000, condition = kindsShown)
        compose.onNodeWithTag(toggleTag("notify:done"), useUnmergedTree = true).assertExists()
    }
}
