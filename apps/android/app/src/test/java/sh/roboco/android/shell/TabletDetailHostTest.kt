package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.shell.tablet.SidebarState

/**
 * The detail column's lifecycle through a real host (android-ui-parity
 * #106, Robolectric): replacing session A with session B in a composed
 * [TabletDetailHost] delivers ticket 24's `setRoutes` order — the old root
 * gets the full appear/disappear pair and `close()`s, its composition
 * disposes exactly once **after** `didDisappear(a, true)` — plus the
 * adapter's root recording for the draft and empty columns. The adapter's
 * other dependencies are fakes; the sessions are [SessionRoute] + [NavRoute]
 * fakes whose `Content()` registers a [NavEntryEffect].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-xhdpi")
class TabletDetailHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val log = mutableListOf<String>()

    private val state = TabletShellState(
        initialChatId = null,
        initialSidebarVisible = true,
        initialDetailRoot = DetailRoot.Empty,
    )

    /** The real adapter over a real [NavStack] and [TabletShellState]; everything else faked. */
    private val adapter = TabletShellAdapter(
        state = state,
        detailStack = NavStack(EmptyDetailRoute),
        sidebar = SidebarState(),
        settings = SettingsPresenterSlot(),
        sheets = { null },
        alerts = { Alerts.Noop },
        actionSheet = { ActionSheetPresenter.Noop },
        toaster = { Toaster.Noop },
        hideImeImpl = {},
    )

    /**
     * A fake session route: its [Content] records the UIKit-parity
     * lifecycle callbacks and its own disposal, as the sessions the real
     * factory pushes (85's detach-on-removal and 76's close safety net
     * ride the same callbacks).
     */
    private inner class FakeSessionRoute(override val chatId: String) : SessionRoute, NavRoute {
        @Composable
        override fun Content() {
            NavEntryEffect(
                onWillAppear = { log += "willAppear($chatId)" },
                onDidAppear = { _ -> log += "didAppear($chatId)" },
                onWillDisappear = { _, removing -> log += "willDisappear($chatId,$removing)" },
                onDidDisappear = { removed -> log += "didDisappear($chatId,$removed)" },
            )
            DisposableEffect(chatId) {
                onDispose { log += "dispose($chatId)" }
            }
        }

        override fun prepareArrival() {}
        override fun finishArrival() {}
        override fun discard() {}
    }

    @Test
    fun replacingSessions_deliversLifecycleAndClosesTheOldRoot() {
        compose.setContent {
            RobocoTheme {
                TabletDetailHost(adapter.detailStack)
            }
        }
        compose.waitForIdle()

        compose.runOnUiThread { adapter.setDetailSession(FakeSessionRoute("a")) }
        compose.waitForIdle()
        val a = adapter.detailStack.entries[0]
        log.clear()

        compose.runOnUiThread { adapter.setDetailSession(FakeSessionRoute("b")) }
        compose.waitForIdle()

        // Ticket 24's setRoutes order: the old root's full pair around the
        // new root's appear, then the sweep closes A and its composition
        // disposes — after didDisappear(a, true), exactly once.
        assertEquals(
            listOf(
                "willDisappear(a,true)",
                "willAppear(b)",
                "didDisappear(a,true)",
                "didAppear(b)",
                "dispose(a)",
            ),
            log,
        )
        assertTrue(a.isClosed)
        assertNull(adapter.detailStack.transition)
        assertEquals(1, adapter.detailStack.entries.size)
        assertEquals(DetailRoot.Session("b"), state.detailRoot)
    }

    @Test
    fun setDetailDraftAndEmpty_recordRootsAndRoutes() {
        // Hostless on purpose: 93's embedded NewSessionScreen needs the app
        // graph (a RobocoApplication), which this rule has no instance of —
        // and with no host attached, setRoutes completes synchronously (24),
        // so the draft page never composes at all.
        adapter.setDetailDraft(null, false) { _, _ -> }
        assertEquals(DetailRoot.Draft(null, false), state.detailRoot)
        assertTrue(adapter.detailStack.entries[0].route is DraftDetailRoute)
        assertNull(adapter.detailStack.transition)

        adapter.setDetailEmpty()
        assertEquals(DetailRoot.Empty, state.detailRoot)
        assertSame(EmptyDetailRoute, adapter.detailStack.entries[0].route)
        assertNull(adapter.detailStack.transition)
    }
}
