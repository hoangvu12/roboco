package sh.roboco.android.design.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.SymbolWeight

/**
 * One slot of a [GlassButtonGroup] (ticket 107): a 44 dp icon slot inside
 * the shared capsule. Icons are ticket 06 registry glyphs; the label is the
 * item's [contentDescription] (iOS `accessibilityLabel`); the optional
 * [testTag] is the iOS `accessibilityIdentifier` (plus Android-only ids).
 *
 * [onClick] receives the slot's **window-space bounds** — a menu caller
 * (the sidebar toolbar's ⋯) hands them straight to
 * [GlassMenuState.open]; `boundsInWindow()` is captured on every placement
 * so the anchor follows relayouts. `Rect.Zero` until the first placement.
 *
 * [interactionSource] lets a caller observe or drive the press externally
 * (the gallery's "hold to press" toggle, the Roborazzi pressed golden); a
 * fresh source is remembered per slot otherwise.
 */
@Immutable
data class GlassGroupItem(
    val icon: RobocoIcon,
    val contentDescription: String,
    val testTag: String? = null,
    val interactionSource: MutableInteractionSource? = null,
    val onClick: (anchorInWindow: Rect) -> Unit,
)

/**
 * iOS 26 grouped bar items as one Compose row (ticket 107): adjacent
 * `UIBarButtonItem`s with no space between them render as **one shared
 * glass capsule**, one [slotSize] slot per item — the tablet sidebar's
 * `[gear ⋯]` group (`ios:Shell/SplitRootController.swift:239-252`).
 *
 * - The capsule is ticket 07's [GlassSurface] with `interactive = false`:
 *   the press lives on **each slot**, not the surface, so the capsule
 *   stays still while a slot swells (and so the two never compound —
 *   07/08's "double press" rule).
 * - Each slot: [Modifier.glassPress] (07's tokens, the highlight from
 *   [Glass.colors] — white @0.12 light / @0.08 dark) clipped to the
 *   [CircleShape], a `clickable` with `indication = null`, the label in
 *   semantics, and the icon in 08's measured bar ink
 *   ([GlassButtonDefaults.barGlyphInk], decision SESSIONS-D1) at
 *   [iconPt] points, Medium. No ripple, no haptics.
 * - `enabled = false` blocks taps **without** changing the glyph ink —
 *   the group is only disabled to swallow taps mid-fade (the toolbar's
 *   `visibility < 1`), where UIKit keeps the items at full ink.
 * - Width wraps the slots (N × [slotSize]); the caller sizes the height
 *   through [modifier] (the toolbar's 44 dp row).
 */
@Composable
fun GlassButtonGroup(
    items: List<GlassGroupItem>,
    modifier: Modifier = Modifier,
    slotSize: Dp = 44.dp,
    iconPt: Float = 17f,
    enabled: Boolean = true,
) {
    // The slot press highlight (07's light/dark pair), read once here so
    // a mid-fade re-read of the appearance cannot skip a slot.
    val pressHighlight = Glass.colors(LocalIsDark.current).pressHighlight
    GlassSurface(modifier.height(slotSize), shape = CapsuleShape, interactive = false) {
        Row {
            items.forEach { item ->
                val source = item.interactionSource ?: remember { MutableInteractionSource() }
                var bounds by remember { mutableStateOf(Rect.Zero) }
                var slotModifier = Modifier
                    .size(slotSize)
                    .onGloballyPositioned { bounds = it.boundsInWindow() }
                    .glassPress(
                        interactionSource = source,
                        shape = CircleShape,
                        highlight = pressHighlight,
                        enabled = enabled,
                    )
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                    ) { item.onClick(bounds) }
                    .semantics { contentDescription = item.contentDescription }
                item.testTag?.let { tag -> slotModifier = slotModifier.testTag(tag) }
                Box(slotModifier, contentAlignment = Alignment.Center) {
                    // Bar ink even when disabled (the ink never dims for a
                    // tap-blocked group); the label lives on the slot's
                    // semantics, so the glyph itself carries none.
                    Icon(item.icon, GlassButtonDefaults.barGlyphInk(), iconPt, SymbolWeight.Medium)
                }
            }
        }
    }
}
