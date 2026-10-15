package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two-layer root swap (android-ui-parity #35) on the ticket's device
 * (`w393dp-h852dp-xxhdpi`, the clock frozen): the CATransition composite —
 * the incoming root below at alpha 1, the outgoing root **on top** fading
 * along `Motion.rootSwap()` (350 ms easeInOut, symmetric → exactly 0.5 at
 * 175 ms) — `beforeSwap` runs before either layer changes, no input
 * during the fade, an interrupted swap ends with a single layer, and the
 * outgoing root keeps its `remember` state through the fade (the `key()`
 * identity).
 *
 * The fake layers fill the window with distinct opaque colours
 * (a = `#5B43E8`, b = `#F3F3F5`), so the mid-fade pixel at 16 dp, 16 dp
 * (48 px at xxhdpi) is the exact 50/50 mix `#A79BEE` — a both-layers fade
 * would give `#CD9DF1` and fail. Layer identity is observed without
 * semantics: each layer records its fresh compositions and its
 * remember-scoped counter through [SideEffect].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class RootCrossfadeTest {

    @get:Rule
    val compose = createComposeRule()

    private var target by mutableStateOf("a")
    private var beforeSwaps = 0
    private var bComposedWhenBeforeSwapRan: Boolean? = null

    /** One entry per fresh layer composition (the key()-identity probe). */
    private val compositions = mutableListOf<String>()

    /** The remember-scoped counter of each live layer, observed per frame. */
    private val observed = mutableMapOf<String, Int>()

    @Composable
    private fun Content() {
        RootCrossfade(
            target = target,
            beforeSwap = {
                beforeSwaps++
                bComposedWhenBeforeSwapRan = "b" in compositions
            },
        ) { k ->
            Layer(k)
        }
    }

    @Composable
    private fun Layer(key: String) {
        // A remember-scoped counter: 0 on a fresh composition, +1 per tap.
        // The leaving layer keeps its value mid-fade iff its composition
        // (and so its key() slot) survives the swap.
        val counter = remember {
            compositions += key
            mutableStateOf(0)
        }
        SideEffect { observed[key] = counter.value }
        Box(
            Modifier
                .fillMaxSize()
                .background(if (key == "a") FillA else FillB)
                .testTag(key)
                .clickable { counter.value++ },
        ) {
            BasicText("$key × ${counter.value}", Modifier.align(Alignment.Center))
        }
    }

    private fun start() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Content() }
        compose.waitForIdle()
    }

    /**
     * A frozen-clock target write: the test thread's write needs explicit
     * apply notifications, then one frame — the composition that sees the
     * new target, after which the swap effect runs `beforeSwap` and starts
     * the fade (the fade's first frame is the next one).
     */
    private fun swapTo(k: String) {
        compose.runOnUiThread {
            target = k
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /**
     * Advances [ms] of animation time and settles the frame. A click's
     * `counter.value++` is a global-snapshot write whose apply notification
     * is only posted (the frozen clock does not drain it before the frame),
     * so without the explicit send the last tap's recomposition would lag a
     * frame behind (the r23 "expected 2 but was 1").
     */
    private fun advance(ms: Long) {
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun pixel(x: Int, y: Int): Int = compose.onRoot().captureToImage().toPixelMap()[x, y].toArgb()

    /** Channel-wise comparison; the ticket's midpoint tolerance is ±2, ±4 covers a frame's rounding. */
    private fun assertPixelNear(expected: Int, actual: Int, tolerance: Int) {
        for (shift in intArrayOf(16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val act = (actual shr shift) and 0xFF
            assertTrue(
                "channel ${(expected shr shift) and 0xFF}/${(actual shr shift) and 0xFF} differed",
                abs(act - e) <= tolerance,
            )
        }
    }

    // ------------------------------------------------- the first composition

    @Test
    fun theInitialTargetIsDrawnAtAlpha1WithNoOutgoingLayer() {
        start()
        advance(16)

        compose.onNodeWithTag("a").assertExists()
        compose.onNodeWithTag("root-swap-blocker").assertDoesNotExist()
        // 16 dp in, away from the centred label: exactly a's fill.
        assertPixelNear(0xFF5B43E8.toInt(), pixel(48, 48), tolerance = 0)
    }

    // ------------------------------------------------------- before the swap

    @Test
    fun beforeSwapRunsOnceBeforeTheLayersChange() {
        start()
        advance(16)
        assertEquals(0, beforeSwaps)

        swapTo("b")

        assertEquals("beforeSwap runs exactly once", 1, beforeSwaps)
        // The effect runs after the composition that saw the new target —
        // that composition still shows the old root, so "b" has never
        // composed when beforeSwap fires.
        assertEquals(false, bComposedWhenBeforeSwapRan)
        // The effect's layer writes reach the composition on the next frame.
        advance(16)
        compose.onNodeWithTag("b").assertExists()
    }

    // -------------------------------------------------------- the fade curve

    @Test
    fun theOutgoingAlphaIsHalfAt175Ms() {
        start()
        advance(16)
        swapTo("b")

        compose.mainClock.advanceTimeByFrame() // the swap frame: old on top at alpha 1
        compose.mainClock.advanceTimeBy(175)
        compose.waitForIdle()

        // The exact 50/50 mix of the two fills (0x5B43E8 over 0xF3F3F5):
        // a both-layers fade over b's fill would be 0.75·b + 0.25·a.
        assertPixelNear(0xFFA79BEE.toInt(), pixel(48, 48), tolerance = 4)
    }

    @Test
    fun theOutgoingLayerLeavesAfter350Ms() {
        start()
        advance(16)
        swapTo("b")

        advance(16)
        advance(400)

        compose.onNodeWithTag("b").assertExists()
        assertTrue("the a layer must be gone", compose.onAllNodesWithTag("a").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("root-swap-blocker").assertDoesNotExist()
    }

    // ------------------------------------------------- input during the fade

    @Test
    fun tapsDuringTheFadeAreNotDelivered() {
        start()
        advance(16)
        compose.onNodeWithTag("a").performClick()
        compose.onNodeWithTag("a").performClick()
        advance(16) // the frozen clock: a frame recomposes the counter
        assertEquals(2, observed["a"])

        swapTo("b")
        advance(16)
        advance(100)

        // Mid-fade: the blocker (zIndex above both layers, Initial pass)
        // consumes everything — neither root sees the tap.
        compose.onNodeWithTag("b").performClick()
        advance(16) // a delivered tap would show up after this frame
        assertEquals(2, observed["a"])
        assertEquals(0, observed["b"])
    }

    // ------------------------------------------------------ interrupted swap

    @Test
    fun anInterruptedSwapEndsWithASingleLayer() {
        start()
        advance(16)
        swapTo("b")
        advance(16)
        advance(100) // mid-fade, b is taking over

        swapTo("a")
        advance(16)
        advance(400)

        assertTrue("the b layer must be gone", compose.onAllNodesWithTag("b").fetchSemanticsNodes().isEmpty())
        assertEquals(1, compose.onAllNodesWithTag("a").fetchSemanticsNodes().size)
        compose.onNodeWithTag("root-swap-blocker").assertDoesNotExist()
    }

    // -------------------------------------------------- key() state identity

    @Test
    fun theOutgoingRootKeepsItsStateDuringTheFade() {
        start()
        advance(16)
        repeat(3) { compose.onNodeWithTag("a").performClick() }
        advance(16) // the frozen clock: a frame recomposes the counter
        assertEquals(3, observed["a"])
        assertEquals(1, compositions.count { it == "a" })

        swapTo("b")
        advance(16)
        advance(100)

        // Mid-fade the leaving layer still composes its remembered
        // counter — a fresh composition would have reset it to 0.
        assertEquals(3, observed["a"])
        assertEquals("the leaving root keeps its composition", 1, compositions.count { it == "a" })
        assertEquals(1, compositions.count { it == "b" })

        advance(16)
        advance(300)
        assertTrue("the a layer must be gone", compose.onAllNodesWithTag("a").fetchSemanticsNodes().isEmpty())
        assertEquals(1, compositions.count { it == "a" })
    }

    private companion object {
        /** The ticket's fake fills: Pairing's accent and the Phone background. */
        val FillA = Color(0xFF5B43E8)
        val FillB = Color(0xFFF3F3F5)
    }
}
