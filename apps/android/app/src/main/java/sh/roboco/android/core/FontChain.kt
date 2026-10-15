package sh.roboco.android.core

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.SystemFonts
import android.os.LocaleList
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.nio.ByteBuffer
import java.util.EnumMap
import java.util.HashMap
import java.util.Locale
import uniffi.roboco_core.FaceRole
import androidx.compose.ui.text.font.FontStyle as ComposeFontStyle
import android.graphics.fonts.FontStyle as PlatformFontStyle

/** `FaceRole.entries[face.ordinal]`: the Rust role the Kotlin face maps to. */
val Face.role: FaceRole
    get() = FaceRole.entries[ordinal]

/** `Face.entries[role.ordinal]`: the Kotlin face for a Rust role. */
val FaceRole.face: Face
    get() = Face.entries[ordinal]

/**
 * One font fallback chain for everything Roboco draws: the bundled Geist
 * (or GeistMono) face first, then Noto Sans CJK at the role's weight (the
 * variant picked by device locale, [CjkFallback]), then the platform's own
 * `sans-serif` fallback. The transcript measurer ([AndroidMeasurer]), the
 * transcript painter (ticket 62) and the Compose UI (ticket 03's
 * `RobocoFonts`) all resolve through these same [Typeface]s, so wrapping,
 * heights and drawing agree.
 *
 * [build] is the only writer of [typeface]'s backing state; [Fonts.load]
 * is its only caller, so the chain is built once per process off the main
 * thread. In monospace faces an East Asian wide character measures (and,
 * from ticket 63, draws) as exactly two cells: [isDoubleWidth] is the one
 * predicate both sides use.
 */
object FontChain {

    private const val TAG = "Roboco"

    /**
     * The shared text-[Paint] recipe (measurer **and** ticket 62's
     * painters): linear, unhinted advances so they scale exactly like
     * rustybuzz's, which is what makes `size` (pt) usable as `textSize`.
     */
    const val TEXT_PAINT_FLAGS: Int = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG

    /** The ligature settings Rust disables (`crates/text/src/font.rs:281-286`). */
    const val LIGATURES_OFF = "'liga' 0, 'clig' 0"

    @Volatile
    private var built: Map<FaceRole, Typeface>? = null

    /** Which CJK file the chain found (`"<file>#<ttc index>"`), for diagnostics and the lab. */
    @Volatile
    var cjkSource: String? = null
        private set

    private val cjkLock = Any()
    private val cjkFamilies = HashMap<Int, FontFamily?>()
    @Volatile
    private var cjk: CjkCandidate? = null

    /**
     * Builds one chain per [Face], from the exact bytes Rust measures.
     * The 12 Geist faces fail loudly ([FontLoadException]); the CJK
     * fallback is optional and only logs when it cannot be built.
     *
     * @param fontsByFace each [Face] with its validated asset bytes
     * @return an [EnumMap] with one entry per given face, in role order
     */
    fun build(fontsByFace: List<Pair<Face, ByteArray>>): Map<FaceRole, Typeface> {
        val candidate = findCjk()
        cjk = candidate
        cjkSource = candidate?.let { "${it.fileName}#${it.ttcIndex}" }
        candidate?.let { Log.i(TAG, "CJK fallback: $cjkSource variable=${it.variable}") }
        val out = EnumMap<FaceRole, Typeface>(FaceRole::class.java)
        for ((face, bytes) in fontsByFace) {
            out[face.role] = buildOne(face, bytes)
        }
        built = out
        return out
    }

    private fun buildOne(face: Face, bytes: ByteArray): Typeface {
        val weight = face.weight.weight
        val slant = if (face.style == ComposeFontStyle.Italic) {
            PlatformFontStyle.FONT_SLANT_ITALIC
        } else {
            PlatformFontStyle.FONT_SLANT_UPRIGHT
        }
        // The same bytes Rust measures: Font.Builder(ByteBuffer) needs a
        // direct buffer, so the array is copied once here.
        val primary = try {
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.rewind()
            Font.Builder(buffer).setWeight(weight).setSlant(slant).build()
        } catch (failure: Throwable) {
            throw FontLoadException(face.assetPath, "Unreadable font asset: ${face.assetPath}", failure)
        }
        val builder = Typeface.CustomFallbackBuilder(FontFamily.Builder(primary).build())
        cjkFamily(weight)?.let { builder.addCustomFallback(it) }
        builder.setSystemFallback("sans-serif")
        builder.setStyle(PlatformFontStyle(weight, slant))
        return builder.build()
    }

    /**
     * Enumerates the system fonts once and picks the CJK face for the
     * device locale. Only this enumeration is tolerant (the CJK fallback
     * is optional); the 12 Geist faces above stay fail-loud.
     */
    private fun findCjk(): CjkCandidate? {
        val candidates = runCatching {
            SystemFonts.getAvailableFonts().mapNotNull { font ->
                val file = font.file ?: return@mapNotNull null
                CjkCandidate(
                    fileName = file.name,
                    path = file.path,
                    ttcIndex = font.ttcIndex,
                    locales = font.localeList?.let { list -> List(list.size()) { list[it] } } ?: emptyList(),
                    variable = font.axes?.any { it.tag == "wght" } == true ||
                        file.name.contains("VF", ignoreCase = true) ||
                        file.name == FIXED_CJK_FILE,
                )
            }
        }.getOrDefault(emptyList())
        val locale = runCatching { LocaleList.getDefault()[0] }.getOrNull() ?: Locale.getDefault()
        val fixedFileExists = runCatching { File(FIXED_CJK_PATH).exists() }.getOrDefault(false)
        return pickCjk(candidates, locale, fixedFileExists)
    }

    /**
     * One CJK [FontFamily] per weight, cached. Not fatal: a build failure
     * logs and the chain simply has no CJK fallback (the system fallback
     * still covers those glyphs). An HK pick whose ttc index 4 face is
     * missing on this image retries TC (3), then SC (2), and records the
     * index that worked in [cjkSource].
     */
    private fun cjkFamily(weight: Int): FontFamily? = synchronized(cjkLock) {
        if (cjkFamilies.containsKey(weight)) {
            cjkFamilies[weight]
        } else {
            buildCjkFamily(weight).also { cjkFamilies[weight] = it }
        }
    }

    private fun buildCjkFamily(weight: Int): FontFamily? {
        val candidate = cjk ?: return null
        val indices = if (candidate.ttcIndex == 4) listOf(4, 3, 2) else listOf(candidate.ttcIndex)
        for (index in indices) {
            try {
                val b = Font.Builder(File(candidate.path))
                    .setTtcIndex(index)
                    .setWeight(weight)
                    .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
                if (candidate.variable) b.setFontVariationSettings("'wght' $weight")
                val family = FontFamily.Builder(b.build()).build()
                if (index != candidate.ttcIndex) {
                    cjkSource = "${candidate.fileName}#$index"
                }
                return family
            } catch (failure: Throwable) {
                Log.w(TAG, "CJK fallback unavailable: ${candidate.fileName}#$index at weight $weight", failure)
            }
        }
        return null
    }

    fun isMono(role: FaceRole): Boolean = role.face.mono

    /**
     * East Asian Wide / Fullwidth code points (UAX #11, upstream PR #621's
     * table verbatim) plus the wide emoji ranges. Not full UAX #11 —
     * transport emoji (0x1F680+) and regional indicators are *not* wide,
     * and both measure and paint (ticket 63) agree either way.
     */
    fun isWide(cp: Int): Boolean = when {
        cp < 0x1100 -> false
        cp <= 0x115F -> true
        cp in 0x2E80..0x303E -> true
        cp in 0x3041..0x33FF -> true
        cp in 0x3400..0x4DBF -> true
        cp in 0x4E00..0x9FFF -> true
        cp in 0xA000..0xA4CF -> true
        cp in 0xA960..0xA97F -> true
        cp in 0xAC00..0xD7A3 -> true
        cp in 0xF900..0xFAFF -> true
        cp in 0xFE10..0xFE19 -> true
        cp in 0xFE30..0xFE6F -> true
        cp in 0xFF00..0xFF60 -> true
        cp in 0xFFE0..0xFFE6 -> true
        cp in 0x1F300..0x1F64F -> true
        cp in 0x1F900..0x1F9FF -> true
        cp in 0x20000..0x3FFFD -> true
        else -> false
    }

    fun hasWide(text: CharSequence, start: Int = 0, end: Int = text.length): Boolean {
        var i = start
        while (i < end) {
            val cp = Character.codePointAt(text, i)
            if (isWide(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * [isWide] minus the two code points Geist Mono covers at a 1-cell
     * advance inside the wide ranges (U+3003 `〃`, U+301C `〜`), so mono
     * code blocks and tables stay column-aligned. The single predicate
     * for "2 cells": this measurer and ticket 63's painter.
     */
    fun isDoubleWidth(cp: Int): Boolean = isWide(cp) && cp != 0x3003 && cp != 0x301C

    /** One monospace cell at the paint's current size (GeistMono's `0` advance = 0.6 × size). */
    fun cellWidth(paint: Paint): Float = advance(paint, "0", 0, 1)

    /**
     * The exact (fractional) advance of `text[start, end)` — what
     * Rust and CoreText measure. Never `Paint.measureText`: it returns
     * `Math.ceil` of the advance (AOSP `Paint.measureText`), so a 13.5 pt
     * Geist Mono cell would read 9 instead of 8.1 and every
     * measured/painted stretch would drift off its Rust position.
     */
    fun advance(paint: Paint, text: CharSequence, start: Int, end: Int, rtl: Boolean = false): Float =
        if (start >= end) 0f else paint.getRunAdvance(text, start, end, start, end, rtl, end)

    /**
     * `null` for ligatures on (platform defaults: liga/clig/calt/kern on),
     * Rust's exact disabled set otherwise. Assign unconditionally: the
     * paints are reused, and a mono call must not leak `'liga' 0` into the
     * next sans call.
     */
    fun featureSettings(ligatures: Boolean): String? = if (ligatures) null else LIGATURES_OFF

    /**
     * The chain typeface for a role. Throws before [build] ran — no
     * silent default to the Sans face.
     */
    fun typeface(role: FaceRole): Typeface =
        (built ?: error("FontChain not built: call Fonts.load(context) first")).getValue(role)

    /**
     * Configures a shared text paint: chain typeface, size (pt for the
     * measurer, `size × density` px for ticket 62's painters — linear text
     * makes the two agree), ligature settings, zero letter spacing (Rust
     * adds its own). `textLocales` stays at its default: the CJK font
     * comes from this chain, not from locale-driven system fallback.
     */
    fun applyTo(
        paint: Paint,
        role: FaceRole,
        textSize: Float,
        ligatures: Boolean,
        typeface: Typeface = typeface(role),
    ) {
        paint.typeface = typeface
        paint.textSize = textSize
        paint.fontFeatureSettings = featureSettings(ligatures)
        paint.letterSpacing = 0f
    }

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(cjkLock) {
            built = null
            cjk = null
            cjkSource = null
            cjkFamilies.clear()
        }
    }
}
