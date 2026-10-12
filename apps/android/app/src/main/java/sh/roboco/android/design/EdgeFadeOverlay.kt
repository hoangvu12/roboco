package sh.roboco.android.design

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Content fading into the background at a screen edge (the session bottom
 * stack, ticket 74), the port of iOS `EdgeFadeOverlay`
 * (`ios:Design/EdgeFade.swift:88-119`): a vertical `Palette.background`
 * gradient — clear at the top of the view, eased so there is no visible
 * band, solid from 55 % down.
 *
 * It is a *painted* overlay, not a mask on the scroll view, so scrolling
 * never renders offscreen (`ios:Session/SessionViewController.swift:133-135`),
 * and it composites for free while content scrolls underneath. Touches pass
 * through to whatever is underneath (`isUserInteractionEnabled = false`):
 * a node without pointer-input modifiers is not a hit target, so the
 * transcript underneath stays tappable. No semantics, no indication —
 * invisible to accessibility. Light/dark follows recomposition through the
 * palette (no `didMoveToWindow` restyle needed).
 */
@Composable
fun EdgeFadeOverlay(modifier: Modifier = Modifier) {
    val bg = RobocoTheme.palette.background
    Spacer(
        modifier.drawWithCache {
            // Every stop is the same colour with the stop's alpha
            // (EdgeFade.swift:108-109); alpha is quantised to the 8-bit byte
            // it will be drawn with, like Palette's rgba() helper.
            val stops = EdgeFade.OverlayStops.map { (location, alpha) ->
                location to withAlpha8(bg, alpha)
            }
            val brush = Brush.verticalGradient(
                *stops.toTypedArray(),
                startY = 0f,
                endY = size.height,
            )
            onDrawBehind { drawRect(brush) }
        },
    )
}

/** [color] with alpha `a` stored as the 8-bit byte `round(a * 255)`. */
private fun withAlpha8(color: Color, alpha: Float): Color {
    val alpha8 = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    return Color((alpha8 shl 24) or (color.toArgb() and 0xFFFFFF))
}
