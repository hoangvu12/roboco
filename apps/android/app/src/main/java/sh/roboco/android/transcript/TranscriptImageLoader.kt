package sh.roboco.android.transcript

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/**
 * The tile loader's cache key (ticket 66, following upstream #817's
 * `imageCacheKey`): two engines can serve different files under the same
 * path, so the reference alone is never enough. The length-prefix shape
 * of iOS's `"\(device.utf8.count):\(device)\(reference)"` is data-class
 * equality here: `ImageKey("a", "bc") != ImageKey("ab", "c")` for the
 * same reason — neither pair collides. [chatId] scopes the entry to one
 * chat: a relative `shots/a.png` names a different file in every chat's
 * cwd.
 */
data class ImageKey(val engineDeviceId: String, val reference: String, val chatId: String = "")

/**
 * Where engine-served bytes come from ([ImageSource.Engine]). Production
 * is `AttachmentReader { d, r -> core.client.readImage(d, r, chatId) }`
 * (the engine's `ReadImageChunk`, falling back to `ReadAttachmentChunk`
 * on older engines; android-app#08: a UniFFI `suspend` fn that throws
 * `CoreException` and runs on the Rust runtime — call it directly, never
 * inside `runBlocking`).
 */
fun interface AttachmentReader {
    suspend fun read(engineDeviceId: String, reference: String): ByteArray
}

/**
 * Bytes → bitmap. [sidePx] > 0 asks for a tile whose **longer** side is
 * exactly `sidePx` px (downsampled, never upscaled; ticket 137: the tile
 * is aspect-fit, so the picture must fit the whole source inside the
 * square, not crop it); `0` asks for the full image with the long side
 * capped at [FullSideCap]. Throws on failure — the loader turns that
 * into a null result.
 */
fun interface ImageDecode {
    fun decode(bytes: ByteArray, sidePx: Int): DecodedImage
}

/** One decoded picture; [full] = no downsampling was applied. */
class DecodedImage(val bitmap: ImageBitmap, val full: Boolean)

/**
 * Long-side cap of `decode(bytes, 0)` (full-size loads, tickets 125/126):
 * well past any phone screen, so the zoomed viewer stays sharp, while a
 * worst-case bitmap stays at 64 MB.
 */
internal const val FullSideCap = 4096

/**
 * The real decoder (ticket 66 step 1): `ImageDecoder` with EXIF
 * orientation applied, integer ceil-division downsampling so the **longer**
 * source side lands on exactly `sidePx` (ticket 137's fit rule — a float
 * `ceil(w·scale)` can overshoot by 1 px) and the long side capped at
 * [FullSideCap] for full loads. `ALLOCATOR_DEFAULT` may hand back a
 * `HARDWARE` bitmap — it draws in Compose but its pixels cannot be read;
 * tests inject `ALLOCATOR_SOFTWARE`.
 */
internal class PlatformImageDecode(
    private val allocator: Int = ImageDecoder.ALLOCATOR_DEFAULT,
) : ImageDecode {
    override fun decode(bytes: ByteArray, sidePx: Int): DecodedImage {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        var full = true
        val bmp = ImageDecoder.decodeBitmap(source) { d, info, _ ->
            d.allocator = allocator
            val w = info.size.width
            val h = info.size.height
            if (sidePx > 0) {
                val long = maxOf(w, h)
                if (long > sidePx) {
                    d.setTargetSize(ceilDiv(w, long, sidePx), ceilDiv(h, long, sidePx))
                    full = false
                }
            } else if (maxOf(w, h) > FullSideCap) {
                val long = maxOf(w, h)
                d.setTargetSize(ceilDiv(w, long, FullSideCap), ceilDiv(h, long, FullSideCap))
                full = false
            }
        }
        return DecodedImage(bmp.asImageBitmap(), full)
    }
}

/** `ceil(a · target / base)`, at least 1, in integer (Long) math. */
private fun ceilDiv(a: Int, base: Int, target: Int): Int =
    ((a.toLong() * target + base - 1) / base).toInt().coerceAtLeast(1)

/**
 * The app-wide image tile loader (ticket 66; iOS
 * `CoreSessionSource.swift:251-283`): an `NSCache`-shaped bounded LRU keyed
 * by [ImageKey], in-flight de-duplication, off-main decoding, and memory
 * trim callbacks. iOS's detached-task semantics are kept: a load runs in
 * [scope] (not the tile's), so it survives the tile leaving composition
 * and still fills the cache — a tile only *awaits* it.
 *
 * Usage through one session's [TranscriptImageBinding]; the tile itself
 * only ever sees [TranscriptActions.cachedImage] /
 * [TranscriptActions.loadImage] (ticket 65).
 */
class TranscriptImageLoader(
    private val decode: ImageDecode = PlatformImageDecode(),
    maxBytes: Int = defaultMaxBytes(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2),
    private val web: WebImageFetcher = PlatformWebImageFetcher,
) : ComponentCallbacks2 {

    private val lru = object : LruCache<ImageKey, DecodedImage>(maxBytes) {
        override fun sizeOf(key: ImageKey, value: DecodedImage): Int =
            value.bitmap.width * value.bitmap.height * 4
    }

    /** One shared load per (key, exact sidePx); removed on completion. */
    private val inflight = ConcurrentHashMap<Pair<ImageKey, Int>, Deferred<DecodedImage?>>()

    /**
     * Main-thread safe, no I/O: the usable entry per the hit rule — a
     * `full` entry always serves, a downsampled one only when its
     * **longer** side is still ≥ `sidePx` (137: the tile is aspect-fit).
     * Otherwise null (a re-decode follows).
     */
    fun cached(key: ImageKey, sidePx: Int): ImageBitmap? =
        lru.get(key)?.takeIf { it.full || maxOf(it.bitmap.width, it.bitmap.height) >= sidePx }?.bitmap

    /**
     * Cache → in-flight deferred → [fetchAndDecode]. Null on any failure
     * (not cached; a later call tries again). The engine read runs
     * wherever the [AttachmentReader] decides (the Rust runtime), the web
     * fetch on IO, the decode on [decodeDispatcher] — never on main.
     */
    suspend fun load(key: ImageKey, sidePx: Int, reader: AttachmentReader): ImageBitmap? {
        cached(key, sidePx)?.let { return it }
        val pair = key to sidePx
        inflight[pair]?.let { return it.await()?.bitmap }
        // LAZY, registered before it starts: on an eager dispatcher
        // (Main.immediate, Unconfined) a read that fails or finishes
        // without suspending would run its `finally` before a getOrPut
        // stored the deferred, leaving a completed entry in the map that
        // every later load (a retry) would join instead of reading again.
        lateinit var fresh: Deferred<DecodedImage?>
        fresh = scope.async(start = CoroutineStart.LAZY) {
            try {
                val decoded = fetchAndDecode(key, sidePx, reader)
                store(key, decoded)
                decoded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // every source failed: stay empty; the tile schedules the retry
            } finally {
                // Only this load's own entry: a later load may own the key.
                inflight.remove(pair, fresh)
            }
        }
        val deferred = inflight.putIfAbsent(pair, fresh)?.also { fresh.cancel() } ?: fresh.also { it.start() }
        return deferred.await()?.bitmap
    }

    /**
     * Full-size decode (long side ≤ [FullSideCap]) for the viewer (125)
     * and image-chip links (126). Not stored in the tile LRU, not deduped.
     */
    suspend fun loadFull(key: ImageKey, reader: AttachmentReader): ImageBitmap? = try {
        fetchAndDecode(key, 0, reader).bitmap
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Try each of the reference's [imageSources] in order — the bytes
     * *and* their decode, so an SVG the phone fetched itself (undecodable
     * here) still gets the engine's PNG rendering — and throw the last
     * failure when none works. The engine is skipped while no engine id is
     * known.
     */
    private suspend fun fetchAndDecode(key: ImageKey, sidePx: Int, reader: AttachmentReader): DecodedImage {
        var failure: Exception? = null
        for (source in imageSources(key.reference)) {
            try {
                val bytes = when (source) {
                    ImageSource.Inline -> decodeDataUri(key.reference) ?: throw IOException("malformed data: URI")
                    ImageSource.Web -> web.fetch(key.reference)
                    ImageSource.Engine -> {
                        if (key.engineDeviceId.isBlank()) throw IOException("no engine for ${key.reference}")
                        reader.read(key.engineDeviceId, key.reference)
                    }
                }
                return withContext(decodeDispatcher) { decode.decode(bytes, sidePx) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IOException("no image source for ${key.reference}")
    }

    /** Drops every cached picture (also [onTrimMemory]'s background path). */
    fun evictAll() {
        lru.evictAll()
    }

    /**
     * Store unless the existing entry is at least as good: a `full` entry
     * always wins, and a larger (longer-side, 137) downsample beats a
     * smaller one, so a 76 dp decode never replaces a 260 dp one.
     */
    private fun store(key: ImageKey, decoded: DecodedImage) {
        val old = lru.get(key)
        if (old == null || (!old.full && (decoded.full || maxSide(decoded) > maxSide(old)))) {
            lru.put(key, decoded)
        }
    }

    private fun maxSide(e: DecodedImage): Int = maxOf(e.bitmap.width, e.bitmap.height)

    /**
     * NSCache's memory-pressure eviction, as an app-level callback:
     * ≥ `TRIM_MEMORY_BACKGROUND` (40) or [onLowMemory] empties the cache,
     * `TRIM_MEMORY_UI_HIDDEN` (20) halves the budget, foreground
     * `RUNNING_*` levels (5/10/15) do nothing.
     */
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            evictAll()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            lru.trimToSize(lru.maxSize() / 2)
        }
    }

    override fun onLowMemory() {
        evictAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No cached state depends on the configuration.
    }

    companion object {
        @Volatile
        private var singleton: TranscriptImageLoader? = null

        /**
         * The app-wide instance, registering the trim callbacks on the
         * application context once. Callers hold the returned loader; the
         * registration keeps it alive for the process lifetime.
         */
        fun get(context: Context): TranscriptImageLoader {
            singleton?.let { return it }
            synchronized(this) {
                singleton?.let { return it }
                val loader = TranscriptImageLoader()
                context.applicationContext.registerComponentCallbacks(loader)
                singleton = loader
                return loader
            }
        }

        /**
         * 48 MiB or an eighth of the heap, whichever is smaller — the
         * bounded stand-in for NSCache (mirrors the Rust byte cache of
         * android-app#08).
         */
        fun defaultMaxBytes(): Int =
            min(48L shl 20, Runtime.getRuntime().maxMemory() / 8).toInt()
    }
}

/**
 * One session's view of the loader (ticket 66): fixes the engine
 * ([engineDeviceId] — `{ controller.hostDeviceId }`, ticket 51, read at
 * call time), the [reader] (bound to the session's chat, so relative
 * references resolve against its cwd) and [retries], which fires when that
 * engine reconnects so failed tiles try again at once. Only a blank
 * reference short-circuits; a blank engine id (no host yet) still lets
 * `data:` and web images load — only the engine source is skipped.
 */
class TranscriptImageBinding(
    private val loader: TranscriptImageLoader,
    private val engineDeviceId: () -> String,
    private val reader: AttachmentReader,
    val retries: Flow<Unit> = emptyFlow(),
    /** The chat [reader] resolves relative references in (part of the cache key). */
    private val chatId: String = "",
) {
    private fun key(reference: String) = ImageKey(engineDeviceId(), reference, chatId)

    fun cached(reference: String, sidePx: Int): ImageBitmap? {
        if (!isFetchableImageReference(reference)) return null
        return loader.cached(key(reference), sidePx)
    }

    suspend fun load(reference: String, sidePx: Int): ImageBitmap? {
        if (!isFetchableImageReference(reference)) return null
        return loader.load(key(reference), sidePx, reader)
    }

    suspend fun loadFull(reference: String): ImageBitmap? {
        if (!isFetchableImageReference(reference)) return null
        return loader.loadFull(key(reference), reader)
    }
}
