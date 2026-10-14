package sh.roboco.android.transcript

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import sh.roboco.android.transcript.widgets.ImageTile
import sh.roboco.android.transcript.widgets.imageTileRadius
import uniffi.roboco_core.DebugEntry
import uniffi.roboco_core.WidgetKind

/**
 * The Rust markdown-image layout crosses the Kotlin bridge (ticket 137;
 * the port of pr817's `testRustImageLayoutCrossesTheSwiftBridge`): one
 * debug entry `![Calculation result: 8](/workspace/calculator/5-plus-3.png)`
 * at width 390, scale 1, yields one row whose first widget is an
 * `Image` with the reference and the alt text as its payload, inside the
 * row's bounds; the [ImageTile] built from that widget carries the alt
 * text as its accessibility label, asks the reader once, and a
 * `performSemanticsAction(OnClick)` (iOS `accessibilityActivate`) asks
 * again.
 *
 * iOS waits on a `CountDownLatch` over `frameReady`; Robolectric's paused
 * main looper needs the Choreographer pump instead (the
 * `TranscriptHostScreenshotTest` pattern) — same 5 s deadline. The test
 * **needs the host core rebuilt with this ticket's layout**: it is skipped
 * with `assumeTrue` when the library is absent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MarkdownImageBridgeTest {

    @get:Rule
    val compose = createComposeRule()

    /** The Rust `ChipBackground` placeholder colour (light) under the tile. */
    private val PLACEHOLDER = Color(0xFFE7E7EB.toInt())

    @Before
    fun init() {
        // Both are safe without the core library; the library itself is
        // only touched inside the test, behind the assumeTrue.
        TextEngine.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
    fun theRustImageLayoutCrossesTheBridge() {
        assumeTrue("host core library missing", runCatching { uniffi.roboco_core.coreVersion() }.isSuccess)
        UniffiSandbox.claimCallbacks()
        // Build the shared engine off the main looper first, as the app does.
        runBlocking { TextEngine.await() }

        val path = "/workspace/calculator/5-plus-3.png"
        val state = TranscriptState()
        try {
            // Viewport first, then entries: a width-0 frame never builds.
            state.setViewport(390f, 1f)
            state.engine.setDebugEntries(
                listOf(DebugEntry(id = "image", user = false, text = "![Calculation result: 8]($path)", streaming = false)),
                working = false,
            )
            // The frame arrives through the relay's Choreographer callback,
            // which the paused main looper schedules one frame delay ahead;
            // `compose.waitUntil` never runs or advances that looper.
            val end = System.nanoTime() + 5_000_000_000L
            while (state.frame?.let { it.rowCount() == 1u } != true) {
                check(System.nanoTime() < end) { "no 1-row frame at width 390 within 5 s" }
                shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
            }
            val display = state.frame!!.display(0u)
            assertNotNull("row 0 has a display list", display)

            val widget = display!!.widgets.firstOrNull() ?: error("the row has no widget")
            val kind = widget.kind
            assertTrue("the markdown image became an Image widget, got $kind", kind is WidgetKind.Image)
            assertEquals(path, (kind as WidgetKind.Image).reference)
            assertEquals("the alt text is the payload", "Calculation result: 8", widget.payload)
            assertTrue("x + w ≤ display.width (${widget.x} + ${widget.w} vs ${display.width})", widget.x + widget.w <= display.width)
            assertTrue("y + h ≤ display.height (${widget.y} + ${widget.h} vs ${display.height})", widget.y + widget.h <= display.height)

            // The configured row's image element: a tile built from that
            // widget with a reader that always fails.
            val reader = FailingReader()
            val loader = TranscriptImageLoader(
                decode = StubDecode(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                decodeDispatcher = Dispatchers.Unconfined,
            )
            val binding = TranscriptImageBinding(loader, { "bridge" }, reader)
            val actions = TranscriptActions(
                cachedImage = binding::cached,
                loadImage = binding::load,
            )
            val side = widget.w.coerceAtLeast(0f)
            val height = widget.h.coerceAtLeast(0f)
            compose.setContent {
                RobocoTheme(Appearance.Light) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        TileSlot(side, height, path, widget.payload ?: "Image", actions)
                    }
                }
            }
            compose.waitForIdle()
            val node = compose.onNodeWithTag("transcript-image").fetchSemanticsNode()
            assertEquals("Calculation result: 8", node.config[SemanticsProperties.ContentDescription]?.firstOrNull())
            assertEquals("the failing read happened once", 1, reader.reads.get())

            // accessibilityActivate() on the failed tile asks again.
            compose.onNodeWithTag("transcript-image").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals("the retry re-asked the reader", 2, reader.reads.get())
        } finally {
            state.close()
        }
    }

    /** The tile over the placeholder box Rust paints under it, at the widget rect. */
    @Composable
    private fun TileSlot(wDp: Float, hDp: Float, reference: String, label: String, actions: TranscriptActions) {
        Box(
            Modifier
                .absoluteOffset(18.dp, 100.dp)
                .size(wDp.dp, hDp.dp)
                .background(PLACEHOLDER, RoundedCornerShape(imageTileRadius(wDp, hDp))),
        ) {
            ImageTile(reference, label, wDp, hDp, actions)
        }
    }

    /** Always fails — the read error path. */
    private class FailingReader : AttachmentReader {
        val reads = AtomicInteger()

        override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
            reads.incrementAndGet()
            throw IOException("no attachment at $reference")
        }
    }

    /** The decode result never matters here (every read fails). */
    private class StubDecode : ImageDecode {
        override fun decode(bytes: ByteArray, sidePx: Int): DecodedImage =
            DecodedImage(ImageBitmap(10, 10), full = false)
    }
}
