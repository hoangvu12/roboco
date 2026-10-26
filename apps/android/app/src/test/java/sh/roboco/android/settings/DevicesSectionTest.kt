package sh.roboco.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.pairing.PairedEngineSink
import sh.roboco.android.pairing.PairingBackend
import sh.roboco.android.pairing.ProvidePairPage
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.LocalPageSheetScope
import sh.roboco.android.shell.PageSheetScope
import sh.roboco.android.shell.SheetHost
import sh.roboco.android.shell.SheetHostState
import sh.roboco.android.shell.SheetStyle
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.RoomInviteTarget
import uniffi.roboco_core.RoomJoinResult
import uniffi.roboco_core.RoomRecord
import uniffi.roboco_core.PairingTarget

/**
 * The Settings Devices section (android-ui-parity #102) through the real
 * [rememberDevicesSectionState] + [devicesSection] over a gallery-style
 * [MutableStateFlow] (ticket 71's registry flow faked at the source):
 *
 *  - geometry on the ticket's `w402dp` phone at xxhdpi (1 dp = 3 px), font
 *    scale 1: the 67.7 dp engine row, the 52 dp pair row, the dot's
 *    centre at screen x 344.0 (round 46) and the chevron box centre at x 363.5, the
 *    separator under the engine row only — all the ticket 70 measurements
 *    at this section's call sites;
 *  - the dot's ink: `success` `#15803D` light / `#34D399` dark when
 *    connected, `tertiary` `#97979F` light otherwise;
 *  - live updates: a state change re-renders the row on the next frame
 *    with no tween; added/removed rows move the pair row at once with no
 *    item animation (`animatingDifferences: false`);
 *  - clicks: [DevicesSectionState] dispatch, the default handlers
 *    (one [EngineDetailRoute] push with the double-tap guard, the Pair
 *    page through 72's real `PairPageLauncher` + `SheetHost`), and the
 *    silent no-op without a launcher;
 *  - semantics: the merged row reads "title, subtitle" with
 *    `Role.Button`; the dot has no semantics node of its own;
 *  - the lazy keys (`grouped:devices:*`) and the null-list pair-only
 *    section, through a recording [LazyListScope] (the `GroupedListTest`
 *    method).
 *
 * Written per the test policy; the batch/VPS run executes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h900dp-xxhdpi")
class DevicesSectionTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ fixtures

    private val flow = MutableStateFlow<List<EngineView>?>(
        listOf(engineView(id = "e1", name = "MacBook Pro", state = EngineState.Connected)),
    )
    private val openCalls = mutableListOf<String>()
    private var pairCalls = 0

    // ------------------------------------------------------------- mounting

    /**
     * Ticket 103's shape: the state at screen level (outside the
     * `LazyColumn`), the rows inside it, on the page background. The
     * handlers are explicit so the composition locals are read only by
     * the default-handler tests below.
     */
    private fun mount(appearance: Appearance = Appearance.Light) {
        compose.setContent {
            RobocoTheme(appearance) {
                val devices = rememberDevicesSectionState(
                    engines = flow,
                    onOpenEngine = { openCalls += it },
                    onPair = { pairCalls++ },
                )
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .background(RobocoTheme.palette.background),
                ) {
                    devicesSection(devices)
                }
            }
        }
        compose.waitForIdle()
    }

    /** The default handlers' mounting: a real [NavStack] behind the locals. */
    private fun mountWithStack(stack: NavStack) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalNavStack provides stack,
                    LocalNavEntry provides stack.entries.first(),
                ) {
                    val devices = rememberDevicesSectionState(engines = flow)
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .background(RobocoTheme.palette.background),
                    ) {
                        devicesSection(devices)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * A test-side flow write on the frozen clock. `waitForIdle` runs the
     * collector and the recomposition without spending clock time; the
     * single `advanceTimeByFrame` lays the frame out — "at most one
     * frame" per the acceptance list. [landed] reports whether the write
     * is visible yet; if the flow's collector hop needed the frame
     * itself, one more frame is spent before failing — a placement
     * animation (300 ms ≈ 18 frames) would still be mid-flight, so the
     * no-animation checks below stay discriminating either way.
     */
    private fun setEngines(engines: List<EngineView>?, landed: () -> Boolean) {
        compose.runOnUiThread {
            flow.value = engines
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        if (!landed()) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertTrue("the engine-list change did not land", landed())
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag)

    private fun rowImage(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    private fun assertDp(actual: Dp, expected: Dp, what: String, tolerance: Float = 1f) {
        assertTrue(
            "$what: $actual dp, expected $expected ±$tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    private fun channel(color: Color, ch: Int): Int = when (ch) {
        0 -> (color.red * 255f).toInt()
        1 -> (color.green * 255f).toInt()
        2 -> (color.blue * 255f).toInt()
        else -> error(ch)
    }

    private fun matches(c: Color, r: Int, g: Int, b: Int, tolerance: Int): Boolean =
        abs(channel(c, 0) - r) <= tolerance && abs(channel(c, 1) - g) <= tolerance &&
            abs(channel(c, 2) - b) <= tolerance

    /** The ink centroid's x, over the rows [yRange] × [xRange], in image px. */
    private fun centroidX(
        px: PixelMap,
        yRange: IntRange,
        xRange: IntRange,
        match: (Color) -> Boolean,
    ): Float {
        var sum = 0f
        var count = 0
        for (y in yRange) {
            for (x in xRange) {
                if (match(px[x, y])) {
                    sum += x
                    count++
                }
            }
        }
        assertTrue("no matching pixels in x=$xRange, y=$yRange", count > 0)
        return sum / count
    }

    /**
     * Separators at [y]: present somewhere in the text-column span
     * (x 168..1062, image-local — the row image starts at the card's left
     * edge), absent either side. UIKit separator over white ≈
     * (198, 198, 200) — the `GroupedListTest` probe.
     */
    private fun PixelMap.hasSeparator(y: Int): Boolean {
        var ink = 0
        for (x in 168..1062) {
            if (matches(this[x, y], 198, 198, 200, 12)) ink++
        }
        if (ink < 20) return false
        assertTrue("stray ink left of the separator start", matches(this[160, y], 255, 255, 255, 2))
        assertTrue("stray ink right of the separator end", matches(this[1070, y], 255, 255, 255, 2))
        return true
    }

    // --------------------------------------------------------- the header

    @Test
    fun theHeaderIsDevicesAndIsAHeading() {
        mount()
        val header = compose.onNodeWithText("Devices")
        header.assertExists()
        val node = header.fetchSemanticsNode()
        assertTrue("the header is a heading", node.config.contains(SemanticsProperties.Heading))
    }

    @Test
    fun withNoEnginesTheHeaderStaysAndOnlyThePairRowShows() {
        flow.value = null
        mount()
        compose.onNodeWithText("Devices").assertExists()
        row("settings-device:e1").assertDoesNotExist()
        row("settings-pair").assertExists()
    }

    // ---------------------------------------------------------- geometry

    @Test
    fun rowHeightsMatchTheTicket() {
        mount()
        assertDp(
            heightOf(row("settings-device:e1").getUnclippedBoundsInRoot()),
            67.7.dp,
            "engine row height (title + one subtitle line)",
        )
        assertDp(
            heightOf(row("settings-pair").getUnclippedBoundsInRoot()),
            52.dp,
            "pair row height (title only, the minimum)",
        )
        // The leading icon keeps the text column at 56 dp inside the card
        // (x 72 from the screen) on both rows — the "Choose Wallpaper…"
        // row's layout in `2-settings.png`.
        val title = compose.onNodeWithText("MacBook Pro", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(title.left, 72.dp, "engine title left")
        val pairTitle = compose.onNodeWithText("Pair an engine", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(pairTitle.left, 72.dp, "pair title left")
    }

    @Test
    fun theDotAndChevronCentresMatchTheTicketGeometry() {
        mount()
        // Image-local px: the row image starts at the card's left edge
        // (screen x 16 dp); 1 dp = 3 px. The chevron box centre is screen
        // x 363.5 dp = 1042.5 px. The dot sits 12 dp before the chevron's
        // layout box, which is the chevron's 7 dp width since android-next
        // round 46 (UIKit's disclosure accessory hugs the glyph), not the
        // 15.6 dp symbol square: card-relative 370 − 19 − 7 − 12 − 4 = 328,
        // screen x 344.0 dp = 984.0 px (was 339.7 dp = 971.1 px).
        val px = rowImage("settings-device:e1")
        val midY = px.height / 2

        // The dot: success #15803D = (21, 128, 61). Its ink span in the
        // vertical centre band gives the centre (8 dp wide = 24 px).
        var left = -1
        var right = -1
        for (x in 940..1005) {
            var ink = false
            for (y in (midY - 8)..(midY + 8)) {
                if (matches(px[x, y], 21, 128, 61, 25)) {
                    ink = true
                    break
                }
            }
            if (ink) {
                if (left < 0) left = x
                right = x
            }
        }
        assertTrue("no dot ink found", left >= 0)
        val dotCentre = (left + right) / 2f
        assertTrue(
            "dot centre $dotCentre px, expected ~984.0 (screen 344.0 dp)",
            abs(dotCentre - 984.0f) <= 4.5f,
        )

        // The chevron: UIKit tertiaryLabel over white ≈ (196, 196, 199),
        // ink centre = the box centre (the `GroupedListTest` probe).
        val chevron = centroidX(px, 40..115, 990..1080) { c ->
            matches(c, 196, 196, 199, 14)
        }
        assertTrue(
            "chevron centroid $chevron px, expected ~1042.5 (screen 363.5 dp)",
            abs(chevron - 1042.5f) <= 4.5f,
        )

        // The pair row's chevron sits at the same centre.
        val pairPx = rowImage("settings-pair")
        val pairChevron = centroidX(pairPx, 40..115, 990..1080) { c ->
            matches(c, 196, 196, 199, 14)
        }
        assertTrue(
            "pair chevron centroid $pairChevron px, expected ~1042.5",
            abs(pairChevron - 1042.5f) <= 4.5f,
        )
    }

    /**
     * The dot's centre column in the engine row image (image-local, 3 px/dp):
     * card-relative 370 − 19 − 7 − 12 − 4 = 328 dp = 984 px since android-next
     * round 46 (the chevron lays out at its 7 dp width). The old 971 px
     * (339.7 dp) fell just left of the moved dot's 972…996 px span (round 48).
     */
    private val DOT_CENTRE_PX = 984

    @Test
    fun theDotInksSuccessInLight() {
        mount(Appearance.Light)
        // success #15803D = (21, 128, 61), ±8 per channel at the core.
        val px = rowImage("settings-device:e1")
        assertTrue("light dot centre", matches(px[DOT_CENTRE_PX, px.height / 2], 21, 128, 61, 8))
    }

    @Test
    fun theDotInksSuccessInDark() {
        mount(Appearance.Dark)
        // success #34D399 = (52, 211, 153) over elevated #111113.
        val px = rowImage("settings-device:e1")
        assertTrue("dark dot centre", matches(px[DOT_CENTRE_PX, px.height / 2], 52, 211, 153, 8))
    }

    @Test
    fun separatorsRunUnderTheEngineRowOnly() {
        mount()
        val engine = rowImage("settings-device:e1")
        assertTrue("separator under the engine row (First)", engine.hasSeparator(engine.height - 1))

        val pair = rowImage("settings-pair")
        assertFalse("no separator under the pair row (Last)", pair.hasSeparator(pair.height - 1))
    }

    // ------------------------------------------------------- live updates

    @Test
    fun aStateChangeLandsOnTheNextFrameAndNeverTweens() {
        mount()
        compose.mainClock.autoAdvance = false
        setEngines(
            listOf(engineView(id = "e1", name = "MacBook Pro", state = EngineState.Reconnecting)),
        ) { compose.onAllNodesWithText("Reconnecting\u2026", substring = true).fetchSemanticsNodes().isNotEmpty() }

        // The label and the dot change in place, on the next frame.
        compose.onNodeWithText("Reconnecting\u2026", substring = true).assertExists()
        compose.onAllNodesWithText("Connected", substring = true).fetchSemanticsNodes().let { nodes ->
            assertTrue("the old label is gone: ${nodes.size}", nodes.isEmpty())
        }
        // tertiary #97979F = (151, 151, 159), no fade, no colour tween.
        var px = rowImage("settings-device:e1")
        assertTrue("tertiary dot at once", matches(px[DOT_CENTRE_PX, px.height / 2], 151, 151, 159, 8))

        // Ten more frames: the ink never moves (no animation of its own).
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        px = rowImage("settings-device:e1")
        assertTrue("tertiary dot stays", matches(px[DOT_CENTRE_PX, px.height / 2], 151, 151, 159, 8))
    }

    @Test
    fun addingAndRemovingRowsMoveThePairRowAtOnceWithNoItemAnimation() {
        mount()
        compose.mainClock.autoAdvance = false
        val engineHeight = heightOf(row("settings-device:e1").getUnclippedBoundsInRoot())
        val top0 = row("settings-pair").getUnclippedBoundsInRoot().top

        // A pair lands a new row between e1 and pair, in the same frame.
        setEngines(
            listOf(
                engineView(id = "e1", name = "MacBook Pro", state = EngineState.Connected),
                engineView(id = "e2", name = "Studio", state = EngineState.Connected),
            ),
        ) { compose.onAllNodesWithTag("settings-device:e2").fetchSemanticsNodes().isNotEmpty() }
        val e1 = row("settings-device:e1").getUnclippedBoundsInRoot()
        val e2 = row("settings-device:e2").getUnclippedBoundsInRoot()
        val top1 = row("settings-pair").getUnclippedBoundsInRoot().top
        assertTrue("e2 sits below e1", e2.top.value >= e1.bottom.value)
        assertTrue("e2 sits above pair", e2.bottom.value <= top1.value)
        assertDp(top1 - top0, engineHeight, "pair top moved down by one engine row, at once")

        // No animateItem(): after the frame that landed the change, the
        // pair row does not move over the next 10 frames.
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertEquals(top1, row("settings-pair").getUnclippedBoundsInRoot().top)

        // Forgetting e1 removes its row in the same frame; the rows below
        // move up by exactly its height.
        setEngines(
            listOf(engineView(id = "e2", name = "Studio", state = EngineState.Connected)),
        ) { compose.onAllNodesWithTag("settings-device:e1").fetchSemanticsNodes().isEmpty() }
        row("settings-device:e1").assertDoesNotExist()
        val top2 = row("settings-pair").getUnclippedBoundsInRoot().top
        assertDp(top1 - top2, engineHeight, "pair top moved up by one engine row, at once")
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertEquals(top2, row("settings-pair").getUnclippedBoundsInRoot().top)
    }

    // ------------------------------------------------------------- clicks

    @Test
    fun clickingAnEngineRowCallsOnOpenEngineOnceWithItsId() {
        mount()
        row("settings-device:e1").performClick()
        compose.waitForIdle()
        assertEquals(listOf("e1"), openCalls)
        assertEquals("the pair row was not clicked", 0, pairCalls)
    }

    @Test
    fun clickingThePairRowCallsOnPairOnce() {
        mount()
        row("settings-pair").performClick()
        compose.waitForIdle()
        assertEquals(1, pairCalls)
        assertTrue("no engine row was clicked", openCalls.isEmpty())
    }

    @Test
    fun theDefaultEngineHandlerPushesExactlyOneDetailRoute() {
        val stack = NavStack(TabRootRoute(TabId.Settings))
        mountWithStack(stack)
        row("settings-device:e1").performClick()
        compose.waitForIdle()

        assertEquals("one push", 2, stack.entries.size)
        val pushed = stack.entries.last().route
        assertTrue("the pushed route is the Engine detail", pushed is EngineDetailRoute)
        val detail = pushed as EngineDetailRoute
        assertEquals("e1", detail.engineId)
        assertFalse("Settings pushes keep the tab bar", detail.hidesBottomChrome)

        // A second tap while the push is under way pushes nothing: the
        // guard sees Settings is no longer on top.
        row("settings-device:e1").performClick()
        compose.waitForIdle()
        assertEquals("the double-tap guard keeps one detail page", 2, stack.entries.size)
    }

    @Test
    fun theDefaultPairHandlerPresentsThePairPageThroughTheHost() {
        val sheetHost = SheetHostState()
        val stack = NavStack(TabRootRoute(TabId.Settings))
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalNavStack provides stack,
                    LocalNavEntry provides stack.entries.first(),
                ) {
                    SheetHost(sheetHost) {
                        ProvidePairPage(
                            backend = StubPairingBackend,
                            store = MapPairedEngineSink,
                            qrScan = null,
                        ) {
                            val devices = rememberDevicesSectionState(engines = flow)
                            LazyColumn(
                                Modifier
                                    .fillMaxSize()
                                    .background(RobocoTheme.palette.background),
                            ) {
                                devicesSection(devices)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        row("settings-pair").performClick()
        compose.waitForIdle()

        assertTrue("the Pair page is presented", sheetHost.entries.isNotEmpty())
        assertEquals("pair", sheetHost.entries.last().key)
        assertEquals("the pair row pushes nothing", 1, stack.entries.size)
    }

    /**
     * /code-review F4 (ticket 104): inside a sheet (the tablet Settings
     * dialog's card) the Pair page stacks over that sheet through
     * [LocalEnclosingSheet] (`LocalPageSheetScope`); presenting from the
     * root host would be refused while the card is up.
     */
    @Test
    fun insideASheetTheDefaultPairHandlerStacksThePairPageOverIt() {
        val sheetHost = SheetHostState()
        val stack = NavStack(TabRootRoute(TabId.Settings))
        val enclosing = RecordingSheetScope()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalNavStack provides stack,
                    LocalNavEntry provides stack.entries.first(),
                ) {
                    SheetHost(sheetHost) {
                        ProvidePairPage(
                            backend = StubPairingBackend,
                            store = MapPairedEngineSink,
                            qrScan = null,
                        ) {
                            CompositionLocalProvider(LocalPageSheetScope provides enclosing) {
                                val devices = rememberDevicesSectionState(engines = flow)
                                LazyColumn(
                                    Modifier
                                        .fillMaxSize()
                                        .background(RobocoTheme.palette.background),
                                ) {
                                    devicesSection(devices)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        row("settings-pair").performClick()
        compose.waitForIdle()

        assertEquals("the Pair page stacks over the enclosing sheet", listOf("pair"), enclosing.presented)
        assertTrue("the root host presents nothing", sheetHost.entries.isEmpty())
    }

    /** A [PageSheetScope] that records what is stacked on it. */
    private class RecordingSheetScope : PageSheetScope {
        val presented = mutableListOf<String>()
        override val key: String = "settings-dialog"
        override fun dismiss(animated: Boolean) {}
        override fun present(
            key: String,
            style: SheetStyle,
            onDismissed: (() -> Unit)?,
            content: @Composable PageSheetScope.() -> Unit,
        ): Boolean {
            presented += key
            return true
        }
        override val pageBoundsInWindow: Rect = Rect.Zero
        override val pageShape: Shape = RectangleShape
        override var dismissBlocked: Boolean = false
    }

    @Test
    fun withNoPairPageLauncherThePairClickIsASilentNoOp() {
        val sheetHost = SheetHostState()
        val stack = NavStack(TabRootRoute(TabId.Settings))
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalNavStack provides stack,
                    LocalNavEntry provides stack.entries.first(),
                ) {
                    // No ProvidePairPage: LocalPairPage stays null.
                    SheetHost(sheetHost) {
                        val devices = rememberDevicesSectionState(engines = flow)
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .background(RobocoTheme.palette.background),
                        ) {
                            devicesSection(devices)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        row("settings-pair").performClick()
        compose.waitForIdle()

        assertTrue("nothing is presented", sheetHost.entries.isEmpty())
        assertEquals("nothing is pushed", 1, stack.entries.size)
        row("settings-pair").assertExists()
    }

    // ---------------------------------------------------------- semantics

    @Test
    fun theEngineRowMergesTitleSubtitleAndButtonAndTheDotIsSilent() {
        mount()
        val node = row("settings-device:e1").fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = "") { it.text }
            ?: ""
        assertTrue("merged text contains the title: '$texts'", texts.contains("MacBook Pro"))
        assertTrue("merged text contains the subtitle: '$texts'", texts.contains("Connected"))
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])

        // Read order: title, then subtitle.
        val list = node.config.getOrNull(SemanticsProperties.Text) ?: emptyList()
        val joined = list.joinToString("") { it.text }
        assertTrue(
            "title before subtitle: '$joined'",
            joined.indexOf("MacBook Pro") < joined.indexOf("Connected"),
        )

        // The dot (and the chevron, the icon) have no semantics node of
        // their own: the whole unmerged subtree under the row carries
        // exactly the two text nodes — nothing else.
        val unmerged = compose
            .onNodeWithTag("settings-device:e1", useUnmergedTree = true)
            .fetchSemanticsNode()
        val subtreeTexts = mutableListOf<String>()
        collectTexts(unmerged, subtreeTexts)
        assertEquals(listOf("MacBook Pro", "Connected"), subtreeTexts)
    }

    /** Every text in the unmerged subtree, in tree order. */
    private fun collectTexts(node: SemanticsNode, out: MutableList<String>) {
        node.config.getOrNull(SemanticsProperties.Text)?.let { list -> out += list.map { it.text } }
        for (child in node.children) collectTexts(child, out)
    }

    @Test
    fun thePairRowReadsAsAButton() {
        mount()
        val node = row("settings-pair").fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = "") { it.text }
            ?: ""
        assertEquals("Pair an engine", texts)
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
    }

    // ------------------------------------------------------ the lazy keys

    /**
     * Records every item the DSL emits as "key|contentType" strings. Never
     * composes: the content lambdas are stored, not invoked, so the key
     * and content-type surface is testable without a layout (the
     * `GroupedListTest` method).
     */
    private class RecordingLazyListScope : LazyListScope {
        val calls = mutableListOf<String>()

        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            calls += "$key|$contentType"
        }

        override fun items(
            count: Int,
            key: ((index: Int) -> Any)?,
            contentType: (index: Int) -> Any?,
            itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
        ) {
            for (i in 0 until count) {
                calls += "${key?.invoke(i)}|${contentType?.invoke(i)}"
            }
        }
    }

    @Test
    fun theSectionEmitsTheTicketKeysAndContentTypes() {
        val scope = RecordingLazyListScope()
        val state = DevicesSectionState(
            mutableStateOf(
                listOf(
                    engineView(id = "e1", name = "MacBook Pro", state = EngineState.Connected),
                    engineView(id = "e2", name = "Studio", state = EngineState.Reconnecting),
                ),
            ),
            onOpenEngine = {},
            onPair = {},
        )
        scope.devicesSection(state)
        assertEquals(
            listOf(
                "grouped:devices:header|grouped-header",
                "grouped:devices:row:device:e1|grouped-row",
                "grouped:devices:row:device:e2|grouped-row",
                "grouped:devices:row:pair|grouped-row",
            ),
            scope.calls,
        )
    }

    @Test
    fun aNullListEmitsOnlyTheHeaderAndThePairRow() {
        val scope = RecordingLazyListScope()
        val state = DevicesSectionState(
            mutableStateOf<List<EngineView>?>(null),
            onOpenEngine = {},
            onPair = {},
        )
        scope.devicesSection(state)
        assertEquals(
            listOf(
                "grouped:devices:header|grouped-header",
                "grouped:devices:row:pair|grouped-row",
            ),
            scope.calls,
        )
    }

    // ------------------------------------------------------- the fakes

    /** The Pair page's backend stub: the page is only presented, never redeemed here. */
    private object StubPairingBackend : PairingBackend {
        override fun parse(input: String): PairingTarget? = null

        override suspend fun pair(url: String, replaceEngineId: String?): PairedEngine =
            PairedEngine(
                engineId = "e-new",
                baseUrl = "http://127.0.0.1:1",
                credential = "credential",
                sessionId = "session",
                label = "label",
                pairedAtMs = 0L,
                deviceId = null,
                name = null,
            )

        override suspend fun forget(engineId: String) {}

        override fun parseRoom(input: String): RoomInviteTarget? = null

        override suspend fun joinRoom(invite: String): RoomJoinResult =
            throw UnsupportedOperationException("this stub joins no rooms")
    }

    /** The in-memory engine sink: the ticket's test-side fake. */
    private object MapPairedEngineSink : PairedEngineSink {
        override fun knownEngineIds(): Set<String> = emptySet()

        override suspend fun upsertRoom(room: RoomRecord) {}

        override suspend fun upsert(engine: PairedEngine) {}

        override suspend fun remove(engineId: String) {}
    }
}
