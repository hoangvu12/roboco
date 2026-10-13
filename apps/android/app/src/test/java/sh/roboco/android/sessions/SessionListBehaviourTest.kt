package sh.roboco.android.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LinearFontScaleDensity
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme

/**
 * The list's behaviour (android-ui-parity #41) on the ticket's worked
 * example: 393 × 852 dp at xxhdpi (1 dp = 3 px) — fixed heights at three
 * type-scale factors ([LinearFontScaleDensity]), tap routing, the editing
 * gate, the per-row `archivedRow`, the press/fade highlight timing, the
 * animation gate (off screen, font scale, reduce motion), the
 * `currentChatId` re-draw, and the overlay's anchor bounds and drag
 * pass-through.
 *
 * The default `toVm` of [rememberSessionListModel] resolves
 * `HomeColorIndex` through the host core on jna.library.path — the same
 * route `CoreLoadTest` takes; its value is the fixture's 6 either way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SessionListBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun resetReduceMotion() {
        ReduceMotion.debugOverride = null
    }

    /**
     * Hosts a [SessionList]; every `() ->` provider is read in
     * composition, so flipping the test's `mutableStateOf` behind it
     * recomposes the list.
     */
    private fun host(
        sections: () -> List<ListSection> = { SessionListFixtures.sections() },
        collapsible: () -> Boolean = { true },
        collapsedIds: () -> Set<String> = { emptySet() },
        loading: Boolean = false,
        empty: SessionListEmpty = SessionListEmpty.None,
        onScreen: () -> Boolean = { true },
        editing: () -> Boolean = { false },
        currentChatId: () -> String? = { null },
        fontScale: () -> Float = { 1f },
        contentPadding: PaddingValues = PaddingValues(),
        state: LazyListState = LazyListState(),
        onOpenSession: (String) -> Unit = {},
        onOpenFolder: (String) -> Unit = {},
        onToggleSection: (String) -> Unit = {},
        archivedRow: (String) -> Boolean = { false },
        rowWrapper: SessionRowWrapper = PassThroughRowWrapper,
        leadingItems: LazyListScope.() -> Unit = {},
    ) {
        compose.setContent {
            // Frozen glyph clock: a live spinner (the loading overlay, working
            // rows) runs a withFrameNanos loop that never lets Compose idle.
            // The density is built in composition from the provider, so a
            // flipped factor re-provides it (one setContent per test).
            CompositionLocalProvider(LocalDensity provides LinearFontScaleDensity(3f, fontScale())) {
                RobocoTheme(Appearance.Light) { ProvideGlyphClock(frozenAtNanos = 0L) {
                    val model = rememberSessionListModel(
                        sections = sections(),
                        collapsible = collapsible(),
                        collapsedIds = collapsedIds(),
                        loading = loading,
                        empty = empty,
                        onScreen = onScreen(),
                    )
                    SessionList(
                        model = model,
                        state = state,
                        contentPadding = contentPadding,
                        onOpenSession = onOpenSession,
                        onOpenFolder = onOpenFolder,
                        onToggleSection = onToggleSection,
                        editing = editing(),
                        currentChatId = currentChatId(),
                        archivedRow = archivedRow,
                        rowWrapper = rowWrapper,
                        leadingItems = leadingItems,
                    )
                } }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------- heights

    @Test
    fun sessionAndHeaderHeightsFollowTheTypeScale() {
        // (factor, session dp, header dp): 62/40 at 1, the clamp ends.
        val table = listOf(
            Triple(0.85f, 53f, 34f),
            Triple(1.0f, 62f, 40f),
            Triple(1.6f, 99f, 64f),
        )
        // One setContent per test (the rule refuses a second): the factor is
        // swapped through state behind the host's density provider.
        var scale by mutableStateOf(table.first().first)
        host(fontScale = { scale })
        for ((factor, session, header) in table) {
            compose.runOnUiThread { scale = factor }
            compose.waitForIdle()
            compose.onNodeWithTag("session-chat-tool-colors").assertHeightIsEqualTo(session.dp)
            compose.onNodeWithTag("section-p0").assertHeightIsEqualTo(header.dp)
        }
    }

    @Test
    fun folderHeightsFollowTheTypeScaleAndThePlainHeaderIsFixed() {
        val table = listOf(0.85f to 39f, 1.0f to 46f, 1.6f to 74f)
        // One setContent per test: the factor is swapped through state.
        var scale by mutableStateOf(table.first().first)
        host(
            sections = { SessionListFixtures.folderPlainSections() },
            collapsible = { false },
            fontScale = { scale },
        )
        for ((factor, folder) in table) {
            compose.runOnUiThread { scale = factor }
            compose.waitForIdle()
            compose.onNodeWithTag("folder-pinned-folder").assertHeightIsEqualTo(folder.dp)
            // The plain header is the first item: the folder's top is its
            // 41 dp, at every factor.
            compose.onNodeWithTag("folder-pinned-folder").assertTopPositionInRootIsEqualTo(41.dp)
        }
    }

    // -------------------------------------------------------- tap routing

    @Test
    fun tapsRouteToTheirHandlers() {
        val opened = CopyOnWriteArrayList<String>()
        val toggled = CopyOnWriteArrayList<String>()
        host(onOpenSession = { opened += it }, onToggleSection = { toggled += it })
        compose.onNodeWithTag("session-chat-i18n").performClick()
        compose.onNodeWithTag("section-mobile").performClick()
        assertEquals(listOf("chat-i18n"), opened)
        assertEquals(listOf("mobile"), toggled)
    }

    @Test
    fun theCollapsibleHeaderIsOneButtonAction() {
        host()
        val node = compose.onNodeWithTag("section-mobile").fetchSemanticsNode()
        // One OnClick action — this ticket's single clickable; 39 adds none.
        assertNotNull("the header has exactly one OnClick action", node.config.getOrNull(SemanticsActions.OnClick))
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
    }

    @Test
    fun folderTapsRouteAndPlainHeaderTapsDoNothing() {
        val opened = CopyOnWriteArrayList<String>()
        val folders = CopyOnWriteArrayList<String>()
        val toggled = CopyOnWriteArrayList<String>()
        host(
            sections = { SessionListFixtures.folderPlainSections() },
            collapsible = { false },
            onOpenSession = { opened += it },
            onOpenFolder = { folders += it },
            onToggleSection = { toggled += it },
        )
        compose.onNodeWithTag("folder-pinned-folder").performClick()
        assertEquals(listOf("pinned-folder"), folders)
        // The plain header is not selectable: no click action, and a real
        // tap on its text fires nothing.
        compose.onNodeWithText("Pinned").assertHasNoClickAction()
        compose.onNodeWithText("Pinned").performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertTrue(opened.isEmpty())
        assertTrue(toggled.isEmpty())
    }

    @Test
    fun editingDisablesRowTaps() {
        val opened = CopyOnWriteArrayList<String>()
        host(editing = { true }, onOpenSession = { opened += it })
        compose.onNodeWithTag("session-chat-i18n").assertIsNotEnabled()
        compose.onNodeWithTag("session-chat-i18n").performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertTrue("no open while editing", opened.isEmpty())
    }

    @Test
    fun archivedRowIsEvaluatedPerRow() {
        val contexts = CopyOnWriteArrayList<SessionRowContext>()
        host(
            archivedRow = { it == "chat-deploy" },
            rowWrapper = { ctx, r -> contexts += ctx; r(Modifier) },
        )
        val byId = contexts.associateBy { it.vm.id }
        assertTrue("the marked row is archived", byId.getValue("chat-deploy").archived)
        assertFalse("the others are not", byId.getValue("chat-i18n").archived)
        assertFalse(byId.getValue("chat-tool-colors").archived)
        assertEquals("recent", byId.getValue("chat-i18n").sectionId)
    }

    @Test
    fun changingCurrentChatIdRebuildsTheRowsInPlace() {
        var current by mutableStateOf<String?>(null)
        val contexts = CopyOnWriteArrayList<SessionRowContext>()
        host(
            currentChatId = { current },
            rowWrapper = { ctx, r -> contexts += ctx; r(Modifier) },
        )
        val before = compose.onNodeWithTag("session-chat-i18n").fetchSemanticsNode().boundsInRoot.top
        compose.runOnUiThread { current = "chat-i18n" }
        compose.waitForIdle()
        val fresh = contexts.associateBy { it.vm.id }
        assertTrue(fresh.getValue("chat-i18n").isCurrent)
        assertFalse(fresh.getValue("chat-launch-post").isCurrent)
        // In place: the row keeps its key and its position.
        val after = compose.onNodeWithTag("session-chat-i18n").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 0.001f)
    }

    // ---------------------------------------------------- the highlight

    @Test
    fun theHighlightSnapsOnPressAndFadesOutOnRelease() {
        val src = MutableInteractionSource()
        var h: State<Float>? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) { h = rememberRowHighlight(src, fadeOnRelease = true) }
        }
        compose.mainClock.advanceTimeBy(100)
        val press = PressInteraction.Press(Offset.Zero)
        compose.runOnUiThread { src.tryEmit(press) }
        compose.mainClock.advanceTimeBy(32)
        assertEquals("press snaps to 1", 1f, h!!.value)
        compose.runOnUiThread { src.tryEmit(PressInteraction.Release(press)) }
        compose.mainClock.advanceTimeBy(150)
        assertEquals("mid fade at 150 ms", 0.5f, h!!.value, 0.05f)
        compose.mainClock.advanceTimeBy(150)
        assertEquals("settled at 300 ms", 0f, h!!.value, 0.01f)
    }

    @Test
    fun theHighlightSnapsToZeroWithoutAFadeWhenToldTo() {
        val src = MutableInteractionSource()
        var h: State<Float>? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) { h = rememberRowHighlight(src, fadeOnRelease = false) }
        }
        compose.mainClock.advanceTimeBy(100)
        val press = PressInteraction.Press(Offset.Zero)
        compose.runOnUiThread { src.tryEmit(press) }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(1f, h!!.value)
        compose.runOnUiThread { src.tryEmit(PressInteraction.Release(press)) }
        compose.mainClock.advanceTimeBy(16)
        assertEquals("no fade: 0 in the next frame", 0f, h!!.value, 0.0001f)
    }

    @Test
    fun aCancelledPressSnapsToZero() {
        val src = MutableInteractionSource()
        var h: State<Float>? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) { h = rememberRowHighlight(src, fadeOnRelease = true) }
        }
        compose.mainClock.advanceTimeBy(100)
        val press = PressInteraction.Press(Offset.Zero)
        compose.runOnUiThread { src.tryEmit(press) }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(1f, h!!.value)
        compose.runOnUiThread { src.tryEmit(PressInteraction.Cancel(press)) }
        compose.mainClock.advanceTimeBy(16)
        assertEquals("cancel snaps to 0", 0f, h!!.value, 0.0001f)
    }

    @Test
    fun pressingARowShowsTheControlFillInTheNextFrame() {
        host()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        // Down in the row's middle; the tap timeout (~100 ms) passes, so
        // the delayed Press lands and the shared source drives 37's fill.
        compose.onNodeWithTag("session-chat-downloads").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        val px = compose.onNodeWithTag("session-chat-downloads").captureToImage().toPixelMap()
        // A probe above the content (y = 4 dp): only the fill paints there.
        // controlFill (0x27272C @ 0.075) over background #F3F3F5 ≈ #E4E4E6.
        assertPixel(px, 100 * 3, 4 * 3, 228, 228, 230, "pressed fill")
    }

    /** ±2 per channel, like ticket 37's pixel probes. */
    private fun assertPixel(px: PixelMap, x: Int, y: Int, r: Int, g: Int, b: Int, what: String) {
        val c = px[x, y]
        fun ok(v: Float, e: Int) = abs(v - e / 255f) <= 2f / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${(c.red * 255f).roundToInt()}, ${(c.green * 255f).roundToInt()}, " +
                "${(c.blue * 255f).roundToInt()}), expected ($r, $g, $b) ±2",
            ok(c.red, r) && ok(c.green, g) && ok(c.blue, b),
        )
    }

    // ------------------------------------------------- the animation gate

    @Test
    fun theModelGatesAnimationWhileOffScreenAndForOneFrameAfter() {
        var onScreen by mutableStateOf(false)
        val produced = CopyOnWriteArrayList<SessionListModel>()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val m = rememberSessionListModel(SessionListFixtures.sections(), collapsible = true, onScreen = onScreen)
                SideEffect { produced += m }
            }
        }
        compose.waitForIdle()
        assertFalse("off screen never animates", produced.last().animate)
        compose.runOnUiThread { onScreen = true }
        compose.waitForIdle()
        // The flip frame lays out without animation; the next one resumes.
        assertFalse("the flip frame does not animate", produced[produced.size - 2].animate)
        assertTrue("the frame after does", produced.last().animate)
    }

    @Test
    fun aFontScaleChangeGatesTheFrameItReMeasuresIn() {
        var factor by mutableStateOf(1f)
        val produced = CopyOnWriteArrayList<SessionListModel>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides LinearFontScaleDensity(3f, factor)) {
                RobocoTheme(Appearance.Light) {
                    val m = rememberSessionListModel(SessionListFixtures.sections(), collapsible = true)
                    SideEffect { produced += m }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(produced.last().animate)
        compose.runOnUiThread { factor = 1.6f }
        compose.waitForIdle()
        assertFalse("the re-measure frame does not animate", produced[produced.size - 2].animate)
        assertTrue(produced.last().animate)
    }

    @Test
    fun aFontScaleChangeReMeasuresRowsWithNoPlacementAnimation() {
        var factor by mutableStateOf(1f)
        val density = { LinearFontScaleDensity(3f, factor) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density()) {
                RobocoTheme(Appearance.Light) {
                    SessionList(
                        model = rememberSessionListModel(SessionListFixtures.sections(), collapsible = true),
                        onOpenSession = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { factor = 1.6f }
        // Frozen clock: the write reaches the Recomposer only once apply
        // notifications are sent (GlobalSnapshotManager posts them to the
        // paused main looper, which advanceTimeBy does not idle).
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("session-chat-tool-colors").assertHeightIsEqualTo(99.dp)
        val jump = compose.onNodeWithTag("session-chat-tool-colors").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.advanceTimeBy(1000)
        val settled = compose.onNodeWithTag("session-chat-tool-colors").fetchSemanticsNode().boundsInRoot.top
        assertEquals("the re-measure jumps, it does not slide", settled, jump)
    }

    @Test
    fun aRemovedRowFadesOutWhileOnScreen() {
        var sections by mutableStateOf(SessionListFixtures.sections())
        host(sections = { sections })
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread {
            sections = sections.map { s -> if (s.id == "recent") s.copy(rows = s.rows.drop(1)) else s }
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("session-chat-i18n").assertExists("the disappearing row stays for the fade")
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("session-chat-i18n").assertDoesNotExist()
    }

    @Test
    fun offScreenChangesApplyInOneFrame() {
        var sections by mutableStateOf(SessionListFixtures.sections())
        host(sections = { sections }, onScreen = { false })
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread {
            sections = sections.map { s -> if (s.id == "recent") s.copy(rows = s.rows.drop(1)) else s }
        }
        Snapshot.sendApplyNotifications() // frozen clock: deliver the write
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("session-chat-i18n").assertDoesNotExist()
    }

    @Test
    fun aRowMovingBetweenSectionsAnimatesItsPlacement() {
        var sections by mutableStateOf(SessionListFixtures.sections())
        host(sections = { sections })
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        val i18n = SessionListFixtures.recentRows[0]
        val move = {
            sections.map { s ->
                when (s.id) {
                    "p0" -> s.copy(rows = s.rows + listOf(i18n))
                    "recent" -> s.copy(rows = s.rows.filter { it.id != i18n.id })
                    else -> s
                }
            }
        }
        compose.runOnUiThread { sections = move() }
        Snapshot.sendApplyNotifications() // frozen clock: deliver the write
        compose.mainClock.advanceTimeBy(50)
        val inFlight = compose.onNodeWithTag("session-chat-i18n").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.advanceTimeBy(1000)
        val settled = compose.onNodeWithTag("session-chat-i18n").fetchSemanticsNode().boundsInRoot.top
        assertTrue("settles higher up the list", settled < inFlight)
        // Mid-flight at ~50 ms: strictly between old (368 dp) and final.
        assertTrue("in flight at 50 ms: $settled < $inFlight < 368*3", inFlight > settled + 3f && inFlight < 368 * 3f - 3f)
    }

    @Test
    fun reducedMotionJumpsTheMovedRow() {
        ReduceMotion.debugOverride = true
        var sections by mutableStateOf(SessionListFixtures.sections())
        host(sections = { sections })
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        val i18n = SessionListFixtures.recentRows[0]
        compose.runOnUiThread {
            sections = sections.map { s ->
                when (s.id) {
                    "p0" -> s.copy(rows = s.rows + listOf(i18n))
                    "recent" -> s.copy(rows = s.rows.filter { it.id != i18n.id })
                    else -> s
                }
            }
        }
        Snapshot.sendApplyNotifications() // frozen clock: deliver the write
        compose.mainClock.advanceTimeBy(50)
        val jump = compose.onNodeWithTag("session-chat-i18n").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.advanceTimeBy(1000)
        val settled = compose.onNodeWithTag("session-chat-i18n").fetchSemanticsNode().boundsInRoot.top
        assertEquals("no placement animation under reduce motion", settled, jump)
        assertNotEquals(368f * 3f, settled) // it did move, instantly
    }

    @Test
    fun reducedMotionKeepsTheRemovalFade() {
        ReduceMotion.debugOverride = true
        var sections by mutableStateOf(SessionListFixtures.sections())
        host(sections = { sections })
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread {
            sections = sections.map { s -> if (s.id == "recent") s.copy(rows = s.rows.drop(1)) else s }
        }
        compose.mainClock.advanceTimeBy(32)
        // Opacity-only changes are reduce-motion safe: the disappearance
        // fade still runs (only the placement jumps).
        compose.onNodeWithTag("session-chat-i18n").assertExists("the disappearing row still fades")
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("session-chat-i18n").assertDoesNotExist()
    }

    // ------------------------------------------------------ the overlay

    @Test
    fun theLoadingOverlayCentresItsSpinnerOnTheAnchor() {
        host(
            sections = { emptyList() },
            loading = true,
            contentPadding = PaddingValues(top = 155.dp, bottom = 110.dp),
        )
        val b = compose.onNodeWithTag("sessions-loading").fetchSemanticsNode().boundsInRoot
        assertEquals(21f, b.width / 3f, 0.5f)
        assertEquals(21f, b.height / 3f, 0.5f)
        assertEquals(196.5f, (b.left + b.right) / 2f / 3f, 0.5f)
        assertEquals(408.5f, (b.top + b.bottom) / 2f / 3f, 0.5f)
    }

    @Test
    fun theEmptySessionsOverlayCentresItsCopyAndShowsBothLines() {
        host(
            sections = { emptyList() },
            empty = SessionListEmpty.Sessions,
            contentPadding = PaddingValues(top = 155.dp, bottom = 110.dp),
        )
        val b = compose.onNodeWithTag("sessions-empty").fetchSemanticsNode().boundsInRoot
        assertEquals(408.5f, (b.top + b.bottom) / 2f / 3f, 1f)
        compose.onNodeWithText("No sessions yet").assertExists()
        compose.onNodeWithText("Tap New session to start one.").assertExists()
    }

    @Test
    fun theArchivedOverlayShowsItsSingleLine() {
        host(
            sections = { emptyList() },
            empty = SessionListEmpty.Archived,
            contentPadding = PaddingValues(top = 155.dp, bottom = 110.dp),
        )
        compose.onNodeWithText("No archived sessions").assertExists()
        compose.onNodeWithText("Tap New session to start one.").assertDoesNotExist()
        val b = compose.onNodeWithTag("sessions-empty").fetchSemanticsNode().boundsInRoot
        assertEquals(408.5f, (b.top + b.bottom) / 2f / 3f, 1f)
    }

    @Test
    fun aDragOnTheEmptyOverlayScrollsTheListUnderneath() {
        val state = LazyListState()
        host(
            sections = { emptyList() },
            empty = SessionListEmpty.Sessions,
            state = state,
            leadingItems = {
                // Leading items give the underlying column something to
                // scroll (the overlay shows because the built items stay
                // empty — leading items do not count).
                items(20) { Box(Modifier.fillMaxWidth().height(100.dp)) }
            },
        )
        assertEquals(0, state.firstVisibleItemIndex)
        // From the anchor — the middle of the overlay's copy.
        compose.onRoot().performTouchInput {
            down(Offset(196.5f * 3f, 408.5f * 3f))
            moveTo(Offset(196.5f * 3f, 100f * 3f))
            up()
        }
        compose.waitForIdle()
        assertTrue("the drag scrolled the column, the overlay consumed nothing", state.firstVisibleItemIndex > 0)
    }
}
