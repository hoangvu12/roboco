package sh.roboco.android.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.sheet.SheetHeaderRow
import sh.roboco.android.design.sheet.SheetLeading

/**
 * The page-sheet goldens (android-ui-parity #31), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`, at the
 * ticket's phone window (411 × 891 dp, density 3 — H = 2643 px). The
 * entries are inserted with `presentSettledForPreview`, so the captured
 * frame is at rest, never mid-animation.
 *
 * - `shell/page_sheet_light.png` / `page_sheet_dark.png`: the placeholder
 *   New Session sheet at rest over the backdrop — the 0.92 recession
 *   with 38 dp top corners, the black side gaps, the scrim (#F3F3F5 under
 *   0.25 ≈ #B6B6B8 light, #060606 under 0.45 ≈ #030303 dark), the
 *   grabber, the ✕;
 * - `shell/page_sheet_stacked_light.png`: New Project (no grabber)
 *   stacked over New Session — the lower sheet at 0.92 with a 10 dp peek;
 * - `shell/page_sheet_tablet_light.png` (the second class, 1280 × 800):
 *   the 704 dp centred card, no recession, scrim still on.
 *
 * `Glass.forceFallback` and Reduce Motion follow the gallery-capture
 * convention (ticket 22): the glass ✕ draws the fallback fill, so the
 * goldens stay byte-stable. Paths are relative to the `roborazzi {
 * outputDir }` (`src/test/screenshots`), landing under `shell/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class PageSheetScreenshotTest {

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

    private fun shot(appearance: Appearance, file: String, stack: Boolean = false) {
        val state = SheetHostState()
        compose.runOnUiThread {
            state.presentSettledForPreview("new-session", SheetStyle.Page(grabber = true)) {
                NewSessionSheetPlaceholder(this)
            }
            if (stack) {
                state.presentSettledForPreview("new-project", SheetStyle.Page(grabber = false)) {
                    NewProjectShotPage(this)
                }
            }
        }
        compose.setContent {
            RobocoTheme(appearance) {
                SheetHost(state) { GalleryBackdrop(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun placeholderLight() {
        shot(Appearance.Light, "shell/page_sheet_light.png")
    }

    @Test
    fun placeholderDark() {
        shot(Appearance.Dark, "shell/page_sheet_dark.png")
    }

    @Test
    fun stackedLight() {
        shot(Appearance.Light, "shell/page_sheet_stacked_light.png", stack = true)
    }
}

/** The tablet golden: the 704 dp centred card, no recession, scrim on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-xhdpi")
class PageSheetTabletScreenshotTest {

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

    @Test
    fun tabletLight() {
        val state = SheetHostState()
        compose.runOnUiThread {
            state.presentSettledForPreview("new-session", SheetStyle.Page(grabber = true)) {
                NewSessionSheetPlaceholder(this)
            }
        }
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                SheetHost(state) { GalleryBackdrop(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("shell/page_sheet_tablet_light.png")
    }
}

/** The stacked New Project page of the phone goldens: no grabber, its own ✕. */
@Composable
private fun NewProjectShotPage(scope: PageSheetScope) {
    Column(Modifier.fillMaxSize()) {
        SheetHeaderRow(
            title = "New Project",
            leading = SheetLeading.Close,
            onLeading = { scope.dismiss(true) },
        )
        Spacer(Modifier.weight(1f))
    }
}
