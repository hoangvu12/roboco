package sh.roboco.android.transcript

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UniffiSandbox
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.LinkHit
import uniffi.roboco_core.RowPlacement

/**
 * The link pipeline against the real core (ticket 126; the same JNA
 * setup as `TranscriptHostTest`, `w411dp-h891dp-xhdpi` so d = 2): the
 * [LinkFixture] renders through [TranscriptHost], and a tap on a painted
 * fragment — row coordinates, and a scroller link at its painted
 * position after a scroll — reaches [TranscriptActions.openLink] with
 * the fragment rect in window px and fires `onTap` exactly once after
 * it. This ports `iosui:SessionFlowTests.swift:161-172`
 * (`testTappingLinkOpensIt`): the browser launch itself is
 * `LinkRouterTest`'s; here only the tap's routing result is observed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptLinkTapTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(context)
        // Build the shared engine off the main looper first, as the app
        // does (`TextEngine.await()`).
        runBlocking { TextEngine.await() }
        TranscriptPerf.reset()
    }

    @After
    fun reset() = ReduceMotion.resetForTest()

    // ------------------------------------------------------------- helpers

    /**
     * A fed state: viewport first, then the [LinkFixture] — waiting for
     * the frame through the relay's Choreographer hop (Robolectric's
     * paused looper, as `TranscriptHostTest` does) until it carries both
     * the Wikipedia link and the table link.
     */
    private fun fedLinkState(): TranscriptState {
        val state = TranscriptState()
        state.setViewport(411f, 1f)
        state.engine.setDebugEntries(LinkFixture, working = false)
        val end = System.nanoTime() + 5_000_000_000L
        while (state.frame?.let { f -> f.width() == 411f && linkRow(f, WIKIPEDIA) != null && linkRow(f, TABLE) != null } != true) {
            check(System.nanoTime() < end) { "no frame with the fixture links within 5 s" }
            shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
        }
        TranscriptPerf.reset()
        return state
    }

    private fun host(state: TranscriptState, actions: TranscriptActions) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    TranscriptHost(state, actions, Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        // The first frame lands at the tail (68 R2); the fixture is short
        // enough to fit, but pin the offset anyway so row topPx() is 0-based.
        compose.runOnIdle { state.viewport.setOffset(0f) }
        compose.waitForIdle()
    }

    /** The first placement and [LinkHit] whose url equals [url], in row order, or `null`. */
    private fun linkRow(frame: LayoutFrame, url: String): Pair<RowPlacement, LinkHit>? {
        for (p in frame.rowsIn(0f, Float.MAX_VALUE)) {
            val display = frame.display(p.index) ?: continue
            val hit = display.links.firstOrNull { it.url == url } ?: continue
            return p to hit
        }
        return null
    }

    private fun requireLink(state: TranscriptState, url: String): Pair<RowPlacement, LinkHit> =
        linkRow(state.frame!!, url) ?: error("no link $url in the frame")

    private fun realized(state: TranscriptState, p: RowPlacement): RealizedRow {
        val row = state.realized[p.key]
        check(row != null) { "row ${p.key} (y=${p.y}) is not realized" }
        return row
    }

    /** A click at host-local px (the host is the root's only child, so root-local agrees). */
    private fun clickHost(x: Float, y: Float) {
        compose.onNodeWithTag("transcript").performTouchInput { click(Offset(x, y)) }
        compose.waitForIdle()
    }

    // --------------------------------------------------------------- tests

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun tappingAWikipediaLinkRoutesItAndFiresOnTapAfter() {
        val state = fedLinkState()
        try {
            val opened = mutableListOf<LinkTap>()
            val events = mutableListOf<String>()
            host(state, TranscriptActions(
                openLink = { tap ->
                    opened += tap
                    events += "open"
                },
                onTap = { events += "tap" },
            ))

            val (p, hit) = requireLink(state, WIKIPEDIA)
            val row = realized(state, p)
            val d = 2f
            clickHost((hit.x + hit.w / 2) * d, row.topPx() + (hit.y + hit.h / 2) * d)

            assertEquals(1, opened.size)
            assertEquals(WIKIPEDIA, opened[0].url)
            assertEquals("onTap exactly once, after openLink", listOf("open", "tap"), events)

            // The rects, in window px: the fragment's painted rect and the
            // row's bounds (root-local == window here; the window origin is
            // measured independently via the root's own bounds).
            val root = compose.onRoot().fetchSemanticsNode().boundsInWindow
            val rowTop = row.topPx().toFloat()
            assertEquals(root.left + hit.x * d, opened[0].linkBounds.left, 1f)
            assertEquals(root.top + rowTop + hit.y * d, opened[0].linkBounds.top, 1f)
            assertEquals(root.left + (hit.x + hit.w) * d, opened[0].linkBounds.right, 1f)
            assertEquals(root.top + rowTop + (hit.y + hit.h) * d, opened[0].linkBounds.bottom, 1f)
            assertEquals(root.top + rowTop, opened[0].rowBounds.top, 1f)
            assertEquals(row.heightPx().toFloat(), opened[0].rowBounds.height, 1f)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theHorizontalSlopIs4dpAndAMissStillFiresOnTap() {
        val state = fedLinkState()
        try {
            val opened = mutableListOf<String>()
            var taps = 0
            host(state, TranscriptActions(
                openLink = { tap -> opened += tap.url },
                onTap = { taps++ },
            ))

            val (p, hit) = requireLink(state, WIKIPEDIA)
            val row = realized(state, p)
            val d = 2f
            val y = row.topPx() + (hit.y + hit.h / 2) * d

            // 3 dp left of the left edge: still inside the 4 dp slop.
            clickHost((hit.x - 3f) * d, y)
            assertEquals("the slop edge is a hit", listOf(WIKIPEDIA), opened)

            // 5 dp left: no hit, but onTap still fires.
            clickHost((hit.x - 5f) * d, y)
            assertEquals("a miss must not open anything", listOf(WIKIPEDIA), opened)
            assertEquals("onTap still fired for the miss", 2, taps)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aTableLinkInAScrollerIsTappableAtItsPaintedPosition() {
        val state = fedLinkState()
        try {
            val opened = mutableListOf<String>()
            var taps = 0
            host(state, TranscriptActions(
                openLink = { tap -> opened += tap.url },
                onTap = { taps++ },
            ))

            val (p, hit) = requireLink(state, TABLE)
            // The ticket's fixture rule: the table really is wide, and the
            // table link really is inside its scroller.
            val display = realized(state, p).model!!.display
            assertTrue("the table produced no scroller", display.scrollers.isNotEmpty())
            assertNotNull("the table link is not in a scroller", hit.scroller)
            val idx = hit.scroller!!.toInt()
            val sc = display.scrollers[idx]
            val row = realized(state, p)
            val d = 2f

            // Scroll the table's scroller until the link's painted centre is
            // on screen, then click it there: the offset is read at tap time
            // (the painted position is `sc.x + l.x − offset`).
            var offset = row.scrollerOffsetDp(idx)
            var paintedX = sc.x + hit.x - offset + hit.w / 2
            var swipes = 0
            while ((paintedX < 0f || paintedX > 400f) && swipes < 8) {
                compose.onAllNodesWithTag(ScrollerDefaults.TestTag)[0].performTouchInput { swipeLeft() }
                compose.waitForIdle()
                offset = row.scrollerOffsetDp(idx)
                paintedX = sc.x + hit.x - offset + hit.w / 2
                swipes++
            }
            assertTrue("the scroller never scrolled: offset=$offset", offset > 0f)
            assertTrue("the link never became visible: paintedX=$paintedX", paintedX in 0f..400f)

            clickHost(paintedX * d, row.topPx() + (sc.y + hit.y + hit.h / 2) * d)
            assertEquals("the scroller link opened at its painted position", listOf(TABLE), opened)
            assertEquals(1, taps)
        } finally {
            state.close()
        }
    }

    private companion object {
        const val WIKIPEDIA = "https://ja.wikipedia.org/wiki/x"
        const val TABLE = "https://example.com/table"
    }
}
