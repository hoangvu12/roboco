package sh.roboco.android.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.DebugEntry
import kotlin.random.Random

/**
 * The lab's entry/stream logic (android-ui-parity #135) — the port of
 * `TranscriptLabViewController.swift:102-134, 182-186` as pure state.
 * Plain JVM: [DebugEntry] is a data class, and [LabStream] makes no
 * native calls, so nothing here needs Robolectric.
 */
class LabStreamTest {

    /** Records every push (`entries`, `working`). */
    private class PushLog : (List<DebugEntry>, Boolean) -> Unit {
        val calls = ArrayList<Pair<List<DebugEntry>, Boolean>>()
        override fun invoke(entries: List<DebugEntry>, working: Boolean) {
            calls += entries to working
        }
    }

    @Test
    fun initialEntriesCycleTheThreePrompts() {
        val entries = LabScript.initialEntries(4, "F")
        assertEquals(8, entries.size)
        assertEquals(
            listOf("u0", "a0", "u1", "a1", "u2", "a2", "u3", "a3"),
            entries.map { it.id },
        )
        assertEquals(
            listOf(true, false, true, false, true, false, true, false),
            entries.map { it.user },
        )
        assertEquals(
            LabScript.Prompts[0] + LabScript.Prompts[1] + LabScript.Prompts[2] + LabScript.Prompts[0],
            entries.filter { it.user }.joinToString("") { it.text },
        )
        assertTrue(entries.none { it.streaming })
        assertTrue(entries.filter { !it.user }.all { it.text == "F" })
        assertTrue(LabScript.initialEntries(0, "F").isEmpty())
    }

    @Test
    fun seedPushesOnceWithWorkingFalse() {
        val log = PushLog()
        val stream = LabStream("F", push = log)
        stream.seed(2)
        assertEquals(1, log.calls.size)
        assertEquals(4, log.calls[0].first.size)
        assertEquals(false, log.calls[0].second)
    }

    /** 137: the lab's image fixtures append after the seeded turns. */
    @Test
    fun seedAppendsTheExtraEntriesAfterTheTurns() {
        val log = PushLog()
        val stream = LabStream("F", push = log)
        stream.seed(
            1,
            extra = listOf(
                DebugEntry("img-u", true, "Show the calculation screenshot.", false),
                DebugEntry("img-a", false, "![Calculation result: 8](/workspace/calculator/5-plus-3.png)", false),
            ),
        )
        assertEquals(1, log.calls.size)
        val entries = log.calls[0].first
        assertEquals("u0, a0, img-u, img-a", entries.joinToString(", ") { it.id })
        assertEquals("Show the calculation screenshot.", entries[2].text)
        assertEquals("![Calculation result: 8](/workspace/calculator/5-plus-3.png)", entries[3].text)
        assertEquals(false, log.calls[0].second)
    }

    @Test
    fun beginAppendsWithoutPushing() {
        val log = PushLog()
        val stream = LabStream("F", push = log)
        stream.seed(2)
        log.calls.clear()

        stream.begin("hi")

        // The deferred first push (iOS :102-110): the new rows reach the
        // engine only on the first tick.
        assertTrue(log.calls.isEmpty())
        val user = stream.entries[stream.entries.size - 2]
        assertEquals("u4", user.id)
        assertTrue(user.user)
        assertEquals("hi", user.text)
        assertFalse(user.streaming)
        val assistant = stream.entries.last()
        assertEquals("a4", assistant.id)
        assertFalse(assistant.user)
        assertEquals("", assistant.text)
        assertTrue(assistant.streaming)
        assertTrue(stream.running)
    }

    @Test
    fun tickStreamsWordsAndRebuildsTheFixture() {
        // "a b  c\nd e".split(" ") keeps the empty string: a, b, "", "c\nd", e.
        val steps = ArrayDeque(listOf(2, 2, 2))
        val log = PushLog()
        val stream = LabStream("a b  c\nd e", push = log, nextStep = { steps.removeFirst() })
        stream.seed(0)
        stream.begin("go")
        log.calls.clear()

        assertTrue(stream.tick())
        assertEquals("a b", stream.entries.last().text)
        assertTrue(stream.tick())
        assertEquals("a b  c\nd", stream.entries.last().text)
        assertFalse(stream.tick())
        // Re-joined with " ", the chunks rebuild the fixture byte for byte.
        assertEquals("a b  c\nd e", stream.entries.last().text)

        assertEquals(3, log.calls.size)
        assertEquals(listOf(true, true, false), log.calls.map { it.second })
        assertFalse(stream.running)
        assertFalse(stream.entries.last().streaming)
    }

    @Test
    fun stopFreezesTheLiveEntryAndPushesOnlyOnce() {
        val steps = ArrayDeque(listOf(2))
        val log = PushLog()
        val stream = LabStream("a b c d e", push = log, nextStep = { steps.removeFirst() })
        stream.seed(0)
        stream.begin("go")
        assertTrue(stream.tick()) // "a b", still streaming
        log.calls.clear()

        stream.stop()
        assertEquals(1, log.calls.size)
        assertEquals(false, log.calls[0].second)
        assertFalse(stream.entries.last().streaming)
        assertEquals("a b", stream.entries.last().text) // frozen where it was
        assertFalse(stream.running)

        log.calls.clear()
        stream.stop() // idempotent
        assertTrue(log.calls.isEmpty())
    }

    @Test
    fun beginWhileStreamingFinishesTheOldEntryFirst() {
        val steps = ArrayDeque(listOf(2, 2, 2, 2))
        val log = PushLog()
        val stream = LabStream("a b c d e", push = log, nextStep = { steps.removeFirst() })
        stream.seed(2) // 4 entries: u0, a0, u1, a1
        stream.begin("one") // u4, a4
        assertTrue(stream.tick()) // a4 "a b", streaming
        log.calls.clear()

        stream.begin("two")

        // One push: the old live entry finished with working = false.
        assertEquals(1, log.calls.size)
        assertEquals(false, log.calls[0].second)
        assertFalse(log.calls[0].first.last().streaming)
        assertEquals("a b", log.calls[0].first.last().text)
        // The new pair ids come from the new size (6), never renumbered.
        assertEquals(
            listOf("u0", "a0", "u1", "a1", "u4", "a4", "u6", "a6"),
            stream.entries.map { it.id },
        )
        assertEquals("two", stream.entries[stream.entries.size - 2].text)
        assertEquals("", stream.entries.last().text)
        assertTrue(stream.entries.last().streaming)
        assertTrue(stream.running)
    }

    @Test
    fun realStepRangeAlwaysFinishesWithin35To87Ticks() {
        // 173 words, like the fixture; 2..5 words per tick gives
        // ceil(173/5) .. ceil(173/2) = 35..87 fires.
        val fixture = List(173) { i -> "w$i" }.joinToString(" ")
        val random = Random(20_260_101)
        repeat(1_000) { run ->
            val log = PushLog()
            val stream = LabStream(fixture, push = log, nextStep = { random.nextInt(2, 6) })
            stream.seed(0)
            stream.begin("go")
            var ticks = 0
            while (true) {
                ticks++
                if (!stream.tick()) break
            }
            assertTrue("run $run took $ticks ticks", ticks in 35..87)
            assertEquals(fixture, stream.entries.last().text)
            assertEquals(false, log.calls.last().second)
        }
    }
}
