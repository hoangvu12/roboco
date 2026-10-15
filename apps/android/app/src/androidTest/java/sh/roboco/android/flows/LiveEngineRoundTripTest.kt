package sh.roboco.android.flows

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity

/**
 * The Android port of `uit:LiveStackTests.swift`'s
 * `testSendRoundTripsThroughRealEngine` (android-app#30): the whole
 * pairing path a real user takes — **no debug `pair` extra**. The test
 * mints a fresh pairing URL from the rig's control endpoint, types it
 * into the visible pairing root's Pair page (the Roboco adaptation of
 * upstream's edge sign-in: no cloud, the phone pairs with the engine
 * directly, ADR 0004), submits, then a new session → send → the mock
 * harness's reply streams back and the turn settles.
 *
 * Upstream ran this against a `wrangler dev` edge; Roboco's "real stack"
 * is the fixture engine on this host (`adb reverse`), which the rig
 * starts. The `harness mock` extra is still sent — a live-stack test
 * never pins a real agent (the fixture's registry lists only the mock
 * anyway).
 */
@RunWith(AndroidJUnit4::class)
class LiveEngineRoundTripTest {

    /**
     * The rig, launched **unpaired** (`pairAtLaunch = false`): the
     * activity starts at the pairing root, and the mint happens by hand
     * mid-test — everything the `pair` extra automates is walked through
     * the visible UI instead.
     */
    private val launch = FixtureRule(
        routeFor = { null },
        enginesFor = { setOf(FixtureEngine.Main) },
        pairAtLaunch = false,
    )

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }

    @Test
    fun sendRoundTripsThroughRealEngine() {
        // 1. The pairing root is what a fresh install shows (no pair
        //    extra, no stored engines): title, tagline, the entry button.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Roboco").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("pairing-root").fetchSemanticsNodes().isNotEmpty()
        }

        // 2. The root's entry button opens the Pair page (ticket 72).
        compose.onNodeWithTag("pair-engine").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("pair-page").fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Type the minted URL into the link field and submit — the
        //    redeem runs through the real `pairEngine` path.
        val pairUrl = launch.mint(launch.controlUrl(FixtureEngine.Main)!!)
        assertTrue("the rig's /mint returned no pairing URL", pairUrl != null)
        compose.onNodeWithTag("pair-url").performClick().performTextInput(pairUrl!!)
        compose.onNodeWithTag("pair-submit").performClick()

        // 4. The redeem lands: the shell replaces the pairing root (the
        //    front page's Sessions title shows).
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }

        // 5. A session: send → the engine adopts the command (the echo
        //    keeps its id) and the mock harness replies through the chat2
        //    room. (iOS opens the new-session sheet here; it is still a
        //    placeholder (ui-parity #92/#113), so the round-trip runs on
        //    the fixture's seeded `chat-deploy` — the same wire path.)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-deploy")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(FlowSemantics.sessionRow("chat-deploy")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("composer-input").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer-input").performTextInput("hello from the phone")
        compose.onNodeWithTag("composer-send").performClick()
        // `main`'s instant ~20-row reply can push the prompt out of the
        // realized band before a plain wait polls it (run 38552405016):
        // page back to it.
        compose.awaitTranscriptNode(FlowSemantics.startsWith("You: hello from the phone"))
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("live-roundtrip.png")
    }
}
