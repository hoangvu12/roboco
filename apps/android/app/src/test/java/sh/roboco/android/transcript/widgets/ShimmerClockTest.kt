package sh.roboco.android.transcript.widgets

import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import sh.roboco.android.design.color
import sh.roboco.android.transcript.RowModel
import sh.roboco.android.transcript.StyleFonts
import uniffi.roboco_core.ColorRole

/**
 * The shimmer clock (ticket 122) on the Compose rule with the main clock
 * frozen: the phase follows the frame clock, wraps at 3.4 s, restarts when
 * the host row re-enters composition, and survives a new model version
 * for the same row (the Roboco adaptation of iOS's per-configure rebuild).
 * The reduce-motion case hosts a standalone [LiveShimmer] — `RowContext`
 * and `RealizedRow` need a live `TranscriptState`, which these paths do
 * not; the "shimmer" tag is looked up on this standalone host only (inside
 * a real row, 129's `clearAndSetSemantics` hides widget tags).
 *
 * Frame arithmetic: the test frame duration is 16 ms and every amount
 * advanced is a whole number of frames, so the sweep's elapsed time is
 * exact — 848 ms is phase 0.2494, one frame later 864 ms is 0.2541, and
 * 3408 ms wraps to 8 ms past the period (0.0024). The warm-up drives
 * frames until the clock's first stamp (whether the composition applies
 * during `setContent` or on the first advanced frame is the rule's
 * business), and that stamp is the sweep's t = 0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class ShimmerClockTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun loadFonts() {
        Fonts.resetForTest()
        Fonts.load(context)
    }

    @After
    fun reset() {
        ReduceMotion.resetForTest()
        Fonts.resetForTest()
    }

    /** The specimen's inputs at the window's density (xxhdpi → 3). */
    private fun specimen(): Triple<StyleFonts, RowModel, Int> {
        val fonts = StyleFonts()
        val model = ShimmerSpecimen.model(fonts, 3f)
        return Triple(fonts, model, TranscriptPalette(dark = false).color(ColorRole.TEXT))
    }

    /**
     * Drives frames until the clock reported by [clock] stamps its first
     * frame (the frame loop's t = 0) and returns that stamp in ns. The
     * composition may apply on `setContent` or on the first advanced
     * frame; both converge here.
     */
    private fun driveToFirstStamp(clock: () -> ShimmerClock?): Long {
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            val n = clock()?.lastFrameNanos() ?: -1L
            if (n >= 0L) return n
        }
        error("the clock never stamped a frame within 10 frames")
    }

    @Test
    fun phaseFollowsTheClockAndWraps() {
        compose.mainClock.autoAdvance = false
        var latest: ShimmerClock? = null
        compose.setContent {
            key(1) {
                val c = remember { ShimmerClock() }
                LaunchedEffect(c) { c.run() }
                SideEffect { latest = c }
            }
        }
        // The first stamped frame is the start instant: phase 0.
        driveToFirstStamp { latest }
        assertEquals(0f, latest!!.phase(), 1e-4f)
        // 53 frames = 848 ms: a quarter of the 3.4 s period.
        compose.mainClock.advanceTimeBy(848)
        assertEquals(0.25f, latest!!.phase(), 0.005f)
        // 160 more frames: 3408 ms in total — 8 ms past the wrap, phase 0.
        compose.mainClock.advanceTimeBy(2560)
        assertEquals(0f, latest!!.phase(), 0.005f)
    }

    @Test
    fun reEntryRestartsTheClock() {
        compose.mainClock.autoAdvance = false
        var show by mutableStateOf(true)
        var latest: ShimmerClock? = null
        compose.setContent {
            if (show) key(1) {
                val c = remember { ShimmerClock() }
                LaunchedEffect(c) { c.run() }
                SideEffect { latest = c }
            }
        }
        driveToFirstStamp { latest }
        compose.mainClock.advanceTimeBy(848)
        assertEquals(0.25f, latest!!.phase(), 0.005f)

        // The row leaves the realized band: the host row and its clock are
        // forgotten (a pooled RowView on iOS, whose animations are cleared).
        val gone = latest!!
        compose.runOnUiThread {
            show = false
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()

        // Re-entry: a brand-new clock — phase 0 on its first frame.
        compose.runOnUiThread {
            show = true
            Snapshot.sendApplyNotifications()
        }
        var guard = 0
        while ((latest == null || latest === gone) && guard++ < 10) {
            compose.mainClock.advanceTimeByFrame()
        }
        assertNotSame(gone, latest!!)
        assertEquals(0f, latest!!.phase(), 0.005f)
    }

    @Test
    fun aNewModelVersionKeepsThePhase() {
        compose.mainClock.autoAdvance = false
        val (fonts, modelV1, color) = specimen()
        var model by mutableStateOf(modelV1)
        var latest: ShimmerClock? = null
        compose.setContent {
            key(1) {
                val c = remember { ShimmerClock() }
                LaunchedEffect(c) { c.run() }
                LiveShimmer(c, model, model.display.widgets[0], fonts, color)
                SideEffect { latest = c }
            }
        }
        driveToFirstStamp { latest }
        compose.mainClock.advanceTimeBy(848)
        val before = latest!!
        assertEquals(0.25f, before.phase(), 0.005f)

        // A streaming update: a new model version for the same row key —
        // iOS rebuilds the ShimmerView (t resets); Roboco keeps the clock,
        // like desktop's `shimmer_started_at`.
        compose.runOnUiThread {
            model = ShimmerSpecimen.model(fonts, 3f, version = 2uL)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(16)
        assertSame("the row host kept its clock", before, latest!!)
        // One frame later the elapsed is 864 ms → 0.2541: the sweep ran on,
        // not restarted from a quiet phase.
        assertEquals(0.25f, latest!!.phase(), 0.005f)
    }

    @Test
    fun reduceMotionHidesTheShimmerAndSkipsTheFrameLoop() {
        compose.mainClock.autoAdvance = false
        ReduceMotion.debugOverride = true
        val (fonts, model, color) = specimen()
        val clock = ShimmerClock()
        var show by mutableStateOf(true)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                if (show) LiveShimmer(clock, model, model.display.widgets[0], fonts, color)
            }
        }
        // iOS isHidden = true: no node, and the loop is not even launched —
        // the clock's frame stamp never advances.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(848)
        compose.onNodeWithTag("shimmer").assertDoesNotExist()
        assertEquals(-1L, clock.lastFrameNanos())

        // Back to full motion (the system setting is off): the sweep
        // returns on the next frame.
        compose.runOnUiThread {
            ReduceMotion.debugOverride = null
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("shimmer").assertExists()

        // Teardown: drop the host so the now-running loop is cancelled
        // before the rule idles.
        compose.runOnUiThread {
            show = false
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
    }
}
