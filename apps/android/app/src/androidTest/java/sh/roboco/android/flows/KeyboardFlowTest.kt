package sh.roboco.android.flows

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity

/**
 * The keyboard flow on a device (ticket 133, CI-only — no emulator or
 * device in the worker slots): the interactive IME dismiss on the real
 * session screen, the port of `iosui:SessionFlowTests.swift:547-564`'s
 * guard included. The session screen (ui-parity#75) and the FixtureRule
 * harness (android-app#30) have both landed, so this runs on the rig's
 * `main` engine and `chat-deploy`.
 *
 * The IME's top edge is tracked through raw window swipes (UiAutomator,
 * 8 dp inside the transcript's left edge — inside the 18 dp row margin so
 * no link is hit, `mob:layout/rows.rs:124`):
 * 1. focus `composer-input`, type "Half-written thought", wait ≤ 3 s
 *    for the IME;
 * 2. a drag ending a quarter of the IME above the window bottom (past
 *    half) closes it — the composer collapses to the capsule
 *    (`composer-chip-model` leaves the semantics tree) and the draft
 *    is kept; a drag ending three quarters in (above half) reopens it
 *    and keeps the card;
 * 3. a plain tap on the transcript at `(8 dp, 0.35·height)` hides the
 *    IME within 3 s (81) with the draft kept.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardFlowTest {

    private val launch = FixtureRule(routeFor = { "chat:chat-deploy" })

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }

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
        // 8 dp inside the transcript's left edge: the window's own edge on a
        // phone, the session column's beside the tablet split's sidebar.
        // A window-relative 8 dp landed in the sidebar there, so the drag
        // never reached the IME (the tablet job's 1 s timeout, ci-speed 23).
        val transcriptLeft = compose.onNodeWithTag("transcript").getBoundsInRoot().left
        val x = ((transcriptLeft + 8.dp).value * compose.density.density).toInt()
        assertTrue("the IME is showing", imeVisible())

        // 2. A drag ending a quarter of the IME above the bottom: past
        // half, so the release closes it.
        device.swipe(x, (windowH * 0.4f).toInt(), x, windowH - imeBottom / 4, 40)
        compose.waitUntil(timeoutMillis = 1_000) { !imeVisible() }
        // The platform reports the IME hidden as soon as the settle finishes
        // the controller; the collapse comes several hops later: the insets
        // dispatch (a view traversal) reaches Compose's isImeVisible, the
        // InteractiveImeDismiss.active flip and 56's blur effect clear the
        // focus, and a recomposition strips the chips (isCard false). The
        // test's idle sync does not wait for a pending traversal, so on the
        // ~1 frame/s CI main thread the assert ran before the blur (run on
        // 4d59594c: the card's chip still at t = 2210 px). Wait for the
        // capsule itself.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-chip-model").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("composer-chip-model").assertDoesNotExist() // the capsule

        // Refocus, then a drag ending three quarters in: above half, so
        // the IME springs back and the composer stays the card.
        compose.onNodeWithTag("composer-input").performClick()
        compose.waitUntil(timeoutMillis = 3_000) { imeVisible() }
        device.swipe(x, (windowH * 0.4f).toInt(), x, windowH - 3 * imeBottom / 4, 40)
        // The spring back (Motion.ImeSettle) runs on the compose clock; on the
        // CI emulator the main thread ran ~1 frame/s, so a 1 s wait ended
        // mid-settle (run 38552405016). Let the clock cover the settle first.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(timeoutMillis = 5_000) { imeVisible() }
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
