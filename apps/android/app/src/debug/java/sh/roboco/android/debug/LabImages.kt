package sh.roboco.android.debug

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The lab's fake image loader (android-ui-parity #135): the bundled
 * `lab/lab-image.png` (a copy of `dist/roboco.png`, 512×512 RGBA) for
 * **every** reference, so inline markdown images (137) and image rows
 * can be exercised without an engine. The fixture has no images, so
 * today this is dormant.
 *
 * The PNG is decoded once off the main thread, downsampled with
 * `inSampleSize` to the largest power of two that keeps both sides at
 * the requested side, and memoized: the second call does no I/O.
 * [sidePx] follows ticket 66's convention — `> 0` is the decode target,
 * `0` or negative means full size. A failure logs once and returns
 * `null` (the tile stays a placeholder).
 */
object LabImages {

    private const val ASSET = "lab/lab-image.png"
    private const val TAG = "RobocoLab"

    private val mutex = Mutex()

    /** The memoized bitmap, `null` until the first successful decode. */
    @Volatile
    private var bitmap: ImageBitmap? = null

    /** Set when the memoized bitmap was decoded with `inSampleSize` 1. */
    @Volatile
    private var full = false

    /** One-shot failure log: the asset is missing only on broken builds. */
    @Volatile
    private var warned = false

    /** The synchronous cache peek (ticket 66): cheap and main-thread safe. */
    fun cached(sidePx: Int): ImageBitmap? =
        bitmap?.takeIf { full || (sidePx > 0 && minOf(it.width, it.height) >= sidePx) }

    /**
     * Loads the lab image for [sidePx], memoizing the first decode. With
     * the 512×512 asset: side 256 → `inSampleSize` 2 (256×256), 100 → 4
     * (128×128), 300 or 0 → 1 (512×512). The `sidePx > 0` guard matters:
     * without it, 0 would loop until `sample` overflows.
     */
    suspend fun load(context: Context, sidePx: Int = 256): ImageBitmap? = mutex.withLock {
        cached(sidePx) ?: withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(ASSET).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                if (sidePx > 0) {
                    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sidePx) sample *= 2
                }
                context.assets.open(ASSET).use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }?.asImageBitmap()?.also { full = sample == 1 }
            }.onFailure {
                if (!warned) {
                    warned = true
                    Log.w(TAG, "lab image decode failed", it)
                }
            }.getOrNull()
        }?.also { bitmap = it }
    }
}
