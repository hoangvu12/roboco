package sh.roboco.android.design.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import kotlin.math.min
import sh.roboco.android.design.DEFAULT_SMOOTHING
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.polygonPath
import sh.roboco.android.design.rgba

/**
 * iOS continuous ("squircle") **top** corners with square bottom corners —
 * the page-sheet silhouette (android-ui-parity #17): `SmoothRoundedShape`
 * rounds all four, so the sheet's own shape rounds only the top pair and
 * leaves the bottom edge square on the window's bottom edge.
 *
 * The polygon recipe is the one ticket 31's `PageSheet` expects: a
 * graphics-shapes rectangle whose `perVertexRounding` leaves the two bottom
 * vertices (`bottom-right, bottom-left` first in the polygon's vertex
 * order) unrounded and rounds the two top vertices (`top-left`,
 * `top-right`) with [smoothing] (0.6, the iOS continuous-corner feel).
 */
@Immutable
class SmoothTopRoundedShape(val radius: Dp, val smoothing: Float = DEFAULT_SMOOTHING) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Rectangle(size.toRect())
        // A bottom-corner radius would run off the parent, and the two top
        // corners share the short side: clamp to width/2 and the height.
        val r = min(with(density) { radius.toPx() }, min(size.width / 2f, size.height))
        if (r <= 0f) return Outline.Rectangle(size.toRect())
        return Outline.Generic(
            polygonPath(
                RoundedPolygon.rectangle(
                    width = size.width,
                    height = size.height,
                    // Vertex order: bottom-right, bottom-left, top-left, top-right.
                    perVertexRounding = listOf(
                        CornerRounding.Unrounded,
                        CornerRounding.Unrounded,
                        CornerRounding(r, smoothing),
                        CornerRounding(r, smoothing),
                    ),
                    centerX = size.width / 2f,
                    centerY = size.height / 2f,
                ),
            ),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is SmoothTopRoundedShape && other.radius == radius && other.smoothing == smoothing

    override fun hashCode(): Int = 31 * radius.hashCode() + smoothing.hashCode()
}

/**
 * The sheet's drag grabber (android-ui-parity #17): the 36 × 5 capsule
 * UIKit draws at 5 dp from the sheet's top, centred, `Palette.tertiary`
 * @0.5. State-free on purpose — ticket 31's `PageSheet` and ticket 101's
 * licence sheet reuse it without a `GlassSheet`.
 *
 * The composable itself is a 44 × 20 touch target (Android-only, for tap
 * and a11y); the caller aligns it `TopCenter` on the sheet, so the visible
 * bar lands at y = 5.
 *
 * - [onClick] != null: interactive (tap toggles the detents on a
 *   two-detent sheet), `Role.Button`, content description "Sheet Grabber"
 *   (UIKit's label) and the caller's [stateDescription]
 *   ("Half screen" / "Full screen").
 * - [onClick] == null: not interactive — `invisibleToUser()`, as a
 *   single-detent sheet's grabber is decorative.
 */
@Composable
fun SheetGrabber(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    stateDescription: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    // Palette.tertiary @0.5 (light #97979F, dark #6B6B72): UIKit's own
    // system grey stand-in, starred in the ticket. Alpha goes through
    // rgba, not Color.copy, per Palette.kt's quantisation note.
    val ink = rgba(if (RobocoTheme.isDark) 0x6B6B72 else 0x97979F, 0.5)
    Box(
        modifier
            .size(GlassSheetDefaults.GrabberTouchTarget)
            .let { m ->
                if (onClick != null) {
                    // No ripple (indication = null) and no haptics: UIKit
                    // fires none here (the ticket's Haptics table is empty).
                    m.clickable(
                        interactionSource = source,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    m
                }
            }
            .semantics {
                if (onClick == null) {
                    invisibleToUser()
                } else {
                    contentDescription = "Sheet Grabber"
                    if (stateDescription != null) this.stateDescription = stateDescription
                }
            },
    ) {
        // The bar: 36 × 5 capsule, top 5, horizontally centred. A 36 × 5
        // capsule is the 2.5 radius rounding.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = GlassSheetDefaults.GrabberTop)
                .size(GlassSheetDefaults.GrabberSize)
                .background(ink, CircleShape),
        )
    }
}
