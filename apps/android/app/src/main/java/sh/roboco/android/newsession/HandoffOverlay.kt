package sh.roboco.android.newsession

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.max
import kotlin.math.roundToInt
import sh.roboco.android.core.Face
import sh.roboco.android.design.HandoffTimings
import sh.roboco.android.design.LocalPalette
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.SmoothRoundedShape
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.HandoffAnimator
import sh.roboco.android.shell.LocalRouterHooks
import sh.roboco.android.shell.RouterHooks
import sh.roboco.android.shell.SessionRoute

/**
 * The states of one playing handoff (android-ui-parity #95) — the
 * overlay-side half of `ios:Session/DraftHandoff.swift:53-132`: the
 * three moving pictures' progress, rects and alphas. Every mutable
 * field is snapshot state and is read **only** in [HandoffOverlayCanvas]'s
 * draw lambda, so each animation frame re-draws the pictures without
 * recomposing anything.
 *
 * Created by [DraftHandoffAnimator.run] (synchronously, so the
 * overlays appear in the same frame as the router's push), removed by
 * `play`'s `finally` — dropping the set from the host's list is the
 * only reference drop for the two bitmaps (iOS `removeFromSuperview`).
 */
@Stable
class HandoffOverlaySet internal constructor(
    /** Ticket 94's capture: the two pictures, their frames and the sent text. */
    val handoff: CapturedDraftHandoff,

    /** The timings this set plays with (normal or reduced). */
    val timings: HandoffTimings,
    density: Float,
) {
    /** The scene dissolve's progress: 0 → 1 (`alpha = 1 − p`). */
    var sceneProgress by mutableFloatStateOf(0f)

    /** The composer picture's rect (root px): starts at `composerFrame`, glides. */
    var composerRect by mutableStateOf(handoff.composerFrame)

    /** The composer picture's alpha: 1 → 0 (the cross-fade to the chat composer). */
    var composerAlpha by mutableFloatStateOf(1f)

    /** The flying bubble's rect (root px): starts at the text outset, flies. */
    var bubbleRect by mutableStateOf(bubbleStartRect(handoff.textFrame, density))

    /**
     * The text layout's `maxWidth` (px): the start inner width until the
     * flight starts, then the target inner width — laid out once, at the
     * target's line breaks, for the whole flight and fade.
     */
    var bubbleLayoutWidth by mutableIntStateOf(
        bubbleInner(bubbleRect, density).width.roundToInt().coerceAtLeast(1),
    )

    /** The bubble fill's alpha: the clamped flight progress (iOS `settle()`). */
    var bubbleFill by mutableFloatStateOf(0f)

    /** The whole bubble's alpha: 1 → 0 in the final 140 ms fade. */
    var bubbleAlpha by mutableFloatStateOf(1f)

    /** Blank text flies nothing (the flight still runs, so timing is unchanged). */
    val textVisible: Boolean = handoff.text.isNotBlank()

    /** The scene's clip path, built once on first draw (a plain cache, not state). */
    private var clip: Path? = null

    /**
     * The scene picture's clip: ticket 94 hands the page's shape over
     * (31's 38 dp top corners on phone, a rectangle embedded) and exactly
     * this ticket applies it — from the shape's outline, in the picture's
     * own coordinates so it scales with the dissolve.
     */
    internal fun sceneClip(scope: DrawScope): Path = clip ?: Path().also { path ->
        when (val outline = handoff.sceneShape.createOutline(handoff.sceneFrame.size, scope.layoutDirection, scope)) {
            is Outline.Rectangle -> path.addRect(outline.rect)
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
        }
        clip = path
    }
}

/**
 * The overlay host's state (android-ui-parity #95): the playing sets
 * plus the host's own root-geometry (position, width, safe-area top and
 * density), which the animator reads for the fallback bubble target and
 * the set construction. [sets] is snapshot state, read in composition
 * (the canvas composes while a handoff plays) and in the draw lambda.
 */
@Stable
class HandoffOverlayState internal constructor() {
    /** The playing handoffs, in z-order (scene < composer < bubble per set). */
    internal val sets = mutableStateListOf<HandoffOverlaySet>()

    /** The host's position in root, px — the overlays draw translated by its negation. */
    internal var hostOrigin by mutableStateOf(Offset.Zero)

    /** The host's width, px (iOS `window.bounds.width`). */
    internal var hostWidth by mutableFloatStateOf(0f)

    /** `WindowInsets.safeDrawing`'s top, px (iOS `window.safeAreaInsets.top`). */
    internal var safeTop by mutableFloatStateOf(0f)

    /** The host's density (the geometry's `d`). */
    internal var density by mutableFloatStateOf(1f)
}

/**
 * The window-level host of the handoff overlays (android-ui-parity #95)
 * — the Roboco adaptation of `DraftHandoffAnimator`'s `window.addSubview`
 * calls: one Compose layer, mounted by ticket 35's `RootHosts` around
 * the `SheetHost`, above the app root and the (hidden) sheet, below
 * glass menus, action sheets, alerts and toasts.
 *
 * Also registers the [DraftHandoffAnimator] with [hooks] (the router's
 * seam of ticket 32; the default is the ambient [LocalRouterHooks]) and
 * restores [HandoffAnimator.Instant] on disposal. [targetOf] resolves a
 * route to its arrival target — production uses the default
 * [arrivalTargetOf]; the debug gallery and the tests inject a fake so a
 * fake route resolves to their fake target.
 *
 * Must sit inside `RobocoTheme` (the canvas reads the palette and the
 * type scale) and inside the `LocalRouterHooks` provider.
 */
@Composable
fun HandoffOverlayHost(
    hooks: RouterHooks = LocalRouterHooks.current,
    targetOf: (SessionRoute) -> HandoffArrivalTarget? = ::arrivalTargetOf,
    content: @Composable () -> Unit,
) {
    val state = remember { HandoffOverlayState() }
    val scope = rememberCoroutineScope() // main + MonotonicFrameClock
    val density = LocalDensity.current
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    SideEffect {
        state.safeTop = safeTop.toFloat()
        state.density = density.density
    }
    val animator = remember(state, scope) { DraftHandoffAnimator(state, scope, targetOf = targetOf) }
    DisposableEffect(hooks, animator) {
        hooks.handoffAnimator = animator
        onDispose {
            if (hooks.handoffAnimator === animator) hooks.handoffAnimator = HandoffAnimator.Instant
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                state.hostOrigin = coordinates.positionInRoot()
                state.hostWidth = coordinates.size.width.toFloat()
            },
    ) {
        content()
        // The overlays compose only while a handoff plays; the canvas is
        // removed instantly at the end (iOS removeFromSuperview), never
        // faded out.
        if (state.sets.isNotEmpty()) HandoffOverlayCanvas(state, Modifier.matchParentSize())
    }
}

/**
 * The overlays themselves (android-ui-parity #95): one non-interactive
 * `Canvas` drawing, per set, bottom to top: the dissolving scene
 * picture (clipped to 94's `sceneShape`, scaled around its centre), the
 * gliding composer picture (size never changes), and the flying bubble
 * (the 20 dp continuous-corner fill over bare text). No pointer
 * modifiers — taps pass through to the chat underneath — and
 * `clearAndSetSemantics { }` so TalkBack never sees the pictures.
 */
@Composable
internal fun HandoffOverlayCanvas(state: HandoffOverlayState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val typeScale = LocalTypeScale.current
    // Geist Regular at the body-scaled 16.5 (DraftHandoff.swift:191).
    val style = uiStyle(Face.Sans, typeScale.bodyScaled(16.5f), palette.text)
    val measurer = rememberTextMeasurer()
    val shape = remember { SmoothRoundedShape(20.dp) }
    Canvas(modifier.clearAndSetSemantics { }) {
        // Root px → host px: the host is the full window in production
        // (ticket 35); a host placed elsewhere still draws at the right
        // place.
        translate(-state.hostOrigin.x, -state.hostOrigin.y) {
            for (set in state.sets) {
                val handoff = set.handoff

                // ── the scene: alpha 1 → 0 with a slight settle-scale.
                val sceneAlpha = 1f - set.sceneProgress
                if (sceneAlpha > 0f) {
                    val frame = handoff.sceneFrame
                    val settleScale = lerp(1f, set.timings.dissolveScale, set.sceneProgress)
                    withTransform({
                        scale(settleScale, settleScale, pivot = frame.center)
                        translate(frame.left, frame.top)
                    }) {
                        clipPath(set.sceneClip(this)) {
                            drawImage(
                                handoff.scene,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(
                                    frame.width.roundToInt(),
                                    frame.height.roundToInt(),
                                ),
                                alpha = sceneAlpha,
                            )
                        }
                    }
                }

                // ── the composer picture: glides, keeps its size, fades out.
                if (set.composerAlpha > 0f) {
                    val rect = set.composerRect
                    drawImage(
                        handoff.composer,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(
                            handoff.composerFrame.width.roundToInt(),
                            handoff.composerFrame.height.roundToInt(),
                        ),
                        alpha = set.composerAlpha,
                    )
                }

                // ── the flying bubble: bare text first, the fill settles
                //    with the same spring. Blank text draws nothing.
                if (set.textVisible) {
                    val rect = set.bubbleRect
                    val fillAlpha = set.bubbleFill * set.bubbleAlpha
                    if (fillAlpha > 0f) {
                        translate(rect.left, rect.top) {
                            drawOutline(
                                shape.createOutline(rect.size, layoutDirection, this),
                                palette.userBubble,
                                alpha = fillAlpha,
                            )
                        }
                    }
                    if (set.bubbleAlpha > 0f) {
                        // Constraints change only twice (start and flight),
                        // so the measurer's cache serves every other frame.
                        val layout = measurer.measure(
                            handoff.text,
                            style,
                            overflow = TextOverflow.Clip,
                            softWrap = true,
                            constraints = Constraints(maxWidth = set.bubbleLayoutWidth),
                        )
                        val inner = bubbleInner(rect, density)
                        // Centred when shorter, top-aligned when taller.
                        val dy = max(0f, (inner.height - layout.size.height) / 2f)
                        clipRect(inner.left, inner.top, inner.right, inner.bottom) {
                            drawText(
                                layout,
                                topLeft = Offset(inner.left, inner.top + dy),
                                alpha = set.bubbleAlpha,
                            )
                        }
                    }
                }
            }
        }
    }
}
