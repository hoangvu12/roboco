package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.ceil
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.image.imageViewerSource
import sh.roboco.android.transcript.MaxAutoImageRetries
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.imageRetryDelayMs
import sh.roboco.android.transcript.isFetchableImageReference

/**
 * The tile's corner radius (ticket 66; iOS `RowView.swift:218`): 14 dp when
 * the shorter side is > 120 dp, else 12 dp — flat, **not** scaled by the
 * text scale (the Rust placeholder box's radius is, and that mismatch is
 * kept from iOS).
 */
fun imageTileRadius(wDp: Float, hDp: Float): Dp = if (minOf(wDp, hDp) > 120f) 14.dp else 12.dp

/** The reference the tile was asked to show (iOS uses it as the view's accessibility identifier). */
val ImageTileReferenceKey = SemanticsPropertyKey<String>("ImageTileReference")

/** Whether the picture is shown. */
val ImageTileLoadedKey = SemanticsPropertyKey<Boolean>("ImageTileLoaded")

/** `1f` when the picture is shown, `0f` otherwise — the cross-fade is gone (137), tests only. */
val ImageTileAlphaKey = SemanticsPropertyKey<Float>("ImageTileAlpha")

/**
 * The tile's mutable state, remembered **without keys** so the composable
 * keeps its identity when the reference changes (iOS's "claim the view
 * first": a slow load for a row it used to show must not land on top of
 * the new one). [claimed] is a plain field — nothing draws from it, and
 * writing a snapshot state read earlier in the same composition would
 * force a backward-write recomposition. [attempt] counts retries: bumping
 * it restarts the load effect (iOS's `activateImage` on a failed tile
 * re-asks the loader). [failures] counts consecutive failed loads; it sets
 * the automatic retry's backoff and resets on success or a new reference.
 */
@Stable
private class TileState {
    var claimed: String? = null
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var status by mutableStateOf(ImageTileStatus.Loading)
    var attempt by mutableIntStateOf(0)
    var failures by mutableIntStateOf(0)
}

/**
 * The picture tile of a `WidgetKind.Image` widget (tickets 66 → 137; iOS
 * `RowView.swift:214-234` + `TranscriptImageView.swift`): a [Box] clipped
 * to iOS continuous corners and filled with `Palette.elevated` (137 — it
 * covers the Rust `ChipBackground` box under it), which fills its
 * incoming constraints (ticket 65 sizes it to the widget rect).
 *
 * The state machine (137): **Loading** (spinner + "Loading image" in full
 * mode, spinner only in compact mode ≤ 120 dp) on a cache miss; **Loaded**
 * on a sync hit — visible in the first frame, no blink — or a successful
 * load, an **instant** swap (137 removed 66's 200 ms cross-fade), the
 * picture aspect-**fit** (137 changed it from fill) and centred; **Failed**
 * ("Image unavailable … Tap to retry." / warning glyph) once every image
 * source failed, or for a blank reference (set synchronously, no RPC).
 * Failures are never cached, and a Failed tile retries by itself: at once
 * when [TranscriptActions.imageRetries] fires (the engine reconnected),
 * otherwise after [imageRetryDelayMs]'s growing backoff (2 s … 1 min),
 * at most [MaxAutoImageRetries] times in a row (≈ 4 min); then only a
 * reconnect or a tap retries. The tile is clickable in **every**
 * state: Loaded opens the viewer through [TranscriptActions.openImage]
 * with the unclipped window rect (`positionInWindow` + size —
 * `boundsInWindow` would clip to ancestors) and the alt text as the
 * viewer's label; Loading/Failed re-request through
 * [TranscriptActions.loadImage] (de-duplicated by the loader's in-flight
 * map, so a tap during Loading joins the running read; a tap after Failed
 * starts a fresh one). No indication, no ripple, no haptic.
 *
 * [overlay] is ticket 67's upload ring slot, drawn inside the clip. The
 * root modifier also carries `.imageViewerSource(reference)` (ticket 125):
 * the same node whose `positionInWindow()` the tap above reports, so the
 * viewer can zoom out of this tile and hide it while it shows.
 */
@Composable
fun ImageTile(
    reference: String,
    label: String,
    widthDp: Float,
    heightDp: Float,
    actions: TranscriptActions,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    // Tiles are square, so min = max; 66's exact formula is unchanged.
    val sidePx = ceil(minOf(widthDp, heightDp) * density.density).toInt().coerceAtLeast(1)

    // The freshest actions without re-launching the effect: a screen may
    // rebuild its callbacks under the same reference.
    val currentActions by rememberUpdatedState(actions)

    val st = remember { TileState() }
    val fetchable = isFetchableImageReference(reference)
    if (st.claimed != reference) {
        // Claim during composition = iOS's synchronous `view.image = hit`:
        // a cached picture never blinks (an effect would cost one empty
        // frame on every row recompose). An unfetchable reference fails
        // in this same first frame, with no RPC at all.
        st.claimed = reference
        st.failures = 0
        val hit = if (fetchable) currentActions.cachedImage(reference, sidePx) else null
        st.bitmap = hit
        st.status = when {
            hit != null -> ImageTileStatus.Loaded
            !fetchable -> ImageTileStatus.Failed
            else -> ImageTileStatus.Loading
        }
    }

    LaunchedEffect(reference, sidePx, st.attempt) {
        if (st.status == ImageTileStatus.Loaded && st.bitmap != null) return@LaunchedEffect // cache hit already shown
        if (!fetchable) {
            st.status = ImageTileStatus.Failed
            return@LaunchedEffect
        }
        st.status = ImageTileStatus.Loading
        // 66's loader joins an in-flight Deferred for the same key+size, so
        // a retry during Loading costs no second read; a retry after Failed
        // reads afresh (failures are never cached).
        val img = currentActions.loadImage(reference, sidePx)
        if (st.claimed != reference) return@LaunchedEffect // the claim check (iOS :279)
        st.bitmap = img
        st.status = if (img != null) ImageTileStatus.Loaded else ImageTileStatus.Failed
        st.failures = if (img != null) 0 else st.failures + 1
    }

    // The automatic retry: a Failed tile waits for the engine to come back
    // or for its backoff, whichever is first, then re-asks the loader. A
    // new failure re-keys this with a longer wait. Past
    // [MaxAutoImageRetries] the backoff stops and only a reconnect (or a
    // tap) tries again; a reconnect restarts the count.
    if (st.status == ImageTileStatus.Failed && fetchable && st.failures > 0) {
        LaunchedEffect(reference, st.failures) {
            val reconnected = if (st.failures <= MaxAutoImageRetries) {
                withTimeoutOrNull(imageRetryDelayMs(st.failures)) {
                    // A finite (or empty) signal must not end the wait early.
                    if (currentActions.imageRetries.firstOrNull() == null) awaitCancellation()
                    true
                } ?: false
            } else {
                if (currentActions.imageRetries.firstOrNull() == null) awaitCancellation()
                true
            }
            if (st.claimed != reference) return@LaunchedEffect
            if (reconnected) st.failures = 0
            st.attempt++
        }
    }

    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val palette = RobocoTheme.palette
    val compact = imageTileCompact(widthDp, heightDp)
    val bmp = st.bitmap
    val status = st.status
    val loaded = status == ImageTileStatus.Loaded && bmp != null
    Box(
        modifier
            .fillMaxSize()
            .onPlaced { coords = it }
            // 125: the zoom-source registration on the same node whose
            // rect the tap below reports.
            .imageViewerSource(reference)
            .clip(rememberSmoothRoundedShape(imageTileRadius(widthDp, heightDp)))
            .background(palette.elevated)
            .testTag("transcript-image")
            .semantics {
                role = Role.Image
                contentDescription = label
                stateDescription = when (status) {
                    ImageTileStatus.Loading -> ImageTileCopy.Loading
                    ImageTileStatus.Loaded -> ImageTileCopy.LoadedValue
                    ImageTileStatus.Failed -> ImageTileCopy.Failed
                }
                this[ImageTileReferenceKey] = reference
                this[ImageTileLoadedKey] = status == ImageTileStatus.Loaded
                this[ImageTileStatusKey] = status
                this[ImageTileAlphaKey] = if (status == ImageTileStatus.Loaded) 1f else 0f
                when (status) {
                    ImageTileStatus.Loaded -> this[ImageTileHintKey] = ImageTileCopy.LoadedHint
                    ImageTileStatus.Failed -> this[ImageTileHintKey] = ImageTileCopy.FailedHint
                    ImageTileStatus.Loading -> Unit
                }
            }
            // Clickable in every state (iOS `accessibilityActivate` /
            // `openImage(_:)`): no image → re-ask the loader (a retry), an
            // image → the viewer. No indication: iOS's UIImageView has no
            // pressed look.
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = when (status) {
                    ImageTileStatus.Loaded -> ImageTileCopy.LoadedHint
                    ImageTileStatus.Failed -> ImageTileCopy.RetryActionLabel
                    ImageTileStatus.Loading -> null
                },
            ) {
                if (loaded) {
                    coords?.let { c ->
                        currentActions.openImage(
                            reference,
                            Rect(c.positionInWindow(), c.size.toSize()),
                            bmp,
                            label,
                        )
                    }
                } else if (fetchable) {
                    st.failures = 0 // a fresh automatic-retry budget
                    st.attempt++ // retry: restarts the effect above
                }
            },
    ) {
        if (loaded) {
            Image(
                bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
            )
        } else {
            ImageTileStatusOverlay(status, compact, Modifier.fillMaxSize())
        }
        overlay() // 67's ring stays on top, clipped
    }
}
