package sh.roboco.android.transcript.widgets

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.ceil
import sh.roboco.android.design.Motion
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.image.imageViewerSource
import sh.roboco.android.transcript.TranscriptActions

/**
 * The tile's corner radius (ticket 66; iOS `RowView.swift:218`): 14 dp when
 * the shorter side is > 120 dp, else 12 dp — flat, **not** scaled by the
 * text scale (the Rust placeholder box's radius is, and that mismatch is
 * kept from iOS).
 */
fun imageTileRadius(wDp: Float, hDp: Float): Dp = if (minOf(wDp, hDp) > 120f) 14.dp else 12.dp

/** The reference the tile was asked to show (iOS uses it as the view's accessibility identifier). */
val ImageTileReferenceKey = SemanticsPropertyKey<String>("ImageTileReference")

/** Whether the picture is shown (tests and ticket 137's states). */
val ImageTileLoadedKey = SemanticsPropertyKey<Boolean>("ImageTileLoaded")

/** The picture's current alpha — the 200 ms cross-fade, for tests only. */
val ImageTileAlphaKey = SemanticsPropertyKey<Float>("ImageTileAlpha")

/**
 * The tile's mutable state, remembered **without keys** so the composable
 * keeps its identity when the reference changes (iOS's "claim the view
 * first": a slow load for a row it used to show must not land on top of
 * the new one). [claimed] is a plain field — nothing draws from it, and
 * writing a snapshot state read earlier in the same composition would
 * force a backward-write recomposition.
 */
@Stable
private class ImageTileState {
    var claimed: String? = null
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var shownAlpha by mutableFloatStateOf(0f)
}

/**
 * The picture tile of a `WidgetKind.Image` widget (ticket 66; iOS
 * `RowView.swift:214-234`): a transparent [Box] clipped to iOS continuous
 * corners that fills its incoming constraints (ticket 65 sizes it to the
 * widget rect) — the Rust-painted `ChipBackground` box under it is the
 * placeholder. Empty = fully transparent and **not** clickable; a cache
 * hit is visible in the first frame with no animation; a late load
 * cross-fades in over 200 ms easeInOut (not gated by reduce motion — iOS
 * does not gate it); a tap on a loaded tile calls
 * [TranscriptActions.openImage] with the unclipped window rect
 * (`positionInWindow` + size — `boundsInWindow` would clip to ancestors).
 *
 * [overlay] is ticket 67's upload ring slot, drawn inside the clip. No
 * haptic; the transcript's own dismiss-tap observer (81) still sees the
 * tap because `UIImageView` is not a `UIControl` on iOS either.
 *
 * The root modifier also carries `.imageViewerSource(reference)`
 * (ticket 125): the same node whose `positionInWindow()` the tap above
 * reports, so the viewer can zoom out of this tile and hide it while
 * it shows. No other tile change.
 */
@Composable
fun ImageTile(
    reference: String,
    widthDp: Float,
    heightDp: Float,
    actions: TranscriptActions,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val sidePx = ceil(minOf(widthDp, heightDp) * density.density).toInt().coerceAtLeast(1)

    // The freshest actions without re-launching the effect: a screen may
    // rebuild its callbacks under the same reference.
    val currentActions by rememberUpdatedState(actions)

    val st = remember { ImageTileState() }
    if (st.claimed != reference) {
        // Claim during composition = iOS's synchronous `view.image = hit`:
        // a cached picture never blinks (an effect would cost one empty
        // frame on every row recompose).
        st.claimed = reference
        val hit = currentActions.cachedImage(reference, sidePx)
        st.bitmap = hit
        st.shownAlpha = if (hit != null) 1f else 0f
    }

    LaunchedEffect(reference, sidePx) {
        if (st.bitmap != null && st.claimed == reference) return@LaunchedEffect // cache hit already shown
        val img = currentActions.loadImage(reference, sidePx) ?: return@LaunchedEffect // failure: stay empty
        if (st.claimed != reference) return@LaunchedEffect // the claim check (iOS :279)
        st.bitmap = img
        st.shownAlpha = 0f
        animate(0f, 1f, animationSpec = Motion.imageTileCrossfade<Float>()) { v, _ ->
            st.shownAlpha = v
        }
    }

    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val shape = rememberSmoothRoundedShape(imageTileRadius(widthDp, heightDp))
    val bmp = st.bitmap
    Box(
        modifier
            .fillMaxSize()
            .onPlaced { coords = it }
            .clip(shape)
            .semantics {
                testTag = "transcript-image"
                role = Role.Image
                contentDescription = "Image"
                this[ImageTileReferenceKey] = reference
                this[ImageTileLoadedKey] = bmp != null
                this[ImageTileAlphaKey] = st.shownAlpha
            }
            .then(
                // Clickable only when loaded (an empty tile's tap falls
                // through to the transcript). No indication, no ripple.
                if (bmp != null) {
                    Modifier.clickable(interactionSource = null, indication = null) {
                        coords?.let { c ->
                            currentActions.openImage(
                                reference,
                                Rect(c.positionInWindow(), c.size.toSize()),
                                bmp,
                            )
                        }
                    }
                } else {
                    Modifier
                },
            )
            // 125: the zoom-source registration on the same node whose
            // rect the tap reports.
            .imageViewerSource(reference),
    ) {
        if (bmp != null) {
            Image(
                bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                alpha = st.shownAlpha,
            )
        }
        overlay()
    }
}
