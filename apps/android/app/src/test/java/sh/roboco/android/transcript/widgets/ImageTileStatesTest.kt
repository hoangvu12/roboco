package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
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
import sh.roboco.android.transcript.DecodedImage
import sh.roboco.android.transcript.ImageDecode
import sh.roboco.android.transcript.ImageKey
import sh.roboco.android.transcript.MaxAutoImageRetries
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptImageBinding
import sh.roboco.android.transcript.TranscriptImageLoader
import sh.roboco.android.transcript.imageRetryDelayMs

/**
 * The tile's state machine (ticket 137, at `w393dp-h852dp-xxhdpi` → d = 3,
 * a 260 dp tile decodes to sidePx 780): the three states and their a11y
 * values/hints (ports of pr817's `testLoadingFailureAndRetryRemainAccessible`
 * and `testLoadedImageKeepsAltTextAndOpens`), the de-duplicated retry (a
 * tap during Loading joins the in-flight read; a tap after Failed starts
 * a fresh one), every non-blank reference reaching the engine (only a
 * blank one fails synchronously), the automatic backoff and reconnect
 * retries, the
 * claim (a late result for an old reference is cached but never shown, a
 * late failure never shown at all), the instant Loading → Loaded swap
 * (no cross-fade remains), the compact tile's full state text, and the
 * status-overlay placement arithmetic (`imageStatusPlacement`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ImageTileStatesTest {

    @get:Rule
    val compose = createComposeRule()

    /** The Rust `ChipBackground` placeholder colour (light) under every tile. */
    private val PLACEHOLDER = Color(0xFFE7E7EB.toInt())

    /** The tile's a11y label, as a markdown image's alt text would give it. */
    private val LABEL = "Calculation result: 8"

    @Before
    fun reset() = ReduceMotion.resetForTest()

    // ------------------------------------------------------------- fixtures

    /** The decoded picture every load returns — identity-checked on tap. */
    private val picture: ImageBitmap by lazy { ImageBitmap(1000, 1000) }

    private val decode = object : ImageDecode {
        override fun decode(bytes: ByteArray, sidePx: Int): DecodedImage =
            DecodedImage(picture, full = false)
    }

    /**
     * The fake engine: reads are counted; [gateRefs] suspend on a gate
     * (observable in-flight loads), [failRefs] throw **after** the gate —
     * so a gated reference can fail on release.
     */
    private class FakeReader(
        private val gateRefs: Set<String> = emptySet(),
        private val failRefs: Set<String> = emptySet(),
    ) : AttachmentReader {
        val reads = AtomicInteger()
        private val gates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()

        override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
            reads.incrementAndGet()
            if (reference in gateRefs) {
                val gate = CompletableDeferred<Unit>()
                gates.add(gate)
                gate.await()
            }
            if (reference in failRefs) throw IOException("no attachment at $reference")
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
     * The tile in a [side] dp slot at (50, 120) over the placeholder box
     * the row paints under it, exactly as ticket 65's WidgetBox places it.
     */
    @Composable
    private fun TestTile(
        reference: String,
        actions: TranscriptActions,
        side: Dp = 260.dp,
        label: String = LABEL,
    ) {
        Box(
            Modifier
                .absoluteOffset(50.dp, 120.dp)
                .size(side, side)
                .background(PLACEHOLDER, RoundedCornerShape(imageTileRadius(side.value, side.value))),
        ) {
            ImageTile(reference, label, side.value, side.value, actions)
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

    /** The first tile's status, or null before it exists (safe to poll). */
    private fun statusOrNull(): ImageTileStatus? =
        tiles().firstOrNull()?.config?.get(ImageTileStatusKey)

    /**
     * Advances frozen frames until [condition] holds (5 s of frames),
     * flushing the apply notifications first so writes made outside
     * composition (a released gate, a `reference` swap) reach the frame.
     */
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

    /**
     * Frozen clock for every test that keeps a tile in Loading across a
     * wait: the Loading overlay's `ActivitySpinner` loops on
     * `withFrameMillis` forever, so an auto-advancing clock never sees
     * Compose idle (AppNotIdleException). Frames are stepped by hand.
     */
    private fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    // -------------------------------------------------- the placement math

    @Test
    fun theStatusPlacementCentresTheLabelAndLiftsTheSpinner() {
        // 260 dp at d = 3: the tile is 780 px; Geist 13's natural line
        // height (1.3 × 13 = 16.9 dp) measures 51 px; the spinner is
        // 20 dp = 60 px; the 8 dp gap is 24 px.
        val (labelY, spinnerY) = imageStatusPlacement(780, 51, 60, 24)
        assertEquals("(780 − 51) / 2 rounds toward 0", 364, labelY)
        assertEquals("365 − 24 − 60", 280, spinnerY)
        // The label's centre is within 0.5 px of the tile's centre …
        assertEquals(390f, labelY + 51 / 2f, 0.5f)
        // … and the spinner's bottom sits the gap above the label's top.
        assertEquals(24, labelY - (spinnerY + 60))
        // The label box spans x 12…248 dp (36…744 px).
    }

    @Test
    fun compactModeFollowsTheShorterSide() {
        assertTrue(imageTileCompact(76f, 76f))
        assertTrue(imageTileCompact(120f, 300f))
        assertFalse(imageTileCompact(121f, 300f))
        assertFalse(imageTileCompact(260f, 260f))
    }

    // ------------------------------------- pr817 testLoadingFailureAndRetry…

    @Test
    fun aNewTileShowsLoadingAndAFailedTileKeepsItsLabelAndHint() {
        val reader = FakeReader(gateRefs = setOf("/a.png"), failRefs = setOf("/a.png"))
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        freezeClock()
        compose.setContent { Host { TestTile("/a.png", actions) } }
        driveFrames { reader.reads.get() == 1 } // the read is in flight (gated)
        assertEquals("a new tile's value is Loading image", ImageTileStatus.Loading, node().config[ImageTileStatusKey])
        assertEquals("Loading image", node().config[SemanticsProperties.StateDescription])
        assertEquals(false, node().config[ImageTileLoadedKey])
        assertEquals("the label is the alt text", LABEL, node().config[SemanticsProperties.ContentDescription]?.firstOrNull())

        reader.releaseAll() // the gated read now fails
        driveFrames { statusOrNull() == ImageTileStatus.Failed }
        assertEquals(ImageTileStatus.Failed, node().config[ImageTileStatusKey])
        val value = node().config[SemanticsProperties.StateDescription]
        assertNotNull(value)
        assertTrue("begins with Image unavailable: $value", value!!.startsWith("Image unavailable"))
        assertTrue("contains Tap to retry.: $value", value.contains("Tap to retry."))
        assertEquals("the verbatim iOS hint for tests", ImageTileCopy.FailedHint, node().config[ImageTileHintKey])
        assertEquals("the label is unchanged", LABEL, node().config[SemanticsProperties.ContentDescription]?.firstOrNull())
        assertEquals(Role.Image, node().config[SemanticsProperties.Role])
    }

    @Test
    fun activatingAFailedTileRetriesWithANewRead() {
        val reader = FakeReader(gateRefs = setOf("/a.png"), failRefs = setOf("/a.png"))
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        freezeClock()
        compose.setContent { Host { TestTile("/a.png", actions) } }
        driveFrames { reader.reads.get() == 1 }
        reader.releaseAll() // read 1 fails
        driveFrames { statusOrNull() == ImageTileStatus.Failed }
        assertEquals(ImageTileStatus.Failed, node().config[ImageTileStatusKey])
        assertEquals(1, reader.reads.get())

        // accessibilityActivate(): the click action re-asks the loader.
        compose.onNodeWithTag("transcript-image").performSemanticsAction(SemanticsActions.OnClick)
        driveFrames { reader.reads.get() == 2 && statusOrNull() == ImageTileStatus.Loading }
        assertEquals("a new read for the retry", 2, reader.reads.get())
        assertEquals("value back to Loading image", "Loading image", node().config[SemanticsProperties.StateDescription])

        reader.releaseAll() // read 2 fails as well
        driveFrames { statusOrNull() == ImageTileStatus.Failed }
        assertEquals(ImageTileStatus.Failed, node().config[ImageTileStatusKey])
        assertEquals(2, reader.reads.get())
    }

    // ---------------------------------------- pr817 testLoadedImageKeeps…

    @Test
    fun aLoadedTileKeepsItsAltTextAndOpensTheViewerOnce() {
        val reader = FakeReader()
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader)
        val opened = mutableListOf<OpenedImage>()
        val actions = TranscriptActions(
            cachedImage = binding::cached,
            loadImage = binding::load,
            openImage = { reference, source, bitmap, label ->
                opened.add(OpenedImage(reference, source, bitmap!!, label))
            },
        )

        compose.setContent { Host { TestTile("/a.png", actions) } }
        compose.waitForIdle()
        assertEquals(ImageTileStatus.Loaded, node().config[ImageTileStatusKey])
        assertEquals("Image loaded", node().config[SemanticsProperties.StateDescription])
        assertEquals(ImageTileCopy.LoadedHint, node().config[ImageTileHintKey])
        assertEquals("the label is the alt text", LABEL, node().config[SemanticsProperties.ContentDescription]?.firstOrNull())

        compose.onNodeWithTag("transcript-image").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("openImage called exactly once", 1, opened.size)

        val open = opened.single()
        assertEquals("/a.png", open.reference)
        assertSame("the shown bitmap is passed on", picture, open.bitmap)
        assertEquals("the alt text becomes the viewer's label", LABEL, open.label)

        // The rect equals the tile's unclipped window rect (±1 px): the
        // test root sits at window (0, 0), so root bounds = window bounds.
        val bounds = compose.onNodeWithTag("transcript-image").getBoundsInRoot()
        val d = compose.density.density
        assertEquals(bounds.left.value * d, open.source.left, 1f)
        assertEquals(bounds.top.value * d, open.source.top, 1f)
        assertEquals(bounds.right.value * d, open.source.right, 1f)
        assertEquals(bounds.bottom.value * d, open.source.bottom, 1f)
    }

    // ------------------------------------------------ the instant swap

    @Test
    fun aLoadCompletesInstantlyWithNoCrossFade() {
        compose.mainClock.autoAdvance = false
        val reader = FakeReader(gateRefs = setOf("/a.png"))
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        compose.setContent { Host { TestTile("/a.png", actions) } }
        driveFrames { statusOrNull() == ImageTileStatus.Loading }
        assertEquals(1, reader.reads.get())

        reader.releaseAll()
        driveFrames { statusOrNull() == ImageTileStatus.Loaded }
        // The frame the load lands in shows the picture fully opaque:
        // 137 removed 66's 200 ms tween (pr817 dropped the 0.2 s
        // cross-dissolve), and no number of further frames changes it.
        assertEquals(1f, node().config[ImageTileAlphaKey]!!, 0.001f)
        repeat(8) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(1f, node().config[ImageTileAlphaKey]!!, 0.001f)
    }

    // ------------------------------------------------------- retry dedupe

    @Test
    fun tappingALoadingTileThreeTimesStillCausesOneRead() {
        val reader = FakeReader(gateRefs = setOf("/a.png"))
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        freezeClock()
        compose.setContent { Host { TestTile("/a.png", actions) } }
        driveFrames { reader.reads.get() == 1 }
        assertEquals(1, reader.reads.get())
        assertEquals(ImageTileStatus.Loading, node().config[ImageTileStatusKey])

        repeat(3) {
            compose.onNodeWithTag("transcript-image").performTouchInput { click(center) }
            frames()
        }
        assertEquals("taps during Loading join the in-flight load", 1, reader.reads.get())
        assertEquals(ImageTileStatus.Loading, node().config[ImageTileStatusKey])

        reader.releaseAll()
        driveFrames { statusOrNull() == ImageTileStatus.Loaded }
        assertEquals(ImageTileStatus.Loaded, node().config[ImageTileStatusKey])
        assertEquals(1, reader.reads.get())
    }

    @Test
    fun tappingAFailedTileStartsANewReadPerTap() {
        val reader = FakeReader(failRefs = setOf("/a.png"))
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        compose.setContent { Host { TestTile("/a.png", actions) } }
        compose.waitForIdle()
        assertEquals(ImageTileStatus.Failed, node().config[ImageTileStatusKey])
        assertEquals(1, reader.reads.get())

        repeat(2) {
            compose.onNodeWithTag("transcript-image").performTouchInput { click(center) }
            compose.waitForIdle()
        }
        assertEquals("failures are retried, one read per tap", 3, reader.reads.get())
        assertEquals(ImageTileStatus.Failed, node().config[ImageTileStatusKey])
    }

    // ------------------------------------------ every reference is tried

    @Test
    fun relativeAndUriReferencesReachTheEngineOnlyBlankFailsAtOnce() {
        val reader = FakeReader()
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)
        val refs = listOf("assets/shot.png", "file:///tmp/a.png", "roboco-file:a.png", "")

        compose.setContent {
            Host {
                Row {
                    refs.forEachIndexed { i, ref ->
                        Box(
                            Modifier
                                .absoluteOffset((i * 280).dp, 120.dp)
                                .size(260.dp, 260.dp)
                                .background(PLACEHOLDER, RoundedCornerShape(14.dp)),
                        ) {
                            ImageTile(ref, "Tile $i", 260f, 260f, actions)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val nodes = compose.onAllNodesWithTag("transcript-image").fetchSemanticsNodes()
        assertEquals(4, nodes.size)
        nodes.take(3).forEach {
            assertEquals(ImageTileStatus.Loaded, it.config[ImageTileStatusKey])
        }
        assertEquals("a blank reference fails in the first frame", ImageTileStatus.Failed, nodes[3].config[ImageTileStatusKey])
        assertEquals("one engine read per non-blank reference", 3, reader.reads.get())
    }

    // ------------------------------------------------- automatic retries

    @Test
    fun aFailedTileRetriesOnItsOwnWithABackoff() {
        val reader = FakeReader(failRefs = setOf("/a.png"))
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        freezeClock()
        compose.setContent { Host { TestTile("/a.png", actions) } }
        driveFrames { statusOrNull() == ImageTileStatus.Failed }
        assertEquals(1, reader.reads.get())

        // Not before the first 2 s backoff …
        compose.mainClock.advanceTimeBy(1_500)
        frames()
        assertEquals(1, reader.reads.get())
        // … then once; the next wait doubles to 4 s.
        compose.mainClock.advanceTimeBy(600)
        driveFrames { reader.reads.get() == 2 }
        driveFrames { statusOrNull() == ImageTileStatus.Failed }
        compose.mainClock.advanceTimeBy(3_000)
        frames()
        assertEquals("the second wait is longer", 2, reader.reads.get())
        compose.mainClock.advanceTimeBy(1_200)
        driveFrames { reader.reads.get() == 3 }
    }

    @Test
    fun aReconnectRetriesAFailedTileAtOnce() {
        val failing = AtomicInteger(1)
        val reader = object : AttachmentReader {
            val reads = AtomicInteger()
            override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
                reads.incrementAndGet()
                if (failing.getAndDecrement() > 0) throw IOException("engine offline")
                return byteArrayOf(1)
            }
        }
        val reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader, retries = reconnected)
        val actions = TranscriptActions(
            cachedImage = binding::cached,
            loadImage = binding::load,
            imageRetries = binding.retries,
        )

        freezeClock()
        compose.setContent { Host { TestTile("/a.png", actions) } }
        driveFrames { statusOrNull() == ImageTileStatus.Failed }
        frames()
        assertEquals(1, reader.reads.get())

        // Well inside the 2 s backoff, the engine comes back.
        compose.mainClock.advanceTimeBy(300)
        reconnected.tryEmit(Unit)
        driveFrames { statusOrNull() == ImageTileStatus.Loaded }
        assertEquals(2, reader.reads.get())
    }

    @Test
    fun automaticRetriesStopAtTheCapAndAReconnectRestartsThem() {
        val reader = FakeReader(failRefs = setOf("/a.png"))
        val reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader, retries = reconnected)
        val actions = TranscriptActions(
            cachedImage = binding::cached,
            loadImage = binding::load,
            imageRetries = binding.retries,
        )

        freezeClock()
        compose.setContent { Host { TestTile("/a.png", actions) } }
        driveFrames { statusOrNull() == ImageTileStatus.Failed }
        assertEquals(1, reader.reads.get())

        // Each automatic retry after its own backoff, up to the cap.
        for (failures in 1..MaxAutoImageRetries) {
            compose.mainClock.advanceTimeBy(imageRetryDelayMs(failures))
            driveFrames { reader.reads.get() == failures + 1 }
            driveFrames { statusOrNull() == ImageTileStatus.Failed }
        }
        assertEquals(MaxAutoImageRetries + 1, reader.reads.get())

        // Past the cap the tile waits: ten more minutes, no read.
        compose.mainClock.advanceTimeBy(600_000)
        frames()
        assertEquals("no automatic retry past the cap", MaxAutoImageRetries + 1, reader.reads.get())

        // A reconnect retries at once …
        reconnected.tryEmit(Unit)
        driveFrames { reader.reads.get() == MaxAutoImageRetries + 2 }
        driveFrames { statusOrNull() == ImageTileStatus.Failed }
        // … and restarts the backoff from its first 2 s step.
        compose.mainClock.advanceTimeBy(imageRetryDelayMs(1) + 100)
        driveFrames { reader.reads.get() == MaxAutoImageRetries + 3 }
    }

    // ---------------------------------------------------------- the claim

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
        // Two reads: b.png's warm-up above went through the same reader,
        // then a.png's (in flight, gated) — as ImageTileTest counts it.
        driveFrames { reader.reads.get() == 2 }
        assertEquals(2, reader.reads.get())
        assertEquals(ImageTileStatus.Loading, node().config[ImageTileStatusKey])

        // Switch to the cached b.png: shows at once, no read.
        compose.runOnIdle { reference = "/b.png" }
        driveFrames { tiles().firstOrNull()?.config?.get(ImageTileReferenceKey) == "/b.png" }
        assertEquals("/b.png", node().config[ImageTileReferenceKey])
        assertEquals(ImageTileStatus.Loaded, node().config[ImageTileStatusKey])

        // a.png's read completes after the switch: it must be cached …
        reader.releaseAll()
        frames()
        assertNotNull(loader.cached(ImageKey("e1", "/a.png"), 780))
        // … but never shown: b.png stays on the tile.
        assertEquals("/b.png", node().config[ImageTileReferenceKey])
        assertEquals(ImageTileStatus.Loaded, node().config[ImageTileStatusKey])
    }

    @Test
    fun aLateFailureForAnOldReferenceIsNeverShown() {
        val reader = FakeReader(gateRefs = setOf("/a.png"), failRefs = setOf("/a.png"))
        val loader = newLoader()
        val binding = TranscriptImageBinding(loader, { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        runBlocking { assertNotNull(loader.load(ImageKey("e1", "/b.png"), 780, reader)) }

        var reference by mutableStateOf("/a.png")
        freezeClock()
        compose.setContent { Host { TestTile(reference, actions) } }
        driveFrames { reader.reads.get() == 2 } // b.png's warm-up read + a.png's gated one

        compose.runOnIdle { reference = "/b.png" }
        driveFrames { tiles().firstOrNull()?.config?.get(ImageTileReferenceKey) == "/b.png" }
        assertEquals(ImageTileStatus.Loaded, node().config[ImageTileStatusKey])

        // a.png's gated read now fails — after the switch.
        reader.releaseAll()
        frames()
        assertNull("failures are never cached", loader.cached(ImageKey("e1", "/a.png"), 780))
        assertEquals("/b.png", node().config[ImageTileReferenceKey])
        assertEquals("the old failure never lands on the tile", ImageTileStatus.Loaded, node().config[ImageTileStatusKey])
    }

    // ----------------------------------------------------- compact tiles

    @Test
    fun aCompactTileKeepsTheFullStateText() {
        val reader = FakeReader(failRefs = setOf("/a.png"))
        val binding = TranscriptImageBinding(newLoader(), { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        compose.setContent { Host { TestTile("/a.png", actions, side = 76.dp) } }
        compose.waitForIdle()
        assertEquals("compact is a layout mode, not a state", ImageTileStatus.Failed, node().config[ImageTileStatusKey])
        assertEquals(
            "the a11y value keeps the full three-line text",
            ImageTileCopy.Failed,
            node().config[SemanticsProperties.StateDescription],
        )
        assertEquals(LABEL, node().config[SemanticsProperties.ContentDescription]?.firstOrNull())
    }

    // ------------------------------------------------- a cache-hit frame

    @Test
    fun aCacheHitShowsLoadedInTheFirstFrameWithoutReading() {
        val reader = FakeReader()
        val loader = newLoader()
        val binding = TranscriptImageBinding(loader, { "e1" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        // Populate the cache before composing (this does the one read).
        runBlocking { assertNotNull(loader.load(ImageKey("e1", "/a.png"), 780, reader)) }
        reader.reads.set(0)

        compose.setContent { Host { TestTile("/a.png", actions) } }
        assertEquals("cache hit: loaded in the first frame", ImageTileStatus.Loaded, node().config[ImageTileStatusKey])
        assertEquals(1f, node().config[ImageTileAlphaKey]!!, 0.001f)
        assertEquals("no read on a hit", 0, reader.reads.get())
    }

    @Test
    fun aBlankEngineIdMeansNoReadAndAFailedTile() {
        val reader = FakeReader()
        val loader = newLoader()
        val binding = TranscriptImageBinding(loader, { "" }, reader)
        val actions = TranscriptActions(cachedImage = binding::cached, loadImage = binding::load)

        compose.setContent { Host { TestTile("/a.png", actions) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600) // nothing retries on its own

        assertEquals(ImageTileStatus.Failed, node().config[ImageTileStatusKey])
        assertEquals(0, reader.reads.get())
        assertNull(loader.cached(ImageKey("", "/a.png"), 780))
    }
}

/** One recorded `openImage` call. */
private data class OpenedImage(val reference: String, val source: Rect, val bitmap: ImageBitmap, val label: String?)
