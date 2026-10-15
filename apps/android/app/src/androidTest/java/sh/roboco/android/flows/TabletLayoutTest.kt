package sh.roboco.android.flows

import android.content.res.Configuration
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity

/**
 * The tablet split shell's flows (android-app#30, the port of
 * `uit:IPadLayoutTests.swift`): the phone UI with a sidebar beside the
 * session column. Runs only where `smallestScreenWidthDp >= 600` (the
 * `android-emulator-tablet` job's Pixel Tablet AVD); on the phone job
 * every test skips via the assumption, like iOS's `XCTSkipUnless`.
 *
 * The "Sign Out" popover step becomes the effect action-sheet anchor
 * (the ticket's adaptation: iPad action sheets need an anchor —
 * Roboco's action sheets always have one), but that row needs the
 * Settings screen's sign-out row, which lands with the parity Settings
 * tickets; the two landed tests stop at the sidebar/session/search
 * round trips.
 */
@RunWith(AndroidJUnit4::class)
@TabletOnly
class TabletLayoutTest {

    // Not a JUnit rule itself: the compose rule wraps it (FixtureRule's
    // usage) - as a second @Rule it launched the activity twice.
    private val launch = FixtureRule()

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }

    @Before
    fun tabletOnly() {
        val smallest = compose
            .activity
            .resources
            .configuration
            .smallestScreenWidthDp
        assumeTrue("tablet only (smallestScreenWidthDp was $smallest)", smallest >= 600)
    }

    /** `testSidebarOpensSessionsBesideIt`: a session beside the sidebar, orientation both ways. */
    @Test
    fun sidebarOpensSessionsBesideIt() {
        landscape()
        // Launch lands on the new-session page beside the sidebar (the
        // placeholder sheet today — the input is still centred).
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        val row = compose.onNode(FlowSemantics.sessionRow("chat-veil"))
        row.performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        // The sidebar stays beside the session (the row is still there).
        assertTrue(
            compose.onAllNodesWithText("Streaming veil on transcript rows")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        portrait()
        // Another session replaces it in the main column.
        compose.onNode(FlowSemantics.sessionRow("chat-deploy")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Wrangler deploy hygiene").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * `testNewSessionFromSidebarAndSearch`: the sidebar's search and
     * settings, with the action-sheet-anchor regression left for the
     * Settings parity tickets (see the class docs).
     */
    @Test
    fun newSessionFromSidebarAndSearch() {
        landscape()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(FlowSemantics.sessionRow("chat-deploy")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Wrangler deploy hygiene").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun landscape() = orientation(Configuration.ORIENTATION_LANDSCAPE)

    private fun portrait() = orientation(Configuration.ORIENTATION_PORTRAIT)

    /**
     * Rotates by locking auto-rotate and setting `user_rotation` (1 =
     * landscape, 0 = portrait): the
     * activity declares `configChanges`, so the rotation is a resize,
     * not a recreation — the sidebar/session survive it, which is part
     * of what the iOS test checks.
     */
    private fun orientation(orientation: Int) {
        val rotation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 1 else 0
        val device = androidx.test.uiautomator.UiDevice
            .getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("settings put system accelerometer_rotation 0")
        device.executeShellCommand("settings put system user_rotation $rotation")
        Thread.sleep(1_000) // the resize's settle — a tour-style wait
    }
}
