package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Tool rail" section of the debug design gallery (ticket 121): the
 * activity rail of an expanded tool group, at the Rust geometry of a
 * 390 dp row — the rail box at x = 18, stand-in row content under the rail
 * so the cover reveal is visible, the colours from the app appearance.
 *
 *  - the settled fixture: the static [RailFixture3] (three rows, the middle
 *    one with an open detail) — the fully drawn rail;
 *  - the live demo: a group that starts at 1 row and draws in. **Replay**
 *    takes a fresh key (a brand-new group: 90 ms lead-in, then one row per
 *    65 ms); **Add row** grows the same key (only the new row draws in,
 *    starting at once); **Add 3** grows by three (the 65 ms stagger is
 *    visible). Under the gallery's "Force reduce motion" every control
 *    shows the final state at once.
 *
 * Registered in `Gallery.sections` as "Tool rail" (slug `tool_rail`).
 */
@Composable
fun ToolRailGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Caption("Settled")
        ToolRailFixture(RailFixture3, live = false, key = 1uL, token = null)
        Caption("Live")
        LiveRailDemo()
    }
}

/**
 * The worked fixture at text scale 1 (`ios:layout/tools.rs` at T = 1.125,
 * `px = 1`), used by the tests, the goldens and this gallery: three rows,
 * the middle one with a 60 dp open detail.
 */
val RailFixture3 = railDemoSpec(3)

/**
 * The demo spec for [count] rows: [RailFixture3]'s rule — 36 dp rows, row 1
 * carrying the open 60 dp detail — prefixed for a smaller count and
 * extended (36 dp rows beyond 3) for a larger one, so growing the count
 * only ever appends rows.
 */
internal fun railDemoSpec(count: Int): RailSpec {
    val heights = FloatArray(count) { if (it == 1) 96f else 36f }
    val tops = FloatArray(count)
    var y = 31.5f
    for (i in 0 until count) {
        tops[i] = y
        y += heights[i]
    }
    return RailSpec(14.0625f, 6.75f, 31.5f, 18f, tops, heights)
}

/**
 * The rail host the goldens, the gallery and the Robolectric tests compose:
 * a 390 dp row on `palette.background` with stand-in row content **under**
 * the rail (the cover fades in over it), and the rail box placed at
 * x = 18, as `WidgetBox` would place it (63 dp wide — the cover still runs
 * to the row's right edge, outside the box).
 */
@Composable
fun ToolRailFixture(
    spec: RailSpec,
    live: Boolean,
    key: ULong,
    token: Any?,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val height = railHeightDp(spec)
    Box(modifier.width(390.dp).height(height).background(palette.background)) {
        RailStandIns(spec)
        Box(Modifier.absoluteOffset(x = 18.dp, y = 0.dp).size(63.dp, height)) {
            ToolRail(groupKey = key, spec = spec, rowWidthDp = 372f, live = live, buildToken = token)
        }
    }
}

/** The widget rect's height: the last row's bottom (`ry − y` in `tools.rs`). */
private fun railHeightDp(spec: RailSpec): Dp =
    (if (spec.count == 0) 0f else spec.tops[spec.count - 1] + spec.heights[spec.count - 1]).dp

/**
 * The stand-in row content the cover reveals (the real icon is 124's, the
 * label 62's Rust paint): an 18 × 18 dp `secondary` square (radius 4) at
 * widget-local (36, top + 9) — where `place_line_header` puts the 18 dp
 * icon — an 8 dp `tertiary` bar of width 120 at (63, top + 14), and a
 * `codeBackground` block under any row with an open detail.
 */
@Composable
private fun RailStandIns(spec: RailSpec) {
    val palette = RobocoTheme.palette
    for (i in 0 until spec.count) {
        val top = spec.tops[i]
        Box(
            Modifier
                .absoluteOffset(x = 18.dp + 36.dp, y = (top + 9).dp)
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(palette.secondary),
        )
        Box(
            Modifier
                .absoluteOffset(x = 18.dp + 63.dp, y = (top + 14).dp)
                .size(120.dp, 8.dp)
                .background(palette.tertiary),
        )
        val detail = spec.heights[i] - 36f
        if (detail > 0f) {
            Box(
                Modifier
                    .absoluteOffset(x = 18.dp + 63.dp, y = (top + 36).dp)
                    .size(200.dp, detail.dp)
                    .background(palette.codeBackground),
            )
        }
    }
}

/** The live demo: one group, its key / count / token driven by the controls. */
@Composable
private fun LiveRailDemo() {
    var key by remember { mutableStateOf(100uL) }
    var count by remember { mutableIntStateOf(1) }
    var token by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DemoChip("Replay") {
                key += 1uL
                count = 1
                token++
            }
            DemoChip("Add row") {
                if (count < 5) {
                    count++
                    token++
                }
            }
            DemoChip("Add 3") {
                count = min(5, count + 3)
                token++
            }
        }
        ToolRailFixture(railDemoSpec(count), live = true, key = key, token = token)
    }
}

/** A small section caption: Geist 13.5 dp fixed in `secondary`. */
@Composable
private fun Caption(text: String) {
    BasicText(
        text,
        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(13.5f), RobocoTheme.palette.secondary),
    )
}

/** A plain-text control chip: a `clickable` BasicText, no Material controls. */
@Composable
private fun DemoChip(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(14f), RobocoTheme.palette.accent),
        modifier = Modifier
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
