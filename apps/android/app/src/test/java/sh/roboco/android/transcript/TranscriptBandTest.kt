package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The realized-band math (ticket 65, plain JVM): the two spec rows, the
 * coverage property over offsets −2000…20000 (step 7.3) × viewports
 * {0, 640, 891} — the band must always cover iOS's
 * `[offset − 700, offset + viewport + 700)` with exactly
 * `viewport + 2·700 + 256` dp of width — and the constant-within-one-step
 * rule that keeps scrolling from recomposing per pixel.
 */
class TranscriptBandTest {

    @Test
    fun bandStartAtZeroIsMinus768() {
        assertEquals(-768f, bandStart(0f), 0f)
        assertEquals(1688f, bandEnd(-768f, 800f), 0f)
    }

    @Test
    fun bandStartAt1000Is256() {
        assertEquals(256f, bandStart(1000f), 0f)
        assertEquals(2712f, bandEnd(256f, 800f), 0f)
    }

    @Test
    fun theBandAlwaysCoversTheIOSBandWithAFixedSize() {
        var offset = -2000f
        while (offset <= 20000f) {
            for (vp in listOf(0f, 640f, 891f)) {
                val start = bandStart(offset)
                val end = bandEnd(start, vp)
                assertTrue("start $start > offset $offset - 700", start <= offset - 700f)
                assertTrue("start $start <= offset $offset - 956", start > offset - 956f)
                assertTrue(
                    "end $end <= offset $offset + vp $vp + 700",
                    end > offset + vp + 700f,
                )
                assertEquals("width at offset $offset vp $vp", vp + 1656f, end - start, 0f)
            }
            offset += 7.3f
        }
    }

    @Test
    fun bandStartIsConstantWithinOne256Step() {
        // [2748, 3004) is one step: (offset - 700) stays in [2048, 2304).
        val expected = bandStart(2748f)
        assertEquals(2048f, expected, 0f)
        var offset = 2748f
        while (offset < 3004f) {
            assertEquals("at $offset", expected, bandStart(offset), 0f)
            offset += 7.3f
        }
        assertEquals("the next step starts", 2304f, bandStart(3004f), 0f)
    }
}
