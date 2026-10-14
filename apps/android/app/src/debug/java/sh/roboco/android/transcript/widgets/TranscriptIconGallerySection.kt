package sh.roboco.android.transcript.widgets

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TranscriptPalette
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.ColorRole

/**
 * The "Transcript icons" section of the debug design gallery (ticket 124):
 * every kind of name the core emits as an `Icon` widget, at the real rect
 * sizes —
 *
 *  - the 11 core tool stems at the 18 dp tool-line size in `secondary`,
 *    plus `tool-terminal` in `danger` (a failed call), and `tool-bot` at
 *    the 13.5 dp sub-agent size;
 *  - the 15.75 dp file-badge file icons (including `angular-component`,
 *    which has no `-dark` art, and the `document` fallback), plus
 *    `fileicon-files-rust` at the 16 dp file-pill size — untinted, with
 *    the art swapped by the appearance;
 *  - the chip-row glyphs at 16 dp (`questionmark.bubble` accent,
 *    `exclamationmark.triangle` danger, `arrow.triangle.branch`
 *    tertiary) and the inline-chip glyphs at 15 dp (`command`,
 *    `wand.and.stars`, `photo` in soft);
 *  - the 18 dp markdown task boxes beside a Geist 16.5 dp body "Task"
 *    label (the box is centred on the 25 dp body line, like
 *    `markdown.rs`), and one 16 dp `waveform` cell that draws nothing and
 *    logs once — the unknown-name case.
 *
 * Flip Light/Dark in the gallery: the file icons switch art
 * (`fileicon-files-code-blue` turns light blue), the tool glyphs re-tint,
 * and the "unknown" cell stays empty.
 *
 * Registered in `Gallery.sections` as "Transcript icons" (slug
 * `transcript_icons`).
 */
@Composable
fun TranscriptIconGallerySection() {
    TranscriptIconFixture()
}

/**
 * The deterministic fixture (previews, goldens, the gallery): a Column on
 * the theme background, cells of a label (Geist 11 dp fixed in `secondary`)
 * over the widget's own Box. Composed at `w393dp-h852dp-xxhdpi` for the
 * Roborazzi goldens (`transcript/icon_widget_{light,dark}.png`).
 */
@Composable
fun TranscriptIconFixture() {
    Column(
        Modifier.background(RobocoTheme.palette.background),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconCaption("Tool glyphs — 18 dp (tool lines), 13.5 dp (agent cards)")
        IconGrid(
            listOf(
                IconCell("terminal", "tool-terminal", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("document", "tool-document", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("document-add", "tool-document-add", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("pen", "tool-pen", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("magnifer", "tool-magnifer", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("folder-with-files", "tool-folder-with-files", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("global", "tool-global", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("checklist", "tool-checklist", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("bot", "tool-bot", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("widget", "tool-widget", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("chat-round-line", "tool-chat-round-line", ColorRole.TEXT_SECONDARY, 18f),
                IconCell("terminal·danger", "tool-terminal", ColorRole.DANGER, 18f),
            ),
        )
        IconCaption("File icons — 15.75 dp (badges, diff stat), 16 dp (file pills)")
        IconGrid(
            listOf(
                IconCell("bot·13.5", "tool-bot", ColorRole.TEXT_SECONDARY, 13.5f),
                IconCell("rust", "fileicon-files-rust", ColorRole.TEXT_SOFT, 15.75f),
                IconCell("code-blue", "fileicon-files-code-blue", ColorRole.TEXT_SOFT, 15.75f),
                IconCell("angular-component", "fileicon-files-angular-component", ColorRole.TEXT_SOFT, 15.75f),
                IconCell("folder", "fileicon-folders-folder", ColorRole.TEXT_SOFT, 15.75f),
                IconCell("document", "fileicon-files-document", ColorRole.TEXT_SOFT, 15.75f),
                IconCell("rust·16", "fileicon-files-rust", ColorRole.TEXT_SOFT, 16f),
            ),
        )
        IconCaption("SF glyphs — 16 dp (chip rows), 15 dp (inline chips)")
        IconGrid(
            listOf(
                IconCell("questionmark.bubble", "questionmark.bubble", ColorRole.ACCENT, 16f),
                IconCell("exclamationmark.triangle", "exclamationmark.triangle", ColorRole.DANGER, 16f),
                IconCell("arrow.triangle.branch", "arrow.triangle.branch", ColorRole.TEXT_TERTIARY, 16f),
                IconCell("command", "command", ColorRole.TEXT_SOFT, 15f),
                IconCell("wand.and.stars", "wand.and.stars", ColorRole.TEXT_SOFT, 15f),
                IconCell("photo", "photo", ColorRole.TEXT_SOFT, 15f),
            ),
        )
        IconCaption("Task boxes — 18 dp, centred on the 25 dp body line")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            TaskCell("square", ColorRole.TEXT_TERTIARY)
            TaskCell("checkmark.square.fill", ColorRole.ACCENT)
        }
        IconCellView(IconCell("unknown", "waveform", ColorRole.TEXT_TERTIARY, 16f))
    }
}

/** One labelled cell: what it is called, and the widget's name. */
private data class IconCell(
    val label: String,
    val name: String,
    val color: ColorRole,
    val sizeDp: Float,
)

/**
 * The cells in lines of three (393 dp − 2 × 16 padding leaves room for a
 * label plus the 12 dp gap): a plain grid, so every name stays visible
 * without a scroller.
 */
@Composable
private fun IconGrid(cells: List<IconCell>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (line in cells.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (cell in line) IconCellView(cell)
            }
        }
    }
}

/** One cell: the label above the widget's own box, at the cell's size. */
@Composable
private fun IconCellView(cell: IconCell) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            cell.label,
            style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.secondary),
        )
        Box(Modifier.size(cell.sizeDp.dp)) {
            TranscriptIcon(
                name = cell.name,
                color = cell.color,
                heightDp = cell.sizeDp,
                palette = TranscriptPalette(LocalIsDark.current),
            )
        }
    }
}

/**
 * One markdown `- [ ]` / `- [x]` line: the 18 dp widget box centred on a
 * 25 dp body line (`markdown.rs:568-578`), the "Task" label at Geist
 * 16.5 dp fixed to show the alignment against the first text line.
 */
@Composable
private fun TaskCell(name: String, color: ColorRole) {
    Row(
        Modifier.height(25.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(18.dp)) {
            TranscriptIcon(name, color, 18f, TranscriptPalette(LocalIsDark.current))
        }
        BasicText(
            "Task",
            style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(16.5f), RobocoTheme.palette.text),
        )
    }
}

/** A section caption: Geist 11 dp fixed in `secondary`. */
@Composable
private fun IconCaption(text: String) {
    BasicText(
        text,
        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.secondary),
    )
}

// ---------------------------------------------------------------------------
// Previews: the fixture, light and dark.
// ---------------------------------------------------------------------------

@Preview(name = "light")
@Composable
private fun TranscriptIconLightPreview() {
    RobocoTheme(Appearance.Light) { TranscriptIconFixture() }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TranscriptIconDarkPreview() {
    RobocoTheme(Appearance.Dark) { TranscriptIconFixture() }
}
