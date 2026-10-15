package sh.roboco.android.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
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
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassPressModifierScaleKey
import sh.roboco.android.design.glass.GlassPressScaleKey
import sh.roboco.android.sessions.FolderRoute
import sh.roboco.android.sessions.LocalSidebarFixtures
import sh.roboco.android.sessions.SessionSearch
import sh.roboco.android.sessions.SessionsScreenActions
import sh.roboco.android.sessions.SidebarFixtures
import sh.roboco.android.sessions.sessionsOptionsMenu
import sh.roboco.android.shell.tablet.Sidebar
import sh.roboco.android.shell.tablet.SidebarRootRoute
import sh.roboco.android.shell.tablet.SidebarState
import sh.roboco.android.shell.tablet.sidebarGalleryFixtures

/**
 * The tablet sidebar's bottom toolbar (android-ui-parity #107) at
 * 360 × 800 dp × 2.0 (xhdpi): the toolbar alone in 105's slot geometry
 * (full width × 44 dp, bottom `barBottom` = navigation bars + 12 —
 * Robolectric reports none, so 12 dp, the row spanning y 744 → 788) plus
 * one test wired through the whole [Sidebar] over 105's fixture.
 *
 * The host mirrors the app root's order (`RobocoTheme`, a
 * `GlassMenuHost` for 15's panel — the composition-local router and the
 * recorder actions stand in for 106 and 46) under a recording
 * [Feedback]: iOS fires no haptic for any of the three items.
 *
 * Ticket 24's test hooks (`holdHostlessOps` + `freeze`) hold transitions
 * mid-flight so the fade's alpha (read through [SidebarToolbarAlphaKey])
 * and the tap blocking can be asserted at a frozen progress, exactly as
 * UIKit's cross-fade tracks the gesture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class SidebarToolbarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ------------------------------------------------------------ the fakes

    /** The recording router: one line per call, the tap tests assert on it. */
    private class RecordingRouter : AppRouter {
        val calls = mutableListOf<String>()
        override fun openSession(chatId: String) {
            calls += "openSession($chatId)"
        }
        override fun openSession(chatId: String, handoff: DraftHandoff?) {
            calls += "openSession($chatId, handoff)"
        }
        override fun presentNewSession(prompt: String?) {
            calls += "presentNewSession(${prompt ?: "null"})"
        }
        override fun showSettings() {
            calls += "showSettings()"
        }
        override fun showSearch() {
            calls += "showSearch()"
        }
        override fun showSessionOpenError() {
            calls += "showSessionOpenError()"
        }
        override fun popToFrontPage() {
            calls += "popToFrontPage()"
        }
    }

    /**
     * The recording actions behind the ⋯ menu: one line per call, the
     * Group-by-Project pref lives in [grouped] (read at build time, so the
     * check is fresh on every open), and "Archived" pushes the folder onto
     * the sidebar's own stack.
     */
    private class RecordingActions(private val sidebar: NavStack) : SessionsScreenActions {
        var grouped = false
        val calls = mutableListOf<String>()
        override val groupByProject: Boolean get() = grouped
        override fun setGroupByProject(on: Boolean) {
            grouped = on
            calls += "setGroupByProject($on)"
        }
        override fun newSection() {
            calls += "newSection()"
        }
        override fun collapseAll() {
            calls += "collapseAll()"
        }
        override fun toggleSection(id: String) {
            calls += "toggleSection($id)"
        }
        override fun openPinned() {
            calls += "openPinned()"
        }
        override fun openArchived() {
            calls += "openArchived()"
            sidebar.push(FolderRoute.archived(), animated = true)
        }
        override fun openSection(id: String, name: String) {
            calls += "openSection($id)"
        }
        override fun renameSection(id: String, currentName: String) {
            calls += "renameSection($id)"
        }
        override fun deleteSection(id: String) {
            calls += "deleteSection($id)"
        }
        override fun userSectionName(id: String): String? = null
    }

    /** Records every haptic and cue: the toolbar fires none of either. */
    private class RecordingFeedback : Feedback {
        val haptics = mutableListOf<Haptic>()
        val cues = mutableListOf<Cue>()
        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }
        override fun cue(cue: Cue) {
            cues += cue
        }
    }

    private val stack = NavStack(SidebarRootRoute)
    private val router = RecordingRouter()
    private val actions = RecordingActions(stack)
    private val feedback = RecordingFeedback()

    // The wired test's own trio (105's fixture search answers nothing).
    private val sidebarState = SidebarState()
    private val sidebarActions = RecordingActions(sidebarState.stack)

    /** A search that never answers: the wired test never types. */
    private val emptySearch: SessionSearch = { _, _ -> emptyList() }

    private val fixtures: SidebarFixtures = sidebarGalleryFixtures(emptySearch)

    // -------------------------------------------------------------- mount

    /**
     * The bare slot: [content] in a full-width, 44-dp-high box whose bottom
     * sits 12 dp above the window bottom (105's `barBottom` with no
     * navigation bar in Robolectric).
     */
    private fun mountSlot(
        direction: LayoutDirection = LayoutDirection.Ltr,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides direction,
                    LocalFeedback provides feedback,
                ) {
                    GlassMenuHost(hazeState = rememberHazeState()) {
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .height(44.dp),
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The stateful toolbar bound to [stack] (the sidebar's own). */
    private fun mountToolbar(direction: LayoutDirection = LayoutDirection.Ltr) {
        mountSlot(direction) {
            SidebarToolbar(
                sidebarStack = stack,
                router = router,
                optionsMenu = { sessionsOptionsMenu(actions) },
            )
        }
    }

    /** The whole sidebar (105) with the toolbar wired into its slot. */
    private fun mountSidebar() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    ContextMenuHost {
                        GlassMenuHost(hazeState = rememberHazeState()) {
                            CompositionLocalProvider(
                                LocalSidebarFixtures provides fixtures,
                            ) {
                                Sidebar(
                                    state = sidebarState,
                                    currentChatId = null,
                                    shown = true,
                                    router = router,
                                    topInset = 24.dp,
                                    toolbar = { sidebarStack ->
                                        SidebarToolbar(
                                            sidebarStack = sidebarStack,
                                            router = router,
                                            optionsMenu = { sessionsOptionsMenu(sidebarActions) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ helpers

    /** px of [v] dp at the test density (2). */
    private fun dp(v: Float): Float = with(compose.density) { v.dp.toPx() }

    /** `getBoundsInRoot` is a DpRect; the assertions compare px. */
    private fun SemanticsNodeInteraction.pxBounds(): Rect =
        with(compose.density) { getBoundsInRoot().toRect() }

    /** The toolbar row's fade, through the test-only semantics key. */
    private fun toolbarAlpha(): Float =
        compose.onNodeWithTag("sidebar-toolbar").fetchSemanticsNode()
            .config[SidebarToolbarAlphaKey]

    /** True when the menu row carries the checked (selected) semantics. */
    private fun selected(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Selected) == true

    private fun hasButtonRole(): SemanticsMatcher =
        SemanticsMatcher("has Button role") { it.config[SemanticsProperties.Role] == Role.Button }

    /** Every published value of [key] in the semantics tree. */
    private fun pressScales(key: SemanticsPropertyKey<Float>): List<Float> {
        val matcher = SemanticsMatcher("has ${key.name}") { it.config.contains(key) }
        return compose.onAllNodes(matcher).fetchSemanticsNodes().map { it.config[key] }
    }

    /** Picks a menu row and runs the close fade out. */
    private fun pickRow(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    /** A stack write on the UI thread, published to the Recomposer. */
    private fun stackOp(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun assertPixel(px: PixelMap, x: Int, y: Int, r: Int, g: Int, b: Int, what: String) {
        val c = px[x, y]
        fun ok(v: Float, e: Int) = abs(v - e / 255f) <= 2f / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${(c.red * 255f).roundToInt()}, ${(c.green * 255f).roundToInt()}, " +
                "${(c.blue * 255f).roundToInt()}), expected ($r, $g, $b) ±2",
            ok(c.red, r) && ok(c.green, g) && ok(c.blue, b),
        )
    }

    // ---------------------------------------------------------- geometry

    @Test
    fun theRowMatchesTheTicketGeometry() {
        mountToolbar()

        // The row: full sidebar width × 44, its bottom 12 above the window.
        val row = compose.onNodeWithTag("sidebar-toolbar").pxBounds()
        assertEquals(dp(360f), row.width, 1f)
        assertEquals(dp(44f), row.height, 1f)
        assertEquals(dp(0f), row.left, 1f)
        assertEquals(dp(788f), row.bottom, 1f)

        // The capsule spans x 16 → 104 (88 × 44): the two slots' union.
        val gear = compose.onNodeWithTag("sidebar-settings").pxBounds()
        assertEquals(dp(16f), gear.left, 1f)
        assertEquals(dp(60f), gear.right, 1f)
        assertEquals(dp(38f), gear.center.x, 1f)

        val options = compose.onNodeWithTag("sidebar-options").pxBounds()
        assertEquals(dp(60f), options.left, 1f)
        assertEquals(dp(104f), options.right, 1f)
        assertEquals(dp(82f), options.center.x, 1f)
        assertEquals(dp(88f), options.right - gear.left, 1f)

        val plus = compose.onNodeWithTag("new-session").pxBounds()
        assertEquals(dp(300f), plus.left, 1f)
        assertEquals(dp(344f), plus.right, 1f)
        assertEquals(dp(322f), plus.center.x, 1f)

        // All three are 44 × 44 and vertically centred in the row
        // (row 744 → 788, centre 766).
        for (tag in listOf("sidebar-settings", "sidebar-options", "new-session")) {
            val b = compose.onNodeWithTag(tag).pxBounds()
            assertEquals("$tag width", dp(44f), b.width, 1f)
            assertEquals("$tag height", dp(44f), b.height, 1f)
            assertEquals("$tag centred", dp(766f), b.center.y, 1.5f)
        }
    }

    @Test
    fun rtlMirrorsTheRow() {
        mountToolbar(LayoutDirection.Rtl)

        // The capsule is on the right with the gear rightmost; the + left.
        val gear = compose.onNodeWithTag("sidebar-settings").pxBounds()
        assertEquals(dp(300f), gear.left, 1f)
        assertEquals(dp(344f), gear.right, 1f)
        assertEquals(dp(322f), gear.center.x, 1f)

        val options = compose.onNodeWithTag("sidebar-options").pxBounds()
        assertEquals(dp(256f), options.left, 1f)
        assertEquals(dp(300f), options.right, 1f)
        assertEquals(dp(278f), options.center.x, 1f)
        assertEquals(dp(88f), gear.right - options.left, 1f) // the capsule: 256 → 344

        val plus = compose.onNodeWithTag("new-session").pxBounds()
        assertEquals(dp(16f), plus.left, 1f)
        assertEquals(dp(60f), plus.right, 1f)
        assertEquals(dp(44f), plus.width, 1f)
    }

    // ------------------------------------------------------ the buttons

    @Test
    fun theGearAndPlusCallTheRouterOnceEachAndFireNoHaptics() {
        mountToolbar()

        compose.onNodeWithTag("sidebar-settings").performClick()
        compose.waitForIdle()
        assertEquals(listOf("showSettings()"), router.calls)

        compose.onNodeWithTag("new-session").performClick()
        compose.waitForIdle()
        assertEquals(listOf("showSettings()", "presentNewSession(null)"), router.calls)

        assertTrue("no haptic from any toolbar tap", feedback.haptics.isEmpty())
        assertTrue(feedback.cues.isEmpty())
    }

    @Test
    fun theThreeButtonsAreLabelledButtonsInReadingOrder() {
        mountToolbar()

        val gear = compose.onNodeWithTag("sidebar-settings")
        gear.assert(hasContentDescription("Settings"))
        gear.assert(hasButtonRole())

        val options = compose.onNodeWithTag("sidebar-options")
        options.assert(hasContentDescription("Options"))
        options.assert(hasButtonRole())

        val plus = compose.onNodeWithTag("new-session")
        plus.assert(hasContentDescription("New session"))
        plus.assert(hasButtonRole())

        // Reading order (LTR): gear → ellipsis → +, left to right.
        val gearX = gear.pxBounds().center.x
        val optionsX = options.pxBounds().center.x
        val plusX = plus.pxBounds().center.x
        assertTrue(gearX < optionsX && optionsX < plusX)
    }

    // -------------------------------------------------------- the menu

    @Test
    fun theEllipsisOpensTheOptionsMenuAnchoredToTheSlot() {
        mountToolbar()

        compose.onNodeWithTag("sidebar-options").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400) // the open spring
        compose.waitForIdle()

        // Exactly 46's four rows, in order.
        val rows = listOf(
            "sessions-options-new-section",
            "sessions-options-collapse-all",
            "sessions-options-group-by-project",
            "sessions-options-archived",
        )
        var lastTop = Float.NEGATIVE_INFINITY
        for (tag in rows) {
            val b = compose.onNodeWithTag(tag).pxBounds()
            assertTrue("$tag is placed", b.width > 0f)
            assertTrue("$tag follows the previous row", b.top > lastTop)
            lastTop = b.top
        }

        // Placement (15's cover rule): the panel's left edge = the
        // ellipsis slot's left edge, its bottom = min(slot bottom,
        // window bottom − max(navigation bars, ime) − 12) — no insets in
        // Robolectric, so both are 788 dp.
        val panel = compose.onNodeWithTag("glass-menu").pxBounds()
        val slot = compose.onNodeWithTag("sidebar-options").pxBounds()
        assertEquals("the panel's left edge aligns to the slot", slot.left, panel.left, 1f)
        assertEquals(dp(788f), panel.bottom, 1f)
        assertEquals(dp(788f), slot.bottom, 1f)
        assertTrue("the panel opens upward, covering the slot", panel.top < slot.top)
        assertTrue("the menu panel covers the ellipsis slot", panel.top < slot.bottom)

        // No haptic from the open, no router call yet.
        assertTrue(feedback.haptics.isEmpty())
        assertTrue(router.calls.isEmpty())

        // Dismiss with the scrim tap, so the close fade runs.
        pickDismiss()
    }

    @Test
    fun theGroupByProjectCheckIsReadAtTapTimeAndFlipsBetweenOpens() {
        mountToolbar()

        compose.onNodeWithTag("sidebar-options").performClick()
        compose.waitForIdle()
        assertFalse("unchecked while the pref is off", selected("sessions-options-group-by-project"))

        pickRow("sessions-options-group-by-project")
        assertTrue("the pick flipped the recorder's pref", actions.grouped)
        assertFalse(actions.calls.isEmpty())

        // Reopened: the check was rebuilt at tap time, so it shows on.
        compose.onNodeWithTag("sidebar-options").performClick()
        compose.waitForIdle()
        assertTrue("checked after the flip", selected("sessions-options-group-by-project"))

        pickRow("sessions-options-group-by-project")
        assertFalse("the second pick flipped it back", actions.grouped)
    }

    @Test
    fun archivedPushesOntoTheSidebarStackAndHidesTheToolbarUntilPop() {
        mountToolbar()

        compose.onNodeWithTag("sidebar-options").performClick()
        compose.waitForIdle()
        pickRow("sessions-options-archived")

        // The folder pushed onto the SIDEBAR's stack (no host: it commits
        // at once), and the toolbar left the semantics tree.
        assertEquals(2, stack.entries.size)
        compose.onNodeWithTag("sidebar-toolbar").assertDoesNotExist()
        compose.onNodeWithTag("sidebar-settings").assertDoesNotExist()

        // Popped back: fully visible and tappable again.
        stackOp { stack.pop(animated = false) }
        compose.onNodeWithTag("sidebar-toolbar").assertExists()
        compose.onNodeWithTag("sidebar-settings").performClick()
        compose.waitForIdle()
        assertEquals(listOf("showSettings()"), router.calls)
    }

    // ------------------------------------------------ the fade + blocking

    @Test
    fun theVisibilityTableOverTheStackStates() {
        // Root, idle: fully visible.
        val root = NavStack(SidebarRootRoute)
        assertEquals(1f, sidebarToolbarVisibility(root), 1e-6f)

        // A folder, idle (a hostless non-animated push commits at once).
        val folder = NavStack(SidebarRootRoute)
        folder.push(FolderRoute.pinned(), animated = false)
        assertEquals(0f, sidebarToolbarVisibility(folder), 1e-6f)

        // A push from the root at 0.25: 1 → 0 lands at 0.75.
        val push = NavStack(SidebarRootRoute)
        push.holdHostlessOps()
        push.push(FolderRoute.pinned(), animated = true)
        push.freeze(0.25f)
        assertEquals(0.75f, sidebarToolbarVisibility(push), 1e-6f)

        // A pop to the root at 0.25: 0 → 1 lands at 0.25.
        val pop = NavStack(SidebarRootRoute)
        pop.push(FolderRoute.pinned(), animated = false)
        pop.holdHostlessOps()
        pop.pop(animated = true)
        pop.freeze(0.25f)
        assertEquals(0.25f, sidebarToolbarVisibility(pop), 1e-6f)

        // A push between two folders: both ends are non-root, stays 0.
        val two = NavStack(SidebarRootRoute)
        two.push(FolderRoute.pinned(), animated = false)
        two.holdHostlessOps()
        two.push(FolderRoute.archived(), animated = true)
        two.freeze(0.5f)
        assertEquals(0f, sidebarToolbarVisibility(two), 1e-6f)
    }

    @Test
    fun aMidFadeShowsAlpha05AndBlocksTaps() {
        stackOp {
            stack.holdHostlessOps()
            stack.push(FolderRoute.archived(), animated = true)
            stack.freeze(0.5f)
        }
        mountToolbar()

        assertEquals(0.5f, toolbarAlpha(), 1e-3f)

        // Taps do nothing — real touches on the gear (the group is
        // disabled) and on the + (the fading wrapper consumes the downs).
        compose.onNodeWithTag("sidebar-settings").performTouchInput { click(center) }
        compose.onNodeWithTag("new-session").performTouchInput { click(center) }
        compose.waitForIdle()
        assertTrue("no router call while fading", router.calls.isEmpty())
        assertTrue(feedback.haptics.isEmpty())
    }

    @Test
    fun anInteractiveBackHeldAt03ShowsAlpha03() {
        stackOp {
            stack.push(FolderRoute.archived(), animated = false)
            stack.holdHostlessOps()
            stack.pop(animated = true)
            stack.freeze(0.3f)
        }
        mountToolbar()

        assertEquals(0.3f, toolbarAlpha(), 1e-3f)
        compose.onNodeWithTag("sidebar-toolbar").assertExists()
    }

    // -------------------------------------------------------- the press

    @Test
    fun pressingASlotScalesItToPressScaleWhileTheCapsuleStaysStill() {
        val source = MutableInteractionSource()
        mountSlot {
            SidebarToolbarContent(
                visibility = { 1f },
                onSettings = {},
                onOptions = {},
                onNewSession = {},
                optionsSource = source,
            )
        }
        val sizeBefore = compose.onNodeWithTag("sidebar-options").fetchSemanticsNode().size

        val press = PressInteraction.Press(Offset.Zero)
        source.tryEmit(press)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400) // the press-down spring settles

        // The slot swells to 07/08's token — no hard-coded scale anywhere
        // in this ticket — and the measured size never changes.
        assertEquals(
            Glass.PressScale,
            compose.onNodeWithTag("sidebar-options").fetchSemanticsNode()
                .config[GlassPressModifierScaleKey],
            0.005f,
        )
        assertEquals(sizeBefore, compose.onNodeWithTag("sidebar-options").fetchSemanticsNode().size)

        // The capsule stays still: no interactive GlassSurface anywhere in
        // the toolbar (the group's surface is interactive = false), and the
        // glassPress publishers are exactly the three items that own one
        // (gear, ellipsis, +) — no stacking, no compounding.
        assertTrue(
            "the capsule must not scale (no interactive GlassSurface)",
            pressScales(GlassPressScaleKey).isEmpty(),
        )
        assertEquals(3, pressScales(GlassPressModifierScaleKey).size)

        source.tryEmit(PressInteraction.Release(press))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600) // release + the small overshoot
        assertEquals(
            1f,
            compose.onNodeWithTag("sidebar-options").fetchSemanticsNode()
                .config[GlassPressModifierScaleKey],
            0.005f,
        )
        assertTrue("a press fires no haptic", feedback.haptics.isEmpty())
    }

    // ------------------------------------------------------ the plus ink

    @Test
    fun thePlusIsAnOpaqueAccentCircleWithAWhiteGlyph() {
        mountSlot {
            SidebarToolbarContent(
                visibility = { 1f },
                onSettings = {},
                onOptions = {},
                onNewSession = {},
            )
        }

        val px = compose.onNodeWithTag("new-session").captureToImage().toPixelMap()
        val cx = px.width / 2
        val cy = px.height / 2
        // The opaque `Palette.accent` fill, 15 dp left of centre — outside
        // the glyph's 13.9 dp box, inside the 44 dp circle.
        assertPixel(px, cx - 30, cy, 0x5B, 0x43, 0xE8, "the plus fill")
        // The glyph at the centre is white.
        assertPixel(px, cx, cy, 0xFF, 0xFF, 0xFF, "the plus glyph")
    }

    // -------------------------------------------------- the wired shell

    @Test
    fun theWiredToolbarSitsAt105sSlotStaysPutUnderFocusAndAddsNoListPadding() {
        mountSidebar()

        // 105's placement: full width × 44, bottom = 800 − barBottom (12,
        // no navigation bar in Robolectric) — the toolbar adds no offset.
        val row = compose.onNodeWithTag("sidebar-toolbar").pxBounds()
        assertEquals(dp(0f), row.left, 1f)
        assertEquals(dp(360f), row.width, 1f)
        assertEquals(dp(44f), row.height, 1f)
        assertEquals(dp(788f), row.bottom, 1f)

        // 105's list bottom padding is unchanged (barBottom + 44 = 56):
        // the last row still clears the toolbar area with the toolbar
        // actually drawn in it.
        compose.runOnUiThread { runBlocking { sidebarState.listState.scrollBy(dp(10_000f)) } }
        compose.waitForIdle()
        val last = compose.onNodeWithTag("session-chat-launch-post").pxBounds()
        assertEquals(dp(744f), last.bottom, 1.5f)

        // Focusing the sidebar search does not move the toolbar
        // (`hidesNavigationBarDuringPresentation = false`).
        compose.onNodeWithTag("sidebar-search").performClick()
        compose.onNodeWithTag("sidebar-search").assertIsFocused()
        val after = compose.onNodeWithTag("sidebar-toolbar").pxBounds()
        assertEquals(dp(788f), after.bottom, 1f)

        // The wired toolbar routes its buttons, too.
        compose.onNodeWithTag("sidebar-settings").performClick()
        compose.waitForIdle()
        assertEquals(listOf("showSettings()"), router.calls)
    }

    // ------------------------------------------------------------ private

    /** Dismisses an open menu with the scrim tap and runs the close fade. */
    private fun pickDismiss() {
        val scrim = compose.onAllNodesWithTag("glass-menu-scrim")
        if (scrim.fetchSemanticsNodes().isNotEmpty()) scrim[0].performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }
}
