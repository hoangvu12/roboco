package sh.roboco.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoAlertCard
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.notify.NotificationPrefs
import sh.roboco.android.notify.NotificationStatus

/**
 * The Notifications rows goldens (android-ui-parity #99): the ticket's
 * three fixtures through the stateless [notificationRowsSection] emitter
 * on the ticket's `w402dp` phone at xxhdpi — the width ticket 70's
 * geometry table is stated against, so the 16 dp card insets, the 84/68/
 * 52 dp rows and the 26 dp continuous corners sit at their reference
 * size:
 *
 *  - `notification_rows_off_light` — the `2-settings.png` state: the
 *    master row only, switch off, the subtitle wrapping after
 *    "needs you" (the side-by-side partner of the reference's
 *    Notifications card);
 *  - `notification_rows_on_light` / `notification_rows_on_dark` — four
 *    rows, "Waiting on your input" off;
 *  - `notification_rows_denied_light` — the master row with the
 *    "Turned off in Android settings" subtitle;
 *  - `notification_rows_denied_alert_light` — the "Notifications are
 *    off" alert presented over the denied fixture (the static card +
 *    scrim, the `AlertScreenshotTest` method: byte-stable, no host, no
 *    clock to freeze).
 *
 * At rest nothing animates (the switches hold their initial fractions),
 * so the goldens are byte-stable without freezing the clock. Ticket 22's
 * automatic gallery capture covers the combined gallery page ("Notification
 * rows", light and dark).
 *
 * Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.settings.NotificationRowsScreenshotTest`,
 * verify with `./gradlew :app:verifyRoborazziDebug --tests …`. Paths are
 * relative to the `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `settings/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h760dp-xxhdpi")
class NotificationRowsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val allOn = NotificationPrefs.Snapshot(enabled = true, done = true, input = true, failed = true)
    private val onFixture = NotificationPrefs.Snapshot(enabled = true, done = true, input = false, failed = true)

    /** The denied alert, exactly as the section's state machine shows it. */
    private val deniedAlert = RobocoAlert(
        title = NotificationCopy.AlertTitle,
        message = NotificationCopy.AlertMessage,
        buttons = listOf(
            AlertButton(NotificationCopy.AlertNotNow, AlertButtonRole.Cancel),
            AlertButton(NotificationCopy.AlertOpenSettings),
        ),
    )

    /** One fixture: the stateless emitter with fixed rows, on the page background. */
    private fun shot(
        appearance: Appearance,
        snapshot: NotificationPrefs.Snapshot,
        status: NotificationStatus,
        file: String,
    ) {
        compose.setContent {
            RobocoTheme(appearance) {
                SectionBackdrop(snapshot, status)
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Composable
    private fun SectionBackdrop(snapshot: NotificationPrefs.Snapshot, status: NotificationStatus) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .background(RobocoTheme.palette.background),
        ) {
            notificationRowsSection(
                rows = notificationRows(snapshot, status, pendingOn = false),
                onToggle = { _, _ -> },
                key = "notifications",
            )
        }
    }

    @Test
    fun offLight() {
        shot(Appearance.Light, allOn, NotificationStatus.NotDetermined, "settings/notification_rows_off_light.png")
    }

    @Test
    fun onLight() {
        shot(Appearance.Light, onFixture, NotificationStatus.Granted, "settings/notification_rows_on_light.png")
    }

    @Test
    fun onDark() {
        shot(Appearance.Dark, onFixture, NotificationStatus.Granted, "settings/notification_rows_on_dark.png")
    }

    @Test
    fun deniedLight() {
        shot(Appearance.Light, allOn, NotificationStatus.Denied, "settings/notification_rows_denied_light.png")
    }

    /**
     * The alert presented over the denied fixture: the rows, the 0.2
     * black scrim, then the static card (no host, no present animation —
     * the `AlertScreenshotTest` method, byte-stable).
     */
    @Test
    fun deniedAlertLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    SectionBackdrop(allOn, NotificationStatus.Denied)
                    Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        RobocoAlertCard(
                            alert = deniedAlert,
                            fieldValue = TextFieldValue(""),
                            onFieldValueChange = {},
                            onButton = {},
                            onImeDone = {},
                            hazeState = null,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("settings/notification_rows_denied_alert_light.png")
    }
}
