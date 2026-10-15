package sh.roboco.android.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One physical pixel in dp, like iOS hairlines
 * (`1 / max(1, traitCollection.displayScale)`: ComposerBar.swift:486,
 * SplitRootController.swift:279, RowView.swift:25). Sub-1 densities clamp to
 * 1 dp, so the hairline never disappears.
 */
fun Density.hairlineDp(): Dp = (1f / maxOf(1f, density)).dp

/** [hairlineDp] for composables. */
@Composable
@ReadOnlyComposable
fun hairlineWidth(): Dp = LocalDensity.current.hairlineDp()

/** One physical pixel, for Canvas painters. */
val Density.hairlinePx: Float
    get() = 1f

/**
 * A 1 px border. Compose draws `Dp.Hairline` as exactly one pixel, while
 * `border(width = hairlineWidth(), …)` gets ceiled to 2 px when the dp value
 * rounds badly — always use [hairlineBorder] instead.
 */
fun Modifier.hairlineBorder(color: Color, shape: Shape): Modifier =
    border(BorderStroke(Dp.Hairline, color), shape)
