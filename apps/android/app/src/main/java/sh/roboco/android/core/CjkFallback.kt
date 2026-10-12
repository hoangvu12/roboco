package sh.roboco.android.core

import android.icu.util.ULocale
import java.util.Locale

/**
 * Which Noto Sans CJK OpenType Collection face the [FontChain] falls back
 * to. The OTC face order is JP, KR, SC, TC, HK (`ttcIndex` 0–4), matching
 * `fonts.xml`'s `NotoSansCJK-Regular.ttc` entries.
 *
 * iOS has no equivalent: `CTLine` picks its own fallback, which on a
 * Japanese device shows Chinese glyph forms for shared code points. Roboco
 * picks the variant from the **device locale** (upstream Android PR #621
 * always used Simplified Chinese), read once when the chain is first built
 * — a live locale change would need a full chain and width-cache rebuild,
 * so it takes effect on the next process start.
 */
enum class CjkVariant(val ttcIndex: Int) {
    JP(0),
    KR(1),
    SC(2),
    TC(3),
    HK(4),
}

/**
 * The CJK variant for a device locale: `ja`→JP, `ko`→KR, `zh` + region
 * HK/MO→HK, `zh` + script Hant→TC, and every other language (including
 * bare `zh`)→SC — the default everywhere, matching the system's own
 * `zh-Hans` declaration for `sans-serif`.
 */
fun cjkVariantFor(locale: Locale): CjkVariant {
    val u = ULocale.addLikelySubtags(ULocale.forLocale(locale))
    return when {
        u.language == "ja" -> CjkVariant.JP
        u.language == "ko" -> CjkVariant.KR
        u.language == "zh" && (u.country == "HK" || u.country == "MO") -> CjkVariant.HK
        u.language == "zh" && u.script == "Hant" -> CjkVariant.TC
        else -> CjkVariant.SC
    }
}

/**
 * One candidate CJK font from [android.graphics.fonts.SystemFonts].
 * Pure data so [pickCjk] stays unit-testable without a device.
 */
data class CjkCandidate(
    val fileName: String,
    val path: String,
    val ttcIndex: Int,
    val locales: List<Locale>,
    val variable: Boolean,
)

/**
 * Picks the Noto Sans CJK face to chain behind the bundled Geist faces.
 *
 * Steps:
 * 1. keep only candidates whose file name contains `CJK` (not `Serif`);
 * 2. the first candidate with a locale matching the variant (HK retries
 *    as TC — `zh-Hant-HK` is rare in `fonts.xml`);
 * 3. else the first `NotoSansCJK*` candidate at the variant's ttc index;
 * 4. else the first candidate;
 * 5. else the fixed `/system/fonts/NotoSansCJK-Regular.ttc` at the
 *    variant index (variable = true) — the 4→3→2 retry for HK lives in
 *    `FontChain.cjkFamily`, which can actually open the file;
 * 6. else null: no CJK fallback, the system fallback still covers it.
 *
 * Pure and JVM-testable given a locale list; `ULocale` is available under
 * Robolectric.
 */
fun pickCjk(candidates: List<CjkCandidate>, locale: Locale, fixedFileExists: Boolean): CjkCandidate? {
    val cjk = candidates.filter {
        it.fileName.contains("CJK", ignoreCase = true) && !it.fileName.contains("Serif", ignoreCase = true)
    }
    val variant = cjkVariantFor(locale)
    for (pattern in if (variant == CjkVariant.HK) listOf(CjkVariant.HK, CjkVariant.TC) else listOf(variant)) {
        val match = cjk.firstOrNull { candidate ->
            candidate.locales.any { matchesVariant(ULocale.forLocale(it), pattern) }
        }
        if (match != null) return match
    }
    cjk.firstOrNull { it.fileName.startsWith("NotoSansCJK") && it.ttcIndex == variant.ttcIndex }
        ?.let { return it }
    cjk.firstOrNull()?.let { return it }
    if (fixedFileExists) {
        return CjkCandidate(
            fileName = FIXED_CJK_FILE,
            path = FIXED_CJK_PATH,
            ttcIndex = variant.ttcIndex,
            locales = emptyList(),
            variable = true,
        )
    }
    return null
}

private fun matchesVariant(locale: ULocale, pattern: CjkVariant): Boolean {
    val u = ULocale.addLikelySubtags(locale)
    return when (pattern) {
        CjkVariant.JP -> u.language == "ja"
        CjkVariant.KR -> u.language == "ko"
        CjkVariant.SC -> u.language == "zh" && u.script == "Hans"
        CjkVariant.TC -> u.language == "zh" && (u.script == "Hant" || u.script == "Bopo")
        CjkVariant.HK -> u.language == "zh" && u.script == "Hant" && (u.country == "HK" || u.country == "MO")
    }
}

/** The last-resort CJK file every Android image ships. */
internal const val FIXED_CJK_FILE = "NotoSansCJK-Regular.ttc"

internal const val FIXED_CJK_PATH = "/system/fonts/$FIXED_CJK_FILE"
