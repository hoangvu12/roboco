package sh.roboco.android.transcript

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
import sh.roboco.android.design.RobocoTheme

/**
 * The host goldens (ticket 65; recorded by `:app:recordRoborazziDebug`,
 * verified by `:app:verifyRoborazziDebug`): [HostFixture] at
 * `w411dp-h891dp-xhdpi` (d = 2) on the `background` role box, light and
 * dark, through the real core — the frame is awaited before `setContent`
 * so the capture is the settled transcript, not the pre-frame state. What
 * the goldens must show, side by side with the iOS Lab (`-lab`, the same
 * three entries) at 411 vs 393 pt: the row order, the right-aligned user
 * bubbles, the code block and the heading; pixel positions differ only by
 * width.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner
 * (android-app#30), like every golden here. The gallery section's own
 * golden (ticket 22's `GalleryScreenshotTest`) is the deterministic
 * pre-frame state by design — see `TranscriptHostGallerySection`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptHostScreenshotTest {

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
    fun hostFixtureLight() {
        shot(Appearance.Light, "transcript/host_fixture_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun hostFixtureDark() {
        shot(Appearance.Dark, "transcript/host_fixture_dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        val state = TranscriptState()
        try {
            // Viewport first, then entries: a width-0 frame never builds.
            state.setViewport(411f, 1f)
            state.engine.setDebugEntries(HostFixture, working = false)
            // The frame arrives through the relay's Choreographer callback,
            // which the paused main looper schedules one frame delay ahead;
            // `compose.waitUntil` never runs or advances that looper.
            val end = System.nanoTime() + 5_000_000_000L
            while (state.frame?.let { it.width() == 411f && it.rowCount() == 6u } != true) {
                check(System.nanoTime() < end) { "no 6-row frame at width 411 within 5 s" }
                shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
            }
            TranscriptPerf.reset()
            compose.setContent {
                RobocoTheme(appearance) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
                    }
                }
            }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(file)
        } finally {
            state.close()
        }
    }
}
