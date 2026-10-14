package sh.roboco.android.transcript

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * The tile loader's cache key (ticket 66, following upstream #817's
 * `imageCacheKey`): two engines can serve different files under the same
 * path, so the reference alone is never enough. The length-prefix shape
 * of iOS's `"\(device.utf8.count):\(device)\(reference)"` is data-class
 * equality here: `ImageKey("a", "bc") != ImageKey("ab", "c")` for the
 * same reason — neither pair collides.
 */
data class ImageKey(val engineDeviceId: String, val reference: String)

/**
 * Where attachment bytes come from. Production is
 * `AttachmentReader { d, p -> core.client.readAttachment(d, p) }`
 * (android-app#08: a UniFFI `suspend` fn that throws `CoreException` and
 * runs on the Rust runtime — call it directly, never inside `runBlocking`).
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

/** Long-side cap of `decode(bytes, 0)` (full-size loads, tickets 125/126). */
internal const val FullSideCap = 4096

/** A Windows drive-absolute reference (`C:\\…` or `C:/…`). */
private val drivePath = Regex("^[A-Za-z]:[\\\\/]")

/**
 * Whether an image reference can be served by `readAttachment` at all
 * (ticket 137, Roboco's no-RPC adaptation). The engine's path jail serves
 * only absolute paths and `pending://` upload references: POSIX absolute
 * (`/…`), Windows drive-absolute (`C:\\…`, `C:/…`) and `\\\\…` UNC/verbatim
 * paths are **fetchable** (the engine decides whether to serve them), as
 * is any `pending://…` reference. Everything else — `http(s)://` (the
 * phone never fetches HTTP image URLs, #817), `data:`, `file:`,
 * `roboco-file:`, relative paths and the empty string — is refused
 * without a round trip: the tile fails synchronously, and no engine id
 * is even read.
 */
fun isFetchableImageReference(ref: String): Boolean = when {
    ref.isEmpty() -> false
    ref.startsWith("pending://") -> true
    drivePath.containsMatchIn(ref) -> true
    ref.startsWith("/") || ref.startsWith("\\\\") -> true
    else -> false
}

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
     * Cache → in-flight deferred → read + decode. Null on any failure
     * (not cached; a later call tries again). The read runs wherever the
     * [AttachmentReader] decides (the Rust runtime), the decode on
     * [decodeDispatcher] — never on main.
     */
    suspend fun load(key: ImageKey, sidePx: Int, reader: AttachmentReader): ImageBitmap? {
        cached(key, sidePx)?.let { return it }
        val pair = key to sidePx
        val deferred = inflight.getOrPut(pair) {
            scope.async {
                try {
                    val bytes = reader.read(key.engineDeviceId, key.reference)
                    val decoded = withContext(decodeDispatcher) { decode.decode(bytes, sidePx) }
                    store(key, decoded)
                    decoded
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // read error or undecodable bytes: stay empty, no retry
                } finally {
                    inflight.remove(pair)
                }
            }
        }
        return deferred.await()?.bitmap
    }

    /**
     * Full-size decode (long side ≤ [FullSideCap]) for the viewer (125)
     * and image-chip links (126). Not stored in the tile LRU, not deduped.
     */
    suspend fun loadFull(key: ImageKey, reader: AttachmentReader): ImageBitmap? {
        val bytes = try {
            reader.read(key.engineDeviceId, key.reference)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val decoded = try {
            withContext(decodeDispatcher) { decode.decode(bytes, 0) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return decoded.bitmap
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
 * call time) and the [reader]. A blank engine id (no host yet) means no
 * read and no cache entry, and (137) an unfetchable reference — `http(s)`,
 * `data:`, other URI schemes, relative paths — short-circuits to a null
 * result **before** the engine id is read or the reader is called, so the
 * tile fails without a round trip.
 */
class TranscriptImageBinding(
    private val loader: TranscriptImageLoader,
    private val engineDeviceId: () -> String,
    private val reader: AttachmentReader,
) {
    fun cached(reference: String, sidePx: Int): ImageBitmap? {
        if (!isFetchableImageReference(reference)) return null
        val id = engineDeviceId()
        if (id.isBlank()) return null
        return loader.cached(ImageKey(id, reference), sidePx)
    }

    suspend fun load(reference: String, sidePx: Int): ImageBitmap? {
        if (!isFetchableImageReference(reference)) return null
        val id = engineDeviceId()
        if (id.isBlank()) return null
        return loader.load(ImageKey(id, reference), sidePx, reader)
    }

    suspend fun loadFull(reference: String): ImageBitmap? {
        if (!isFetchableImageReference(reference)) return null
        val id = engineDeviceId()
        if (id.isBlank()) return null
        return loader.loadFull(ImageKey(id, reference), reader)
    }
}
