package sh.roboco.android.core

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `Face` table (plain JVM, for the merge check): exactly 12 entries in
 * `uniffi.roboco_core.FaceRole` order (`mob:layout/style.rs:11-24`), with the
 * file, weight, style and family of each, and the fontTools metrics the
 * centring consumers (tickets 37/39) read from [GeistMetrics].
 */
class FontFacesTest {

    /** iOS `Fonts.swift:7-20` and the Rust `FaceRole`, verbatim order. */
    private val faceRoleOrder = listOf(
        "Sans", "SansMedium", "SansSemibold", "SansBold",
        "SansItalic", "SansMediumItalic", "SansSemiboldItalic", "SansBoldItalic",
        "Mono", "MonoMedium", "MonoSemibold", "MonoItalic",
    )

    @Test
    fun twelveFacesInFaceRoleOrder() {
        // Ticket 60 maps FaceRole -> Face by ordinal and asserts the names,
        // so the order here is API.
        assertEquals(faceRoleOrder, Face.entries.map { it.name })
        assertEquals(12, Face.entries.size)
    }

    @Test
    fun filesMatchTheBundledAssets() {
        val files = mapOf(
            "Sans" to "Geist.ttf",
            "SansMedium" to "Geist-Medium.ttf",
            "SansSemibold" to "Geist-SemiBold.ttf",
            "SansBold" to "Geist-Bold.ttf",
            "SansItalic" to "Geist-Italic.ttf",
            "SansMediumItalic" to "Geist-MediumItalic.ttf",
            "SansSemiboldItalic" to "Geist-SemiBoldItalic.ttf",
            "SansBoldItalic" to "Geist-BoldItalic.ttf",
            "Mono" to "GeistMono.ttf",
            "MonoMedium" to "GeistMono-Medium.ttf",
            "MonoSemibold" to "GeistMono-SemiBold.ttf",
            "MonoItalic" to "GeistMono-Italic.ttf",
        )
        for (name in faceRoleOrder) {
            assertEquals("$name file", files[name], Face.valueOf(name).file)
        }
    }

    @Test
    fun weightsAndStylesCoverTheShippedMatrix() {
        // Geist: 400/500/600/700 × normal/italic (8). GeistMono: 400/500/600
        // normal + 400 italic (4) — #609 omitted four of these.
        val expected = mapOf(
            "Sans" to (400 to FontStyle.Normal),
            "SansMedium" to (500 to FontStyle.Normal),
            "SansSemibold" to (600 to FontStyle.Normal),
            "SansBold" to (700 to FontStyle.Normal),
            "SansItalic" to (400 to FontStyle.Italic),
            "SansMediumItalic" to (500 to FontStyle.Italic),
            "SansSemiboldItalic" to (600 to FontStyle.Italic),
            "SansBoldItalic" to (700 to FontStyle.Italic),
            "Mono" to (400 to FontStyle.Normal),
            "MonoMedium" to (500 to FontStyle.Normal),
            "MonoSemibold" to (600 to FontStyle.Normal),
            "MonoItalic" to (400 to FontStyle.Italic),
        )
        for ((name, matrix) in expected) {
            val face = Face.valueOf(name)
            assertEquals("$name weight", matrix.first, face.weight.weight)
            assertEquals("$name style", matrix.second, face.style)
        }
    }

    @Test
    fun monoFlagSplitsTheFamilies() {
        assertEquals(
            listOf("Sans", "SansMedium", "SansSemibold", "SansBold",
                "SansItalic", "SansMediumItalic", "SansSemiboldItalic", "SansBoldItalic"),
            Face.entries.filter { !it.mono }.map { it.name },
        )
        assertEquals(
            listOf("Mono", "MonoMedium", "MonoSemibold", "MonoItalic"),
            Face.entries.filter { it.mono }.map { it.name },
        )
    }

    @Test
    fun assetPathsLiveUnderFonts() {
        for (face in Face.entries) {
            assertEquals("${face.name} asset path", "fonts/${face.file}", face.assetPath)
        }
    }

    @Test
    fun fontAssetsFilesDeriveFromTheFaceTable() {
        // android-app#13's generated-assets test iterates this list; it must
        // stay in lockstep with `Face` (and buildSrc's RobocoFonts).
        assertEquals(
            listOf(
                "Geist.ttf", "Geist-Medium.ttf", "Geist-SemiBold.ttf", "Geist-Bold.ttf",
                "Geist-Italic.ttf", "Geist-MediumItalic.ttf", "Geist-SemiBoldItalic.ttf",
                "Geist-BoldItalic.ttf", "GeistMono.ttf", "GeistMono-Medium.ttf",
                "GeistMono-SemiBold.ttf", "GeistMono-Italic.ttf",
            ),
            FontAssets.FILES,
        )
        assertEquals(Face.entries.map { it.file }, FontAssets.FILES)
    }

    @Test
    fun geistMetricsMatchFontTools() {
        // fontTools on crates/ui/assets/fonts/*.ttf: hhea == OS/2 typo,
        // usWin metrics differ (Mono descent 394) — see the ticket table.
        assertEquals(1.005f, GeistMetrics.ASCENT)
        assertEquals(0.295f, GeistMetrics.DESCENT)
        assertEquals(1.3f, GeistMetrics.LINE_HEIGHT)
        assertEquals(0.710f, GeistMetrics.CAP_HEIGHT)
        assertEquals(0.530f, GeistMetrics.xHeight(FontWeight.Normal))
        assertEquals(0.530f, GeistMetrics.xHeight(FontWeight(400)))
        assertEquals(0.532f, GeistMetrics.xHeight(FontWeight.Medium))
        assertEquals(0.534f, GeistMetrics.xHeight(FontWeight.SemiBold))
        assertEquals(0.536f, GeistMetrics.xHeight(FontWeight.Bold))
    }

    @Test
    fun xHeightBucketsCoverEveryShippedWeight() {
        for (face in Face.entries) {
            val x = GeistMetrics.xHeight(face.weight)
            org.junit.Assert.assertTrue("${face.name} x-height in 0.53..0.536", x in 0.530f..0.536f)
        }
    }
}
