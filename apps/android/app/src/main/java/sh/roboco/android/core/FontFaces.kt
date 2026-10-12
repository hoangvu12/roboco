package sh.roboco.android.core

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The 12 bundled Geist faces, one enum entry per `Fonts.ui(face, size)` role
 * (`ios:Core/Fonts.swift:7-20`).
 *
 * **The declaration order is API:** it equals `uniffi.roboco_core.FaceRole`
 * (`mob:layout/style.rs:11-24`) — Sans, SansMedium, SansSemibold, SansBold,
 * SansItalic, SansMediumItalic, SansSemiboldItalic, SansBoldItalic, Mono,
 * MonoMedium, MonoSemibold, MonoItalic. Ticket 60 (transcript-text-engine)
 * maps `FaceRole` to `Face` by ordinal and asserts the names, so never
 * reorder or insert.
 *
 * All 12 files are `.ttf` and byte-identical to `crates/ui/assets/fonts/`
 * (the bytes the Rust layout engine measures); `android-app#13` copies them
 * into the APK at `assets/fonts/<file>.ttf`. Roboco's repo also carries
 * GeistMono Bold/MediumItalic/SemiBoldItalic/BoldItalic — they are not
 * shipped and nothing uses them.
 *
 * Requests outside the shipped Mono set resolve like Rust's
 * `FaceRole::of` (`mob:layout/style.rs:42-58`): a Mono 700 request falls
 * back to MonoSemibold (600, the closest), any Mono italic to MonoItalic
 * (the only italic) — Compose's font matcher does this by itself once
 * synthesis is off (`FontSynthesis.None` in `RobocoTextDefaults.Base`).
 */
enum class Face(
    /** The file name under `assets/fonts/`. */
    val file: String,
    val weight: FontWeight,
    val style: FontStyle,
    /** True for the GeistMono family (the `RobocoFontFamilies.geistMono`). */
    val mono: Boolean,
) {
    Sans("Geist.ttf", FontWeight.Normal, FontStyle.Normal, false),
    SansMedium("Geist-Medium.ttf", FontWeight.Medium, FontStyle.Normal, false),
    SansSemibold("Geist-SemiBold.ttf", FontWeight.SemiBold, FontStyle.Normal, false),
    SansBold("Geist-Bold.ttf", FontWeight.Bold, FontStyle.Normal, false),
    SansItalic("Geist-Italic.ttf", FontWeight.Normal, FontStyle.Italic, false),
    SansMediumItalic("Geist-MediumItalic.ttf", FontWeight.Medium, FontStyle.Italic, false),
    SansSemiboldItalic("Geist-SemiBoldItalic.ttf", FontWeight.SemiBold, FontStyle.Italic, false),
    SansBoldItalic("Geist-BoldItalic.ttf", FontWeight.Bold, FontStyle.Italic, false),
    Mono("GeistMono.ttf", FontWeight.Normal, FontStyle.Normal, true),
    MonoMedium("GeistMono-Medium.ttf", FontWeight.Medium, FontStyle.Normal, true),
    MonoSemibold("GeistMono-SemiBold.ttf", FontWeight.SemiBold, FontStyle.Normal, true),
    MonoItalic("GeistMono-Italic.ttf", FontWeight.Normal, FontStyle.Italic, true),
}

/** The path of [Face]'s file inside the APK assets: `fonts/<file>`. */
val Face.assetPath: String get() = "fonts/$file"

/**
 * Per-em metrics of the bundled faces, read with fontTools (units/1000 em;
 * hhea and OS/2 typo are equal, USE_TYPO_METRICS is set).
 *
 * `SessionCell.swift:171-173` uses `titleFont.lineHeight`, `.ascender` and
 * `.xHeight`; `ProjectTile.swift:27` uses `font.ascender` and
 * `font.capHeight` — the centring consumers (tickets 37/39) port those with
 * these constants. `usWinAscent`/`usWinDescent` (1088/262 sans, 1088/394
 * mono) are what `includeFontPadding = true` would add; `UILabel` has no
 * font padding, so neither does `RobocoTextDefaults.Base`.
 */
object GeistMetrics {
    /** × size (hhea ascent, 1005/1000). */
    const val ASCENT = 1.005f

    /** × size, positive (hhea descent, −295/1000). */
    const val DESCENT = 0.295f

    /** × size (ascent − descent, lineGap 0). */
    const val LINE_HEIGHT = 1.3f

    /** × size (710/1000, same in every face). */
    const val CAP_HEIGHT = 0.710f

    /** × size: Regular/Italic 530, Medium 532, SemiBold 534, Bold 536. */
    fun xHeight(weight: FontWeight): Float = when (weight.weight) {
        in 0..449 -> 0.530f
        in 450..549 -> 0.532f
        in 550..649 -> 0.534f
        else -> 0.536f
    }
}
