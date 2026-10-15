package sh.roboco.android.image

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.chrisbanes.haze.hazeSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.IosSpring
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.smoothRoundedPath
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.shell.RegisterRootPresentation

/**
 * The viewer's open/close transition spring (ticket Comments: UIKit's
 * `.zoom` timing is system-owned, so these are the chosen stand-ins,
 * defined locally per FOUNDATION-D1 — `IosSpring`, never a stiffness
 * literal): critically damped, settle-matched, 0.45 s.
 */
val ViewerZoom = IosSpring("imageViewerZoom", 0.45, 1.0f)

/** Drag-release and predictive-back-cancel spring back (0.38 s, ζ 0.86). */
val ViewerSnapBack = IosSpring("imageViewerSnapBack", 0.38, 0.86f)

/** Reduce-motion open/close: the 200 ms easeInOut cross-fade. */
internal const val ViewerFadeMs = 200

/**
 * The pan fling's decay: `UIScrollView.DecelerationRate.normal`
 * (0.998/ms → k = 2.002 s⁻¹); Compose's `exponentialDecay` friction is
 * `4.2 × multiplier`, so the multiplier is `2.002 / 4.2 = 0.4767`.
 */
internal const val ViewerPanFrictionMultiplier = 0.4767f

/** k = 4.2 · multiplier (s⁻¹), for the same-shape fling that stops at a bound. */
internal const val ViewerPanDecayK = 4.2f * ViewerPanFrictionMultiplier

/** The pan fling spec itself. */
internal val ViewerPanDecay = exponentialDecay<Float>(frictionMultiplier = ViewerPanFrictionMultiplier)

/** The close button's copy (verbatim from the accessibility table). */
internal const val CloseImagePreviewLabel = "Close image preview"

/** The share button's copy (verbatim). */
internal const val ShareImageLabel = "Share image"

/** The picture's fallback accessibility label (verbatim). */
internal const val ImageFallbackLabel = "Image"

/** The full-screen picture's test tag (pr817's `image-preview`). */
internal const val ImageViewerTag = "image-preview"

/** The close button's test tag. */
internal const val ImageViewerCloseTag = "image-preview-close"

/** The share button's test tag. */
internal const val ImageViewerShareTag = "image-preview-share"

/**
 * One open request (ticket 125's public API). Built by the session
 * screen's `openImage` (75), image-chip links (126) and markdown images
 * (137, which also fills [label] with the alt text).
 */
@Immutable
data class ImageViewerRequest(
    /** The widget reference — matches the `imageViewerSource` registrations. */
    val reference: String,
    /** Start frame (ticket 66's tile bitmap). Null = await [loadFull] before opening (ticket 126). */
    val preview: ImageBitmap?,
    /** Source rect in window px, unclipped. Null = no zoom source (open and close fade). */
    val sourceBounds: Rect?,
    /** Corner radius of the source at `t = 0` (tile: `imageTileRadius(w, h)`; a row source: 0.dp). */
    val sourceCornerRadius: Dp,
    /** Accessibility label (alt text, ticket 137). Null → "Image". */
    val label: String? = null,
    /** Full-resolution load (TranscriptImageBinding.loadFull). Null = preview is already full size. */
    val loadFull: (suspend () -> ImageBitmap?)? = null,
)

/** The viewer's lifecycle (`Hidden` = not composed at all). */
enum class ViewerPhase { Hidden, Opening, Shown, Dragging, Back, Closing }

/** Whether the transition morphs out of the source (Zoom) or cross-fades (Fade). */
internal enum class ViewerTransition { Zoom, Fade }

/** The pointer loop's gesture mode (private to the page). */
private enum class ViewerGesture { Undecided, Pinch, Pan, Dismiss, Ignore }

/**
 * A registered zoom source (one per `Modifier.imageViewerSource` node);
 * the same picture can appear twice, so the registry keeps a list.
 */
internal class ViewerSourceEntry(val reference: String) {
    /** The node's coordinates, written from `onPlaced` (main thread). */
    var coords: LayoutCoordinates? = null
}

/** The saved system-bar icon appearance, restored once on close/dispose. */
internal class ViewerBarAppearance(
    val controller: WindowInsetsControllerCompat,
    val status: Boolean,
    val navigation: Boolean,
)

/** The picture's zoom, for tests (semantics key `ImageViewerZoom`). */
val ImageViewerZoomKey = SemanticsPropertyKey<Float>("ImageViewerZoom")

/** The background alpha, for tests (semantics key `ImageViewerBackgroundAlpha`). */
val ImageViewerBackgroundAlphaKey = SemanticsPropertyKey<Float>("ImageViewerBackgroundAlpha")

/**
 * The full-screen image viewer's state (android-ui-parity #125): the
 * port of iOS `ImageViewer.swift` as a root-level overlay — zoom out of
 * the tapped tile, pinch 1×–5× with rubber-banded overshoot, double-tap
 * 2.5× around the tap, pan with the whole-view bounds (letterbox
 * included), drag-to-dismiss at zoom 1, predictive back, share, close.
 *
 * - `show()` is ignored while [isShowing] (iOS cannot present twice).
 * - `close()` reverses the open animation (or cross-fades when the
 *   source is gone, off screen, or Reduce Motion is on); while a
 *   `preview == null` load is still in flight it just cancels the load.
 * - `dismissInstantly()` is the root-swap teardown (ticket 35).
 * - No haptics anywhere (the iOS viewer fires none).
 *
 * All motion goes through [ViewerZoom] / [ViewerSnapBack] /
 * `tween(200, Motion.EaseInOut)`; the pan fling through the UIKit
 * decay. Values are px; [attach] (from [ImageViewerHost]) supplies the
 * scope, Reduce Motion and the density.
 */
@Stable
class ImageViewerState {

    /** The current lifecycle phase. */
    var phase: ViewerPhase by mutableStateOf(ViewerPhase.Hidden)
        private set

    /** `phase != Hidden` — also true while a null-preview load is awaited. */
    val isShowing: Boolean get() = phase != ViewerPhase.Hidden

    // ------------------------------------------------------------ request

    /** The request being shown (null while Hidden). */
    internal var request by mutableStateOf<ImageViewerRequest?>(null)

    /** `full ?: preview` — the bitmap the page draws. */
    internal var displayBitmap by mutableStateOf<ImageBitmap?>(null)

    /** The full-resolution swap (null until loadFull lands). */
    internal var full by mutableStateOf<ImageBitmap?>(null)

    /** The in-flight full-resolution load, cancelled by finish(). */
    private var fullDeferred: Deferred<ImageBitmap?>? = null

    /** The show/close flow job (awaiting a null-preview load, watching the full). */
    private var openJob: Job? = null

    /** The presence (t) animation job — open or close. */
    private var presenceJob: Job? = null

    /** The zoom/T settle job (pinch release, double tap, pan bounds). */
    private var settleJob: Job? = null

    /** The drag-offset spring-back job. */
    private var dragJob: Job? = null

    /** The predictive-back cancel snap-back job. */
    private var backJob: Job? = null

    /** The last gesture step's snap (pinch/pan/drag), chained in order. */
    private var stepJob: Job? = null

    /** The share job (one at a time; a second tap is ignored while it runs). */
    private var shareJob: Job? = null

    // ------------------------------------------------------------ geometry

    /** The overlay's own origin in window px (almost always (0, 0)). */
    internal var overlayOrigin by mutableStateOf(Offset.Zero)

    /** The overlay size `W x H` in px, from the page's first layout. */
    internal var viewSize by mutableStateOf(Size.Zero)

    /** The picture's aspect-fit rect `F` in overlay px. */
    internal var fitRect by mutableStateOf(Rect.Zero)

    // ------------------------------------------------------------ motion

    /** `t` of the open/close transition: 0 = at the source, 1 = presented. */
    internal val presence = Animatable(0f)

    /** The displayed zoom (rubber-banded during a pinch). */
    internal val zoom = Animatable(1f)

    /** The raw content translation (rubber-banded in draw during a pan). */
    internal val tx = Animatable(0f)

    /** The raw content translation, vertical. */
    internal val ty = Animatable(0f)

    /** The drag-to-dismiss offset (zero during pan/back). */
    internal val dragOffset = Animatable(Offset.Zero, Offset.VectorConverter)

    /** The predictive-back progress `q`. */
    internal val backProgress = Animatable(0f)

    /** True while a two-finger pinch owns the transform (T drawn raw). */
    internal var pinching by mutableStateOf(false)

    /** The raw pinch product (reset at every pinch start). */
    private var zRaw = 1f

    /** The last pinch centroid (the settle's anchor). */
    private var lastCentroid = Offset.Zero

    // ------------------------------------------------------------ transition

    /** The mode of the current open/close. */
    internal var transition: ViewerTransition by mutableStateOf(ViewerTransition.Fade)
        private set

    /** `E` — the rect the presence value runs between. */
    internal var endRect: Rect by mutableStateOf(Rect.Zero)
        private set

    /** `Eα` — the background alpha the presence value runs between. */
    internal var endAlpha: Float by mutableStateOf(1f)
        private set

    /** The close's target rect (the tile re-read at close time), overlay px. */
    internal var closeTarget: Rect? by mutableStateOf<Rect?>(null)
        private set

    /** The zoom-source entry of the current request (hides the tile). */
    internal var sourceEntry: ViewerSourceEntry? by mutableStateOf<ViewerSourceEntry?>(null)
        private set

    /** True until the page's first layout starts the open animation. */
    private var awaitingLayout = false

    /** True once the presence animation has begun (close may reverse it). */
    internal var openStarted = false
        private set

    // ------------------------------------------------------------ host wiring

    /** The host's composition scope (animations), attached by the host. */
    private var scope: CoroutineScope? = null

    /** Reduce Motion as the host sees it (re-attached every recomposition). */
    private var reduceMotion = false

    /** The host density (dismiss thresholds are in dp). */
    internal var density: Density = Density(1f)

    /** The saved system-bar appearance, non-null while the page owns it. */
    internal var bars: ViewerBarAppearance? = null

    /** Every `imageViewerSource` node, keyed by reference. */
    internal val registry = mutableMapOf<String, MutableList<ViewerSourceEntry>>()

    /**
     * Attaches the host's scope, Reduce Motion and density. Called on
     * every recomposition of [ImageViewerHost] — plain fields, so it
     * never invalidates anything.
     */
    internal fun attach(scope: CoroutineScope, reduceMotion: Boolean, density: Density) {
        this.scope = scope
        this.reduceMotion = reduceMotion
        this.density = density
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Opens the viewer for [request] (main thread; ignored while
     * showing). A null [ImageViewerRequest.preview] first awaits
     * [ImageViewerRequest.loadFull] — a null result never opens anything
     * (a failed tile never opens the viewer), exactly as iOS
     * `TranscriptListView.swift:582-583`.
     */
    fun show(request: ImageViewerRequest) {
        if (isShowing) return
        val sc = scope ?: return
        // Claim the phase synchronously: isShowing guards re-entry and
        // the host swallows taps while the load is awaited.
        phase = ViewerPhase.Opening
        openJob = sc.launch(start = CoroutineStart.UNDISPATCHED) {
            val preview = request.preview
            if (preview == null) {
                resetMotion()
                val loaded = safeLoad(request)
                if (loaded == null) {
                    finish()
                    return@launch
                }
                startOpen(request, loaded, loadedInPlace = true)
            } else {
                startOpen(request, preview, loadedInPlace = false)
            }
        }
    }

    /**
     * The animated close (Close button, drag release, back commit,
     * tests). Reverses an `Opening` from where it is; captures the
     * current picture rect and background alpha otherwise; fades when
     * the source is gone, off screen, or Reduce Motion is on; restores
     * the system-bar icons immediately.
     */
    fun close() {
        when (phase) {
            ViewerPhase.Hidden, ViewerPhase.Closing -> return
            else -> {}
        }
        if (displayBitmap == null) {
            // Still awaiting a preview-less load: nothing was ever drawn.
            openJob?.cancel()
            fullDeferred?.cancel()
            finish()
            return
        }
        if (phase != ViewerPhase.Opening) {
            endRect = currentPictureRect()
            endAlpha = currentBackgroundAlpha()
        }
        // Re-read the source: only an attached, (partly) visible tile zooms.
        val coords = sourceEntry?.coords
        val visible = coords?.boundsInWindow()
        closeTarget = if (
            coords != null && coords.isAttached &&
            visible != null && visible.width > 0f && visible.height > 0f
        ) {
            val rect = Rect(coords.positionInWindow(), coords.size.toSize())
            Rect(
                rect.left - overlayOrigin.x,
                rect.top - overlayOrigin.y,
                rect.right - overlayOrigin.x,
                rect.bottom - overlayOrigin.y,
            )
        } else {
            null
        }
        transition = if (reduceMotion || closeTarget == null) ViewerTransition.Fade else ViewerTransition.Zoom
        restoreBars()
        phase = ViewerPhase.Closing
        val sc = scope ?: run {
            finish()
            return
        }
        if (!openStarted) {
            // The open animation never began: nothing to reverse.
            finish()
            return
        }
        openJob?.cancel()
        presenceJob?.cancel()
        presenceJob = sc.launch {
            presence.animateTo(0f, presenceSpec(reduceMotion))
            finish()
        }
    }

    /** The instant teardown (root swaps, ticket 35's registry). */
    fun dismissInstantly() = finish()

    /**
     * Puts the state straight into `Shown` with [bitmap] — previews and
     * goldens only, no animation (step 11). Suspend because the reset
     * writes the Animatables (plain snapTos; nothing animates).
     */
    internal suspend fun showForPreview(bitmap: ImageBitmap) {
        request = ImageViewerRequest(
            reference = "preview",
            preview = bitmap,
            sourceBounds = null,
            sourceCornerRadius = 0.dp,
        )
        displayBitmap = bitmap
        full = null
        phase = ViewerPhase.Shown
        transition = ViewerTransition.Fade
        endRect = Rect.Zero
        endAlpha = 1f
        closeTarget = null
        sourceEntry = null
        awaitingLayout = false
        openStarted = true
        pinching = false
        zRaw = 1f
        presence.snapTo(1f)
        zoom.snapTo(1f)
        tx.snapTo(0f)
        ty.snapTo(0f)
        dragOffset.snapTo(Offset.Zero)
        backProgress.snapTo(0f)
    }

    /**
     * Shares the full-resolution bitmap (the preview if the load failed);
     * the viewer stays open and a second tap is ignored while one runs.
     */
    fun share(context: Context) {
        if (phase != ViewerPhase.Shown) return
        if (shareJob?.isActive == true) return
        val sc = scope ?: return
        shareJob = sc.launch {
            try {
                val deferred = fullDeferred
                val bitmap = if (deferred != null) {
                    try {
                        deferred.await() ?: displayBitmap
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        displayBitmap
                    }
                } else {
                    displayBitmap
                }
                if (bitmap != null) ImageShare.share(context, bitmap)
            } finally {
                shareJob = null
            }
        }
    }

    /** The common teardown: cancels everything, releases the bitmaps, restores the bars. */
    private fun finish() {
        openJob?.cancel()
        openJob = null
        presenceJob?.cancel()
        presenceJob = null
        settleJob?.cancel()
        settleJob = null
        dragJob?.cancel()
        dragJob = null
        backJob?.cancel()
        backJob = null
        shareJob?.cancel()
        shareJob = null
        fullDeferred?.cancel()
        fullDeferred = null
        phase = ViewerPhase.Hidden
        request = null
        displayBitmap = null
        full = null
        sourceEntry = null
        closeTarget = null
        awaitingLayout = false
        openStarted = false
        restoreBars()
    }

    /** Resets every Animatable to its rest value (suspend: plain snapTos). */
    private suspend fun resetMotion() {
        presence.snapTo(0f)
        zoom.snapTo(1f)
        tx.snapTo(0f)
        ty.snapTo(0f)
        dragOffset.snapTo(Offset.Zero)
        backProgress.snapTo(0f)
        pinching = false
        zRaw = 1f
    }

    /** Runs [ImageViewerRequest.loadFull] without ever throwing into the scope. */
    private suspend fun safeLoad(request: ImageViewerRequest): ImageBitmap? = try {
        request.loadFull?.invoke()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * The open's synchronous prologue: request, motion reset, source
     * resolution and the transition mode; then (in the caller's
     * coroutine) the full-load watch. The presence animation itself
     * starts at the page's first layout ([onLayout]).
     */
    private suspend fun startOpen(
        request: ImageViewerRequest,
        preview: ImageBitmap,
        loadedInPlace: Boolean,
    ) {
        resetMotion()
        this.request = request
        displayBitmap = preview
        full = null
        sourceEntry = resolveSource(request)
        phase = ViewerPhase.Opening
        transition = if (reduceMotion || request.sourceBounds == null) {
            ViewerTransition.Fade
        } else {
            ViewerTransition.Zoom
        }
        endRect = Rect.Zero
        endAlpha = 1f
        closeTarget = null
        awaitingLayout = true
        openStarted = false
        fullDeferred = if (!loadedInPlace && request.loadFull != null) {
            scope?.async {
                try {
                    request.loadFull?.invoke()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        } else {
            null
        }
        // Watch the full load: swap it in without animation when it lands.
        fullDeferred?.let { deferred ->
            val loaded = try {
                deferred.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            onFullLoaded(loaded)
        }
    }

    /** Picks the attached registry entry within 2 px of the request's source rect. */
    private fun resolveSource(request: ImageViewerRequest): ViewerSourceEntry? {
        val bounds = request.sourceBounds ?: return null
        val entries = registry[request.reference] ?: return null
        return entries.firstOrNull { entry ->
            val coords = entry.coords ?: return@firstOrNull false
            coords.isAttached &&
                (coords.positionInWindow() - bounds.topLeft).getDistance() <= 2f
        }
    }

    /** The full-resolution swap: same picture, no animation, `F` recomputed. */
    private fun onFullLoaded(loaded: ImageBitmap?) {
        if (loaded == null || request == null) return
        full = loaded
        displayBitmap = loaded
        recomputeFit()
        if (phase == ViewerPhase.Opening) endRect = fitRect
    }

    /** Recomputes `F` from the current bitmap and view size. */
    private fun recomputeFit() {
        val bitmap = displayBitmap ?: return
        if (viewSize == Size.Zero) return
        fitRect = fitRect(Size(bitmap.width.toFloat(), bitmap.height.toFloat()), viewSize)
    }

    /**
     * The page's layout report. The first layout (with a bitmap) starts
     * the open animation; later ones recompute `F` and re-clamp `T` when
     * zoomed (iOS re-lays out only at zoom 1, so a zoomed picture keeps
     * its zoom across a rotation). The re-clamp runs in the scope —
     * `snapTo` suspends and `onSizeChanged` cannot.
     */
    internal fun onLayout(size: Size) {
        if (size == viewSize && !awaitingLayout) return
        viewSize = size
        if (displayBitmap != null) {
            recomputeFit()
            if (phase == ViewerPhase.Opening) endRect = fitRect
            if (zoom.value > 1.001f) {
                val clamped = clampT(Offset(tx.value, ty.value), zoom.value, size)
                scope?.launch {
                    tx.snapTo(clamped.x)
                    ty.snapTo(clamped.y)
                }
            }
        }
        if (awaitingLayout && phase == ViewerPhase.Opening && displayBitmap != null && viewSize != Size.Zero) {
            awaitingLayout = false
            openStarted = true
            presenceJob?.cancel()
            presenceJob = scope?.launch {
                presence.animateTo(1f, presenceSpec(reduceMotion))
                if (phase == ViewerPhase.Opening) phase = ViewerPhase.Shown
            }
        }
    }

    /** The open/close animation spec: spring, or the reduce-motion tween. */
    private fun presenceSpec(reduce: Boolean): AnimationSpec<Float> =
        if (reduce) tween(durationMillis = ViewerFadeMs, easing = Motion.EaseInOut) else ViewerZoom.spec()

    // ------------------------------------------------------------ drawing

    /**
     * The translation the page draws: raw during a pinch (the content
     * follows the fingers), rubber-banded outside the pan bounds during
     * a one-finger pan.
     */
    internal fun displayedT(): Offset {
        val t = Offset(tx.value, ty.value)
        val view = viewSize
        if (pinching || view == Size.Zero) return t
        val z = zoom.value
        val lowerX = view.width - view.width * z
        val lowerY = view.height - view.height * z
        val x = when {
            t.x > 0f -> rubberBand(t.x, view.width)
            t.x < lowerX -> lowerX - rubberBand(lowerX - t.x, view.width)
            else -> t.x
        }
        val y = when {
            t.y > 0f -> rubberBand(t.y, view.height)
            t.y < lowerY -> lowerY - rubberBand(lowerY - t.y, view.height)
            else -> t.y
        }
        return Offset(x, y)
    }

    /** The background alpha (and the buttons' alpha). */
    internal fun currentBackgroundAlpha(): Float = when (phase) {
        ViewerPhase.Opening, ViewerPhase.Closing -> endAlpha * presence.value
        else -> {
            val view = viewSize
            (1f - max(dismissProgress(dragOffset.value.y, view.height), backProgress.value)).coerceIn(0f, 1f)
        }
    }

    /** The picture rect on screen right now (zoom, pan, dismiss transform). */
    internal fun currentPictureRect(): Rect {
        val view = viewSize
        val z = zoom.value
        val rect = zoomedRect(fitRect, z, displayedT())
        val p = max(dismissProgress(dragOffset.value.y, view.height), backProgress.value)
        return dismissTransform(
            rect,
            Offset(view.width / 2f, view.height / 2f),
            p,
            dragOffset.value,
        )
    }

    // ------------------------------------------------------------ gestures

    /** A new gesture begins: snap any running zoom/T/drag animation to now. */
    internal fun stopSettles() {
        settleJob?.cancel()
        settleJob = null
        dragJob?.cancel()
        dragJob = null
        backJob?.cancel()
        backJob = null
        // A fresh pinch starts from the resting zoom (the raw product is
        // per-gesture, never carried across settles).
        zRaw = zoom.value
    }

    /**
     * The dismiss drag crossed the slop at zoom 1 (vertical-dominant):
     * enters `Dragging`, which owns the finger until release.
     */
    internal fun beginDismissDrag() {
        if (phase == ViewerPhase.Shown) phase = ViewerPhase.Dragging
    }

    /**
     * One pinch step: `zoom *= zFactor` anchored at [centroid] (the
     * displayed picture point under the centroid stays under it),
     * `+ pan`; `T` stays raw (unclamped) during the pinch. [centroid]
     * also becomes the settle anchor.
     */
    internal fun pinch(zFactor: Float, centroid: Offset, pan: Offset) = step {
        if (phase != ViewerPhase.Shown) return@step
        pinching = true
        lastCentroid = centroid
        zRaw *= zFactor
        val zOld = zoom.value
        val zNew = rubberZoom(zRaw)
        if (zOld > 0f) {
            val tOld = Offset(tx.value, ty.value)
            val tNew = centroid - (centroid - tOld) * (zNew / zOld) + pan
            tx.snapTo(tNew.x)
            ty.snapTo(tNew.y)
        }
        zoom.snapTo(zNew)
    }

    /**
     * Pinch release: animate `z` to `clamp(z, 1, 5)` around the last
     * centroid and `T` to the pan bounds — at `zTarget == 1`,
     * `T → (0, 0)` — with [ViewerZoom].
     */
    internal fun settlePinch(centroid: Offset = lastCentroid) {
        if (phase != ViewerPhase.Shown) return
        pinching = false
        val sc = scope ?: return
        val z = zoom.value
        val zTarget = z.coerceIn(ViewerMinZoom, ViewerMaxZoom)
        val view = viewSize
        val anchored = if (z > 0f) {
            centroid - (centroid - Offset(tx.value, ty.value)) * (zTarget / z)
        } else {
            Offset.Zero
        }
        val target = if (view == Size.Zero) Offset.Zero else clampT(anchored, zTarget, view)
        settleJob?.cancel()
        settleJob = sc.launch {
            coroutineScope {
                launch { zoom.animateTo(zTarget, ViewerZoom.spec()) }
                launch { tx.animateTo(target.x, ViewerZoom.spec()) }
                launch { ty.animateTo(target.y, ViewerZoom.spec()) }
            }
        }
    }

    /** One one-finger pan step at zoom > 1: `T += delta`, unclamped. */
    internal fun pan(delta: Offset) = step {
        if (phase != ViewerPhase.Shown) return@step
        tx.snapTo(tx.value + delta.x)
        ty.snapTo(ty.value + delta.y)
    }

    /**
     * Pan release: out of bounds → spring back to the bound with
     * [ViewerZoom]; in bounds → fling with the UIKit decay, stopping at
     * the bounds.
     */
    internal fun endPan(velocity: Offset) {
        if (phase != ViewerPhase.Shown) return
        val sc = scope ?: return
        val view = viewSize
        if (view == Size.Zero) return
        val z = zoom.value
        val lowerX = view.width - view.width * z
        val lowerY = view.height - view.height * z
        val x0 = tx.value
        val y0 = ty.value
        val xTarget = x0.coerceIn(lowerX, 0f)
        val yTarget = y0.coerceIn(lowerY, 0f)
        settleJob?.cancel()
        if (xTarget != x0 || yTarget != y0) {
            settleJob = sc.launch {
                coroutineScope {
                    if (xTarget != x0) launch { tx.animateTo(xTarget, ViewerZoom.spec()) }
                    if (yTarget != y0) launch { ty.animateTo(yTarget, ViewerZoom.spec()) }
                }
            }
            return
        }
        settleJob = sc.launch {
            coroutineScope {
                launch { decayFling(tx, velocity.x, lowerX) }
                launch { decayFling(ty, velocity.y, lowerY) }
            }
        }
    }

    /**
     * One deceleration of [axis] from [v0] that never leaves
     * `[lower, 0]`: the analytic decay target clamped to the bound, then
     * a same-shape decay (velocity scaled to land exactly there).
     */
    private suspend fun decayFling(
        axis: Animatable<Float, AnimationVector1D>,
        v0: Float,
        lower: Float,
    ) {
        val start = axis.value
        val target = ViewerPanDecay.calculateTargetValue(start, v0).coerceIn(lower, 0f)
        if (abs(target - start) < 0.1f) {
            axis.snapTo(target)
            return
        }
        axis.animateDecay((target - start) * ViewerPanDecayK, ViewerPanDecay)
        axis.snapTo(target)
    }

    /** One dismiss-drag step at zoom 1: the picture follows the finger. */
    internal fun drag(delta: Offset) = step {
        if (phase != ViewerPhase.Dragging) return@step
        dragOffset.snapTo(dragOffset.value + delta)
    }

    /**
     * Runs one gesture step's snaps on the host scope. The gesture loop's
     * `AwaitPointerEventScope` is `@RestrictsSuspension`, so it can't call
     * `Animatable.snapTo` itself. UNDISPATCHED runs the step synchronously
     * whenever the animatables are free (always, at rest). A step that
     * has to wait (a just-cancelled settle still holds the mutator) is
     * joined by the next one, so steps apply in order. A later snap never
     * cancels an earlier pending one, so no delta is lost.
     */
    private fun step(block: suspend () -> Unit) {
        val sc = scope ?: return
        val previous = stepJob
        stepJob = sc.launch(start = CoroutineStart.UNDISPATCHED) {
            previous?.join()
            block()
        }
    }

    /**
     * Dismiss-drag release: past 100 dp or 800 dp/s → close from the
     * dragged state; otherwise spring `D → (0, 0)` back with
     * [ViewerSnapBack] (`p` follows).
     */
    internal fun endDrag(velocity: Offset) {
        if (phase != ViewerPhase.Dragging) return
        if (shouldDismiss(dragOffset.value.y, velocity.y, density.density)) {
            close()
            return
        }
        phase = ViewerPhase.Shown
        val sc = scope ?: return
        dragJob = sc.launch {
            dragOffset.animateTo(Offset.Zero, ViewerSnapBack.spec())
        }
    }

    /**
     * Double tap (anywhere on the page, not on a button): `z > 1` →
     * `(1, (0, 0))`; otherwise the 2.5× target centred on the tap —
     * both animated with [ViewerZoom]. A single tap does nothing.
     */
    internal fun onDoubleTap(tap: Offset) {
        if (phase != ViewerPhase.Shown) return
        val sc = scope ?: return
        val view = viewSize
        if (view == Size.Zero) return
        stopSettles()
        val (z, t) = doubleTapTarget(zoom.value, Offset(tx.value, ty.value), tap, view)
        settleJob = sc.launch {
            coroutineScope {
                launch { zoom.animateTo(z, ViewerZoom.spec()) }
                launch { tx.animateTo(t.x, ViewerZoom.spec()) }
                launch { ty.animateTo(t.y, ViewerZoom.spec()) }
            }
        }
    }

    // ------------------------------------------------------------ predictive back

    /** Back started while shown: owns the gesture until commit or cancel. */
    internal fun beginBackGesture() {
        if (phase == ViewerPhase.Shown) phase = ViewerPhase.Back
    }

    /** One back progress step: `q` follows the finger. */
    internal suspend fun onBackEvent(progress: Float) {
        if (phase != ViewerPhase.Back) return
        backProgress.snapTo(progress)
    }

    /** Back cancelled: snap `q → 0` with [ViewerSnapBack], back to `Shown`. */
    internal fun onBackCancelled() {
        if (phase != ViewerPhase.Back) return
        phase = ViewerPhase.Shown
        val sc = scope ?: return
        backJob?.cancel()
        backJob = sc.launch {
            backProgress.animateTo(0f, ViewerSnapBack.spec())
        }
    }

    // ------------------------------------------------------------ system bars

    /**
     * The page's first composition: saves the icon appearance and forces
     * light (white) icons on the black page — iOS's `.default` over
     * black is a readability bug, so this is a deliberate deviation.
     */
    internal fun applyLightBars(controller: WindowInsetsControllerCompat) {
        if (bars == null) {
            bars = ViewerBarAppearance(
                controller = controller,
                status = controller.isAppearanceLightStatusBars,
                navigation = controller.isAppearanceLightNavigationBars,
            )
        }
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }

    /** Restores the saved appearance once (idempotent). */
    internal fun restoreBars() {
        val saved = bars ?: return
        saved.controller.isAppearanceLightStatusBars = saved.status
        saved.controller.isAppearanceLightNavigationBars = saved.navigation
        bars = null
    }
}

/**
 * Remembers a [ImageViewerState] — one per host, never per screen (the
 * viewer outlives the session screen that opened it).
 */
@Composable
fun rememberImageViewerState(): ImageViewerState = remember { ImageViewerState() }

/** The nearest [ImageViewerHost]'s state, or null outside one. */
val LocalImageViewer = staticCompositionLocalOf<ImageViewerState?> { null }

/**
 * Provides [LocalImageViewer] and draws the viewer over [content]
 * (android-ui-parity #125): a root-level overlay in the activity's own
 * window, so the picture can animate out of a transcript tile — a new
 * Activity or Dialog window cannot. The page covers the whole window on
 * black with the system bars still visible; while showing, the content
 * below is hidden from accessibility and receives no touches.
 *
 * The predictive-back handler is composed **only while showing** (after
 * the content, so it outranks every handler registered under it) and
 * stays enabled in every non-Hidden phase — a disabled one would let
 * the back event pop the session screen under the viewer.
 */
@Composable
fun ImageViewerHost(state: ImageViewerState, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    state.attach(scope, reduceMotion, density)
    CompositionLocalProvider(LocalImageViewer provides state) {
        Box(Modifier.fillMaxSize()) {
            // The content first: its handlers register before the viewer's.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (state.isShowing) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                content()
            }
            if (state.isShowing) {
                PredictiveBackHandler(enabled = true) { events ->
                    if (state.phase != ViewerPhase.Shown) {
                        // Opening, Dragging, Back or Closing: never animate,
                        // just own the gesture and close on commit.
                        try {
                            events.collect { }
                            state.close()
                        } catch (e: CancellationException) {
                            throw e
                        }
                    } else {
                        state.beginBackGesture()
                        try {
                            events.collect { event -> state.onBackEvent(event.progress) }
                            state.close()
                        } catch (e: CancellationException) {
                            state.onBackCancelled()
                            throw e
                        }
                    }
                }
                RegisterRootPresentation(active = true, dismissInstantly = state::dismissInstantly)
                if (state.displayBitmap != null) {
                    ViewerPage(state)
                } else {
                    // Awaiting a preview-less load: nothing to draw, but
                    // taps must still be swallowed (isShowing is true).
                    Box(Modifier.fillMaxSize().swallowTouches())
                }
            }
        }
    }
}

/** Consumes every pointer event that reaches it (the awaiting scrim). */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var released = false
        while (!released) {
            val event = awaitPointerEvent()
            event.changes.forEach { change ->
                if (!change.isConsumed) change.consume()
            }
            released = event.changes.none { it.pressed }
        }
    }
}

/**
 * The viewer's page: the full-screen black layer with the zoomable
 * picture (the haze source, so the glass buttons sample it) and the two
 * glass buttons outside it. All animated values are read inside
 * `drawBehind` / `graphicsLayer` lambdas, so a frame redraws without
 * recomposing.
 */
@Composable
private fun ViewerPage(state: ImageViewerState) {
    val activity = LocalActivity.current as? ComponentActivity
    val view = LocalView.current
    val context = LocalContext.current

    // Light system-bar icons while the page shows; restored on close and
    // on dispose (whichever comes first, once).
    DisposableEffect(activity, view) {
        if (activity == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            state.applyLightBars(controller)
            onDispose { state.restoreBars() }
        }
    }

    ProvideHazeState {
        val haze = LocalHazeState.current
        Box(
            Modifier
                .fillMaxSize()
                .onPlaced { coords -> state.overlayOrigin = coords.positionInWindow() }
                .onSizeChanged { size ->
                    state.onLayout(Size(size.width.toFloat(), size.height.toFloat()))
                },
        ) {
            // Child 1: the page layer — the zoomable picture, the haze source.
            Spacer(
                Modifier
                    .fillMaxSize()
                    .hazeSource(haze!!)
                    .semantics {
                        role = Role.Image
                        contentDescription = state.request?.label ?: ImageFallbackLabel
                        traversalIndex = 1f
                        this[ImageViewerZoomKey] = state.zoom.value
                        this[ImageViewerBackgroundAlphaKey] = state.currentBackgroundAlpha()
                    }
                    .testTag(ImageViewerTag)
                    .pointerInput(state) {
                        // Before the transform loop: taps reach this first
                        // and a consumed drag cancels it (the loop consumes
                        // only after the touch slop).
                        detectTapGestures(onDoubleTap = { tap -> state.onDoubleTap(tap) })
                    }
                    .pointerInput(state) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (state.phase != ViewerPhase.Shown) {
                                // Opening/Closing/Dragging/Back: swallow the gesture.
                                var released = false
                                while (!released) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { change ->
                                        if (!change.isConsumed) change.consume()
                                    }
                                    released = event.changes.none { it.pressed }
                                }
                                return@awaitEachGesture
                            }
                            state.stopSettles()
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var mode = ViewerGesture.Undecided
                            var slopPan = Offset.Zero
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                pressed.firstOrNull()?.let { change ->
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                }
                                if (mode == ViewerGesture.Undecided) {
                                    if (pressed.size >= 2) {
                                        mode = ViewerGesture.Pinch
                                    } else {
                                        slopPan += event.calculatePan()
                                        if (slopPan.getDistance() > viewConfiguration.touchSlop) {
                                            mode = when {
                                                state.zoom.value > 1.001f -> ViewerGesture.Pan
                                                abs(slopPan.y) > abs(slopPan.x) -> {
                                                    state.beginDismissDrag()
                                                    ViewerGesture.Dismiss
                                                }
                                                else -> ViewerGesture.Ignore
                                            }
                                        }
                                    }
                                }
                                when (mode) {
                                    ViewerGesture.Pinch -> {
                                        state.pinch(
                                            event.calculateZoom(),
                                            event.calculateCentroid(useCurrent = true),
                                            event.calculatePan(),
                                        )
                                        event.changes.forEach { change -> change.consume() }
                                    }
                                    ViewerGesture.Pan -> {
                                        if (event.changes.count { it.pressed } >= 2) {
                                            mode = ViewerGesture.Pinch
                                        } else {
                                            state.pan(event.calculatePan())
                                            event.changes.forEach { change -> change.consume() }
                                        }
                                    }
                                    ViewerGesture.Dismiss -> {
                                        // Extra fingers during a dismiss drag are
                                        // ignored: only the initial pointer moves
                                        // the picture.
                                        event.changes
                                            .firstOrNull { change -> change.id == down.id }
                                            ?.let { change ->
                                                state.drag(change.positionChange())
                                                change.consume()
                                            }
                                    }
                                    ViewerGesture.Ignore, ViewerGesture.Undecided -> Unit
                                }
                            }
                            val v = tracker.calculateVelocity()
                            when (mode) {
                                ViewerGesture.Pinch -> state.settlePinch()
                                ViewerGesture.Pan -> state.endPan(Offset(v.x, v.y))
                                ViewerGesture.Dismiss -> state.endDrag(Offset(v.x, v.y))
                                else -> Unit
                            }
                        }
                    }
                    .drawBehind { drawViewer(state) },
            )
            // Child 2: the glass buttons, outside the haze source, fading
            // with the background. TopStart/TopEnd mirror in RTL, like the
            // iOS leading/trailing constraints. Enabled stays true even
            // while Closing (ticket 08 would ink a disabled glyph grey,
            // which would flash during the fade-out): close() itself
            // no-ops in Closing, share() outside Shown.
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .graphicsLayer { alpha = state.currentBackgroundAlpha() },
            ) {
                GlassCircleButton(
                    icon = RobocoIcons.Xmark,
                    contentDescription = CloseImagePreviewLabel,
                    onClick = { state.close() },
                    size = 44.dp,
                    iconPt = 15f,
                    ink = RobocoTheme.palette.text,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 8.dp)
                        .testTag(ImageViewerCloseTag),
                )
                GlassCircleButton(
                    icon = RobocoIcons.SquareAndArrowUp,
                    contentDescription = ShareImageLabel,
                    onClick = { state.share(context) },
                    size = 44.dp,
                    iconPt = 15f,
                    ink = RobocoTheme.palette.text,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 16.dp, top = 8.dp)
                        .testTag(ImageViewerShareTag),
                )
            }
        }
    }
}

/**
 * The page's draw: the black background at the current alpha, then the
 * picture — the zoom/pan/dismiss rect while presented, the
 * `lerp(S, E, t)` clip-rect morph (aspect-fill, the tile's corner
 * radius) during a Zoom open/close, or a plain cross-fade during a Fade
 * one. `E` always has the picture's aspect ratio, so `fill(E) = E` at
 * `t = 1` and the hand-off to the zoomable page has no jump.
 */
private fun DrawScope.drawViewer(state: ImageViewerState) {
    val bitmap = state.displayBitmap ?: return
    val request = state.request ?: return
    val view = state.viewSize
    if (view.width <= 0f || view.height <= 0f) return
    val bg = state.currentBackgroundAlpha()
    drawRect(color = Color.Black, size = size, alpha = bg)
    val bitmapSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
    when (state.phase) {
        ViewerPhase.Opening, ViewerPhase.Closing -> when (state.transition) {
            ViewerTransition.Zoom -> {
                val source: Rect = if (state.phase == ViewerPhase.Opening) {
                    val bounds = request.sourceBounds
                    if (bounds != null) {
                        Rect(
                            bounds.left - state.overlayOrigin.x,
                            bounds.top - state.overlayOrigin.y,
                            bounds.right - state.overlayOrigin.x,
                            bounds.bottom - state.overlayOrigin.y,
                        )
                    } else {
                        state.endRect
                    }
                } else {
                    state.closeTarget ?: state.endRect
                }
                val t = state.presence.value
                val clip = lerpRect(source, state.endRect, t)
                val radius = request.sourceCornerRadius.toPx() * (1f - t)
                drawFillClipped(bitmap, bitmapSize, clip, radius)
            }
            ViewerTransition.Fade -> drawPicture(bitmap, state.endRect, alpha = state.presence.value)
        }
        else -> drawPicture(bitmap, state.currentPictureRect())
    }
}

/** Draws [bitmap] into [dst] (px), Medium filtering. */
private fun DrawScope.drawPicture(bitmap: ImageBitmap, dst: Rect, alpha: Float = 1f) {
    if (dst.width < 0.5f || dst.height < 0.5f) return
    val dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt())
    translate(dst.left, dst.top) {
        drawImage(
            bitmap,
            dstOffset = IntOffset.Zero,
            dstSize = dstSize,
            alpha = alpha,
            filterQuality = FilterQuality.Medium,
        )
    }
}

/**
 * Draws [bitmap] aspect-fill inside [clip], rounded by [radiusPx] (the
 * tile's continuous corners at `t = 0`, square by `t = 1`): the zoom
 * transition's flying crop.
 */
private fun DrawScope.drawFillClipped(
    bitmap: ImageBitmap,
    bitmapSize: Size,
    clip: Rect,
    radiusPx: Float,
) {
    if (clip.width < 0.5f || clip.height < 0.5f) return
    val fill = fillRect(bitmapSize, clip)
    val dstOffset = IntOffset(
        (fill.left - clip.left).roundToInt(),
        (fill.top - clip.top).roundToInt(),
    )
    val dstSize = IntSize(fill.width.roundToInt(), fill.height.roundToInt())
    translate(clip.left, clip.top) {
        val drawBlock: DrawScope.() -> Unit = {
            drawImage(
                bitmap,
                dstOffset = dstOffset,
                dstSize = dstSize,
                filterQuality = FilterQuality.Medium,
            )
        }
        if (radiusPx < 0.5f) {
            clipRect(0f, 0f, clip.width, clip.height, block = drawBlock)
        } else {
            clipPath(smoothRoundedPath(clip.width, clip.height, radiusPx), block = drawBlock)
        }
    }
}

/**
 * Registers this node as a zoom source for [reference] (the tile root,
 * the same node whose `positionInWindow()` ticket 66 reports) and hides
 * it — alpha 0, read inside the layer block so only the layer redraws —
 * while it is the viewer's source: the `.zoom` transition moves the
 * source into the destination, so the flying picture never doubles with
 * the tile underneath.
 */
fun Modifier.imageViewerSource(reference: String): Modifier = composed {
    val state = LocalImageViewer.current
    val entry = remember { ViewerSourceEntry(reference) }
    DisposableEffect(state, reference) {
        val registry = state?.registry
        if (registry != null) {
            registry.getOrPut(reference) { mutableListOf() }.add(entry)
        }
        onDispose {
            entry.coords = null
            registry?.get(reference)?.remove(entry)
        }
    }
    this
        .onPlaced { coords -> entry.coords = coords }
        .graphicsLayer { alpha = if (state?.sourceEntry === entry) 0f else 1f }
}

// ---------------------------------------------------------------------------
// Previews (step 11): 393 × 852 light and dark, the state straight into
// Shown over a fake transcript.
// ---------------------------------------------------------------------------

@Preview(name = "Image viewer light", widthDp = 393, heightDp = 852)
@Composable
private fun ImageViewerLightPreview() {
    RobocoTheme(Appearance.Light) {
        val bitmap = remember { previewFixture() }
        val state = remember { ImageViewerState() }
        LaunchedEffect(state) { state.showForPreview(bitmap) }
        ImageViewerHost(state) { FakeTranscriptForPreview() }
    }
}

@Preview(name = "Image viewer dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ImageViewerDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        val bitmap = remember { previewFixture() }
        val state = remember { ImageViewerState() }
        LaunchedEffect(state) { state.showForPreview(bitmap) }
        ImageViewerHost(state) { FakeTranscriptForPreview() }
    }
}

/**
 * The transcript stand-in under the preview's viewer: the background
 * with a few project-tone stripes, so the black overlay and the glass
 * buttons have something to cover and blur.
 */
@Composable
private fun FakeTranscriptForPreview() {
    val palette = RobocoTheme.palette
    Box(Modifier.fillMaxSize().background(palette.background)) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 120.dp)
                .width(120.dp)
                .height(80.dp)
                .background(palette.projectTone(2)),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 18.dp, top = 220.dp)
                .width(160.dp)
                .height(90.dp)
                .background(palette.projectTone(4)),
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 340.dp)
                .width(260.dp)
                .height(146.dp)
                .background(palette.projectTone(0)),
        )
    }
}

/** The preview's fixture: the gallery's landscape gradient picture. */
private fun previewFixture(): ImageBitmap =
    imageViewerFixture(1600, 900, 0xFF5B43E8.toInt(), 0xFFF0A04B.toInt())
