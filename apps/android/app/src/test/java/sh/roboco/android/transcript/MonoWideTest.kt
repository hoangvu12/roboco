package sh.roboco.android.transcript

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.core.FontChain

/**
 * [MonoWide] — the painter's half of ticket 60's double-width rule
 * (Robolectric for `android.icu`'s grapheme clusters; no graphics
 * needed, so no fonts are loaded). The cluster boundaries come from
 * [android.icu.text.BreakIterator.getCharacterInstance], the same
 * iterator the measurer uses, so plan and measurement always agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MonoWideTest {

    @Test
    fun isDoubleWidthDelegatesToFontChainMinusTheTwoCoveredCodePoints() {
        // East Asian wide code points…
        for (cp in intArrayOf(0x1100, 0x2E80, 0x3041, 0x4E00, 0x9FFF, 0xAC00, 0xFF01, 0x1F300, 0x1F600, 0x1F900, 0x20000)) {
            assertTrue("0x${cp.toString(16)} should be wide", FontChain.isWide(cp))
            assertTrue("0x${cp.toString(16)} should be double-width", MonoWide.isDoubleWidth(cp))
        }
        // …minus the pair Geist Mono covers at one cell (Rust shapes them
        // itself, so they never reach the measurer).
        for (cp in intArrayOf(0x3003, 0x301C)) {
            assertTrue("0x${cp.toString(16)} should be wide", FontChain.isWide(cp))
            assertFalse("0x${cp.toString(16)} is covered by Geist Mono: 1 cell, not 2", MonoWide.isDoubleWidth(cp))
        }
        // Outside the table: natural width in measurer and painter alike.
        for (cp in intArrayOf('a'.code, 0x10FF, 0x303F, 0x1F680, 0x2600, 0x3FFFE)) {
            assertFalse("0x${cp.toString(16)} should not be wide", FontChain.isWide(cp))
            assertFalse("0x${cp.toString(16)} should not be double-width", MonoWide.isDoubleWidth(cp))
        }
    }

    @Test
    fun planPacksAbsoluteUtf16PairsForWideClusters() {
        // a 名 b 😀(2 units) c — 名 is [1,2), 😀 is [3,5).
        val text = "a名b😀c"
        assertEquals(6, text.length)
        assertArrayEquals(intArrayOf(1, 2, 3, 5), MonoWide.plan(text, 0, text.length))
    }

    @Test
    fun anEmojiZwjSequenceIsOneWideCluster() {
        // U+1F469 + ZWJ + U+1F4BB: 5 UTF-16 units, one grapheme cluster,
        // wide because its first code point is in 0x1F300..0x1F64F.
        val text = "👩‍💻"
        assertEquals(5, text.length)
        assertArrayEquals(intArrayOf(0, 5), MonoWide.plan(text, 0, text.length))
    }

    @Test
    fun aSequenceEmojiOutsideTheTablePlansToNone() {
        // U+1F680 🚀 is outside #621's isWide table: natural width, and
        // ticket 60's measurer agrees (it is never sent to the host).
        val text = "🚀"
        assertSame(MonoWide.NONE, MonoWide.plan(text, 0, text.length))
    }

    @Test
    fun theCodePointsGeistMonoCoversStayOneCell() {
        // 〃 and 〜 are inside the wide ranges but excluded: no cluster is
        // double-width, so the whole run is one fast-path drawText.
        val text = "〃〜.."
        assertSame(MonoWide.NONE, MonoWide.plan(text, 0, text.length))
    }

    @Test
    fun planUsesAbsoluteIndicesIntoTheGivenRange() {
        // Range [1, 5) of "xx名yy": the cluster [2,3) is the only wide one,
        // and its indices are absolute, not range-relative.
        val text = "xx名yy"
        assertArrayEquals(intArrayOf(2, 3), MonoWide.plan(text, 1, 5))
    }

    @Test
    fun planIgnoresWideCodePointsOutsideTheRange() {
        // 名 sits before the requested range: the cheap scan finds nothing.
        val text = "名abc"
        assertSame(MonoWide.NONE, MonoWide.plan(text, 1, 4))
    }

    @Test
    fun emptyAndAllNarrowRangesPlanToNone() {
        assertSame(MonoWide.NONE, MonoWide.plan("abc", 2, 2))
        assertSame(MonoWide.NONE, MonoWide.plan("abc", 0, 3))
    }

    @Test
    fun aClusterMixedIntoAShorterStringStaysIntact() {
        // The ZWJ sequence is one cluster wherever it sits: [1, 6).
        val text = "x👩‍💻y"
        assertArrayEquals(intArrayOf(1, 6), MonoWide.plan(text, 0, text.length))
    }
}
