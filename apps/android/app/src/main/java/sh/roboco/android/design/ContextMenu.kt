package sh.roboco.android.design

import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import kotlinx.coroutines.withTimeoutOrNull
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.hasContent

/**
 * `Modifier.contextMenu` (android-ui-parity #16): Roboco's port of the iOS
 * long-press context menu (`UIContextMenuInteraction`): the pressed row
 * "lifts" as a rounded preview over a dimmed, blurred backdrop, and a
 * [sh.roboco.android.design.glass.GlassMenuContent] opens attached to it.
 * The gesture, haptic, lift, backdrop, placement and dismissal live here
 * and in `ContextMenuHost.kt`; the menu **items** belong to the callers
 * (tickets 44, 46, 128).
 *
 * This file holds the row-side half: the highlight presets
 * (`UIPreviewParameters` of `TranscriptListView.swift:633-639` /
 * `SessionCell.swift:79-84,267-273`), the starred chrome tokens, the
 * lift-state seam for the callers' own pressed fill, the
 * record-into-a-`GraphicsLayer` row modifier, and the press detector.
 */

/** What fills the lifted preview under the recorded row content. */
enum class LiftFill {
    /** `Palette.background` — transparent rows (transcript). */
    Background,

    /** `background`, then `controlFill` over it — the pressed list-cell look (≈ `#E4E4E6` light / `#171717` dark). Drawn as two layers, never a hard-coded composite. */
    Pressed,
}

/**
 * The preview's cut-out: the row's bounds inset by [horizontalInset] /
 * [verticalInset], clipped to [cornerRadius] continuous corners, filled
 * with [fill]. Row content outside the cut-out (the side margins) is not
 * part of the preview.
 */
@Immutable
data class LiftHighlight(
    val horizontalInset: Dp,
    val verticalInset: Dp,
    val cornerRadius: Dp,
    val fill: LiftFill,
) {
    companion object {
        /** `TranscriptListView.swift:636-637`: inset 8/0, radius 16, `background`. */
        val TranscriptRow = LiftHighlight(8.dp, 0.dp, 16.dp, LiftFill.Background)

        /** `SessionCell.swift:81-83`: inset 8/1, radius 16, pressed fill. */
        val SessionRow = LiftHighlight(8.dp, 1.dp, 16.dp, LiftFill.Pressed)

        /** `SessionCell.swift:269-271`: inset 8/2, radius 12, pressed fill. */
        val SectionHeader = LiftHighlight(8.dp, 2.dp, 12.dp, LiftFill.Pressed)

        /** A Settings grouped card (android-next#05): the card inset and
         * radius of `GroupedListTokens`, pressed fill — the accounts
         * page's login rows. */
        val GroupedCard = LiftHighlight(
            GroupedListTokens.CardInset,
            0.dp,
            GroupedListTokens.CornerRadius,
            LiftFill.Pressed,
        )
    }
}

/**
 * The context-menu chrome UIKit draws for free — no upstream source
 * numbers exist. Starred values come from `android-app#17` (checked
 * against iOS recordings once): tune them in the gallery against iOS,
 * then freeze.
 */
object ContextMenuTokens {

    /** The lifted preview's scale, about its centre. (\*) */
    const val LiftScale = 1.02f

    /** The preview's shadow. (\*) */
    val ShadowRadius = 24.dp // *

    /** The preview's shadow offset. (\*) */
    val ShadowOffsetY = 8.dp // *

    /** The preview's shadow opacity. (\*) */
    const val ShadowAlpha = 0.12f

    /** The full-window scrim behind the preview and menu. (\*) */
    const val DimLight = 0.12f

    /** The full-window scrim behind the preview and menu. (\*) */
    const val DimDark = 0.35f

    /** The backdrop's blur radius, a `RenderEffect` on the app content layer. (\*) */
    val BlurRadius = 12.dp

    /** The gap between the scaled preview and the menu. (\*) */
    val MenuGap = 8.dp

    /** The margin inside `WindowInsets.safeDrawing` on every side. (\*) */
    val WindowMargin = 8.dp

    /**
     * The lift spring, ≈ UIKit's 0.38 s / ζ 0.86 lift (settle-matched
     * stiffness 446.80 — computed by [IosSpring], never pasted). The
     * visibility threshold matches the removal rule: the overlay leaves
     * when `lift` settles within 0.001 of 0.
     */
    val LiftSpring: SpringSpec<Float> = IosSpring("contextLift", 0.38, 0.86f).spec(visibilityThreshold = 0.001f)

    /** Reduce Motion: no scale, dim/blur/shadow cross-fade 150 ms both ways. */
    val ReducedLift: TweenSpec<Float> = tween(150, easing = LinearEasing)
}

/**
 * Read by the caller to suppress the row's own pressed fill while lifted
 * (the "double pressed fill" pitfall): ticket 37's row draws
 * `controlFill × h` inside the content that `Modifier.contextMenu`
 * records, and the `Pressed` preset also draws `controlFill`, so the
 * preview would show ≈ 2 × 0.075 while the finger is down. Callers that
 * draw a pressed fill pass a lift state and snap their highlight to 0
 * while [isLifted] (tickets 44, 46). Rows without their own fill pass
 * nothing.
 */
@Stable
class ContextMenuLiftState {
    /** True from the moment the row lifts until the overlay is removed. */
    var isLifted: Boolean by mutableStateOf(false)
        internal set
}

/** The [ContextMenuLiftState] for one row. */
@Composable
fun rememberContextMenuLiftState(): ContextMenuLiftState = remember { ContextMenuLiftState() }

/**
 * True when the menu holds no content — ticket 15's open rule, applied
 * through its own [Menu.hasContent] (the root level flattens to no
 * `Row` / `Submenu` / `Loading` line; a pending deferred element counts
 * as content).
 */
internal fun Menu.isEffectivelyEmpty(): Boolean = !hasContent()

/** The no-host warning fires once per process, not once per row. */
private var warnedNoContextMenuHost = false

/**
 * iOS `UIContextMenuInteraction` for a row: long-pressing records the row
 * into a [androidx.compose.ui.graphics.layer.GraphicsLayer], hides it and
 * lets the ambient [ContextMenuHost] draw it lifted — the row's content
 * keeps recomposing into the same layer, so the preview stays live.
 *
 * [menu] runs on the main thread when the long press fires; `null` or an
 * empty menu → nothing happens (no haptic, no lift — the #609 trap), and
 * the gesture is left alone so the row's tap still works on release.
 *
 * Place **before** `clickable` and any swipe modifier in the chain, so the
 * swallowed release after a long press cannot reach them.
 *
 * Semantics: an `onLongClick` "Show menu" action (the label ticket 46
 * expects) opens the menu as if long-pressed at the row centre, with the
 * haptic, and returns `false` when the menu is null/empty. Ticket 129
 * passes `semanticsLongClick = false` on the transcript rows: the row
 * container would otherwise become a TalkBack focus stop ("double-tap
 * and hold to long press") between the row text and its widgets; the
 * touch gesture path is unchanged, and the row text node's custom
 * actions replace it.
 */
fun Modifier.contextMenu(
    highlight: LiftHighlight,
    enabled: Boolean = true,
    liftState: ContextMenuLiftState? = null,
    swallowWithoutMenu: Boolean = false,
    semanticsLongClick: Boolean = true,
    menu: () -> Menu?,
): Modifier = composed {
    val host = LocalContextMenuHost.current
    if (host == null) {
        if (!warnedNoContextMenuHost) {
            warnedNoContextMenuHost = true
            Log.w("ContextMenu", "No ContextMenuHost mounted; a contextMenu modifier did nothing")
        }
        return@composed this
    }

    val layer = rememberGraphicsLayer()
    val source = remember(layer, highlight, liftState) { LiftSource(layer, highlight, liftState) }
    val menuRef by rememberUpdatedState(menu)
    val feedback = LocalFeedback.current

    DisposableEffect(source) {
        onDispose { source.disposed = true }
    }

    /**
     * Opens the lift for a touch at [posInRow] (row-local px). The menu is
     * built first; the haptic fires only when it opens (UIKit plays it when
     * the preview lifts, and nothing lifts without a menu).
     */
    fun open(posInRow: Offset): Boolean {
        if (host.isOpen) return false // one lift at a time
        if (source.disposed) return false
        val built = menuRef() ?: return false
        if (built.isEffectivelyEmpty()) return false
        feedback.haptic(Haptic.LongPress)
        return host.open(source, built, posInRow)
    }

    this
        .onGloballyPositioned { coords ->
            source.coords = coords
            // While lifted the overlay re-reads the geometry on every tick:
            // the preview follows the row (a layout change above it). Never
            // bump it unconditionally — that is an invalidation loop.
            if (source.lifted) source.geometryTick++
        }
        .drawWithContent {
            // Record-then-draw: the row always records into the layer, and
            // draws it only when not lifted — the overlay draws the layer
            // instead, so a streaming row updates inside the preview.
            // DrawScope's `GraphicsLayer.record(size) {}`, not the member
            // `record(density, …)`: the scope re-binds this draw node on every
            // replay. The overlay draws the layer from a DIFFERENT node, and a
            // software replay (Robolectric's snapshot path, software rendering)
            // runs the block outside this draw pass, where the member form's
            // captured `drawContent()` throws ("drawContent for a `null` node")
            // — the same bug as the handoff capture (round 34).
            layer.record(size.toIntSize()) {
                this@drawWithContent.drawContent()
            }
            if (!source.lifted) drawLayer(layer)
        }
        .then(
            // 129: optional — see the parameter docs. The gesture path
            // below never depends on this node existing.
            if (semanticsLongClick) {
                Modifier.semantics {
                    if (enabled) {
                        onLongClick(label = "Show menu") {
                            val coords = source.coords
                            if (coords == null) {
                                false
                            } else {
                                open(Offset(coords.size.width / 2f, coords.size.height / 2f))
                            }
                        }
                    }
                }
            } else {
                Modifier
            },
        )
        .pointerInput(enabled, host, swallowWithoutMenu) {
            if (enabled) detectContextPress(swallowWithoutMenu) { position -> open(position) }
        }
}

/**
 * The context-menu long press. Not `detectTapGestures(onLongPress)`: that
 * consumes the down and competes with the row's `clickable` (the #609
 * trap). This detector only **observes** until the system long-press
 * timeout ([PointerInputScope.viewConfiguration]'s
 * `longPressTimeoutMillis`, 400 ms default, user-adjustable) elapses with
 *
 *  - the finger still down,
 *  - a single pointer,
 *  - no movement past touch slop,
 *  - and no other gesture having consumed the pointer (scroll, swipe).
 *
 * When [onLongPress] returns true, the **rest of this touch is swallowed**
 * on the Initial pass, so the row's tap, the list's scroll and any swipe
 * never see it. When it returns false (no menu), the release flows on
 * untouched and the row's own tap still fires — unless [swallowWithoutMenu]
 * (ticket 46's menu-less section headers: the long press does nothing, only
 * a tap folds), which swallows the rest of the touch either way.
 */
private suspend fun PointerInputScope.detectContextPress(
    swallowWithoutMenu: Boolean,
    onLongPress: (Offset) -> Boolean,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final) // see what scroll/swipe did this frame
                if (event.changes.size > 1) return@withTimeoutOrNull true // second finger
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                // The down's own Final pass: the row's `clickable` (inner, so
                // it sees Main first) always consumes the down — that is a
                // tap starting, not a gesture claiming the pointer.
                if (change.changedToDownIgnoreConsumed()) continue
                if (!change.pressed || change.isConsumed) return@withTimeoutOrNull true // released early, or a drag claimed it
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    return@withTimeoutOrNull true // moved past slop: a scroll is starting
                }
            }
            @Suppress("UNREACHABLE_CODE") // the loop never exits; this pins the lambda's type
            true
        }
        if (cancelled != null) return@awaitEachGesture // null = the timeout elapsed = long press
        if (!onLongPress(down.position) && !swallowWithoutMenu) return@awaitEachGesture
        // Swallow the rest of this touch: no row tap, no list scroll, no swipe.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}
