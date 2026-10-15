package sh.roboco.android.newsession

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.rememberTextMeasurer
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.abs
import kotlin.math.min
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.HandoffGalleryChat
import sh.roboco.android.design.HandoffGalleryHero
import sh.roboco.android.design.HandoffGalleryTarget
import sh.roboco.android.design.HandoffGalleryText
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Palette
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.RouterlessHandoffRoute
import sh.roboco.android.design.buildHandoffGalleryFixture
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.RouterHooks

/**
 * The handoff overlays' goldens (android-ui-parity #95) — the gallery
 * section's fixture (the fake chat, the fake target and the fixture
 * handoff) at `w393dp-h852dp-xxhdpi`, light and dark, with the clock
 * frozen and glass on its fallback tier:
 *
 * - `newsession/handoff_start_{light,dark}.png` — frame 0: the scene
 *   picture clipped to its 38 dp shape over the hidden chat, the
 *   composer picture at its start frame, the bare bubble text;
 * - `newsession/handoff_mid_{light,dark}.png` — clock at 300 ms with the
 *   bubble found at the first poll: the scene half-dissolved and
 *   scaled, the composer gliding, the fill gaining opacity;
 * - `newsession/handoff_land_{light,dark}.png` — the frame right after
 *   the spring ends: the fill fully opaque on top of the fake bubble,
 *   the text at the same line breaks as the chat's.
 *
 * The land frames are also pixel-checked: the bubble fill is
 * `Palette.userBubble` (#FFFFFF / #19191C) and the text `Palette.text`
 * (#27272C / #E8E8EA) — the ticket's colour acceptance, verified on the
 * same captured frame the golden records.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class HandoffOverlayScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private var hooks: RouterHooks? = null
    private var target: HandoffGalleryTarget? = null
    private var buildHandoff: (() -> CapturedDraftHandoff)? = null

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
        ReduceMotion.debugOverride = false
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
        ReduceMotion.debugOverride = null
    }

    // ------------------------------------------------------------ the stage

    /**
     * The gallery stage: the host over the fake chat, with the fixture
     * builder stashed for the (main-thread) `run` call.
     */
    private fun mount(appearance: Appearance) {
        compose.setContent {
            RobocoTheme(appearance) {
                val palette = RobocoTheme.palette
                val typeScale = LocalTypeScale.current
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val style = uiStyle(Face.Sans, typeScale.bodyScaled(16.5f), palette.text)
                val heroStyle = uiStyle(Face.SansSemibold, typeScale.fixed(28f), palette.text)
                val topInsetPx = WindowInsets.safeDrawing.getTop(density)
                val bottomInsetPx = WindowInsets.safeDrawing.getBottom(density)
                val h = remember { RouterHooks() }
                val t = remember { HandoffGalleryTarget() }
                hooks = h
                target = t
                var widthPx = 0f
                var heightPx = 0f
                buildHandoff = {
                    buildHandoffGalleryFixture(
                        background = palette.background,
                        card = palette.userBubble,
                        cardBorder = palette.text.copy(alpha = 0.12f),
                        hero = HandoffGalleryHero,
                        text = HandoffGalleryText,
                        measurer = measurer,
                        style = style,
                        heroStyle = heroStyle,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        topInsetPx = topInsetPx.toFloat(),
                        bottomInsetPx = bottomInsetPx.toFloat(),
                        density = density.density,
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            widthPx = it.size.width.toFloat()
                            heightPx = it.size.height.toFloat()
                        },
                ) {
                    HandoffOverlayHost(hooks = h, targetOf = { t }) {
                        HandoffGalleryChat(t)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    /** The Reset pass, then a fresh fixture handoff through the host. */
    private fun runHandoff() {
        val h = checkNotNull(hooks) { "mount first" }
        val t = checkNotNull(target) { "mount first" }
        val build = checkNotNull(buildHandoff) { "mount first" }
        compose.runOnIdle {
            t.reset()
            (h.handoffAnimator as DraftHandoffAnimator).run(build(), RouterlessHandoffRoute(t)) {}
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("newsession/handoff_$name.png")
    }

    // -------------------------------------------------------------- goldens

    @Test
    fun handoffStartLight() {
        mount(Appearance.Light)
        runHandoff()
        compose.mainClock.advanceTimeByFrame()
        capture("start_light")
    }

    @Test
    fun handoffStartDark() {
        mount(Appearance.Dark)
        runHandoff()
        compose.mainClock.advanceTimeByFrame()
        capture("start_dark")
    }

    /** The clock at 300 ms (the bubble was found at the first poll). */
    @Test
    fun handoffMidLight() {
        mount(Appearance.Light)
        runHandoff()
        compose.mainClock.advanceTimeByFrame()
        while (compose.mainClock.currentTime < 300) compose.mainClock.advanceTimeByFrame()
        capture("mid_light")
    }

    @Test
    fun handoffMidDark() {
        mount(Appearance.Dark)
        runHandoff()
        compose.mainClock.advanceTimeByFrame()
        while (compose.mainClock.currentTime < 300) compose.mainClock.advanceTimeByFrame()
        capture("mid_dark")
    }

    /**
     * The frame right after the spring ends — the fill fully opaque on
     * the fake bubble — plus the ticket's colour checks on the same
     * frame: `userBubble` for the fill, `text` for the glyphs.
     */
    @Test
    fun handoffLandLight() {
        mount(Appearance.Light)
        runHandoff()
        compose.mainClock.advanceTimeByFrame()
        while (compose.mainClock.currentTime < 1_500 && (target?.finishArrivalCalls ?: 0) == 0) {
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeByFrame()
        capture("land_light")
        assertLandingColours(dark = false)
    }

    @Test
    fun handoffLandDark() {
        mount(Appearance.Dark)
        runHandoff()
        compose.mainClock.advanceTimeByFrame()
        while (compose.mainClock.currentTime < 1_500 && (target?.finishArrivalCalls ?: 0) == 0) {
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeByFrame()
        capture("land_dark")
        assertLandingColours(dark = true)
    }

    // ------------------------------------------------------- colour checks

    /**
     * The landed bubble: the fill is `Palette.userBubble` in the left
     * padding zone (no glyphs there) and the text runs in `Palette.text`
     * somewhere inside the inner rect.
     */
    private fun assertLandingColours(dark: Boolean) {
        val bubble = checkNotNull(target?.bubbleBounds) { "the fake chat laid out its bubble" }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val palette = Palette.of(dark)
        val userBubble = palette.userBubble
        val text = palette.text

        // The fill, in the 15 dp left padding (before the text).
        val fill = pixels[(bubble.left + 8).toInt(), (bubble.top + bubble.height / 2f).toInt()]
        assertTrue(
            "the landed fill is Palette.userBubble ($userBubble), was $fill",
            close(fill, userBubble),
        )

        // The text: the pixel closest to Palette.text inside the inner
        // rect must be the text colour (antialiasing blends toward the
        // fill, so the tolerance is generous).
        val inner = bubbleInner(bubble, 3f)
        var best = Float.MAX_VALUE
        val step = 2
        var y = inner.top.toInt()
        while (y < inner.bottom.toInt()) {
            var x = inner.left.toInt()
            while (x < inner.right.toInt()) {
                val c = pixels[x, y]
                best = min(
                    best,
                    abs(c.red - text.red) + abs(c.green - text.green) + abs(c.blue - text.blue),
                )
                x += step
            }
            y += step
        }
        assertTrue(
            "the landed text is Palette.text ($text), closest sample distance $best",
            best <= 0.25f,
        )
    }

    private fun close(actual: Color, expected: Color): Boolean =
        abs(actual.red - expected.red) <= 2f / 255f &&
            abs(actual.green - expected.green) <= 2f / 255f &&
            abs(actual.blue - expected.blue) <= 2f / 255f
}
