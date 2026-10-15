package sh.roboco.android.settings

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.notify.NotificationKind
import sh.roboco.android.notify.NotificationPrefs
import sh.roboco.android.notify.NotificationStatus

/**
 * The "Notification rows" page of the debug design gallery
 * (android-ui-parity #99): the ticket's step-9 fixture — one `LazyColumn`
 * on the page background holding three `notificationRowsSection` blocks
 * with the real emitter and fixed snapshots (no real prefs, no permission
 * reader, no launcher), keyed `n-off` / `n-on` / `n-denied`:
 *
 *  1. **off** — `Snapshot(true, true, true, true)`, `NotDetermined`:
 *     the master row only, switch off — the `2-settings.png` state (the
 *     simulator was not authorized), the side-by-side partner of the
 *     reference's Notifications card;
 *  2. **on** — `Snapshot(true, true, false, true)`, `Granted`: four
 *     rows, "Waiting on your input" off;
 *  3. **denied** — `Snapshot(true, true, true, true)`, `Denied`: the
 *     master row only with the "Turned off in Android settings" subtitle,
 *     switch off. Tapping it on presents the real "Notifications are off"
 *     alert through [LocalAlerts] (the gallery's `GalleryProviders`
 *     mounts ticket 18's `AlertHost`); its handlers only log — never a
 *     system-settings intent from the debug gallery.
 *
 * Each fixture holds its snapshot in local snapshot state, so the kind
 * switches really flip their rows and the master switches walk the state
 * machine's shape: off persists, on-while-Granted persists, on-while-
 * Denied presents the alert with `pendingOn`. On while `NotDetermined`
 * there is no system dialog to show in the gallery, so the fixture
 * simulates an allowed request — the happy path a real phone walks after
 * "Allow".
 *
 * Registered in ticket 22's `design/Gallery.kt` (debug source set) as
 * `GallerySection("Notification rows", GalleryLayout.Fill,
 * screenshotHeightDp = 760) { NotificationRowsGallerySection() }` — Fill
 * because the section owns the `LazyColumn`. The column is capped at the
 * reference phone's 402 dp width on wider bodies, so the cards sit at the
 * reference size.
 *
 * The Roborazzi goldens of `NotificationRowsScreenshotTest` host the same
 * fixtures directly; at rest nothing animates, so they are byte-stable
 * without freezing the clock.
 */
@Composable
fun NotificationRowsGallerySection() {
    val alerts = LocalAlerts.current
    val background = RobocoTheme.palette.background
    val off = rememberFixture(
        initial = NotificationPrefs.Snapshot(enabled = true, done = true, input = true, failed = true),
        status = NotificationStatus.NotDetermined,
    )
    val on = rememberFixture(
        initial = NotificationPrefs.Snapshot(enabled = true, done = true, input = false, failed = true),
        status = NotificationStatus.Granted,
    )
    val denied = rememberFixture(
        initial = NotificationPrefs.Snapshot(enabled = true, done = true, input = true, failed = true),
        status = NotificationStatus.Denied,
    )
    LazyColumn(
        Modifier
            .fillMaxSize()
            .widthIn(max = 402.dp)
            .background(background),
    ) {
        notificationGalleryFixture(fixture = off, alerts = alerts, key = "n-off", caption = "off \u00b7 fresh install")
        notificationGalleryFixture(fixture = on, alerts = alerts, key = "n-on", caption = "on \u00b7 kinds")
        notificationGalleryFixture(fixture = denied, alerts = alerts, key = "n-denied", caption = "denied \u00b7 Android settings")
    }
}

// ---------------------------------------------------------------------------
// The fixtures
// ---------------------------------------------------------------------------

/**
 * One fixture's state, held in composition so the switches work: the
 * snapshot, the status and `pendingOn`. [onToggled] walks the state
 * machine's shape without the real machinery (see the class KDoc above
 * for the per-status behaviour).
 */
private class NotificationGalleryFixture(
    initial: NotificationPrefs.Snapshot,
    initialStatus: NotificationStatus,
) {
    var snapshot by mutableStateOf(initial)
    var status by mutableStateOf(initialStatus)
    var pendingOn by mutableStateOf(false)

    fun onToggled(row: NotificationRowModel, on: Boolean, alerts: Alerts) {
        val kind = row.kind
        when {
            kind != null -> snapshot = when (kind) {
                NotificationKind.Done -> snapshot.copy(done = on)
                NotificationKind.Input -> snapshot.copy(input = on)
                NotificationKind.Failed -> snapshot.copy(failed = on)
            }

            !on -> {
                pendingOn = false
                snapshot = snapshot.copy(enabled = false)
            }

            status == NotificationStatus.Denied -> {
                // The real alert, through the gallery's AlertHost; its
                // handlers only log (never a real settings intent).
                pendingOn = true
                alerts.show(deniedGalleryAlert())
            }

            status == NotificationStatus.NotDetermined -> {
                // No system dialog exists here: the request returns
                // allowed at once, so the fixture walks the happy path.
                snapshot = snapshot.copy(enabled = true)
                status = NotificationStatus.Granted
            }

            else -> snapshot = snapshot.copy(enabled = true)
        }
    }
}

/** The denied fixture's alert: the section's copy, handlers that only log. */
private fun deniedGalleryAlert() = RobocoAlert(
    title = NotificationCopy.AlertTitle,
    message = NotificationCopy.AlertMessage,
    buttons = listOf(
        AlertButton(NotificationCopy.AlertNotNow, AlertButtonRole.Cancel) {
            Log.i("NotificationGallery", "Not Now")
        },
        AlertButton(NotificationCopy.AlertOpenSettings, AlertButtonRole.Default) {
            Log.i("NotificationGallery", "Open Settings")
        },
    ),
)

@Composable
private fun rememberFixture(
    initial: NotificationPrefs.Snapshot,
    status: NotificationStatus,
): NotificationGalleryFixture = remember { NotificationGalleryFixture(initial, status) }

/**
 * One fixture block in the gallery's `LazyColumn`: a caption item, then
 * the real emitter with the fixture's rows — `notificationRows` reads the
 * fixture's snapshot state inside the DSL, so a flip re-derives the rows
 * on the next frame, exactly like the live section.
 */
private fun LazyListScope.notificationGalleryFixture(
    fixture: NotificationGalleryFixture,
    alerts: Alerts,
    key: String,
    caption: String,
) {
    item(key = "$key:caption", contentType = "gallery-caption") {
        NotificationRowsCaption(caption)
    }
    notificationRowsSection(
        rows = notificationRows(fixture.snapshot, fixture.status, fixture.pendingOn),
        onToggle = { row, on -> fixture.onToggled(row, on, alerts) },
        key = key,
    )
}

/** The block caption: Geist 12 fixed, secondary, at the header's inset (the Devices rows gallery's style). */
@Composable
private fun NotificationRowsCaption(text: String) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    BasicText(
        text,
        style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
        modifier = Modifier.padding(start = 32.dp, top = 16.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "light")
@Composable
private fun NotificationRowsGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            NotificationRowsGallerySection()
        }
    }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NotificationRowsGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            NotificationRowsGallerySection()
        }
    }
}
