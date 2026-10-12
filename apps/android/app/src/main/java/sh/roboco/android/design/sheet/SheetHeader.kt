package sh.roboco.android.design.sheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.uiStyle

/**
 * What sits in the leading (start) slot of a [SheetHeaderRow]
 * (android-ui-parity #17). `None` draws nothing.
 */
sealed interface SheetLeading {
    data object None : SheetLeading

    /** A ✕ glass circle, `UIBarButtonItem(systemItem: .close)`. */
    data object Close : SheetLeading
}

/**
 * What sits in the trailing (end) slot of a [SheetHeaderRow]. `None`
 * draws nothing.
 */
sealed interface SheetTrailing {
    data object None : SheetTrailing

    /**
     * The iOS 26 `.done` bar item: a checkmark in an accent-filled glass
     * circle, not the word "Done" (the ticket's recorded decision). The
     * plain `UINavigationController`s the sheets use inherit
     * `window.tintColor = Palette.accent` (`SceneDelegate.swift:15`), so
     * the prominent fill is the accent.
     */
    data object Done : SheetTrailing
}

/**
 * The sheet nav bar (android-ui-parity #17): a 52 dp row with a centred
 * title, an optional leading ✕ and an optional trailing Done — the
 * `UINavigationController` bar every iOS sheet hosts, drawn as an opaque
 * row above the content.
 *
 * Scope-free on purpose: tickets 31 (`PageSheet`) and 101 (licence sheet)
 * reuse it without a `GlassSheet` — it draws **no background of its own**
 * (the sheet fill shows through), so owners that want a transparent bar
 * over a wallpaper (93's New Session) reuse it as is.
 *
 * - Title: Geist SemiBold 17 `fixed` (UIKit nav titles do not scale with
 *   Dynamic Type at this size), `Palette.text`, centred on the whole row
 *   width — not the gap between the buttons — with `maxLines = 1`, tail
 *   ellipsis, and `heading()` semantics. It starts ellipsizing 8 dp before
 *   the buttons (max width `width − 2 × (16 + 44 + 8)`).
 * - ✕ (leading, [SheetLeading.Close]): ticket 08's `GlassCircleButton`,
 *   44 dp glass circle, `Xmark` 17 pt Medium, 08's default bar-item ink,
 *   start inset 16, testTag `sheet-close`, content description "Close".
 * - ✓ (trailing, [SheetTrailing.Done]): ticket 08's `GlassCircleButton`,
 *   44 dp accent-filled circle with a white `Checkmark` 17 pt Medium, end
 *   inset 16, testTag `sheet-done`, content description "Done".
 */
@Composable
fun SheetHeaderRow(
    title: String,
    leading: SheetLeading = SheetLeading.None,
    trailing: SheetTrailing = SheetTrailing.None,
    onLeading: () -> Unit = {},
    onTrailing: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BoxWithConstraints(modifier.fillMaxWidth().height(GlassSheetDefaults.HeaderHeight)) {
        BasicText(
            title,
            Modifier
                .align(Alignment.Center)
                // width − 2 × (16 + 44 + 8): ellipsize 8 dp before the buttons.
                .widthIn(max = maxWidth - GlassSheetDefaults.TitleClearance)
                .semantics { heading() },
            style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (leading) {
            SheetLeading.None -> {}
            SheetLeading.Close -> GlassCircleButton(
                icon = RobocoIcons.Xmark,
                contentDescription = "Close",
                onClick = onLeading,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .testTag("sheet-close"),
            )
        }
        when (trailing) {
            SheetTrailing.None -> {}
            SheetTrailing.Done -> GlassCircleButton(
                icon = RobocoIcons.Checkmark,
                contentDescription = "Done",
                onClick = onTrailing,
                filled = palette.accent,
                ink = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .testTag("sheet-done"),
            )
        }
    }
}

/**
 * [GlassSheet]'s convenience wrapper over [SheetHeaderRow]: the same
 * 52 dp nav bar, with `dismiss()` as the default action of both bar items
 * — every iOS call site's handler is `self?.dismiss(animated: true)`
 * (`TranscriptListView.swift:686`, `NewSessionViewController.swift:114-118`).
 * Pass [onLeading]/[onTrailing] to do something else first.
 */
@Composable
fun GlassSheetScope.SheetHeader(
    title: String,
    leading: SheetLeading = SheetLeading.None,
    trailing: SheetTrailing = SheetTrailing.None,
    onLeading: () -> Unit = { dismiss() },
    onTrailing: () -> Unit = { dismiss() },
    modifier: Modifier = Modifier,
) {
    SheetHeaderRow(title, leading, trailing, onLeading, onTrailing, modifier)
}
