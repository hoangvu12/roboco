package sh.roboco.android.core

import android.content.res.AssetManager
import java.io.IOException

/**
 * The bundled font faces. The 12 files are the subset #609's `Fonts.kt`
 * maps to `FaceRole`s (byte-identical in `crates/ui/assets/fonts/` and the
 * iOS bundle): Rust measures the exact bytes Kotlin paints with. Roboco's
 * repo carries 16 faces; the other four (GeistMono Bold/MediumItalic/
 * SemiBoldItalic/BoldItalic) are not shipped.
 *
 * Ticket 15 builds `Fonts`/the measurer on this list.
 */
object FontAssets {

    val FILES: List<String> = listOf(
        "Geist.ttf",
        "Geist-Medium.ttf",
        "Geist-SemiBold.ttf",
        "Geist-Bold.ttf",
        "Geist-Italic.ttf",
        "Geist-MediumItalic.ttf",
        "Geist-SemiBoldItalic.ttf",
        "Geist-BoldItalic.ttf",
        "GeistMono.ttf",
        "GeistMono-Medium.ttf",
        "GeistMono-SemiBold.ttf",
        "GeistMono-Italic.ttf",
    )

    /**
     * Opens every `fonts/<file>` and throws an [IllegalStateException]
     * naming the missing files — startup must fail, not limp (upstream
     * #609 swallowed them, review bug 8).
     */
    fun requireAll(assets: AssetManager) {
        val missing = mutableListOf<String>()
        for (file in FILES) {
            try {
                assets.open("fonts/$file").use { stream -> stream.read() }
            } catch (error: IOException) {
                missing += file
            }
        }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Roboco is missing required font files in assets/fonts: ${missing.joinToString()}. " +
                    "Rebuild the APK (the fonts are copied by the syncRobocoFonts Gradle task).",
            )
        }
    }
}
