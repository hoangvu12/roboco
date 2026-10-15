package roboco.gradle

/**
 * The 12 bundled Geist faces the app ships (android-app#13): the same
 * subset #609's `Fonts.kt` maps to `FaceRole`s, in the same order.
 * `core/FontAssets.kt` mirrors this list for runtime checks — keep the two
 * in lockstep when a face is ever added.
 */
object RobocoFonts {
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
}
