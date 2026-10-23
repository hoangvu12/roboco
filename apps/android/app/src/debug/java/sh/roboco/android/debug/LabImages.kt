package sh.roboco.android.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.roboco.android.transcript.AttachmentReader

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

/**
 * The `--ez images true` lab's user prompt (ticket 137, pr817's demo chat
 * `chat-images`): "Show the calculation screenshot." — verbatim.
 */
const val LAB_IMAGES_PROMPT = "Show the calculation screenshot."

/**
 * The `--ez images true` lab's assistant reply (ticket 137, the pr817 demo
 * fixture `transcripts.rs:616-621`, verbatim): the served workspace PNG
 * (960 × 540), its separate download link, and a missing image (no file
 * at that `/tmp` path).
 */
const val LAB_IMAGES_FIXTURE =
    "Here are the screenshots.\n\n" +
        "![Calculation result: 8](/workspace/calculator/5-plus-3.png)\n\n" +
        "[Download screenshot](/workspace/calculator/5-plus-3.png)\n\n" +
        "![Missing screenshot](/tmp/calculator-result/missing.png)"

/**
 * The lab's markdown-image edge cases (ticket 137): inline tiles in text
 * order, a quoted tile, a listed tile, a linked tile with its caption
 * link, a remote URL (the phone fetches it, then the lab reader as the
 * engine fallback), a relative path (the lab reader, which has no such
 * file), and the heading/table-cell **linked-alt fallback** that
 * #817 keeps because those still call `prepare_runs` directly.
 */
const val LAB_IMAGES_EXTRAS_FIXTURE =
    "Before ![First](/workspace/calculator/5-plus-3.png) after " +
        "![Second](/workspace/calculator/portrait.png) end.\n\n" +
        "> ![Quoted](/workspace/calculator/5-plus-3.png)\n\n" +
        "- ![Listed](/workspace/calculator/portrait.png)\n\n" +
        "[![Linked](/workspace/calculator/5-plus-3.png)](https://example.com/details)\n\n" +
        "![Remote](https://example.com/remote.png)\n\n" +
        "![Relative](assets/shot.png)\n\n" +
        "### Heading ![Logo](/workspace/calculator/5-plus-3.png)\n\n" +
        "| Shot |\n| --- |\n| ![Cell](/workspace/calculator/5-plus-3.png) |"

/**
 * The deterministic lab art (ticket 137; the #817 demo's generated PNG
 * stand-in): a two-stop diagonal gradient `#5B43E8` (top-left) →
 * `#F0A04B` (bottom-right) with a white 2 px grid every 100 px — 66's
 * fixture palette, so the tile's aspect-fit letterboxing is obvious.
 * Memoized per size.
 */
internal val labGradientCache = ConcurrentHashMap<Pair<Int, Int>, Bitmap>()

internal fun labGradientBitmap(w: Int, h: Int): Bitmap = labGradientCache.getOrPut(w to h) {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        0f, 0f, w.toFloat(), h.toFloat(),
        0xFF5B43E8.toInt(), 0xFFF0A04B.toInt(),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    paint.color = 0xFFFFFFFF.toInt()
    paint.strokeWidth = 2f
    var x = 100
    while (x < w) {
        canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), paint)
        x += 100
    }
    var y = 100
    while (y < h) {
        canvas.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), paint)
        y += 100
    }
    bitmap
}

/** PNG bytes of [labGradientBitmap] at `w × h`, memoized per size. */
private val labPngCache = ConcurrentHashMap<Pair<Int, Int>, ByteArray>()

/**
 * The lab's generated attachment (ticket 137): the gradient art as PNG
 * bytes, memoized per size. The heavy encode runs on the caller's
 * dispatcher — [LabImageReader] wraps it in `Dispatchers.Default`.
 */
fun labFixturePng(w: Int, h: Int): ByteArray = labPngCache.getOrPut(w to h) {
    ByteArrayOutputStream().use { out ->
        labGradientBitmap(w, h).compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}

/**
 * One lab-style read: [delayMs], then the gradient PNG built off main —
 * the shared shape of [LabImageReader] and the states gallery's readers.
 */
internal suspend fun serveLabFixture(delayMs: Long, w: Int, h: Int): ByteArray {
    delay(delayMs)
    return withContext(Dispatchers.Default) { labFixturePng(w, h) }
}

/**
 * The lab's image attachment source (ticket 137, `--ez images true`): the
 * deterministic stand-in for `readAttachment` — the two workspace PNGs
 * after 600 ms, `IOException` for anything else after 300 ms (no such file
 * on the engine), with a per-reference read count so the lab
 * tests and the retry demo can pin the RPC count.
 */
object LabImageReader : AttachmentReader {

    /** The lab image the `images` extra serves, verbatim from #817's demo. */
    internal const val LAB_LANDSCAPE = "/workspace/calculator/5-plus-3.png"

    /** The portrait companion (the Roboco addition). */
    internal const val LAB_PORTRAIT = "/workspace/calculator/portrait.png"

    private val readsByRef = ConcurrentHashMap<String, AtomicInteger>()

    /** How many reads [reference] has seen (0 before the first). */
    fun reads(ref: String): Int = readsByRef[ref]?.get() ?: 0

    override suspend fun read(engineDeviceId: String, reference: String): ByteArray {
        readsByRef.getOrPut(reference) { AtomicInteger() }.incrementAndGet()
        return when (reference) {
            LAB_LANDSCAPE -> serveLabFixture(600, 960, 540)
            LAB_PORTRAIT -> serveLabFixture(600, 540, 960)
            else -> {
                delay(300)
                throw IOException("NotFound: $reference")
            }
        }
    }
}
