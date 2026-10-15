package sh.roboco.android.sidechats

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.sessions.SessionRowVM

/**
 * The Side Chats & Agents sheet (android-next #06, upstream #899's
 * `SideChatsViewController`): the three foldable sections and their rows
 * ([SideChatsList] — the header's count over every row, folded or not; a
 * tap on a header folds it; empty sections left out), the subagent row
 * (its spoken label, the trailing model or age), the row taps, the +
 * menu, and the overlay's + hiding on an engine without side chats.
 *
 * Robolectric + compose-ui-test with the fallback glass (no blur), the
 * clock auto-advancing (the sheet's present spring settles in
 * `waitForIdle`; the spinners are infinite animations ui-test ignores).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SideChatsSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        Glass.forceFallback = true
        SideChatsFolds.clear()
    }

    @After
    fun tearDown() {
        Glass.forceFallback = false
        SideChatsFolds.clear()
    }

    private val openedChats = mutableListOf<String>()
    private val openedAgents = mutableListOf<SideChatsAgent>()

    private val running = SideChatsAgent(
        docId = "engine:v1:k:chat--sub--run",
        title = "Audit the reduced-motion fallback",
        state = AgentState.Running,
        model = "haiku",
        spawnedAtMs = 0L,
    )
    private val done = SideChatsAgent(
        docId = "engine:v1:k:chat--sub--done",
        title = "Check CJK line breaking",
        state = AgentState.Done,
        spawnedAtMs = 0L,
    )
    private val failed = SideChatsAgent(
        docId = "engine:v1:k:chat--sub--fail",
        title = "Run the veil test matrix",
        state = AgentState.Failed,
        spawnedAtMs = 0L,
    )

    private fun chat(id: String, title: String) = SessionRowVM(
        id = id,
        title = title,
        projectName = "roboco",
        hasProject = true,
        colorIndex = 6,
        harness = "claude-code",
        branch = "main",
        pr = null,
        prNumber = null,
        status = SessionRowVM.Status.Idle,
        timeLabel = "5m",
        unseen = false,
        pinned = false,
        sendFailed = false,
    )

    private val ui = SideChatsUi(
        running = listOf(running),
        chats = listOf(chat("engine:v1:k:side", "Veil under Reduce Motion")),
        finished = listOf(failed, done),
    )

    private fun mountList(ui: SideChatsUi) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    SideChatsList(
                        ui = ui,
                        bottomInset = 0f,
                        onOpenChat = { openedChats += it },
                        onOpenAgent = { openedAgents += it },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ sections

    @Test
    fun theThreeSectionsListTheirRowsUnderCountedHeaders() {
        mountList(ui)
        compose.onNodeWithTag("section-side-running").assertExists()
        compose.onNodeWithTag("section-side-chats").assertExists()
        compose.onNodeWithTag("section-side-finished").assertExists()
        assertEquals(
            listOf("Running, 1"),
            compose.onNodeWithTag("section-side-running").fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        assertEquals(
            listOf("Finished agents, 2"),
            compose.onNodeWithTag("section-side-finished").fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        compose.onNodeWithTag("subagent-${running.docId}").assertExists()
        compose.onNodeWithTag("subagent-${done.docId}").assertExists()
        compose.onNodeWithTag("subagent-${failed.docId}").assertExists()
        compose.onNodeWithTag("session-engine:v1:k:side").assertExists()
    }

    @Test
    fun anEmptySectionIsLeftOut() {
        mountList(SideChatsUi(chats = listOf(chat("engine:v1:k:side", "Only a side chat"))))
        compose.onNodeWithTag("section-side-chats").assertExists()
        compose.onNodeWithTag("section-side-running").assertDoesNotExist()
        compose.onNodeWithTag("section-side-finished").assertDoesNotExist()
    }

    @Test
    fun aHeaderTapFoldsItsSectionAndKeepsTheCount() {
        mountList(ui)
        compose.onNodeWithTag("section-side-finished").performClick()
        compose.waitForIdle()
        assertTrue(SideChatsFolds.isFolded(SideChatsSection.Finished))
        compose.onNodeWithTag("subagent-${done.docId}").assertDoesNotExist()
        compose.onNodeWithTag("subagent-${failed.docId}").assertDoesNotExist()
        compose.onNodeWithTag("subagent-${running.docId}").assertExists()
        assertEquals(
            "the count is over every row, folded or not",
            listOf("Finished agents, 2"),
            compose.onNodeWithTag("section-side-finished").fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        compose.onNodeWithTag("section-side-finished").performClick()
        compose.waitForIdle()
        assertFalse(SideChatsFolds.isFolded(SideChatsSection.Finished))
        compose.onNodeWithTag("subagent-${done.docId}").assertExists()
    }

    // ------------------------------------------------------------ rows

    @Test
    fun rowTapsOpenTheSubagentOrTheSideChat() {
        mountList(ui)
        compose.onNodeWithTag("subagent-${running.docId}").performClick()
        compose.onNodeWithTag("session-engine:v1:k:side").performClick()
        compose.waitForIdle()
        assertEquals(listOf(running), openedAgents)
        assertEquals(listOf("engine:v1:k:side"), openedChats)
    }

    @Test
    fun aSubagentRowSpeaksItsStateModelAndTail() {
        assertEquals(
            "Subagent, Audit the reduced-motion fallback, Running, Haiku",
            subagentRowA11yLabel(running),
        )
        assertEquals(
            "Subagent, Audit, Running, Reading RowView.swift",
            subagentRowA11yLabel(SideChatsAgent("d", "Audit", AgentState.Running, tail = "Reading RowView.swift")),
        )
        assertEquals("Subagent, Run the veil test matrix, Failed", subagentRowA11yLabel(failed))
        mountList(ui)
        assertEquals(
            listOf("Subagent, Check CJK line breaking, Done"),
            compose.onNodeWithTag("subagent-${done.docId}").fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
    }

    @Test
    fun finishedAgentsShowTheirAgeLikeTheSessionList() {
        val now = 10_000_000L
        assertEquals("now", relativeAgentTime(now - 59_000L, now))
        assertEquals("34m", relativeAgentTime(now - 34 * 60_000L, now))
        assertEquals("2h", relativeAgentTime(now - 2 * 3_600_000L - 5_000L, now))
        assertEquals("1d", relativeAgentTime(now - 86_400_000L, now - 0L))
        assertEquals("a future spawn reads now", "now", relativeAgentTime(now + 5_000L, now))
        assertEquals("Haiku", capitalizedModel("haiku"))
        assertEquals("", capitalizedModel(""))
    }

    // ------------------------------------------------------------ the + menu

    @Test
    fun thePlusMenuOffersNewAndForkInOrder() {
        var news = 0
        var forks = 0
        val menu = sideChatsAddMenu(onNew = { news++ }, onFork = { forks++ })
        assertNull(menu.title)
        val items = menu.children.map { it as MenuItem }
        assertEquals(listOf("New Side Chat", "Fork into Side Chat"), items.map { it.title })
        assertEquals(RobocoIcons.ArrowTriangleBranch, (items[1].icon as MenuIcon.Symbol).icon)
        items[0].action()
        items[1].action()
        assertEquals(1, news)
        assertEquals(1, forks)
    }

    @Test
    fun theOverlayHidesThePlusOnAnEngineWithoutSideChats() {
        var canAdd by mutableStateOf(false)
        val controller = SideChatsSheetController()
        controller.open()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost {
                    Box(Modifier.fillMaxSize()) {
                        SideChatsOverlay(
                            controller = controller,
                            ui = ui,
                            canAdd = canAdd,
                            onOpenChat = {},
                            onOpenAgent = {},
                            onNew = {},
                            onFork = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(SideChatsCopy.Title).assertExists()
        compose.onNodeWithTag("side-chats-add").assertDoesNotExist()
        compose.onNodeWithTag("sheet-done").assertExists()

        compose.runOnUiThread { canAdd = true }
        compose.waitForIdle()
        compose.onNodeWithTag("side-chats-add").assertExists()
    }

    @Test
    fun doneDismissesTheSheetThenRunsNothing() {
        val controller = SideChatsSheetController()
        controller.open()
        var news = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost {
                    Box(Modifier.fillMaxSize()) {
                        SideChatsOverlay(
                            controller = controller,
                            ui = ui,
                            canAdd = true,
                            onOpenChat = {},
                            onOpenAgent = {},
                            onNew = { news++ },
                            onFork = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sheet-done").performClick()
        compose.waitForIdle()
        assertFalse("the sheet left after its exit animation", controller.showing)
        assertEquals(0, news)
        compose.onNodeWithTag("side-chats-sheet").assertDoesNotExist()
    }

    @Test
    fun theControllerRunsTheWaitingActionOnceDismissed() {
        val controller = SideChatsSheetController()
        controller.open()
        var forks = 0
        controller.dismissThen { forks++ }
        assertEquals("nothing before the sheet is gone", 0, forks)
        controller.onDismissed()
        assertFalse(controller.showing)
        assertEquals(1, forks)
        controller.onDismissed()
        assertEquals("the action runs once", 1, forks)
    }
}
