package sh.roboco.android.design

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared clock (android-ui-parity #11) through a real composition: one
 * frame loop for every animated glyph on screen, subscription lifecycle,
 * the reduce-motion suppression, the no-recomposition rule and the default
 * measured sizes. The clock is frozen whenever the animation itself is not
 * under test; `autoAdvance = false` + `advanceTimeBy` drive the loop the
 * way the glass tests drive springs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StatusGlyphClockTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    /** Composes [glyphs] under the theme, capturing the subtree's clock. */
    private fun composeGlyphs(glyphs: @Composable () -> Unit): GlyphClock {
        var clock: GlyphClock? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock {
                    clock = LocalGlyphClock.current
                    glyphs()
                }
            }
        }
        compose.waitForIdle()
        return requireNotNull(clock) { "the clock was never provided" }
    }

    /**
     * Lets a state write made outside composition land while the clock is
     * frozen: under Robolectric `runOnUiThread` runs inline, the write
     * only reaches the Recomposer after `Snapshot.sendApplyNotifications()`
     * (normally posted to the paused main Looper), and the recomposition
     * itself waits for a frame, which a frozen clock never sends on its own
     * (`waitForIdle` treats a frozen clock as idle). One frame recomposes
     * and runs the DisposableEffects that own the subscription count.
     */
    private fun settleFrozen() {
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ subscription

    @Test
    fun oneAnimatedGlyphSubscribesAndTicks() {
        val clock = composeGlyphs { StatusGlyph(StatusGlyphKind.Spinner) }
        assertEquals(1, clock.subscribers)
        assertEquals("no frame may tick before the clock is advanced", 0L, clock.framesTicked)
        compose.mainClock.advanceTimeBy(100)
        assertTrue("framesTicked must increase, was ${clock.framesTicked}", clock.framesTicked > 0L)
        assertEquals(1, clock.subscribers)
    }

    @Test
    fun threeGlyphsShareASingleLoop() {
        val clock = composeGlyphs {
            StatusGlyph(StatusGlyphKind.Spinner)
            StatusGlyph(StatusGlyphKind.Spinner)
            StatusGlyph(StatusGlyphKind.Trailer)
        }
        assertEquals(3, clock.subscribers)
        compose.mainClock.advanceTimeBy(100)
        val ticks = clock.framesTicked
        assertTrue(ticks > 0L)
        // One loop: each frame ticks exactly once, not once per glyph.
        compose.mainClock.advanceTimeByFrame()
        assertEquals(ticks + 1L, clock.framesTicked)
        assertEquals(3, clock.subscribers)
    }

    @Test
    fun removingEveryGlyphStopsTheLoop() {
        var show by mutableStateOf(true)
        val clock = composeGlyphs {
            if (show) {
                StatusGlyph(StatusGlyphKind.Spinner)
                StatusGlyph(StatusGlyphKind.Trailer)
            }
        }
        assertEquals(2, clock.subscribers)
        compose.mainClock.advanceTimeBy(100)

        compose.runOnUiThread { show = false }
        settleFrozen()
        assertEquals(0, clock.subscribers)
        compose.mainClock.advanceTimeBy(100) // let the cancellation settle
        val ticks = clock.framesTicked
        compose.mainClock.advanceTimeBy(200)
        assertEquals("the loop must stop when the last glyph leaves", ticks, clock.framesTicked)
    }

    @Test
    fun staticKindsNeverSubscribe() {
        val clock = composeGlyphs {
            StatusGlyph(StatusGlyphKind.Dot(Color(0xFF5B43E8)))
            StatusGlyph(StatusGlyphKind.Check(Color(0xFF15803D)))
            StatusGlyph(StatusGlyphKind.None)
        }
        assertEquals(0, clock.subscribers)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(0, clock.subscribers)
        assertEquals(0L, clock.framesTicked)
    }

    // ---------------------------------------------------------- reduce motion

    @Test
    fun reduceMotionSuppressesTheClockAndFlipsLive() {
        // LocalReduceMotion driven by a mutableStateOf, so the flip happens
        // without recreating the composition — the loop starts or stops and
        // the cells jump to the static or animated alpha.
        var reduce by mutableStateOf(true)
        var clock: GlyphClock? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalReduceMotion provides reduce) {
                    ProvideGlyphClock {
                        clock = LocalGlyphClock.current
                        StatusGlyph(StatusGlyphKind.Spinner)
                        StatusGlyph(StatusGlyphKind.Trailer)
                    }
                }
            }
        }
        compose.waitForIdle()
        val c = requireNotNull(clock) { "the clock was never provided" }
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("reduce motion: nothing subscribes", 0, c.subscribers)
        assertEquals("reduce motion: no frames tick", 0L, c.framesTicked)

        compose.runOnUiThread { reduce = false }
        settleFrozen()
        assertEquals("the live flip subscribes both glyphs", 2, c.subscribers)
        compose.mainClock.advanceTimeBy(100)
        assertTrue("the live flip restarts the loop", c.framesTicked > 0L)
    }

    // -------------------------------------------------------- no recomposition

    @Test
    fun theClockDoesNotRecomposeWhileAnimating() {
        var recompositions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock {
                    SideEffect { recompositions++ }
                    StatusGlyph(StatusGlyphKind.Spinner)
                }
            }
        }
        compose.mainClock.advanceTimeBy(200)
        val settled = recompositions
        assertTrue("the fixture must have composed", settled > 0)
        compose.mainClock.advanceTimeBy(500)
        assertEquals("frame ticks must invalidate only the draw", settled, recompositions)
    }

    // --------------------------------------------------------------- the box

    @Test
    fun theDefaultBoxIsTwelveDpAndScaleMultipliesIt() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Row {
                        StatusGlyph(StatusGlyphKind.Spinner, modifier = Modifier.testTag("default"))
                        StatusGlyph(StatusGlyphKind.Spinner, modifier = Modifier.testTag("scaled"), scale = 1.75f)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("default").assertWidthIsEqualTo(12.dp).assertHeightIsEqualTo(12.dp)
        compose.onNodeWithTag("scaled").assertWidthIsEqualTo(21.dp).assertHeightIsEqualTo(21.dp)
    }

    @Test
    fun theCallerSizeWinsOverTheDefaultBox() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    StatusGlyph(StatusGlyphKind.Trailer, modifier = Modifier.testTag("caller").size(14.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("caller").assertWidthIsEqualTo(14.dp).assertHeightIsEqualTo(14.dp)
    }

    // ----------------------------------------------------- the fallback clock

    @Test
    fun glyphsOutsideProvideGlyphClockDriveTheirOwnClock() {
        var recomposed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // No ProvideGlyphClock here: the theme still provides one,
                // so also drop the local to exercise the true fallback path.
                CompositionLocalProvider(LocalGlyphClock provides null) {
                    SideEffect { recomposed++ }
                    StatusGlyph(StatusGlyphKind.Spinner)
                }
            }
        }
        compose.mainClock.advanceTimeBy(200)
        val settled = recomposed
        compose.mainClock.advanceTimeBy(300)
        assertEquals("the fallback clock must not recompose either", settled, recomposed)
    }

    @Test
    fun subscriptionBookkeepingSurvivesCompositionDisposal() {
        // DisposableEffect (not composition) owns the counter: an effect
        // that is keyed restarts atomically — dispose the old value, apply
        // the new one — so the count can never leak.
        var kind by mutableStateOf<StatusGlyphKind>(StatusGlyphKind.Spinner)
        var clock: GlyphClock? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock {
                    clock = LocalGlyphClock.current
                    StatusGlyph(kind)
                }
            }
        }
        compose.waitForIdle()
        val c = requireNotNull(clock)
        assertEquals(1, c.subscribers)

        compose.runOnUiThread { kind = StatusGlyphKind.Dot(Color(0xFF5B43E8)) }
        settleFrozen()
        assertEquals("a static kind must release its subscription", 0, c.subscribers)

        compose.runOnUiThread { kind = StatusGlyphKind.Spinner }
        settleFrozen()
        assertEquals(1, c.subscribers)
    }
}
