package sh.roboco.android.transcript

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.session.BottomStackModel
import sh.roboco.android.session.SessionBottomStack
import sh.roboco.android.session.rememberBottomStackState

/**
 * The inset sync of ticket 133 — [Modifier.transcriptBottomInset] over
 * 74's real [SessionBottomStack] on Robolectric, at `w412dp-h915dp-mdpi`
 * (1 dp = 1 px). Robolectric reports no insets, so the ticket's 24 dp
 * nav bottom rides through the debug `imeOverride` (the stack bottom
 * reads `max(ime, nav)`), the same trick 74's suite uses; the driven IME
 * values are floored to 24 accordingly. The fake host is a full-size
 * layout node whose placement puts a tagged marker at
 * `contentHeight − offset` — reading the viewport's offset in placement,
 * exactly like 65's row placement — so its bounds prove the same-frame
 * contract of the measure-time delivery.
 *
 * The geometry is the ticket's worked case A/C: `H = 915`, composer 50,
 * nothing else in the stack, `topInset 68`, content 3000. The frozen
 * clock drives one IME value per frame
 * (`Snapshot.sendApplyNotifications()` + `advanceTimeByFrame()`), the
 * 74 recipe. The runway item is viewport-level plain logic (no host) —
 * 69's worked example through the real [RunwayDriver].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-mdpi")
class BottomInsetSyncTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewport = TranscriptViewport()
    private var ime by mutableStateOf(24.dp)
    private var composerTall by mutableStateOf(false)

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    // ------------------------------------------------------------ the frame

    /**
     * Composes the stack with a 50 dp (120 dp tall) composer stand-in and
     * the fake host, and lands the viewport on the worked geometry
     * (the fake host has no 65 `SideEffect`, so the model is set up here).
     */
    private fun host() {
        viewport.setViewportHeight(915f)
        viewport.setTopInset(68f)
        viewport.onFrameApplied(CONTENT, first = true)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val stackState = rememberBottomStackState()
                // Written in composition, read in measure: a change
                // invalidates the measure and lands in the same frame.
                stackState.imeOverride = ime
                SessionBottomStack(
                    state = stackState,
                    model = BottomStackModel(),
                    transcript = { insets -> FakeHost(viewport, insets.covered) },
                    pill = {},
                    queue = {},
                    questions = {},
                    composer = { ComposerStandIn(tall = composerTall) },
                    jump = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /** State write + notification + one frame, the frozen-clock recipe. */
    private fun applyFrame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    /** The driven value with the nav floor (Robolectric reports no insets). */
    private fun effective(value: Int): Dp = value.coerceAtLeast(24).dp

    /**
     * The S7 invariant: the content end sits at `stackTop − 8`
     * (= `915 − max(ime, 24) − 8 − 50 − 8`) within 0.5 dp, and the
     * viewport height never changes (S1).
     */
    private fun assertSynced(imeValue: Int) {
        val composerTop = compose.onNodeWithTag("bottom-stack-composer").getBoundsInRoot().top.value
        val endTop = compose.onNodeWithTag(CONTENT_END).getBoundsInRoot().top.value
        assertEquals("content end rides the composer (ime $imeValue)", composerTop - 8f, endTop, 4f)
        assertEquals(
            "the worked value (ime $imeValue)",
            915f - max(imeValue, 24) - 8f - 50f - 8f,
            endTop,
            0.5f,
        )
        assertEquals("viewportHeight never changes (S1)", 915f, viewport.viewportHeight, 1e-3f)
    }

    // ------------------------------------------------------------- the ride

    /**
     * Every frame of the ride — open, close, and the rapid reversals of
     * the ticket's last eight values — keeps the content end at
     * `stackTop − 8`.
     */
    @Test
    fun everyFrameOfTheImeRideKeepsTheContentEndAtTheStackTop() {
        host()
        for (value in SEQUENCE) {
            ime = effective(value)
            applyFrame()
            assertSynced(value)
        }
    }

    /** The worked table: ime 0 → 2175, ime 150 → 2301, ime 300 → 2451. */
    @Test
    fun theWorkedOffsetTableHolds() {
        host()
        assertEquals("ime 0 (nav 24): the first delivered inset pins 3000 + 90 − 915", 2175f, viewport.offset, 0.5f)
        ime = effective(150)
        applyFrame()
        assertEquals("ime 150: 3000 + 216 − 915", 2301f, viewport.offset, 0.5f)
        ime = effective(300)
        applyFrame()
        assertEquals("ime 300: 3000 + 366 − 915", 2451f, viewport.offset, 0.5f)
    }

    /** Not following: the offset is untouched, the content is simply covered. */
    @Test
    fun whileNotFollowingTheOffsetStaysPutThroughTheWholeRide() {
        host()
        assertEquals(2175f, viewport.offset, 0.5f)
        compose.runOnIdle { viewport.releaseFollow() }
        compose.waitForIdle()
        for (value in SEQUENCE) {
            ime = effective(value)
            applyFrame()
            assertEquals("the reading position is kept (ime $value)", 2175f, viewport.offset, 0.5f)
            assertEquals("viewportHeight never changes (S1)", 915f, viewport.viewportHeight, 1e-3f)
        }
    }

    /**
     * Composer growth 50 → 120 dp in one frame at ime 300: the content
     * end moves from 549 to 479 in that same frame — no dip and glide
     * back (the runway/pin order of 69/68).
     */
    @Test
    fun composerGrowthMovesTheContentEndInTheSameFrame() {
        host()
        ime = effective(300)
        applyFrame()
        assertEquals(549f, compose.onNodeWithTag(CONTENT_END).getBoundsInRoot().top.value, 0.5f)
        assertEquals(2451f, viewport.offset, 0.5f)
        composerTall = true
        applyFrame()
        assertEquals("same frame, no dip", 479f, compose.onNodeWithTag(CONTENT_END).getBoundsInRoot().top.value, 0.5f)
        assertEquals("the pin uses the new maxOffset: 3000 + 436 − 915", 2521f, viewport.offset, 0.5f)
    }

    // ----------------------------------------------------- the fake pieces

    /**
     * The fake host: a full-size custom layout with 133's modifier
     * outermost, and a 1 dp tagged marker placed at
     * `contentHeight − offset` in the placement lambda — the offset read
     * in placement, like 65's rows, so the same frame's pinned offset
     * places it.
     */
    @Composable
    private fun FakeHost(viewport: TranscriptViewport, covered: Dp) {
        val density = LocalDensity.current.density
        Layout(
            content = { Box(Modifier.width(40.dp).height(1.dp).testTag(CONTENT_END)) },
            modifier = Modifier
                .fillMaxSize()
                .transcriptBottomInset(viewport, covered), // 133, outermost
        ) { measurables, constraints ->
            val marker = measurables.first().measure(Constraints())
            layout(constraints.maxWidth, constraints.maxHeight) {
                val y = ((viewport.contentHeight - viewport.offset) * density).roundToInt()
                marker.place(0, y)
            }
        }
    }

    /** The 50 dp composer stand-in (120 dp when tall). */
    @Composable
    private fun ComposerStandIn(tall: Boolean) {
        Box(Modifier.fillMaxWidth().height(if (tall) 120.dp else 50.dp))
    }

    // ------------------------------------------------ the runway, no host

    /**
     * 69's worked example, viewport-level: `topInset 100`,
     * `viewportHeight 800`, prompt at `y 1000`, natural 1100 — so
     * `hold = 900` and `maxOffset` stays 900 whatever the insets, and
     * the prompt row's screen y stays at `100 ± 0.5` while the bottom
     * inset walks 80 → 230 → 380 → 230 → 80.
     */
    @Test
    fun theRunwayHoldsThePromptUnderTheHeaderAcrossInsetChanges() {
        val vp = TranscriptViewport()
        var current: RunRows? = null
        val driver = RunwayDriver(vp) { current }
        vp.onGeometryChanged = driver::refresh
        vp.setTopInset(100f)
        vp.setViewportHeight(800f)

        current = oldRows()
        vp.onFrameApplied(driver.heightFor(oldRows()), first = true) // content 1100, offset 380
        driver.beginOwnTurn() // before = {K0, K1}
        current = promptRows()
        vp.onFrameApplied(driver.heightFor(promptRows()), first = false) // the reservation lands

        for (inset in intArrayOf(80, 230, 380, 230, 80)) {
            vp.setBottomInset(inset.toFloat())
            assertEquals("maxOffset stays 900 (bottom inset $inset)", 900f, vp.maxOffset, 0.5f)
            assertEquals("the prompt's screen y stays under the header ($inset)", 100f, 1000f - vp.offset, 0.5f)
        }
    }

    /** A fake frame: rows as (key, isUser, top y), plus the natural height. */
    private class RunRows(
        private val rows: List<Row>,
        override val naturalHeight: Float,
    ) : RunwayRows {
        data class Row(val key: ULong, val user: Boolean, val y: Float)

        override val rowCount: Int get() = rows.size
        override fun userKeyAt(index: Int): ULong? = rows.getOrNull(index)?.takeIf { it.user }?.key
        override fun yOf(key: ULong): Float? = rows.firstOrNull { it.key == key }?.y
    }

    /** The frame current at send time: two old user rows, natural 1100. */
    private fun oldRows() = RunRows(
        listOf(RunRows.Row(K0, true, 0f), RunRows.Row(K1, true, 500f)),
        naturalHeight = 1100f,
    )

    /** The prompt echo lands at `y = 1000` (hold 900, minimum 1620). */
    private fun promptRows() = RunRows(
        listOf(
            RunRows.Row(K0, true, 0f),
            RunRows.Row(K1, true, 500f),
            RunRows.Row(K, true, 1000f),
        ),
        naturalHeight = 1100f,
    )

    private companion object {
        /** The marker's tag. */
        const val CONTENT_END = "content-end"

        /** The content height (dp). */
        const val CONTENT = 3000f

        /** Row keys of the runway fixture. */
        val K0 = 1uL
        val K1 = 2uL
        val K = 3uL

        /**
         * The ticket's frame sequence: open to 300, close, and the last
         * eight values are rapid reversals.
         */
        val SEQUENCE = intArrayOf(0, 40, 110, 190, 260, 300, 300, 240, 150, 60, 0, 0, 90, 200, 130, 30, 170, 300)
    }
}
