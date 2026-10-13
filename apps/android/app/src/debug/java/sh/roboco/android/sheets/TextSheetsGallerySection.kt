package sh.roboco.android.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.transcript.DetailWidget

/**
 * The "Text sheets" page of the debug design gallery (ticket 127): one
 * button per variant of [SelectTextSheet] plus the `Detail` widget demo —
 *
 * - "Select Text (short)": three paragraphs, the whole text selected on
 *   open (`TranscriptListView.swift:641-645`);
 * - "Select Text (long)": 300 lines of prose — the large-text check;
 * - "Tool detail (Bash)": the mono payload of the preview, medium/large
 *   detents with the grabber (`RowView.swift:244-248`);
 * - the "Detail widget" box: the invisible tap target in a 320 × 44 dp
 *   striped box, filling a 200 × 20 dp rect at (16, 12). The thin dashed
 *   outline is drawn **by the gallery only** so the target can be found;
 *   the widget itself draws nothing.
 *
 * Every button opens through a [TextSheetController] and a
 * [TextSheetOverlay] placed over the section, exactly as the session
 * screen (75) will mount it — one sheet at a time, new requests ignored
 * while one is showing. Under capture no sheet is open, so the golden is
 * the button list and the widget box.
 *
 * Registered in `Gallery.sections` as
 * `GallerySection("Text sheets", GalleryLayout.Fill, screenshotHeightDp = 420) { TextSheetsGallerySection() }`
 * — `Fill` because the section hosts a full-screen overlay.
 */
@Composable
fun TextSheetsGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val controller = rememberTextSheetController()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                "Text sheets — Select Text and the tool detail (ticket 127)",
                style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            )
            GalleryButton("Select Text (short)") { controller.selectText(ShortMessage) }
            GalleryButton("Select Text (long)") { controller.selectText(LongProse) }
            GalleryButton("Tool detail (Bash)") { controller.showDetail("Bash", ToolPayload) }
            BasicText(
                "Open each: Select Text selects the whole text on open (no keyboard, handles + " +
                    "Copy); the tool detail opens at half screen with the grabber — swipe the text " +
                    "up to expand it to large first. Done, the scrim, back and a drag dismiss. " +
                    "New requests are ignored while a sheet is showing.",
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
            DetailWidgetDemo(controller)
            BasicText(
                "The Detail widget in the striped box above is invisible: only its dashed outline " +
                    "(gallery-only) marks the 200 × 20 dp rect at (16, 12). A tap opens the tool " +
                    "detail; a drag starting on it scrolls this list instead.",
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
        }
        // The sheet over everything, the session screen's mounting shape.
        TextSheetOverlay(controller)
    }
}

/** One plain-text button row (no Material controls). */
@Composable
private fun GalleryButton(text: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
    )
}

/**
 * The "Detail widget" demo: the invisible tap target over stripes. The
 * widget fills the 200 × 20 dp rect WidgetBox would give it; the dashed
 * outline exists only here so the target can be found on a device.
 */
@Composable
private fun DetailWidgetDemo(controller: TextSheetController) {
    val palette = RobocoTheme.palette
    Box(
        Modifier
            .width(320.dp)
            .height(44.dp)
            .drawBehind {
                // Stripes, so the target's transparency is visible against
                // something busy (deterministic: the gallery golden).
                val bar = 12.dp.toPx()
                var x = 0f
                var i = 0
                while (x < size.width) {
                    drawRect(
                        if (i % 2 == 0) palette.elevated else palette.chip,
                        topLeft = Offset(x, 0f),
                        size = Size(minOf(bar, size.width - x), size.height),
                    )
                    x += bar
                    i++
                }
                // The rect the widget fills — gallery-only, dashed.
                drawRect(
                    palette.secondary,
                    topLeft = Offset(16.dp.toPx(), 12.dp.toPx()),
                    size = Size(200.dp.toPx(), 20.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                        ),
                    ),
                )
            },
    ) {
        Box(Modifier.absoluteOffset(16.dp, 12.dp).size(200.dp, 20.dp)) {
            DetailWidget("Bash", ToolPayload) { title, payload ->
                controller.showDetail(title, payload)
            }
        }
    }
}

/** The short fixture: the preview's three-paragraph message. */
private val ShortMessage =
    "Roboco connects directly to the engines you pair with it — no account, no cloud, no sync.\n\n" +
        "The transcript is painted straight from the Rust layout core, so the text in the list itself " +
        "cannot be selected. Select Text shows the whole message here, where the system selection " +
        "handles and the Copy toolbar work.\n\n" +
        "Done dismisses the sheet; the text stays selected while it is open."

/** The long fixture: 300 lines of prose (the large-text check). */
private val LongProse: String = buildString {
    for (i in 1..300) {
        appendLine("prose line $i: the paint-only transcript keeps scrolling cheap; Select Text hands the whole string to the system text engine.")
    }
}

/** The tool-detail fixture: 40 mono lines, the last one 160 characters. */
private val ToolPayload: String = buildString {
    for (i in 1..39) appendLine("line $i: $ roboco engine start --port ${8674 + i}")
    append("k".repeat(160))
}
