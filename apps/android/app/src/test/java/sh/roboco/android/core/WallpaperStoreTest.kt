package sh.roboco.android.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.roboco_core.WallpaperEffect

/** The authority the `set(uri)` fixtures are served under. */
private const val AUTHORITY = "sh.roboco.test.images"

/** The store's prefs file name. */
private const val PREFS_FILE = "wallpaper"

private val RED = 0xFFFF0000.toInt()
private val GRAY = 0xFF8A8A8E.toInt()

/**
 * `WallpaperStore` behaviour (ticket 20): the import pipeline
 * (in-memory `ImageDecoder` decode-time downsample + EXIF + JPEG 90 + `AtomicFile`),
 * the prefs and the change signal, the render pipeline's scale maths and
 * byte order, the contrast-guard call shape, the per-appearance cache and
 * its generation guard, ordering on the serial io dispatcher,
 * main-safety, and the process-wide `get`.
 *
 * Robolectric with NATIVE graphics — `ImageDecoder`, `Bitmap.compress` and
 * `copyPixelsToBuffer` run real Skia — plus injected test dispatchers and a
 * recording [WallpaperCore]; only [WallpaperCoreFfiTest] uses the real core.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class WallpaperStoreTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** Serial-inline: io work runs eagerly, in call order, on the caller. */
    private val io = UnconfinedTestDispatcher()

    /** Counts (and inline-runs) `withContext(renderDispatcher)` hops. */
    private val renderDispatcher = CountingDispatcher()

    private var fixtureCounter = 0

    @After
    fun clearProviderState() {
        FakeImageProvider.fixture = null
        FakeImageProvider.displayName = null
    }

    // ------------------------------------------------------------- fixtures

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun newStore(core: WallpaperCore = RecordingCore()): WallpaperStore =
        WallpaperStore(
            context = context,
            prefs = prefs(),
            core = core,
            io = io,
            renderDispatcher = renderDispatcher,
            scope = CoroutineScope(SupervisorJob() + io),
        )

    private fun wallpaperFile(): File =
        File(context.filesDir, "wallpaper/wallpaper.jpg")

    private fun storedBitmap(): Bitmap =
        checkNotNull(BitmapFactory.decodeFile(wallpaperFile().path)) { "no stored wallpaper" }

    private fun newFixture(name: String): File {
        fixtureCounter++
        return tmpFolder.newFile("$name-$fixtureCounter.jpg")
    }

    /** A solid-colour JPEG at quality 100, as a fixture file. */
    private fun solidJpeg(width: Int, height: Int, color: Int, name: String = "fixture"): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return writeJpeg(bitmap, name)
    }

    /** Four solid quadrants: top-left, top-right, bottom-left, bottom-right. */
    private fun quadrantJpeg(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val quadrants = intArrayOf(
            0xFFDC2626.toInt(), // top-left: red
            0xFF15803D.toInt(), // top-right: green
            0xFF1D4ED8.toInt(), // bottom-left: blue
            0xFFFACC15.toInt(), // bottom-right: yellow
        )
        for (quadrant in 0 until 4) {
            paint.color = quadrants[quadrant]
            val left = if (quadrant % 2 == 0) 0f else width / 2f
            val top = if (quadrant < 2) 0f else height / 2f
            canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint)
        }
        return writeJpeg(bitmap, "quadrants")
    }

    private fun writeJpeg(bitmap: Bitmap, name: String): File {
        val file = newFixture(name)
        file.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)) { "fixture encode failed" }
        }
        bitmap.recycle()
        return file
    }

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int = 3) {
        assertTrue(
            "expected ~${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
            Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        )
    }

    private fun assertByteNear(expected: Int, actual: Byte, where: String) {
        val value = actual.toInt() and 0xFF
        assertTrue("$where: expected ~$expected, got $value", Math.abs(expected - value) <= 3)
    }

    // ------------------------------------------------------ fresh / initial

    @Test
    fun freshInstallHasNoWallpaperAndRendersNullWithoutADispatcherHop() {
        val core = RecordingCore()
        val store = newStore(core)
        assertEquals(
            WallpaperStore.State(set = false, name = null, effect = WallpaperEffect.NONE, generation = 0),
            store.state.value,
        )
        runBlocking {
            assertNull(store.render(true))
            assertNull(store.render(false))
        }
        assertEquals(0, core.renderCalls.size)
        assertEquals(0, core.opacityCalls.size)
        assertEquals(0, renderDispatcher.entries)
    }

    @Test
    fun initialStateReadsThePrefsAndTheFileOnce() {
        prefs().edit()
            .putString("wallpaperName", "IMG_0412")
            .putString("wallpaperEffect", "scanlines")
            .commit()
        wallpaperFile().parentFile!!.mkdirs()
        wallpaperFile().writeText("presence is all isSet needs")
        val store = newStore(RecordingCore())
        assertEquals(
            WallpaperStore.State(set = true, name = "IMG_0412", effect = WallpaperEffect.SCANLINES, generation = 0),
            store.state.value,
        )
    }

    @Test
    fun anUnknownEffectPrefReadsAsNone() {
        // Case-sensitive like iOS: "Halftone" is unknown, so no effect.
        prefs().edit().putString("wallpaperEffect", "Halftone").commit()
        val store = newStore(RecordingCore())
        assertEquals(WallpaperEffect.NONE, store.state.value.effect)
    }

    // ---------------------------------------------------------------- import

    @Test
    fun setPathDownsamplesToThe1600BoundAndKeepsTheFileName() {
        val store = newStore(RecordingCore())
        val fixture = solidJpeg(4000, 3000, GRAY, "big")
        runBlocking {
            assertTrue(store.set(fixture).await())
        }
        val stored = storedBitmap()
        assertEquals(1600, stored.width)
        assertEquals(1200, stored.height)
        stored.recycle()
        val state = store.state.value
        assertTrue(state.set)
        assertEquals(fixture.name, state.name) // the extension is kept
        assertEquals(1, state.generation)
        assertTrue(wallpaperFile().exists())
        assertEquals(fixture.name, prefs().getString("wallpaperName", null))
    }

    @Test
    fun setPathDoesNotUpscaleImagesAlreadyWithinTheBound() {
        val store = newStore(RecordingCore())
        val fixture = solidJpeg(800, 600, GRAY, "small")
        runBlocking {
            assertTrue(store.set(fixture).await())
        }
        val stored = storedBitmap()
        assertEquals(800, stored.width)
        assertEquals(600, stored.height)
        stored.recycle()
        assertEquals(fixture.name, store.state.value.name)
    }

    @Test
    fun importRoundsTheTargetSizeLikeSwift() {
        val store = newStore(RecordingCore())
        runBlocking {
            assertTrue(store.set(solidJpeg(3201, 1601, GRAY, "wide")).await())
        }
        val stored = storedBitmap()
        // scale = 1600/3201: 3201 x scale = 1600.0, 1601 x scale = 800.25,
        // rounded half-away-from-zero (roundToInt) like Swift .rounded().
        assertEquals(1600, stored.width)
        assertEquals(800, stored.height)
        stored.recycle()
    }

    @Test
    fun setPathAppliesExifOrientation() {
        // Orientation 6 (rotate 90° CW): the stored image is 200 x 400 and
        // its top-left pixel is the source's bottom-left quadrant.
        val source = quadrantJpeg(400, 200)
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            saveAttributes()
        }
        val store = newStore(RecordingCore())
        runBlocking {
            assertTrue(store.set(source).await())
        }
        val stored = storedBitmap()
        assertEquals(200, stored.width)
        assertEquals(400, stored.height)
        assertColorNear(0xFF1D4ED8.toInt(), stored.getPixel(4, 4))
        stored.recycle()
    }

    @Test
    fun setUriNamesTheImportFromTheDisplayName() {
        FakeImageProvider.fixture = solidJpeg(64, 64, RED, "picked")
        FakeImageProvider.displayName = "IMG_0412.HEIC"
        Robolectric.setupContentProvider(FakeImageProvider::class.java, AUTHORITY)
        val store = newStore(RecordingCore())
        runBlocking {
            assertTrue(store.set(Uri.parse("content://$AUTHORITY/photo")).await())
        }
        val state = store.state.value
        // suggestedName carries no extension: IMG_0412.HEIC -> IMG_0412.
        assertEquals("IMG_0412", state.name)
        assertTrue(state.set)
        assertEquals(1, state.generation)
        assertTrue(wallpaperFile().exists())
    }

    @Test
    fun setUriFallsBackToPhotoWhenTheProviderHasNoDisplayName() {
        FakeImageProvider.fixture = solidJpeg(64, 64, RED, "picked")
        Robolectric.setupContentProvider(FakeImageProvider::class.java, AUTHORITY)
        val store = newStore(RecordingCore())
        runBlocking {
            assertTrue(store.set(Uri.parse("content://$AUTHORITY/photo")).await())
        }
        assertEquals("Photo", store.state.value.name)

        // A blank name falls back too.
        FakeImageProvider.displayName = "   "
        runBlocking {
            assertTrue(store.set(Uri.parse("content://$AUTHORITY/photo")).await())
        }
        assertEquals("Photo", store.state.value.name)
    }

    @Test
    fun setPathOnANonImageFailsWithoutChangingAnything() {
        val junk = tmpFolder.newFile("junk.txt")
        junk.writeText("this is not an image")
        val store = newStore(RecordingCore())
        val imported = runBlocking { store.set(junk).await() }
        assertFalse(imported)
        assertEquals(
            WallpaperStore.State(set = false, name = null, effect = WallpaperEffect.NONE, generation = 0),
            store.state.value,
        )
        assertFalse(wallpaperFile().exists())
        assertNull(prefs().getString("wallpaperName", null))
    }

    // ------------------------------------------------------ effects / remove

    @Test
    fun setEffectWritesThePrefAndBumpsTheGenerationEvenWhenUnchanged() {
        val store = newStore(RecordingCore())
        assertTrue(store.setEffect("halftone"))
        assertEquals("halftone", prefs().getString("wallpaperEffect", null))
        assertEquals(WallpaperEffect.HALFTONE, store.state.value.effect)
        assertEquals(1, store.state.value.generation)

        // Same effect re-picked: iOS bumps the generation regardless.
        store.setEffect(WallpaperEffect.HALFTONE)
        assertEquals(2, store.state.value.generation)
        assertEquals("halftone", prefs().getString("wallpaperEffect", null))
    }

    @Test
    fun setEffectKeyIgnoresUnknownKeys() {
        prefs().edit().putString("wallpaperEffect", "scanlines").commit()
        val store = newStore(RecordingCore())
        assertFalse(store.setEffect("bogus"))
        assertEquals("scanlines", prefs().getString("wallpaperEffect", null))
        assertEquals(WallpaperEffect.SCANLINES, store.state.value.effect)
        assertEquals(0, store.state.value.generation)
    }

    @Test
    fun removeDeletesTheFileAndTheNameButKeepsTheEffect() {
        val store = newStore(RecordingCore())
        runBlocking {
            assertTrue(store.set(solidJpeg(64, 64, RED)).await())
        }
        store.setEffect(WallpaperEffect.HALFTONE)
        runBlocking { store.remove().join() }
        assertFalse(wallpaperFile().exists())
        assertNull(prefs().getString("wallpaperName", null))
        assertEquals("halftone", prefs().getString("wallpaperEffect", null))
        val state = store.state.value
        assertFalse(state.set)
        assertNull(state.name)
        assertEquals(WallpaperEffect.HALFTONE, state.effect)
        assertEquals(3, state.generation) // import + setEffect + remove
        runBlocking { assertNull(store.render(true)) }
    }

    @Test
    fun setFollowedByRemoveWithoutAwaitingEndsRemoved() {
        val store = newStore(RecordingCore())
        val import = store.set(solidJpeg(64, 64, RED))
        val removal = store.remove()
        runBlocking {
            assertTrue(import.await()) // the import itself succeeded
            removal.join()
        }
        val state = store.state.value
        assertFalse(state.set)
        assertNull(state.name)
        assertFalse(wallpaperFile().exists())
        assertNull(prefs().getString("wallpaperName", null))
    }

    // ---------------------------------------------------------------- render

    @Test
    fun renderTruncatesTheTargetSizeLikeIos() {
        val core = RecordingCore()
        val store = newStore(core)
        val render = runBlocking {
            assertTrue(store.set(solidJpeg(1600, 1066, GRAY, "tall")).await())
            checkNotNull(store.render(true))
        }
        // 1066 * 0.75 = 799.5 truncates to 799; rounding would give 800.
        assertEquals(1200, render.image.width)
        assertEquals(799, render.image.height)
        val call = core.renderCalls.single()
        assertEquals(1200, call.width)
        assertEquals(799, call.height)
    }

    @Test
    fun renderDoesNotUpscaleImagesWithinTheBound() {
        val core = RecordingCore()
        val store = newStore(core)
        runBlocking {
            assertTrue(store.set(solidJpeg(900, 600, GRAY, "landscape")).await())
            assertNotNull(store.render(false))
        }
        val call = core.renderCalls.single()
        assertEquals(900, call.width)
        assertEquals(600, call.height)
    }

    @Test
    fun renderRunsTheContrastGuardTwiceWithThePaletteColours() {
        val core = RecordingCore(primaryOpacity = 0.75f, mutedOpacity = 0.5f)
        val store = newStore(core)
        val darkRender = runBlocking {
            assertTrue(store.set(solidJpeg(64, 64, GRAY, "guard")).await())
            checkNotNull(store.render(true))
        }
        // The opacity is the stricter (min) of the two guards.
        assertEquals(0.5f, darkRender.opacity, 1e-6f)
        // core.render receives light = !dark.
        assertEquals(false, core.renderCalls.single().light)
        assertEquals(2, core.opacityCalls.size)
        val darkPrimary = core.opacityCalls[0]
        val darkMuted = core.opacityCalls[1]
        assertEquals(0xE8E8EA, darkPrimary.textRgb)
        assertEquals(0x060606, darkPrimary.backgroundRgb)
        assertEquals(0.6f, darkPrimary.region, 1e-6f)
        assertEquals(4.5f, darkPrimary.minContrast, 1e-6f)
        assertEquals(1f, darkPrimary.maxOpacity, 1e-6f)
        assertEquals(0xA9A9AE, darkMuted.textRgb)
        assertEquals(0x060606, darkMuted.backgroundRgb)
        assertEquals(0.6f, darkMuted.region, 1e-6f)
        assertEquals(3f, darkMuted.minContrast, 1e-6f)
        assertEquals(1f, darkMuted.maxOpacity, 1e-6f)

        val lightRender = runBlocking { checkNotNull(store.render(false)) }
        assertEquals(0.5f, lightRender.opacity, 1e-6f)
        assertEquals(true, core.renderCalls[1].light)
        val lightPrimary = core.opacityCalls[2]
        val lightMuted = core.opacityCalls[3]
        assertEquals(0x27272C, lightPrimary.textRgb)
        assertEquals(0xF3F3F5, lightPrimary.backgroundRgb)
        assertEquals(4.5f, lightPrimary.minContrast, 1e-6f)
        assertEquals(0x62626A, lightMuted.textRgb)
        assertEquals(0xF3F3F5, lightMuted.backgroundRgb)
        assertEquals(3f, lightMuted.minContrast, 1e-6f)
    }

    @Test
    fun renderSendsRgbaBytesToTheCore() {
        val core = RecordingCore()
        val store = newStore(core)
        runBlocking {
            assertTrue(store.set(solidJpeg(64, 64, RED, "red")).await())
            assertNotNull(store.render(true))
        }
        // Android ARGB_8888 is R, G, B, A per pixel in memory.
        val rgba = core.renderCalls.single().rgba
        assertEquals(64 * 64 * 4, rgba.size)
        for (offset in intArrayOf(0, (17 * 64 + 31) * 4, (63 * 64 + 63) * 4)) {
            assertByteNear(255, rgba[offset], "red channel at $offset")
            assertByteNear(0, rgba[offset + 1], "green channel at $offset")
            assertByteNear(0, rgba[offset + 2], "blue channel at $offset")
            assertByteNear(255, rgba[offset + 3], "alpha channel at $offset")
        }
    }

    @Test
    fun rendersAreCachedPerAppearanceAndInvalidatedOnChange() {
        val core = RecordingCore()
        val store = newStore(core)
        runBlocking {
            assertTrue(store.set(solidJpeg(64, 64, GRAY, "cache")).await())
        }
        val first = runBlocking { checkNotNull(store.render(true)) }
        val again = runBlocking { checkNotNull(store.render(true)) }
        assertSame(first, again)
        assertEquals(1, core.renderCalls.size)

        // The other appearance is a separate cache entry.
        runBlocking { checkNotNull(store.render(false)) }
        assertEquals(2, core.renderCalls.size)

        // Any change clears the cache.
        store.setEffect(WallpaperEffect.DITHER)
        val third = runBlocking { checkNotNull(store.render(true)) }
        assertNotSame(first, third)
        assertEquals(3, core.renderCalls.size)
        assertEquals(WallpaperEffect.DITHER, core.renderCalls.last().effect)

        // A re-import clears it too (AC: "After setEffect(…) or set(…),
        // render(true) returns a new instance").
        runBlocking {
            assertTrue(store.set(solidJpeg(64, 64, RED, "reimport")).await())
        }
        val fourth = runBlocking { checkNotNull(store.render(true)) }
        assertNotSame(third, fourth)
        assertEquals(4, core.renderCalls.size)
    }

    @Test
    fun aStaleRenderIsDeliveredButNotCached() {
        val core = BlockingCore()
        val store = newStore(core)
        runBlocking {
            assertTrue(store.set(solidJpeg(64, 64, GRAY, "stale")).await())
        }
        runBlocking {
            val first = async(Dispatchers.Default) { store.render(true) }
            assertTrue("the render never reached the core", core.awaitEntered())
            store.setEffect(WallpaperEffect.DITHER) // bumps the generation mid-render
            core.release()
            val stale = first.await()
            assertNotNull(stale) // a stale result is still delivered

            assertEquals(1, core.renderCalls)
            val fresh = checkNotNull(store.render(true))
            // The stale render was not cached: the core runs again, with the
            // new key "dither-true".
            assertEquals(2, core.renderCalls)
            assertEquals(WallpaperEffect.DITHER, core.lastEffect)
            assertNotSame(stale, fresh)
        }
    }

    // ---------------------------------------------------------- main-safety

    @Test
    fun setEffectStaysMainSafeUnderAStrictThreadPolicy() {
        val store = newStore(RecordingCore())
        val previous = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyDeath()
                .build(),
        )
        try {
            store.setEffect(WallpaperEffect.HALFTONE)
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
        assertEquals("halftone", prefs().getString("wallpaperEffect", null))
    }

    @Test
    fun importsRendersAndRemovesRunOffTheMainThread() {
        val core = RecordingCore()
        val ioThreads = CopyOnWriteArraySet<Thread>()
        val renderThreads = CopyOnWriteArraySet<Thread>()
        val store = WallpaperStore(
            context = context,
            prefs = prefs(),
            core = core,
            io = ThreadRecordingDispatcher(Dispatchers.Default.limitedParallelism(1), ioThreads),
            renderDispatcher = ThreadRecordingDispatcher(Dispatchers.Default.limitedParallelism(1), renderThreads),
            scope = CoroutineScope(SupervisorJob()),
        )
        runBlocking {
            assertTrue(store.set(solidJpeg(64, 64, RED, "threaded")).await())
            assertNotNull(store.render(true))
            store.remove().join()
        }
        val main = Looper.getMainLooper().thread
        // The whole io block — decode, compress, delete — runs off main.
        assertTrue(ioThreads.isNotEmpty())
        assertTrue(ioThreads.none { it === main })
        assertTrue(renderThreads.isNotEmpty())
        assertTrue(renderThreads.none { it === main })
        assertTrue(core.threads.none { it === main })
    }

    @Test
    fun getIsTheProcessWideOwner() {
        WallpaperStore.resetForTest()
        try {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val otherContext: Context = ContextWrapper(app)
            val store = WallpaperStore.get(app)
            assertSame(store, WallpaperStore.get(otherContext))
        } finally {
            WallpaperStore.resetForTest()
        }
    }

    // ---------------------------------------------------------------- fakes

    /** Records every call; renders pass-through and reports fixed opacities. */
    private class RecordingCore(
        private val primaryOpacity: Float = 1f,
        private val mutedOpacity: Float = 1f,
    ) : WallpaperCore {

        class RenderCall(
            val rgba: ByteArray,
            val width: Int,
            val height: Int,
            val effect: WallpaperEffect,
            val light: Boolean,
        )

        class OpacityCall(
            val width: Int,
            val height: Int,
            val textRgb: Int,
            val backgroundRgb: Int,
            val region: Float,
            val minContrast: Float,
            val maxOpacity: Float,
        )

        val renderCalls = mutableListOf<RenderCall>()
        val opacityCalls = mutableListOf<OpacityCall>()
        val threads = CopyOnWriteArraySet<Thread>()

        override fun render(
            rgba: ByteArray,
            width: Int,
            height: Int,
            effect: WallpaperEffect,
            light: Boolean,
        ): ByteArray {
            threads.add(Thread.currentThread())
            renderCalls.add(RenderCall(rgba.copyOf(), width, height, effect, light))
            return rgba.copyOf()
        }

        override fun safeOpacity(
            rgba: ByteArray,
            width: Int,
            height: Int,
            textRgb: Int,
            backgroundRgb: Int,
            region: Float,
            minContrast: Float,
            maxOpacity: Float,
        ): Float {
            threads.add(Thread.currentThread())
            opacityCalls.add(
                OpacityCall(width, height, textRgb, backgroundRgb, region, minContrast, maxOpacity),
            )
            // 4.5 is the primary (title) call, 3 the secondary (muted) one.
            return if (minContrast > 4f) primaryOpacity else mutedOpacity
        }
    }

    /** Stalls inside `render` until released, for the generation guard. */
    private class BlockingCore : WallpaperCore {

        private val entered = CountDownLatch(1)
        private val gate = CountDownLatch(1)

        var renderCalls: Int = 0
            private set

        var lastEffect: WallpaperEffect? = null
            private set

        override fun render(
            rgba: ByteArray,
            width: Int,
            height: Int,
            effect: WallpaperEffect,
            light: Boolean,
        ): ByteArray {
            renderCalls++
            lastEffect = effect
            entered.countDown()
            gate.await()
            return rgba
        }

        override fun safeOpacity(
            rgba: ByteArray,
            width: Int,
            height: Int,
            textRgb: Int,
            backgroundRgb: Int,
            region: Float,
            minContrast: Float,
            maxOpacity: Float,
        ): Float = 1f

        fun awaitEntered(): Boolean = entered.await(10, TimeUnit.SECONDS)

        fun release() {
            gate.countDown()
        }
    }

    /** Runs blocks inline (like Unconfined) while counting the hops. */
    private class CountingDispatcher : CoroutineDispatcher() {
        var entries: Int = 0
            private set

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            entries++
            block.run()
        }
    }

    /** Records the thread each block actually runs on, on a real dispatcher. */
    private class ThreadRecordingDispatcher(
        private val delegate: CoroutineDispatcher,
        private val threads: MutableSet<Thread>,
    ) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context, Runnable {
                threads.add(Thread.currentThread())
                block.run()
            })
        }
    }
}

/**
 * Serves [fixture] under [AUTHORITY] for the `set(uri)` tests: a
 * `DISPLAY_NAME` query (or no row), `image/jpeg` for `getType`, and the
 * file for `openFile` — what `ContentResolver.openInputStream(uri)` reads
 * the picked image's bytes through.
 */
class FakeImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
        val name = displayName ?: return MatrixCursor(columns) // no row
        return MatrixCursor(columns).apply { addRow(arrayOf<Any>(name)) }
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = fixture ?: throw FileNotFoundException("no fixture registered for $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        var fixture: File? = null
        var displayName: String? = null
    }
}
