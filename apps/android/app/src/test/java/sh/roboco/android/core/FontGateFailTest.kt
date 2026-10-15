package sh.roboco.android.core

import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * `FontGate.fail` (Robolectric: `Log` needs the shadow): a late failure
 * merges its path into `Missing` without duplicates, logs the ticket's
 * message, and every change is visible through `statusFlow` — the flow
 * `MainActivity` collects, so the fatal screen appears without recreating
 * the activity (the recomposition itself is `FontGateHostTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontGateFailTest {

    private val goodMagic = byteArrayOf(0, 1, 0, 0)

    @After
    fun resetToOk() {
        FontGate.check({ ByteArrayInputStream(goodMagic) }, debug = false, log = {})
    }

    @Test
    fun failMarksTheGateMissing() {
        FontGate.fail("fonts/Geist.ttf")
        assertEquals(FontStatus.Missing(listOf("fonts/Geist.ttf")), FontGate.status)
        assertEquals(FontStatus.Missing(listOf("fonts/Geist.ttf")), FontGate.statusFlow.value)
    }

    @Test
    fun aSecondFailAppendsItsPathWithoutDuplicates() {
        FontGate.fail("fonts/Geist.ttf")
        FontGate.fail("fonts/GeistMono.ttf")
        FontGate.fail("fonts/Geist.ttf")
        assertEquals(
            FontStatus.Missing(listOf("fonts/Geist.ttf", "fonts/GeistMono.ttf")),
            FontGate.status,
        )
    }

    @Test
    fun theFailureIsLoggedWithThePath() {
        ShadowLog.clear()
        FontGate.fail("fonts/Geist.ttf")
        val messages = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue(messages.any { it == "Unreadable font asset: fonts/Geist.ttf" })
    }

    @Test
    fun statusFlowEmitsEveryChange() = runBlocking {
        val seen = mutableListOf<FontStatus>()
        // Unconfined: the collector sees the current value at launch and
        // then every emission synchronously on this thread.
        val collector = launch(Dispatchers.Unconfined) {
            FontGate.statusFlow.collect { seen.add(it) }
        }
        try {
            FontGate.fail("fonts/Geist.ttf")
            FontGate.fail("fonts/GeistMono.ttf")
        } finally {
            collector.cancel()
        }
        assertEquals(
            listOf(
                FontStatus.Ok,
                FontStatus.Missing(listOf("fonts/Geist.ttf")),
                FontStatus.Missing(listOf("fonts/Geist.ttf", "fonts/GeistMono.ttf")),
            ),
            seen,
        )
    }
}
