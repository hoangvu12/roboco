package sh.roboco.android.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The split pane geometry (android-ui-parity #33), pure px math from the
 * ticket's Verification list. Plain JUnit: no Compose, no Robolectric.
 *
 * The four values per case: `sidebarX` (the pane's leading edge),
 * `paneX`/`paneWidth` (the detail pane's visible window — the only part
 * that resizes per animation frame) and `contentWidth` (the width the
 * detail content was measured at — the **target** width, never
 * per-frame).
 */
class SplitGeometryTest {

    @Test
    fun shownFullWidth() {
        // A 1280 dp window at density 2.0 with the 360 dp sidebar at
        // fraction 1: the sidebar at 0, the pane beside it.
        val g = splitGeometry(totalPx = 2560, sidebarPx = 720, fraction = 1f, targetVisible = true)
        assertEquals(0, g.sidebarX)
        assertEquals(720, g.paneX)
        assertEquals(1840, g.paneWidth)
        assertEquals(1840, g.contentWidth)
    }

    @Test
    fun hiddenFullWidth() {
        val g = splitGeometry(totalPx = 2560, sidebarPx = 720, fraction = 0f, targetVisible = false)
        assertEquals(-720, g.sidebarX)
        assertEquals(0, g.paneX)
        assertEquals(2560, g.paneWidth)
        assertEquals(2560, g.contentWidth)
    }

    @Test
    fun midSlideHiddenTarget() {
        // Hiding, halfway: the content is already at the hidden target
        // width (W) while the pane still covers only part of it.
        val g = splitGeometry(totalPx = 2560, sidebarPx = 720, fraction = 0.5f, targetVisible = false)
        assertEquals(-360, g.sidebarX)
        assertEquals(360, g.paneX)
        assertEquals(2200, g.paneWidth)
        assertEquals(2560, g.contentWidth)
    }

    @Test
    fun midSlideShownTargetKeepsContentAtSidebarWidth() {
        // Showing, halfway: the content was measured once at the shown
        // target width (W − S) and slides into place; the pane background
        // fills the strip it doesn't cover yet.
        val g = splitGeometry(totalPx = 2560, sidebarPx = 720, fraction = 0.5f, targetVisible = true)
        assertEquals(1840, g.contentWidth)
    }

    @Test
    fun sidebarClampsToTheWindow() {
        // A window narrower than the sidebar (a tiny preview, or exactly
        // 840 dp with the sidebar minimum): the sidebar clamps to the
        // window, the detail collapses to zero — no crash, no overflow.
        val g = splitGeometry(totalPx = 300, sidebarPx = 720, fraction = 1f, targetVisible = true)
        assertEquals(0, g.sidebarX)
        assertEquals(0, g.contentWidth)
    }

    @Test
    fun fractionIsClamped() {
        // A stray fraction outside 0..1 can't push the panes off-model.
        val g = splitGeometry(totalPx = 2560, sidebarPx = 720, fraction = 2f, targetVisible = true)
        assertEquals(0, g.sidebarX)
        assertEquals(720, g.paneX)
        assertEquals(1840, g.paneWidth)
        val h = splitGeometry(totalPx = 2560, sidebarPx = 720, fraction = -1f, targetVisible = false)
        assertEquals(-720, h.sidebarX)
        assertEquals(0, h.paneX)
    }
}
