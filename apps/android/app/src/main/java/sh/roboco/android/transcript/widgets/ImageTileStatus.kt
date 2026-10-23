package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.IconInBox
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.ActivitySpinner
import sh.roboco.android.design.uiStyle

/**
 * The tile's three states (ticket 137; iOS `TranscriptImageView.swift`'s
 * Loading/Loaded/Failed): every `WidgetKind.Image` tile — markdown images,
 * user thumbnails and assistant image rows alike — enters Loading on a
 * cache miss, Loaded on a sync hit or a successful load, Failed once
 * every image source failed (or for a blank reference) — and a Failed
 * tile retries on its own.
 */
enum class ImageTileStatus { Loading, Loaded, Failed }

/**
 * The tile's copy (ticket 137, verbatim from iOS except the one ADAPT:
 * "Check the **engine** connection." — Roboco names the paired machine an
 * engine). [RetryActionLabel] is the TalkBack `onClick` label ("Retry",
 * because TalkBack prefixes "Double-tap to"); the verbatim iOS hint
 * [FailedHint] is kept for tests through [ImageTileHintKey].
 */
object ImageTileCopy {
    /** The label fallback when the Rust payload is null (`w.payload ?? "Image"`). */
    const val FallbackLabel = "Image"

    /** Loading status text and a11y value. */
    const val Loading = "Loading image"

    /** Loaded a11y value. */
    const val LoadedValue = "Image loaded"

    /** Loaded a11y hint (also the TalkBack action label). */
    const val LoadedHint = "Open image preview"

    /** Failed status text (spans three lines) and a11y value. */
    const val Failed =
        "Image unavailable\nCheck the engine connection. Use an image in the project folder.\nTap to retry."

    /** Failed a11y hint (verbatim iOS; [RetryActionLabel] is the action label). */
    const val FailedHint = "Tap to retry."

    /** TalkBack's action label for a failed tile's tap. */
    const val RetryActionLabel = "Retry"
}

/** The tile's state, for tests and the row's a11y tree (ticket 129). */
val ImageTileStatusKey = SemanticsPropertyKey<ImageTileStatus>("ImageTileStatus")

/** The tile's per-state a11y hint (verbatim iOS wording), for tests. */
val ImageTileHintKey = SemanticsPropertyKey<String>("ImageTileHint")

/**
 * Compact tiles (ticket 137's adaptation): shorter side ≤ 120 dp — the
 * same predicate as the 14/12 radius rule. Compact tiles show the 20 dp
 * spinner/glyph only; their a11y values keep the full texts.
 */
fun imageTileCompact(wDp: Float, hDp: Float): Boolean = minOf(wDp, hDp) <= 120f

/**
 * The full-mode status placement (px): `labelY = (tileH − labelH) / 2`,
 * `spinnerY = labelY − gapPx − spinnerH` — the label vertically centred
 * in the tile and the spinner's bottom `gap` px above its top (iOS's
 * constraints `status.centerY = centerY` and
 * `spinner.bottom = status.top − 8`). Integer division rounds toward 0,
 * so it is ≤ 0.5 px off UIKit's fractional centring. Returns
 * `(labelY, spinnerY)`.
 */
internal fun imageStatusPlacement(tileH: Int, labelH: Int, spinnerH: Int, gapPx: Int): Pair<Int, Int> {
    val labelY = (tileH - labelH) / 2
    val spinnerY = labelY - gapPx - spinnerH
    return labelY to spinnerY
}

/**
 * The Loading/Failed status overlay of an image tile (ticket 137; iOS
 * `TranscriptImageView`'s label + spinner). **Full mode** (shorter side
 * > 120 dp): a `BasicText` in Geist Regular 13 fixed, natural line
 * height, `Palette.secondary`, centred, unlimited lines, 12 dp side
 * insets, vertically centred in the tile; ticket 08's `ActivitySpinner`
 * (the iOS `.medium` `UIActivityIndicatorView`, 20 dp) sits horizontally
 * centred with its bottom 8 dp above the label's top. **Compact mode**:
 * the 20 dp spinner (Loading) or warning glyph (Failed) centred, no
 * label — a 76 dp thumbnail cannot hold the three-line text. Draws
 * nothing when Loaded.
 *
 * The root clears its semantics (iOS `isAccessibilityElement = false`):
 * the label and spinner must not leak as separate TalkBack nodes — the
 * tile's own node carries the label, value, hint and actions.
 */
@Composable
internal fun ImageTileStatusOverlay(status: ImageTileStatus, compact: Boolean, modifier: Modifier = Modifier) {
    if (status == ImageTileStatus.Loaded) return // nothing overlays the picture
    val palette = RobocoTheme.palette
    if (compact) {
        Box(
            modifier.clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                ImageTileStatus.Loading -> ActivitySpinner(palette.secondary, Modifier.size(20.dp))
                ImageTileStatus.Failed -> IconInBox(RobocoIcons.ExclamationmarkTriangle, palette.secondary, 20.dp)
                ImageTileStatus.Loaded -> Unit
            }
        }
        return
    }
    val typeScale = LocalTypeScale.current
    val text = when (status) {
        ImageTileStatus.Loading -> ImageTileCopy.Loading
        ImageTileStatus.Failed -> ImageTileCopy.Failed
        ImageTileStatus.Loaded -> return
    }
    Layout(
        content = {
            // Fixed child order: the spinner (Loading only) then the label.
            if (status == ImageTileStatus.Loading) {
                ActivitySpinner(palette.secondary, Modifier.size(20.dp))
            }
            BasicText(
                text = text,
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary)
                    .copy(textAlign = TextAlign.Center),
                softWrap = true,
                overflow = TextOverflow.Clip,
            )
        },
        modifier = modifier.clearAndSetSemantics {},
        measurePolicy = MeasurePolicy { measurables, constraints ->
            val sideInset = 12.dp.roundToPx()
            val spinnerSide = 20.dp.roundToPx()
            val gap = 8.dp.roundToPx()
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val labelW = (w - 2 * sideInset).coerceAtLeast(0)
            // The spinner is only composed while Loading, so the first
            // measurable (if there are two) is always it; the label is last.
            val spinnerPlaceable = if (measurables.size == 2) {
                measurables.first().measure(Constraints.fixed(spinnerSide, spinnerSide))
            } else {
                null
            }
            val labelPlaceable = measurables.last().measure(Constraints(minWidth = labelW, maxWidth = labelW))
            val sw = spinnerPlaceable?.width ?: 0
            val (labelY, spinnerY) = imageStatusPlacement(h, labelPlaceable.height, sw, gap)
            layout(w, h) {
                spinnerPlaceable?.placeRelative((w - sw) / 2, spinnerY)
                labelPlaceable.placeRelative(sideInset, labelY)
            }
        },
    )
}
