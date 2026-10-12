package sh.roboco.android.design.gallery

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardCapitalization
import sh.roboco.android.core.Face
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.AlertField
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.AlertHostState
import sh.roboco.android.design.AlertStripes
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Alert" page of the debug design gallery (ticket 18): one row per
 * alert shape, every one of them shown through a real [AlertHost] so the
 * present/dismiss animation, the FIFO queue, the field focus, the
 * back/scrim rules and the glass over the stripes are exactly what the app
 * gets.
 *
 * Ticket 22 owns the registration line in
 * `apps/android/app/src/debug/java/sh/roboco/android/design/Gallery.kt`:
 * `GallerySection("Alert", GalleryLayout.Scroll, screenshotHeightDp = 640) { AlertGallerySection() }`
 * — that file does not exist yet (ticket 22 is not merged), same as ticket
 * 05's `HapticsGallerySection`. The gallery deep link `--es section alert`
 * matches 22's case-insensitive lookup of the name "Alert".
 *
 * The section mounts its own host over a 640 dp frame of stripes: the
 * gallery page is not a window root, and this keeps the demo alerts scoped
 * to the section. When ticket 35 mounts `AlertHost` at the app root the
 * nearest host still wins for anything composed inside one.
 */
@Composable
fun AlertGallerySection() {
    val alerts = remember { AlertHostState() }
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val hintStyle = uiStyle(Face.Sans, typeScale.scaled(13f), palette.secondary)

    // New Folder (ticket 111's shape): Create is disabled while the trimmed
    // field is empty, and the retry re-shows the alert with the typed name
    // pre-filled — the PR #685 capture's state.
    fun showNewFolder(name: String, message: String = "Create a folder in /Users/me/code on Studio.") {
        alerts.show(
            RobocoAlert(
                title = "New Folder",
                message = message,
                field = AlertField(
                    text = name,
                    placeholder = "Folder name",
                    capitalization = KeyboardCapitalization.None,
                    autocorrect = false,
                ),
                buttons = listOf(
                    AlertButton("Cancel", role = AlertButtonRole.Cancel),
                    AlertButton("Create", requiresText = true) { text ->
                        showNewFolder(
                            text,
                            "Couldn't create the folder. Enter a folder name without path separators.",
                        )
                    },
                ),
            ),
        )
    }

    AlertHost(state = alerts) {
        Box(Modifier.fillMaxWidth().height(640.dp).background(palette.background)) {
            AlertStripes()
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicText("Alert", style = uiStyle(Face.SansSemibold, typeScale.scaled(17f), palette.text))
                Spacer12()
                BasicText(
                    "Every alert below runs through a real AlertHost: queue, focus, " +
                        "back, scrim and IME rules included.",
                    style = hintStyle,
                )
                Spacer12()

                // Ticket 94: acknowledgement, one button, no field.
                AlertDemoRow("OK — Couldn't start the session") {
                    alerts.show(
                        RobocoAlert(
                            title = "Couldn't start the session",
                            message = "Choose a project or a host that can run it.",
                            buttons = listOf(AlertButton("OK")),
                        ),
                    )
                }

                // Ticket 99: two buttons, the cancel action carries a handler.
                AlertDemoRow("Two buttons — Notifications are off") {
                    alerts.show(
                        RobocoAlert(
                            title = "Notifications are off",
                            message = "Allow notifications for Roboco in Android settings.",
                            buttons = listOf(
                                AlertButton("Not Now", role = AlertButtonRole.Cancel) {
                                    log("Not Now: reload (the cancel handler)")
                                },
                                AlertButton("Open Settings") { log("Open Settings") },
                            ),
                        ),
                    )
                }

                // Ticket 43: placeholder + Words capitalization.
                AlertDemoRow("New Section — placeholder, Words") {
                    alerts.show(
                        RobocoAlert(
                            title = "New Section",
                            field = AlertField(placeholder = "Name", capitalization = KeyboardCapitalization.Words),
                            buttons = listOf(
                                AlertButton("Cancel", role = AlertButtonRole.Cancel),
                                AlertButton("Create") { text ->
                                    if (text.trim().isNotEmpty()) log("new section: '$text'")
                                },
                            ),
                        ),
                    )
                }

                // Ticket 43: pre-filled, the caret at the end (decision FOUNDATION-D2).
                AlertDemoRow("Rename (pre-filled) — Rename Session") {
                    alerts.show(
                        RobocoAlert(
                            title = "Rename Session",
                            field = AlertField(text = "Fix flaky test"),
                            buttons = listOf(
                                AlertButton("Cancel", role = AlertButtonRole.Cancel),
                                AlertButton("Rename") { text ->
                                    if (text.trim().isNotEmpty()) log("rename session: '$text'")
                                },
                            ),
                        ),
                    )
                }

                // Ticket 111 + PR #685: requiresText and the retry.
                AlertDemoRow("New Folder — requires text + retry") { showNewFolder("") }

                // Destructive role (gallery-only copy).
                AlertDemoRow("Destructive — Remove wallpaper?") {
                    alerts.show(
                        RobocoAlert(
                            title = "Remove wallpaper?",
                            buttons = listOf(
                                AlertButton("Cancel", role = AlertButtonRole.Cancel),
                                AlertButton("Remove", role = AlertButtonRole.Destructive) {
                                    log("remove wallpaper")
                                },
                            ),
                        ),
                    )
                }

                // Three buttons: always stacked, Cancel last.
                AlertDemoRow("Three buttons (stacked)") {
                    alerts.show(
                        RobocoAlert(
                            title = "Version conflict",
                            message = "A session with this name already exists.",
                            buttons = listOf(
                                AlertButton("Keep Both Versions") { log("keep both") },
                                AlertButton("Replace Existing") { log("replace") },
                                AlertButton("Cancel", role = AlertButtonRole.Cancel),
                            ),
                        ),
                    )
                }

                // Two long titles: wider than the 108 dp half-capsule budget.
                AlertDemoRow("Long titles (stack)") {
                    alerts.show(
                        RobocoAlert(
                            title = "Version conflict",
                            buttons = listOf(
                                AlertButton("Keep Both Versions") { log("keep both") },
                                AlertButton("Replace Existing") { log("replace") },
                            ),
                        ),
                    )
                }

                // Two shows in one frame: the second presents after the first
                // faded out (the FIFO queue).
                AlertDemoRow("Show twice — the FIFO queue") {
                    alerts.show(
                        RobocoAlert(
                            title = "First",
                            message = "Only one alert is visible at a time.",
                            buttons = listOf(AlertButton("OK")),
                        ),
                    )
                    alerts.show(
                        RobocoAlert(
                            title = "Second",
                            message = "The queued alert, in the frame after the first faded out.",
                            buttons = listOf(AlertButton("OK")),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertDemoRow(label: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        label,
        style = uiStyle(Face.Sans, typeScale.scaled(15f), palette.text),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun Spacer12() {
    Box(Modifier.height(12.dp))
}

private fun log(message: String) {
    Log.d("AlertGallery", message)
}
