package sh.roboco.android.transcript

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.Scroller
import uniffi.roboco_core.Widget
import uniffi.roboco_core.WidgetKind

/**
 * The "not on a control" hit test (android-ui-parity #128), plain JVM —
 * [isOverControl] is pure over generated records, which construct cleanly
 * without JNA or android.os (the [RowFixtures] reasoning).
 *
 * Edges are left/top-inclusive and right/bottom-exclusive, no slop; a
 * widget in a scroller is hit through the viewport translation, and an
 * out-of-range `scroller` index falls back to row coordinates.
 */
class RowMenuControlHitTest {

    private val noScroll = { _: Int -> 0f }

    private fun display(widgets: List<Widget>, scrollers: List<Scroller> = emptyList()): RowDisplay = RowDisplay(
        key = 1uL,
        version = 1uL,
        width = 411f,
        height = 112f,
        text = "",
        runs = emptyList(),
        boxes = emptyList(),
        links = emptyList(),
        scrollers = scrollers,
        widgets = widgets,
        fades = emptyList(),
        copyText = "",
    )

    private fun widget(
        kind: WidgetKind,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        scroller: UInt? = null,
    ): Widget = Widget(id = 1u, kind = kind, x = x, y = y, w = w, h = h, payload = null, scroller = scroller)

    // ------------------------------------------------------------- no scroller

    @Test
    fun aCopyCodeWidgetClaimsItsRectInRowCoordinates() {
        val d = display(listOf(widget(WidgetKind.CopyCode, 300f, 10f, 28f, 28f)))
        assertTrue(isOverControl(d, 314f, 24f, noScroll))
        assertFalse("right edge exclusive", isOverControl(d, 328f, 24f, noScroll))
        assertFalse("left of the widget", isOverControl(d, 100f, 24f, noScroll))
        assertTrue("top edge inclusive", isOverControl(d, 300f, 10f, noScroll))
        assertFalse("bottom edge exclusive", isOverControl(d, 314f, 38f, noScroll))
    }

    @Test
    fun everyOtherControlKindClaimsItsRectToo() {
        // CopyCode, Disclosure, ToolToggle, Detail and Image are controls.
        val kinds = listOf(
            WidgetKind.CopyCode,
            WidgetKind.Disclosure(expanded = true),
            WidgetKind.ToolToggle(1uL, open = false),
            WidgetKind.Detail("Bash"),
            WidgetKind.Image("ref"),
            WidgetKind.Action("sign-in", "Log in"),
        )
        for (k in kinds) {
            val d = display(listOf(widget(k, 0f, 0f, 20f, 20f)))
            assertTrue("a $k control claims its rect", isOverControl(d, 10f, 10f, noScroll))
        }
    }

    @Test
    fun displayOnlyWidgetsNeverClaimTheTouch() {
        // Icon, ToolStatus, Spinner, Working, Chevron, ToolRail, Shimmer:
        // `isUserInteractionEnabled = false` on iOS — the row lifts over them.
        val kinds = listOf(
            WidgetKind.Shimmer,
            WidgetKind.Spinner,
            WidgetKind.ToolStatus(running = true, failed = false),
            WidgetKind.Working(sinceMs = null, streaming = true),
            WidgetKind.Chevron(expanded = false),
            WidgetKind.Icon("check", ColorRole.TEXT),
            WidgetKind.ToolRail(
                trunkX = 10f,
                bend = 4f,
                branchEnd = 20f,
                rowMid = 10f,
                tops = listOf(0f),
                heights = listOf(20f),
            ),
        )
        for (k in kinds) {
            val d = display(listOf(widget(k, 0f, 0f, 411f, 112f)))
            assertFalse("display-only $k never claims", isOverControl(d, 200f, 50f, noScroll))
            assertFalse(isOverControl(d, 5f, 5f, noScroll))
        }
    }

    @Test
    fun negativeSizesClaimNothing() {
        val d = display(listOf(widget(WidgetKind.CopyCode, 10f, 10f, -5f, -5f)))
        assertFalse(isOverControl(d, 10f, 10f, noScroll))
        assertFalse(isOverControl(d, 8f, 8f, noScroll))
    }

    // ------------------------------------------------------------- scrollers

    @Test
    fun aScrolledControlIsHitThroughTheViewportTranslation() {
        val d = display(
            widgets = listOf(widget(WidgetKind.Disclosure(expanded = true), 500f, 0f, 20f, 20f, scroller = 0u)),
            scrollers = listOf(Scroller(16f, 40f, 300f, 100f, 520f)),
        )
        // Offset 250: the widget's row rect is [266, 286) x [40, 60), inside
        // the viewport [16, 316) x [40, 140); the touch at (271, 45) hits it.
        assertTrue(isOverControl(d, 271f, 45f, { if (it == 0) 250f else 0f }))
        // Offset 0: the widget sits at row x [516, 536), past the viewport's
        // right edge 316 — the same point misses it.
        assertFalse(isOverControl(d, 271f, 45f, { 0f }))
        // Outside the viewport: no offset can claim it.
        assertFalse(isOverControl(d, 10f, 45f, { if (it == 0) 250f else 0f }))
        assertFalse(isOverControl(d, 10f, 45f, { 0f }))
        // The widget's own edges in content coordinates.
        assertTrue("left/top inclusive", isOverControl(d, 266f, 40f, { if (it == 0) 250f else 0f }))
        assertFalse("right edge exclusive", isOverControl(d, 286f, 45f, { if (it == 0) 250f else 0f }))
        assertFalse("bottom edge exclusive", isOverControl(d, 271f, 60f, { if (it == 0) 250f else 0f }))
        // The viewport's own right edge: outside it, even though the widget
        // is scrolled in range.
        assertFalse(isOverControl(d, 316f, 45f, { if (it == 0) 250f else 0f }))
    }

    @Test
    fun theSameWidgetWithoutAScrollerSitsInRowCoordinates() {
        // The same widget with no scroller sits in row coordinates at
        // (500, 0): nothing masks it, so the row-x point 505 hits it.
        val d = display(listOf(widget(WidgetKind.Disclosure(expanded = true), 500f, 0f, 20f, 20f)))
        assertTrue(isOverControl(d, 505f, 5f, noScroll))
        assertFalse(isOverControl(d, 485f, 5f, noScroll))
    }

    @Test
    fun anOutOfRangeScrollerIndexFallsBackToRowCoordinates() {
        val d = display(
            widgets = listOf(widget(WidgetKind.CopyCode, 10f, 10f, 20f, 20f, scroller = 5u)),
            scrollers = listOf(Scroller(0f, 0f, 100f, 100f, 100f)),
        )
        assertTrue(isOverControl(d, 15f, 15f, { 250f }))
        assertTrue("top-left inclusive", isOverControl(d, 10f, 10f, { 250f }))
        assertFalse("right edge exclusive", isOverControl(d, 35f, 15f, { 250f }))
        assertFalse("bottom edge exclusive", isOverControl(d, 15f, 35f, { 250f }))
    }

    @Test
    fun theFirstControlHitWinsAmongManyWidgets() {
        val d = display(
            listOf(
                widget(WidgetKind.Shimmer, 0f, 0f, 411f, 112f),
                widget(WidgetKind.CopyCode, 300f, 10f, 28f, 28f),
                widget(WidgetKind.Detail("Bash"), 300f, 10f, 28f, 28f),
            ),
        )
        assertTrue(isOverControl(d, 314f, 24f, noScroll))
        assertFalse(isOverControl(d, 100f, 24f, noScroll))
    }
}
