package sh.roboco.android.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.FadingHorizontalScroll
import sh.roboco.android.design.Icon
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.rememberSmoothRoundedShape

/**
 * The staged-thumbnail strip's geometry (android-ui-parity #88), every
 * value a UIKit anchor from `ios:Composer/ComposerBar.swift` — all dp
 * (iOS pt 1:1). "Strip" coordinates have their origin at the strip's
 * top-start corner; ticket 55 places the strip at (12, 0) with width
 * `W - 24` inside the glass.
 */
object StagedThumbMetrics {

    /** `:513` — the scroll view's height: 12 top margin + 56 tile. */
    const val StripHeight = 68f

    /** `:514` — the tile row's top margin inside the strip. */
    const val TopMargin = 12f

    /** `:490-491` — one tile. */
    const val Tile = 56f

    /** `:141` — the stack's spacing. */
    const val Spacing = 8f

    /** One slot per tile: 56 + 8 = 64. Tile `i` starts at `Pitch * i`. */
    const val Pitch = Tile + Spacing

    /** `:484-485` — the tile's continuous corner radius. */
    const val CornerRadius = 14f

    /** `:508-509` — the remove button. */
    const val RemoveSize = 18f

    /** `:506-507` — the remove button's top/trailing inset inside the tile. */
    const val RemoveInset = 4f

    /** `:493` — the `xmark` SF point size, `.bold`. */
    const val RemoveGlyphPt = 8f

    /** `:494` — `black @ 0.55` fill. */
    const val RemoveFillAlpha = 0.55f
}

/** The pressed overlay's alpha (the `ink @ 0.12` convention, white ink). */
private const val RemovePressedAlpha = 0.12f

/**
 * The staged-photo strip at the top of the composer card
 * (android-ui-parity #88) — the port of `rebuildThumbs`
 * (`ios:Composer/ComposerBar.swift:478-517`): a horizontally scrolling row
 * of 56 dp square, 14 dp continuous-cornered tiles with a 1 px hairline
 * border and a dark circular x in each tile's top-trailing corner.
 *
 * - **Height:** 68 dp while it shows images, 0 otherwise. The height is
 *   reported **in measure** (never through a callback), so ticket 55's
 *   layout and the bottom stack (74) re-measure every frame of the
 *   collapse — the Android replacement for iOS's `onHeightChange`.
 * - **Removal:** tapping x un-stages that image synchronously
 *   ([onRemove]); the removed tile vanishes in the same frame, every
 *   surviving tile whose index decreased slides one
 *   [StagedThumbMetrics.Pitch] over [Motion.thumbRemove]'s 250 ms
 *   easeInOut, and the strip itself collapses 68 -> 0 over the same 250 ms
 *   when the removed tile was the last one. Every other way the strip
 *   empties (send, queue-edit stash) or fills (picker return, restore)
 *   **snaps** in the same frame, like iOS's un-animated `layoutIfNeeded`.
 * - **Scrolling:** [FadingHorizontalScroll] (ticket 10) with the default
 *   24 dp travel-ramped edge fades; the row starts flush at the strip's
 *   leading edge (iOS pins the stack to the content guide with no inset),
 *   the 12 dp side insets live in ticket 55's placement. No
 *   `AnimatedVisibility`, no `LazyRow` (the #609 traps), no auto-scroll.
 * - **Focus:** the tile itself is not clickable, so a tap on the photo
 *   falls through to ticket 56's glass tap-to-focus; the x button carries
 *   [controlModifier] (56's `Modifier.composerControl`), which excludes it
 *   from that detector, and consumes the tap itself.
 * - **Roboco addition:** [visible] (wired to
 *   `ComposerController.attachEnabled`, false while a queued message is
 *   edited) hides the strip — height 0, nothing composed, no semantics.
 *
 * The strip itself never removes anything: [onRemove] must remove the
 * image synchronously on the main thread (`ComposerController.removeImage`
 * does), or the collapse flag could leak into an unrelated emptying.
 *
 * @param images ticket 54's staged images, in staging order
 * @param onRemove ticket 54's `ComposerController::removeImage`
 * @param modifier applied to the strip's outer box; 55 passes the slot's
 *   positioning (the box fills the width it is given)
 * @param visible `controller.attachEnabled` — while false the strip is
 *   height 0 and composes nothing
 * @param controlModifier ticket 56's `Modifier.composerControl(morph)`,
 *   applied to every x so x never focuses the field; the strip compiles
 *   without it (78's wiring line passes it)
 * @param scrollState the row's scroll state; the caller may hoist it (93's
 *   canvas reuses the composer as-is, so the default suffices)
 */
@Composable
fun StagedThumbnailStrip(
    images: List<StagedImage>,
    onRemove: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    controlModifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val shown = visible && images.isNotEmpty()

    // dp units. The first composition starts at the target (no entrance
    // animation); after that only the x-caused collapse animates.
    val height = remember { Animatable(if (shown) StagedThumbMetrics.StripHeight else 0f) }
    val motion = remember { StripMotion() }
    val currentImages by rememberUpdatedState(images)
    val currentOnRemove by rememberUpdatedState(onRemove)

    LaunchedEffect(shown) {
        val target = if (shown) StagedThumbMetrics.StripHeight else 0f
        if (!shown && motion.collapseByTap) {
            // x on the last tile: the 250 ms collapse (iOS animates the
            // same layout pass inside its 0.25 s block).
            height.animateTo(0f, Motion.thumbRemove())
        } else {
            // Snap: additions, restores, send, stash, visible = false —
            // and a re-fill during a collapse (animateTo is cancelled).
            height.snapTo(target)
        }
        motion.collapseByTap = false
    }

    /**
     * The x tap: flag the last-image case before the state change, then
     * remove synchronously. Reads the live list/handler so a tap during a
     * stale recomposition still removes the right image.
     */
    fun removeTapped(id: String) {
        val list = currentImages
        if (list.size == 1 && list[0].id == id) motion.collapseByTap = true
        currentOnRemove(id)
    }

    Box(
        modifier
            .fillMaxWidth()
            // Both BEFORE .layout: they must see the reported (animated)
            // height, not the inner 68/0 placeable. A tag after .layout
            // would report 0 during a collapse; a clip after it would not
            // clip to the collapsing height.
            .testTag("composer-thumbs")
            .clipToBounds()
            .layout { measurable, constraints ->
                val hDp = when {
                    // Additions/restores (and the re-fill interrupting a
                    // collapse): 68 in the same frame.
                    shown -> StagedThumbMetrics.StripHeight
                    // x on the last tile: the 250 ms collapse. Reading the
                    // Animatable here (measure, not composition) means a
                    // running collapse re-measures only — and the height
                    // the composer sees follows every frame.
                    motion.collapseByTap -> height.value
                    // Send, stash, visible = false: 0 in the same frame.
                    else -> 0f
                }
                val h = hDp.dp.roundToPx().coerceAtLeast(0)
                val placeable = measurable.measure(
                    constraints.copy(
                        minHeight = 0,
                        maxHeight = StagedThumbMetrics.StripHeight.dp.roundToPx(),
                    ),
                )
                layout(constraints.maxWidth, h) { placeable.place(0, 0) }
            },
    ) {
        if (shown) {
            FadingHorizontalScroll(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StagedThumbMetrics.StripHeight.dp),
                state = scrollState,
                horizontalArrangement = Arrangement.spacedBy(StagedThumbMetrics.Spacing.dp),
                // 68 - 56 = the 12 dp top margin (the bottom margin is 0).
                verticalAlignment = Alignment.Bottom,
            ) {
                // Keyed by id, never index: the surviving tiles keep their
                // nodes and slide; the removed one just disappears.
                images.forEachIndexed { i, img ->
                    key(img.id) {
                        StagedThumbnail(
                            image = img,
                            index = i,
                            onRemove = { removeTapped(img.id) },
                            controlModifier = controlModifier,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The strip's plain (non-snapshot) one-shot flags: written by the x
 * handler, consumed by the height [LaunchedEffect] and read by the outer
 * box's measure. Not snapshot state on purpose — a write must not
 * invalidate anything by itself; the following `images` change does.
 */
private class StripMotion {
    /** True only between "x removed the last image" and the collapse's end. */
    var collapseByTap = false
}

/**
 * One tile: the thumbnail aspect-fill-cropped into a 56 dp square with
 * continuous 14 dp corners, a 1 px [hairlineBorder] over the image, and
 * the x button at the top-trailing corner. The tile is **not** clickable —
 * a tap on the photo falls through to the glass's tap-to-focus.
 */
@Composable
private fun StagedThumbnail(
    image: StagedImage,
    index: Int,
    onRemove: () -> Unit,
    controlModifier: Modifier,
) {
    // The tile's visual slot (a float index). Only a removal lowers a
    // surviving tile's index — appends never move existing tiles — so
    // "index decreased" is exactly "an earlier tile was removed by x".
    val slot = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) {
        if (index < slot.targetValue) {
            // The slide: 250 ms easeInOut from the old slot. Interruptions
            // (a second x) keep the value and velocity, like UIKit.
            slot.animateTo(index.toFloat(), Motion.thumbRemove())
        } else {
            // First composition, or an index that did not decrease
            // (a re-fill during a slide): snap.
            slot.snapTo(index.toFloat())
        }
    }
    val pitchPx = with(LocalDensity.current) { StagedThumbMetrics.Pitch.dp.toPx() }
    val shape = rememberSmoothRoundedShape(StagedThumbMetrics.CornerRadius.dp)
    // Read per composition so a light/dark switch recolours the border in
    // the next frame (iOS keeps a stale border until the next rebuild).
    val border = RobocoTheme.palette.hairline
    Box(
        Modifier
            // Placement-phase read of the slide: a running slide moves the
            // tile without recomposing the row.
            .offset { IntOffset(((slot.value - index) * pitchPx).roundToInt(), 0) }
            .size(StagedThumbMetrics.Tile.dp)
            // Clip before the border so the hairline draws over the image
            // and inside the tile's outline (a CALayer border).
            .clip(shape)
            .hairlineBorder(border, shape)
            .testTag("composer-thumb"),
    ) {
        Image(
            bitmap = image.thumb,
            contentDescription = null, // UIImageView is not an a11y element
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier.matchParentSize(),
        )
        RemoveImageButton(
            onClick = onRemove,
            controlModifier = controlModifier,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = StagedThumbMetrics.RemoveInset.dp,
                    end = StagedThumbMetrics.RemoveInset.dp,
                ),
        )
    }
}

/**
 * The x button: an 18 dp dark translucent circle (`black @ 0.55`) with a
 * bold 8 pt white `xmark`, `contentDescription = "Remove image"`, no
 * ripple, no focus changes. [controlModifier] (56's marker) sits outside
 * the chrome but inside the positioning, so its bounds are exactly the
 * 18 dp circle the glass's tap-to-focus skips.
 */
@Composable
private fun RemoveImageButton(
    onClick: () -> Unit,
    controlModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .then(controlModifier)
            .size(StagedThumbMetrics.RemoveSize.dp)
            .clip(CapsuleShape)
            .background(Color.Black.copy(alpha = StagedThumbMetrics.RemoveFillAlpha))
            .drawWithContent {
                drawContent()
                // The pressed highlight (UIKit's internal .filled look):
                // white ink over everything, clipped to the circle.
                if (pressed) drawRect(Color.White.copy(alpha = RemovePressedAlpha))
            }
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = "Remove image" }
            .testTag("composer-thumb-remove"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            RobocoIcons.Xmark,
            tint = Color.White,
            sizePt = StagedThumbMetrics.RemoveGlyphPt,
            weight = SymbolWeight.Bold,
        )
    }
}
