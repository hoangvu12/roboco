package sh.roboco.android.design.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.uiStyle

/**
 * The sheet goldens (ticket 17), recorded by `recordRoborazziDebug` and
 * verified by `verifyRoborazziDebug`: each fixture of the ticket's list
 * in light and dark, on the fixed striped backdrop, at the ticket's
 * 360 × 740 dp window at density 3, with the sheet **snapped** to its
 * detent — the snap runs in the content slot, whose effects launch after
 * the sheet's own present effect, so the captured frame is settled, not
 * mid-animation.
 *
 * - `medium_grabber_done`: the tool detail look — medium detent, grabber,
 *   trailing Done, mono lines;
 * - `large_close`: the New Session header over an empty background;
 * - `large_done_no_grabber`: Select Text — large, no grabber, Done;
 * - `long_title`: an ellipsized 80-character title with both buttons.
 *
 * `Glass.forceFallback` and Reduce Motion follow the gallery-capture
 * convention (ticket 22): the glass circles draw the fallback fill, and
 * no loop runs, so the goldens stay byte-stable. Paths are relative to
 * the `roborazzi { outputDir }` (`src/test/screenshots`), landing under
 * `design/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xxhdpi")
class GlassSheetScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
        ReduceMotion.debugOverride = true
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        ReduceMotion.debugOverride = null
    }

    private fun shot(
        appearance: Appearance,
        file: String,
        detents: List<SheetDetent>,
        grabber: Boolean,
        title: String,
        leading: SheetLeading = SheetLeading.None,
        trailing: SheetTrailing = SheetTrailing.None,
        body: @Composable () -> Unit = {},
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                SheetShotFrame(detents, grabber, title, leading, trailing, body)
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Composable
    private fun SheetShotFrame(
        detents: List<SheetDetent>,
        grabber: Boolean,
        title: String,
        leading: SheetLeading,
        trailing: SheetTrailing,
        body: @Composable () -> Unit,
    ) {
        Box(Modifier.fillMaxSize()) {
            GalleryBackdrop(Modifier.fillMaxSize())
            val state = rememberGlassSheetState(detents)
            val snapTarget = detents.first()
            GlassSheet(
                onDismiss = {},
                state = state,
                grabber = grabber,
                title = title,
                header = { SheetHeader(title, leading, trailing) },
            ) { _ ->
                LaunchedEffect(state) { state.snapTo(snapTarget) }
                body()
            }
        }
    }

    // ------------------------------------------------------------ fixtures

    @Test
    fun mediumGrabberDoneLight() {
        shot(
            Appearance.Light, "design/sheet_medium_grabber_done_light.png",
            listOf(SheetDetent.Medium, SheetDetent.Large), grabber = true, title = "Bash",
            trailing = SheetTrailing.Done, body = { MonoBody() },
        )
    }

    @Test
    fun mediumGrabberDoneDark() {
        shot(
            Appearance.Dark, "design/sheet_medium_grabber_done_dark.png",
            listOf(SheetDetent.Medium, SheetDetent.Large), grabber = true, title = "Bash",
            trailing = SheetTrailing.Done, body = { MonoBody() },
        )
    }

    @Test
    fun largeCloseLight() {
        shot(
            Appearance.Light, "design/sheet_large_close_light.png",
            listOf(SheetDetent.Large), grabber = true, title = "New Session",
            leading = SheetLeading.Close,
        )
    }

    @Test
    fun largeCloseDark() {
        shot(
            Appearance.Dark, "design/sheet_large_close_dark.png",
            listOf(SheetDetent.Large), grabber = true, title = "New Session",
            leading = SheetLeading.Close,
        )
    }

    @Test
    fun largeDoneNoGrabberLight() {
        shot(
            Appearance.Light, "design/sheet_large_done_no_grabber_light.png",
            listOf(SheetDetent.Large), grabber = false, title = "Select Text",
            trailing = SheetTrailing.Done, body = { MonoBody() },
        )
    }

    @Test
    fun largeDoneNoGrabberDark() {
        shot(
            Appearance.Dark, "design/sheet_large_done_no_grabber_dark.png",
            listOf(SheetDetent.Large), grabber = false, title = "Select Text",
            trailing = SheetTrailing.Done, body = { MonoBody() },
        )
    }

    @Test
    fun longTitleLight() {
        shot(
            Appearance.Light, "design/sheet_long_title_light.png",
            listOf(SheetDetent.Medium, SheetDetent.Large), grabber = true, title = LongTitle,
            leading = SheetLeading.Close, trailing = SheetTrailing.Done, body = { MonoBody() },
        )
    }

    @Test
    fun longTitleDark() {
        shot(
            Appearance.Dark, "design/sheet_long_title_dark.png",
            listOf(SheetDetent.Medium, SheetDetent.Large), grabber = true, title = LongTitle,
            leading = SheetLeading.Close, trailing = SheetTrailing.Done, body = { MonoBody() },
        )
    }

    /** The 80-character title: ellipsized a good 8 dp before the ✕ and the ✓. */
    private val LongTitle =
        "Tool output from engine roboco-build-farm-runner-07 (branch ui-parity/sheet-017)"

    /** The tool-detail body: fixed mono lines. */
    @Composable
    private fun MonoBody() {
        val typeScale = LocalTypeScale.current
        val palette = RobocoTheme.palette
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (i in 1..12) {
                BasicText(
                    "line $i: $ roboco engine start --port ${8674 + i}",
                    style = uiStyle(Face.Mono, typeScale.bodyScaled(13f), palette.text),
                )
            }
        }
    }
}
