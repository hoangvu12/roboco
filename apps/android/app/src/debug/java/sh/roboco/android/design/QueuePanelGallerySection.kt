package sh.roboco.android.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.composer.QueueAction
import sh.roboco.android.composer.QueuePanel
import sh.roboco.android.composer.QueuePanelFixtures
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.session.SessionChrome

/**
 * The "Queue panel" page of the debug design gallery (android-ui-parity
 * #82), registered in ticket 22's registry as
 * `GallerySection("Queue panel", screenshotHeightDp = 891) { QueuePanelGallerySection() }`.
 *
 * Two halves, each on its own [GlassStage] over the striped backdrop:
 *  - the **static** fixtures — F1 (one row), F3 (three rows), F5 (three
 *    rows + `+2 more queued`), FG (gate labels + `+1 more queued`), the
 *    same fixtures the previews and the Roborazzi goldens shoot;
 *  - a **live** [QueuePanel] driven by its own `onAction` — the port of
 *    `FixtureSessionSource.queueAction`
 *    (`ios:Session/SessionSource.swift:195-205`): Remove deletes the
 *    row, Move Up / Move Down swap with the neighbour, Send Now removes
 *    the row (the fixture source has no engine behind it), and Edit
 *    stands in for ticket 83 with a toast. "Add row" / "Add long row" /
 *    "Clear" / "Writable" drive the state.
 *
 * The live panel's glass mirrors ticket 74: `glassVisible =
 * list.isNotEmpty()` with `animateGlass = false` on the first composition
 * (a `SideEffect` flips it afterwards), so Clear dematerializes over
 * 250 ms and the next Add grows the card in over 350 ms, while row
 * add/remove changes snap inside the panel (74's reflow spring owns the
 * stack motion).
 *
 * The section wraps itself in its own [GlassMenuHost] (the registry's
 * [GalleryProviders] installs no host): the content is the haze source
 * the ⋯ menu panels blur, and each stage's backdrop is a second source
 * of the same state so the cards themselves blur the stripes.
 *
 * Try it live (`adb shell am start -n sh.roboco.android/.MainActivity
 * --es route gallery --es section queue_panel`): tap ⋯ on row 0 (Move Up
 * is greyed), pick Remove, then Add long row and watch the second row's
 * label fade at its tail with both ⋯ buttons still on the card.
 */
@Composable
fun QueuePanelGallerySection() {
    val hazeState = rememberHazeState()
    GlassMenuHost(hazeState = hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QueueGalleryCaption(
                "Queue panel",
                uiStyle(Face.SansSemibold, LocalTypeScale.current.fixed(17f), RobocoTheme.palette.text),
            )
            QueueGalleryCaption(
                "Messages waiting for the turn to end, above the composer: one glass " +
                    "card, at most three rows, a +N more queued footer, a send-now button " +
                    "and a ⋯ menu (Edit / Move Up / Move Down / Remove). Long rows fade " +
                    "at the tail, never ellipsize.",
                uiStyle(Face.Sans, LocalTypeScale.current.fixed(13f), RobocoTheme.palette.secondary),
            )
            QueueFixtureStage("F1 — one row (48 dp card)", QueuePanelFixtures.F1, 96.dp, hazeState)
            QueueFixtureStage("F3 — three rows (128 dp card)", QueuePanelFixtures.F3, 176.dp, hazeState)
            QueueFixtureStage("F5 — +2 more queued (152 dp card)", QueuePanelFixtures.F5, 200.dp, hazeState)
            QueueFixtureStage("FG — gates (152 dp card)", QueuePanelFixtures.FG, 200.dp, hazeState)
            QueueLiveDemo(hazeState)
        }
    }
}

/** One static fixture on its own stage, with a caption above it. */
@Composable
private fun QueueFixtureStage(
    caption: String,
    items: List<SessionChrome.QueuedItem>,
    height: Dp,
    hazeState: HazeState,
) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    QueueGalleryCaption(caption, uiStyle(Face.SansMedium, typeScale.fixed(12f), palette.tertiary))
    GlassStage(
        modifier = Modifier.width(378.dp),
        height = height,
        backdropModifier = Modifier.hazeSource(hazeState),
    ) {
        ProvideHazeState(hazeState) {
            QueuePanel(
                items = items,
                glassVisible = true,
                animateGlass = false,
                onAction = { _, _ -> },
            )
        }
    }
}

/**
 * The live panel: a local list mutated by the panel's own actions (the
 * fixture source's rules) plus the Add / Add long row / Clear / Writable
 * chips and an action log.
 */
@Composable
private fun QueueLiveDemo(hazeState: HazeState) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    val toaster = LocalToaster.current

    var items by remember { mutableStateOf(QueuePanelFixtures.F3) }
    var writable by remember { mutableStateOf(true) }
    var log by remember { mutableStateOf("(no action yet)") }
    var nextId by remember { mutableIntStateOf(6) }
    // Ticket 74's rule: glass animates only after the first composition.
    var animateGlass by remember { mutableStateOf(false) }
    SideEffect { if (!animateGlass) animateGlass = true }

    fun act(id: String, action: QueueAction) {
        log = "$action($id)"
        when (action) {
            QueueAction.SendNow, QueueAction.Remove ->
                items = items.filterNot { it.id == id }
            QueueAction.MoveUp -> items = swapAt(items, id, -1)
            QueueAction.MoveDown -> items = swapAt(items, id, +1)
            QueueAction.Edit -> toaster.show("Edit (ticket 83)")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QueueGalleryCaption(
            "Live — tap ↑ or ⋯; Edit is ticket 83's (toast stand-in)",
            uiStyle(Face.SansMedium, typeScale.fixed(12f), palette.tertiary),
        )
        GlassStage(
            modifier = Modifier.width(378.dp),
            height = 200.dp,
            backdropModifier = Modifier.hazeSource(hazeState),
        ) {
            ProvideHazeState(hazeState) {
                QueuePanel(
                    items = items,
                    glassVisible = items.isNotEmpty(),
                    animateGlass = animateGlass,
                    writable = writable,
                    onAction = { id, action -> act(id, action) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueueGalleryChip("Add row") {
                items = items + SessionChrome.QueuedItem(
                    id = "q$nextId",
                    text = "Queued message $nextId",
                    gate = null,
                )
                nextId++
            }
            QueueGalleryChip("Add long row") {
                items = items + SessionChrome.QueuedItem(
                    id = "q$nextId",
                    text = QueuePanelFixtures.LongRowText,
                    gate = null,
                )
                nextId++
            }
            QueueGalleryChip("Clear") { items = emptyList() }
            QueueGalleryChip("Writable ${if (writable) "on" else "off"}") { writable = !writable }
        }
        QueueGalleryCaption(log, uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary))
    }
}

/**
 * `FixtureSessionSource.queueAction`'s move rule: swap the row with its
 * neighbour, a no-op at the edges.
 */
private fun swapAt(items: List<SessionChrome.QueuedItem>, id: String, delta: Int): List<SessionChrome.QueuedItem> {
    val i = items.indexOfFirst { it.id == id }
    val j = i + delta
    if (i < 0 || j < 0 || j >= items.size) return items
    val list = items.toMutableList()
    val row = list[i]
    list[i] = list[j]
    list[j] = row
    return list
}

/** A plain text caption; a `BasicText`, no Material controls. */
@Composable
private fun QueueGalleryCaption(text: String, style: TextStyle) {
    BasicText(text, style = style)
}

/** A plain text chip; a `clickable` BasicText, no Material controls. */
@Composable
private fun QueueGalleryChip(text: String, onClick: () -> Unit) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
