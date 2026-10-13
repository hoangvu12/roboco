package sh.roboco.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OpenReveal] — the open state machine (android-ui-parity #75): the begin
 * guard, both reveal orders (rows first and landing first), the give-up
 * and its probe suppression, the no-ops, and [OpenReveal.shouldHold]'s
 * truth table — plus [loaderTopLeft]'s worked-values table. Plain JUnit:
 * the state is snapshot state, which runs anywhere.
 */
class OpenRevealTest {

    // ------------------------------------------------------------- begin

    @Test
    fun beginFalseStaysIdleAndNeverLeavesIt() {
        val reveal = OpenReveal()
        reveal.begin(false)
        assertEquals(OpenReveal.Phase.Idle, reveal.phase)
        assertEquals(1f, reveal.progress, 1e-3f)
        assertFalse(reveal.loaderComposed)
        assertFalse(reveal.settlingEdge)
        assertTrue("the probe's once-flag fires on the first rows", reveal.onFirstRows())
        assertFalse("and never again", reveal.onFirstRows())
        assertEquals(OpenReveal.Phase.Idle, reveal.phase)
        assertEquals(1f, reveal.progress, 1e-3f)
    }

    @Test
    fun aSecondBeginIsIgnored() {
        val reveal = OpenReveal()
        reveal.begin(false)
        reveal.begin(true)
        assertEquals("begin is once-only", OpenReveal.Phase.Idle, reveal.phase)
        assertEquals(1f, reveal.progress, 1e-3f)
    }

    // ------------------------------------------------------------- hold

    @Test
    fun beginTrueHoldsWithTheLoaderComposed() {
        val reveal = OpenReveal()
        reveal.begin(true)
        assertEquals(OpenReveal.Phase.Holding, reveal.phase)
        assertEquals(0f, reveal.progress, 1e-3f)
        assertEquals(0f, reveal.transcriptAlpha, 1e-3f)
        assertEquals(1f, reveal.loaderAlpha, 1e-3f)
        assertTrue(reveal.loaderComposed)
        assertTrue(reveal.settlingEdge)
        assertFalse(reveal.landed)
        assertFalse(reveal.reportedOpen)
    }

    @Test
    fun rowsFirstThenLandingReveals() {
        val reveal = OpenReveal()
        reveal.begin(true)
        assertTrue(reveal.onFirstRows())
        assertEquals("rows alone do not reveal (the push has not landed)", OpenReveal.Phase.Holding, reveal.phase)
        reveal.onLanded()
        assertEquals(OpenReveal.Phase.Revealing, reveal.phase)
        assertTrue(reveal.landed)
        assertTrue(reveal.reportedOpen)
    }

    @Test
    fun landingFirstThenRowsReveals() {
        val reveal = OpenReveal()
        reveal.begin(true)
        reveal.onLanded()
        assertEquals("landing alone does not reveal (no rows yet)", OpenReveal.Phase.Holding, reveal.phase)
        assertTrue(reveal.landed)
        assertTrue(reveal.onFirstRows())
        assertEquals(OpenReveal.Phase.Revealing, reveal.phase)
    }

    // ---------------------------------------------------------- give-up

    @Test
    fun theGiveUpRevealsAndSuppressesTheProbe() {
        val reveal = OpenReveal()
        reveal.begin(true)
        reveal.onLanded()
        reveal.giveUp()
        assertEquals(OpenReveal.Phase.Revealing, reveal.phase)
        assertTrue(reveal.reportedOpen)
        assertFalse("a give-up consumed the probe's once-flag", reveal.onFirstRows())
    }

    @Test
    fun giveUpIsANoOpOutsideHolding() {
        val idle = OpenReveal()
        idle.begin(false)
        idle.giveUp()
        assertEquals(OpenReveal.Phase.Idle, idle.phase)
        assertFalse(idle.reportedOpen)
        assertTrue("the no-op did not consume the probe", idle.onFirstRows())

        val revealing = OpenReveal()
        revealing.begin(true)
        revealing.onLanded()
        revealing.onFirstRows()
        revealing.giveUp()
        assertEquals("a reveal already running is not re-triggered", OpenReveal.Phase.Revealing, revealing.phase)

        val shown = OpenReveal()
        shown.begin(true)
        shown.onLanded()
        shown.onFirstRows()
        shown.finishReveal()
        shown.giveUp()
        assertEquals(OpenReveal.Phase.Shown, shown.phase)
        assertEquals(1f, shown.progress, 1e-3f)
    }

    // ---------------------------------------------------- finishReveal

    @Test
    fun finishRevealLandsBothAlphas() {
        val reveal = OpenReveal()
        reveal.begin(true)
        reveal.onLanded()
        reveal.onFirstRows()
        reveal.finishReveal()
        assertEquals(OpenReveal.Phase.Shown, reveal.phase)
        assertEquals(1f, reveal.progress, 1e-3f)
        assertEquals(1f, reveal.transcriptAlpha, 1e-3f)
        assertEquals(0f, reveal.loaderAlpha, 1e-3f)
        assertFalse(reveal.loaderComposed)
        assertFalse(reveal.settlingEdge)
    }

    // --------------------------------------------------------- shouldHold

    @Test
    fun shouldHoldRequiresAnAnimatedFirstUnpreparedPush() {
        for (animated in listOf(true, false)) {
            for (hasAppeared in listOf(true, false)) {
                for (arrivalPrepared in listOf(true, false)) {
                    val expected = animated && !hasAppeared && !arrivalPrepared
                    assertEquals(
                        "shouldHold($animated, $hasAppeared, $arrivalPrepared)",
                        expected,
                        OpenReveal.shouldHold(animated, hasAppeared, arrivalPrepared),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------ loaderTopLeft

    @Test
    fun loaderTopLeftMatchesTheWorkedValuesTable() {
        // iPhone 16 Pro reference, 402 × 874, 62 / 34.
        val iphone = loaderTopLeft(width = 402f, height = 874f, statusBarTop = 62f, navBarBottom = 34f)
        assertEquals(190.5f, iphone.x, 0.01f)
        assertEquals(422.5f, iphone.y, 0.01f)

        // The test fixture, 412 × 900, 24 / 0.
        val fixture = loaderTopLeft(width = 412f, height = 900f, statusBarTop = 24f, navBarBottom = 0f)
        assertEquals(195.5f, fixture.x, 0.01f)
        assertEquals(433.5f, fixture.y, 0.01f)

        // Pixel, gesture nav, 412 × 915, 24 / 24.
        val gesture = loaderTopLeft(width = 412f, height = 915f, statusBarTop = 24f, navBarBottom = 24f)
        assertEquals(195.5f, gesture.x, 0.01f)
        assertEquals(429.0f, gesture.y, 0.01f)

        // Pixel, 3-button nav, 412 × 915, 24 / 48.
        val buttons = loaderTopLeft(width = 412f, height = 915f, statusBarTop = 24f, navBarBottom = 48f)
        assertEquals(195.5f, buttons.x, 0.01f)
        assertEquals(417.0f, buttons.y, 0.01f)
    }
}
