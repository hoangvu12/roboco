package sh.roboco.android.debug

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.transcript.TranscriptState

/**
 * The Transcript Lab goldens (android-ui-parity #135; recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`):
 * one fixture turn through the real core at `w411dp-h891dp-xhdpi`
 * (d = 2), light and dark, IME hidden, landed at the bottom — side by
 * side with an iOS `-lab -turns 1` capture (393 pt) the row order, the
 * right-aligned user bubble, the code block, the table and the title
 * placement must match; pixel positions differ only by width. The input
 * row differs by design (the Roboco adaptation).
 *
 * Rasterisation is host-dependent: record on the Ubuntu runner, like
 * every golden here. This class also pins the two formula checks the
 * ticket calls unit-checkable — `topInset == statusBarTop + 44` and
 * `bottomInset == max(navBottom, H − inputTop + 8)` — with fake insets
 * dispatched on the compose root, and the fake image loader over the
 * bundled PNG.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptLabScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(ApplicationProvider.getApplicationContext<Context>())
        // Build the shared engine off the main looper first, as the app does.
        runBlocking { TextEngine.await() }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun labLight() {
        shot(Appearance.Light, "debug/transcript_lab_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun labDark() {
        shot(Appearance.Dark, "debug/transcript_lab_dark.png")
    }

    /**
     * The ticket's worked example at d = 2, single-line 44-dp input row:
     * IME 0 → `topInset 68`, `bottomInset 84`; IME 300 dp →
     * `bottomInset 360` — the input row's covered height wins over the
     * navigation bar either way, and the top inset never moves.
     */
    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun insetsTrackTheInputRow() {
        val state = TranscriptState()
        val controller = TranscriptLabController(state, scope = CoroutineScope(Dispatchers.Unconfined))
        try {
            compose.setContent {
                RobocoTheme(Appearance.Light) {
                    TranscriptLabScreen(controller, LabOptions(turns = 1), runScripts = false)
                }
            }
            compose.waitForIdle()
            val window = compose.activity.window
            val root = window.decorView
            // The real lab is edge-to-edge (enableEdgeToEdge); with
            // decorFitsSystemWindows the DecorView would consume the
            // dispatched insets before they reach Compose.
            WindowCompat.setDecorFitsSystemWindows(window, false)

            dispatchLabInsets(root, imeVisible = false, imeBottomPx = 0)
            compose.waitForIdle()
            assertEquals(68f, state.viewport.topInset, 0.5f)
            assertEquals(84f, state.viewport.bottomInset, 0.5f)

            dispatchLabInsets(root, imeVisible = true, imeBottomPx = 600)
            compose.waitForIdle()
            assertEquals(68f, state.viewport.topInset, 0.5f)
            assertEquals(360f, state.viewport.bottomInset, 0.5f)
        } finally {
            state.close()
        }
    }

    /** The fake loader: non-null, 256×256 (side 256 → inSampleSize 2), memoized. */
    @Test
    @Config(sdk = [36])
    fun labImageLoadsFromTheBundledAsset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = runBlocking { LabImages.load(context, 256) }
        assertNotNull("lab/lab-image.png did not decode", first)
        assertEquals(256, first!!.width)
        assertEquals(256, first.height)
        // Memoized: the cached peek and the second load do no I/O.
        assertSame(first, LabImages.cached(256))
        assertSame(first, runBlocking { LabImages.load(context, 256) })
    }

    // ------------------------------------------------------------- helpers

    private fun shot(appearance: Appearance, file: String) {
        val state = TranscriptState()
        val controller = TranscriptLabController(state, scope = CoroutineScope(Dispatchers.Unconfined))
        try {
            // Viewport first, then entries: a width-0 frame never builds.
            state.setViewport(411f, 1f)
            controller.seed(1)
            // The frame arrives through the relay's Choreographer callback,
            // which the paused main looper schedules one frame delay ahead;
            // `compose.waitUntil` never runs or advances that looper.
            val end = System.nanoTime() + 5_000_000_000L
            while (state.frame?.let { it.width() == 411f && it.rowCount() > 0u } != true) {
                check(System.nanoTime() < end) { "no fixture frame at width 411 within 5 s" }
                shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
            }
            compose.setContent {
                RobocoTheme(appearance) {
                    TranscriptLabScreen(controller, LabOptions(turns = 1), runScripts = false)
                }
            }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(file)
        } finally {
            state.close()
        }
    }

    /**
     * The ticket's fake insets, dispatched on the compose root: status
     * bar 24 dp, navigation bar 24 dp, IME [imeBottomPx] px (0 = hidden).
     */
    private fun dispatchLabInsets(root: View, imeVisible: Boolean, imeBottomPx: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 48, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
            .setVisible(WindowInsetsCompat.Type.ime(), imeVisible)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(root, insets) }
    }
}
