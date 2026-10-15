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
 * The Tailcat smoke (android-next#02): the whole pairing path for an
 * engine the phone can reach **only over Tailcat**. The rig's Tailcat
 * fixture publishes a `main`-profile engine through the desktop adapter
 * against a local DERP relay; `flow-tests.sh` adb-reverses the relay and
 * the control port but never the engine port. So the seeded sessions can
 * only arrive through `libroboco_tailcat.so` loaded in this process: its
 * `dlopen` under ART, the getifaddrs interface getter, the WireGuard
 * tunnel over DERP, and the loopback WebSocket the core dials through it.
 *
 * Skips when the rig built no Go pieces (no `fixtureTailcat` argument).
 */
@RunWith(AndroidJUnit4::class)
class TailcatPairingFlowTest {

    private val launch = FixtureRule(
        routeFor = { null },
        enginesFor = { setOf(FixtureEngine.Tailcat) },
        pairAtLaunch = false,
    )

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }

    @Test
    fun aTailcatInvitePairsAndTheSessionsLoad() {
        // 1. The pairing root, then the Pair page.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("pairing-root").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pair-engine").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("pair-page").fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Paste a fresh invite: the target line names Tailcat (the
        //    core's parser ran), with no cleartext warning.
        val invite = launch.mintTailcat(launch.controlUrl(FixtureEngine.Tailcat)!!)
        assertTrue("the rig's /mint-tailcat returned no invite", invite != null)
        compose.onNodeWithTag("pair-url").performClick().performTextInput(invite!!)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Engine over Tailcat", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithTag("pair-cleartext").fetchSemanticsNodes().isEmpty())

        // 3. Submit: the library loads, the tunnel comes up (a ping over
        //    DERP, up to ~20 s), the code is redeemed through it.
        compose.onNodeWithTag("pair-submit").performClick()
        compose.waitUntil(timeoutMillis = 90_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }

        // 4. The feed arrived over the tunnel: the seeded session row.
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-deploy")).fetchSemanticsNodes().isNotEmpty()
        }
        FlowSemantics.saveScreenshot("tailcat-paired.png")
    }
}
