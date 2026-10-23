package sh.roboco.android.settings

import androidx.compose.foundation.lazy.LazyListScope
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoSwitch
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.settingsTag
import sh.roboco.android.design.toggleTag

/**
 * The "Background" section of the Settings screen (ADR 0010): one switch,
 * "Stay connected in background", bound to the phone-local
 * [sh.roboco.android.core.Prefs.stayConnected] preference (off by
 * default). While on, the run-watching foreground service stays up with a
 * permanent, silent "Staying connected" notification, so the process and
 * its engine connections live while Roboco is in the background; off
 * restores the run-only service.
 *
 * Android-only: iOS has no equivalent (it had APNs through the edge), so
 * the copy is new and the row uses ticket 70's [GroupedRow] and ticket
 * 98's [RobocoSwitch] like the Sessions section's Group by Project row —
 * no dp, font or colour of its own. The row body is not tappable (the
 * switch is the control), and the value is read inside the item lambda so
 * the lazy key stays stable when it flips.
 */
object BackgroundSettingsCopy {
    const val Header = "Background"
    const val StayConnectedTitle = "Stay connected in background"
    const val StayConnectedSubtitle = "Shows a permanent notification"
    const val Footer =
        "Keeps your engine connections open while Roboco is in the background, so runs stay live " +
            "when you come back. Uses some extra battery."

    /** The row id: `settings-background:stayConnected` / `toggle-background:stayConnected`. */
    const val StayConnectedId = "background:stayConnected"
}

/**
 * Emits the "Background" section into the Settings `LazyColumn`.
 *
 * @param stayConnected a getter read inside the row item (see the file KDoc)
 * @param onStayConnectedChange the switch's writer — `Prefs::setStayConnected`
 */
fun LazyListScope.backgroundSettingsSection(
    stayConnected: () -> Boolean,
    onStayConnectedChange: (Boolean) -> Unit,
) {
    groupedSection(
        key = SettingsSection.Background.key,
        header = BackgroundSettingsCopy.Header,
        items = listOf(BackgroundSettingsCopy.StayConnectedId),
        itemKey = { it },
        footer = BackgroundSettingsCopy.Footer,
    ) { id, position ->
        GroupedRow(
            title = BackgroundSettingsCopy.StayConnectedTitle,
            subtitle = BackgroundSettingsCopy.StayConnectedSubtitle,
            icon = RobocoIcons.Link,
            position = position,
            accessories = listOf(
                GroupedAccessory.Custom {
                    RobocoSwitch(
                        checked = stayConnected(),
                        onCheckedChange = onStayConnectedChange,
                        testTag = toggleTag(id),
                    )
                },
            ),
            testTag = settingsTag(id),
            onClick = null,
        )
    }
}
