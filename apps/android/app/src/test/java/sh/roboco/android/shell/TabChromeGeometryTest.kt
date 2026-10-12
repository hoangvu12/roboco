package sh.roboco.android.shell

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [tabChromeGeometry] — android-ui-parity #28's acceptance criteria: every
 * cell of the ticket's worked table (W 402, H 874, B 22) within 0.01 dp,
 * including the no-accessory rows, the W = 360 / W = 600 rows, an
 * `insetStart = 24` case, and the unclamped overshoot past p = 1; plus the
 * pins that [TabChromeMetrics] equals ticket 26's `GlassTabBarDefaults` and
 * 27's `NewSessionAccessoryMetrics.Height`, so the pure geometry and the
 * Dp constants can never drift.
 */
class TabChromeGeometryTest {

    private fun assertRect(what: String, rect: Rect, left: Float, top: Float, right: Float, bottom: Float) {
        assertEquals("$what left", left, rect.left, 0.01f)
        assertEquals("$what top", top, rect.top, 0.01f)
        assertEquals("$what right", right, rect.right, 0.01f)
        assertEquals("$what bottom", bottom, rect.bottom, 0.01f)
    }

    // ------------------------------------------------------ worked table

    @Test
    fun expandedMatchesTheWorkedTable() {
        val rects = tabChromeGeometry(402f, 874f, 22f, hasAccessory = true, progress = 0f)
        assertRect("pill", rects.pill, 21f, 790f, 311f, 852f)
        assertRect("search", rects.search, 319f, 790f, 381f, 852f)
        assertRect("accessory", rects.accessory!!, 21f, 734f, 381f, 782f)
        assertEquals(734f, rects.chromeTop, 0.01f)
    }

    @Test
    fun halfWayMatchesTheWorkedTable() {
        val rects = tabChromeGeometry(402f, 874f, 22f, hasAccessory = true, progress = 0.5f)
        assertRect("pill", rects.pill, 21f, 797f, 190f, 852f)
        assertRect("search", rects.search, 326f, 797f, 381f, 852f)
        assertRect("accessory", rects.accessory!!, 49f, 769f, 353f, 817f)
        assertEquals(769f, rects.chromeTop, 0.01f)
    }

    @Test
    fun minimizedMatchesTheWorkedTable() {
        val rects = tabChromeGeometry(402f, 874f, 22f, hasAccessory = true, progress = 1f)
        assertRect("pill", rects.pill, 21f, 804f, 69f, 852f)
        assertRect("search", rects.search, 333f, 804f, 381f, 852f)
        assertRect("accessory", rects.accessory!!, 77f, 804f, 325f, 852f)
        // All three minimized pieces share the bottom edge B = 852.
        assertEquals(852f, rects.accessory!!.bottom, 0.01f)
        assertEquals(734f + 70f, 804f, 0.01f) // sanity: the accessory moved down 70
        assertEquals(804f, rects.chromeTop, 0.01f)
    }

    @Test
    fun noAccessoryRowsMatchTheWorkedTable() {
        val expanded = tabChromeGeometry(402f, 874f, 22f, hasAccessory = false, progress = 0f)
        assertRect("pill", expanded.pill, 21f, 790f, 311f, 852f)
        assertRect("search", expanded.search, 319f, 790f, 381f, 852f)
        assertNull(expanded.accessory)
        assertEquals(790f, expanded.chromeTop, 0.01f)

        val minimized = tabChromeGeometry(402f, 874f, 22f, hasAccessory = false, progress = 1f)
        assertRect("pill", minimized.pill, 21f, 804f, 69f, 852f)
        assertRect("search", minimized.search, 333f, 804f, 381f, 852f)
        assertNull(minimized.accessory)
        assertEquals(804f, minimized.chromeTop, 0.01f)
    }

    // ----------------------------------------------------- width frames

    @Test
    fun narrowPhone360InlineAccessoryIs206Wide() {
        // W = 360: rowW = 318, inline accessory width 318 − 112 = 206.
        val rects = tabChromeGeometry(360f, 874f, 22f, hasAccessory = true, progress = 1f)
        assertRect("pill", rects.pill, 21f, 804f, 69f, 852f)
        assertRect("accessory", rects.accessory!!, 77f, 804f, 283f, 852f)
        assertEquals(206f, rects.accessory!!.width, 0.01f)
    }

    @Test
    fun wideScreenCapsTheRowAt398AndCentresIt() {
        // W = 600: rowW = 398 (capped), L = 101, R = 499 — so the pill is
        // 328 wide (398 − 70) and the search's right edge sits at R = 499.
        val rects = tabChromeGeometry(600f, 874f, 22f, hasAccessory = true, progress = 0f)
        assertRect("pill", rects.pill, 101f, 790f, 429f, 852f)
        assertRect("search", rects.search, 437f, 790f, 499f, 852f)
        assertRect("accessory", rects.accessory!!, 101f, 734f, 499f, 782f)
    }

    @Test
    fun aSideInsetShiftsAndCentresTheRow() {
        // insetStart = 24 at W = 402: available = 378, rowW = 336, L = 45, R = 381.
        val rects = tabChromeGeometry(
            402f, 874f, 22f, hasAccessory = true, progress = 0f,
            insetStart = 24f, insetEnd = 0f,
        )
        assertRect("pill", rects.pill, 45f, 790f, 311f, 852f)
        assertRect("search", rects.search, 319f, 790f, 381f, 852f)
        assertRect("accessory", rects.accessory!!, 45f, 734f, 381f, 782f)
        // The minimized circle stays at the row's start: x 45.
        val minimized = tabChromeGeometry(
            402f, 874f, 22f, hasAccessory = true, progress = 1f,
            insetStart = 24f, insetEnd = 0f,
        )
        assertRect("pill", minimized.pill, 45f, 804f, 93f, 852f)
    }

    // -------------------------------------------------------- overshoot

    @Test
    fun theOvershootPastOneKeepsShrinkingThePill() {
        // p = 1.0063 (the spring's peak): the pill is narrower than 48 by
        // 0.0063 × 242 ≈ 1.52 dp, i.e. ≈ 46.5 — geometry never clamps.
        val rects = tabChromeGeometry(402f, 874f, 22f, hasAccessory = true, progress = 1.0063f)
        val width = rects.pill.width
        assertTrue("pill width at the peak: $width", width < 48f && width > 46.4f)
        assertEquals(46.48f, width, 0.02f)
        assertTrue("chromeTop past the peak: ${rects.chromeTop}", rects.chromeTop > 804f)
    }

    @Test
    fun aNegativeOvershootGrowsThePillSymmetrically() {
        // p = −0.02 (an interrupted expand): the pill is 2 % past expanded.
        val rects = tabChromeGeometry(402f, 874f, 22f, hasAccessory = true, progress = -0.02f)
        assertEquals(290f + 0.02f * 242f, rects.pill.width, 0.01f)
        assertEquals(62f + 0.02f * 14f, rects.pill.height, 0.01f)
    }

    // ------------------------------------------------- the shared constants

    @Test
    fun metricsMatchGlassTabBarDefaults() {
        // Ticket 26's Dp constants and 28's Float ones are the same numbers:
        // the pure geometry and the composables can never drift.
        assertEquals(GlassTabBarDefaults.SideMargin.value, TabChromeMetrics.SideMargin, 0f)
        assertEquals(GlassTabBarDefaults.MaxRowWidth.value, TabChromeMetrics.MaxRowWidth, 0f)
        assertEquals(GlassTabBarDefaults.PillHeight.value, TabChromeMetrics.PillHeight, 0f)
        assertEquals(GlassTabBarDefaults.SearchSize.value, TabChromeMetrics.SearchExpanded, 0f)
        assertEquals(GlassTabBarDefaults.Gap.value, TabChromeMetrics.Gap, 0f)
        assertEquals(GlassTabBarDefaults.IconBox.value, TabChromeMetrics.IconSize, 0f)
        assertEquals(GlassTabBarDefaults.PlatterInset.value, TabChromeMetrics.PlatterInset, 0f)
        assertEquals(
            (GlassTabBarDefaults.IconTop + GlassTabBarDefaults.IconBox / 2).value,
            TabChromeMetrics.IconCentreY,
            0f,
        )
    }

    @Test
    fun metricsMatchTheAccessoryHeight() {
        assertEquals(NewSessionAccessoryMetrics.Height.value, TabChromeMetrics.AccessoryHeight, 0f)
    }

    @Test
    fun theExpandedPillWidthMatchesTheRowGeometry() {
        // tabPillExpandedWidth is the geometry's p = 0 pill width: 290 at
        // 402 (row 360), 248 at 360 (row 318), 328 at 600 (capped 398).
        assertEquals(290f, tabPillExpandedWidth(402f), 0.01f)
        assertEquals(248f, tabPillExpandedWidth(360f), 0.01f)
        assertEquals(328f, tabPillExpandedWidth(600f), 0.01f)
        // …and the geometry agrees at every width it is asked for.
        for (width in listOf(320f, 360f, 402f, 440f, 600f)) {
            val rects = tabChromeGeometry(width, 874f, 22f, hasAccessory = true, progress = 0f)
            assertEquals(
                "expanded pill width at W=$width",
                tabPillExpandedWidth(width),
                rects.pill.width,
                0.01f,
            )
        }
    }
}
