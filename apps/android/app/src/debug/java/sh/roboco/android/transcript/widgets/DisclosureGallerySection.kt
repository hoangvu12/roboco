package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Transcript disclosure" section of the debug design gallery (ticket
 * 120): the Rust geometry of the three widget kinds on `palette.background`,
 * 393 dp wide with the row origin at x = 18 and content width 357 —
 *
 *  - the static fixture: an expanded and a collapsed tool header (the
 *    chevron at (6.1875, 6.75) 15.75², the summary stand-in at 31.5), an
 *    idle and a pressed tool row (the label stand-in at the rail's end,
 *    63 dp, with the square `controlFill` press fill over it);
 *  - the live demo: one header whose `DisclosureTarget` flips a local
 *    `expanded` — the chevron flips with the 140 ms fold curve and snaps
 *    under the gallery's "Force reduce motion" — and two 36 dp tool rows,
 *    each with a working `ToolToggleTarget` and a 40 dp `controlFill`
 *    block standing in for the Rust-painted detail body.
 *
 * Haptics play through the real `LocalFeedback` (the gallery installs the
 * engine; captures get `NoFeedback`). The text is a stand-in for Rust
 * paint (62/63): Geist 13.5 dp fixed in `secondary`.
 *
 * Registered in `Gallery.sections` as "Transcript disclosure" (slug
 * `transcript_disclosure`).
 */
@Composable
fun DisclosureGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        DisclosureFixture(static = true)
        DisclosureFixture(static = false)
    }
}

/**
 * The shared fixture (previews, goldens, the gallery): [static] renders
 * the four fixed rows, else the live expand / collapse demo. Composed at
 * `w393dp-h852dp-xxhdpi` for the Roborazzi goldens
 * (`transcript/disclosure_toggles_{light,dark}.png`).
 */
@Composable
fun DisclosureFixture(static: Boolean) {
    if (static) {
        Column(
            Modifier.padding(start = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FixtureHeaderRow("Ran 3 commands", key = 1uL, expanded = true)
            FixtureHeaderRow("Read 2 files", key = 2uL, expanded = false)
            FixtureToolRow("Ran ls -la", pressed = false)
            FixtureToolRow("Ran git status", pressed = true)
        }
    } else {
        LiveDisclosureDemo()
    }
}

/** A mock tool-group header: the chevron at its Rust rect, the summary stand-in at TITLE_X. */
@Composable
private fun FixtureHeaderRow(title: String, key: ULong, expanded: Boolean) {
    Box(Modifier.width(357.dp).height(29.25.dp)) {
        ToolChevron(key, expanded, Modifier.absoluteOffset(6.1875.dp, 6.75.dp).size(15.75.dp))
        StandInText(title, 31.5.dp)
    }
}

/** A mock tool row: the label stand-in right of the rail (63 dp), the press fill over it. */
@Composable
private fun FixtureToolRow(label: String, pressed: Boolean) {
    Box(Modifier.width(357.dp).height(36.dp)) {
        StandInText(label, 63.dp)
        // On top of the label: iOS's control background covers the row text.
        ToolToggleSurface(pressed)
    }
}

/** The stand-in for the text Rust paints (62/63): Geist 13.5 dp fixed in `secondary`. */
@Composable
private fun StandInText(text: String, x: Dp) {
    Box(Modifier.absoluteOffset(x, 0.dp).fillMaxHeight().width(240.dp)) {
        BasicText(
            text,
            Modifier.align(Alignment.CenterStart),
            style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(13.5f), RobocoTheme.palette.secondary),
        )
    }
}

/**
 * The live demo: a header with a working [DisclosureTarget] over
 * (0, 0, 145, 29.25) — the exact Rust rect for a 100 dp summary — whose
 * local flip drives the chevron, and while expanded two 36 dp tool rows
 * with working [ToolToggleTarget]s. The state is local and optimistic:
 * the gallery has no engine, so the flip stands in for the frame that
 * Rust would publish in the app (the real wiring never flips locally).
 */
@Composable
private fun LiveDisclosureDemo() {
    Column(
        Modifier.padding(start = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var expanded by remember { mutableStateOf(true) }
        Box(Modifier.width(357.dp).height(29.25.dp)) {
            ToolChevron(3uL, expanded, Modifier.absoluteOffset(6.1875.dp, 6.75.dp).size(15.75.dp))
            StandInText("Ran 3 commands", 31.5.dp)
            DisclosureTarget(
                expanded = expanded,
                onToggle = { expanded = !expanded },
                modifier = Modifier.absoluteOffset(0.dp, 0.dp).size(145.dp, 29.25.dp),
            )
        }
        if (expanded) {
            LiveToolRow("Ran ls -la")
            LiveToolRow("Ran git status")
        }
    }
}

@Composable
private fun LiveToolRow(label: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Box(Modifier.width(357.dp).height(36.dp)) {
            StandInText(label, 63.dp)
            ToolToggleTarget(open = open, onToggle = { open = !open })
        }
        if (open) {
            // The detail body's stand-in (Rust paints it in the app).
            Box(Modifier.width(357.dp).height(40.dp).background(RobocoTheme.palette.controlFill))
        }
    }
}
