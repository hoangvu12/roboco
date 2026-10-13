package sh.roboco.android.settings

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity
import sh.roboco.android.design.toggleTag

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
 */
@RunWith(AndroidJUnit4::class)
class NotificationSettingsFlowTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Launched at rule application: the Settings tab (ticket 36's route extra). */
    private val scenario = ActivityScenarioRule<MainActivity>(
        Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        )
            .putExtra("route", "more"),
    )

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        AndroidComposeTestRule(scenario) { rule ->
            var activity: MainActivity? = null
            rule.scenario.onActivity { activity = it }
            checkNotNull(activity) { "Activity was not set in the ActivityScenarioRule" }
        }

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
        val kindsShown = {
            compose.onAllNodes(hasTestTag(toggleTag("notify:done")), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // "tap toggle-notify:enabled if its value isn't 1": the kinds are
        // already there when the permission was granted before.
        if (!kindsShown()) {
            masterSwitch.performClick()

            // The system dialog's Allow button, by resource then by text.
            val allow = device.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))
                ?: device.findObject(By.text("Allow"))
            assertNotNull("the system Allow button was not found", allow)
            allow.click()
        }

        // "toggle-notify:done must exist" ("kinds shown once on"): the
        // result callback + ON_RESUME land within a few frames.
        compose.waitUntil(timeoutMillis = 10_000, condition = kindsShown)
        compose.onNodeWithTag(toggleTag("notify:done"), useUnmergedTree = true).assertExists()
    }
}
