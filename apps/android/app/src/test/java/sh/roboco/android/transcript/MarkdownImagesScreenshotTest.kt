package sh.roboco.android.transcript

import android.content.Context
import android.graphics.ImageDecoder
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UniffiSandbox
import sh.roboco.android.debug.LAB_IMAGES_FIXTURE
import sh.roboco.android.debug.LabImageReader
import sh.roboco.android.debug.labFixturePng
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.DebugEntry

/**
 * The markdown-image rows goldens (ticket 137; recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`):
 * [LAB_IMAGES_FIXTURE] through the real core and [TranscriptHost] at
 * `w393dp-h852dp-xxhdpi` (d = 3), light and dark, with an **instant**
 * fake reader — the workspace PNG for the served reference (the 260 dp
 * square tile at x = 18, the 960 × 540 art aspect-fit on `elevated`),
 * `IOException` for the missing one (the full Failed text). Host core
 * required: the first Gradle run must build it without
 * `-ProbocoSkipCore`, since the rows come from this ticket's Rust layout.
 *
 * What the goldens must show, side by side with the iOS lab: "Here are
 * the screenshots." as a plain text row; the 260 dp image tile with the
 * alt text as its a11y label; "Download screenshot" as a separate text
 * link row below it; the missing screenshot as a Failed tile. A
 * `transcript-link` node for the download link stays separate from the
 * tile.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MarkdownImagesScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(ApplicationProvider.getApplicationContext<Context>())
        // Build the shared engine off the main looper first, as the app does.
        runBlocking { TextEngine.await() }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun markdownImagesRowsLight() {
        shot(Appearance.Light, "transcript/markdown_images_rows_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun markdownImagesRowsDark() {
        shot(Appearance.Dark, "transcript/markdown_images_rows_dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        val state = TranscriptState()
        try {
            // Viewport first, then entries: a width-0 frame never builds.
            state.setViewport(393f, 1f)
            state.engine.setDebugEntries(
                listOf(DebugEntry(id = "img-a", user = false, text = LAB_IMAGES_FIXTURE, streaming = false)),
                working = false,
            )
            // Four rows: the intro text, the image tile, the download link
            // and the missing screenshot. The frame arrives through the
            // relay's Choreographer callback, which the paused main looper
            // schedules one frame delay ahead.
            val end = System.nanoTime() + 5_000_000_000L
            while (state.frame?.let { it.rowCount() == 4u } != true) {
                check(System.nanoTime() < end) { "no 4-row frame at width 393 within 5 s" }
                shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
            }
            // The instant fake reader: the demo PNG for the served
            // reference, NotFound for anything else (the Lab's 600 ms /
            // 300 ms waits removed so the capture is the settled state).
            val reader = AttachmentReader { _, reference ->
                when (reference) {
                    LabImageReader.LAB_LANDSCAPE -> labFixturePng(960, 540)
                    else -> throw IOException("NotFound: $reference")
                }
            }
            val loader = TranscriptImageLoader(
                decode = PlatformImageDecode(allocator = ImageDecoder.ALLOCATOR_SOFTWARE),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                decodeDispatcher = Dispatchers.Unconfined,
            )
            val binding = TranscriptImageBinding(loader, { "shot" }, reader)
            val actions = TranscriptActions(
                cachedImage = binding::cached,
                loadImage = binding::load,
            )
            compose.setContent {
                RobocoTheme(appearance) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        TranscriptHost(state, actions, Modifier.fillMaxSize())
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
