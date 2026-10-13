package sh.roboco.android.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.SheetHost
import sh.roboco.android.shell.SheetStyle
import sh.roboco.android.shell.SheetHostState

/**
 * The About & Licenses goldens (android-ui-parity #101), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`. Paths are
 * relative to the `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `settings/`:
 *
 * - `about_rows_{light,dark}.png` — the gallery's About card
 *   ([AboutGalleryCard]) at the geometry width (402 dp, the reference
 *   iPhone's point width): the "About" header, the 68 dp Version and
 *   Licenses rows, and the pressed demo row;
 * - `licenses_list_{light,dark}.png` — the pushed Licenses list over the
 *   fixture sections, 412 × 900 dp, a fixed 24 dp status bar, no bottom
 *   chrome (the pushed Settings tab keeps the tab bar on a real phone;
 *   [LocalBottomChrome] defaults to 0 here, which pins the card layout);
 * - `licence_sheet_{light,dark}.png` — the licence-text sheet at rest
 *   (`presentSettledForPreview`, 31's capture convention) over the gallery
 *   backdrop: the 52 dp header with the trailing Done, Geist Regular 13
 *   text at 16/19/32/19 insets.
 *
 * `Glass.forceFallback` and Reduce Motion follow the gallery-capture
 * convention (ticket 22): the glass Done circle draws the fallback fill,
 * so the goldens stay byte-stable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class AboutLicensesScreenshotTest {

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

    private fun shot(appearance: Appearance, file: String, content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) { content() }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h891dp-xxhdpi")
    fun aboutRowsLight() {
        shot(Appearance.Light, "settings/about_rows_light.png") { AboutGalleryCard() }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h891dp-night-xxhdpi")
    fun aboutRowsDark() {
        shot(Appearance.Dark, "settings/about_rows_dark.png") { AboutGalleryCard() }
    }

    @Test
    fun licensesListLight() {
        shot(Appearance.Light, "settings/licenses_list_light.png") { LicensesListGoldenHost() }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h900dp-night-xxhdpi")
    fun licensesListDark() {
        shot(Appearance.Dark, "settings/licenses_list_dark.png") { LicensesListGoldenHost() }
    }

    @Test
    fun licenceSheetLight() {
        licenceSheetShot(Appearance.Light, "settings/licence_sheet_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h900dp-night-xxhdpi")
    fun licenceSheetDark() {
        licenceSheetShot(Appearance.Dark, "settings/licence_sheet_dark.png")
    }

    /** The sheet, presented at rest over the gallery backdrop (31's rule). */
    private fun licenceSheetShot(appearance: Appearance, file: String) {
        val state = SheetHostState()
        compose.runOnUiThread {
            state.presentSettledForPreview("licence:geist", SheetStyle.Page(grabber = false)) {
                LicenceTextSheet(
                    title = "Geist and Geist Mono",
                    text = AboutGalleryOflFixture,
                    onDone = { dismiss(true) },
                )
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

    /** The pushed Licenses list, as the golden hosts it. */
    @Composable
    private fun LicensesListGoldenHost() {
        val stack = remember { NavStack(GoldenRoot).apply { push(GoldenLicensesPage) } }
        NavStackHost(stack = stack, active = true) { entry ->
            if (entry.route === GoldenLicensesPage) {
                LicensesList(
                    sections = AboutGallerySections,
                    onOpen = {},
                    topInset = 24.dp,
                )
            }
        }
    }

    private object GoldenRoot : NavRoute {
        override val paneTitle: String get() = "Settings"
    }

    private object GoldenLicensesPage : NavRoute {
        override val paneTitle: String get() = "Licenses"
    }
}
