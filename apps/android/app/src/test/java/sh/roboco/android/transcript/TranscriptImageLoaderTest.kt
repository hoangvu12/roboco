package sh.roboco.android.transcript

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The image tile loader (ticket 66): cache + hit rule, the engine-keyed
 * entries, in-flight de-duplication, failure semantics, the LRU replace
 * rule, `loadFull` outside the LRU, and the memory-trim levels — all with
 * a scripted [ImageDecode] and counting/gated fake readers on
 * `StandardTestDispatcher`s sharing the test scheduler. The downsample
 * arithmetic runs the real [PlatformImageDecode] under Robolectric's
 * NATIVE graphics mode (PNG bytes in, `ALLOCATOR_SOFTWARE` bitmaps out),
 * pinning the integer ceil-division targets exactly (1200×800 at 780 →
 * 1170×780, at 228 → 342×228; 300×200 never upscaled; the 4096 long-side
 * cap for full loads).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptImageLoaderTest {

    // ------------------------------------------------------------- helpers

    /** Returns the scripted results in order; a missing entry fails loudly. */
    private class ScriptedDecode(private val results: List<DecodedImage>) : ImageDecode {
        var calls = 0

        override fun decode(bytes: ByteArray, sidePx: Int): DecodedImage {
            check(calls < results.size) { "unexpected decode #$calls (sidePx=$sidePx)" }
            return results[calls++]
        }
    }

    /** Always throws: the undecodable-bytes path. */
    private object ThrowingDecode : ImageDecode {
        override fun decode(bytes: ByteArray, sidePx: Int): DecodedImage =
            throw IOException("not an image")
    }

    /** Succeeds (or fails, per reference) and counts the reads. */
    private class FakeReader(
        private val failRefs: Set<String> = emptySet(),
    ) : AttachmentReader {
        val reads = AtomicInteger()

        override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
            reads.incrementAndGet()
            if (reference in failRefs) throw IOException("no attachment at $reference")
            return byteArrayOf(1)
        }

        fun reset() = reads.set(0)
    }

    /** Suspends every read until released, so loads overlap. */
    private class GatedReader : AttachmentReader {
        val reads = AtomicInteger()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
            reads.incrementAndGet()
            val gate = CompletableDeferred<Unit>()
            synchronized(gates) { gates.add(gate) }
            gate.await()
            return byteArrayOf(1)
        }

        fun reset() = reads.set(0)

        fun releaseAll() {
            synchronized(gates) {
                gates.forEach { it.complete(Unit) }
                gates.clear()
            }
        }
    }

    private fun bmp(w: Int, h: Int, full: Boolean = false): DecodedImage =
        DecodedImage(ImageBitmap(w, h), full)

    private fun TestScope.newLoader(
        decode: ImageDecode,
        maxBytes: Int = 16 * 1024 * 1024,
    ): TranscriptImageLoader = TranscriptImageLoader(
        decode = decode,
        maxBytes = maxBytes,
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        decodeDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** PNG bytes of a `w × h` uniform picture (tiny, decodable). */
    private fun pngBytes(w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    // ------------------------------------------------------ cache + hit rule

    @Test
    fun aLoadCachesItsResultAndTheHitRuleServesIt() = runTest {
        val reader = FakeReader()
        val loader = newLoader(ScriptedDecode(listOf(bmp(1170, 780))))
        val k = ImageKey("e1", "pic.png")

        val image = loader.load(k, 780, reader)
        assertNotNull(image)
        assertEquals(1170, image!!.width)
        assertEquals(1, reader.reads.get())

        // minSide 780 >= 780 → hit; >= 228 → hit; > 780 → miss (not full).
        assertNotNull(loader.cached(k, 780))
        assertNotNull(loader.cached(k, 228))
        assertNull(loader.cached(k, 781))

        // The cache serves the second load: no second read.
        assertNotNull(loader.load(k, 228, reader))
        assertEquals(1, reader.reads.get())
    }

    @Test
    fun aFullEntryAlwaysServesAnySize() = runTest {
        val reader = FakeReader()
        val loader = newLoader(ScriptedDecode(listOf(bmp(1200, 800, full = true))))
        val k = ImageKey("e1", "pic.png")

        loader.load(k, 780, reader)
        assertNotNull(loader.cached(k, 781))
        assertNotNull(loader.cached(k, 2000))
    }

    @Test
    fun aMissForALargerTileReDecodesAndReplacesTheEntry() = runTest {
        val reader = FakeReader()
        val decode = ScriptedDecode(
            listOf(
                bmp(750, 500),            // load at 500: downsampled, minSide 500
                bmp(1200, 800, full = true), // load at 800: full
            ),
        )
        val loader = newLoader(decode)
        val k = ImageKey("e1", "pic.png")

        assertNotNull(loader.load(k, 500, reader))
        // 500 < 800 and not full → miss → a second read, a full decode.
        assertNull(loader.cached(k, 800))
        assertNotNull(loader.load(k, 800, reader))
        assertEquals(2, reader.reads.get())
        assertEquals(2, decode.calls)

        // The full entry replaced the smaller one and serves everything.
        assertNotNull(loader.cached(k, 800))
        assertNotNull(loader.cached(k, 500))
    }

    @Test
    fun aSmallerDecodeNeverReplacesALargerCacheEntry() = runTest {
        val reader = FakeReader()
        val loader = newLoader(
            ScriptedDecode(
                listOf(
                    bmp(1170, 780),   // load at 780
                    bmp(450, 300),    // load at 2000 (a miss: 780 < 2000)
                ),
            ),
        )
        val k = ImageKey("e1", "pic.png")

        assertNotNull(loader.load(k, 780, reader))
        // A miss for a larger tile returns its own (smaller) result …
        val smaller = loader.load(k, 2000, reader)
        assertNotNull(smaller)
        assertEquals(450, smaller!!.width)
        assertEquals(2, reader.reads.get())
        // … but the cache kept the larger entry and still serves it.
        val cached = loader.cached(k, 780)
        assertNotNull(cached)
        assertEquals(1170, cached!!.width)
    }

    // ------------------------------------------------------------- engine key

    @Test
    fun theEngineIsPartOfTheCacheKey() = runTest {
        val reader = FakeReader()
        val loader = newLoader(
            ScriptedDecode(listOf(bmp(100, 100), bmp(100, 100))),
        )

        loader.load(ImageKey("e1", "same.png"), 100, reader)
        loader.load(ImageKey("e2", "same.png"), 100, reader)
        assertEquals("two engines, two reads", 2, reader.reads.get())

        assertNotNull(loader.cached(ImageKey("e1", "same.png"), 100))
        assertNotNull(loader.cached(ImageKey("e2", "same.png"), 100))

        // The same reference twice on one engine: one read, cache-served.
        loader.load(ImageKey("e1", "same.png"), 100, reader)
        assertEquals(2, reader.reads.get())
    }

    // ---------------------------------------------------------- dedupe + races

    @Test
    fun concurrentLoadsOfTheSameKeyAndSizeShareOneRead() = runTest {
        val reader = GatedReader()
        val loader = newLoader(ScriptedDecode(listOf(bmp(100, 100))))
        val k = ImageKey("e1", "a.png")

        val first = async { loader.load(k, 300, reader) }
        advanceUntilIdle() // the read reaches its gate
        val second = async { loader.load(k, 300, reader) }
        advanceUntilIdle() // joins the same in-flight deferred

        assertEquals("one read while both are in flight", 1, reader.reads.get())

        reader.releaseAll()
        advanceUntilIdle()
        assertNotNull(first.await())
        assertNotNull(second.await())
        assertEquals("the shared load was never repeated", 1, reader.reads.get())
    }

    @Test
    fun aCompletedLoadIsRemovedFromTheMapSoTheNextOneReads() = runTest {
        val reader = GatedReader()
        val loader = newLoader(ScriptedDecode(listOf(bmp(100, 100), bmp(100, 100))))
        val k = ImageKey("e1", "a.png")

        val first = async { loader.load(k, 300, reader) }
        advanceUntilIdle()
        reader.releaseAll()
        advanceUntilIdle()
        assertNotNull(first.await())
        reader.reset()

        // The cache was cleared; the map entry is gone, so it reads again.
        loader.evictAll()
        val second = async { loader.load(k, 300, reader) }
        advanceUntilIdle()
        reader.releaseAll()
        advanceUntilIdle()
        assertNotNull(second.await())
        assertEquals(1, reader.reads.get())
    }

    @Test
    fun aLoadSurvivesItsAwaiterAndStillFillsTheCache() = runTest {
        val reader = GatedReader()
        // A 300 px short side: the hit rule serves it for sidePx 300 (a
        // 100×100 downsample would be a legitimate miss at 300).
        val loader = newLoader(ScriptedDecode(listOf(bmp(450, 300))))
        val k = ImageKey("e1", "a.png")

        // The awaiter is cancelled while the gated read is in flight (iOS's
        // detached task: the tile goes away, the load keeps running).
        val awaiter = async { loader.load(k, 300, reader) }
        advanceUntilIdle()
        awaiter.cancel()
        advanceUntilIdle()
        assertNull("nothing cached yet", loader.cached(k, 300))

        reader.releaseAll()
        advanceUntilIdle()
        assertNotNull("the load still cached its result", loader.cached(k, 300))
    }

    // --------------------------------------------------------- failure rules

    @Test
    fun aFailedReadIsNotCachedAndTheNextLoadTriesAgain() = runTest {
        val reader = FakeReader(failRefs = setOf("bad.png"))
        val loader = newLoader(ScriptedDecode(listOf(bmp(100, 100))))
        val k = ImageKey("e1", "bad.png")

        assertNull(loader.load(k, 300, reader))
        assertNull(loader.cached(k, 300))
        assertEquals(1, reader.reads.get())

        assertNull(loader.load(k, 300, reader))
        assertEquals("failures are retried, not remembered", 2, reader.reads.get())
    }

    @Test
    fun undecodableBytesAreNotCachedEither() = runTest {
        val reader = FakeReader()
        val loader = newLoader(ThrowingDecode)
        val k = ImageKey("e1", "not-an-image.bin")

        assertNull(loader.load(k, 300, reader))
        assertNull(loader.cached(k, 300))
        // The read succeeded once and the decode failed; a second call
        // reads again (and fails again).
        assertNull(loader.load(k, 300, reader))
        assertEquals(2, reader.reads.get())
    }

    // ------------------------------------------------------------- loadFull

    @Test
    fun loadFullDecodesOutsideTheTileLru() = runTest {
        val reader = FakeReader()
        val decode = ScriptedDecode(
            listOf(
                bmp(1200, 800, full = true),
                bmp(1200, 800, full = true),
            ),
        )
        val loader = newLoader(decode)
        val k = ImageKey("e1", "pic.png")

        val image = loader.loadFull(k, reader)
        assertNotNull(image)
        assertEquals(1200, image!!.width)

        // Full loads never enter the tile LRU and are not deduped.
        assertNull(loader.cached(k, 300))
        assertEquals(1, decode.calls)
        assertNotNull(loader.loadFull(k, reader))
        assertEquals("no dedupe on loadFull", 2, reader.reads.get())
    }

    // ----------------------------------------------------------- blank engine

    @Test
    fun aBlankEngineIdMeansNoReadAndNoCacheEntry() = runTest {
        val reader = FakeReader()
        val loader = newLoader(ScriptedDecode(listOf(bmp(100, 100))))
        val binding = TranscriptImageBinding(loader, { "  " }, reader)

        assertNull(binding.cached("a.png", 300))
        assertNull(binding.load("a.png", 300))
        assertNull(binding.loadFull("a.png"))
        assertEquals(0, reader.reads.get())
        assertNull(loader.cached(ImageKey("  ", "a.png"), 300))
    }

    // ------------------------------------------------------------ trim levels

    @Test
    fun trimHalvesAtUiHiddenEvictsAtBackgroundAndIgnoresForegroundLevels() = runTest {
        // Each entry is 100×100×4 = 40 000 bytes; the budget holds exactly two.
        val reader = FakeReader()
        val loader = newLoader(
            ScriptedDecode(listOf(bmp(100, 100), bmp(100, 100), bmp(100, 100))),
            maxBytes = 80_000,
        )
        val k1 = ImageKey("e1", "one.png")
        val k2 = ImageKey("e1", "two.png")
        assertNotNull(loader.load(k1, 100, reader))
        assertNotNull(loader.load(k2, 100, reader))
        assertNotNull(loader.cached(k1, 100))
        assertNotNull(loader.cached(k2, 100))

        // UI_HIDDEN (20): trim to half the budget — the least recently
        // used entry (k1) goes, k2 stays.
        loader.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertNull(loader.cached(k1, 100))
        assertNotNull(loader.cached(k2, 100))

        // RUNNING_CRITICAL (15) is a foreground level: untouched.
        loader.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertNotNull(loader.cached(k2, 100))

        // BACKGROUND (40) and onLowMemory: everything goes.
        loader.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertNull(loader.cached(k2, 100))

        assertNotNull(loader.load(k1, 100, reader)) // refill
        loader.onLowMemory()
        assertNull(loader.cached(k1, 100))
    }

    // ------------------------------------------------------------- singleton

    @Test
    fun theAppWideInstanceIsASingleton() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val a = TranscriptImageLoader.get(context)
        val b = TranscriptImageLoader.get(context)
        assertSame(a, b)
    }

    @Test
    fun theDefaultBudgetIsAtMost48MiB() {
        assertTrue(TranscriptImageLoader.defaultMaxBytes() in 1..(48 shl 20))
    }

    // -------------------------------------------------- real decoder (NATIVE)

    @Test
    fun theDecoderDownsamplesWithIntegerCeilDivision() {
        val decode = PlatformImageDecode(allocator = ImageDecoder.ALLOCATOR_SOFTWARE)
        val wide = pngBytes(1200, 800)

        val at780 = decode.decode(wide, 780)
        assertEquals(1170, at780.bitmap.width)
        assertEquals(780, at780.bitmap.height)
        assertFalse(at780.full)

        val at228 = decode.decode(wide, 228)
        assertEquals(342, at228.bitmap.width)
        assertEquals(228, at228.bitmap.height)
        assertFalse(at228.full)

        // A source smaller than the tile is never upscaled.
        val small = decode.decode(pngBytes(300, 200), 780)
        assertEquals(300, small.bitmap.width)
        assertEquals(200, small.bitmap.height)
        assertTrue(small.full)

        // sidePx 0 = full decode (below the cap).
        val full = decode.decode(pngBytes(300, 200), 0)
        assertEquals(300, full.bitmap.width)
        assertTrue(full.full)
    }

    @Test
    fun fullDecodesCapTheLongSideAt4096() {
        val decode = PlatformImageDecode(allocator = ImageDecoder.ALLOCATOR_SOFTWARE)

        val capped = decode.decode(pngBytes(5000, 1000), 0)
        assertEquals(4096, max(capped.bitmap.width, capped.bitmap.height))
        // ceil(1000 · 4096 / 5000) = ceil(819.2) = 820
        assertEquals(820, min(capped.bitmap.width, capped.bitmap.height))
        assertFalse(capped.full)
    }

    @Test
    fun undecodableBytesThrowFromTheRealDecoder() {
        val decode = PlatformImageDecode(allocator = ImageDecoder.ALLOCATOR_SOFTWARE)
        val bytes = "not an image".toByteArray()
        val result = runCatching { decode.decode(bytes, 300) }
        assertTrue("decode should have thrown, returned ${result.getOrNull()}", result.isFailure)
    }
}
