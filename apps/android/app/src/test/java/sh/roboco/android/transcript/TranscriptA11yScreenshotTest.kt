package sh.roboco.android.transcript

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import sh.roboco.android.core.Fonts
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UniffiSandbox
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme

/**
 * Ticket 129's goldens (recorded by `:app:recordRoborazziDebug`, verified
 * by `:app:verifyRoborazziDebug`): the [A11yFixture] over the real host
 * with [LocalTranscriptA11yBounds] on, light, at `w411dp-h891dp-xhdpi`
 * (d = 2) — the invisible nodes outlined: link fragments in
 * `Palette.accent` (violet), exposed widget groups in `Palette.danger`
 * (red), 1 px strokes exactly on the painted rects. The second golden
 * scrolls the table's scroller 150 dp through its `ScrollBy` semantics
 * action (116 keeps `horizontalScroll`'s default semantics): the
 * `wiki` outline slides with the painted text, unclipped, past the
 * scroller's right edge.
 *
 * Side by side with iOS: the outlines sit exactly on the link text of
 * the paragraph, the list item and the table cell, and on the code
 * block's copy button. Rasterisation is host-dependent: record on the
 * Ubuntu CI runner, like every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptA11yScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { TextEngine.await() }
        // Load the fonts here too: another class's @After Fonts.resetForTest()
        // (same Robolectric sandbox) clears FontChain while TextEngine keeps
        // its built system, so await() never re-runs Fonts.load and the row
        // paints throw "FontChain not built" (verify run 38599396791). The
        // other transcript screenshot tests load it the same way.
        Fonts.load(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun a11yBoundsLight() {
        shot("transcript/a11y_bounds_light.png", scrollTable = false)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun a11yBoundsScrolledLight() {
        shot("transcript/a11y_bounds_scrolled_light.png", scrollTable = true)
    }

    private fun shot(file: String, scrollTable: Boolean) {
        val state = TranscriptState()
        try {
            // Viewport first, then entries (a width-0 frame never builds);
            // the frame arrives through the relay's Choreographer hop.
            state.setViewport(411f, 1f)
            state.engine.setDebugEntries(A11yFixture, working = true)
            val end = System.nanoTime() + 5_000_000_000L
            while (state.frame?.let { it.width() == 411f && it.rowCount() == 7u } != true) {
                check(System.nanoTime() < end) { "no 7-row frame at width 411 within 5 s" }
                shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
            }
            TranscriptPerf.reset()
            compose.setContent {
                RobocoTheme(Appearance.Light) {
                    // Frozen glyph clock: the Working row's trailer would
                    // otherwise run the theme's endless withFrameNanos loop,
                    // so the auto-advancing clock never idles.
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        CompositionLocalProvider(LocalTranscriptA11yBounds provides true) {
                            Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                                TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle { state.viewport.setOffset(0f) }
            compose.waitForIdle()
            if (scrollTable) scrollTableBy150Dp()
            compose.onRoot().captureRoboImage(file)
        } finally {
            state.close()
        }
    }

    /**
     * Scrolls the table scroller 150 dp through its `ScrollBy` semantics
     * action (the same call as `TranscriptAccessibilityTest`'s scroller
     * test): no public scroll API exists (116), and the link outlines must
     * slide with the painted text.
     */
    private fun scrollTableBy150Dp() {
        val d = 2f
        val scrollers = compose.onAllNodesWithTag(ScrollerDefaults.TestTag, useUnmergedTree = true)
        val infos = scrollers.fetchSemanticsNodes()
        val tableIdx = infos.indexOfFirst { info ->
            (info.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue() ?: 0f) > 0f
        }
        check(tableIdx >= 0) { "no scrollable scroller in the fixture" }
        scrollers[tableIdx].performSemanticsAction(SemanticsActions.ScrollBy) { it(150f * d, 0f) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
}
