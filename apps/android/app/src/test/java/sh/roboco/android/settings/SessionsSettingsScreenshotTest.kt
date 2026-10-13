package sh.roboco.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GroupedHeader
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.RobocoTheme

/**
 * The Sessions section goldens (android-ui-parity #100), recorded by
 * `recordRoborazziDebug` and verified by `verifyRoborazziDebug`. Paths are
 * relative to the `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `settings/`:
 *
 * - `sessions_section_{on,off}_{light,dark}.png` — the section through the
 *   real [sessionsSettingsSection] in the ticket's mounting (a 402 dp
 *   `LazyColumn` on the page background), the switch pinned on (the
 *   side-by-side partner of `2-settings.png`'s Sessions card) or off;
 * - `sessions_section_archived_pressed_light.png` — the rows drawn
 *   non-lazily with the Archived row held pressed (`previewPressed`), the
 *   pressed-highlight fixture.
 *
 * The `on_light` golden is the side-by-side partner of `2-settings.png`:
 * scale it to 460 px wide and align it on the Sessions card top
 * (y ≈ 648/649 px in the reference); the card edges, corners, row heights
 * (84/52), icon and text x (32…56, 72), the subtitle's wrap point (after
 * "under"), the separator span (72…370), the switch's position and
 * `#5B43E8` track and the chevron must all match within ±1.5 px.
 *
 * The host is the ticket's `w402dp-h520dp` phone at xxhdpi, font scale 1,
 * so ticket 70's geometry sits at its reference size. At rest nothing
 * animates — the switch holds its initial value, whose slide starts at
 * the target — so the goldens are byte-stable without freezing the clock
 * (the `RobocoSwitchScreenshotTest` precedent).
 *
 * Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.settings.SessionsSettingsScreenshotTest`,
 * verify with `./gradlew :app:verifyRoborazziDebug --tests …`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h520dp-xxhdpi")
class SessionsSettingsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** The section, the ticket's mounting, with the switch pinned to [on]. */
    private fun shotSection(appearance: Appearance, on: Boolean, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                val strings = sessionsSettingsStrings()
                LazyColumn(
                    Modifier
                        .width(402.dp)
                        .fillMaxSize()
                        .background(RobocoTheme.palette.background),
                ) {
                    sessionsSettingsSection(
                        strings = strings,
                        groupByProject = { on },
                        onGroupByProjectChange = { },
                        onOpenArchived = { },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun sectionOnLight() {
        shotSection(Appearance.Light, true, "settings/sessions_section_on_light.png")
    }

    @Test
    fun sectionOnDark() {
        shotSection(Appearance.Dark, true, "settings/sessions_section_on_dark.png")
    }

    @Test
    fun sectionOffLight() {
        shotSection(Appearance.Light, false, "settings/sessions_section_off_light.png")
    }

    @Test
    fun sectionOffDark() {
        shotSection(Appearance.Dark, false, "settings/sessions_section_off_dark.png")
    }

    @Test
    fun archivedPressedLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Column(
                    Modifier
                        .width(402.dp)
                        .fillMaxSize()
                        .background(RobocoTheme.palette.background),
                ) {
                    val strings = sessionsSettingsStrings()
                    GroupedHeader(strings.header)
                    SessionsSettingsRowContent(
                        row = SessionsSettingsRow.GroupByProject,
                        position = GroupedPosition.First,
                        strings = strings,
                        groupByProject = true,
                        onGroupByProjectChange = { },
                        onOpenArchived = { },
                    )
                    SessionsSettingsRowContent(
                        row = SessionsSettingsRow.Archived,
                        position = GroupedPosition.Last,
                        strings = strings,
                        groupByProject = true,
                        onGroupByProjectChange = { },
                        onOpenArchived = { },
                        previewPressed = true,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("settings/sessions_section_archived_pressed_light.png")
    }
}
