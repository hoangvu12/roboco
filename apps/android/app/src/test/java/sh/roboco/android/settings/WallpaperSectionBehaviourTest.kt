package sh.roboco.android.settings

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
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
import sh.roboco.android.core.WallpaperStore
import sh.roboco.android.design.ActionSheetHost
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.WallpaperEffect

/**
 * The Wallpaper section's behaviour (android-ui-parity #97) through the
 * real [rememberWallpaperSectionState] + [wallpaperSection] over a fake
 * [WallpaperSectionActions] and a recording [ActivityResultRegistry]:
 *
 *  - the photo picker: one `PickVisualMedia(ImageOnly)` request per tap,
 *    the double-launch guard, Uri results and cancels through
 *    `dispatchResult`, and the "set immediately, inside the callback" rule;
 *  - the "Wallpaper Effect" action sheet through ticket 19's host: the
 *    current effect checked, `onSelect` only after the 250 ms exit, Cancel
 *    a no-op;
 *  - Remove: one call, no sheet, no alert;
 *  - live updates: rows appear/disappear/retitle on the next frame, with
 *    no animation;
 *  - the geometry of ticket 70's rows at this section's call sites, on the
 *    ticket's `w402dp` device at xxhdpi (1 dp = 3 px), like
 *    `GroupedListTest`: the 68/84.6/52 dp heights, chevrons on
 *    choose/effect but not remove, the separators following First/Middle/
 *    Last, and the icon/title inks (`danger` for remove, `secondary` for
 *    the others) in light and dark.
 *
 * The registry overrides `LocalActivityResultRegistryOwner` with a fake
 * owner, exactly the ticket's setup: `onLaunch` records the input and the
 * request code; the test then calls `dispatchResult`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h891dp-xxhdpi")
class WallpaperSectionBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ fixtures

    private val flow = MutableStateFlow(
        WallpaperStore.State(set = false, name = null, effect = WallpaperEffect.NONE, generation = 0),
    )
    private val setCalls = mutableListOf<Uri>()
    private val effectCalls = mutableListOf<WallpaperEffect>()
    private var removeCalls = 0

    private val fake = WallpaperSectionActions(
        state = flow,
        set = { setCalls += it },
        setEffect = { effectCalls += it },
        remove = { removeCalls += 1 },
    )

    private val registry = RecordingRegistry()
    private val owner = RegistryOwner(registry)

    /** `onLaunch` records the input and the request code; the test dispatches results back. */
    private class RecordingRegistry : ActivityResultRegistry() {
        val inputs = mutableListOf<Any?>()
        val requestCodes = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            requestCodes += requestCode
            inputs += input
        }
    }

    private class RegistryOwner(private val registry: ActivityResultRegistry) : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = registry
    }

    // ------------------------------------------------------------- mounting

    /**
     * The mounted appearance. A compose rule allows one `setContent` per
     * test, so a light-then-dark test swaps it through [switchAppearance];
     * the content is keyed on it, so each appearance gets a fresh mount.
     */
    private var appearance by mutableStateOf(Appearance.Light)

    /**
     * Ticket 103's shape: the state at screen level (outside the
     * `LazyColumn`, so the launcher's registration survives scrolling),
     * the rows inside it, and the sheet host above both.
     */
    private fun mount(appearance: Appearance = Appearance.Light) {
        this.appearance = appearance
        compose.setContent {
            val current = this.appearance
            key(current) {
                RobocoTheme(current) {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        ActionSheetHost {
                            val section = rememberWallpaperSectionState(fake)
                            LazyColumn(
                                Modifier
                                    .fillMaxSize()
                                    .background(RobocoTheme.palette.background),
                            ) {
                                wallpaperSection(section)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Re-mounts the already-set content in [appearance] (one setContent per test). */
    private fun switchAppearance(appearance: Appearance) {
        compose.runOnUiThread { this.appearance = appearance }
        compose.waitForIdle()
    }

    private fun setState(update: (WallpaperStore.State) -> WallpaperStore.State) {
        compose.runOnUiThread { flow.value = update(flow.value) }
        compose.waitForIdle()
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag)

    private fun sheetNode(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    // ---------------------------------------------------------- the picker

    @Test
    fun tappingChooseLaunchesExactlyOneImageOnlyRequest() {
        mount()
        row("settings-wallpaper:choose").performClick()
        compose.waitForIdle()

        assertEquals(1, registry.inputs.size)
        val request = registry.inputs.single() as PickVisualMediaRequest
        assertEquals("images only, like PHPicker's .images", ActivityResultContracts.PickVisualMedia.ImageOnly, request.mediaType)
    }

    @Test
    fun aSecondTapWhileALaunchIsPendingDoesNotLaunchAgain() {
        mount()
        row("settings-wallpaper:choose").performClick()
        compose.waitForIdle()
        row("settings-wallpaper:choose").performClick()
        compose.waitForIdle()
        assertEquals("the double-launch guard keeps one request pending", 1, registry.inputs.size)
    }

    @Test
    fun dispatchingAPickedUriCallsSetOnceInsideTheCallback() {
        mount()
        row("settings-wallpaper:choose").performClick()
        compose.waitForIdle()
        val uri = Uri.fromParts("content", "media/picked/1", null)

        registry.dispatchResult(registry.requestCodes.single(), uri)
        compose.waitForIdle()

        assertEquals("the Uri goes to set immediately, while the grant is live", listOf(uri), setCalls)
        assertTrue(effectCalls.isEmpty())
        assertEquals(0, removeCalls)
    }

    @Test
    fun cancellingThePickerCallsNothingAndALaterTapLaunchesAgain() {
        mount()
        row("settings-wallpaper:choose").performClick()
        compose.waitForIdle()

        registry.dispatchResult<Uri?>(registry.requestCodes.single(), null)
        compose.waitForIdle()
        assertTrue("an empty result is a cancel: nothing happens", setCalls.isEmpty())

        row("settings-wallpaper:choose").performClick()
        compose.waitForIdle()
        assertEquals("the guard is free again after a cancel", 2, registry.inputs.size)
    }

    // ----------------------------------------------------------- the sheet

    @Test
    fun tappingEffectShowsTheSheetWithTheCurrentEffectChecked() {
        mount()
        setState { it.copy(set = true, name = "IMG_0412", effect = WallpaperEffect.ASCII) }

        row("settings-wallpaper:effect").performClick()
        compose.waitForIdle()

        sheetNode("action-sheet").assertExists()
        sheetNode("action-sheet-row-2").assertIsSelected() // ASCII
        // Ticket 19 sets `selected` only on the checked row; unchecked rows
        // carry no Selected property at all (ActionSheetHostTest asserts the
        // same), so assertIsNotSelected — which needs Selected = false —
        // cannot apply.
        assertFalse(
            "an unchecked row is not selected",
            SemanticsProperties.Selected in sheetNode("action-sheet-row-4").fetchSemanticsNode().config,
        )

        sheetNode("action-sheet-cancel").performClick()
        compose.waitForIdle() // runs the 250 ms exit on the auto clock
        assertTrue("Cancel picks nothing", effectCalls.isEmpty())
        sheetNode("action-sheet").assertDoesNotExist()
    }

    @Test
    fun selectingARowRunsSetEffectAfterTheSheetIsGone() {
        mount()
        setState { it.copy(set = true, name = "IMG_0412", effect = WallpaperEffect.ASCII) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)

        row("settings-wallpaper:effect").performClick()
        compose.mainClock.advanceTimeByFrame() // composes the overlay
        compose.mainClock.advanceTimeBy(500) // the 0.4 s rise settles

        sheetNode("action-sheet-row-4").performClick()
        compose.mainClock.advanceTimeByFrame() // flush the gesture's frame

        // advanceTimeBy rounds up to whole 16 ms frames: 249 would be 16
        // frames = 256 ms of play time, past the 250 ms exit. 240 is exactly
        // 15 frames, the last whose play time is still < 250 (as in
        // ActionSheetHostTest.aRowTapRunsOnSelectOnlyAfterThe250MsDismiss).
        compose.mainClock.advanceTimeBy(240)
        assertTrue("onSelect must not run before the exit ends", effectCalls.isEmpty())
        sheetNode("action-sheet").assertExists()

        compose.mainClock.advanceTimeBy(1)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf(WallpaperEffect.SCANLINES), effectCalls)
        sheetNode("action-sheet").assertDoesNotExist()
    }

    // ----------------------------------------------------------- the remove

    @Test
    fun tappingRemoveRemovesAtOnceWithNoSheetAndNoAlert() {
        mount()
        setState { it.copy(set = true, name = "IMG_0412", effect = WallpaperEffect.HALFTONE) }

        row("settings-wallpaper:remove").performClick()
        compose.waitForIdle()

        assertEquals("one call, no confirmation", 1, removeCalls)
        assertTrue(setCalls.isEmpty())
        assertTrue(effectCalls.isEmpty())
        sheetNode("action-sheet").assertDoesNotExist()
        compose.onNodeWithTag("alert").assertDoesNotExist()
    }

    // -------------------------------------------------------- live updates

    @Test
    fun rowsAppearAndRetitleOnTheNextFrameAndDisappearAgain() {
        mount()
        row("settings-wallpaper:effect").assertDoesNotExist()
        row("settings-wallpaper:remove").assertDoesNotExist()
        compose.onNodeWithText("Choose Wallpaper\u2026").assertExists()

        // The pick finished: the section flips on the next frame.
        setState { it.copy(set = true, name = "IMG_0412", effect = WallpaperEffect.HALFTONE, generation = 1) }
        compose.onNodeWithText("Change Wallpaper\u2026").assertExists()
        compose.onNodeWithText("IMG_0412").assertExists()
        row("settings-wallpaper:effect").assertExists()
        row("settings-wallpaper:remove").assertExists()

        // Removed: the rows collapse back, instantly.
        setState { it.copy(set = false, name = null, generation = 2) }
        compose.onNodeWithText("Choose Wallpaper\u2026").assertExists()
        compose.onNodeWithText("Shown behind new chats and the sessions list").assertExists()
        row("settings-wallpaper:effect").assertDoesNotExist()
        row("settings-wallpaper:remove").assertDoesNotExist()
    }

    @Test
    fun changingOnlyTheEffectUpdatesTheSubtitleInPlace() {
        mount()
        setState { it.copy(set = true, name = "IMG_0412", effect = WallpaperEffect.HALFTONE, generation = 1) }
        compose.onNodeWithText("Halftone", substring = true).assertIsDisplayed()

        setState { it.copy(effect = WallpaperEffect.SCANLINES, generation = 2) }
        compose.onNodeWithText("Scanlines \u2014 Adds a pronounced horizontal display-line texture.", substring = true)
            .assertExists()
        compose.onAllNodesWithText("Halftone", substring = true).fetchSemanticsNodes().let { nodes ->
            assertTrue("the old subtitle is gone: ${nodes.size}", nodes.isEmpty())
        }
        row("settings-wallpaper:effect").assertExists() // the row itself never left
    }

    // ---------------------------------------------------------- geometry

    /** The node's bounds in dp (± the ticket's 1 dp). */
    private fun assertDp(actual: Dp, expected: Dp, what: String, tolerance: Float = 1f) {
        assertTrue(
            "$what: $actual dp, expected $expected ±$tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    private fun rowImage(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    @Test
    fun unsetChooseRowIs68DpTallWithAChevron() {
        mount()
        assertDp(heightOf(row("settings-wallpaper:choose").getUnclippedBoundsInRoot()), 68.dp, "unset choose row height")

        // The disclosure chevron: UIKit tertiaryLabel over white ≈
        // (196, 196, 199), in the row's trailing 60 dp (GroupedListTest's
        // chevron probe region, x 990..1080).
        val px = rowImage("settings-wallpaper:choose")
        val chevronInk = px.regionInk(990..1080, 40..115) { c -> matches(c, 196, 196, 199, 14) }
        assertTrue("chevron ink expected at the row's trailing edge", chevronInk > 0)
    }

    @Test
    fun setRowsFollowTheMeasuredHeightsAndChevronRules() {
        mount()
        setState { it.copy(set = true, name = "IMG_0412", effect = WallpaperEffect.HALFTONE, generation = 1) }

        assertDp(heightOf(row("settings-wallpaper:choose").getUnclippedBoundsInRoot()), 68.dp, "set choose row height")
        // The Halftone subtitle wraps to two lines: 30 + 20.8 + 2 × 16.9.
        assertDp(heightOf(row("settings-wallpaper:effect").getUnclippedBoundsInRoot()), 84.6.dp, "effect row height")
        // No accessory: the text column runs to 16 dp before the card edge.
        assertDp(heightOf(row("settings-wallpaper:remove").getUnclippedBoundsInRoot()), 52.dp, "remove row height")

        val removePx = rowImage("settings-wallpaper:remove")
        assertEquals("no chevron on the destructive row", 0, removePx.regionInk(990..1080, 40..115) { c -> matches(c, 196, 196, 199, 14) })
    }

    @Test
    fun separatorsFollowFirstMiddleLast() {
        mount()
        setState { it.copy(set = true, name = "IMG_0412", effect = WallpaperEffect.HALFTONE, generation = 1) }

        // UIKit separator over white ≈ (198, 198, 200), on the row's last
        // pixel row, 56 dp into the card (image x = 168) to 16 dp before
        // its edge (x = 1062) — GroupedListTest's separator probes.
        val choose = rowImage("settings-wallpaper:choose")
        val chooseBottom = choose.height - 1
        assertTrue("separator under choose (First)", choose.hasSeparator(chooseBottom))

        val effect = rowImage("settings-wallpaper:effect")
        val effectBottom = effect.height - 1
        assertTrue("separator under effect (Middle)", effect.hasSeparator(effectBottom))

        val remove = rowImage("settings-wallpaper:remove")
        val removeBottom = remove.height - 1
        assertFalse("no separator under remove (Last)", remove.hasSeparator(removeBottom))
    }

    @Test
    fun theUnsetSectionHasNoSeparator() {
        mount()
        val choose = rowImage("settings-wallpaper:choose")
        assertFalse("no separator under a Single row", choose.hasSeparator(choose.height - 1))
    }

    /**
     * Separators at [y]: present somewhere in the text-column span
     * (x 168..1062, image-local — the row image starts at the card's left
     * edge), absent either side.
     */
    private fun PixelMap.hasSeparator(y: Int): Boolean {
        var ink = 0
        for (x in 168..1062) {
            if (matches(this[x, y], 198, 198, 200, 12)) ink++
        }
        if (ink < 20) return false
        // Both flanks stay clean (the card fill is white there).
        assertTrue("stray ink left of the separator start", matches(this[160, y], 255, 255, 255, 2))
        assertTrue("stray ink right of the separator end", matches(this[1070, y], 255, 255, 255, 2))
        return true
    }

    // ---------------------------------------------------------- ink probes

    /** Counts pixels in [x]×[y] matching [match]. */
    private fun PixelMap.regionInk(x: IntRange, y: IntRange, match: (Color) -> Boolean): Int {
        var count = 0
        for (yy in y) {
            for (xx in x) {
                if (match(this[xx, yy])) count++
            }
        }
        return count
    }

    /**
     * Ink inside the leading icon box (image x 48..120), in a 90 dp window
     * centred on the row — the box is centred too, whatever the row height.
     */
    private fun PixelMap.iconInk(match: (Color) -> Boolean): Int {
        val mid = height / 2
        return regionInk(48..120, (mid - 45)..(mid + 45), match)
    }

    private fun channel(color: Color, ch: Int): Int = when (ch) {
        0 -> (color.red * 255f).toInt()
        1 -> (color.green * 255f).toInt()
        2 -> (color.blue * 255f).toInt()
        else -> error(ch)
    }

    private fun matches(c: Color, r: Int, g: Int, b: Int, tolerance: Int): Boolean =
        abs(channel(c, 0) - r) <= tolerance && abs(channel(c, 1) - g) <= tolerance && abs(channel(c, 2) - b) <= tolerance

    /**
     * The remove row's icon and title sample `danger` and the choose/effect
     * icons sample `secondary` (the ticket's hex table; ±8 at glyph cores,
     * where the tint is un-blended — the match count keeps the probe off
     * the anti-aliased fringes). The icon box is 16..40 dp into the card
     * (image x 48..120), the title starts at 56 dp (x 168).
     */
    @Test
    fun removeInksInDangerAndTheOthersInSecondary() {
        val dangerLight = Triple(220, 38, 38) // #DC2626
        val dangerDark = Triple(248, 113, 113) // #F87171
        val secondaryLight = Triple(98, 98, 106) // #62626A
        val secondaryDark = Triple(169, 169, 174) // #A9A9AE

        for (dark in listOf(false, true)) {
            if (dark) switchAppearance(Appearance.Dark) else mount(Appearance.Light)
            setState { it.copy(set = true, name = "IMG_0412", effect = WallpaperEffect.HALFTONE, generation = 1) }

            val (dr, dg, db) = if (dark) dangerDark else dangerLight
            val (sr, sg, sb) = if (dark) secondaryDark else secondaryLight

            val remove = rowImage("settings-wallpaper:remove")
            assertTrue(
                "light=$dark the remove icon samples danger",
                remove.iconInk { c -> matches(c, dr, dg, db, 8) } > 4,
            )
            assertTrue(
                "light=$dark the remove title samples danger",
                remove.regionInk(168..700, 40..115) { c -> matches(c, dr, dg, db, 8) } > 4,
            )

            val choose = rowImage("settings-wallpaper:choose")
            assertTrue(
                "light=$dark the choose icon samples secondary",
                choose.iconInk { c -> matches(c, sr, sg, sb, 8) } > 4,
            )

            val effect = rowImage("settings-wallpaper:effect")
            assertTrue(
                "light=$dark the effect icon samples secondary",
                effect.iconInk { c -> matches(c, sr, sg, sb, 8) } > 4,
            )
        }
    }

    // --------------------------------------------------- the section tokens

    @Test
    fun theSectionHeaderIsWallpaperAndRowsReadAsButtons() {
        mount()
        compose.onNodeWithText("Wallpaper").assertExists()
        // Merged row semantics: the row node reads title, then subtitle.
        row("settings-wallpaper:choose").assertExists()
        compose.onNodeWithText("Choose Wallpaper\u2026", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Shown behind new chats and the sessions list", substring = true).assertExists()
    }
}
