package sh.roboco.android.core

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The CJK fallback choice (Robolectric: `ULocale` needs it, the logic
 * itself is pure). `cjkVariantFor` maps the device locale onto the OTC
 * variant; `pickCjk` walks a synthetic system-font list mirroring
 * `fonts.xml` — locale match first, then ttc index, then the fixed
 * `/system/fonts/NotoSansCJK-Regular.ttc` — and never picks a Serif file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CjkFallbackTest {

    @Test
    fun cjkVariantForTheDeviceLocale() {
        assertEquals(CjkVariant.JP, cjkVariantFor(Locale.forLanguageTag("ja-JP")))
        assertEquals(CjkVariant.KR, cjkVariantFor(Locale.forLanguageTag("ko-KR")))
        assertEquals(CjkVariant.SC, cjkVariantFor(Locale.forLanguageTag("zh-CN")))
        assertEquals(CjkVariant.SC, cjkVariantFor(Locale.forLanguageTag("zh")))
        assertEquals(CjkVariant.TC, cjkVariantFor(Locale.forLanguageTag("zh-TW")))
        assertEquals(CjkVariant.TC, cjkVariantFor(Locale.forLanguageTag("zh-Hant")))
        assertEquals(CjkVariant.HK, cjkVariantFor(Locale.forLanguageTag("zh-HK")))
        assertEquals(CjkVariant.HK, cjkVariantFor(Locale.forLanguageTag("zh-MO")))
        assertEquals(CjkVariant.SC, cjkVariantFor(Locale.forLanguageTag("en-US")))
        assertEquals(CjkVariant.SC, cjkVariantFor(Locale.forLanguageTag("vi-VN")))
    }

    private fun cjk(fileName: String, index: Int, vararg tags: String) =
        CjkCandidate(
            fileName = fileName,
            path = "/system/fonts/$fileName",
            ttcIndex = index,
            locales = tags.map { Locale.forLanguageTag(it) },
            variable = false,
        )

    /** The system image's entries for `NotoSansCJK-Regular.ttc`, plus the Serif decoys. */
    private val fontsXml = listOf(
        cjk("NotoSansCJK-Regular.ttc", 2, "zh-Hans"),
        cjk("NotoSansCJK-Regular.ttc", 3, "zh-Hant", "zh-Bopo"),
        cjk("NotoSansCJK-Regular.ttc", 0, "ja"),
        cjk("NotoSansCJK-Regular.ttc", 1, "ko"),
        cjk("NotoSerifCJK-Regular.ttc", 2, "zh-Hans"),
        cjk("NotoSerifCJK-Regular.ttc", 3, "zh-Hant"),
    )

    @Test
    fun localeMatchPicksTheRightTtcFace() {
        assertEquals(0, pickCjk(fontsXml, Locale.forLanguageTag("ja-JP"), false)!!.ttcIndex)
        assertEquals(1, pickCjk(fontsXml, Locale.forLanguageTag("ko-KR"), false)!!.ttcIndex)
        assertEquals(2, pickCjk(fontsXml, Locale.forLanguageTag("en-US"), false)!!.ttcIndex)
        assertEquals(2, pickCjk(fontsXml, Locale.forLanguageTag("zh-CN"), false)!!.ttcIndex)
        assertEquals(3, pickCjk(fontsXml, Locale.forLanguageTag("zh-TW"), false)!!.ttcIndex)
        // No zh-Hant-HK entry exists in fonts.xml: HK retries as TC.
        assertEquals(3, pickCjk(fontsXml, Locale.forLanguageTag("zh-HK"), false)!!.ttcIndex)
    }

    @Test
    fun serifCandidatesAreNeverPicked() {
        for (tag in listOf("ja-JP", "ko-KR", "en-US", "zh-CN", "zh-TW", "zh-HK", "vi-VN")) {
            val picked = pickCjk(fontsXml, Locale.forLanguageTag(tag), false)
            assertNotNull("picked for $tag", picked)
            assertFalse("serif picked for $tag", picked!!.fileName.contains("Serif"))
            assertEquals("NotoSansCJK-Regular.ttc", picked.fileName)
        }
    }

    @Test
    fun indexMatchWinsWhenNoLocaleMatches() {
        // A SC device against a list whose only locale-tagged entry is
        // Japanese: index 2 still matches by name + ttc index, before the
        // "first candidate" step could pick index 0.
        val list = listOf(
            cjk("NotoSansCJK-Regular.ttc", 0, "ja"),
            cjk("NotoSansCJK-Regular.ttc", 2),
        )
        assertEquals(2, pickCjk(list, Locale.forLanguageTag("en-US"), false)!!.ttcIndex)
    }

    @Test
    fun firstCandidateWhenNothingMatchesAtAll() {
        val odd = listOf(cjk("NotoSansCJK-VF.ttc", 5, "th"))
        val picked = pickCjk(odd, Locale.forLanguageTag("en-US"), false)!!
        assertEquals("NotoSansCJK-VF.ttc", picked.fileName)
        assertEquals(5, picked.ttcIndex)
    }

    @Test
    fun emptyListFallsBackToTheFixedFileAtTheVariantIndex() {
        val ja = pickCjk(emptyList(), Locale.forLanguageTag("ja-JP"), true)!!
        assertEquals("NotoSansCJK-Regular.ttc", ja.fileName)
        assertEquals("/system/fonts/NotoSansCJK-Regular.ttc", ja.path)
        assertEquals(0, ja.ttcIndex)
        assertTrue(ja.variable)
        // HK returns index 4; the 4 -> 3 -> 2 retry lives in
        // FontChain.cjkFamily, which can actually open the file.
        assertEquals(4, pickCjk(emptyList(), Locale.forLanguageTag("zh-HK"), true)!!.ttcIndex)
        assertEquals(2, pickCjk(emptyList(), Locale.forLanguageTag("en-US"), true)!!.ttcIndex)
    }

    @Test
    fun emptyListWithoutTheFixedFileIsNull() {
        assertNull(pickCjk(emptyList(), Locale.forLanguageTag("ja-JP"), false))
    }
}
