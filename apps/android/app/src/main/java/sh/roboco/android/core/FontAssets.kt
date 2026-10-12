package sh.roboco.android.core

/**
 * The 12 bundled font faces, derived from [Face] (the same subset #609's
 * `Fonts.kt` maps to `FaceRole`s, byte-identical in
 * `crates/ui/assets/fonts/` and the iOS bundle): Rust measures the exact
 * bytes Kotlin paints with. Roboco's repo carries 16 faces; the other four
 * (GeistMono Bold/MediumItalic/SemiBoldItalic/BoldItalic) are not shipped.
 *
 * Runtime fail-loud checking lives in [FontGate] (ticket 03); the Gradle
 * sync task and the generated-assets test read this list — keep it in
 * lockstep with buildSrc's `RobocoFonts` when a face is ever added.
 */
object FontAssets {

    val FILES: List<String> = Face.entries.map { it.file }
}
