package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face

/**
 * The "Grouped list" page of the debug design gallery (ticket 70): the
 * inset-grouped list fixture, `2-settings.png` sections 1–6 plus the
 * pressed-highlight demo. Sections 1–4 and "Remove Wallpaper" are iOS copy
 * verbatim (`MoreViewController.swift:132,200-202,205,209,214-215`); the
 * Devices rows and "Version" are Roboco fixture copy (tickets 102 / 101),
 * not iOS strings.
 *
 * Registered by ticket 22's registry as
 * `GallerySection("Grouped list", GalleryLayout.Fill, screenshotHeightDp = 1300)`
 * — the section owns its scrolling because the cards must sit 16 dp from
 * the *screen* edges, un-padded by the gallery frame. The Roborazzi goldens
 * (`grouped_list_{light,dark}.png`, `w402dp-h1300dp-xxhdpi`) live in
 * `GroupedListScreenshotTest`, which hosts this section directly.
 */
@Composable
fun GroupedListGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        Modifier
            // 402 dp — the reference iPhone's point width — when the body
            // is wider (tablets); phone bodies pass through untouched.
            .widthIn(max = 402.dp)
            .fillMaxSize()
            .background(palette.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // 1. "Notifications" — iOS copy verbatim; ticket 98's RobocoSwitch,
        // off by default.
        var notificationsOn by remember { mutableStateOf(false) }
        GroupedHeader("Notifications")
        GroupedRow(
            title = "Notifications",
            subtitle = "When a session finishes, needs you or fails",
            icon = RobocoIcons.Bell,
            position = GroupedPosition.Single,
            accessories = listOf(
                GroupedAccessory.Custom {
                    RobocoSwitch(notificationsOn, { notificationsOn = it }, testTag = toggleTag("notify:enabled"))
                },
            ),
            onClick = null,
        )

        // 2. "Appearance" — System is checked (Check(true)); Light and Dark
        // reserve no trailing space (Check(false) renders nothing).
        GroupedHeader("Appearance")
        GroupedRow(
            title = "System",
            icon = RobocoIcons.CircleLeftHalfFilled,
            position = GroupedPosition.First,
            accessories = listOf(GroupedAccessory.Check(on = true)),
            onClick = null,
        )
        GroupedRow(
            title = "Light",
            icon = RobocoIcons.SunMax,
            position = GroupedPosition.Middle,
            accessories = listOf(GroupedAccessory.Check(on = false)),
            onClick = null,
        )
        GroupedRow(
            title = "Dark",
            icon = RobocoIcons.Moon,
            position = GroupedPosition.Last,
            accessories = listOf(GroupedAccessory.Check(on = false)),
            onClick = null,
        )

        // 3. "Wallpaper" — the default disclosure accessory.
        GroupedHeader("Wallpaper")
        GroupedRow(
            title = "Choose Wallpaper…",
            subtitle = "Shown behind new chats and the sessions list",
            icon = RobocoIcons.Photo,
            position = GroupedPosition.Single,
        )

        // 4. "Sessions" — the two-line subtitle row (84 dp) next to a
        // switch that is on.
        GroupedHeader("Sessions")
        var groupByProject by remember { mutableStateOf(true) }
        GroupedRow(
            title = "Group by Project",
            subtitle = "Sessions outside your sections, under their project",
            icon = RobocoIcons.SquareStack3dUp,
            position = GroupedPosition.First,
            accessories = listOf(
                GroupedAccessory.Custom {
                    RobocoSwitch(groupByProject, { groupByProject = it }, testTag = toggleTag("groupByProject"))
                },
            ),
            onClick = null,
        )
        GroupedRow(
            title = "Archived Sessions",
            icon = RobocoIcons.Archivebox,
            position = GroupedPosition.Last,
        )

        // 5. "Devices" — Roboco fixture copy (ticket 102): a dot +
        // disclosure pair and a plain disclosure row.
        GroupedHeader("Devices")
        GroupedRow(
            title = "MacBook Pro",
            subtitle = "Connected",
            icon = RobocoIcons.Desktopcomputer,
            position = GroupedPosition.First,
            accessories = listOf(
                GroupedAccessory.Dot(online = true),
                GroupedAccessory.Disclosure,
            ),
        )
        GroupedRow(
            title = "Pair an engine",
            icon = RobocoIcons.Plus,
            position = GroupedPosition.Last,
        )

        // 6. The blank-title section (" "): header height, no text. The
        // Version row is the value cell (Roboco copy, ticket 101), not
        // clickable; "Remove Wallpaper" is the destructive row with no
        // accessories at all.
        GroupedHeader(" ")
        GroupedRow(
            title = "Version",
            position = GroupedPosition.First,
            accessories = listOf(GroupedAccessory.Value("0.1.0 (1)")),
            onClick = null,
        )
        GroupedRow(
            title = "Remove Wallpaper",
            icon = RobocoIcons.Trash,
            destructive = true,
            accessories = emptyList(),
            position = GroupedPosition.Last,
        )

        // 7. The pressed-highlight demo: the highlight held at 1, so the
        // gallery (and the golden) show the press fill.
        Spacer(Modifier.height(16.dp))
        BasicText(
            "pressed",
            style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            modifier = Modifier.padding(
                start = GroupedListTokens.HeaderHorizontal + GroupedListTokens.CardInset,
            ),
        )
        GroupedRow(
            title = "Archived Sessions",
            icon = RobocoIcons.Archivebox,
            position = GroupedPosition.Single,
            previewPressed = true,
        )
    }
}

@Preview(name = "light")
@Composable
private fun GroupedListGalleryLightPreview() {
    RobocoTheme(Appearance.Light) { GroupedListGallerySection() }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GroupedListGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) { GroupedListGallerySection() }
}
