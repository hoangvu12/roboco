package sh.roboco.android.shell

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hoisted shell-state scope (android-ui-parity #108) — the ticket's
 * Verification list. Robolectric + `createComposeRule`, frozen clock
 * (`mainClock.autoAdvance = false`): the carry-over's `withFrameNanos` is
 * the only thing that needs a frame, so a flip is `runOnUiThread { write;
 * Snapshot.sendApplyNotifications() }` → `waitForIdle()` (the
 * recomposition and the restarted effect) → `advanceTimeByFrame()` (the
 * deferred step) → `waitForIdle()`.
 *
 * The fake detail / tabs columns are injected through the internal
 * [ShellStateScope] overload; 106's real router is still created (the
 * production path), it just never drives the column here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShellStateScopeTest {

    @get:Rule
    val compose = createComposeRule()

    private val kind = mutableStateOf(RootKind.Split)
    private val scopeShown = mutableStateOf(true)

    private val detailLog = mutableListOf<String>()
    private val tabsLog = mutableListOf<String>()

    /** The fake columns injected through the internal overload. */
    private val fakeDetail = RecordingDetail(shownChatId = "c1", isEmpty = false, log = detailLog)
    private val fakeTabs = RecordingTabs(tabsLog)

    /** Every [ShellStates] the content saw (asserted to be one instance). */
    private val seenStates = mutableListOf<ShellStates>()

    /** The phone branch's saveable counter (the round-trip case sets it to 3). */
    private var counter: MutableState<Int>? = null

    /** One `setContent` per test: the scope, driven by [kind] and [scopeShown]. */
    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (scopeShown.value) {
                ShellStateScope(
                    kind = kind.value,
                    detailOverride = fakeDetail,
                    tabsOverride = fakeTabs,
                ) {
                    seenStates.add(LocalShellStates.current)
                    if (kind.value == RootKind.Split) {
                        BasicText("split")
                    } else {
                        // 35's phone slot shape: the saveable provider the
                        // hoisted holder keys the branch's state into.
                        LocalShellStates.current.saveable.SaveableStateProvider("phone") {
                            val n = rememberSaveable { mutableIntStateOf(0) }
                            counter = n
                            BasicText("phone ${n.intValue}")
                        }
                    }
                }
            } else {
                BasicText("gone")
            }
        }
        compose.waitForIdle()
    }

    /**
     * One shell-kind change on the frozen clock: `waitForIdle` runs no
     * frame, so frame 1 recomposes (the SideEffect writes isCollapsed and
     * the restarted `LaunchedEffect(kind)` reaches its `withFrameNanos`) and
     * frame 2 serves that frame — the carry-over's deferred step.
     */
    private fun flip(next: RootKind) {
        compose.runOnUiThread {
            kind.value = next
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame() // frame 1: recomposition + the restarted effect
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame() // frame 2: the deferred step
        compose.waitForIdle()
    }

    /** One frame on the frozen clock: a state write is composed (and applied) only then. */
    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    // ------------------------------------------- the hoisted identity

    @Test
    fun theHoistedStateIsOneInstanceAcrossTheSwitch() {
        mount()
        flip(RootKind.Phone)
        flip(RootKind.Split)
        flip(RootKind.Phone)
        flip(RootKind.Split)
        // iOS keeps one `SplitRootController` (with its lazy `tabs`); the
        // scope keeps one [ShellStates] — and one `MainTabState` in it.
        assertTrue("the content composed", seenStates.isNotEmpty())
        assertTrue(
            "one ShellStates across Split → Phone → Split → Phone → Split",
            seenStates.all { it === seenStates.first() },
        )
        assertSame("one MainTabState for both shells", seenStates.first().tabs, seenStates.last().tabs)
    }

    @Test
    fun isCollapsedFollowsTheShellKind() {
        mount()
        val states = seenStates.first()
        assertFalse("split is not collapsed", states.tablet.isCollapsed)
        flip(RootKind.Phone)
        assertTrue("phone is collapsed", states.tablet.isCollapsed)
        flip(RootKind.Split)
        assertFalse(states.tablet.isCollapsed)
    }

    // ---------------------------------------- the deferred transition

    @Test
    fun theCollapseClearsTheDetailAfterOneFrame_NotBefore() {
        mount()
        // The fake detail shows a session (c1); nothing has run yet.
        assertEquals(0, detailLog.size)
        compose.runOnUiThread {
            kind.value = RootKind.Phone
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        // Frozen clock: waitForIdle composed nothing. Frame 1 recomposes and
        // starts the restarted effect, which then waits for ITS frame — the
        // column still holds its copy.
        assertEquals(0, detailLog.size)
        frame()
        assertEquals("not before the frame", 0, detailLog.size)
        frame() // frame 2: the deferred step
        assertEquals(listOf("detail.clear"), detailLog)
    }

    // ------------------------------------------- the teardown release

    @Test
    fun removingTheScopeReleasesEveryHoistedSession() {
        mount()
        compose.runOnUiThread {
            scopeShown.value = false
            Snapshot.sendApplyNotifications()
        }
        // Frozen clock: the removal is composed (and onDispose runs) only on a frame.
        frame()
        // The DisposableEffect runs after the content is forgotten: the
        // tab stacks pop (hostless, so the entries close) and the column
        // clears — once each.
        assertEquals(listOf("tabs.popAll"), tabsLog)
        assertEquals(listOf("detail.clear"), detailLog)
        assertEquals(1, detailLog.size)
    }

    // -------------------------------------- the per-shell saveable state

    @Test
    fun thePhoneBranchsSaveableStateSurvivesARoundTrip() {
        mount()
        flip(RootKind.Phone)
        compose.waitForIdle()
        compose.runOnUiThread {
            requireNotNull(counter).value = 3
            Snapshot.sendApplyNotifications()
        }
        // Frozen clock: the write reaches the text only on a frame.
        frame()
        compose.onNodeWithText("phone 3").assertExists()
        // Phone → Split → Phone: the holder kept the branch's state, as
        // iOS keeps the tab controller's screens.
        flip(RootKind.Split)
        flip(RootKind.Phone)
        compose.waitForIdle()
        assertEquals(3, counter?.value)
        compose.onNodeWithText("phone 3").assertExists()
    }

    // ------------------------------------------------------- the fakes

    /** The detail column: logs every call, tracks what it shows. */
    private class RecordingDetail(
        shownChatId: String?,
        isEmpty: Boolean,
        private val log: MutableList<String>,
    ) : SplitDetailColumn {
        override var shownChatId: String? = shownChatId
        override var isEmpty: Boolean = isEmpty

        override fun clear() {
            log += "detail.clear"
            shownChatId = null
            isEmpty = true
        }

        override fun showSession(chatId: String) {
            log += "detail.show($chatId)"
            shownChatId = chatId
            isEmpty = false
        }

        override fun showDraft() {
            log += "detail.showDraft"
            shownChatId = null
            isEmpty = false
        }
    }

    /** The tab shell: logs every call. */
    private class RecordingTabs(private val log: MutableList<String>) : TabsColumn {
        override fun openSession(chatId: String) {
            log += "tabs.open($chatId)"
        }

        override fun popToFrontPage() {
            log += "tabs.popToFrontPage"
        }

        override fun popSearchSessions() {
            log += "tabs.popSearchSessions"
        }

        override fun popAllToRoot() {
            log += "tabs.popAll"
        }
    }
}
