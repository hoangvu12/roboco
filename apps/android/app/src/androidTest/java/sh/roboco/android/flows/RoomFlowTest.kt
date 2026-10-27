package sh.roboco.android.flows

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity
import sh.roboco.android.RobocoApplication

/**
 * The rooms flow (ADR 0011, ticket 04): the phone's scan-once. A room is
 * minted on the rig (the fixture's test-only `/room-invite` control
 * endpoint — the same control channel `/mint` uses; the engines join
 * through the same `RoomStore` the CLI drives), then the Pair page joins
 * it: every book member gets its own session and engine entry, a member
 * that was offline at join time heals on the next network change, and
 * nothing here is data — the engines appear in the existing engines list
 * (ADR 0004 untouched).
 *
 * The invite is a secret: it never logs, and the assertions never print
 * it (failure messages quote tags and counts, not payloads).
 */
@RunWith(AndroidJUnit4::class)
class RoomFlowTest {

    private val launch = FixtureRule(
        routeFor = { null },
        enginesFor = { setOf(FixtureEngine.Main, FixtureEngine.Slow) },
        pairAtLaunch = false,
    )

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(launch) { rule -> rule.mountedActivity() }

    private val app: RobocoApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as RobocoApplication

    /** The room both tests join: main + slow as Direct loopback members. */
    private fun mintRoom(): String {
        val mainControl = launch.controlUrl(FixtureEngine.Main)
        val slowControl = launch.controlUrl(FixtureEngine.Slow)
        val mainPort = launch.enginePort(FixtureEngine.Main)
        val slowPort = launch.enginePort(FixtureEngine.Slow)
        assertNotNull("the rig is absent (control URLs missing)", mainControl)
        assertNotNull("the rig passed no engine ports (fixtureMainPort/fixtureSlowPort)", mainPort)
        val invite = launch.roomInvite(
            mainControl!!,
            name = "Flow room",
            members = listOf(
                "main" to "http://127.0.0.1:$mainPort",
                "slow" to "http://127.0.0.1:$slowPort",
            ),
        )
        assertTrue("the rig's /room-invite returned no invite", invite != null)
        // The second member joins the same room through its own control
        // port (the room is hostless: every member holds the book).
        val roomId = launch.roomJoin(slowControl!!, invite!!)
        assertTrue("the rig's /room-join refused the invite", roomId != null)
        return invite
    }

    @Test
    fun aRoomInviteJoinsEveryMemberAndTheSessionsLoad() {
        val invite = mintRoom()

        // 1. The pairing root, then the Pair page.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("pairing-root").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pair-engine").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("pair-page").fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Paste the invite: the room target line names the room and the
        //    member count (the core's parser ran; no engine target shows).
        compose.onNodeWithTag("pair-url").performClick().performTextInput(invite)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("pair-room-target").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Join room").fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Submit: every member joins — one session per engine, exactly
        //    like a pairing (the present mints ordinary sessions).
        compose.onNodeWithTag("pair-submit").performClick()
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText("Sessions").fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Both members are paired and connected; the main engine's
        //    seeded session row arrived (rooms share reachability, never
        //    data: the chats are each engine's own).
        compose.waitUntil(timeoutMillis = 60_000) {
            app.clientOrNull()?.engines().orEmpty().size == 2
        }
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(FlowSemantics.sessionRow("chat-deploy")).fetchSemanticsNodes().isNotEmpty()
        }
        val engines = app.clientOrNull()?.engines().orEmpty()
        assertEquals(2, engines.size)
        assertTrue(engines.all { it.room?.roomId != null })
        FlowSemantics.saveScreenshot("room-joined.png")
    }

    @Test
    fun aMemberOfflineAtJoinTimeHealsOnTheNextNetworkChange() {
        // The slow member is down BEFORE the join (its engine listener
        // stops; the control port and its room row persist, so it can
        // still have joined the room). The stop must actually bite: a
        // stop that stopped no listener, or left the port accepting, is
        // a broken rig and fails here by name — not as the join minting
        // both members and timing out 60 s later (CI 40256510722).
        val stop = launch.listenerControl(launch.controlUrl(FixtureEngine.Slow)!!, "stop")
        assertTrue("the rig's /listener/stop stopped no listener", stop.getBoolean("stopped"))
        assertTrue("the slow engine's port still accepts after /listener/stop", stop.getBoolean("closed"))
        val invite = mintRoom()

        // 1. A partial join: main joins, slow is missing — a success, the
        //    page closes, the phone holds one engine.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("pairing-root").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pair-engine").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("pair-page").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pair-url").performClick().performTextInput(invite)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("pair-room-target").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pair-submit").performClick()
        compose.waitUntil(timeoutMillis = 60_000) {
            app.clientOrNull()?.engines().orEmpty().size == 1
        }

        // 2. The member wakes; the next network change heals the room
        //    without any user action (the heal rides the existing
        //    network-change moment — no new timers, no probing).
        launch.listenerControl(launch.controlUrl(FixtureEngine.Slow)!!, "start")
        app.clientOrNull()?.setNetworkChanged()
        compose.waitUntil(timeoutMillis = 90_000) {
            app.clientOrNull()?.engines().orEmpty().size == 2
        }
        val healed = app.clientOrNull()?.engines().orEmpty()
        assertEquals(2, healed.size)
        assertTrue(
            "the healed member carries its room tag",
            healed.all { it.room?.roomId != null },
        )
        FlowSemantics.saveScreenshot("room-healed.png")
    }
}
