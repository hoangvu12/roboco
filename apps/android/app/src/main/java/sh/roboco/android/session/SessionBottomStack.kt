package sh.roboco.android.session

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.EdgeFadeOverlay
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle

/**
 * The bottom stack's test tags (Android-only hooks, ticket 22's rule: the
 * tags live with the component that owns the node). The jump button keeps
 * ticket 131's own `jump-to-latest`.
 */
internal object BottomStackTags {
    /** The whole container: transcript, fade, stack and jump. */
    const val Stack = "bottom-stack"

    /** The bottom edge fade (ticket 10's overlay, placed by this container). */
    const val Fade = "bottom-fade"

    // Subcompose slot ids (internal plumbing, not test hooks).
    const val Body = "bottom-stack-body"
    const val Blocker = "bottom-stack-blocker"
    const val Jump = "bottom-stack-jump"

    /** One slot's box. */
    fun forSlot(slot: BottomStackSlot): String = when (slot) {
        BottomStackSlot.Limited -> "bottom-stack-limited"
        BottomStackSlot.Pill -> "bottom-stack-pill"
        BottomStackSlot.Queue -> "bottom-stack-queue"
        BottomStackSlot.Questions -> "bottom-stack-questions"
        BottomStackSlot.Composer -> "bottom-stack-composer"
    }
}

/**
 * The Session screen's bottom stack container (android-ui-parity #74) —
 * the port of `ios:Session/SessionViewController.swift:119-165, 367-379,
 * 492-521`. A vertical glass stack floats over the transcript's lower
 * edge and rides the keyboard; from top to bottom it holds the status
 * pill row, the queue panel, the question panel and the composer. Every
 * part is a **slot composable** supplied by its owner ticket (73 pill, 82
 * queue, 90/91 questions, 78 composer, 131 jump, 65/68 transcript), so
 * this file imports none of them and there is no dependency cycle.
 *
 * What the container owns:
 *
 * - **Order and visibility** — [BottomStackModel] decides which slots
 *   show; all four are always *composed* (glass state and content survive
 *   hide and show, like iOS keeping `questions` configured with the last
 *   items).
 * - **Width and placement** — `min(safeWidth − 24, 768)` centred on the
 *   safe-area centre, bottom at `max(ime, nav) − 8`, read in the measure
 *   block so every IME animation frame re-measures without recomposing.
 * - **The reflow** — a structural change (questions appear or vanish, the
 *   queue count changes, the banner flips) springs each slot's extent
 *   with [Motion.BottomStackReflow] (0.38 s, damping 0.86), interruptibly
 *   from the current value and velocity. Non-structural changes snap —
 *   except a slot already in flight, which retargets. The stack top, the
 *   jump anchor, the fade top and the transcript inset derive from the
 *   animated extents every frame.
 * - **The transcript inset** — subcomposed after `stackTop` is known, so
 *   the transcript slot receives [SessionBottomInsets] in the same frame
 *   the stack moved (never a frame behind the IME). [BottomStackState.insets]
 *   republishes it, guarded by the 0.5 dp threshold of `:373`.
 * - **The bottom fade** — ticket 10's [EdgeFadeOverlay] spans the window
 *   width from `stackTop − 44` down, inside the same `hazeSource` box as
 *   the transcript, under the stack and over the list.
 * - **The jump anchor** — the slot is placed at the stack's top-right,
 *   12 above and 16 in from the safe trailing edge, and rides the reflow.
 *
 * Touch routing follows UIKit hit-testing: the stack rect (including the
 * 8 dp gaps and the empty area right of the pill) never lets a touch
 * through to the transcript — a pointer-input blocker observes without
 * consuming, so the slots above it still get their taps. [stackAlpha] is
 * iOS `bottom.alpha` (75 passes the product of the push-pop and arrival
 * factors); it applies to the slots' layers **only** — never to the
 * transcript, the fade or the jump slot — and below 0.01 the slots also
 * stop being interactive and leave the semantics tree, while the stack
 * area keeps swallowing touches.
 *
 * No Material anywhere: no `Scaffold`, no `imePadding` (an ancestor
 * `imePadding` would shift the stack twice and shrink the transcript) —
 * the container reads the raw insets itself.
 *
 * @param modifier the caller passes `Modifier.fillMaxSize()`; never an
 *   alpha or graphicsLayer modifier (route those through [stackAlpha])
 * @param stackAlpha iOS `bottom.alpha`, read in placement only
 * @param hazeState the screen's haze state (ticket 07's convention: the
 *   container is the body source); `null` disables the source modifier
 */
@Composable
fun SessionBottomStack(
    state: BottomStackState,
    model: BottomStackModel,
    transcript: @Composable (SessionBottomInsets) -> Unit,
    limited: @Composable BottomStackSlotScope.() -> Unit = {},
    pill: @Composable BottomStackSlotScope.() -> Unit,
    queue: @Composable BottomStackSlotScope.() -> Unit,
    questions: @Composable BottomStackSlotScope.() -> Unit,
    composer: @Composable BottomStackSlotScope.() -> Unit,
    jump: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    stackAlpha: () -> Float = { 1f },
    hazeState: HazeState? = LocalHazeState.current,
) {
    val tracker = remember { ReflowTracker() }
    val motionScope = rememberCoroutineScope()
    val layoutDir = LocalLayoutDirection.current

    // The scopes are built here, in this composition, so the first
    // SideEffect's animateGlass flip recomposes exactly this body once —
    // the slots never see the flag flip mid-measure.
    val animateGlass = tracker.animateGlass
    val limitedScope = SlotScopeImpl(shown = model.limitedShown, glassVisible = model.limitedShown, animateGlass = animateGlass)
    val pillScope = SlotScopeImpl(shown = model.pillShown, glassVisible = true, animateGlass = animateGlass)
    val queueScope = SlotScopeImpl(shown = model.queueShown, glassVisible = model.queueCount > 0, animateGlass = animateGlass)
    val questionsScope = SlotScopeImpl(shown = model.questionsShown, glassVisible = model.questionsShown, animateGlass = animateGlass)
    val composerScope = SlotScopeImpl(shown = model.composerShown, glassVisible = true, animateGlass = animateGlass)

    // Structural tracking: computed without mutating; the SideEffect
    // commits before this frame's layout, so the measure pass sees the
    // new epoch in the same frame the model changed.
    val pendingStructural = tracker.last?.let { isStructural(it, model) } ?: false
    SideEffect { tracker.commit(model, pendingStructural) }

    // The subcompose contents are remembered so an animation frame —
    // which re-runs the measure block with the same lambdas — re-measures
    // and re-places only, never recomposing the slots. A scope change
    // (or the caller's fresher slot lambda, through rememberUpdatedState)
    // recomposes them.
    val limitedContent = rememberSlotContent(BottomStackSlot.Limited, limitedScope, stackAlpha, limited)
    val pillContent = rememberSlotContent(BottomStackSlot.Pill, pillScope, stackAlpha, pill)
    val queueContent = rememberSlotContent(BottomStackSlot.Queue, queueScope, stackAlpha, queue)
    val questionsContent = rememberSlotContent(BottomStackSlot.Questions, questionsScope, stackAlpha, questions)
    val composerContent = rememberSlotContent(BottomStackSlot.Composer, composerScope, stackAlpha, composer)
    val currentJump by rememberUpdatedState(jump)
    val jumpContent: @Composable () -> Unit = remember { { currentJump() } }
    val blockerContent: @Composable () -> Unit = remember { { StackBlocker() } }

    // The inset objects come from @Composable getters, so they are obtained
    // here in composition; their values are snapshot reads taken in the
    // measure block below (an IME frame re-measures, never recomposes).
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    // Wave-10 42 (#1014): beside the tablet sidebar, its toolbar's middle.
    val toolbarMid = LocalSidebarToolbarMid.current
    val safeInsets = WindowInsets.safeDrawing

    SubcomposeLayout(modifier.testTag(BottomStackTags.Stack)) { constraints ->
        check(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            "SessionBottomStack needs a bounded window (the caller passes Modifier.fillMaxSize())"
        }
        val W = constraints.maxWidth
        val H = constraints.maxHeight
        val windowWidth = W.toFloat()
        val windowHeight = H.toFloat()

        // The insets are read in the measure block: every IME animation
        // frame re-measures this layout only, with no recomposition.
        val imePx = state.imeOverride?.toPx() ?: imeInsets.getBottom(this).toFloat()
        val navPx = navInsets.getBottom(this).toFloat()
        val safeL = safeInsets.getLeft(this, layoutDir).toFloat()
        val safeR = safeInsets.getRight(this, layoutDir).toFloat()

        val stackWidthPx = BottomStackMetrics.stackWidth(
            windowWidth, safeL, safeR,
            side = BottomStackMetrics.SideInset.toPx(),
            maxW = BottomStackMetrics.MaxWidth.toPx(),
        )
        val stackX = BottomStackMetrics.stackX(windowWidth, safeL, safeR, stackWidthPx)
        val stackBottomPx = if (toolbarMid != null) {
            BottomStackMetrics.stackBottomBesideToolbar(
                windowHeight, imePx, toolbarMid.toPx(),
                restHeight = BottomStackMetrics.ComposerRestHeight.toPx(),
                gap = BottomStackMetrics.KeyboardGap.toPx(),
            )
        } else {
            BottomStackMetrics.stackBottom(
                windowHeight, imePx, navPx,
                gap = BottomStackMetrics.KeyboardGap.toPx(),
            )
        }
        val wPx = stackWidthPx.roundToInt()
        val xPx = stackX.roundToInt()

        // The five slots, subcomposed and measured at the stack width. The
        // pill hugs its own width up to w; the others are pinned to w.
        val limitedPlaceable = subcompose(BottomStackSlot.Limited, limitedContent).first()
            .measure(Constraints.fixedWidth(wPx))
        val pillPlaceable = subcompose(BottomStackSlot.Pill, pillContent).first()
            .measure(Constraints(minWidth = 0, maxWidth = wPx, minHeight = 0, maxHeight = Constraints.Infinity))
        val queuePlaceable = subcompose(BottomStackSlot.Queue, queueContent).first()
            .measure(Constraints.fixedWidth(wPx))
        val questionsPlaceable = subcompose(BottomStackSlot.Questions, questionsContent).first()
            .measure(Constraints.fixedWidth(wPx))
        val composerPlaceable = subcompose(BottomStackSlot.Composer, composerContent).first()
            .measure(Constraints.fixedWidth(wPx))

        // The pill row is at least the pill's pinned 30 dp height.
        val pillHeight = max(BottomStackMetrics.PillHeight.toPx(), pillPlaceable.height.toFloat())
        val limitedHeight = limitedPlaceable.height.toFloat()
        val queueHeight = queuePlaceable.height.toFloat()
        val questionsHeight = questionsPlaceable.height.toFloat()
        val composerHeight = composerPlaceable.height.toFloat()

        // Only the first measure after a structural commit animates.
        val animateWindow = tracker.epoch != tracker.lastMeasuredEpoch
        tracker.lastMeasuredEpoch = tracker.epoch

        // The slots in stack order, top to bottom (SessionViewController.swift:122).
        val slots = listOf(
            SlotPlacement(BottomStackSlot.Limited, limitedPlaceable, limitedHeight, model.limitedShown),
            SlotPlacement(BottomStackSlot.Pill, pillPlaceable, pillHeight, model.pillShown),
            SlotPlacement(BottomStackSlot.Queue, queuePlaceable, queueHeight, model.queueShown),
            SlotPlacement(BottomStackSlot.Questions, questionsPlaceable, questionsHeight, model.questionsShown),
            SlotPlacement(BottomStackSlot.Composer, composerPlaceable, composerHeight, model.composerShown),
        )

        val spacingPx = BottomStackMetrics.Spacing.toPx()
        for (entry in slots) {
            state.motions.getValue(entry.slot).update(
                BottomStackMetrics.slotTarget(entry.slot, entry.shown, entry.height, spacingPx),
                animateWindow,
                motionScope,
            )
        }
        var stackHeight = 0f
        for (entry in slots) stackHeight += state.motions.getValue(entry.slot).extent()
        val stackTop = stackBottomPx - stackHeight
        val coveredPx = BottomStackMetrics.covered(windowHeight, stackTop, gap = BottomStackMetrics.InsetPad.toPx())
        val contentInsetPx = BottomStackMetrics.contentInset(coveredPx, navPx)
        val insets = SessionBottomInsets(
            stackTop = stackTop.toDp(),
            covered = coveredPx.toDp(),
            contentInset = contentInsetPx.toDp(),
            navBottom = navPx.toDp(),
        )
        state.publish(insets)

        // The body: the transcript plus the bottom fade, subcomposed AFTER
        // stackTop is known so the transcript receives the inset in the
        // same frame the stack moved. The fade sits inside the same
        // hazeSource box as the transcript, so the glass slots blur the
        // faded content.
        val fadeTopPx = BottomStackMetrics.fadeTop(stackTop, above = BottomStackMetrics.FadeAbove.toPx())
        val bodyPlaceable = subcompose(BottomStackTags.Body) {
            BottomStackBody(insets = insets, fadeTopPx = fadeTopPx, hazeState = hazeState, transcript = transcript)
        }.first().measure(Constraints.fixed(W, H))

        // The blocker: the stack rect as a pointer-input node that
        // observes every event and never consumes — being hit-testable it
        // occludes the transcript behind it (UIKit's plain-UIView stack),
        // while the slots above it still receive their taps.
        val blockerPlaceable = subcompose(BottomStackTags.Blocker, blockerContent).first()
            .measure(Constraints.fixed(wPx, stackHeight.roundToInt().coerceAtLeast(0)))

        // The jump slot is the caller's composable and may emit no node at
        // all (a host without a jump button passes `{}`): an absent slot
        // simply places nothing.
        val jumpPlaceable = subcompose(BottomStackTags.Jump, jumpContent).firstOrNull()?.measure(Constraints())
        val jumpOrigin = BottomStackMetrics.jumpOrigin(
            windowWidth, safeR, stackTop,
            (jumpPlaceable?.width ?: 0).toFloat(), (jumpPlaceable?.height ?: 0).toFloat(),
            trailing = BottomStackMetrics.JumpTrailing.toPx(),
            above = BottomStackMetrics.JumpAbove.toPx(),
        )

        layout(W, H) {
            bodyPlaceable.place(0, 0)

            blockerPlaceable.placeWithLayer(xPx, stackTop.roundToInt()) {
                this.alpha = stackAlpha()
            }

            var y = stackTop
            for (entry in slots) {
                val motion = state.motions.getValue(entry.slot)
                val extent = motion.extent()
                if (extent > 0.5f || entry.shown) {
                    val gapPx = if (
                        entry.slot == BottomStackSlot.Limited ||
                        entry.slot == BottomStackSlot.Pill ||
                        entry.slot == BottomStackSlot.Queue
                    ) {
                        spacingPx
                    } else {
                        0f
                    }
                    entry.placeable.placeWithLayer(xPx, y.roundToInt()) {
                        this.alpha = stackAlpha()
                        // While collapsing or growing — either direction —
                        // the slot is drawn at its full measured height,
                        // top-anchored, clipped to the revealed part
                        // `extent × h / (h + gap)`; once settled the visible
                        // height equals the full height, so no clip is left
                        // on and the glass shadows are not cut.
                        val revealed = BottomStackMetrics.visibleHeight(extent, entry.height, gapPx)
                        if (revealed < entry.height - 0.5f) {
                            this.clip = true
                            this.shape = SlotClipShape(revealed)
                        }
                    }
                    if (entry.slot == BottomStackSlot.Composer) {
                        state.setComposerBounds(
                            Rect(
                                stackX, y,
                                stackX + entry.placeable.width.toFloat(),
                                y + entry.placeable.height.toFloat(),
                            ),
                        )
                    }
                }
                y += extent
            }

            jumpPlaceable?.place(jumpOrigin.first.roundToInt(), jumpOrigin.second.roundToInt())
        }
    }
}

// ---------------------------------------------------------------------------
// The slot plumbing: scope, box, hidden modifier, remembered contents
// ---------------------------------------------------------------------------

/** The [BottomStackSlotScope] handed to one slot, a plain value. */
@Immutable
private data class SlotScopeImpl(
    override val shown: Boolean,
    override val glassVisible: Boolean,
    override val animateGlass: Boolean,
) : BottomStackSlotScope

/**
 * Remembers one slot's subcompose content. Keyed on [scope], so a scope
 * change recomposes the slot; stable otherwise, so animation frames
 * (which re-run the measure block with the same lambda) never recompose
 * it. [stackAlpha] and [content] go through [rememberUpdatedState], so
 * the caller's fresher lambdas are always the ones composed.
 */
@Composable
private fun rememberSlotContent(
    slot: BottomStackSlot,
    scope: BottomStackSlotScope,
    stackAlpha: () -> Float,
    content: @Composable BottomStackSlotScope.() -> Unit,
): @Composable () -> Unit {
    val currentContent by rememberUpdatedState(content)
    val currentAlpha by rememberUpdatedState(stackAlpha)
    return remember(scope) {
        val boxed: @Composable () -> Unit = { SlotBox(slot, scope, currentAlpha, currentContent) }
        boxed
    }
}

/**
 * One slot's box: the test tag and the hidden-or-invisible treatment
 * wrap the owner's composable, so the whole slot area — including the
 * empty space right of the pill — is one node.
 */
@Composable
private fun SlotBox(
    slot: BottomStackSlot,
    scope: BottomStackSlotScope,
    stackAlpha: () -> Float,
    content: @Composable BottomStackSlotScope.() -> Unit,
) {
    Box(
        Modifier
            .testTag(BottomStackTags.forSlot(slot))
            .then(hiddenOrInvisibleModifier(scope.shown, stackAlpha)),
    ) {
        with(scope) { content() }
    }
}

/**
 * The modifier a slot gets while it is hidden (target hidden, still
 * collapsing) or while the whole stack is invisible
 * (`stackAlpha() < 0.01`, the UIKit alpha-0 rule): cleared from the
 * semantics tree and swallowing all pointer events at the Initial pass,
 * from the moment it becomes hidden — even while it is still drawn.
 */
@Composable
private fun hiddenOrInvisibleModifier(shown: Boolean, stackAlpha: () -> Float): Modifier {
    val currentAlpha by rememberUpdatedState(stackAlpha)
    // Only the threshold crossing recomposes; the alpha itself is read in
    // the derived calculation.
    val invisible by remember { derivedStateOf { currentAlpha() < 0.01f } }
    return if (!shown || invisible) {
        Modifier
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
    } else {
        Modifier
    }
}

/**
 * The touch blocker over the stack rect: observes every event, never
 * consumes (the slots above still get their taps; the transcript behind
 * is occluded by hit testing, not by consumption).
 */
@Composable
private fun StackBlocker() {
    Spacer(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                }
            }
        },
    )
}

/**
 * The body: the transcript plus the bottom fade, placed by a custom
 * layout so the fade's bounds are exactly `x 0..W, y stackTop − 44 .. H`
 * (`SessionViewController.swift:139-142`) — full window width, not the
 * safe area — with the transcript filling the window underneath. The
 * fade passes touches through (no pointer input, no semantics).
 */
@Composable
private fun BottomStackBody(
    insets: SessionBottomInsets,
    fadeTopPx: Float,
    hazeState: HazeState?,
    transcript: @Composable (SessionBottomInsets) -> Unit,
) {
    Layout(
        content = {
            transcript(insets)
            EdgeFadeOverlay(Modifier.testTag(BottomStackTags.Fade))
        },
        modifier = Modifier
            .fillMaxSize()
            .then(if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val transcriptPlaceable = measurables[0].measure(constraints)
        val top = fadeTopPx.roundToInt().coerceIn(0, height)
        val fadeHeight = (height - top).coerceAtLeast(0)
        val fadePlaceable = measurables[1].measure(Constraints.fixed(width, fadeHeight))
        layout(width, height) {
            transcriptPlaceable.place(0, 0)
            fadePlaceable.place(0, top)
        }
    }
}

/** One slot's measured data, in placement order. */
private class SlotPlacement(
    val slot: BottomStackSlot,
    val placeable: Placeable,
    val height: Float,
    val shown: Boolean,
)

/**
 * The top-anchored clip rectangle while a slot collapses or grows:
 * `visibleHeight` tall over the slot's full measured box, so the panel's
 * content stays put and its bottom edge rises with the extent.
 */
private class SlotClipShape(private val visibleHeightPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width, min(visibleHeightPx, size.height)))
}

// ---------------------------------------------------------------------------
// The fixture: stand-in slots, the striped transcript, the frame
// (previews, goldens and the gallery's toggles drive these)
// ---------------------------------------------------------------------------

/**
 * The transcript stand-in: `Palette.background` with alternating 10 dp
 * `Palette.text` / `Palette.accent` stripes, so the bottom fade and the
 * glass blur have something to fade and sample.
 */
@Composable
private fun StripeTranscript() {
    val palette = RobocoTheme.palette
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .drawBehind {
                val stripe = 10.dp.toPx()
                val colors = listOf(palette.text, palette.accent)
                var stripeTop = 0f
                var index = 0
                while (stripeTop < size.height) {
                    drawRect(
                        colors[index % 2],
                        topLeft = Offset(0f, stripeTop),
                        size = Size(size.width, minOf(stripe, size.height - stripeTop)),
                    )
                    stripeTop += stripe
                    index++
                }
            },
    )
}

/**
 * The queue stand-in: a 20 dp glass card with N rows of 40 dp and 4/4
 * padding, 10 dp side insets — one row is the ticket's 48 dp one-row
 * queue (`ios:Composer/QuestionPanel.swift:237-240, 270`). The glass
 * follows the slot scope: it stays materialized while the queue is
 * non-empty even when questions hide the slot (it only collapses).
 */
@Composable
private fun QueuePanelStandIn(rows: Int, glassVisible: Boolean, animateGlass: Boolean) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().height((8 + 40 * rows).dp),
        shape = Glass.shape(20.dp),
        visible = glassVisible,
        animateVisibility = animateGlass,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            repeat(rows) { index ->
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
                    BasicText(
                        "Queued message ${index + 1}",
                        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(15f), RobocoTheme.palette.text),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The questions stand-in: a 26 dp glass card, 220 dp tall. */
@Composable
private fun QuestionsPanelStandIn(glassVisible: Boolean, animateGlass: Boolean) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        shape = Glass.shape(26.dp),
        visible = glassVisible,
        animateVisibility = animateGlass,
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            BasicText("Pick an answer", style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text))
            Spacer(Modifier.height(8.dp))
            BasicText(
                "Which fix should land first?",
                style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.secondary),
            )
        }
    }
}

/** The composer stand-in: a 50 dp glass capsule (120 dp when tall). */
@Composable
private fun ComposerStandIn(tall: Boolean) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth().height(if (tall) 120.dp else 50.dp),
        shape = CapsuleShape,
    ) {
        Box(Modifier.fillMaxSize().padding(start = 18.dp), contentAlignment = Alignment.CenterStart) {
            BasicText("Message", style = uiStyle(Face.Sans, typeScale.fixed(16.5f), palette.tertiary))
        }
    }
}

/**
 * The whole stack over the striped transcript in a phone frame, with
 * glass stand-ins for the slots and ticket 08's jump circle — the
 * previews' case B, and the goldens' fixture (the Roborazzi tests pick
 * the model, the fake IME and the frame per case).
 */
@Composable
internal fun BottomStackStandIn(
    model: BottomStackModel = BottomStackModel(queueCount = 2, bannerShown = true),
    imeOverride: Dp? = null,
    stackAlpha: Float = 1f,
    composerTall: Boolean = false,
    frameWidth: Dp = 412.dp,
    frameHeight: Dp = 915.dp,
) {
    val state = rememberBottomStackState()
    // A write in composition, read in measure: the same value writes again
    // as a no-op (structural equality), a change invalidates the measure.
    state.imeOverride = imeOverride
    ProvideHazeState {
        Box(Modifier.size(frameWidth, frameHeight)) {
            StandInBottomStack(state, model, stackAlpha = { stackAlpha }, composerTall = composerTall)
        }
    }
}

/** The jump stand-in: ticket 08's 40 dp glass ↓ circle (131's stand-in). */
@Composable
private fun JumpStandIn() {
    GlassCircleButton(
        icon = RobocoIcons.ArrowDown,
        contentDescription = "Jump to latest",
        onClick = {},
        size = 40.dp,
        iconPt = 15f,
        ink = RobocoTheme.palette.text,
    )
}

/**
 * The shared stand-in wiring: the container with glass stand-ins for the
 * slots, over the striped transcript — the previews, the goldens and the
 * gallery all drive this. The pill mirrors production
 * (`shownBanner(chrome.banner, editing)`, banner on = Sending); the queue
 * and the questions panels follow the slot scopes' glass flags.
 */
@Composable
private fun StandInBottomStack(
    state: BottomStackState,
    model: BottomStackModel,
    stackAlpha: () -> Float,
    composerTall: Boolean,
) {
    SessionBottomStack(
        state = state,
        model = model,
        transcript = { StripeTranscript() },
        pill = {
            StatusPillRow(
                shownBanner(
                    if (model.bannerShown) SessionChrome.Banner.Sending else SessionChrome.Banner.None,
                    model.editing,
                ),
                onTap = {},
            )
        },
        queue = { QueuePanelStandIn(model.queueCount, glassVisible, animateGlass) },
        questions = { QuestionsPanelStandIn(glassVisible, animateGlass) },
        composer = { ComposerStandIn(composerTall) },
        jump = { JumpStandIn() },
        modifier = Modifier.fillMaxSize(),
        stackAlpha = stackAlpha,
    )
}

// ---------------------------------------------------------------------------
// The gallery section (ticket 22's registry, registered in Gallery.kt)
// ---------------------------------------------------------------------------

/** The stack-alpha cycle of the gallery toggle: 0 / 0.5 / 1. */
private val StackAlphaChoices = floatArrayOf(0f, 0.5f, 1f)

private fun stackOnOff(value: Boolean): String = if (value) "on" else "off"

/**
 * The "Bottom stack" gallery page (android-ui-parity #74): the stack over
 * the striped transcript in a fixed-height session window with live
 * toggles — Questions, Queue count (0–5), Banner, Editing, Fake IME
 * (0 / 300 dp), Stack alpha and Composer tall. Toggling Questions or the
 * queue count springs the reflow; toggling Editing snaps, exactly as the
 * structural rule says. Foundation `BasicText` and `Modifier.clickable`
 * rows only — no Material controls.
 *
 * Registered in `debug/…/design/Gallery.kt` as
 * `GallerySection("Bottom stack", layout = GalleryLayout.Fill, screenshotHeightDp = 915)`.
 */
@Composable
fun BottomStackGallerySection() {
    var questions by remember { mutableStateOf(false) }
    var queueCount by remember { mutableIntStateOf(2) }
    var banner by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf(false) }
    var imeOn by remember { mutableStateOf(false) }
    var alphaStep by remember { mutableIntStateOf(2) }
    var composerTall by remember { mutableStateOf(false) }

    val state = rememberBottomStackState()
    state.imeOverride = if (imeOn) 300.dp else null
    val model = BottomStackModel(
        questionsShown = questions,
        queueCount = queueCount,
        bannerShown = banner,
        editing = editing,
    )

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(560.dp)) {
            ProvideHazeState {
                StandInBottomStack(
                    state,
                    model,
                    stackAlpha = { StackAlphaChoices[alphaStep] },
                    composerTall = composerTall,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            StackToggleRow("Questions", stackOnOff(questions)) { questions = !questions }
            StackToggleRow("Queue count", queueCount.toString()) { queueCount = (queueCount + 1) % 6 }
            StackToggleRow("Banner", stackOnOff(banner)) { banner = !banner }
            StackToggleRow("Editing", stackOnOff(editing)) { editing = !editing }
            StackToggleRow("Fake IME", if (imeOn) "300 dp" else "0 dp") { imeOn = !imeOn }
            StackToggleRow("Stack alpha", StackAlphaChoices[alphaStep].toString()) {
                alphaStep = (alphaStep + 1) % StackAlphaChoices.size
            }
            StackToggleRow("Composer tall", if (composerTall) "120 dp" else "50 dp") { composerTall = !composerTall }
        }
    }
}

/** One gallery toggle row: label left, current value in accent right. */
@Composable
private fun StackToggleRow(label: String, value: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        Spacer(Modifier.weight(1f))
        BasicText(value, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent))
    }
}

// ---------------------------------------------------------------------------
// Previews (step 6): case B in light and dark
// ---------------------------------------------------------------------------

@Preview(name = "Bottom stack light")
@Composable
private fun BottomStackLightPreview() {
    RobocoTheme(Appearance.Light) { BottomStackStandIn() }
}

@Preview(name = "Bottom stack dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BottomStackDarkPreview() {
    RobocoTheme(Appearance.Dark) { BottomStackStandIn() }
}

/**
 * Wave-10 42 (upstream #1014): how far above the window bottom the tablet
 * sidebar toolbar's middle sits while the sidebar shows beside this
 * session (the split's detail pane provides it); `null` everywhere else —
 * the phone, a sheet, a hidden sidebar — where the composer keeps its
 * 8 dp rest above the nav inset.
 */
val LocalSidebarToolbarMid = androidx.compose.runtime.staticCompositionLocalOf<Dp?> { null }
