package sh.roboco.android.design

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import sh.roboco.android.core.Face

/**
 * Ticket 19's host behaviour (Robolectric, `createAndroidComposeRule`): the
 * iPhone geometry on a 393 × 852 dp window with zero insets (stack 377 wide
 * at 8 dp, action card 324.9 / 119.8 dp, rows 56, Cancel 56 at 844, gap 8,
 * the landscape width, the many-rows height cap + scrolling), the checked
 * row (selected semantics, centred titles under the 44 dp padding), the
 * scrim pixel over white, the present/dismiss timeline through the frozen
 * clock (the stack starts below the window, settles with no overshoot,
 * `onSelect` only after the 250 ms exit, the one-shot guard), the four
 * cancel paths (Cancel, scrim, back, hardware Escape), the
 * blocked-under-scrim rule, the refuse-while-up rule with its warning, the
 * re-show from a callback, Reduce Motion (fade, no translation), font scale
 * 2.0, and the accessibility pane. Written per the test policy; the batch
 * run executes it.
 *
 * All tag/text lookups use the **unmerged** semantics tree: the overlay
 * root is focusable, and a focusable node may merge descendants — the
 * unmerged tree keeps every tagged node addressable either way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ActionSheetHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val handlers = mutableListOf<String>()
    private val appTaps = mutableListOf<String>()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ fixtures

    /**
     * The app under the sheet: a **white** tappable box, so a blocked tap is
     * visible and the scrim's settled pixel reads `#CCCCCC` (0.2 black over
     * white).
     */
    @Composable
    private fun DemoContent() {
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .testTag("app-content")
                .clickable { appTaps += "app" },
        ) {
            BasicText("app", style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        }
    }

    private fun host(content: @Composable () -> Unit = { DemoContent() }): ActionSheetHostState {
        val state = ActionSheetHostState()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ActionSheetHost(state = state) { content() }
            }
        }
        return state
    }

    private fun px(dp: Float): Float = with(compose.density) { dp.dp.toPx() }

    private fun px(dp: Int): Float = px(dp.toFloat())

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)

    /** `getBoundsInRoot` is a [androidx.compose.ui.unit.DpRect]; the assertions compare px. */
    private fun SemanticsNodeInteraction.pxBounds(): Rect = with(compose.density) { getBoundsInRoot().toRect() }

    private fun bounds(tag: String): Rect = node(tag).pxBounds()

    /** Presents [model] on the frozen clock and advances past the 0.4 s spring. */
    private fun presentFrozen(state: ActionSheetHostState, model: ActionSheetModel) {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(model) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(500)
    }

    /** The Wallpaper Effect fixture (Dither checked) with recorded handlers. */
    private val wallpaperSheet = wallpaperEffectSheet(
        checked = 1,
        onSelect = { handlers += "select:${WallpaperEffectLabels[it]}" },
        onCancel = { handlers += "cancel" },
    )

    private val deleteSheet = ActionSheetModel(
        title = "Delete “Backend”?",
        message = "Its sessions move back to the main list.",
        actions = listOf(
            ActionSheetAction("Delete Section", style = ActionSheetStyle.Destructive) { handlers += "delete" },
        ),
        onCancel = { handlers += "cancel" },
    )

    private val moveSheet = ActionSheetModel(
        title = "Move to Section",
        actions = listOf("Backend", "Design review", "No Section").map { name ->
            ActionSheetAction(name) { handlers += "select:$name" }
        },
        onCancel = { handlers += "cancel" },
    )

    private val manyRowsSheet = ActionSheetModel(
        title = "Move to Section",
        actions = (1..20).map { i -> ActionSheetAction("Section $i") { handlers += "select:Section $i" } },
        onCancel = { handlers += "cancel" },
    )

    // ------------------------------------------------------------ geometry

    @Test
    fun theWallpaperSheetMatchesTheIPhoneGeometry() {
        val state = host()
        presentFrozen(state, wallpaperSheet)

        val window = compose.onRoot().pxBounds()
        val card = bounds("action-sheet-card")
        val cancel = bounds("action-sheet-cancel")

        // Stack: 377 wide, 8 dp from each side, centred.
        assertEquals(px(8), card.left, 1f)
        assertEquals(px(377), card.width, 1f)
        assertEquals(card.left, window.right - card.right, 1f)

        // Action card: header 14 + 16.9 + 14 + 5 rows × 56 = 324.9.
        assertEquals(px(324.9f), card.height, 1f)

        // Cancel card: 56 high, its bottom edge at 852 − 8 = 844, 8 dp under
        // the action card, same width.
        assertEquals(px(56), cancel.height, 1f)
        assertEquals(px(8), window.bottom - cancel.bottom, 1f)
        assertEquals(px(8), cancel.top - card.bottom, 1f)
        assertEquals(card.left, cancel.left, 1f)
        assertEquals(card.width, cancel.width, 1f)

        // Every row exactly 56 at font factor 1.0.
        for (i in 0..4) {
            assertEquals("row $i", px(56), bounds("action-sheet-row-$i").height, 1f)
        }
    }

    @Test
    fun theDeleteFixtureIs119DpWithCentredHeaderText() {
        val state = host()
        presentFrozen(state, deleteSheet)

        val card = bounds("action-sheet-card")
        assertEquals(px(119.8f), card.height, 1f)
        assertEquals(px(56), bounds("action-sheet-row-0").height, 1f)

        // Title and message centred on the card (unmerged: the BasicText
        // nodes themselves).
        val title = text("Delete “Backend”?").pxBounds()
        val message = text("Its sessions move back to the main list.").pxBounds()
        assertEquals(card.center.x, title.center.x, 1f)
        assertEquals(card.center.x, message.center.x, 1f)
        assertTrue("title above message", title.bottom <= message.top)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w852dp-h393dp-xxhdpi")
    fun landscapeKeepsThePortraitWidthCentred() {
        val state = host()
        presentFrozen(state, wallpaperSheet)
        val card = bounds("action-sheet-card")
        assertEquals(px(377), card.width, 1f)
        assertEquals(card.left, compose.onRoot().pxBounds().right - card.right, 1f)
    }

    // ---------------------------------------------------- the checked row

    @Test
    fun theCheckedRowCarriesSelectedAndKeepsTitlesCentred() {
        val state = host()
        presentFrozen(state, wallpaperSheet)
        val card = bounds("action-sheet-card")

        node("action-sheet-row-1").assertIsSelected()
        for (i in 0..4) {
            if (i == 1) continue
            assertTrue(
                "row $i must not be selected",
                SemanticsProperties.Selected !in node("action-sheet-row-$i").fetchSemanticsNode().config,
            )
        }
        // With a check present every title pads to 44 yet stays centred on
        // the card — including the checked row's own.
        for (i in 0..4) {
            val title = text(WallpaperEffectLabels[i]).pxBounds()
            assertEquals("row $i title centred", card.center.x, title.center.x, 1.5f)
        }
    }

    // ------------------------------------------------------- the scrim

    @Test
    fun theScrimSettlesAtBlack20OverWhite() {
        val state = host()
        compose.runOnUiThread { state.show(wallpaperSheet) }
        compose.waitForIdle() // present + settle on the auto clock

        // A pixel above the cards, over the white backdrop: 0.8 × 255 = 204.
        // Compose colours are float components; ±0.005 ≈ ±1 of 255.
        // (200, 200) dp: clear of DemoContent's "app" label at the top-left
        // corner (10, 10 dp lands on its glyph) and well above the cards
        // (their top sits near 455 dp).
        val pixel = compose.onRoot().captureToImage().toPixelMap()[px(200).toInt(), px(200).toInt()]
        assertEquals(204f / 255f, pixel.red, 0.005f)
        assertEquals(204f / 255f, pixel.green, 0.005f)
        assertEquals(204f / 255f, pixel.blue, 0.005f)
    }

    /**
     * User bug (round 44, the 3bd01174 debug APK on a phone): option taps
     * "don't do anything", intermittently. A real finger's tap carries a few
     * ACTION_MOVEs between down and up, and the cards' tap swallower consumed
     * them, so the row's clickable cancelled the tap on the Final pass. A
     * finger-like tap (moves well inside touch slop) must select, every time.
     */
    @Test
    fun aFingerTapWithSmallMovesSelectsTheOption() {
        val state = host()
        for (attempt in 1..3) {
            handlers.clear()
            compose.runOnUiThread { state.show(wallpaperSheet) }
            compose.waitForIdle() // present + settle on the auto clock
            compose.onNodeWithTag("action-sheet-row-2").performTouchInput {
                down(center)
                moveBy(Offset(0f, 2f))
                moveBy(Offset(3f, -1f))
                moveBy(Offset(-1f, 1f))
                up()
            }
            compose.waitForIdle() // the 250 ms dismiss, then onSelect
            assertEquals("attempt $attempt", listOf("select:ASCII"), handlers)
        }
    }

    // -------------------------------------------------- present / dismiss

    private fun hasSheetKey(key: SemanticsPropertyKey<Float>): SemanticsMatcher =
        SemanticsMatcher("has ${key.name}") { it.config.contains(key) }

    private fun sheetValue(key: SemanticsPropertyKey<Float>): Float {
        val nodes = compose.onAllNodes(hasSheetKey(key), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no ${key.name} node in the semantics tree", nodes.isNotEmpty())
        return nodes.first().config[key]
    }

    @Test
    fun theStackStartsBelowTheWindowAndSettlesWithoutOvershoot() {
        val state = host()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(wallpaperSheet) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeByFrame() // frame 0: composed, progress 0

        assertEquals(0f, sheetValue(ActionSheetProgressKey), 0.001f)

        // One more frame so the stack is measured (onSizeChanged lands);
        // the spring has barely moved, so the translation still holds the
        // stack below the window.
        compose.mainClock.advanceTimeByFrame()
        val stackPx = bounds("action-sheet-card").height + px(8) + bounds("action-sheet-cancel").height
        val offset = sheetValue(ActionSheetOffsetKey)
        assertTrue(
            "the stack starts below the window (offset $offset vs stack $stackPx)",
            offset >= 0.9f * stackPx,
        )

        // Critically damped (ζ = 1): the progress never passes 1 — the cards
        // never rise above their rest position.
        var elapsed = 0
        while (elapsed < 500) {
            compose.mainClock.advanceTimeBy(50)
            elapsed += 50
            assertTrue(
                "no overshoot at ${elapsed} ms",
                sheetValue(ActionSheetProgressKey) <= 1.0001f,
            )
        }
        assertEquals(1f, sheetValue(ActionSheetProgressKey), 0.01f)
        assertTrue("settled offset must be ~0", sheetValue(ActionSheetOffsetKey) < 1f)
    }

    @Test
    fun aRowTapRunsOnSelectOnlyAfterThe250MsDismiss() {
        val state = host()
        presentFrozen(state, moveSheet)

        node("action-sheet-row-0").performClick()
        // The click's up launches the exit; its animateTo takes the NEXT
        // frame as its start (play time 0).
        compose.mainClock.advanceTimeByFrame()

        // advanceTimeBy rounds up to whole 16 ms frames, so 249 would be 16
        // frames = 256 ms of play time — past the 250 ms fall. 240 is exactly
        // 15 frames: the last frame whose play time (240 ms) is still < 250.
        compose.mainClock.advanceTimeBy(240)
        assertTrue("onSelect must not run before the dismiss ends", handlers.isEmpty())
        node("action-sheet").assertExists()

        // One more frame (256 ms ≥ 250): the fall ends, onSelect runs.
        compose.mainClock.advanceTimeBy(1)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf("select:Backend"), handlers)
        node("action-sheet").assertDoesNotExist()
    }

    @Test
    fun aSecondTapDuringTheExitDoesNothing() {
        val state = host()
        presentFrozen(state, moveSheet)

        node("action-sheet-row-0").performClick()
        compose.mainClock.advanceTimeByFrame()

        // The exit disabled the rows: the one-shot guard plus the disabled
        // semantics together mean no second action can fire.
        node("action-sheet-row-0").assertIsNotEnabled()
        node("action-sheet-cancel").assertIsNotEnabled()

        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the guard must run the handler exactly once", listOf("select:Backend"), handlers)
        node("action-sheet").assertDoesNotExist()
    }

    // --------------------------------------------------- the cancel paths

    @Test
    fun tappingCancelRunsOnCancelAfterTheExitAndNeverAnOnSelect() {
        val state = host()
        val sheet = ActionSheetModel(
            actions = listOf(ActionSheetAction("Row") { handlers += "select" }),
            onCancel = { handlers += "cancel" },
        )
        compose.runOnUiThread { state.show(sheet) }
        compose.waitForIdle()

        node("action-sheet-cancel").performClick()
        compose.waitForIdle() // runs the 250 ms exit on the auto clock
        assertEquals(listOf("cancel"), handlers)
        node("action-sheet").assertDoesNotExist()
    }

    @Test
    fun aScrimTapRunsOnCancelAndNeverReachesTheApp() {
        val state = host()
        compose.runOnUiThread { state.show(moveSheet) }
        compose.waitForIdle()

        compose.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        compose.waitForIdle()
        assertEquals(listOf("cancel"), handlers)
        assertTrue("a tap under the scrim must not reach the app", appTaps.isEmpty())
        node("action-sheet").assertDoesNotExist()

        // With the sheet gone the app is tappable again.
        node("app-content").performClick()
        compose.waitForIdle()
        assertEquals(listOf("app"), appTaps)
    }

    @Test
    fun backRunsOnCancel() {
        val state = host()
        compose.runOnUiThread { state.show(moveSheet) }
        compose.waitForIdle()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("cancel"), handlers)
        assertTrue("the app must not see the back as a tap", appTaps.isEmpty())
        node("action-sheet").assertDoesNotExist()
    }

    @Test
    fun hardwareEscapeRunsOnCancel() {
        val state = host()
        compose.runOnUiThread { state.show(moveSheet) }
        compose.waitForIdle() // the overlay root took focus on present

        node("action-sheet").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals(listOf("cancel"), handlers)
        node("action-sheet").assertDoesNotExist()
    }

    @Test
    fun nothingUnderTheScrimIsReachableWhileTheSheetIsLeaving() {
        val state = host()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(moveSheet) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(500)

        // The exit starts (Cancel); while the sheet is still on screen a raw
        // tap where the app lives must stay blocked by the scrim.
        node("action-sheet-cancel").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().performTouchInput { click(Offset(10f, 10f)) }

        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("exactly one cancel", listOf("cancel"), handlers)
        assertTrue("no app tap through the leaving scrim", appTaps.isEmpty())
        node("action-sheet").assertDoesNotExist()
    }

    // -------------------------------------------------- the host's rules

    @Test
    fun showWhileASheetIsUpIsRefusedWithAWarning() {
        val state = host()
        ShadowLog.clear()
        compose.runOnUiThread { state.show(moveSheet) }
        compose.waitForIdle()
        compose.runOnUiThread { state.show(deleteSheet) }
        compose.waitForIdle()

        // The first sheet is unchanged; the second never presented.
        text("Move to Section").assertExists()
        text("Delete “Backend”?").assertDoesNotExist()
        val warned = ShadowLog.getLogs().mapNotNull { it.msg }.any { it.contains("already presented") }
        assertTrue("the refused show must log a warning", warned)

        compose.runOnUiThread { state.show(deleteSheet) } // still up: refused again
        compose.waitForIdle()
        text("Delete “Backend”?").assertDoesNotExist()
    }

    @Test
    fun aCallbackThatShowsAgainPresentsAfterTheDismiss() {
        val state = host()
        val second = ActionSheetModel(
            title = "Second",
            actions = listOf(ActionSheetAction("Done") { handlers += "done" }),
        )
        val first = ActionSheetModel(
            title = "First",
            actions = listOf(ActionSheetAction("Row") { state.show(second) }),
        )
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(first) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(500)

        // The row's callback re-shows; the host is already cleared when it
        // runs, so the second sheet presents after the exit.
        node("action-sheet-row-0").performClick()
        compose.mainClock.advanceTimeBy(400) // 250 ms exit + the 0.4 s rise
        compose.mainClock.advanceTimeByFrame()
        text("Second").assertExists()
        text("First").assertDoesNotExist()

        node("action-sheet-row-0").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf("done"), handlers)
    }

    @Test
    fun theHostProvidesItsPresenterAndTheNoopLogs() {
        val state = ActionSheetHostState()
        var presenter: ActionSheetPresenter? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ActionSheetHost(state = state) {
                    presenter = LocalActionSheet.current
                    DemoContent()
                }
            }
        }
        compose.waitForIdle()
        assertSame(state, presenter)

        // The ticket's test note: show through the captured
        // LocalActionSheet.current, as a caller would.
        compose.runOnUiThread { presenter!!.show(moveSheet) }
        compose.waitForIdle()
        node("action-sheet").assertExists()

        ShadowLog.clear()
        ActionSheetPresenter.Noop.show(moveSheet)
        val dropped = ShadowLog.getLogs().mapNotNull { it.msg }.any { it.contains("No ActionSheetHost mounted") }
        assertTrue("the Noop presenter must log the drop", dropped)
    }

    @Test
    fun aCallbackThatShowsAToastRunsAfterTheDismiss() {
        // Ticket 35's mount order: the toast host sits OUTSIDE the sheet host,
        // so a toast raised from an action callback draws above the sheet —
        // and the host is already cleared when the callback runs.
        val state = ActionSheetHostState()
        var toaster: Toaster? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ToastHost {
                    toaster = LocalToaster.current
                    ActionSheetHost(state = state) { DemoContent() }
                }
            }
        }
        compose.waitForIdle()
        val sheet = ActionSheetModel(
            actions = listOf(ActionSheetAction("Row") { toaster!!.show("Moved to Backend") }),
        )

        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(sheet) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(500)

        node("action-sheet-row-0").performClick()
        compose.mainClock.advanceTimeBy(400) // the 250 ms exit, then the callback
        compose.mainClock.advanceTimeByFrame()

        node("action-sheet").assertDoesNotExist()
        node("toast").assertExists()
    }

    @Test
    fun showIsMainThreadOnly() {
        val state = ActionSheetHostState()
        var failure: Throwable? = null
        val thread = Thread {
            runCatching { state.show(moveSheet) }.onFailure { failure = it }
        }
        thread.start()
        thread.join()
        assertTrue(
            "show off the main thread must fail the check (was $failure)",
            failure is IllegalStateException,
        )
    }

    // ------------------------------------------------------ reduce motion

    @Test
    fun reducedMotionFadesWithoutTranslating() {
        ReduceMotion.debugOverride = true
        val state = host()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(wallpaperSheet) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeByFrame()

        assertEquals(0f, sheetValue(ActionSheetProgressKey), 0.001f)
        assertEquals("no translation under Reduce Motion", 0f, sheetValue(ActionSheetOffsetKey), 0.001f)

        compose.mainClock.advanceTimeBy(250) // the 250 ms fade
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, sheetValue(ActionSheetProgressKey), 0.01f)
        assertEquals(0f, sheetValue(ActionSheetOffsetKey), 0.001f)
    }

    // ---------------------------------------------------- many rows + cap

    @Test
    fun manyRowsCapTheCardScrollAndKeepCancelVisible() {
        val state = host()
        presentFrozen(state, manyRowsSheet)

        val window = compose.onRoot().pxBounds()
        val card = bounds("action-sheet-card")
        val cancel = bounds("action-sheet-cancel")

        // The cap: 852 − 0 − 8 − 8 − 56 − 8 = 772; the card's top sits 8 dp
        // down the window.
        assertEquals(px(772), card.height, 1.5f)
        assertEquals(px(8), card.top - window.top, 1.5f)

        // The header stays put at the top of the card; the tail rows are
        // offscreen until scrolled.
        val title = text("Move to Section").pxBounds()
        assertEquals(px(14), title.top - card.top, 1.5f)
        node("action-sheet-row-0").assertIsDisplayed()
        node("action-sheet-row-19").assertIsNotDisplayed()

        // The ScrollBy semantics action animates (foundation's
        // semanticsScrollBy runs `animate`): on the frozen clock the scroll
        // never moves, and performScrollToNode re-scrolls until the end of
        // the range forever. Let the clock run for the scroll.
        compose.mainClock.autoAdvance = true
        node("action-sheet-rows").performScrollToNode(hasTestTag("action-sheet-row-19"))
        compose.waitForIdle()
        node("action-sheet-row-19").assertIsDisplayed()

        // Scrolling never moves the card or the Cancel card.
        assertEquals(px(772), bounds("action-sheet-card").height, 1.5f)
        assertEquals(px(8), window.bottom - bounds("action-sheet-cancel").bottom, 1f)
        assertEquals(px(56), cancel.height, 1f)
    }

    // -------------------------------------------------------- font scale

    @Test
    fun fontScale2ClampsToFactor1_6AndGrowsRows() {
        // The clamp: at font scale 2.0 the body factor exceeds 1.6 → 1.6.
        assertEquals(1.6f, TypeScale.from(Density(3f, 2f)).factor, 0.001f)

        val state = ActionSheetHostState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 2f)) {
                RobocoTheme(Appearance.Light) {
                    ActionSheetHost(state = state) { DemoContent() }
                }
            }
        }
        compose.runOnUiThread { state.show(wallpaperSheet) }
        compose.waitForIdle()

        // scaled(18) = 29 dp → line box 37.7 dp → row 24 + 37.7 = 61.7 dp.
        assertEquals(px(61.7f), bounds("action-sheet-row-0").height, 1.5f)
        val title = text("None").pxBounds()
        assertEquals("the 29 dp title's 1.3 line box", px(37.7f), title.height, 1.5f)
    }

    // ------------------------------------------------------ accessibility

    @Test
    fun theSheetIsAPaneWithButtonRowsAndHiddenContent() {
        val state = host()
        compose.runOnUiThread { state.show(wallpaperSheet) }
        compose.waitForIdle()

        val sheet = node("action-sheet").fetchSemanticsNode()
        assertEquals("Wallpaper Effect", sheet.config[SemanticsProperties.PaneTitle])
        assertTrue(sheet.config.contains(SemanticsProperties.IsTraversalGroup))

        // Rows are buttons; the checked row reads Selected; the Cancel card
        // is a button too.
        for (i in 0..4) {
            val config = node("action-sheet-row-$i").fetchSemanticsNode().config
            assertEquals("row $i role", Role.Button, config[SemanticsProperties.Role])
        }
        assertTrue(
            "the checked row must be selected",
            SemanticsProperties.Selected in node("action-sheet-row-1").fetchSemanticsNode().config,
        )
        assertEquals(
            Role.Button,
            node("action-sheet-cancel").fetchSemanticsNode().config[SemanticsProperties.Role],
        )

        // The wrapped content is hidden from accessibility while the sheet
        // is up.
        val hidden = compose
            .onAllNodes(
                SemanticsMatcher("has HideFromAccessibility") {
                    it.config.contains(SemanticsProperties.HideFromAccessibility)
                },
            )
            .fetchSemanticsNodes()
        assertTrue("the wrapped content must be hidden from a11y while the sheet is up", hidden.isNotEmpty())
    }
}
