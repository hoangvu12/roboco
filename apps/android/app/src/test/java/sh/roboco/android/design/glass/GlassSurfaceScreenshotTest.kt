package sh.roboco.android.design.glass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The glass material goldens (ticket 07, the step 9 fixture shared with the
 * `@Preview`s in `GlassSurface.kt`), recorded by `recordRoborazziDebug` and
 * verified by `verifyRoborazziDebug`. Paths are relative to the
 * `roborazzi { outputDir }` (`src/test/screenshots`).
 *
 * `Glass.forceFallback = true` is set for the whole class so the goldens
 * never depend on how Robolectric reports hardware acceleration: they
 * capture the Fallback tier (flat fallback fill, rim, shadow). The light
 * golden's pixel probes (the acceptance criteria) are taken around the
 * probe capsule at x 200–360 dp, y 220–264 dp (xhdpi = 2 px/dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h300dp-xhdpi")
class GlassSurfaceScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun forceFallbackTier() {
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) { GlassSurfaceFixture() }
        }
        compose.onRoot().captureRoboImage(file)
    }

    /** The fixture's minimum progress — the one animating surface. */
    private fun minProgress(): Float =
        compose.onAllNodes(hasGlassProgress())
            .fetchSemanticsNodes()
            .map { it.config[GlassProgressKey] }
            .minOrNull()
            ?: error("no GlassProgressKey nodes in the fixture")

    private fun hasGlassProgress(): SemanticsMatcher =
        SemanticsMatcher("has GlassProgress") { it.config.contains(GlassProgressKey) }

    @Test
    fun light() {
        shot(Appearance.Light, "design/glass_surface_light.png")
    }

    @Test
    fun dark() {
        shot(Appearance.Dark, "design/glass_surface_dark.png")
    }

    @Test
    fun materializeHalfLight() {
        // The static fixture's `visible = false` surface, flipped visible:
        // content and material captured at ≈50 % (+175 ms of a 350 ms
        // materialize). The clock is frozen; the animation origin is
        // calibrated (advance frames until its first tick = 16 ms of
        // playtime), then 10 frames = 176 ms ≈ half.
        var materialize by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) { GlassSurfaceFixture(materialize) }
        }
        compose.waitForIdle()

        materialize = true
        compose.waitForIdle()
        var frames = 0
        while (minProgress() <= 0.001f && frames < 12) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        check(frames < 12) { "the materialize animation never started" }
        compose.mainClock.advanceTimeBy(160) // 10 whole frames
        check(minProgress() in 0.45f..0.56f) { "expected ≈50% materialized, was ${minProgress()}" }
        compose.onRoot().captureRoboImage("design/glass_surface_materialize_half_light.png")
    }
}
