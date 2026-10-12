package sh.roboco.android.transcript

import android.view.Choreographer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manual poster the ticket prescribes: every posted callback is kept in
 * [posted], the still-pending ones in [pending], and [doFrame] drains one
 * exactly as the vsync would (`doFrame(0L)`). Shared with
 * `TranscriptStateTest`.
 */
internal class ManualPoster : FramePoster {
    val posted = ArrayList<Choreographer.FrameCallback>()
    val pending = ArrayDeque<Choreographer.FrameCallback>()

    override fun post(callback: Choreographer.FrameCallback) {
        posted.add(callback)
        pending.addLast(callback)
    }

    override fun remove(callback: Choreographer.FrameCallback) {
        pending.remove(callback)
    }

    /** Runs the oldest pending callback, as the main Choreographer would. */
    fun doFrame() {
        pending.removeFirstOrNull()?.doFrame(0L)
    }
}

/**
 * The relay's coalescing rules (ticket 61, `ios:Transcript/FrameRelay.swift`):
 * one outstanding hop per burst, the flag cleared *before* apply so a frame
 * published during apply is never lost, and a close that cancels everything.
 * Runs under Robolectric because [Choreographer.FrameCallback] is an
 * android class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FrameRelayTest {

    @Test
    fun aHundredLayoutThreadNotificationsCoalesceIntoOnePost() {
        val applies = AtomicInteger()
        val poster = ManualPoster()
        val relay = FrameRelay(poster) { applies.incrementAndGet() }
        try {
            // A burst before main runs: 100 notifications from the layout thread.
            val layoutThread = Thread { repeat(100) { relay.frameReady((it + 1).toULong()) } }
            layoutThread.start()
            layoutThread.join()

            assertEquals(1, poster.posted.size)
            assertEquals(0, applies.get())

            poster.doFrame()
            assertEquals(1, applies.get())
            assertTrue(poster.pending.isEmpty())

            // A later notification posts again: the flag was cleared.
            relay.frameReady(100uL)
            assertEquals(1, poster.pending.size)
            poster.doFrame()
            assertEquals(2, applies.get())
            assertTrue(poster.pending.isEmpty())
        } finally {
            relay.close()
        }
    }

    @Test
    fun aFramePublishedDuringApplySchedulesAFreshHop() {
        val applies = AtomicInteger()
        val poster = ManualPoster()
        lateinit var relay: FrameRelay
        relay = FrameRelay(poster) {
            if (applies.incrementAndGet() == 1) {
                // A frame lands while the first apply is running: the flag
                // was already cleared, so this must schedule a second hop.
                relay.frameReady(9uL)
            }
        }
        try {
            relay.frameReady(1uL)
            poster.doFrame()
            assertEquals(1, applies.get())

            // The notification from inside apply posted a second callback.
            assertEquals(1, poster.pending.size)
            poster.doFrame()
            assertEquals(2, applies.get())
            assertTrue(poster.pending.isEmpty())
        } finally {
            relay.close()
        }
    }

    @Test
    fun closeRemovesThePostedCallbackAndIgnoresEverythingAfter() {
        val applies = AtomicInteger()
        val poster = ManualPoster()
        val relay = FrameRelay(poster) { applies.incrementAndGet() }

        relay.frameReady(1uL)
        assertEquals(1, poster.pending.size)
        val posted = poster.posted.single()

        relay.close()
        assertTrue("close() must remove the posted callback", poster.pending.isEmpty())

        // The callback still runs (the race): it finds closed and does nothing.
        posted.doFrame(0L)
        assertEquals(0, applies.get())

        // Late notifications post nothing.
        relay.frameReady(2uL)
        assertEquals(1, poster.posted.size)
        assertTrue(poster.pending.isEmpty())
    }
}
