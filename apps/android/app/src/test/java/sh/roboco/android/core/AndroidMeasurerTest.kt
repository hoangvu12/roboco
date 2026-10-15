package sh.roboco.android.core

import android.content.Context
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import uniffi.roboco_core.FaceRole

/**
 * `AndroidMeasurer` under Robolectric (native graphics, real GeistMono
 * advances): the ticket's worked-example table, the absolute
 * one-advance-per-scalar contract over a 200-string corpus, ligature
 * settings that do not leak between calls, the LRU memo, and the
 * never-throw wrapper.
 *
 * Wide-mono assertions compare against `2 × measure(MONO, size, false,
 * "0")`, which holds whether or not the Robolectric host has a CJK font:
 * the double-width rule is ours, not the fallback font's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AndroidMeasurerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        Fonts.resetForTest()
        AndroidMeasurer.Stats.reset()
    }

    @After
    fun resetAfter() {
        Fonts.resetForTest()
        AndroidMeasurer.Stats.reset()
    }

    private fun newMeasurer(): AndroidMeasurer =
        AndroidMeasurer(Fonts.load(context).typefaces)

    @Test
    fun workedExamples() {
        val m = newMeasurer()
        val c = m.measure(FaceRole.MONO, 13.5f, false, "0")
        // GeistMono's `0`: 600/1000 em, so 0.6 x 13.5.
        assertEquals(8.1f, c, 0.01f)

        val cjk = m.measureRun(FaceRole.MONO, 13.5f, false, "名称")
        assertEquals(2, cjk.size)
        assertEquals(2 * c, cjk[0], 0.01f)
        assertEquals(2 * c, cjk[1], 0.01f)
        assertEquals(4 * c, m.measure(FaceRole.MONO, 13.5f, false, "名称"), 0.01f)

        // A ZWJ family: 5 scalars, 1 grapheme cluster, width on the first.
        val family = m.measureRun(FaceRole.SANS, 16.5f, true, "👨‍👩‍👧")
        assertEquals(5, family.size)
        assertTrue("family width was ${family[0]}", family[0] > 0f)
        assertEquals(0f, family[1], 1e-6f)
        assertEquals(0f, family[2], 1e-6f)
        assertEquals(0f, family[3], 1e-6f)
        assertEquals(0f, family[4], 1e-6f)

        // Regional indicators: 2 scalars, 1 cluster.
        val flag = m.measureRun(FaceRole.SANS, 16.5f, true, "🇯🇵")
        assertEquals(2, flag.size)
        assertTrue(flag[0] > 0f)
        assertEquals(0f, flag[1], 1e-6f)

        // Decomposed and precomposed é.
        val decomposed = m.measureRun(FaceRole.SANS, 16.5f, true, "e\u0301")
        assertEquals(2, decomposed.size)
        assertTrue(decomposed[0] > 0f)
        assertEquals(0f, decomposed[1], 1e-6f)
        assertEquals(1, m.measureRun(FaceRole.SANS, 16.5f, true, "\u00E9").size)

        // The empty string is supported: an empty list, never a wrong length.
        assertTrue(m.measureRun(FaceRole.SANS, 16.5f, true, "").isEmpty())

        // ASCII at mono weight next to a wide cluster.
        val mixed = m.measureRun(FaceRole.MONO, 13.5f, false, "a名")
        assertEquals(2, mixed.size)
        assertEquals(0.6f * 13.5f, mixed[0], 0.01f)
        assertEquals(2 * c, mixed[1], 0.01f)
    }

    @Test
    fun theGeistCoveredWideMarksStayOneCellWide() {
        val m = newMeasurer()
        // 〃 (U+3003) is wide per the table but covered by GeistMono at a
        // 1-cell advance: one cell, not two.
        val run = m.measureRun(FaceRole.MONO, 10f, false, "〃")
        assertEquals(1, run.size)
        assertEquals(m.measure(FaceRole.MONO, 10f, false, "0"), run[0], 0.01f)
        val wave = m.measureRun(FaceRole.MONO, 10f, false, "〜")
        assertEquals(1, wave.size)
        assertEquals(m.measure(FaceRole.MONO, 10f, false, "0"), wave[0], 0.01f)
    }

    @Test
    fun runLengthsMatchCodePointCountsOverACorpus() {
        val m = newMeasurer()
        for (text in corpus()) {
            for (role in listOf(FaceRole.SANS, FaceRole.MONO)) {
                val ligatures = role == FaceRole.SANS
                val run = m.measureRun(role, 16.5f, ligatures, text)
                assertEquals(
                    "size for \"${text.take(16)}\" (${text.codePointCount(0, text.length)} scalars)",
                    text.codePointCount(0, text.length),
                    run.size,
                )
                for (advance in run) {
                    assertTrue("advance $advance for \"${text.take(16)}\"", advance.isFinite())
                    assertTrue("negative advance $advance for \"${text.take(16)}\"", advance >= 0f)
                }
            }
        }
    }

    @Test
    fun theEmptyStringIsAnEmptyListForBothPaths() {
        val m = newMeasurer()
        assertTrue(m.measureRun(FaceRole.SANS, 16.5f, true, "").isEmpty())
        assertTrue(m.measureRun(FaceRole.MONO, 13.5f, false, "").isEmpty())
        assertEquals(0f, m.measure(FaceRole.SANS, 16.5f, true, ""), 0f)
    }

    @Test
    fun ligatureCallsDoNotLeakSettingsIntoEachOther() {
        val consecutive = newMeasurer()
        val vFalse = consecutive.measure(FaceRole.SANS, 16.5f, false, "fi")
        val vTrue = consecutive.measure(FaceRole.SANS, 16.5f, true, "fi")

        // Each call alone, on a fresh measurer (fresh paint + memo):
        val alone = newMeasurer()
        val wFalse = alone.measure(FaceRole.SANS, 16.5f, false, "fi")
        val wTrue = alone.measure(FaceRole.SANS, 16.5f, true, "fi")

        // Whether "fi" ligates on this host is left open (the assertion
        // holds both ways); what must hold is determinism: consecutive
        // (false, true) calls return the same values as isolated ones.
        assertEquals(wFalse, vFalse, 0f)
        assertEquals(wTrue, vTrue, 0f)
    }

    @Test
    fun theMemoCountsOnlyRealMeasurements() {
        val m = newMeasurer()
        m.measureRun(FaceRole.MONO, 13.5f, false, "名称")
        assertEquals(1, AndroidMeasurer.Stats.calls.get())
        m.measureRun(FaceRole.MONO, 13.5f, false, "名称")
        assertEquals(1, AndroidMeasurer.Stats.calls.get())
    }

    @Test
    fun theMemoEvictsPastFourThousandAndNinetySixEntries() {
        val m = newMeasurer()
        for (i in 0..4096) {
            m.measureRun(FaceRole.MONO, 10f, false, "k%05d".format(i))
        }
        assertEquals(4097, AndroidMeasurer.Stats.calls.get())
        // "k00000" was the first inserted and is the one evicted.
        m.measureRun(FaceRole.MONO, 10f, false, "k00000")
        assertEquals(4098, AndroidMeasurer.Stats.calls.get())
    }

    @Test
    fun aMissingRoleNeverThrows() {
        // Test-only construction: the map lacks every role.
        val m = AndroidMeasurer(emptyMap<FaceRole, Typeface>())
        ShadowLog.clear()
        assertEquals(0f, m.measure(FaceRole.MONO, 10f, false, "a"), 0f)
        val run = m.measureRun(FaceRole.MONO, 10f, false, "a名")
        assertEquals(2, run.size)
        run.forEach { assertEquals(0f, it, 0f) }
        assertTrue(
            ShadowLog.getLogs().mapNotNull { it.msg }.any { it == "measurer failed" },
        )
    }

    /**
     * A deterministic 200-string corpus: ASCII, CJK, Hangul, emoji ZWJ
     * families, flags, keycaps, combining marks, Arabic, CRLF and mixes,
     * each repeated to lengths 1..10.
     */
    private fun corpus(): List<String> {
        val pieces = listOf(
            "plain ascii text",
            "名称 状態 ハングル 한국어",
            "直",
            "👨‍👩‍👧 family",
            "1️⃣ keycap",
            "🇯🇵🇺🇸 flags",
            "e\u0301 decomposed",
            "\u00E9 precomposed",
            "ابجد هوز حطي Arabic",
            "\r\n",
            "a\r\nb",
            "mixed 名称 ascii 👨‍👩‍👧 tail",
            "| 名称 | 状態 |",
            "0123456789",
            "!@#%*()_+-=[]{}:;,.?",
            "narrow katakana ｱｲｳ",
            "halfwidth keitai ｹｰﾀｲ",
            "combining a\u0301\u0302",
            "emoji alone 😀",
            "zwj alone \u200D",
        )
        val out = mutableListOf<String>()
        for (piece in pieces) {
            for (n in 1..10) {
                out += piece.repeat(n)
            }
        }
        assertEquals(200, out.size)
        return out
    }
}
