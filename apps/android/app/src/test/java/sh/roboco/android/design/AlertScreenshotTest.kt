package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ticket 18's goldens: the alert card over the striped backdrop and the
 * 0.2 scrim, `hazeState = null` (the fallback fill) so they never depend on
 * how Robolectric reports hardware acceleration, and the field unfocused
 * (no caret). Device `w393dp-h852dp-xxhdpi`. Recorded by the batch run
 * (`:app:recordRoborazziDebug`), verified by `verifyRoborazziDebug`; the
 * paths land under `src/test/screenshots/design/`.
 *
 * `alert_new_folder_retry_light` is the PR #685 capture's content
 * (`ios-name-error.png`): the retry message, the field holding
 * "../outside", Cancel + a enabled Create.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class AlertScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    private fun shot(appearance: Appearance, file: String, alert: RobocoAlert, fieldText: String = "") {
        compose.setContent {
            RobocoTheme(appearance) {
                AlertGoldenFixture(alert, TextFieldValue(fieldText))
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("design/$file")
    }

    /** The card, centred over the stripes and the 0.2 scrim, no host (static). */
    @Composable
    private fun AlertGoldenFixture(alert: RobocoAlert, fieldValue: TextFieldValue) {
        val palette = RobocoTheme.palette
        Box(Modifier.fillMaxSize().background(palette.background)) {
            AlertStripes()
            Box(Modifier.fillMaxSize().background(rgba(0x000000, 0.2)))
            Box(
                Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                RobocoAlertCard(
                    alert = alert,
                    fieldValue = fieldValue,
                    onFieldValueChange = {},
                    onButton = {},
                    onImeDone = {},
                    hazeState = null,
                )
            }
        }
    }

    private val okAlert = RobocoAlert(
        title = "Couldn't start the session",
        message = "Choose a project or a host that can run it.",
        buttons = listOf(AlertButton("OK")),
    )

    private val twoButtonsAlert = RobocoAlert(
        title = "Notifications are off",
        message = "Allow notifications for Roboco in Android settings.",
        buttons = listOf(
            AlertButton("Not Now", role = AlertButtonRole.Cancel),
            AlertButton("Open Settings"),
        ),
    )

    private val fieldAlert = RobocoAlert(
        title = "Rename Session",
        field = AlertField(text = "Fix flaky test"),
        buttons = listOf(
            AlertButton("Cancel", role = AlertButtonRole.Cancel),
            AlertButton("Rename"),
        ),
    )

    private val newFolderAlert = RobocoAlert(
        title = "New Folder",
        message = "Create a folder in /Users/me/code on Studio.",
        field = AlertField(
            placeholder = "Folder name",
            capitalization = KeyboardCapitalization.None,
            autocorrect = false,
        ),
        buttons = listOf(
            AlertButton("Cancel", role = AlertButtonRole.Cancel),
            AlertButton("Create", requiresText = true),
        ),
    )

    private val stackedAlert = RobocoAlert(
        title = "Version conflict",
        message = "A session with this name already exists.",
        buttons = listOf(
            AlertButton("Keep Both Versions"),
            AlertButton("Replace Existing"),
            AlertButton("Cancel", role = AlertButtonRole.Cancel),
        ),
    )

    private val destructiveAlert = RobocoAlert(
        title = "Remove wallpaper?",
        buttons = listOf(
            AlertButton("Cancel", role = AlertButtonRole.Cancel),
            AlertButton("Remove", role = AlertButtonRole.Destructive),
        ),
    )

    private val retryAlert = RobocoAlert(
        title = "New Folder",
        message = "Couldn't create the folder. Enter a folder name without path separators.",
        field = AlertField(
            text = "../outside",
            placeholder = "Folder name",
            capitalization = KeyboardCapitalization.None,
            autocorrect = false,
        ),
        buttons = listOf(
            AlertButton("Cancel", role = AlertButtonRole.Cancel),
            AlertButton("Create", requiresText = true),
        ),
    )

    @Test
    fun alertOkLight() {
        shot(Appearance.Light, "alert_ok_light.png", okAlert)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun alertOkDark() {
        shot(Appearance.Dark, "alert_ok_dark.png", okAlert)
    }

    @Test
    fun alertTwoButtonsLight() {
        shot(Appearance.Light, "alert_two_buttons_light.png", twoButtonsAlert)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun alertTwoButtonsDark() {
        shot(Appearance.Dark, "alert_two_buttons_dark.png", twoButtonsAlert)
    }

    @Test
    fun alertFieldLight() {
        shot(Appearance.Light, "alert_field_light.png", fieldAlert)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun alertFieldDark() {
        shot(Appearance.Dark, "alert_field_dark.png", fieldAlert)
    }

    @Test
    fun alertFieldDisabledLight() {
        // Empty field: the placeholder shows, Create is disabled (tertiary ink).
        shot(Appearance.Light, "alert_field_disabled_light.png", newFolderAlert)
    }

    @Test
    fun alertStackedLight() {
        shot(Appearance.Light, "alert_stacked_light.png", stackedAlert)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun alertDestructiveDark() {
        shot(Appearance.Dark, "alert_destructive_dark.png", destructiveAlert)
    }

    @Test
    fun alertNewFolderRetryLight() {
        shot(Appearance.Light, "alert_new_folder_retry_light.png", retryAlert, fieldText = "../outside")
    }
}
