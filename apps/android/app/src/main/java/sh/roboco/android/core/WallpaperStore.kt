package sh.roboco.android.core

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.StrictMode
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.design.Palette
import uniffi.roboco_core.WallpaperEffect

/**
 * Seam over the two mobile-core wallpaper calls so tests can count and
 * stall them (android-app#05/#10). Dimensions and colours are Kotlin
 * [Int]s; [Ffi] widens them with `toUInt()` for the UniFFI signatures.
 */
interface WallpaperCore {

    /** `mob:wallpaper.rs:44-57` — straight RGBA8 in, straight RGBA8 out. */
    fun render(
        rgba: ByteArray,
        width: Int,
        height: Int,
        effect: WallpaperEffect,
        light: Boolean,
    ): ByteArray

    /** `mob:wallpaper.rs:177-235` — the WCAG contrast guard. */
    fun safeOpacity(
        rgba: ByteArray,
        width: Int,
        height: Int,
        textRgb: Int,
        backgroundRgb: Int,
        region: Float,
        minContrast: Float,
        maxOpacity: Float,
    ): Float

    /** The real core, through the generated UniFFI Kotlin. */
    object Ffi : WallpaperCore {
        override fun render(
            rgba: ByteArray,
            width: Int,
            height: Int,
            effect: WallpaperEffect,
            light: Boolean,
        ): ByteArray = uniffi.roboco_core.wallpaperRender(
            rgba, width.toUInt(), height.toUInt(), effect, light,
        )

        override fun safeOpacity(
            rgba: ByteArray,
            width: Int,
            height: Int,
            textRgb: Int,
            backgroundRgb: Int,
            region: Float,
            minContrast: Float,
            maxOpacity: Float,
        ): Float = uniffi.roboco_core.wallpaperSafeOpacity(
            rgba, width.toUInt(), height.toUInt(),
            textRgb.toUInt(), backgroundRgb.toUInt(),
            region, minContrast, maxOpacity,
        )
    }
}

/**
 * The chat wallpaper's one owner in the process: the stored image file
 * (`filesDir/wallpaper/wallpaper.jpg`), the `wallpaperName` and
 * `wallpaperEffect` prefs, and the rendered, contrast-guarded cache.
 * A port of `ios:Design/Wallpaper.swift:6-187` (`enum WallpaperStore`):
 * same file name, same pref strings, same effects, same scale maths —
 * `NotificationCenter` becomes [state] (bump [WallpaperStore.State.generation]
 * on every change), the serial `userInitiated` queue becomes
 * [renderDispatcher], and iOS's live `fileExists` becomes a single
 * constructor read that the mutators then maintain: this store is the only
 * writer, so a per-read disk stat would only race (and put a disk read on
 * the main thread in [setEffect]).
 *
 * Mutators are fire-and-forget like iOS's, on the store-owned [scope]; each
 * returns a handle tests can await. Consumers (the hero of ticket 21, the
 * settings rows of ticket 97, the launch extras of ticket 36) reach the
 * store through [get].
 */
class WallpaperStore internal constructor(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val core: WallpaperCore = WallpaperCore.Ffi,
    /** Serial: imports and `remove()` never interleave (iOS mutates on main). */
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    /** Serial: renders queue up, like the serial `userInitiated` queue. */
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** What consumers watch; [State.generation] bumps on every change. */
    data class State(val set: Boolean, val name: String?, val effect: WallpaperEffect, val generation: Int)

    /** A rendered wallpaper and the highest opacity that keeps text readable. */
    class Render(val image: ImageBitmap, val opacity: Float)

    private val file = File(context.filesDir, "wallpaper/wallpaper.jpg")
    private val lock = Any()
    private val cache = HashMap<String, Render>()
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<State> = _state.asStateFlow()

    // ---------------------------------------------------------------- mutators

    /**
     * Import a picked image (content Uri). Name = the provider's display
     * name minus its extension (Photos `suggestedName` has none), fallback
     * `"Photo"`.
     *
     * The Uri grant is temporary: start this while the activity still holds
     * it; the read happens on [io], not on the caller.
     */
    fun set(uri: Uri): Deferred<Boolean> =
        scope.async(io) {
            importFrom(displayName(uri)) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw FileNotFoundException("no stream for $uri")
            }
        }

    /** Import a file (debug launch extra `wallpaper`). Name = file name with extension. */
    fun set(path: File): Deferred<Boolean> =
        scope.async(io) {
            importFrom(path.name) { path.readBytes() }
        }

    /**
     * Sets the effect pref and bumps the generation — even when the value is
     * unchanged, like iOS. Main-safe: `apply()` writes asynchronously.
     */
    fun setEffect(effect: WallpaperEffect) {
        prefs.edit().putString(PREF_EFFECT, effect.key).apply()
        changed { it.copy(effect = effect) }
    }

    /** Launch-extra form: an unknown key is ignored (returns `false`), like iOS. */
    fun setEffect(key: String): Boolean {
        val effect = WallpaperEffects.fromKey(key) ?: return false
        setEffect(effect)
        return true
    }

    /** Removes the image and its name; `wallpaperEffect` survives, like iOS. */
    fun remove(): Job =
        scope.launch(io) {
            file.delete()
            prefs.edit().remove(PREF_NAME).apply()
            changed { it.copy(set = false, name = null) }
        }

    // ----------------------------------------------------------------- render

    /**
     * Decodes the stored JPEG (long side ≤ [MAX_RENDER_SIDE], **truncating**
     * the target size — iOS `Int(CGFloat)` truncates), runs the core effect
     * for the current [State.effect] and the requested appearance, computes
     * the contrast-guard opacity and caches the result per appearance.
     * `null` when no wallpaper is set or decoding fails (never cached).
     * Safe to call from any thread or coroutine.
     */
    suspend fun render(dark: Boolean): Render? {
        val s = synchronized(lock) { _state.value }
        if (!s.set) return null
        val key = "${s.effect.key}-$dark"
        synchronized(lock) { cache[key] }?.let { return it }
        // text / secondary / background resolved for the requested appearance
        val palette = Palette.of(dark)
        val text = palette.text.toArgb() and 0xFFFFFF
        val secondary = palette.secondary.toArgb() and 0xFFFFFF
        val background = palette.background.toArgb() and 0xFFFFFF
        return withContext(renderDispatcher) {
            // An identical render queued earlier may have filled the cache.
            synchronized(lock) { cache[key] }?.let { return@withContext it }
            runCatching {
                val bitmap = toArgb8888(
                    ImageDecoder.decodeBitmap(bytesSource(file.readBytes())) { decoder, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        val scale = min(1.0, MAX_RENDER_SIDE.toDouble() / max(w, h))
                        if (scale < 1.0) {
                            // Truncation, like Swift Int(CGFloat) — `roundToInt()`
                            // would shift the effect grid by a pixel.
                            decoder.setTargetSize(
                                max(1, (w * scale).toInt()),
                                max(1, (h * scale).toInt()),
                            )
                        }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    },
                )
                val width = bitmap.width
                val height = bitmap.height
                // Android ARGB_8888 is RGBA in memory: R, G, B, A per pixel,
                // row-major, top row first. A host Skia (Robolectric) may
                // use BGRA for the same config: [toRgba] normalises.
                val buffer = ByteBuffer.allocate(width * height * 4)
                bitmap.copyPixelsToBuffer(buffer)
                bitmap.recycle()
                val rgba = toRgba(buffer.array())
                val out = core.render(rgba, width, height, s.effect, light = !dark)
                // Text sits over the top of the artwork: primary text keeps
                // 4.5:1, secondary 3:1 against the artwork's worst pixels there.
                val primary = core.safeOpacity(
                    out, width, height, text, background, CONTRAST_REGION, PRIMARY_MIN_CONTRAST, 1f,
                )
                val muted = core.safeOpacity(
                    out, width, height, secondary, background, CONTRAST_REGION, SECONDARY_MIN_CONTRAST, 1f,
                )
                val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                rendered.copyPixelsFromBuffer(ByteBuffer.wrap(toRgba(out.copyOf())))
                rendered.prepareToDraw()
                Render(rendered.asImageBitmap(), min(primary, muted))
            }.onFailure { error ->
                Log.w(TAG, "could not render the wallpaper", error)
            }.getOrNull()?.also { render ->
                synchronized(lock) {
                    // A stale result is still delivered, just not cached: the
                    // caller reloads anyway because it also observes the change.
                    if (_state.value.generation == s.generation) cache[key] = render
                }
            }
        }
    }

    // -------------------------------------------------------------- internals

    /**
     * The store's only synchronous disk read (iOS reads `UserDefaults`
     * synchronously too, so the first frame already knows). After this,
     * each mutator states its own delta — never a re-read.
     */
    private fun initial(): State {
        val policy = StrictMode.allowThreadDiskReads()
        try {
            return State(
                set = file.exists(),
                name = prefs.getString(PREF_NAME, null),
                effect = WallpaperEffects.fromKey(prefs.getString(PREF_EFFECT, null))
                    ?: WallpaperEffect.NONE,
                generation = 0,
            )
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    /**
     * Clear the cache, apply the delta and bump the generation — all under
     * one lock, which is what makes `render`'s `generation` check sound.
     * No disk access: the caller's delta is the truth.
     */
    private fun changed(update: (State) -> State) {
        synchronized(lock) {
            cache.clear()
            val current = _state.value
            _state.value = update(current).copy(generation = current.generation + 1)
        }
    }

    /**
     * The import pipeline (both the picker Uri and the launch-arg file):
     * decode-time downsampling to long side ≤ [MAX_STORED_SIDE] (rounding,
     * like Swift `.rounded()`), EXIF orientation applied by `ImageDecoder`,
     * JPEG 90, atomic write, then the name pref and the change signal.
     * A failed import returns `false` with no pref change and no signal.
     * [read] (the encoded bytes) runs inside the failure handling.
     */
    private fun importFrom(name: String, read: () -> ByteArray): Boolean =
        runCatching {
            val bitmap = toArgb8888(
                ImageDecoder.decodeBitmap(bytesSource(read())) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = min(1.0, MAX_STORED_SIDE.toDouble() / max(w, h))
                    if (scale < 1.0) {
                        decoder.setTargetSize(target(w, scale), target(h, scale))
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                },
            )
            try {
                file.parentFile!!.mkdirs()
                val atomic = AtomicFile(file)
                val out = atomic.startWrite()
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        "JPEG encode failed"
                    }
                    atomic.finishWrite(out)
                } catch (error: Throwable) {
                    atomic.failWrite(out)
                    throw error
                }
            } finally {
                bitmap.recycle()
            }
            prefs.edit().putString(PREF_NAME, name).apply()
            changed { it.copy(set = true, name = name) }
            true
        }.onFailure { error ->
            Log.w(TAG, "could not import the wallpaper from $name", error)
        }.getOrDefault(false)

    /**
     * The picker's display name minus its extension — Photos' `suggestedName`
     * has none (`IMG_0412.HEIC` → `IMG_0412`); a failed query or a blank
     * result falls back to `"Photo"`.
     */
    private fun displayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val name = queried?.trim()
        if (name.isNullOrEmpty()) return FALLBACK_NAME
        // A leading dot (".hidden") is the whole name, not an extension.
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /**
     * Decodes from the encoded bytes in memory, never from a file
     * descriptor: `ImageDecoder`'s File/Uri sources hand the native decoder
     * a raw fd, which host Skia builds without POSIX descriptors (Robolectric
     * native graphics on Windows) cannot read, so every import and render
     * failed there. Downsampling and EXIF orientation are unchanged — both
     * are decode-time options of the same `ImageDecoder`; the cost is one
     * copy of the compressed file (a few MB) for the decode's duration.
     */
    private fun bytesSource(bytes: ByteArray): ImageDecoder.Source =
        ImageDecoder.createSource(ByteBuffer.wrap(bytes))

    /** Scales `v` by [scale], clamped to ≥ 1 and rounded (Swift `.rounded()`). */
    private fun target(v: Int, scale: Double): Int = max(1, (v * scale).roundToInt())

    /**
     * Swaps R and B in place when this platform's ARGB_8888 memory order is
     * BGRA (host Skia builds, e.g. Robolectric's native graphics); a no-op
     * on devices, where it is RGBA. The swap is its own inverse, so the
     * same call converts the core's RGBA output back to native order.
     */
    private fun toRgba(pixels: ByteArray): ByteArray {
        if (!nativeIsBgra) return pixels
        var i = 0
        while (i + 3 < pixels.size) {
            val r = pixels[i]
            pixels[i] = pixels[i + 2]
            pixels[i + 2] = r
            i += 4
        }
        return pixels
    }

    /**
     * Wide-gamut or HDR sources can decode to `RGBA_F16`; the core and
     * `copyPixelsToBuffer` expect 8-bit RGBA, so convert once.
     */
    private fun toArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val converted = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) {
            "could not convert the ${bitmap.config} wallpaper to ARGB_8888"
        }
        bitmap.recycle()
        return converted
    }

    companion object {
        private const val TAG = "Wallpaper"

        /** Probes one opaque red pixel: is ARGB_8888 laid out B, G, R, A here? */
        private val nativeIsBgra: Boolean by lazy {
            val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            probe.eraseColor(0xFFFF0000.toInt())
            val bytes = ByteBuffer.allocate(4)
            probe.copyPixelsToBuffer(bytes)
            probe.recycle()
            (bytes.get(0).toInt() and 0xFF) == 0 && (bytes.get(2).toInt() and 0xFF) == 0xFF
        }
        private const val PREFS_FILE = "wallpaper"
        private const val PREF_NAME = "wallpaperName"
        private const val PREF_EFFECT = "wallpaperEffect"
        private const val FALLBACK_NAME = "Photo"

        /** Stored image long side (px), `Wallpaper.swift:74,93`. */
        const val MAX_STORED_SIDE = 1600
        /** Render input long side (px), `Wallpaper.swift:171`. */
        const val MAX_RENDER_SIDE = 1200
        /** JPEG quality, `Wallpaper.swift:79,95`. */
        const val JPEG_QUALITY = 90
        /** Fraction of the image height where text sits, `Wallpaper.swift:152`. */
        const val CONTRAST_REGION = 0.6f
        /** Primary text (title) contrast, `Wallpaper.swift:153`. */
        const val PRIMARY_MIN_CONTRAST = 4.5f
        /** Secondary text contrast, `Wallpaper.swift:154`. */
        const val SECONDARY_MIN_CONTRAST = 3f

        @Volatile
        private var instance: WallpaperStore? = null

        /** The process-wide store: exactly one owner of the file and the prefs. */
        fun get(context: Context): WallpaperStore {
            instance?.let { return it }
            val app = context.applicationContext
            return synchronized(this) {
                instance ?: WallpaperStore(
                    context = app,
                    prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
        }

        @VisibleForTesting
        fun resetForTest() {
            synchronized(this) { instance = null }
        }
    }
}
