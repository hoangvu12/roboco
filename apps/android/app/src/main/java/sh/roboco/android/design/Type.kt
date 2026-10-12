package sh.roboco.android.design

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import sh.roboco.android.core.Face
import sh.roboco.android.core.assetPath

/**
 * The two Compose families the app's UI text is set in: `Geist` (8 faces,
 * 400/500/600/700 × normal/italic) and `GeistMono` (4 faces, 400/500/600
 * normal + 400 italic). #609's `Theme.kt` omitted the Sans 500/600/700
 * italics and the Mono italic; this table ships all 12.
 */
@Immutable
class RobocoFontFamilies(val geist: FontFamily, val geistMono: FontFamily) {

    /** The family a [Face] belongs to. */
    fun family(face: Face): FontFamily = if (face.mono) geistMono else geist
}

/**
 * Builds the [RobocoFontFamilies] from the APK assets — CoreText's
 * `CTFontManagerRegisterGraphicsFont` (`Fonts.swift:22-36`) in Compose form:
 * [Font] with an `AssetManager`, one entry per [Face] with its exact weight
 * and slant, so Compose's matcher resolves every shipped role without
 * synthesis.
 *
 * Ticket 60 replaces the body with `FontChain`-backed typefaces behind this
 * same API — callers must not depend on how the families are built.
 */
object RobocoFonts {

    @Volatile
    private var cached: RobocoFontFamilies? = null

    /**
     * Process-wide, built once. Constructing a [Font] does not open the
     * asset (Compose loads it blocking on first use), so this is cheap and
     * safe on the main thread. In `@Preview`/layoutlib the
     * `applicationContext` may be the preview context — the merged assets
     * are there too.
     */
    fun families(context: Context): RobocoFontFamilies {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
        }
    }

    private fun build(context: Context): RobocoFontFamilies {
        val assets = context.assets

        fun assetFont(face: Face) = Font(face.assetPath, assets, face.weight, face.style)

        return RobocoFontFamilies(
            geist = FontFamily(Face.entries.filter { !it.mono }.map { assetFont(it) }),
            geistMono = FontFamily(Face.entries.filter { it.mono }.map { assetFont(it) }),
        )
    }
}

/**
 * The resolved font families. No default on purpose: a component rendered
 * outside [RobocoTheme] fails at once instead of silently drawing Roboto
 * (the fonts being *the* thing this app's text must not fall back from).
 */
val LocalFonts = staticCompositionLocalOf<RobocoFontFamilies> {
    error("LocalFonts not provided: wrap content in RobocoTheme")
}
