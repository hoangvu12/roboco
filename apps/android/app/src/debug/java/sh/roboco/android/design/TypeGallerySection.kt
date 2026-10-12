package sh.roboco.android.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import sh.roboco.android.core.Face

/**
 * A `Density` whose sp↔dp conversion is linear (android-ui-parity #22).
 * Android 14's non-linear `FontScaleConverter` maps font scale 1.6 to a body
 * factor below 1.6, so a real font scale cannot show the clamp end exactly;
 * this override gives exact factors, matching ticket 03's expected-value
 * table (0.85 linear, 1.6 = clamp).
 */
internal class LinearFontScaleDensity(
    override val density: Float,
    override val fontScale: Float,
) : Density {
    override fun Dp.toSp(): TextUnit = (value / fontScale).sp

    override fun TextUnit.toDp(): Dp {
        check(type == TextUnitType.Sp) { "Only Sp can convert to Dp" }
        return (value * fontScale).dp
    }
}

/**
 * The "Type" gallery section (android-ui-parity #22, on ticket 03): the
 * iOS call-site sizes at font scale 0.85 / 1 / 1.6 — the clamp ends and the
 * default — each block under a [LinearFontScaleDensity] with
 * [LocalTypeScale] re-provided, so `factor = bodyFactor = f` exactly.
 * Below them, ticket 03's [TypeSpecimen] at the real density.
 *
 * Both locals are re-provided together: `BasicText` converts sp with
 * [LocalDensity], and `fixed` converts with the density captured in
 * [TypeScale].
 */
@Composable
fun TypeGallerySection() {
    val outer = LocalDensity.current
    Column(Modifier.fillMaxWidth()) {
        for ((index, factor) in listOf(0.85f, 1f, 1.6f).withIndex()) {
            if (index > 0) Spacer(Modifier.height(20.dp))
            FactorBlock(factor, outer)
        }
        Spacer(Modifier.height(20.dp))
        // TypeSpecimen scrolls internally; a fixed-height box is required so
        // nesting it in the frame's verticalScroll never measures it
        // unbounded.
        Box(Modifier.fillMaxWidth().height(440.dp)) {
            TypeSpecimen()
        }
    }
}

@Composable
private fun FactorBlock(factor: Float, outer: Density) {
    val palette = RobocoTheme.palette
    val d = remember(outer.density, factor) { LinearFontScaleDensity(outer.density, factor) }
    val ts = remember(d) { TypeScale.from(d) }
    CompositionLocalProvider(LocalDensity provides d, LocalTypeScale provides ts) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(
                String.format(Locale.ROOT, "factor %.2f", factor),
                style = uiStyle(Face.SansSemibold, ts.fixed(13f), palette.secondary),
                maxLines = 1,
            )
            BasicText(
                String.format(Locale.ROOT, "fontScale %.2f · linear · body %.3f", factor, ts.bodyFactor),
                style = uiStyle(Face.Mono, ts.fixed(11f), palette.tertiary),
                maxLines = 1,
            )
            // One row per iOS call site: the sample in its own style, an 8 dp
            // gap, the formula label in Mono.
            TypeRow(
                "Refactor session list",
                uiStyle(Face.SansMedium, ts.scaled(16.5f), palette.text),
                "scaled(16.5) = ${roundHalfAway(16.5f * ts.factor).toInt()}",
                ts, palette,
            )
            TypeRow(
                "roboco",
                uiStyle(Face.Sans, ts.scaled(13.5f), palette.text),
                "scaled(13.5) = ${roundHalfAway(13.5f * ts.factor).toInt()}",
                ts, palette,
            )
            TypeRow(
                "main",
                uiStyle(Face.Sans, ts.scaled(12.5f), palette.text),
                "scaled(12.5) = ${roundHalfAway(12.5f * ts.factor).toInt()}",
                ts, palette,
            )
            TypeRow(
                "Done",
                uiStyle(Face.SansMedium, ts.scaled(13f), palette.text),
                "scaled(13) = ${roundHalfAway(13f * ts.factor).toInt()}",
                ts, palette,
            )
            TypeRow(
                // The PR-badge label is the bare number (StatusGlyph.swift:204).
                "102",
                uiStyle(Face.MonoMedium, ts.scaled(11f), palette.text),
                "scaled(11) = ${roundHalfAway(11f * ts.factor).toInt()}",
                ts, palette,
            )
            TypeRow(
                "Pinned",
                uiStyle(Face.SansSemibold, ts.scaled(13.5f), palette.text),
                "scaled(13.5) = ${roundHalfAway(13.5f * ts.factor).toInt()}",
                ts, palette,
            )
            TypeRow(
                "Message Claude",
                uiStyle(Face.Sans, ts.bodyScaled(16.5f), palette.text),
                String.format(Locale.ROOT, "bodyScaled(16.5) = %.1f", 16.5f * ts.bodyFactor),
                ts, palette,
            )
            TypeRow(
                "Sessions",
                uiStyle(Face.SansSemibold, ts.fixed(17f), palette.text),
                "fixed(17) = 17",
                ts, palette,
            )
            // SessionCell.swift:49: the row height scales with the factor too.
            TypeRow(
                null,
                null,
                "row scaledDp(62) = ${roundHalfAway(62f * ts.factor).toInt()}",
                ts, palette,
            )
        }
    }
}

@Composable
private fun TypeRow(sample: String?, sampleStyle: TextStyle?, label: String, ts: TypeScale, palette: Palette) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (sample != null && sampleStyle != null) {
            BasicText(
                sample,
                style = sampleStyle,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        BasicText(
            label,
            style = uiStyle(Face.Mono, ts.fixed(11f), palette.secondary),
            maxLines = 1,
        )
    }
}
