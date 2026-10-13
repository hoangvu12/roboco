package sh.roboco.android.flows

import android.content.Intent
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity

/**
 * The keyboard flow on a device (ticket 133, CI-only — no emulator or
 * device in the worker slots): the interactive IME dismiss on the real
 * session screen, the port of `iosui:SessionFlowTests.swift:547-564`'s
 * guard included. The ticket's FixtureRule harness (android-app#30,
 * `route chat:chat-deploy`) has not landed, so the session screen
 * (ui-parity#75) and tap-to-dismiss (#81) are exercised here once they
 * merge — until then this class stays ignored.
 *
 * The IME's top edge is tracked through raw window swipes (UiAutomator,
 * 8 dp from the left edge — inside the 18 dp row margin so no link is
 * hit, `mob:layout/rows.rs:124`):
 * 1. focus `composer-input`, type "Half-written thought", wait ≤ 3 s
 *    for the IME;
 * 2. a drag ending a quarter of the IME above the window bottom (past
 *    half) closes it — the composer collapses to the capsule
 *    (`composer-chip-model` leaves the semantics tree, 55) and the draft
 *    is kept; a drag ending three quarters in (above half) reopens it
 *    and keeps the card;
 * 3. a plain tap on the transcript at `(8 dp, 0.35·height)` hides the
 *    IME within 3 s (81) with the draft kept.
 */
@Ignore("Needs the session screen (ui-parity#75) and the FixtureRule harness (android-app#30); neither has landed")
@RunWith(AndroidJUnit4::class)
class KeyboardFlowTest {

    @get:Rule
    val scenario = ActivityScenarioRule<MainActivity>(
        Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        )
            .putExtra("route", "chat:chat-deploy"),
    )

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        AndroidComposeTestRule(scenario) { rule ->
            var activity: MainActivity? = null
            rule.scenario.onActivity { activity = it }
            checkNotNull(activity) { "Activity was not set in the ActivityScenarioRule" }
        }

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Test
    fun interactiveDismissClosesPastHalfAndReopensAboveIt() {
        // 1. Focus the composer and type the draft; the IME shows within 3 s.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("Half-written thought")
        compose.waitUntil(timeoutMillis = 3_000) { imeVisible() }

        val decor = compose.activity.window.decorView
        val imeBottom = ViewCompat.getRootWindowInsets(decor)!!
            .getInsets(WindowInsetsCompat.Type.ime()).bottom
        val windowH = decor.height
        val x = (8 * compose.density.density).toInt()
        assertTrue("the IME is showing", imeVisible())

        // 2. A drag ending a quarter of the IME above the bottom: past
        // half, so the release closes it.
        device.swipe(x, (windowH * 0.4f).toInt(), windowH - imeBottom / 4, 40)
        compose.waitUntil(timeoutMillis = 1_000) { !imeVisible() }
        compose.onNodeWithTag("composer-chip-model").assertDoesNotExist() // the capsule

        // Refocus, then a drag ending three quarters in: above half, so
        // the IME springs back and the composer stays the card.
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitUntil(timeoutMillis = 3_000) { imeVisible() }
        device.swipe(x, (windowH * 0.4f).toInt(), windowH - 3 * imeBottom / 4, 40)
        compose.waitUntil(timeoutMillis = 1_000) { imeVisible() }
        compose.onNodeWithTag("composer-chip-model").assertExists() // still the card

        // 3. The tap-to-dismiss guard (`testTapOutsideMinimizesComposer`,
        // ported by 81): a plain tap on the transcript hides the IME
        // within 3 s and the draft survives.
        device.click(x, (windowH * 0.35f).toInt())
        compose.waitUntil(timeoutMillis = 3_000) { !imeVisible() }
        compose.onNodeWithTag("composer-input").assertTextContains("Half-written", substring = true)
        assertFalse(imeVisible())
    }
}
