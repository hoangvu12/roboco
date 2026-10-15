package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.core.GeistMetrics
import java.io.File

/**
 * The project tile's math and letter rules (android-ui-parity #12), from
 * `ios:Design/ProjectTile.swift:1-33` and the desktop tile
 * (`crates/ui/src/shell/project_icon.rs`).
 *
 * Runs under Robolectric: [ProjectTiles.letter] segments grapheme clusters
 * with `android.icu.text.BreakIterator`, which the mockable android.jar
 * cannot serve on a plain JVM. `metrics` and the font pin are pure; they
 * live here because this is the ticket's named test class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectTileTest {

    // ------------------------------------------------------------- letter

    @Test
    fun letterTrimsSwiftWhitespacesAndUppercases() {
        assertEquals("R", ProjectTiles.letter("roboco"))
        assertEquals("Z", ProjectTiles.letter("  zeron "))
        assertEquals("B", ProjectTiles.letter("\tblog"))
        assertEquals("E", ProjectTiles.letter(" edge"))
        assertEquals("E", ProjectTiles.letter("\u00A0edge")) // NBSP is Zs: trimmed
        assertEquals("H", ProjectTiles.letter("Home"))
    }

    @Test
    fun emptyOrAllWhitespaceNamesFallBackToTheQuestionMark() {
        assertEquals("?", ProjectTiles.letter(""))
        assertEquals("?", ProjectTiles.letter("   "))
        assertEquals("?", ProjectTiles.letter("\t \u00A0"))
    }

    @Test
    fun letterKeepsOneGraphemeClusterWhole() {
        // Precomposed é (U+00E9) uppercases to É (U+00C9).
        assertEquals("\u00C9", ProjectTiles.letter("\u00E9clair"))
        // Decomposed e + combining acute (U+0301): one cluster, two chars,
        // kept whole — only the base letter uppercases.
        assertEquals("E\u0301", ProjectTiles.letter("e\u0301x"))
        assertEquals(2, ProjectTiles.letter("e\u0301x").length)
        // Thumbs-up + Fitzpatrick modifier: one cluster.
        assertEquals("\uD83D\uDC4D\uD83C\uDFFD", ProjectTiles.letter("\uD83D\uDC4D\uD83C\uDFFDx"))
    }

    @Test
    fun letterUppercasesLocaleIndependently() {
        // Swift's String.uppercased() is locale-independent: ß → SS.
        assertEquals("SS", ProjectTiles.letter("\u00DFeta"))
    }

    @Test
    fun newlineIsNotWhitespaceForSwiftParity() {
        // Swift .whitespaces is Zs + TAB — a newline is not trimmed, so the
        // first cluster is the newline itself.
        assertEquals("\n", ProjectTiles.letter("\nzeron"))
    }

    // ------------------------------------------------------------ metrics

    @Test
    fun metricsMatchTheTicketTable() {
        val m14 = ProjectTiles.metrics(14f)
        assertEquals(3.2308f, m14.radius, 0.001f)
        assertEquals(9.6923f, m14.fontSize, 0.001f)
        assertEquals(10.4408f, m14.baseline, 0.001f)

        val m20 = ProjectTiles.metrics(20f)
        assertEquals(4.6154f, m20.radius, 0.001f)
        assertEquals(13.8462f, m20.fontSize, 0.001f)
        assertEquals(14.9154f, m20.baseline, 0.001f)
    }

    @Test
    fun metricsScaleWithTheSide() {
        // k = side / 13: radius 3k, font 9k, baseline side/2 + 0.71·f/2 —
        // the cap height is centred, the ascender cancels.
        for (side in listOf(13f, 14f, 18f, 20f)) {
            val m = ProjectTiles.metrics(side)
            assertEquals("radius side=$side", 3f * side / 13f, m.radius, 1e-4f)
            assertEquals("fontSize side=$side", 9f * side / 13f, m.fontSize, 1e-4f)
            assertEquals(
                "baseline side=$side",
                side / 2f + GeistMetrics.CAP_HEIGHT * (9f * side / 13f) / 2f,
                m.baseline,
                1e-4f,
            )
        }
    }

    // -------------------------------------------------- the font-byte pin

    /**
     * Reads `crates/ui/assets/fonts/GeistMono-Medium.ttf` (byte-identical
     * to the APK asset and to `ios:Fonts/`) and pins
     * [GeistMetrics.CAP_HEIGHT] to the OS/2 table's `sCapHeight`: the
     * tile's vertical centring is a font-metric property, and Android's
     * `Paint.FontMetrics` exposes no cap height to read at runtime.
     *
     * Big-endian table directory: 12-byte offset table, 16-byte records
     * (tag, checksum, offset, length); `head`+18 → unitsPerEm,
     * `OS/2`+88 → sCapHeight (version ≥ 2).
     */
    @Test
    fun capHeightIsPinnedToTheGeistMonoMediumAsset() {
        val ttf = File(TestRepoRoot.root(), "crates/ui/assets/fonts/GeistMono-Medium.ttf").readBytes()
        assertEquals("TrueType magic", 0x00010000, ((u16(ttf, 0).toInt() shl 16) or u16(ttf, 2).toInt()))

        var os2 = -1
        var head = -1
        val numTables = u16(ttf, 4)
        for (i in 0 until numTables) {
            val record = 12 + 16 * i
            when (String(ttf, record, 4, Charsets.US_ASCII)) {
                "OS/2" -> os2 = u32(ttf, record + 8).toInt()
                "head" -> head = u32(ttf, record + 8).toInt()
            }
        }
        assertTrue("OS/2 table not found", os2 >= 0)
        assertTrue("head table not found", head >= 0)

        val unitsPerEm = u16(ttf, head + 18)
        val sCapHeight = u16(ttf, os2 + 88)
        assertEquals(1000, unitsPerEm)
        assertEquals(710, sCapHeight)
        assertEquals(GeistMetrics.CAP_HEIGHT, sCapHeight.toFloat() / unitsPerEm, 1e-6f)
    }

    private fun u16(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, offset: Int): Long =
        ((b[offset].toLong() and 0xFF) shl 24) or ((b[offset + 1].toLong() and 0xFF) shl 16) or
            ((b[offset + 2].toLong() and 0xFF) shl 8) or (b[offset + 3].toLong() and 0xFF)
}
