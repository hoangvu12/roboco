package sh.roboco.android.newsession

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import sh.roboco.android.design.RobocoTheme

/**
 * The locations rail and search/typed-path field's goldens
 * (android-ui-parity #112), recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.newsession.FolderLocationsScreenshotTest`
 * and verified by `verifyRoborazziDebug`:
 *
 *  - the ticket's four fixtures A–D in light and dark, each on the plain
 *    background at the preview size 402 × 120 dp (mdpi, so the geometry
 *    reads 1:1 against the ticket's dp table);
 *  - the whole "Folder browser locations" gallery section (fixtures
 *    A–D stacked with captions) in light and dark.
 *
 * Visual checks against the neighbours, per the ticket's acceptance:
 * ticket 59's chip goldens (same chip height, font and padding) and
 * ticket 90's "Other…" field (same fill, corners, font and placeholder
 * colour). Nothing animates, so the frozen clock and a single
 * `waitForIdle` are byte-stable. Paths are relative to the
 * `roborazzi { outputDir }` (`src/test/screenshots`), landing under
 * `newsession/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h120dp-mdpi")
class FolderLocationsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    @After
    fun unfreeze() {
        compose.mainClock.autoAdvance = true
    }

    // ---------------------------------------------------------- the stage

    private fun present(spec: FolderLocationsSpec, appearance: Appearance) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    FolderLocationsStage(spec, Modifier.fillMaxWidth())
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(spec: FolderLocationsSpec, appearance: Appearance, file: String) {
        present(spec, appearance)
        compose.onRoot().captureRoboImage("newsession/$file")
    }

    // ------------------------------------------------------------ goldens

    @Test
    fun fixtureALight() {
        shot(folderLocationsFixtureA(), Appearance.Light, "folder_locations_a_light.png")
    }

    @Test
    fun fixtureADark() {
        shot(folderLocationsFixtureA(), Appearance.Dark, "folder_locations_a_dark.png")
    }

    @Test
    fun fixtureBLight() {
        shot(folderLocationsFixtureB(), Appearance.Light, "folder_locations_b_light.png")
    }

    @Test
    fun fixtureBDark() {
        shot(folderLocationsFixtureB(), Appearance.Dark, "folder_locations_b_dark.png")
    }

    @Test
    fun fixtureCLight() {
        shot(folderLocationsFixtureC(), Appearance.Light, "folder_locations_c_light.png")
    }

    @Test
    fun fixtureCDark() {
        shot(folderLocationsFixtureC(), Appearance.Dark, "folder_locations_c_dark.png")
    }

    @Test
    fun fixtureDLight() {
        shot(folderLocationsFixtureD(), Appearance.Light, "folder_locations_d_light.png")
    }

    @Test
    fun fixtureDDark() {
        shot(folderLocationsFixtureD(), Appearance.Dark, "folder_locations_d_dark.png")
    }

    // ------------------------------------------------------- the section

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h560dp-mdpi")
    fun galleryLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    FolderLocationsGallerySection()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("newsession/folder_locations_section_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h560dp-mdpi")
    fun galleryDark() {
        compose.setContent {
            RobocoTheme(Appearance.Dark) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    FolderLocationsGallerySection()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("newsession/folder_locations_section_dark.png")
    }
}
