package sh.roboco.android.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.uiStyle

/**
 * The overlay's vertical anchor, in px: `(safeTop + (height − safeBottom))
 * / 2 − lift` — the safe-area centre lifted 40 dp, where iOS centres its
 * loader against `view.safeAreaLayoutGuide.centerYAnchor, constant: -40`
 * (`SessionViewController.swift:229-233`; iOS's safe area includes the
 * expanded large-title bar and the tab bar, so the caller's insets stand
 * in for it).
 *
 * Worked example (393 × 852, safe top 155, bottom 110, lift 40):
 * (155 + 742) / 2 − 40 = **408.5**.
 */
fun statusAnchorY(heightPx: Float, safeTopPx: Float, safeBottomPx: Float, liftPx: Float): Float =
    (safeTopPx + (heightPx - safeBottomPx)) / 2f - liftPx

/** The loader's lift: `safeAreaLayoutGuide.centerYAnchor, constant: -40`. */
private val AnchorLift = 40.dp

/** The empty copy's side margins: 24 dp each (48 total). */
private val EmptySideInset = 24.dp

/** The 4 dp gap between the two empty lines (Roboco addition). */
private val EmptyLineGap = 4.dp

/**
 * The merged empty / loading overlay (Roboco addition — iOS shows a blank
 * list), painted for every status but [SessionListStatus.Content] and
 * [SessionListStatus.Blank]:
 *
 * - **Loading** — the ×1.75 loader spinner (ticket 11 sizes the box
 *   12 × 1.75 = 21 × 21 dp; this passes **no** size modifier), tagged
 *   `sessions-loading`, announcing an indeterminate progress bar. Never
 *   clipped: the spinner may overhang its box.
 * - **EmptySessions** — two centred lines, "No sessions yet" (SansMedium
 *   16 scaled, `secondary`) then "Tap New session to start one." (Sans 13
 *   scaled, `tertiary`), 4 dp apart, tagged `sessions-empty`.
 * - **EmptyArchived** — one line, "No archived sessions", tagged
 *   `sessions-empty`.
 *
 * The block's centre sits on the [statusAnchorY] anchor (the horizontal
 * centre of the overlay bounds). Text is centred, wraps, and is capped at
 * the overlay width minus 48. State swaps are instant — rows arriving
 * after `Loading` fade in through the list's `animateItem`, not here.
 *
 * The overlay **never takes pointer input**: no `clickable`, no
 * `pointerInput` — a drag that starts on it reaches the `LazyColumn`
 * underneath (pull to refresh still works on an empty list).
 *
 * @param safePadding the caller's safe insets ([SessionList] passes its
 *   `contentPadding`; inside `LargeTitleScaffold` the screen passes
 *   `PaddingValues(top = topInset + 44.dp + 52.dp, bottom = bottomInset)`).
 */
@Composable
fun SessionListStatusOverlay(
    status: SessionListStatus,
    safePadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    when (status) {
        SessionListStatus.Content, SessionListStatus.Blank -> Unit

        SessionListStatus.Loading -> AnchorLayout(safePadding, modifier) {
            StatusGlyph(
                StatusGlyphKind.Spinner,
                modifier = Modifier
                    .testTag("sessions-loading")
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
                scale = 1.75f,
            )
        }

        SessionListStatus.EmptySessions -> AnchorLayout(safePadding, modifier) {
            Column(
                Modifier.testTag("sessions-empty"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(EmptyLineGap),
            ) {
                BasicText(
                    "No sessions yet",
                    style = uiStyle(Face.SansMedium, ts.scaled(16f), palette.secondary)
                        .copy(textAlign = TextAlign.Center),
                )
                BasicText(
                    "Tap New session to start one.",
                    style = uiStyle(Face.Sans, ts.scaled(13f), palette.tertiary)
                        .copy(textAlign = TextAlign.Center),
                )
            }
        }

        SessionListStatus.EmptyArchived -> AnchorLayout(safePadding, modifier) {
            BasicText(
                "No archived sessions",
                style = uiStyle(Face.SansMedium, ts.scaled(16f), palette.secondary)
                    .copy(textAlign = TextAlign.Center),
                modifier = Modifier.testTag("sessions-empty"),
            )
        }
    }
}

/**
 * Places its single child so the child's **centre** is the horizontal
 * centre of the bounds and the [statusAnchorY] anchor: the child is
 * measured with `maxWidth = width − 48` (the copy's side margins) and
 * otherwise unconstrained, so text wraps rather than truncates.
 */
@Composable
private fun AnchorLayout(
    safePadding: PaddingValues,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val textMaxWidth = (w - 2 * EmptySideInset.roundToPx()).coerceAtLeast(0)
        val child = measurables.singleOrNull()?.measure(Constraints(maxWidth = textMaxWidth))
        val safeTop = safePadding.calculateTopPadding().roundToPx()
        val safeBottom = safePadding.calculateBottomPadding().roundToPx()
        val anchorY = statusAnchorY(h.toFloat(), safeTop.toFloat(), safeBottom.toFloat(), AnchorLift.toPx())
        layout(w, h) {
            if (child != null) {
                val x = (w - child.width) / 2
                val y = (anchorY - child.height / 2f).roundToInt()
                child.place(x, y)
            }
        }
    }
}
