package sh.roboco.android.sidechats

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuSection
import sh.roboco.android.session.SessionHeader
import sh.roboco.android.session.SessionHeaderMetrics
import sh.roboco.android.session.SideChatMenuRows
import sh.roboco.android.session.sessionMenu
import uniffi.roboco_core.CoreException

/**
 * The side chats' entry points (android-next #06): the header's second
 * bar item with its badge (shown only with something to show, its label
 * carrying the running count, the title cap narrowed for it), the ⋯
 * group (New Side Chat / Fork into Side Chat between Copy Transcript and
 * Archive, disabled offline or while a fork runs, absent on an engine
 * without side chats), the fork's one-at-a-time run and its failure
 * copy, and the subagent viewer's subtitle and menu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class SideChatsEntryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun fallbackGlass() {
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    private class RecordingToaster : Toaster {
        val texts = mutableListOf<String>()
        override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
            texts += text
        }
    }

    // ------------------------------------------------------------ header button

    @Test
    fun theHeaderButtonCarriesTheBadgeAndOpensTheSheet() {
        var taps = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                SessionHeader(
                    title = "Streaming veil on transcript rows",
                    subtitle = "roboco @ MacBook Pro",
                    edgeEngaged = false,
                    onMore = {},
                    showBack = true,
                    onBack = {},
                    statusBarTop = 24.dp,
                    accessory = { SideChatsButton(SideChatsBadge(running = 4, total = 9), onClick = { taps++ }) },
                )
            }
        }
        compose.waitForIdle()
        val button = compose.onNodeWithTag("side-chats")
        assertEquals(
            listOf("Side chats and agents, 4 running"),
            button.fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        compose.onNodeWithTag("side-chats-badge", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("session-more").assertExists()
        button.performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun noAccessoryNoButton() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                SessionHeader(
                    title = "A chat with nothing hanging off it",
                    subtitle = "roboco @ studio",
                    edgeEngaged = false,
                    onMore = {},
                    showBack = true,
                    onBack = {},
                    statusBarTop = 24.dp,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("side-chats").assertDoesNotExist()
    }

    @Test
    fun theTitleCapClearsTheSecondBarItem() {
        assertEquals(240f, SessionHeaderMetrics.titleMaxWidth(402f, showBack = true), 0.001f)
        val extra = SessionHeaderMetrics.AccessoryExtent
        assertEquals(52f, extra, 0.001f)
        assertEquals(162f, SessionHeaderMetrics.titleMaxWidth(402f, showBack = true, extraTrailing = extra), 0.001f)
        assertEquals(120f, SessionHeaderMetrics.titleMaxWidth(360f, showBack = true, extraTrailing = extra), 0.001f)
    }

    // ------------------------------------------------------------ the ⋯ group

    private fun menu(canWrite: Boolean, rows: SideChatMenuRows?) = sessionMenu(
        pinned = false,
        canWrite = canWrite,
        onSetPinned = {},
        onCopyTranscript = {},
        onShowPreviews = {},
        onShowAccounts = {},
        onArchive = {},
        sideChats = rows,
    )

    @Test
    fun theSessionMenuGroupsTheSideChatRowsBetweenCopyAndArchive() {
        var news = 0
        var forks = 0
        val m = menu(canWrite = true, rows = SideChatMenuRows(forking = false, onNew = { news++ }, onFork = { forks++ }))
        // Pin, Copy, the side-chat group, Previews (agent-previews
        // ticket 05), Account & Usage (#05), Archive.
        assertEquals(6, m.children.size)
        assertEquals("Copy Transcript", (m.children[1] as MenuItem).title)
        val group = m.children[2] as MenuSection
        assertTrue("an inline group: hairlines above and below", group.inline)
        val rows = group.children.map { it as MenuItem }
        assertEquals(listOf("New Side Chat", "Fork into Side Chat"), rows.map { it.title })
        assertEquals(RobocoIcons.ArrowTriangleBranch, (rows[1].icon as MenuIcon.Symbol).icon)
        assertEquals(
            listOf("session-menu-new-side-chat", "session-menu-fork-side-chat"),
            rows.map { it.testTag },
        )
        assertFalse(rows[0].disabled)
        assertFalse(rows[1].disabled)
        rows[0].action()
        rows[1].action()
        assertEquals(1, news)
        assertEquals(1, forks)
        assertEquals("Previews", (m.children[3] as MenuItem).title)
        assertEquals("Archive", (m.children[5] as MenuItem).title)
    }

    @Test
    fun offlineOrForkingDisablesTheRowsAndAnOldEngineHasNone() {
        val offline = (menu(false, SideChatMenuRows(false, {}, {})).children[2] as MenuSection)
            .children.map { it as MenuItem }
        assertTrue(offline.all { it.disabled })
        val forking = (menu(true, SideChatMenuRows(true, {}, {})).children[2] as MenuSection)
            .children.map { it as MenuItem }
        assertFalse("New stays live while a fork runs", forking[0].disabled)
        assertTrue("one fork at a time", forking[1].disabled)
        val old = menu(true, null)
        assertEquals("an engine without side chats: the menu as it was", 5, old.children.size)
        assertTrue(old.children.none { it is MenuSection })
    }

    // ------------------------------------------------------------ the fork

    @Test
    fun aForkRunsOnceAtATimeAndOpensTheNewChat() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<String>()
        val sources = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val toaster = RecordingToaster()
        val fork = SideChatFork(
            fork = { source ->
                sources += source
                gate.await()
            },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            toaster = toaster,
            logW = { _, _ -> },
        )
        fork.start("engine:v1:k:chat") { opened += it }
        fork.start("engine:v1:k:chat") { opened += it }
        advanceUntilIdle()
        assertTrue(fork.forking)
        assertEquals("the second tap is ignored", listOf("engine:v1:k:chat"), sources)

        gate.complete("engine:v1:k:fork")
        advanceUntilIdle()
        assertFalse(fork.forking)
        assertEquals(listOf("engine:v1:k:fork"), opened)
        assertTrue(toaster.texts.isEmpty())
    }

    @Test
    fun aFailedForkToastsTheHostsReason() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val toaster = RecordingToaster()
        val opened = mutableListOf<String>()
        val fork = SideChatFork(
            fork = { throw CoreException.HostException("nothing to fork yet: no reply has completed") },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            toaster = toaster,
            logW = { _, _ -> },
        )
        fork.start("engine:v1:k:chat") { opened += it }
        advanceUntilIdle()
        assertFalse(fork.forking)
        assertTrue(opened.isEmpty())
        assertEquals(listOf("nothing to fork yet: no reply has completed"), toaster.texts)
    }

    @Test
    fun forkFailureCopyFollowsTheErrorKind() {
        assertEquals("busy", forkErrorText(CoreException.HostException("busy")))
        assertEquals(SideChatForkCopy.Failed, forkErrorText(CoreException.HostException("  ")))
        assertEquals(
            "The host is unreachable (Engine connection lost).",
            forkErrorText(CoreException.HostUnavailable("Engine connection lost")),
        )
        assertEquals(SideChatForkCopy.Unsupported, forkErrorText(CoreException.Unsupported("ForkSideChat")))
        assertEquals(SideChatForkCopy.Failed, forkErrorText(IllegalStateException("boom")))
    }

    // ------------------------------------------------------------ the viewer

    @Test
    fun theViewerSubtitleFollowsTheChip() {
        assertEquals("Running · Streaming veil", subagentSubtitle(AgentState.Running, "Streaming veil"))
        assertEquals("Subagent · Streaming veil", subagentSubtitle(AgentState.Done, "Streaming veil"))
        assertEquals("Failed · Streaming veil", subagentSubtitle(AgentState.Failed, "Streaming veil"))
        assertEquals("Subagent", subagentSubtitle(AgentState.Done, " "))
        val copy = subagentMenu(onCopyTranscript = {}).children.single() as MenuItem
        assertEquals("Copy Transcript", copy.title)
        assertEquals(RobocoIcons.DocOnDoc, (copy.icon as MenuIcon.Symbol).icon)
    }
}
