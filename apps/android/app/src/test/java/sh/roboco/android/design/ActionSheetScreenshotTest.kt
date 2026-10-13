package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ticket 19's goldens: the settled `ActionSheetPanel` over the gallery
 * backdrop and the 0.2 scrim, `hazeState = null` (ticket 07's fallback fill,
 * deterministic like ticket 18's goldens — never depending on how Robolectric
 * reports hardware acceleration). Device `w393dp-h852dp-xxhdpi`. Recorded by
 * the batch run (`:app:recordRoborazziDebug`), verified by
 * `verifyRoborazziDebug`; the paths land under `src/test/screenshots/design/`.
 *
 * The placement mirrors the overlay's rest position (the geometry helpers,
 * bottom gap 8, the 772 dp height cap) without the animation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ActionSheetScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    private fun shot(appearance: Appearance, file: String, model: ActionSheetModel) {
        compose.setContent {
            RobocoTheme(appearance) { ActionSheetGoldenFixture(model) }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("design/$file")
    }

    /** The panel as the overlay places it at rest: backdrop + scrim + bottom stack. */
    @Composable
    private fun ActionSheetGoldenFixture(model: ActionSheetModel) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            GalleryBackdrop(Modifier.matchParentSize())
            Box(Modifier.fillMaxSize().background(rgba(0x000000, 0.2)))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val stackWidth = actionSheetWidth(maxWidth.value, maxHeight.value).dp
                val maxCardHeight = actionSheetMaxCardHeight(maxHeight.value, 0f, 0f).dp
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = actionSheetBottomGap(0f).dp)
                        .width(stackWidth),
                ) {
                    ActionSheetPanel(
                        model = model,
                        hazeState = null,
                        onAction = {},
                        onCancel = {},
                        maxCardHeight = maxCardHeight,
                    )
                }
            }
        }
    }

    // Wallpaper Effect, "Dither" checked (the checkmark + 44 dp padding fixture).
    private val wallpaperSheet = wallpaperEffectSheet(checked = 1, onSelect = {})

    // Delete “Backend”? — title + message + one destructive row (iOS copy,
    // curly quotes and all).
    private val deleteSheet = ActionSheetModel(
        title = "Delete “Backend”?",
        message = "Its sessions move back to the main list.",
        actions = listOf(
            ActionSheetAction("Delete Section", style = ActionSheetStyle.Destructive) {},
        ),
    )

    // Move to Section — title only, one row per section, "No Section" last
    // (the ellipsis + long-title row).
    private val moveSheet = ActionSheetModel(
        title = "Move to Section",
        actions = listOf(
            "Backend",
            "Design review",
            "A very long section name that will not fit on one line",
            "No Section",
        ).map { name -> ActionSheetAction(name) {} },
    )

    // 20 rows, no header: the height cap + scroll fixture.
    private val manyRowsSheet = ActionSheetModel(
        actions = (1..20).map { i -> ActionSheetAction("Section $i") {} },
    )

    @Test
    fun actionSheetWallpaperLight() {
        shot(Appearance.Light, "action_sheet_wallpaper_light.png", wallpaperSheet)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun actionSheetWallpaperDark() {
        shot(Appearance.Dark, "action_sheet_wallpaper_dark.png", wallpaperSheet)
    }

    @Test
    fun actionSheetDeleteLight() {
        shot(Appearance.Light, "action_sheet_delete_light.png", deleteSheet)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun actionSheetDeleteDark() {
        shot(Appearance.Dark, "action_sheet_delete_dark.png", deleteSheet)
    }

    @Test
    fun actionSheetMoveLight() {
        shot(Appearance.Light, "action_sheet_move_light.png", moveSheet)
    }

    @Test
    fun actionSheetManyRowsLight() {
        shot(Appearance.Light, "action_sheet_many_rows_light.png", manyRowsSheet)
    }
}
