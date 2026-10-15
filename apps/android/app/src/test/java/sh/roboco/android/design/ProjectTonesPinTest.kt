package sh.roboco.android.design

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the project tones against the desktop's monogram palette
 * (`crates/ui/src/shell/project_icon.rs`, `MONOGRAM_PALETTE` at :60-69):
 * the Android palette must list the same 8 colours in the same order.
 *
 * The Rust tuples are **(dark, light)** — the reverse of the iOS argument
 * order this port's tables use.
 */
class ProjectTonesPinTest {

    private fun monogramPaletteTuples(): List<Pair<Int, Int>> {
        val source = File(TestRepoRoot.root(), "crates/ui/src/shell/project_icon.rs").readText()
        val start = source.indexOf("MONOGRAM_PALETTE")
        assertTrue("MONOGRAM_PALETTE not found in project_icon.rs", start >= 0)
        val end = source.indexOf("];", start)
        assertTrue("MONOGRAM_PALETTE's closing bracket not found", end > start)
        val body = source.substring(start, end)
        val pattern = Regex("\\(0x([0-9a-fA-F]{6}),\\s*0x([0-9a-fA-F]{6})\\)")
        return pattern.findAll(body).map { it.groupValues[1].toInt(16) to it.groupValues[2].toInt(16) }.toList()
    }

    @Test
    fun projectTonesMatchTheDesktopMonogramPalette() {
        val tuples = monogramPaletteTuples()
        assertEquals("expected exactly 8 monogram palette entries", 8, tuples.size)
        assertEquals(
            "the palette must size itself from the desktop table",
            8,
            Palette.PROJECT_TONE_COUNT,
        )
        for (i in tuples.indices) {
            val (dark, light) = tuples[i]
            assertEquals(
                "dark component of monogram palette entry $i",
                dark,
                Palette.Dark.projectTones[i].toArgb() and 0xFFFFFF,
            )
            assertEquals(
                "light component of monogram palette entry $i",
                light,
                Palette.Light.projectTones[i].toArgb() and 0xFFFFFF,
            )
        }
    }
}
