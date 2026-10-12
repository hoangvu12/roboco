package sh.roboco.android.design

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.util.concurrent.atomic.AtomicInteger
import sh.roboco.android.BuildConfig
import sh.roboco.android.core.Face
import sh.roboco.android.core.FontGate
import sh.roboco.android.core.FontLoadException
import sh.roboco.android.core.Fonts
import sh.roboco.android.core.role

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
 * Builds the [RobocoFontFamilies] from the shared font chain (ticket 60):
 * each entry is a [ChainFont] that resolves through [ChainLoader] to the
 * `FontChain` typeface for its role — the same chain the Rust layout
 * engine measures with and the transcript painter draws with, so UI
 * labels and transcript text share one fallback chain (Geist → Noto Sans
 * CJK at the role weight → system `sans-serif`).
 *
 * This is the Compose-side port of `CTFontManagerRegisterGraphicsFont`
 * (`Fonts.swift:22-36`): one source of truth for measurement and drawing.
 */
object RobocoFonts {

    @Volatile
    private var cached: RobocoFontFamilies? = null

    /** Test hook: how many typefaces [ChainLoader] has resolved so far. */
    internal val chainLoads = AtomicInteger()

    /**
     * Process-wide, built once. Constructing a [ChainFont] resolves
     * nothing (Compose loads the typeface blocking on first use), so this
     * is cheap and safe on the main thread. In `@Preview`/layoutlib the
     * `applicationContext` may be the preview context — the merged assets
     * are there too.
     */
    fun families(context: Context): RobocoFontFamilies {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build().also { cached = it }
        }
    }

    private fun build(): RobocoFontFamilies = RobocoFontFamilies(
        geist = FontFamily(Face.entries.filter { !it.mono }.map { ChainFont(it) }),
        geistMono = FontFamily(Face.entries.filter { it.mono }.map { ChainFont(it) }),
    )

    @VisibleForTesting
    internal fun resetForTest() {
        cached = null
        chainLoads.set(0)
    }
}

/**
 * One [Face] as a Compose font over the shared chain. `Blocking` is
 * required: `Async`/`OptionalLocal` would draw a system font first — a
 * visible flash. With `FontSynthesis.None` (in `RobocoTextDefaults.Base`)
 * Compose returns the chain typeface untouched, so a Mono 700 request
 * matches `MonoSemibold` and any Mono italic `MonoItalic`, exactly like
 * Rust's `FaceRole::of`.
 */
private class ChainFont(val face: Face) :
    AndroidFont(FontLoadingStrategy.Blocking, ChainLoader, FontVariation.Settings()) {

    override val weight: FontWeight get() = face.weight
    override val style: FontStyle get() = face.style

    override fun equals(other: Any?): Boolean = other is ChainFont && other.face == face
    override fun hashCode(): Int = face.hashCode()
    override fun toString(): String = "ChainFont($face)"
}

private object ChainLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
        RobocoFonts.chainLoads.incrementAndGet()
        return loadChainTypeface(context, (font as ChainFont).face, BuildConfig.DEBUG)
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
        loadBlocking(context, font)
}

/**
 * Resolves a [Face]'s chain typeface for the Compose loader — the
 * §7 failure row for the main thread: in release a [FontLoadException]
 * marks the gate `Missing` (the [FontGateHost] recomposes to the fatal
 * screen) and returns [Typeface.DEFAULT] for this one frame; in debug it
 * rethrows, crashing with the path in logcat.
 */
internal fun loadChainTypeface(context: Context, face: Face, debug: Boolean): Typeface =
    try {
        Fonts.load(context).typefaces.getValue(face.role)
    } catch (failure: FontLoadException) {
        FontGate.fail(failure.path)
        if (debug) throw failure
        Typeface.DEFAULT
    }

/**
 * The resolved font families. No default on purpose: a component rendered
 * outside [RobocoTheme] fails at once instead of silently drawing Roboto
 * (the fonts being *the* thing this app's text must not fall back from).
 */
val LocalFonts = staticCompositionLocalOf<RobocoFontFamilies> {
    error("LocalFonts not provided: wrap content in RobocoTheme")
}
