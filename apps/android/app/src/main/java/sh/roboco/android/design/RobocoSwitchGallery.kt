package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face

/**
 * The "Switch" page of the debug design gallery (android-ui-parity #98):
 * the `UISwitch` port's fixture. The static card shows every rest and lift
 * state of [RobocoSwitchVisual] (the two Rows exist because five 63 dp
 * fixtures do not fit a 402 dp card); the two interactive rows below use
 * ticket 70's grouped rows with the iOS copy from
 * `MoreViewController.swift:132,214` — the first a **refusing** caller
 * (its `onCheckedChange` ignores the value, so the thumb springs back),
 * the second an accepting one starting on.
 *
 * Registered by ticket 22's registry as
 * `GallerySection("Switch", screenshotHeightDp = 420)`. The goldens
 * (`design/robocoswitch_{light,dark}.png`, `w402dp-h420dp-xxhdpi`) live in
 * [RobocoSwitchScreenshotTest], which hosts this section directly.
 */
@Composable
fun RobocoSwitchGallerySection() {
    var groupByProject by remember { mutableStateOf(true) }

    // One child, so the gallery frame's 16 dp child spacing never splits
    // the First/Last rows of the interactive card apart.
    Column(Modifier.fillMaxWidth()) {
        // 1. The state card: static fixtures, captioned in Geist 13 secondary.
        GroupedRowContainer(position = GroupedPosition.Single, onClick = null) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SwitchFixture("off", fraction = 0f, lift = 0f)
                    SwitchFixture("on", fraction = 1f, lift = 0f)
                    SwitchFixture("mid-drag", fraction = 0.5f, lift = 1f)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SwitchFixture("off · pressed", fraction = 0f, lift = 1f)
                    SwitchFixture("on · pressed", fraction = 1f, lift = 1f)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 2. The interactive rows: iOS copy verbatim, the row itself not
        // clickable — only the switch reacts.
        GroupedRow(
            title = "Notifications",
            subtitle = "When a session finishes, needs you or fails",
            icon = RobocoIcons.Bell,
            position = GroupedPosition.First,
            accessories = listOf(
                GroupedAccessory.Custom {
                    // The refusing caller: the value is never accepted, so a
                    // tap animates nothing and a drag springs back.
                    RobocoSwitch(checked = false, onCheckedChange = {}, testTag = toggleTag("notify:enabled"))
                },
            ),
            onClick = null,
        )
        GroupedRow(
            title = "Group by Project",
            subtitle = "Sessions outside your sections, under their project",
            icon = RobocoIcons.SquareStack3dUp,
            position = GroupedPosition.Last,
            accessories = listOf(
                GroupedAccessory.Custom {
                    RobocoSwitch(groupByProject, { groupByProject = it }, testTag = toggleTag("groupByProject"))
                },
            ),
            onClick = null,
        )
    }
}

/** One captioned [RobocoSwitchVisual] of the state card. */
@Composable
private fun SwitchFixture(caption: String, fraction: Float, lift: Float) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RobocoSwitchVisual(fraction = { fraction }, lift = { lift })
        BasicText(caption, style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary))
    }
}

@Preview(name = "light")
@Composable
private fun RobocoSwitchPreviewLight() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            RobocoSwitchGallerySection()
        }
    }
}

@Preview(name = "dark")
@Composable
private fun RobocoSwitchPreviewDark() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            RobocoSwitchGallerySection()
        }
    }
}
