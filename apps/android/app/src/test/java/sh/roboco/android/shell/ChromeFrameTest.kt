package sh.roboco.android.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 30's pure functions, plain JUnit: [chromeFrame] and
 * [accessoryRevealAlpha] against the Spec's worked-values table (W = 400
 * px, LTR unless stated), plus the idle / derivation rows through a real
 * [ChromeVisibilityController] over a real [NavStack] (24's
 * `holdHostlessOps` + `freeze` make the transitions inspectable without a
 * host).
 */
class ChromeFrameTest {

    /** A route that never hides the chrome (26's roots, folder pages). */
    private data class PlainRoute(val title: String) : NavRoute {
        override val paneTitle: String? get() = title
    }

    /** A route that hides it (76's Session route). */
    private data class HidingRoute(val title: String = "Session") : NavRoute {
        override val hidesBottomChrome: Boolean get() = true
        override val paneTitle: String? get() = title
    }

    private fun controller(): ChromeVisibilityController =
        ChromeVisibilityController(MainTabState(TabId.Sessions), null, CoroutineScope(Dispatchers.Unconfined))

    /** The frame of a RidesBelow transition (over a Session) at [p]. */
    private fun rides(kind: NavKind, p: Float, rtl: Boolean = false, reduce: Boolean = false): ChromeFrame =
        chromeFrame(
            ChromeLayer.RidesBelow,
            navLayerTransforms(kind, p, 400f, rtl, reduce),
            400f,
            rtl,
            reduce,
        )

    // -------------------------------------------------- the worked table (W = 400)

    @Test
    fun pushToASessionAtHalfRidesBelowCoveredAndDimmed() {
        val x = navLayerTransforms(NavKind.Push, 0.5f, 400f, false, false)
        assertEquals(-60f, x.belowTx, 1e-3f)
        assertEquals(200f, x.aboveTx, 1e-3f)
        val f = rides(NavKind.Push, 0.5f)
        assertTrue(f.placed)
        assertEquals(-60f, f.translationX, 1e-3f)
        assertEquals(1f, f.alpha, 1e-3f)
        assertEquals(0f, f.clipLeft, 1e-3f)
        assertEquals(200f, f.clipRight, 1e-3f)
        assertEquals(0.05f, f.dim, 1e-3f)
    }

    @Test
    fun pushToASessionAtOneIsFullyCovered() {
        val x = navLayerTransforms(NavKind.Push, 1.0f, 400f, false, false)
        assertEquals(-120f, x.belowTx, 1e-3f)
        assertEquals(0f, x.aboveTx, 1e-3f)
        val f = rides(NavKind.Push, 1.0f)
        assertTrue(f.placed)
        assertEquals(-120f, f.translationX, 1e-3f)
        // The clip collapses to 0..0: nothing of the group is drawn.
        assertEquals(0f, f.clipLeft, 1e-3f)
        assertEquals(0f, f.clipRight, 1e-3f)
        assertEquals(0.10f, f.dim, 1e-3f)
    }

    @Test
    fun popFromASessionAtQuarterRidesBelowRevealing() {
        val x = navLayerTransforms(NavKind.Pop, 0.25f, 400f, false, false)
        assertEquals(-90f, x.belowTx, 1e-3f)
        assertEquals(100f, x.aboveTx, 1e-3f)
        val f = rides(NavKind.Pop, 0.25f)
        assertTrue(f.placed)
        assertEquals(-90f, f.translationX, 1e-3f)
        assertEquals(0f, f.clipLeft, 1e-3f)
        assertEquals(100f, f.clipRight, 1e-3f)
        assertEquals(0.075f, f.dim, 1e-3f)
    }

    @Test
    fun popFromASessionAtOneIsFullyShown() {
        val x = navLayerTransforms(NavKind.Pop, 1.0f, 400f, false, false)
        assertEquals(0f, x.belowTx, 1e-3f)
        assertEquals(400f, x.aboveTx, 1e-3f)
        val f = rides(NavKind.Pop, 1.0f)
        assertTrue(f.placed)
        assertEquals(0f, f.translationX, 1e-3f)
        assertEquals(0f, f.clipLeft, 1e-3f)
        assertEquals(400f, f.clipRight, 1e-3f)
        assertEquals(0f, f.dim, 1e-3f)
    }

    @Test
    fun rtlPushClipsAtTheTrailingSide() {
        val x = navLayerTransforms(NavKind.Push, 0.5f, 400f, true, false)
        assertEquals(60f, x.belowTx, 1e-3f)
        assertEquals(-200f, x.aboveTx, 1e-3f)
        val f = rides(NavKind.Push, 0.5f, rtl = true)
        assertTrue(f.placed)
        assertEquals(60f, f.translationX, 1e-3f)
        assertEquals(200f, f.clipLeft, 1e-3f)
        assertEquals(400f, f.clipRight, 1e-3f)
        assertEquals(0.05f, f.dim, 1e-3f)
    }

    @Test
    fun reducedMotionCrossFadesInsteadOfRiding() {
        val push = rides(NavKind.Push, 0.5f, reduce = true)
        assertTrue(push.placed)
        assertEquals(0f, push.translationX, 1e-3f)
        assertEquals(0.5f, push.alpha, 1e-3f)
        assertTrue(push.clipLeft.isInfinite())
        assertTrue(push.clipRight.isInfinite())
        assertEquals(0f, push.dim, 1e-3f)

        val pop = rides(NavKind.Pop, 0.25f, reduce = true)
        assertTrue(pop.placed)
        assertEquals(0.25f, pop.alpha, 1e-3f)
        assertTrue(pop.clipLeft.isInfinite())
        assertEquals(0f, pop.dim, 1e-3f)
    }

    // ----------------------------------------------------------- layer rows

    @Test
    fun fixedPlacesTheGroupWithNoClipOrDim() {
        val f = chromeFrame(
            ChromeLayer.Fixed,
            navLayerTransforms(NavKind.Push, 0.5f, 400f, false, false),
            400f,
            false,
            false,
        )
        assertEquals(ChromeFrame.Shown, f)
        assertTrue(f.placed)
        assertTrue(f.clipLeft.isInfinite())
        assertTrue(f.clipRight.isInfinite())
        assertEquals(0f, f.dim, 1e-3f)
    }

    @Test
    fun hiddenDoesNotPlaceTheGroup() {
        val f = chromeFrame(
            ChromeLayer.Hidden,
            navLayerTransforms(NavKind.Push, 0.5f, 400f, false, false),
            400f,
            false,
            false,
        )
        assertEquals(ChromeFrame.Hidden, f)
        assertFalse(f.placed)
    }

    @Test
    fun ridesAboveFollowsTheIncomingScreen() {
        val x = navLayerTransforms(NavKind.Push, 0.5f, 400f, false, false)
        val f = chromeFrame(ChromeLayer.RidesAbove, x, 400f, false, false)
        assertTrue(f.placed)
        assertEquals(x.aboveTx, f.translationX, 1e-3f)
        assertEquals(x.aboveAlpha, f.alpha, 1e-3f)
        assertTrue(f.clipLeft.isInfinite())
        assertEquals(0f, f.dim, 1e-3f)

        // Reduced motion: the group fades in with the incoming screen.
        val xr = navLayerTransforms(NavKind.Push, 0.5f, 400f, false, true)
        val fr = chromeFrame(ChromeLayer.RidesAbove, xr, 400f, false, true)
        assertEquals(0.5f, fr.alpha, 1e-3f)
    }

    // --------------------------------------------- controller idle + transitions

    @Test
    fun idleTopShowingChromePlacesTheGroup() {
        val c = controller()
        assertEquals(ChromeFrame.Shown, c.frame())
    }

    @Test
    fun idleTopHidingChromeHidesTheGroup() {
        val c = controller()
        c.stack.push(HidingRoute(), animated = false)
        assertEquals(ChromeFrame.Hidden, c.frame())
    }

    @Test
    fun aControllerDerivesRidesBelowForAPushToASession() {
        val c = controller()
        c.widthPx = 400f
        val s = c.stack
        s.holdHostlessOps()
        s.push(HidingRoute())
        s.freeze(0.5f)
        val f = c.frame()
        assertTrue(f.placed)
        assertEquals(-60f, f.translationX, 1e-3f)
        assertEquals(0f, f.clipLeft, 1e-3f)
        assertEquals(200f, f.clipRight, 1e-3f)
        assertEquals(0.05f, f.dim, 1e-3f)
    }

    @Test
    fun aControllerDerivesRidesAboveForAPushFromASession() {
        val c = controller()
        c.widthPx = 400f
        val s = c.stack
        s.push(HidingRoute()) // completes hostlessly: the session is the top
        s.holdHostlessOps()
        s.push(PlainRoute("Folder")) // from hides, to shows: RidesAbove
        s.freeze(0.5f)
        val f = c.frame()
        assertTrue(f.placed)
        assertEquals(200f, f.translationX, 1e-3f)
        assertEquals(1f, f.alpha, 1e-3f)
        assertTrue(f.clipLeft.isInfinite())
        assertEquals(0f, f.dim, 1e-3f)
    }

    @Test
    fun aControllerHidesTheGroupWhenNeitherLayerShowsIt() {
        // A transition between two hiding routes (session over session —
        // the depth-2 case): neither end shows the chrome.
        val c = controller()
        c.widthPx = 400f
        val s = c.stack
        s.holdHostlessOps()
        s.push(HidingRoute())
        s.push(HidingRoute("Session 2"))
        s.freeze(0.5f)
        assertEquals(ChromeFrame.Hidden, c.frame())
    }

    // ------------------------------------------------------ accessory reveal

    @Test
    fun theRevealFadesTheContentWithThePopProgress() {
        assertEquals(0.25f, accessoryRevealAlpha(fromShows = false, toShows = true, progress = 0.25f, reduceMotion = false), 1e-4f)
        assertEquals(1f, accessoryRevealAlpha(fromShows = false, toShows = true, progress = 0.25f, reduceMotion = true), 1e-4f)
        assertEquals(1f, accessoryRevealAlpha(fromShows = true, toShows = false, progress = 0.25f, reduceMotion = false), 1e-4f)
        assertEquals(1f, accessoryRevealAlpha(fromShows = true, toShows = true, progress = 0.25f, reduceMotion = false), 1e-4f)
        // Unclamped progress clamps to 0..1.
        assertEquals(1f, accessoryRevealAlpha(false, true, 1.5f, false), 1e-4f)
        assertEquals(0f, accessoryRevealAlpha(false, true, -0.2f, false), 1e-4f)
    }

    @Test
    fun aPushToASessionLeavesTheContentAlphaAtOne() {
        val c = controller()
        val s = c.stack
        s.holdHostlessOps()
        s.push(HidingRoute())
        s.freeze(0.5f)
        assertEquals(1f, c.accessoryContentAlpha(), 1e-4f)
    }

    @Test
    fun aPopFromASessionRevealsTheContentWithProgress() {
        val c = controller()
        val s = c.stack
        s.push(HidingRoute()) // completes hostlessly
        s.holdHostlessOps()
        s.pop()
        s.freeze(0.25f)
        assertEquals(0.25f, c.accessoryContentAlpha(), 1e-4f)
    }

    @Test
    fun theRevealStaysOneUnderReducedMotion() {
        val c = controller()
        c.reduceMotion = true
        val s = c.stack
        s.push(HidingRoute())
        s.holdHostlessOps()
        s.pop()
        s.freeze(0.25f)
        assertEquals(1f, c.accessoryContentAlpha(), 1e-4f)
    }
}
