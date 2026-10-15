package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The own-turn runway's rules (ticket 69), plain JUnit — no Robolectric
 * and no core: [Runway] is a pure state machine exercised with fake rows
 * (a list of `(key, isUser, y)` plus the natural height), and
 * [RunwayDriver] runs against a real [TranscriptViewport] on an injected
 * clock (68's plain-JVM model), wired exactly like [TranscriptState]:
 * the driver's height as the `onFrameApplied` argument and its
 * [RunwayDriver.refresh] as 68's `onGeometryChanged` hook.
 *
 * The geometry is the ticket's worked example: `topInset 100`,
 * `bottomInset 80`, `viewportHeight 800`, a prompt row at `y = 1000` and
 * `natural = 1100`, so `hold = 900`, `minimum = 1620` and `maxOffset =
 * 900` — the prompt row's top sits just under the header whatever the
 * insets are. With the keyboard up (`bottomInset 380`) the minimum is
 * 1320; the reply retires the runway once `natural ≥ 1619.5`, and the
 * keyboard going away re-reserves it. Curve tolerances follow 68's
 * suite: 1e-4 for exact arithmetic, 1e-3 for the spring steps.
 */
class RunwayTest {

    // ------------------------------------------------------------ fixtures

    /** A fake frame: rows as (key, isUser, top y), plus the natural height. */
    private class FakeRows(
        private val rows: List<Row>,
        override val naturalHeight: Float,
    ) : RunwayRows {
        data class Row(val key: ULong, val user: Boolean, val y: Float)

        override val rowCount: Int get() = rows.size
        override fun userKeyAt(index: Int): ULong? = rows.getOrNull(index)?.takeIf { it.user }?.key
        override fun yOf(key: ULong): Float? = rows.firstOrNull { it.key == key }?.y
    }

    private fun fakeRows(vararg rows: Triple<ULong, Boolean, Float>, naturalHeight: Float) =
        FakeRows(rows.map { FakeRows.Row(it.first, it.second, it.third) }, naturalHeight)

    /** The frame current at send time: two old user rows, natural 1100. */
    private fun oldFrame() = fakeRows(
        Triple(K0, true, 0f),
        Triple(K1, true, 500f),
        naturalHeight = 1100f,
    )

    /** The prompt echo lands at `y = 1000` (worked example: hold 900, minimum 1620). */
    private fun promptFrame(naturalHeight: Float = 1100f) = fakeRows(
        Triple(K0, true, 0f),
        Triple(K1, true, 500f),
        Triple(K, true, 1000f),
        naturalHeight = naturalHeight,
    )

    /** The queued prompt's frame: K2 lands at `y = 1000` instead of K. */
    private fun queuedFrame() = fakeRows(
        Triple(K0, true, 0f),
        Triple(K1, true, 500f),
        Triple(K2, true, 1000f),
        naturalHeight = 1100f,
    )

    /**
     * A viewport + driver wired like `TranscriptState` on the worked
     * example's geometry: `refresh` installed as 68's geometry hook, and
     * [apply] doing the apply order (swap the frame, then hand 68 the
     * runway height, so the recompute runs before the snap/follow).
     * [viewportHeight] null leaves the box size undelivered (68 R12).
     */
    private class Rig(initial: FakeRows?, viewportHeight: Float? = 800f) {
        val viewport = TranscriptViewport { T0 }
        var current: FakeRows? = initial
        val driver = RunwayDriver(viewport) { current }

        init {
            viewport.onGeometryChanged = driver::refresh
            viewport.setTopInset(100f)
            viewport.setBottomInset(80f)
            viewportHeight?.let { viewport.setViewportHeight(it) }
        }

        fun apply(rows: FakeRows, first: Boolean) {
            current = rows
            viewport.onFrameApplied(driver.heightFor(rows), first)
        }
    }

    // -------------------------------------------------------- the key scans

    @Test
    fun recentUserKeysReturnsTheUserRowsAmongTheLastTwentyFour() {
        // 26 rows (indices 0..25): the scan covers 25 downTo 2, so the
        // user rows at n − 25 and n − 26 are excluded and the non-User
        // rows inside the window are skipped (every third row is an
        // assistant row here).
        val rows = List(26) { i -> Triple((i + 1).toULong(), i % 3 != 0, i * 10f) }
        val frame = fakeRows(*rows.toTypedArray(), naturalHeight = 260f)
        val expected = (2..25).filter { it % 3 != 0 }.map { (it + 1).toULong() }.toSet()
        assertEquals(expected, Runway.recentUserKeys(frame))
    }

    @Test
    fun recentUserKeysScansTheWholeFrameWhenShorterThanTwentyFour() {
        val frame = fakeRows(
            Triple(1uL, true, 0f),
            Triple(2uL, false, 10f),
            Triple(3uL, true, 20f),
            naturalHeight = 30f,
        )
        assertEquals(setOf(1uL, 3uL), Runway.recentUserKeys(frame))
    }

    @Test
    fun recentUserKeysWithoutAFrameIsEmpty() {
        assertEquals(emptySet<ULong>(), Runway.recentUserKeys(null))
    }

    // ------------------------------------------------------ the height rule

    @Test
    fun theWorkedExampleReservesAViewportBelowThePrompt() {
        val runway = Runway()
        runway.begin(setOf(K0, K1))
        val h = runway.contentHeight(promptFrame(), 100f, 800f, 80f)
        // hold = 1000 − 100 = 900, minimum = 900 + 800 − 80 = 1620.
        assertEquals(1620f, h.height, 0f)
        assertEquals(1620f, runway.runwayHeight!!, 0f)
        assertTrue(runway.holding)
        assertFalse(h.promoted)
    }

    @Test
    fun withoutAnOwnTurnTheHeightIsTheNaturalOne() {
        val runway = Runway()
        val h = runway.contentHeight(promptFrame(), 100f, 800f, 80f)
        assertEquals(1100f, h.height, 0f)
        assertNull(runway.runwayHeight)
        assertFalse(runway.holding)
        assertFalse(h.promoted)
    }

    @Test
    fun aRowAlreadyPresentAtSendTimeIsNeverThePrompt() {
        val runway = Runway()
        runway.begin(setOf(K0, K1, K)) // every user row was already there
        val h = runway.contentHeight(promptFrame(), 100f, 800f, 80f)
        // No new User row: the key stays unresolved, so the height holds
        // still — max(natural, 0) — with no reservation behind it.
        assertEquals(1100f, h.height, 0f)
        assertNull(runway.runwayHeight)
    }

    @Test
    fun aNewUserRowOutsideTheTwelveRowScanIsNotPickedUp() {
        val runway = Runway()
        // 14 rows: the only new user row sits at index 1 = n − 13, outside
        // the newest-first scan (13 downTo 2), so the key stays unresolved.
        val rows = List(14) { i ->
            if (i == 1) Triple(NEW, true, 10f)
            else Triple(1000uL + i.toULong(), false, i * 10f)
        }
        val frame = fakeRows(*rows.toTypedArray(), naturalHeight = 140f)
        runway.begin(emptySet())
        val h = runway.contentHeight(frame, 100f, 800f, 80f)
        assertEquals(140f, h.height, 0f) // unresolved → max(natural, 0)
        assertNull(runway.runwayHeight)
        assertTrue("the turn is still waiting for its echo", runway.hasOwnTurn)
    }

    @Test
    fun anUnresolvedPromptHoldsStillAndKeepsThePreviousRunway() {
        val runway = Runway()
        // Turn 1 resolves on K and holds 1620.
        runway.begin(setOf(K0, K1))
        runway.contentHeight(promptFrame(), 100f, 800f, 80f)
        assertEquals(1620f, runway.runwayHeight!!, 0f)

        // Turn 2 begins before its echo exists: hold still on turn 1's
        // reservation — `begin` does not reset it.
        runway.begin(setOf(K0, K1, K))
        val held = runway.contentHeight(promptFrame(), 100f, 800f, 80f)
        assertEquals(1620f, held.height, 0f) // max(natural 1100, 1620)
        assertEquals(1620f, runway.runwayHeight!!, 0f) // unchanged

        // A taller frame while still unresolved returns the natural height.
        val grown = runway.contentHeight(promptFrame(2000f), 100f, 800f, 80f)
        assertEquals(2000f, grown.height, 0f) // max(2000, 1620)
    }

    @Test
    fun aDisappearingPromptRowHoldsStill() {
        val runway = Runway()
        runway.begin(setOf(K0, K1))
        runway.contentHeight(promptFrame(), 100f, 800f, 80f) // resolves K, holds 1620
        // The echo row vanishes (a failed send, a removed entry): hold
        // still on the reservation.
        val gone = fakeRows(
            Triple(K0, true, 0f),
            Triple(K1, true, 500f),
            naturalHeight = 1100f,
        )
        val h = runway.contentHeight(gone, 100f, 800f, 80f)
        assertEquals(1620f, h.height, 0f) // max(natural 1100, 1620)
        assertEquals(1620f, runway.runwayHeight!!, 0f) // unchanged
        assertTrue("the stale reservation keeps the glide", runway.holding)
    }

    @Test
    fun theRunwayRetiresWhenTheReplyFillsTheReservedSpace() {
        val runway = Runway()
        runway.begin(setOf(K0, K1))
        // minimum 1620: natural 1619.5 is within the 0.5 tolerance →
        // retire to the natural height, but keep the anchor.
        val h = runway.contentHeight(promptFrame(1619.5f), 100f, 800f, 80f)
        assertEquals(1619.5f, h.height, 0f)
        assertNull(runway.runwayHeight)
        assertFalse(runway.holding)
        assertTrue("the anchor survives retirement", runway.hasOwnTurn)
    }

    @Test
    fun keyboardInsetsRetireAndReReserveTheSameRunway() {
        val runway = Runway()
        runway.begin(setOf(K0, K1))
        val frame = promptFrame(1400f)
        // Keyboard up: minimum = 900 + 800 − 380 = 1320; natural 1400 ≥
        // 1319.5 → retire, the height is the natural one.
        val kb = runway.contentHeight(frame, 100f, 800f, 380f)
        assertEquals(1400f, kb.height, 0f)
        assertNull(runway.runwayHeight)
        // Keyboard down: minimum 1620 > 1400 → the same anchor re-reserves.
        val again = runway.contentHeight(frame, 100f, 800f, 80f)
        assertEquals(1620f, again.height, 0f)
        assertEquals(1620f, runway.runwayHeight!!, 0f)
    }

    // ------------------------------------------------------- queued turns

    @Test
    fun aQueuedSendKeepsItsFirstExpectation() {
        val runway = Runway()
        runway.expectQueued(setOf(K0))
        // Ignored: the first one is kept. The second set covers every
        // user row of the frame — if it had replaced the first, the
        // promotion would find no new row at all.
        runway.expectQueued(setOf(K0, K1, K2))
        val h = runway.contentHeight(queuedFrame(), 100f, 800f, 80f)
        assertTrue("the first expectation was the one kept", h.promoted)
        // … and it resolved on K2 (y 1000), not on an older row.
        assertEquals(1620f, h.height, 0f)
    }

    @Test
    fun aQueuedPromptPromotesWithoutAnOwnTurnAndResolvesInPlace() {
        val runway = Runway()
        runway.expectQueued(setOf(K0, K1))
        val h = runway.contentHeight(queuedFrame(), 100f, 800f, 80f)
        assertTrue("promotion is checked before the own-turn guard", h.promoted)
        assertTrue(runway.hasOwnTurn)
        // The promoted turn resolves on K2 in the same call: minimum 1620.
        assertEquals(1620f, h.height, 0f)
        assertEquals(1620f, runway.runwayHeight!!, 0f)
    }

    @Test
    fun beginClearsAPendingQueuedExpectation() {
        val runway = Runway()
        runway.expectQueued(setOf(K0))
        runway.begin(setOf(K0)) // an immediate turn takes over
        // K2 is not in the old queued set, but the expectation is gone:
        // this is the begun turn's own resolution (rule 3), not a
        // promotion.
        val h = runway.contentHeight(queuedFrame(), 100f, 800f, 80f)
        assertFalse(h.promoted)
        assertEquals(1620f, h.height, 0f)
    }

    // ---------------------------------------------------- driver + viewport

    @Test
    fun theWorkedExampleThroughTheDriverPinsThePromptUnderTheHeader() {
        val h = Rig(oldFrame())
        h.apply(oldFrame(), first = true) // landed: content 1100, offset 380
        h.driver.beginOwnTurn() // before = {K0, K1} from the current frame
        h.apply(promptFrame(), first = false)
        assertEquals(1620f, h.viewport.contentHeight, 0f)
        assertTrue(h.viewport.glide)
        assertTrue("R3: the apply started the follow spring", h.viewport.springRunning)
        // The reservation makes the hold the max offset:
        // maxOffset = 1620 + 80 − 800 = 900 = y − topInset.
        assertEquals(900f, h.viewport.maxOffset, 1e-4f)
    }

    @Test
    fun theFirstFrameAfterBeginSnapsStraightToTheHeldPosition() {
        // Ticket 77's new-session arrival: beginOwnTurn() before any
        // frame, so the first frame lands on maxOffset == hold with no
        // glide (the runway height replaces the natural height before
        // 68's first-frame snap).
        val h = Rig(null)
        h.driver.beginOwnTurn() // before = {} — nothing applied yet
        h.apply(promptFrame(), first = true)
        assertEquals(1620f, h.viewport.contentHeight, 0f)
        assertEquals(900f, h.viewport.offset, 1e-4f)
        assertFalse("the snap never starts the spring", h.viewport.springRunning)
        assertTrue(h.viewport.glide)
    }

    @Test
    fun aHeldRunwayGlidesAndARetiredOneSettles() {
        val h = Rig(oldFrame())
        h.apply(oldFrame(), first = true) // content 1100 → offset 380
        h.driver.beginOwnTurn()
        h.apply(promptFrame(), first = false) // holds 1620: maxOffset 900
        assertTrue(h.viewport.springRunning)

        // A 1/60 s tick while holding: the glide step is exactly
        // 1 − 0.85 (68's springTick reads `glide` on every tick).
        val offsetBefore = h.viewport.offset
        val delta = h.viewport.maxOffset - offsetBefore
        assertEquals(520f, delta, 1e-3f)
        assertTrue(h.viewport.springTick(T0 + STEP60))
        assertEquals(0.15f * delta, h.viewport.offset - offsetBefore, 1e-4f)

        // The reply fills the reserved space mid-glide: the same 1/60 s
        // tick now takes 68's settle step (1 − e^(−16/60) = 0.234072).
        h.apply(promptFrame(1619.5f), first = false) // natural 1619.5 → retire
        assertFalse(h.viewport.glide)
        val offsetAfter = h.viewport.offset
        val delta2 = h.viewport.maxOffset - offsetAfter
        assertTrue(h.viewport.springTick(T0 + 2 * STEP60))
        assertEquals(0.234072f * delta2, h.viewport.offset - offsetAfter, 1e-3f)
        assertTrue("the spring keeps chasing the tail", h.viewport.springRunning)
    }

    @Test
    fun theKeyboardRetiresAndReReservesTheRunway() {
        val h = Rig(oldFrame())
        h.viewport.setBottomInset(380f) // the keyboard is up before the turn
        h.driver.beginOwnTurn()
        h.apply(promptFrame(), first = true)
        // minimum = 900 + 800 − 380 = 1320; natural 1100 holds. The snap
        // lands on maxOffset == hold == 900 whatever the insets are.
        assertTrue(h.viewport.glide)
        assertEquals(1320f, h.viewport.contentHeight, 0f)
        assertEquals(900f, h.viewport.offset, 1e-4f)

        // The reply grows to natural 1400: 1400 ≥ 1319.5 → retire; 68's
        // R3 follows the real tail (maxOffset = 1400 + 380 − 800 = 980).
        h.apply(promptFrame(1400f), first = false)
        assertFalse(h.viewport.glide)
        assertEquals(1400f, h.viewport.contentHeight, 0f)
        assertTrue(h.viewport.springRunning)

        // The keyboard goes away: minimum 1620 > 1400 → the same anchor
        // re-reserves, and 68's following pin lands the offset back on
        // the hold — the prompt never moved.
        h.viewport.setBottomInset(80f)
        assertTrue(h.viewport.glide)
        assertEquals(1620f, h.viewport.contentHeight, 0f)
        assertEquals(900f, h.viewport.maxOffset, 1e-4f)
    }

    @Test
    fun aLateViewportHeightReReservesAndPinsTheHold() {
        // Compose can deliver the box size after the first frame was
        // applied (68 R12): with viewportHeight 0 the minimum is tiny, so
        // the first apply retires — but the anchor survives, and the
        // later geometry change re-reserves and pins the offset on the
        // hold. No special case: the rules alone produce this.
        val h = Rig(null, viewportHeight = null) // box size not yet delivered
        h.driver.beginOwnTurn() // before = {} — nothing applied yet
        h.apply(promptFrame(), first = true)
        assertFalse(h.viewport.glide) // tiny minimum: retired on arrival
        assertTrue(h.viewport.following)
        // The box size arrives: minimum 1620 re-reserves, the height is
        // written, and 68's following pin lands on maxOffset == hold.
        h.viewport.setViewportHeight(800f)
        assertTrue(h.viewport.glide)
        assertEquals(1620f, h.viewport.contentHeight, 0f)
        assertEquals(900f, h.viewport.offset, 1e-4f)
    }

    @Test
    fun aBottomInsetChangeWhileHeldPinsThePromptInPlace() {
        // The worked example, then the IME covers 380 dp at the bottom:
        // the geometry hook recomputes the runway height first, then 68's
        // pin lands the offset back on maxOffset == hold (SVC:373-378) —
        // the prompt row does not dip and glide back.
        val h = Rig(null)
        h.driver.beginOwnTurn()
        h.apply(promptFrame(), first = true) // snaps to hold: offset 900
        assertEquals(900f, h.viewport.offset, 1e-4f)

        h.viewport.setBottomInset(380f)
        assertEquals(1320f, h.viewport.contentHeight, 0f) // 900 + 800 − 380
        assertEquals(900f, h.viewport.maxOffset, 1e-4f)
        assertEquals(900f, h.viewport.offset, 1e-4f) // the prompt row did not move
    }

    // ----------------------------------------------------------- refresh

    @Test
    fun refreshDoesNothingWithoutAnOwnTurn() {
        val h = Rig(oldFrame())
        h.apply(oldFrame(), first = true)
        h.viewport.setBottomInset(180f) // a geometry change with no own turn
        assertEquals(1100f, h.viewport.contentHeight, 0f) // untouched
        assertFalse(h.viewport.springRunning)
    }

    @Test
    fun refreshSkipsSubHalfDpChanges() {
        val h = Rig(null)
        h.driver.beginOwnTurn()
        h.apply(promptFrame(), first = true) // holds 1620
        // Park the viewport 0.3 dp under the held height, then refresh:
        // the recompute still runs (the glide flag stays current) but
        // nothing is written and no spring starts.
        h.viewport.setContentHeight(1619.7f)
        h.driver.refresh()
        assertEquals(1619.7f, h.viewport.contentHeight, 0f) // no write
        assertFalse(h.viewport.springRunning)
        assertTrue(h.viewport.glide)
    }

    @Test
    fun refreshWritesUnconditionallyAndSpringsOnlyWhileFollowingAndNotTracking() {
        // Not following: the height is written, the spring is not started.
        val released = Rig(null)
        released.driver.beginOwnTurn()
        released.apply(promptFrame(), first = true)
        released.viewport.releaseFollow()
        released.viewport.setContentHeight(1000f)
        released.driver.refresh()
        assertEquals(1620f, released.viewport.contentHeight, 0f)
        assertFalse(released.viewport.springRunning)

        // A finger down (tracking): written, still no spring.
        val finger = Rig(null)
        finger.driver.beginOwnTurn()
        finger.apply(promptFrame(), first = true)
        finger.viewport.setContentHeight(1000f)
        finger.viewport.onPointerDown()
        finger.driver.refresh()
        assertEquals(1620f, finger.viewport.contentHeight, 0f)
        assertFalse(finger.viewport.springRunning)

        // Following with no finger: written and the spring is armed.
        val live = Rig(null)
        live.driver.beginOwnTurn()
        live.apply(promptFrame(), first = true)
        live.viewport.setContentHeight(1000f)
        assertFalse("the first-frame snap never started it", live.viewport.springRunning)
        live.driver.refresh()
        assertEquals(1620f, live.viewport.contentHeight, 0f)
        assertTrue(live.viewport.springRunning)
    }

    // --------------------------------------------------- follow semantics

    @Test
    fun expectQueuedDoesNotFollowAndBeginDoesNotSpring() {
        val h = Rig(oldFrame())
        h.apply(oldFrame(), first = true)
        h.viewport.releaseFollow()
        assertFalse(h.viewport.following)

        h.driver.expectQueuedTurn() // records only
        assertFalse(h.viewport.following)
        assertFalse(h.viewport.springRunning)

        // An immediate turn takes over: following flips on, the spring
        // stays off (iOS `beginOwnTurn` never starts it — 68's
        // `setFollowing` does not either).
        h.driver.beginOwnTurn()
        assertTrue(h.viewport.following)
        assertFalse(h.viewport.springRunning)
    }

    @Test
    fun aQueuedPromotionFlipsFollowingWithinTheApply() {
        val h = Rig(oldFrame())
        h.apply(oldFrame(), first = true)
        h.viewport.releaseFollow()
        h.driver.expectQueuedTurn() // before = {K0, K1}
        // The queued bubble lands: heightFor promotes it and flips
        // following before 68's R3 runs, so the same apply starts the
        // spring.
        h.apply(queuedFrame(), first = false)
        assertTrue(h.viewport.following)
        assertTrue(h.viewport.springRunning)
        assertTrue(h.viewport.glide)
        assertEquals(1620f, h.viewport.contentHeight, 0f)
    }

    private companion object {
        /** 68's test clock: spring ticks run at [T0] + n·[STEP60]. */
        const val T0 = 1_000_000_000L
        const val STEP60 = 16_666_667L

        /** Two old user rows, the own turn's prompt, a queued prompt, a scan outsider. */
        val K0 = 1uL
        val K1 = 2uL
        val K = 3uL
        val K2 = 4uL
        val NEW = 5uL
    }
}
