package sh.roboco.android.debug

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.transcript.TranscriptPerf
import java.util.Locale

/**
 * The bench's JSON shapes (android-ui-parity #136) — the iOS format
 * strings, run under a German default locale so a `,` decimal would fail:
 * every number must stay a `.` (invalid JSON otherwise). Robolectric:
 * `org.json` is a stub on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BenchJsonTest {

    @Before
    fun forceGermanLocale() {
        Locale.setDefault(Locale.GERMANY)
    }

    @Test
    fun benchResultJsonMatchesTheIosShape() {
        val result = BenchResult().apply {
            frames = 10
            hitches = 2
            hitchMs = 25.0
            seconds = 0.5
            worstMs = 33.3333
        }
        assertEquals(
            "{\"frames\":10,\"hitches\":2,\"hitchRatioMsPerS\":50.00,\"worstFrameMs\":33.3,\"seconds\":0.50}",
            result.json,
        )
    }

    @Test
    fun zeroSecondsReadsAZeroRatio() {
        val result = BenchResult().apply {
            frames = 7
            hitches = 1
            hitchMs = 3.0
            seconds = 0.0
            worstMs = 5.0
        }
        assertTrue(result.json.contains("\"hitchRatioMsPerS\":0.00"))
    }

    /**
     * Sanity read of 117's object (owned there, only read here): the
     * reset state and the exact key order the combined JSON embeds.
     */
    @Test
    fun transcriptPerfJsonAfterReset() {
        TranscriptPerf.reset()
        assertEquals(
            "{\"maxLayoutMs\":0.00,\"maxApplyMs\":0.00,\"maxSyncBuildMs\":0.00," +
                "\"syncBuilds\":0,\"asyncBuilds\":0,\"viewsCreated\":0}",
            TranscriptPerf.json,
        )
    }

    @Test
    fun benchJsonKeepsTheKeyOrderAndParses() {
        val idle = BenchResult().apply {
            frames = 671
            hitches = 3
            hitchMs = 13.8628
            seconds = 11.18
            worstMs = 41.7
        }
        val streaming = BenchResult().apply {
            frames = 535
            hitches = 4
            hitchMs = 10.7
            seconds = 8.93
            worstMs = 40.2
        }
        val idlePerf =
            "{\"maxLayoutMs\":2.31,\"maxApplyMs\":0.84,\"maxSyncBuildMs\":1.92," +
                "\"syncBuilds\":4,\"asyncBuilds\":0,\"viewsCreated\":38}"
        val streamingPerf =
            "{\"maxLayoutMs\":3.10,\"maxApplyMs\":0.90,\"maxSyncBuildMs\":2.20," +
                "\"syncBuilds\":6,\"asyncBuilds\":2,\"viewsCreated\":41}"
        val jank =
            "{\"idleFrames\":668,\"idleJanky\":2,\"idleJankyPct\":0.30,\"idleWorstMs\":33.1," +
                "\"streamingFrames\":535,\"streamingJanky\":4,\"streamingJankyPct\":0.75," +
                "\"streamingWorstMs\":40.2,\"refreshHz\":120.0}"

        val json = benchJson(idle, streaming, idlePerf, streamingPerf, jank)

        assertEquals(
            "{\"idle\":${idle.json},\"streaming\":${streaming.json},\"idlePerf\":$idlePerf," +
                "\"streamingPerf\":$streamingPerf,\"jankstats\":$jank}",
            json,
        )
        // The iOS parse shape still holds: every top-level key is a flat
        // object of numbers.
        val parsed = JSONObject(json)
        for (key in listOf("idle", "streaming", "idlePerf", "streamingPerf", "jankstats")) {
            assertTrue("$key is not an object", parsed.optJSONObject(key) != null)
        }
        assertEquals(671, parsed.getJSONObject("idle").getInt("frames"))
        assertEquals(1.24, parsed.getJSONObject("idle").getDouble("hitchRatioMsPerS"), 1e-9)
        assertEquals(120.0, parsed.getJSONObject("jankstats").getDouble("refreshHz"), 1e-9)
    }

    @Test
    fun jankTallyCountsPerPhase() {
        val tally = JankTally()
        // 10 idle frames, 1 janky, the worst 40 ms.
        repeat(10) { i ->
            tally.record(isJank = i == 0, durationNanos = if (i == 0) 40_000_000L else 8_000_000L)
        }
        assertEquals(
            "{\"idleFrames\":10,\"idleJanky\":1,\"idleJankyPct\":10.00,\"idleWorstMs\":40.0," +
                "\"streamingFrames\":0,\"streamingJanky\":0,\"streamingJankyPct\":0.00," +
                "\"streamingWorstMs\":0.0,\"refreshHz\":120.0}",
            tally.json(120.0f),
        )
        // The phase flip moves the tally; the idle numbers freeze.
        tally.phase = JankTally.Phase.Streaming
        tally.record(isJank = true, durationNanos = 50_000_000L)
        tally.record(isJank = false, durationNanos = 9_000_000L)
        assertEquals(
            "{\"idleFrames\":10,\"idleJanky\":1,\"idleJankyPct\":10.00,\"idleWorstMs\":40.0," +
                "\"streamingFrames\":2,\"streamingJanky\":1,\"streamingJankyPct\":50.00," +
                "\"streamingWorstMs\":50.0,\"refreshHz\":60.0}",
            tally.json(60.0f),
        )
    }
}
