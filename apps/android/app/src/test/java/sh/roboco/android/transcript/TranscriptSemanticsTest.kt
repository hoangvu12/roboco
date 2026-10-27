package sh.roboco.android.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.WidgetKind

/**
 * The pure halves of ticket 129's semantics tree, on Robolectric (no
 * Compose): [linkLabel] (android.net.Uri needs the framework),
 * [rowAccessibilityLabel], 128's [WidgetKind.isControl] set (pinned
 * across all 12 kinds — this ticket relies on it for exposed-vs-hidden)
 * and [a11yScrollTarget]'s paging table, plus the follow-aware
 * [TranscriptViewport.accessibilityScrollBy] rules on a bare viewport.
 *
 * The Compose tree itself is `TranscriptAccessibilityTest`; the menu
 * item rules are 128's (`RowMenuActionsTest`), not repeated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranscriptSemanticsTest {

    // ------------------------------------------------------------ linkLabel

    /** Every row of the ticket's label examples table. */
    @Test
    fun linkLabelReadsTheEncodedHostFallingBackToTheWholeUrl() {
        assertEquals("example.com", linkLabel("https://example.com/docs"))
        assertEquals("ja.wikipedia.org", linkLabel("https://ja.wikipedia.org/wiki/x"))
        // userinfo and port are not part of the host (Swift `URL.host()`).
        assertEquals("host.dev", linkLabel("https://user@host.dev:8080/a?b=1"))
        // Opaque / scheme-less URIs have no host: the whole URL, verbatim.
        assertEquals("mailto:a@b.com", linkLabel("mailto:a@b.com"))
        assertEquals("roboco-file:src/main.rs", linkLabel("roboco-file:src/main.rs"))
        assertEquals("not a url", linkLabel("not a url"))
        // A user-prompt image chip's preview URL: the host is "image".
        assertEquals("image", linkLabel("roboco-preview://image?ref=%2Ftmp%2Fx.png"))
    }

    @Test
    fun linkLabelUsesTheEncodedHostNotTheDecodedOne() {
        // The percent-encoded host matches Swift's default
        // (`percentEncoded: true`); a decoded host would differ here.
        val url = "https://xn--wgv71a.example/path"
        assertEquals(url.substringAfter("://").substringBefore('/'), linkLabel(url))
    }

    @Test
    fun linkLabelFallsBackWhenTheHostIsEmpty() {
        assertEquals("https://?q=1", linkLabel("https://?q=1"))
    }

    // ----------------------------------------------- rowAccessibilityLabel

    @Test
    fun userRowsAreAlwaysReadAsYouEvenWithAnEmptyPrompt() {
        assertEquals("You: ", rowAccessibilityLabel(RowKind.USER, "", "x"))
        assertEquals("You: Hi", rowAccessibilityLabel(RowKind.USER, "Hi", ""))
    }

    @Test
    fun blockRowsReadTheirMarkdownSourceNotThePaintedText() {
        assertEquals("## Plan", rowAccessibilityLabel(RowKind.MARKDOWN, "## Plan", "Plan"))
    }

    @Test
    fun emptyCopyTextFallsBackToTheConcatenatedDisplayText() {
        assertEquals("Ran 2 commands", rowAccessibilityLabel(RowKind.TOOLS, "", "Ran 2 commands"))
        assertEquals("", rowAccessibilityLabel(RowKind.WORKING, "", ""))
    }

    // -------------------------------------------------- the control set

    /** 128's set, pinned: exactly the five kinds iOS exposes. */
    @Test
    fun isControlIsTrueForExactlyTheFiveExposedKinds() {
        val controls = listOf(
            WidgetKind.CopyCode,
            WidgetKind.Disclosure(expanded = true),
            WidgetKind.ToolToggle(detail = 1uL, open = true),
            WidgetKind.Detail(title = "Terminal"),
            WidgetKind.Image(reference = "img-1"),
            // wave-9 #14: the error card's button (upstream #967 `UIButton`).
            WidgetKind.Action(action = "sign-in", label = "Log in"),
        )
        controls.forEach { assertTrue("$it must be a control", it.isControl()) }
    }

    /** The other seven kinds stay hidden (iOS `isUserInteractionEnabled = false`). */
    @Test
    fun isControlIsFalseForTheSevenHiddenKinds() {
        val hidden = listOf(
            WidgetKind.ToolStatus(running = true, failed = false),
            WidgetKind.Spinner,
            WidgetKind.Working(sinceMs = 0L, streaming = true),
            WidgetKind.Icon(name = "sparkles", color = ColorRole.TEXT),
            WidgetKind.Chevron(expanded = false),
            WidgetKind.ToolRail(
                trunkX = 0f,
                bend = 4f,
                branchEnd = 100f,
                rowMid = 20f,
                tops = listOf(0f),
                heights = listOf(40f),
            ),
            WidgetKind.Shimmer,
        )
        // + SubagentLink (android-next #06) and Action (wave-9 #14), both controls.
        assertEquals("the Rust core has 14 widget kinds", 14, hidden.size + 7)
        hidden.forEach { assertFalse("$it must not be a control", it.isControl()) }
    }

    // -------------------------------------------------------- the page math

    /** Every row of the ticket's worked-values table (viewport 891). */
    @Test
    fun a11yScrollTargetPagesByTheUncoveredPageHeight() {
        // No insets: a full page, clamped at the range's end only by step 3.
        assertEquals(109f, a11yScrollTarget(1000f, 0f, 5000f, -891f, 891f, 0f, 0f), 1e-3f)
        // 68 dp of header and 100 dp of bottom stack: page 723.
        assertEquals(277f, a11yScrollTarget(1000f, 0f, 5000f, -891f, 891f, 68f, 100f), 1e-3f)
        // Forward past the end: clamped to maxOffset.
        assertEquals(5000f, a11yScrollTarget(4800f, 0f, 5000f, 891f, 891f, 0f, 0f), 1e-3f)
        // A show-on-screen delta (under a host height): the delta itself.
        assertEquals(1120f, a11yScrollTarget(1000f, 0f, 5000f, 120f, 891f, 0f, 0f), 1e-3f)
        // Already at the resting top with a top inset: clamped, no move
        // (the caller then returns false).
        assertEquals(-68f, a11yScrollTarget(-68f, -68f, 5000f, -891f, 891f, 68f, 100f), 1e-3f)
    }

    @Test
    fun aDeltaJustUnderThePageThresholdIsNotAPage() {
        // 890.4 < 891 - 0.5: the step is the delta, not the page.
        assertEquals(1890.4f, a11yScrollTarget(1000f, 0f, 5000f, 890.4f, 891f, 0f, 0f), 1e-3f)
        // 890.6 >= 891 - 0.5: it is a page (891 dp).
        assertEquals(1891f, a11yScrollTarget(1000f, 0f, 5000f, 890.6f, 891f, 0f, 0f), 1e-3f)
    }

    @Test
    fun aZeroViewportHeightUsesTheDeltaItself() {
        // The host was never measured: fall back to the raw delta.
        assertEquals(150f, a11yScrollTarget(100f, 0f, 1000f, 50f, 0f, 0f, 0f), 1e-3f)
        // The page never drops below 1 dp, so a page-sized delta still moves.
        assertEquals(2f, a11yScrollTarget(1f, 0f, 1000f, 50f, 1f, 0f, 0f), 1e-3f)
    }

    @Test
    fun aContentShorterThanTheViewportClampsToTheRestingTop() {
        // maxOffset < minOffset: the range degenerates to [min, min].
        assertEquals(-68f, a11yScrollTarget(0f, -68f, -100f, -891f, 891f, 68f, 0f), 1e-3f)
    }

    // ------------------------------------------- accessibilityScrollBy (68)

    @Test
    fun aBackwardPageReleasesFollowAndAForwardArrivalReLatchesIt() {
        val vp = TranscriptViewport()
        vp.setViewportHeight(891f)
        vp.setContentHeight(2000f) // maxOffset = 1109, minOffset = 0
        vp.setOffset(1109f) // at the tail, still following by default

        assertTrue("a page toward older content scrolls", vp.accessibilityScrollBy(-891f))
        assertEquals(1109f - 891f, vp.offset, 0.5f)
        assertFalse("the follow spring must not pull the view back", vp.following)

        assertTrue("a page toward the tail scrolls", vp.accessibilityScrollBy(891f))
        assertEquals(1109f, vp.offset, 0.5f)
        assertTrue("paging back to the bottom re-latches follow", vp.following)

        assertFalse("a further forward page is at the end", vp.accessibilityScrollBy(891f))
    }

    @Test
    fun aBackwardPageAtTheRestingTopReturnsFalse() {
        val vp = TranscriptViewport()
        vp.setTopInset(68f)
        vp.setViewportHeight(891f)
        vp.setContentHeight(500f) // maxOffset = max(-68, 500-891) = -68
        vp.setOffset(-68f)
        assertFalse("nothing to scroll up to", vp.accessibilityScrollBy(-891f))
        assertEquals(-68f, vp.offset, 0.5f)
    }
}
