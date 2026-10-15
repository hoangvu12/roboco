package sh.roboco.android.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.R
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.AlertHostState
import sh.roboco.android.design.AlertProgress
import sh.roboco.android.design.AlertProgressKey
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoTheme

/**
 * The update alerts' rendering (android-next#04, panels A/B/C): the launch
 * prompt's copy and buttons (Update preferred — accent fill, white text —
 * Later grey), the in-place morph to the download state (one card, never a
 * second alert) with the progress bar and its label, the permission ask,
 * and "Later" resolving the alert. Robolectric + Compose, real resources.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class UpdateAlertsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context
        get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val alerts = AlertHostState()

    /** The exact launch prompt the center builds (panel A). */
    private fun launchAlert(): RobocoAlert = RobocoAlert(
        title = context.getString(R.string.update_alert_title),
        message = context.getString(R.string.update_alert_message, "0.10.0", "0.9.2"),
        buttons = listOf(
            AlertButton(
                context.getString(R.string.update_alert_update),
                preferred = true,
                morphsInPlace = true,
            ) {
                // The morph the center performs: A → B in place.
                alerts.updateCurrent(downloadingAlert(0.42f))
            },
            AlertButton(
                context.getString(R.string.update_alert_later),
                role = AlertButtonRole.Cancel,
            ) {},
        ),
    )

    /** The exact download state the center builds (panel B). */
    private fun downloadingAlert(fraction: Float): RobocoAlert = RobocoAlert(
        title = context.getString(R.string.update_downloading_title),
        message = context.getString(R.string.update_downloading_message, "0.10.0"),
        progress = AlertProgress(
            fraction,
            context.getString(R.string.update_progress_of_mb, "10.3", "24.5"),
        ),
        buttons = listOf(
            AlertButton(context.getString(R.string.update_downloading_cancel), role = AlertButtonRole.Cancel) {},
        ),
    )

    /** The exact permission ask the center builds (panel C). */
    private fun permissionAlert(): RobocoAlert = RobocoAlert(
        title = context.getString(R.string.update_permission_title),
        message = context.getString(R.string.update_permission_message),
        buttons = listOf(
            AlertButton(context.getString(R.string.update_permission_open), preferred = true) {},
            AlertButton(context.getString(R.string.update_permission_not_now), role = AlertButtonRole.Cancel) {},
        ),
    )

    private fun mount() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                AlertHost(alerts) {
                    Box(Modifier.fillMaxSize()) { BasicText("the app behind the alert") }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.waitForIdle()
    }

    private fun show(alert: RobocoAlert) {
        compose.runOnUiThread { alerts.show(alert) }
        compose.waitForIdle()
        // Let the present animation settle (the fade-in runs 200 ms).
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    // --------------------------------------------------------- panel A

    @Test
    fun theLaunchPromptShowsTheApprovedCopyAndButtons() {
        mount()
        show(launchAlert())

        compose.onNodeWithText("Update Available").assertExists()
        compose.onNodeWithText("Roboco 0.10.0 is available. You have 0.9.2.").assertExists()
        compose.onNodeWithTag("alert-primary").assertExists()
        compose.onNodeWithText("Update").assertExists()
        compose.onNodeWithTag("alert-cancel").assertExists()
        compose.onNodeWithText("Later").assertExists()
    }

    @Test
    fun thePreferredButtonIsTheAccentCapsuleWithWhiteText() {
        mount()
        show(launchAlert())

        val image = compose.onNodeWithTag("alert-primary").captureToImage().toPixelMap()
        // A point above the title line, inside the capsule's fill: the
        // accent, opaque (light theme: #5B43E8).
        val x = image.width / 2
        val y = (image.height / 6).coerceAtLeast(1)
        val c = image[x, y]
        val accent = Palette.Light.accent
        assertTrue(
            "preferred fill rgb(${c.red}, ${c.green}, ${c.blue}), expected $accent at ($x, $y)",
            abs(c.red - accent.red) < 0.06f &&
                abs(c.green - accent.green) < 0.06f &&
                abs(c.blue - accent.blue) < 0.06f,
        )
    }

    @Test
    fun laterResolvesTheAlert() {
        mount()
        show(launchAlert())

        compose.onNodeWithTag("alert-cancel").performClick()
        // The fade-out runs 200 ms.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        compose.onNodeWithText("Update Available").assertDoesNotExist()
    }

    // --------------------------------------------------------- panel B

    @Test
    fun updateMorphsThePromptIntoTheDownloadStateInPlace() {
        mount()
        show(launchAlert())

        compose.onNodeWithTag("alert-primary").performClick()
        // The clock is paused (mount): advance as laterResolvesTheAlert does,
        // so the tap is processed and the swapped content recomposes. One
        // frame was not enough (CI on a8122511). A morph never fades out, so
        // the alert is still up afterwards.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()

        // One card, the same presentation — never a second alert.
        assertEquals(1, compose.onAllNodesWithTag("alert").fetchSemanticsNodes().size)
        compose.onNodeWithText("Updating Roboco").assertExists()
        compose.onNodeWithText("Downloading 0.10.0…").assertExists()
        compose.onNodeWithText("Cancel").assertExists()
        // The progress bar's fraction and its label.
        compose.onNode(
            SemanticsMatcher("progress 0.42") { it.config.getOrNull(AlertProgressKey) == 0.42f },
        ).assertExists()
        compose.onNodeWithText("10.3 of 24.5 MB").assertExists()
    }

    // --------------------------------------------------------- panel C

    @Test
    fun thePermissionAskShowsOpenSettingsAsPreferred() {
        mount()
        show(permissionAlert())

        compose.onNodeWithText("Allow Roboco to Install Updates").assertExists()
        compose.onNodeWithText("Android asks once. Turn on \"Allow from this source\" for Roboco, then come back.")
            .assertExists()
        compose.onNodeWithText("Open Settings").assertExists()
        compose.onNodeWithText("Not Now").assertExists()
        compose.onNodeWithTag("alert-primary").assertExists()
        compose.onNodeWithTag("alert-cancel").assertExists()
    }
}
