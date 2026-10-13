package sh.roboco.android.transcript

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TranscriptPerf]'s JSON shape (`ios:Debug/Signposts.swift:23-26`) and
 * [reset]: the lab bench (136) parses this string, so the key order and
 * the `%.2f` / `%d` format are pinned, and `Locale.US` keeps a
 * German-locale device from writing `0,00`. Plain JVM.
 */
class TranscriptPerfTest {

    @Test
    fun jsonAfterResetMatchesTheIosShapeExactly() {
        TranscriptPerf.reset()
        assertEquals(
            "{\"maxLayoutMs\":0.00,\"maxApplyMs\":0.00,\"maxSyncBuildMs\":0.00," +
                "\"syncBuilds\":0,\"asyncBuilds\":0,\"viewsCreated\":0}",
            TranscriptPerf.json,
        )
    }

    @Test
    fun jsonFormatsDoublesWithTwoDecimalsAndIntegersPlain() {
        TranscriptPerf.reset()
        TranscriptPerf.maxLayoutMs = 1.236
        TranscriptPerf.syncBuilds = 7
        val json = TranscriptPerf.json
        assertTrue("\"maxLayoutMs\":1.24 missing in $json", json.contains("\"maxLayoutMs\":1.24"))
        assertTrue("\"syncBuilds\":7 missing in $json", json.contains("\"syncBuilds\":7"))
    }

    @Test
    fun jsonStaysDotSeparatedUnderAGermanLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            TranscriptPerf.reset()
            TranscriptPerf.maxApplyMs = 0.5
            TranscriptPerf.maxSyncBuildMs = 1.25
            assertTrue(TranscriptPerf.json.contains("\"maxApplyMs\":0.50"))
            assertTrue(TranscriptPerf.json.contains("\"maxSyncBuildMs\":1.25"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun resetZeroesAllSixFields() {
        TranscriptPerf.maxLayoutMs = 3.0
        TranscriptPerf.maxApplyMs = 2.0
        TranscriptPerf.maxSyncBuildMs = 1.0
        TranscriptPerf.syncBuilds = 5
        TranscriptPerf.asyncBuilds = 6
        TranscriptPerf.viewsCreated = 7
        TranscriptPerf.reset()
        assertEquals(0.0, TranscriptPerf.maxLayoutMs, 0.0)
        assertEquals(0.0, TranscriptPerf.maxApplyMs, 0.0)
        assertEquals(0.0, TranscriptPerf.maxSyncBuildMs, 0.0)
        assertEquals(0, TranscriptPerf.syncBuilds)
        assertEquals(0, TranscriptPerf.asyncBuilds)
        assertEquals(0, TranscriptPerf.viewsCreated)
    }
}
