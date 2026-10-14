package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.transcript.AttachmentReader
import sh.roboco.android.transcript.ImageDecode
import sh.roboco.android.transcript.DecodedImage
import sh.roboco.android.transcript.ImageKey
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptImageBinding
import sh.roboco.android.transcript.TranscriptImageLoader

/**
 * The tile's 66 behaviours that survive 137 (the state machine is
 * [ImageTileStatesTest]'s): the radius rule, the first-frame cache hit
 * with no read (alpha 1, no animation), the claim (a late result for an
 * old reference is cached but never shown), taps opening only a loaded
 * tile with the exact unclipped window rect and the shown bitmap, the
 * image semantics with the click action present in **every** state
 * (137: Loading/Failed taps retry), and failed reads / blank engine ids
 * now ending in the Failed state instead of an empty tile. 137 removed
 * the 200 ms cross-fade — its two tween tests were deleted with it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ImageTileTest {

    @get:Rule
    val compose = createComposeRule()

    /** The Rust `ChipBackground` placeholder colour (light) under every tile. */
    private val PLACEHOLDER = Color(0xFFE7E7EB.toInt())

    /** The tile's a11y label, as a markdown image's alt text would give it. */
    private val LABEL = "Calculation result: 8"

    @After
    fun reset() = ReduceMotion.resetForTest()

    // ------------------------------------------------------------- fixtures

    /** The decoded picture every load returns — identity-checked on tap. */
    private val picture: ImageBitmap by lazy { ImageBitmap(1000, 1000) }

    private val decode = object : ImageDecode {
        override fun decode(bytes: ByteArray, sidePx: Int): DecodedImage =
            DecodedImage(picture, full = false)
    }

    /**
     * The fake engine: reads are counted and (for [gateRefs]) held on a
     * gate so a load can be observed in flight; [failRefs] throw.
     */
    private class FakeReader(
        private val gateRefs: Set<String> = emptySet(),
        private val failRefs: Set<String> = emptySet(),
    ) : AttachmentReader {
        val reads = AtomicInteger()
        private val gates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()

        override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
            reads.incrementAndGet()
            if (reference in failRefs) throw IOException("no attachment at $reference")
            if (reference in gateRefs) {
                val gate = CompletableDeferred<Unit>()
                gates.add(gate)
                gate.await()
            }
            return byteArrayOf(1)
        }

        fun releaseAll() {
            gates.forEach { it.complete(Unit) }
            gates.clear()
        }
    }

    /** Unconfined: the fake read/decode never touch a real dispatcher. */
    private fun newLoader(): TranscriptImageLoader = TranscriptImageLoader(
        decode = decode,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        decodeDispatcher = Dispatchers.Unconfined,
    )

    /**
     * The tile in a 260 dp slot at (50, 120) over the placeholder box the
     * row paints under it, exactly as ticket 65's WidgetBox places it.
     */
    @Composable
    private fun TestTile(reference: String, actions: TranscriptActions) {
        Box(
            Modifier
                .absoluteOffset(50.dp, 120.dp)
                .size(260.dp, 260.dp)
                .background(PLACEHOLDER, RoundedCornerShape(14.dp)),
        ) {
            ImageTile(reference, LABEL, 260f, 260f, actions)
        }
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        RobocoTheme(Appearance.Light) {
            Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) { content() }
        }
    }

    private fun node() = compose.onNodeWithTag("transcript-image").fetchSemanticsNode()

    /** All tile nodes — empty before the first composition, safe to poll. */
    private fun tiles() = compose.onAllNodesWithTag("transcript-image").fetchSemanticsNodes()

    private fun alpha(): Float = tiles().firstOrNull()?.config?.get(ImageTileAlphaKey) ?: -1f

    private fun loaded(): Boolean = tiles().firstOrNull()?.config?.get(ImageTileLoadedKey) == true

    /**
     * Frozen clock for every test that keeps a tile in Loading across a
     * wait: the Loading overlay's `ActivitySpinner` loops on
     * `withFrameMillis` forever, so an auto-advancing clock never sees
     * Compose idle (AppNotIdleException). Frames are stepped by hand.
     */
    private fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    /** Advances frozen frames (flushing outside writes first) until [condition] holds. */
    private fun driveFrames(condition: () -> Boolean) {
        repeat(300) {
            if (condition()) return
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
        error("condition not met within 300 frames")
    }

    /** A few frozen frames: lets a tap's recomposition and restarted effect run out. */
    private fun frames(n: Int = 4) = repeat(n) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    // ------------------------------------------------------------ the radius

    @Test
    fun theRadiusRuleFollowsTheShorterSide() {
        assertEquals(12.dp, imageTileRadius(120f, 120f))
        assertEquals(12.dp, imageTileRadius(76f, 300f))
        assertEquals(14.dp, imageTileRadius(121f, 300f))
        assertEquals(14.dp, imageTileRadius(260f, 260f))
    }

    // --------------------------------------------------- the first-frame hit

    @Test
    fun aCacheHitShowsInTheFirstFrameWithoutReading() {
        val reader = FakeReader()
        val loader = newLoader()
        val binding = TranscriptImageBinding(loader, { "e1" }, reader)
        val actions = TranscriptActions(
            cachedImage = binding::cached,
            loadImage = binding::load,
        )

        // Populate the cache before composing (this does the one read).
        runBlocking { assertNotNull(loader.load(ImageKey("e1", "/a.png"), 780, reader)) }
        reader.reads.set(0)

        compose.setContent { Host { TestTile("/a.png", actions) } }

        assertEquals("cache hit: loaded in the first frame", true, loaded())
        assertEquals("no animation on a hit", 1f, alpha(), 0.001f)
        assertEquals("no read on a hit", 0, reader.reads.get())
        assertEquals("the alt text is the label", LABEL, node().config[SemanticsProperties.ContentDescription]?.firstOrNull())
    }

    // ------------------------------------------------------------- the claim

    @Test
    fun aLateResultForAnOldReferenceIsCachedButNeverShown() {
        val reader = FakeReader(gateRefs = setOf("/a.png"))
        val loader = newLoader()
        val binding = TranscriptImageBinding(loader, { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        // b.png is cached in advance (its read is not gated).
        runBlocking { assertNotNull(loader.load(ImageKey("e1", "/b.png"), 780, reader)) }

        var reference by mutableStateOf("/a.png")
        freezeClock()
        compose.setContent { Host { TestTile(reference, actions) } }
        driveFrames { reader.reads.get() == 2 } // a.png's read is in flight (gated)
        assertEquals(2, reader.reads.get())
        assertEquals(false, loaded())

        // Switch to the cached b.png: shows at once, no read.
        compose.runOnIdle { reference = "/b.png" }
        driveFrames { tiles().firstOrNull()?.config?.get(ImageTileReferenceKey) == "/b.png" }
        assertEquals("/b.png", node().config[ImageTileReferenceKey])
        assertEquals(true, loaded())
        assertEquals(1f, alpha(), 0.001f)
        assertEquals(2, reader.reads.get())

        // a.png's read completes after the switch: it must be cached …
        reader.releaseAll()
        frames()
        assertNotNull(loader.cached(ImageKey("e1", "/a.png"), 780))
        // … but never shown: b.png stays on the tile.
        assertEquals("/b.png", node().config[ImageTileReferenceKey])
        assertEquals(true, loaded())
        assertEquals(1f, alpha(), 0.001f)
    }

    // ---------------------------------------------------------------- taps

    @Test
    fun aTapOpensOnlyALoadedTileWithItsWindowRect() {
        val reader = FakeReader(gateRefs = setOf("/a.png"))
        val loader = newLoader()
        val binding = TranscriptImageBinding(loader, { "e1" }, reader)
        val opened = mutableListOf<Triple<String, Rect, ImageBitmap>>()
        val actions = TranscriptActions(
            cachedImage = binding::cached,
            loadImage = binding::load,
            openImage = { r, rect, bmp, _ -> opened.add(Triple(r, rect, bmp!!)) },
        )

        freezeClock()
        compose.setContent { Host { TestTile("/a.png", actions) } }
        driveFrames { reader.reads.get() == 1 }

        // Not loaded yet: the tap retries (137), it never opens the viewer.
        compose.onNodeWithTag("transcript-image").performTouchInput { click(center) }
        frames()
        assertTrue("no viewer while Loading", opened.isEmpty())
        assertEquals("the retry joined the in-flight read", 1, reader.reads.get())

        reader.releaseAll()
        driveFrames { loaded() } // the load lands, instantly at alpha 1
        assertEquals(true, loaded())
        assertEquals(1f, alpha(), 0.001f)

        compose.onNodeWithTag("transcript-image").performTouchInput { click(center) }
        frames()
        assertEquals("openImage called exactly once", 1, opened.size)

        val (ref, rect, bmp) = opened.single()
        assertEquals("/a.png", ref)
        assertSame("the shown bitmap is passed on", picture, bmp)

        // The rect equals the tile's unclipped window rect (±1 px): the
        // test root sits at window (0, 0), so root bounds = window bounds.
        val bounds = compose.onNodeWithTag("transcript-image").getBoundsInRoot()
        val d = compose.density.density
        assertEquals(bounds.left.value * d, rect.left, 1f)
        assertEquals(bounds.top.value * d, rect.top, 1f)
        assertEquals(bounds.right.value * d, rect.right, 1f)
        assertEquals(bounds.bottom.value * d, rect.bottom, 1f)
    }

    // ----------------------------------------------------------- semantics

    @Test
    fun theNodeCarriesImageSemanticsAndClickInEveryState() {
        val reader = FakeReader(gateRefs = setOf("/a.png"))
        val loader = newLoader()
        val binding = TranscriptImageBinding(loader, { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        freezeClock()
        compose.setContent { Host { TestTile("/a.png", actions) } }
        driveFrames { reader.reads.get() == 1 }

        val loading = node().config
        assertEquals(Role.Image, loading[SemanticsProperties.Role])
        assertEquals(LABEL, loading[SemanticsProperties.ContentDescription]?.firstOrNull())
        assertEquals("transcript-image", loading[SemanticsProperties.TestTag])
        assertEquals("/a.png", loading[ImageTileReferenceKey])
        assertEquals(false, loading[ImageTileLoadedKey])
        assertEquals(ImageTileStatus.Loading, loading[ImageTileStatusKey])
        assertEquals(ImageTileCopy.Loading, loading[SemanticsProperties.StateDescription])
        assertNotNull("137: the click action exists while Loading (it retries)", loading.getOrNull(SemanticsActions.OnClick))

        reader.releaseAll()
        driveFrames { loaded() }

        val loadedConfig = node().config
        assertEquals(true, loadedConfig[ImageTileLoadedKey])
        assertEquals(ImageTileStatus.Loaded, loadedConfig[ImageTileStatusKey])
        assertEquals(ImageTileCopy.LoadedValue, loadedConfig[SemanticsProperties.StateDescription])
        assertEquals(ImageTileCopy.LoadedHint, loadedConfig[ImageTileHintKey])
        assertNotNull("click action once loaded", loadedConfig.getOrNull(SemanticsActions.OnClick))
    }

    // ------------------------------------------------------------ failures

    @Test
    fun aFailedReadShowsTheFailedStateAndATapRetries() {
        val reader = FakeReader(failRefs = setOf("/a.png"))
        val loader = newLoader()
        val binding = TranscriptImageBinding(loader, { "e1" }, reader)
        val opened = AtomicInteger()
        val actions = TranscriptActions(
            cachedImage = binding::cached,
            loadImage = binding::load,
            openImage = { _, _, _, _ -> opened.incrementAndGet() },
        )

        compose.setContent { Host { TestTile("/a.png", actions) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600) // nothing retries on its own

        assertEquals(false, loaded())
        assertEquals(0f, alpha(), 0.001f)
        assertEquals(ImageTileStatus.Failed, node().config[ImageTileStatusKey])
        assertEquals(1, reader.reads.get()) // one read, then the effect ended
        assertNull(loader.cached(ImageKey("e1", "/a.png"), 780))

        compose.onNodeWithTag("transcript-image").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("the failed tap starts a new read (137)", 2, reader.reads.get())
        assertEquals("still nothing opened", 0, opened.get())
    }

    @Test
    fun aBlankEngineIdMeansNoReadAndAFailedTile() {
        val reader = FakeReader()
        val loader = newLoader()
        val binding = TranscriptImageBinding(loader, { "" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        compose.setContent { Host { TestTile("/a.png", actions) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)

        assertEquals(ImageTileStatus.Failed, node().config[ImageTileStatusKey])
        assertEquals(0, reader.reads.get())
        assertNull(loader.cached(ImageKey("", "/a.png"), 780))
    }
}
