package sh.roboco.android.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face

/**
 * The "Status glyph" section of the design gallery (android-ui-parity #11,
 * registered in `Gallery.sections`): the four visible kinds, every status
 * tone as a dot, the transcript boxes, the ×1.75 loader spinner and a
 * session-row corner sample. The gallery frame provides the theme, so the
 * glyphs run on RobocoTheme's shared clock — every spinner on the page
 * pulses in sync, and the "Force reduce motion" toggle freezes them at the
 * static (cycle-0) alphas by dropping their clock subscriptions.
 *
 * Labels are foundation `BasicText` (no Material `Text`); the "Working"
 * sample matches `ios:Threads/SessionCell.swift:142` (`TypeScale.size(13)`,
 * `palette.status.working`), the glyph 5 dp before it and centred on the
 * text line.
 */
@Composable
fun StatusGlyphGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. The five kinds at scale 1 in 12-dp boxes: None (an empty box —
        //    it draws nothing), then the preview's five. The 4-dp cell
        //    padding keeps each grid's unclipped overflow visible.
        RowLabel("kinds · scale 1 · 12 dp boxes")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            GlyphSample("none") { StatusGlyph(StatusGlyphKind.None) }
            GlyphSample("spinner") { StatusGlyph(StatusGlyphKind.Spinner) }
            GlyphSample("trailer") { StatusGlyph(StatusGlyphKind.Trailer) }
            GlyphSample("dot input") { StatusGlyph(StatusGlyphKind.Dot(palette.status.input)) }
            GlyphSample("dot failed") { StatusGlyph(StatusGlyphKind.Dot(palette.status.failed)) }
            GlyphSample("check done") { StatusGlyph(StatusGlyphKind.Check(palette.status.done)) }
        }

        // 2. Dot in every StatusTone plus Palette.danger, and Check(done).
        RowLabel("dots · every status tone + danger")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            GlyphSample("working") { StatusGlyph(StatusGlyphKind.Dot(palette.status.working)) }
            GlyphSample("input") { StatusGlyph(StatusGlyphKind.Dot(palette.status.input)) }
            GlyphSample("failed") { StatusGlyph(StatusGlyphKind.Dot(palette.status.failed)) }
            GlyphSample("done") { StatusGlyph(StatusGlyphKind.Dot(palette.status.done)) }
            GlyphSample("idle") { StatusGlyph(StatusGlyphKind.Dot(palette.status.idle)) }
            GlyphSample("danger") { StatusGlyph(StatusGlyphKind.Dot(palette.danger)) }
            GlyphSample("check done") { StatusGlyph(StatusGlyphKind.Check(palette.status.done)) }
        }

        // 3. The transcript boxes: the Working/Writing trailer's fixed
        //    14 × 14 (RowView.swift:445) and the core's spinner widget,
        //    14 × 1.125 × text scale = 15.75 × 15.75 at scale 1. The cells
        //    stay 3.5 dp; they are only centred in a bigger box.
        RowLabel("transcript · trailer 14 × 14 · spinner 15.75 × 15.75")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            GlyphSample("trailer 14") {
                StatusGlyph(StatusGlyphKind.Trailer, modifier = Modifier.size(14.dp))
            }
            GlyphSample("spinner 15.75") {
                StatusGlyph(StatusGlyphKind.Spinner, modifier = Modifier.size(15.75.dp))
            }
        }

        // 4. The session-open loader / pull-to-refresh scale (ticket 48).
        RowLabel("spinner × 1.75 · session loader · pull-to-refresh")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            GlyphSample("× 1.75") { StatusGlyph(StatusGlyphKind.Spinner, scale = 1.75f) }
        }

        // 5. Sample session-row corner — alignment eyeball only; the real
        //    row is ticket 37's.
        RowLabel("session row corner")
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(StatusGlyphKind.Spinner)
            Spacer(Modifier.width(5.dp))
            BasicText(
                "Working",
                style = uiStyle(Face.SansMedium, typeScale.scaled(13f), palette.status.working),
            )
        }
    }
}

/** A section row label: Mono 11 fixed, secondary. */
@Composable
private fun RowLabel(text: String) {
    BasicText(
        text,
        style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.secondary),
    )
}

/** One glyph in its 4-dp-padded box with a tertiary label under it. */
@Composable
private fun GlyphSample(label: String, glyph: @Composable () -> Unit) {
    Column {
        Box(Modifier.padding(4.dp)) { glyph() }
        BasicText(
            label,
            style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.tertiary),
        )
    }
}
