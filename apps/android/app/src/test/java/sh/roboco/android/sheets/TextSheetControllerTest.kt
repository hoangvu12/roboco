package sh.roboco.android.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextSheetController]'s state machine (ticket 127) — plain JVM, no
 * Robolectric: snapshot state is a plain library. The ticket's list:
 * `show` returns `true` when idle and `false` while a request is current;
 * `onDismissed` clears it; `selectText("x")` produces
 * `TextSheetRequest("x", "Select Text", false)` and
 * `showDetail("Bash", "y")` produces `TextSheetRequest("y", "Bash", true)`.
 */
class TextSheetControllerTest {

    @Test
    fun showReturnsTrueWhenIdleAndFalseWhileARequestIsCurrent() {
        val controller = TextSheetController()
        assertNull(controller.current)

        assertTrue(controller.show(TextSheetRequest("a", "A", mono = false)))
        // One at a time: every later request is ignored, including during
        // the dismiss animation (onDismissed has not run yet).
        assertFalse(controller.show(TextSheetRequest("b", "B", mono = true)))
        assertFalse(controller.selectText("b"))
        assertFalse(controller.showDetail("B", "b"))
        assertEquals(TextSheetRequest("a", "A", mono = false), controller.current)

        controller.onDismissed()
        assertNull(controller.current)
        assertTrue(controller.showDetail("B", "b"))
        assertEquals(TextSheetRequest("b", "B", mono = true), controller.current)
    }

    @Test
    fun selectTextBuildsTheSansRequest() {
        val controller = TextSheetController()
        assertTrue(controller.selectText("hello"))
        assertEquals(TextSheetRequest("hello", "Select Text", mono = false), controller.current)
    }

    @Test
    fun showDetailBuildsTheMonoRequest() {
        val controller = TextSheetController()
        assertTrue(controller.showDetail("Bash", "payload"))
        assertEquals(TextSheetRequest("payload", "Bash", mono = true), controller.current)
    }

    @Test
    fun aRequestIsValueEqualOverAllThreeFields() {
        assertEquals(
            TextSheetRequest("t", "T", mono = false),
            TextSheetRequest("t", "T", mono = false),
        )
        assertFalse(TextSheetRequest("t", "T", mono = false) == TextSheetRequest("t", "T", mono = true))
        assertFalse(TextSheetRequest("t", "T", mono = false) == TextSheetRequest("t", "X", mono = false))
        assertFalse(TextSheetRequest("t", "T", mono = false) == TextSheetRequest("u", "T", mono = false))
    }

    @Test
    fun onDismissedIsIdempotentWhenIdle() {
        val controller = TextSheetController()
        controller.onDismissed()
        controller.onDismissed()
        assertNull(controller.current)
        assertTrue(controller.selectText("again"))
    }
}
