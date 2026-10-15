package sh.roboco.android.transcript

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UniffiSandbox
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToastHost
import uniffi.roboco_core.DebugEntry
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.LinkHit
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.RowPlacement
import uniffi.roboco_core.WidgetKind

/**
 * Ticket 129's semantics tree against the real core (the same JNA setup
 * as `TranscriptHostTest`, `w411dp-h891dp-xhdpi` so d = 2), over the
 * [A11yFixture]: the host's collection and scroll semantics, the row
 * groups, the row text labels and bounds, the custom actions (Copy /
 * Copy Message / Select Text through 128's performer — the clipboard is
 * read through the platform `ClipboardManager`), the link nodes (label,
 * state, bounds, activation, and the table scroller's unclipped,
 * offset-following position), and TalkBack paging (the page height, the
 * follow release / re-latch, and the scroll range in px). Touch
 * long-press still opens 128's menu.
 *
 * `useUnmergedTree = true` everywhere, per the ticket: the tree is read
 * node by node, never merged.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** 128's host: `ToastHost { ContextMenuHost { TranscriptHost } }`. */
    private val opened = mutableListOf<LinkTap>()
    private var taps = 0
    private val selectTextCalls = mutableListOf<String>()

    private val actions = TranscriptActions(
        openLink = { tap -> opened += tap },
        onTap = { taps++ },
        selectText = { text -> selectTextCalls += text },
    )

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(context)
        runBlocking { TextEngine.await() }
        TranscriptPerf.reset()
    }

    // ------------------------------------------------------------- helpers

    /** The 7-row a11y fixture (u1 + 5 blocks + the Working row). */
    private fun fedA11yState(): TranscriptState = fedState(A11yFixture, working = true) { it.rowCount() == 7u }

    /**
     * The 120-entry paging fixture: 60 user/assistant turns (the
     * assistant texts are two paragraphs, so 180 rows) — tall enough that
     * the realized band never holds the whole history.
     */
    private val PagingFixture: List<DebugEntry> =
        (0 until 60).flatMap { i ->
            listOf(
                DebugEntry("u$i", true, "Question $i?", false),
                DebugEntry("a$i", false, "Answer $i.\n\nA second paragraph for answer $i.", false),
            )
        }

    /**
     * A fed state: viewport first, then entries — the frame is awaited
     * through the relay's Choreographer hop (Robolectric's paused looper,
     * as `TranscriptHostTest` does), never `compose.waitUntil`, until
     * [frameReady] holds.
     */
    private fun fedState(
        entries: List<DebugEntry>,
        working: Boolean,
        frameReady: (LayoutFrame) -> Boolean,
    ): TranscriptState {
        val state = TranscriptState()
        state.setViewport(411f, 1f)
        state.engine.setDebugEntries(entries, working)
        val end = System.nanoTime() + 5_000_000_000L
        while (state.frame?.let { it.width() == 411f && frameReady(it) } != true) {
            check(System.nanoTime() < end) { "no frame at width 411 within 5 s" }
            shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
        }
        TranscriptPerf.reset()
        return state
    }

    /** The ticket's host, with the offset pinned to the top when [pinTop]. */
    private fun host(state: TranscriptState, pinTop: Boolean = true) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // Frozen glyph clock: the fixture's Working row subscribes the
                // trailer glyph to the theme's endless withFrameNanos loop, and
                // an auto-advancing test clock never sees Compose idle.
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    ToastHost {
                        ContextMenuHost {
                            TranscriptHost(state, actions, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (pinTop) {
            compose.runOnIdle { state.viewport.setOffset(0f) }
            compose.waitForIdle()
        }
    }

    private fun hostNode(): SemanticsNode =
        compose.onNodeWithTag("transcript", useUnmergedTree = true).fetchSemanticsNode()

    /** Every node of the unmerged tree, root first, depth-first. */
    private fun allNodes(): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            out += n
            n.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /** The row text nodes (the `row-*` tags). */
    private fun rowTextNodes(): List<SemanticsNode> = allNodes().filter { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("row-") == true
    }

    /** The row text nodes keyed by their row group's collection row index. */
    private fun textNodesByRowIndex(): Map<Int, SemanticsNode> =
        rowTextNodes().associateBy { node ->
            node.parent?.config?.getOrNull(SemanticsProperties.CollectionItemInfo)?.rowIndex ?: -1
        }

    /** The first placement and [LinkHit] whose url equals [url], or `null`. */
    private fun linkRow(frame: LayoutFrame, url: String): Pair<RowPlacement, LinkHit>? {
        for (p in frame.rowsIn(0f, Float.MAX_VALUE)) {
            val display = frame.display(p.index) ?: continue
            val hit = display.links.firstOrNull { it.url == url } ?: continue
            return p to hit
        }
        return null
    }

    private fun requireLink(state: TranscriptState, url: String): Pair<RowPlacement, LinkHit> =
        linkRow(state.frame!!, url) ?: error("no link $url in the frame")

    private fun realized(state: TranscriptState, p: RowPlacement): RealizedRow {
        val row = state.realized[p.key]
        check(row != null) { "row ${p.key} (y=${p.y}) is not realized" }
        return row
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(ClipboardManager::class.java) ?: error("no ClipboardManager")

    private fun clipText(): String = clipboard().primaryClip!!.getItemAt(0).text.toString()

    private fun drainFrames() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    // --------------------------------------------------------------- tests

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theHostIsATalkBackScrollableCollectionOfTheFramesRows() {
        val state = fedA11yState()
        try {
            host(state)
            val node = hostNode()
            val info = node.config[SemanticsProperties.CollectionInfo]
            assertEquals(state.frame!!.rowCount().toInt(), info.rowCount)
            assertEquals(1, info.columnCount)
            assertTrue("a vertical scroll range", node.config.contains(SemanticsProperties.VerticalScrollAxisRange))
            assertTrue("a scrollBy action (paging)", node.config.contains(SemanticsActions.ScrollBy))
            // The tags-as-resource-ids flag is set on this subtree (the
            // manual step verifies it through `uiautomator dump`).
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun everyRealizedRowIsATraversalGroupCollectionItemWithNoLongClick() {
        val state = fedA11yState()
        try {
            host(state)
            assertEquals("the fixture realizes all 7 rows", 7, state.realized.size)
            val texts = textNodesByRowIndex()
            val groups = texts.mapValues { (index, text) ->
                val group = text.parent
                assertNotNull("row $index has a group parent", group)
                group!!
            }
            for (row in state.realized.values) {
                val index = row.placement.index.toInt()
                val text = texts[index] ?: error("no text node for row $index")
                val group = groups[index] ?: error("no row group for index $index")
                assertTrue("row $index is a traversal group", group.config.getOrNull(SemanticsProperties.IsTraversalGroup) == true)
                val item = group.config[SemanticsProperties.CollectionItemInfo]
                assertEquals(index, item.rowIndex)
                assertEquals(1, item.rowSpan)
                assertEquals(0, item.columnIndex)
                assertEquals(1, item.columnSpan)
                assertNull("the semantics long-click is off (16)", group.config.getOrNull(SemanticsActions.OnLongClick))
                assertNull("the row group carries no tag (it moved to the text node)", group.config.getOrNull(SemanticsProperties.TestTag))
                assertNull("the text node has no long-click either (custom actions replace it)", text.config.getOrNull(SemanticsActions.OnLongClick))
            }
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theUserRowReadsYouPlusThePrompt() {
        val state = fedA11yState()
        try {
            host(state)
            val user = compose.onNodeWithTag("row-user", useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(listOf("You: Summarize the launch post in three bullets."), user.config[SemanticsProperties.ContentDescription])
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun blockRowsReadTheirMarkdownSourceFromTheFrame() {
        val state = fedA11yState()
        try {
            host(state)
            val frame = state.frame!!
            val texts = textNodesByRowIndex()
            val markdown = frame.rowsIn(0f, Float.MAX_VALUE).filter { it.kind == RowKind.MARKDOWN }
            assertEquals("the fixture lays out five markdown rows", 5, markdown.size)
            for (p in markdown) {
                val text = texts[p.index.toInt()] ?: error("no text node for row ${p.index}")
                assertEquals(
                    frame.display(p.index)!!.copyText,
                    text.config[SemanticsProperties.ContentDescription].single(),
                )
            }
            // The topmost markdown row is the heading.
            val first = markdown.minBy { it.y }
            assertEquals("## Links", texts[first.index.toInt()]!!.config[SemanticsProperties.ContentDescription].single())
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theRowTextNodeCoversTheRowAndSitsLastInTraversal() {
        val state = fedA11yState()
        try {
            host(state)
            val hostWidth = hostNode().boundsInRoot.width
            val texts = textNodesByRowIndex()
            for (row in state.realized.values) {
                val text = texts[row.placement.index.toInt()] ?: error("no text node for row ${row.placement.index}")
                val b = text.boundsInRoot
                assertEquals("left", 0f, b.left, 1f)
                assertEquals("top", row.topPx().toFloat(), b.top, 1f)
                assertEquals("height", row.heightPx().toFloat(), b.height, 1f)
                assertEquals("width (the full host)", hostWidth, b.width, 2f)
                assertEquals("the text node traverses after the 0f nodes", 1f, text.config.getOrNull(SemanticsProperties.TraversalIndex) ?: -1f, 1e-3f)
            }
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theWorkingRowStaysSilentAndNothingReadsWorking() {
        val state = fedA11yState()
        try {
            host(state)
            val working = compose.onNodeWithTag("row-working", useUnmergedTree = true).fetchSemanticsNode()
            assertFalse("no description (an empty label is skipped)", working.config.contains(SemanticsProperties.ContentDescription))
            assertFalse("no custom actions for an empty text", working.config.contains(SemanticsActions.CustomActions))
            for (node in allNodes()) {
                val text = node.config.getOrNull(SemanticsProperties.Text)?.any { "Working" in it.text } == true
                val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { "Working" in it } == true
                assertFalse("nothing may read Working: ${node.config.getOrNull(SemanticsProperties.TestTag)}", text || desc)
            }
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun linkNodesCarryTheirHostTheirUrlTheirRectAndAnOnClick() {
        val state = fedA11yState()
        try {
            host(state)
            val d = 2f
            val (p, hit) = requireLink(state, DOCS)
            val row = realized(state, p)
            val links = compose.onAllNodesWithTag("transcript-link", useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("the fixture paints links", links.isNotEmpty())
            val node = links.first { it.config.getOrNull(SemanticsProperties.StateDescription) == DOCS }
            assertEquals(listOf("example.com"), node.config[SemanticsProperties.ContentDescription])
            assertEquals(0f, node.config.getOrNull(SemanticsProperties.TraversalIndex) ?: -1f, 1e-3f)
            assertTrue("an OnClick for TalkBack activation", node.config.contains(SemanticsActions.OnClick))
            // The painted fragment rect, dp to px, translated by the row.
            val b = node.boundsInRoot
            assertEquals(hit.x * d, b.left, 1f)
            assertEquals(row.topPx() + hit.y * d, b.top, 1f)
            assertEquals(hit.w * d, b.width, 1f)
            assertEquals(hit.h * d, b.height, 1f)
        } finally {
            state.close()
        }
    }

    /** The UI-test port of `iosui:SessionFlowTests.swift:160-171`. */
    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun activatingAWikipediaLinkOpensItAndFiresOnTapAfter() {
        val state = fedA11yState()
        try {
            host(state)
            val matcher = SemanticsMatcher("ja.wikipedia link") {
                it.config.getOrNull(SemanticsProperties.StateDescription)?.contains("ja.wikipedia") == true
            }
            val links = compose.onAllNodes(matcher, useUnmergedTree = true)
            assertEquals("exactly one ja.wikipedia link", 1, links.fetchSemanticsNodes().size)
            links[0].performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals(1, opened.size)
            assertEquals(WIKI, opened[0].url)
            assertEquals("onTap exactly once, after openLink", 1, taps)

            // rowBounds is the row's window rect; linkBounds the node's.
            val (p, _) = requireLink(state, WIKI)
            val row = realized(state, p)
            val rb = row.boundsInWindow()
            val nb = links[0].fetchSemanticsNode().boundsInWindow
            assertEquals(rb.left, opened[0].rowBounds.left, 1f)
            assertEquals(rb.top, opened[0].rowBounds.top, 1f)
            assertEquals(rb.right, opened[0].rowBounds.right, 1f)
            assertEquals(rb.bottom, opened[0].rowBounds.bottom, 1f)
            assertEquals(nb.left, opened[0].linkBounds.left, 1f)
            assertEquals(nb.top, opened[0].linkBounds.top, 1f)
            assertEquals(nb.right, opened[0].linkBounds.right, 1f)
            assertEquals(nb.bottom, opened[0].linkBounds.bottom, 1f)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aTableLinkIsUnclippedAndSlidesWithItsScrollerWithoutRecomposing() {
        val state = fedA11yState()
        try {
            host(state)
            val d = 2f
            val (p, hit) = requireLink(state, TABLE)
            val display = realized(state, p).model!!.display
            assertNotNull("the table link is in a scroller", hit.scroller)
            val idx = hit.scroller!!.toInt()
            val sc = display.scrollers[idx]
            // The ticket's fixture precondition: the wiki link starts
            // outside the scroller's viewport (font metrics could change it).
            assertTrue("the fixture's table link starts past the viewport (l.x=${hit.x} >= w=${sc.w})", hit.x >= sc.w)

            val node = allNodes().first { it.config.getOrNull(SemanticsProperties.StateDescription) == TABLE }
            val scrollers = compose.onAllNodesWithTag(ScrollerDefaults.TestTag, useUnmergedTree = true)
            val infos = scrollers.fetchSemanticsNodes()
            val tableIdx = infos.indexOfFirst { info ->
                (info.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue() ?: 0f) > 0f
            }
            assertTrue("exactly the table overflows (the code block fits)", tableIdx >= 0)
            val viewport = infos[tableIdx].boundsInRoot
            // `positionInRoot`, not `boundsInRoot`: the host clips at its
            // own bounds, and a fragment past its right edge clips to an
            // empty rect — the position is the unclipped geometry.
            assertTrue("unclipped: the link's left edge is past the scroller's right edge", node.positionInRoot.x >= viewport.right)

            // Scroll by exactly 150 dp and check the placement-phase move.
            val before = node.positionInRoot
            val compositions = state.hostCompositions
            scrollers[tableIdx].performSemanticsAction(SemanticsActions.ScrollBy) { it(150f * d, 0f) }
            drainFrames()
            val after = allNodes().first { it.config.getOrNull(SemanticsProperties.StateDescription) == TABLE }.positionInRoot
            assertEquals("moved left by 150 dp", before.x - 150f * d, after.x, 1f)
            assertEquals("the vertical position never moved", before.y, after.y, 1f)
            assertEquals("a scroller scroll recomposes nothing", compositions, state.hostCompositions)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theCopyCodeButtonIsAGroupAtTraversalIndexZeroInsideTheRowGroup() {
        val state = fedA11yState()
        try {
            host(state)
            val codeIndex = codeRowIndex(state)
            val node = compose.onNodeWithContentDescription("Copy code", useUnmergedTree = true).fetchSemanticsNode()
            // The nearest traversal-group ancestor below the row group is
            // this node's WidgetBox (0f); the row group is above it.
            val group = generateSequence(node.parent) { it.parent }
                .takeWhile { it.config.getOrNull(SemanticsProperties.CollectionItemInfo)?.rowIndex != codeIndex }
                .firstOrNull { it.config.getOrNull(SemanticsProperties.IsTraversalGroup) == true }
            assertNotNull("a traversal group wraps the button", group)
            assertEquals(0f, group!!.config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f, 1e-3f)
            // And the row group above it contains the button.
            val rowGroup = generateSequence(node.parent) { it.parent }
                .firstOrNull { it.config.getOrNull(SemanticsProperties.CollectionItemInfo)?.rowIndex == codeIndex }
            assertNotNull("the code-block row group contains the button", rowGroup)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theCustomActionsAre128sItemsAndRunThroughThePerformer() {
        val state = fedA11yState()
        try {
            host(state)
            val frame = state.frame!!
            val codeIndex = codeRowIndex(state)
            val codeBlock = frame.display(codeIndex.toUInt())!!.copyText
            val message = frame.messageText(codeIndex.toUInt())!!
            val codeRow = compose.onNode(textNodeAt(codeIndex), useUnmergedTree = true)
            codeRow.fetchSemanticsNode().let { node ->
                assertEquals(
                    listOf("Copy", "Copy Message", "Select Text"),
                    node.config[SemanticsActions.CustomActions].map { it.label },
                )
            }

            runCustomAction(codeRow, "Copy")
            compose.waitForIdle()
            assertEquals(codeBlock, clipText())
            assertEquals("Roboco", clipboard().primaryClip!!.description.label.toString())

            runCustomAction(codeRow, "Copy Message")
            compose.waitForIdle()
            assertEquals(message, clipText())

            runCustomAction(codeRow, "Select Text")
            compose.waitForIdle()
            assertEquals(listOf(message), selectTextCalls)
            assertEquals("no link was activated", 0, taps)

            // The single-block user row: Copy and Select Text only.
            val userRow = compose.onNode(textNodeAt(userRowIndex(state)), useUnmergedTree = true)
            assertEquals(
                listOf("Copy", "Select Text"),
                userRow.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label },
            )
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun talkBackPagingPagesByTheUncoveredHeightAndManagesFollow() {
        val state = fedState(PagingFixture, working = false) { it.rowCount() >= 100u }
        try {
            host(state, pinTop = false)
            val d = 2f
            val transcript = compose.onNodeWithTag("transcript", useUnmergedTree = true)
            // After the first frame the viewport follows, at maxOffset.
            assertEquals(state.viewport.maxOffset, state.viewport.offset, 0.5f)
            assertTrue(state.viewport.following)

            // A backward page: a full host height, no insets.
            var ok = false
            transcript.performSemanticsAction(SemanticsActions.ScrollBy) { ok = it(0f, -891f * d) }
            compose.waitForIdle()
            val first = state.viewport.offset
            assertTrue("the page scrolled", ok)
            assertEquals(state.viewport.maxOffset - 891f, first, 0.5f)
            assertFalse("paging toward older content releases follow", state.viewport.following)
            assertBandCoversViewport(state)

            // Insets: the page becomes the uncovered area (723 dp).
            compose.runOnIdle {
                state.viewport.setTopInset(68f)
                state.viewport.setBottomInset(100f)
            }
            compose.waitForIdle()
            ok = false
            transcript.performSemanticsAction(SemanticsActions.ScrollBy) { ok = it(0f, -891f * d) }
            compose.waitForIdle()
            assertTrue(ok)
            assertEquals(first - 723f, state.viewport.offset, 0.5f)
            assertFalse(state.viewport.following)
            assertBandCoversViewport(state)

            // Forward pages return to maxOffset and re-latch follow.
            var pages = 0
            while (!state.viewport.following && pages < 20) {
                var moved = false
                transcript.performSemanticsAction(SemanticsActions.ScrollBy) { moved = it(0f, 891f * d) }
                compose.waitForIdle()
                assertTrue("forward page $pages must scroll", moved)
                pages++
            }
            assertTrue("forward paging never re-latched follow after $pages pages", state.viewport.following)
            assertEquals(state.viewport.maxOffset, state.viewport.offset, 0.5f)
            assertBandCoversViewport(state)

            // A further forward page returns false: TalkBack's end.
            var end = false
            transcript.performSemanticsAction(SemanticsActions.ScrollBy) { end = it(0f, 891f * d) }
            compose.waitForIdle()
            assertFalse("already at the end", end)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aTouchLongPressStillOpens128sMenu() {
        val state = fedA11yState()
        try {
            host(state)
            compose.mainClock.autoAdvance = true
            compose.onAllNodesWithTag("row-markdown", useUnmergedTree = true)[0].performTouchInput { longClick() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            compose.onNodeWithTag("context-menu-overlay").assertExists()
        } finally {
            state.close()
        }
    }

    // ------------------------------------------------------------- helpers

    /** The row text node of the row at [rowIndex], as a matcher on the parent group's index. */
    private fun textNodeAt(rowIndex: Int) = SemanticsMatcher("row text at index $rowIndex") { n ->
        n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("row-") == true &&
            n.parent?.config?.getOrNull(SemanticsProperties.CollectionItemInfo)?.rowIndex == rowIndex
    }

    /** The row index of the code-block row (the one carrying a `CopyCode` widget). */
    private fun codeRowIndex(state: TranscriptState): Int {
        val frame = state.frame!!
        for (p in frame.rowsIn(0f, Float.MAX_VALUE)) {
            val display = frame.display(p.index) ?: continue
            if (display.widgets.any { it.kind is WidgetKind.CopyCode }) return p.index.toInt()
        }
        error("no code-block row in the fixture")
    }

    /** The row index of the user row (the fixture's first). */
    private fun userRowIndex(state: TranscriptState): Int =
        state.frame!!.rowsIn(0f, Float.MAX_VALUE).first { it.kind == RowKind.USER }.index.toInt()

    /**
     * 65's band rule: the realized rows cover the viewport, one text node
     * per realized row, the tree never holds the whole history, and the
     * host's px scroll range tracks the offset (`(offset − minOffset)·d`,
     * d = 2 at xhdpi).
     */
    private fun assertBandCoversViewport(state: TranscriptState) {
        val vpTop = state.viewport.offset
        val vpBottom = vpTop + 891f
        assertTrue(
            "rows must cover the viewport at offset $vpTop",
            state.realized.values.any { row -> row.placement.y < vpBottom && row.placement.y + row.placement.height > vpTop },
        )
        val rowNodes = rowTextNodes().size
        assertEquals("one text node per realized row", state.realized.size, rowNodes)
        assertTrue(
            "the tree never holds the whole history",
            state.realized.size < state.frame!!.rowCount().toInt(),
        )
        val range = hostNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("the scroll range tracks the offset, in px", (vpTop - state.viewport.minOffset) * 2f, range.value(), 1f)
    }

    private companion object {
        const val DOCS = "https://example.com/docs"
        const val WIKI = "https://ja.wikipedia.org/wiki/x"
        const val TABLE = "https://en.wikipedia.org/wiki/Table"
    }

    /**
     * Runs the node's custom action labelled [label]. CustomActions is a
     * SemanticsPropertyKey<List<CustomAccessibilityAction>>, not an
     * AccessibilityAction key, so performSemanticsAction does not apply: read
     * the list off the node and invoke the matching action on the UI thread.
     */
    private fun runCustomAction(node: SemanticsNodeInteraction, label: String) {
        val action = node.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { action.action() }
    }
}
