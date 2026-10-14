package sh.roboco.android.design

import android.content.res.Configuration
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState

/**
 * The iOS action sheet (android-ui-parity #19): the twin of
 * `UIAlertController(preferredStyle: .actionSheet)` as UIKit draws it on
 * iPhone — a bottom-anchored stack of two glass cards, an action card
 * (optional title/message header + one full-width row per action, hairline
 * separators between them, a checkmark on the checked row) over a
 * **separate** Cancel card 8 dp below. Every action sheet in the app has
 * exactly one cancel action, and UIKit always pulls it out into that second
 * card, so the model has no cancel *row*: callers pass `cancelTitle` +
 * `onCancel` and the component draws the card itself.
 *
 * The look is system-drawn on iOS, so the geometry (8 dp margins, radius 26,
 * rows 56 dp, title Medium 13 / message Regular 13 / rows Regular 18 /
 * Cancel SemiBold 18, scrim black @0.2) is the parent epic's approximation of
 * the system chrome, ported in Geist; the numbers live in
 * [ActionSheetMetrics] and the pure functions of [ActionSheetLayout.kt].
 * Tint follows `window.tintColor = Palette.accent` (SceneDelegate.swift:15):
 * default and Cancel titles and the checkmark are accent, destructive titles
 * are danger. No haptics (no call site has a generator).
 *
 * This file owns the data model, the imperative presenter
 * ([ActionSheetPresenter] / [LocalActionSheet], like iOS `present(sheet)`),
 * the in-window host ([ActionSheetHost]) and the stateless visual
 * ([ActionSheetPanel]). No `Dialog`/`Popup` — Haze cannot blur across
 * windows — and no Material components anywhere.
 *
 * Callers own the copy; the component's one string of its own is the default
 * `cancelTitle` "Cancel" (verbatim iOS).
 */

// ---------------------------------------------------------------------------
// The model
// ---------------------------------------------------------------------------

/** `UIAlertAction.Style`, minus Cancel (the model has no cancel *row*). */
enum class ActionSheetStyle { Default, Destructive }

/**
 * One action row. [checked] is the port of UIKit's private KVC `"checked"`
 * key: a checkmark on the leading side of the row, the title staying
 * centred. [onSelect] runs **after** the sheet finished dismissing (UIKit
 * runs `UIAlertAction` handlers once the controller is gone), so a
 * navigation in the callback never animates under a sliding sheet.
 */
@Immutable
data class ActionSheetAction(
    val title: String,
    val style: ActionSheetStyle = ActionSheetStyle.Default,
    /** iOS's private `checked` KVC key on `UIAlertAction`. */
    val checked: Boolean = false,
    val onSelect: () -> Unit,
)

/** One action sheet: header, rows, and the separate Cancel card's copy + callback. */
@Immutable
data class ActionSheetModel(
    val title: String? = null,
    val message: String? = null,
    val actions: List<ActionSheetAction>,
    /** This component's one string of its own; callers may override it. */
    val cancelTitle: String = "Cancel",
    val onCancel: () -> Unit = {},
)

// ---------------------------------------------------------------------------
// The presenter
// ---------------------------------------------------------------------------

/**
 * Shows an action sheet imperatively, like iOS `present(sheet)`. Main
 * thread only: callers (tickets 43, 44, 97, 140) invoke it from click
 * handlers.
 */
interface ActionSheetPresenter {

    /** Presents [model]; ignored (Log.w) while another sheet is up. Main thread only. */
    fun show(model: ActionSheetModel)

    companion object {
        /** Default when no host is mounted (previews): logs and shows nothing. */
        val Noop: ActionSheetPresenter = object : ActionSheetPresenter {
            override fun show(model: ActionSheetModel) {
                Log.w(TAG, "No ActionSheetHost mounted; dropped: ${model.title}")
            }
        }
    }
}

private const val TAG = "ActionSheet"

/**
 * The state behind [ActionSheetHost]: at most one sheet ([current]) — a
 * `show` while one is up is refused with a warning, like iOS refusing to
 * present on a controller that is already presenting. [clear] runs only
 * from the overlay's exit, so a callback that shows a new sheet (or a
 * toast) runs with the host already empty.
 */
@Stable
class ActionSheetHostState : ActionSheetPresenter {

    /** The presented sheet, or `null` when none is up. */
    var current: ActionSheetModel? by mutableStateOf(null)
        private set

    override fun show(model: ActionSheetModel) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ActionSheetHostState.show is main-thread only (call it from a click handler)"
        }
        if (current != null) {
            Log.w(TAG, "A sheet is already presented; dropped: ${model.title}")
            return
        }
        current = model
    }

    /** Called by the overlay when its exit animation ended. */
    internal fun clear() {
        current = null
    }
}

/** The nearest host's presenter — the app root's [ActionSheetHost] (ticket 35) or [ActionSheetPresenter.Noop]. */
val LocalActionSheet = staticCompositionLocalOf<ActionSheetPresenter> { ActionSheetPresenter.Noop }

// ---------------------------------------------------------------------------
// The host
// ---------------------------------------------------------------------------

/**
 * The in-window action-sheet host (ticket 19): wraps a window's content,
 * provides [LocalActionSheet] and draws the scrim + cards **above** it,
 * owning back, hardware Escape, the scrim tap, the one-shot guard and the
 * present/dismiss animation. No `Dialog`/`Popup` window — Haze samples its
 * own window only, and the in-window host keeps the sheet below toasts.
 *
 * The wrapped content is this host's own `hazeSource` (the cards blur it);
 * screens inside keep their per-screen `ProvideHazeState` sources (ticket
 * 07's convention — a nested source is a *different* state). The overlay is
 * a **sibling** drawn after the content, never inside the source it blurs.
 * While a sheet is up the content is hidden from accessibility, so TalkBack
 * stays inside the sheet.
 *
 * Ticket 35 mounts this at the app root, innermost of the overlay hosts:
 * `AlertHost { ToastHost { ActionSheetHost(actionSheets) { … } } }` — a
 * toast raised from an action callback draws above the sheet, an alert
 * above both. This ticket mounts it in the gallery and tests only.
 */
@Composable
fun ActionSheetHost(
    state: ActionSheetHostState = remember { ActionSheetHostState() },
    content: @Composable () -> Unit,
) {
    val haze = rememberHazeState()
    CompositionLocalProvider(LocalActionSheet provides state) {
        Box(Modifier.fillMaxSize()) {
            // 1. The wrapped content: the window-level haze source, hidden
            //    from TalkBack while a sheet is up.
            val hideContent = state.current != null
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(haze)
                    .let { m -> if (hideContent) m.semantics { hideFromAccessibility() } else m },
            ) {
                content()
            }
            // 2. The sheet itself, keyed by the model: each presentation
            //    gets fresh animation state.
            state.current?.let { model ->
                key(model) {
                    ActionSheetOverlay(model = model, hazeState = haze, onGone = state::clear)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The overlay: one presentation, the animation, the interaction rules
// ---------------------------------------------------------------------------

/**
 * One sheet on screen. A single [Animatable] `progress` (0 hidden, 1 shown)
 * drives everything, so a dismiss started mid-present continues from the
 * current value: present `Motion.ActionSheetRise` (0.4 s critically damped,
 * no overshoot), dismiss 250 ms `Motion.actionSheetFall` (ease-in). The
 * cards slide **together** — they start fully below the window bottom, one
 * `graphicsLayer` translation, no relayout. Under Reduce Motion nothing
 * translates: cards and scrim cross-fade instead
 * (`Motion.actionSheetFade`), like iOS's reduced-motion alerts.
 *
 * [finishing] is the one-shot guard: after the first row / Cancel / scrim /
 * back / Escape, every further input is ignored until the sheet is gone —
 * an action can never fire twice. The scrim keeps consuming every touch
 * through the exit animation, so nothing underneath is reachable.
 *
 * Glass stays `visible = true` — fully formed from frame 0 (the cards
 * slide in already shaped, as on iOS); the scrim is **not** part of the
 * `hazeSource`, so the cards blur the content, not the scrim.
 */
@Composable
private fun ActionSheetOverlay(
    model: ActionSheetModel,
    hazeState: HazeState,
    onGone: () -> Unit,
) {
    val density = LocalDensity.current
    // navBottom = navigationBars ∪ displayCutout bottom; statusTop = statusBars ∪ displayCutout top.
    val navBottomPx = WindowInsets.navigationBars.union(WindowInsets.displayCutout).getBottom(density)
    val statusTopPx = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density)
    val navBottom = with(density) { navBottomPx.toDp().value }
    val statusTop = with(density) { statusTopPx.toDp().value }

    val progress = remember { Animatable(0f) }
    var finishing by remember { mutableStateOf(false) }
    var stackHeightPx by remember { mutableStateOf(0f) }
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    /**
     * The single exit path: ignore everything once started, sink the cards
     * (250 ms ease-in / a fade under Reduce Motion), remove the sheet,
     * **then** run the caller's callback — UIKit's handler-after-dismiss
     * order. `onGone()` before `then()`, so a callback that shows a new
     * sheet (or a toast) is not refused.
     */
    fun finish(then: () -> Unit) {
        if (finishing) return
        finishing = true
        scope.launch {
            progress.animateTo(0f, if (reduce) Motion.actionSheetFade() else Motion.actionSheetFall())
            onGone()
            then()
        }
    }

    LaunchedEffect(Unit) {
        // Hardware keys reach focused nodes only; the overlay root is it.
        focusRequester.requestFocus()
        progress.animateTo(1f, if (reduce) Motion.actionSheetFade() else Motion.ActionSheetRise.spec())
    }

    // Composed after the screens' handlers, so it wins while the sheet is up.
    BackHandler(enabled = !finishing) { finish(model.onCancel) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .testTag("action-sheet")
            .semantics {
                model.title?.let { paneTitle = it }
                isTraversalGroup = true
            }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                    finish(model.onCancel)
                    true
                } else {
                    false
                }
            },
    ) {
        val stackWidth = actionSheetWidth(maxWidth.value, maxHeight.value).dp
        val bottomGap = actionSheetBottomGap(navBottom).dp
        val bottomGapPx = with(density) { bottomGap.toPx() }
        val maxCardHeight = actionSheetMaxCardHeight(maxHeight.value, statusTop, navBottom).dp

        // The scrim: full window, black @0.2 × progress, unreachable by
        // TalkBack, consuming every touch (also through the exit).
        Scrim(progress) { finish(model.onCancel) }

        // The stack: Cancel card's bottom edge at window − max(navBottom, 8).
        // graphicsLayer (never offset): the slide never relayouts, and the
        // two cards move as one.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomGap)
                .width(stackWidth)
                .onSizeChanged { stackHeightPx = it.height.toFloat() }
                .graphicsLayer {
                    if (reduce) {
                        alpha = progress.value
                    } else {
                        translationY = (1f - progress.value) * (size.height + bottomGapPx)
                    }
                }
                // The cards' own background must not fall through to the scrim
                // below (children — rows, the rows' scroll — process the Main
                // pass first and are unaffected).
                .swallowPanelTaps(),
        ) {
            ActionSheetPanel(
                model = model,
                hazeState = hazeState,
                onAction = { index -> finish(model.actions[index].onSelect) },
                onCancel = { finish(model.onCancel) },
                maxCardHeight = maxCardHeight,
                enabled = !finishing,
            )
            ActionSheetOverlaySemantics(progress, reduce, { stackHeightPx }, bottomGapPx)
        }
    }
}

/**
 * The scrim: a full-window black @0.2 (its layer alpha is the present
 * progress), clear of semantics, and it swallows every pointer gesture —
 * nothing under the sheet can start a gesture, and taps keep being consumed
 * through the 250 ms exit. A tap (down + up outside the cards — the panel's
 * tap swallower has already consumed downs that land on the cards) fires
 * [onScrimTap].
 */
@Composable
private fun Scrim(progress: Animatable<Float, AnimationVector1D>, onScrimTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress.value }
            .background(rgba(0x000000, 0.2))
            .clearAndSetSemantics { }
            .testTag("action-sheet-scrim")
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val onScrim = !down.isConsumed
                    if (!down.isConsumed) down.consume()
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (!change.isConsumed) change.consume()
                        }
                        released = event.changes.all { !it.pressed }
                    }
                    if (onScrim) onScrimTap()
                }
            },
    )
}

/**
 * Makes the cards a hit target and consumes the **down** that lands on
 * them (Main pass, after the children): a tap on the header never reaches
 * the scrim's tap detector, which treats a consumed down as "inside the
 * cards".
 *
 * Downs only, never moves or ups. A row's `clickable` re-checks every
 * intermediate event on the Final pass and cancels its tap when an
 * ancestor consumed it (`waitForUpOrCancellation`), and a real finger's tap
 * carries a few ACTION_MOVEs between down and up. Consuming those here
 * dropped option taps on phones: the press showed and nothing happened
 * (user report on the 3bd01174 debug APK, the Wallpaper Effect sheet).
 * A still test tap has no moves, which is why no test saw it.
 */
private fun Modifier.swallowPanelTaps(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            for (change in event.changes) {
                if (change.pressed && !change.previousPressed && !change.isConsumed) change.consume()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Test-only progress keys (frame-0 / mid-animation assertions, ActionSheetHostTest)
// ---------------------------------------------------------------------------

/** Test-only: the present/dismiss progress of the current sheet (0…1). */
internal val ActionSheetProgressKey = SemanticsPropertyKey<Float>("ActionSheetProgress")

/** Test-only: the applied translationY (px) of the card stack — 0 under Reduce Motion. */
internal val ActionSheetOffsetKey = SemanticsPropertyKey<Float>("ActionSheetOffset")

/**
 * Exposes [ActionSheetProgressKey]/[ActionSheetOffsetKey] on a zero-size
 * sibling of the cards, recomposing alone while the animation runs — the
 * same isolation trick as `GlassSurface`'s progress keys. The offset mirrors
 * the overlay's graphicsLayer formula: `(1 − progress) × (stackHeight +
 * bottomGap)`, 0 when Reduce Motion drops the translation.
 */
@Composable
private fun ActionSheetOverlaySemantics(
    progress: Animatable<Float, AnimationVector1D>,
    reduce: Boolean,
    stackHeightPx: () -> Float,
    bottomGapPx: Float,
) {
    val p = progress.value
    val translation = if (reduce) 0f else (1f - p) * (stackHeightPx() + bottomGapPx)
    Box(
        Modifier.semantics {
            this[ActionSheetProgressKey] = p
            this[ActionSheetOffsetKey] = translation
        },
    )
}

// ---------------------------------------------------------------------------
// The panel: the stateless visual
// ---------------------------------------------------------------------------

/**
 * The stateless visual: the action card (header + rows, capped at
 * [maxCardHeight] — the header stays fixed, the rows scroll) over the
 * separate Cancel card, laid out bottom-up by the caller. No scrim, no
 * animation — the host owns those, so previews, the gallery, screenshots
 * and ticket 43 render it standalone.
 *
 * [hazeState] is provided to the cards' [GlassSurface] as `LocalHazeState`
 * (they sample the host's window-level source); `null` draws the fallback
 * fill (previews, screenshots). Ticket 43 calls this with named args
 * `model`, `hazeState`, `onAction`, `onCancel`.
 *
 * Row press: the row rect fills `palette.controlFill` (clipped to the card
 * shape by `GlassSurface`'s content clip, so the first/last rows get
 * rounded highlight corners). No scale, no ripple. Rows and the Cancel card
 * are disabled while [enabled] is false (the exit animation).
 */
@Composable
fun ActionSheetPanel(
    model: ActionSheetModel,
    hazeState: HazeState?,
    onAction: (index: Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    maxCardHeight: Dp = Dp.Infinity,
    enabled: Boolean = true,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val shape = rememberSmoothRoundedShape(ActionSheetMetrics.CardRadiusDp.dp)
    val hasHeader = model.title != null || model.message != null
    val anyChecked = model.actions.any { it.checked }
    val titleStyle = uiStyle(Face.SansMedium, typeScale.scaled(13f), palette.secondary)
        .copy(textAlign = TextAlign.Center)
    val messageStyle = uiStyle(Face.Sans, typeScale.scaled(13f), palette.secondary)
        .copy(textAlign = TextAlign.Center)
    val actionTitleStyle = uiStyle(Face.Sans, typeScale.scaled(18f))
    val cancelTitleStyle = uiStyle(Face.SansSemibold, typeScale.scaled(18f), palette.accent)

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(ActionSheetMetrics.CardGapDp.dp)) {
            // The action card. heightIn(max) caps it; the header stays put
            // and the rows scroll inside the remaining height.
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxCardHeight)
                    .testTag("action-sheet-card"),
                shape = shape,
                interactive = false,
                tint = null,
                visible = true,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    if (hasHeader) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = ActionSheetMetrics.HeaderPaddingHorizontalDp.dp,
                                    end = ActionSheetMetrics.HeaderPaddingHorizontalDp.dp,
                                    top = ActionSheetMetrics.HeaderPaddingVerticalDp.dp,
                                    bottom = ActionSheetMetrics.HeaderPaddingVerticalDp.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(
                                ActionSheetMetrics.HeaderTitleToMessageGapDp.dp,
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            model.title?.let { BasicText(it, style = titleStyle) }
                            model.message?.let { BasicText(it, style = messageStyle) }
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .testTag("action-sheet-rows"),
                    ) {
                        model.actions.forEachIndexed { index, action ->
                            ActionRow(
                                title = action.title,
                                titleColor = if (action.style == ActionSheetStyle.Destructive) {
                                    palette.danger
                                } else {
                                    palette.accent
                                },
                                titleStyle = actionTitleStyle,
                                tag = "action-sheet-row-$index",
                                onClick = { onAction(index) },
                                checked = action.checked,
                                separator = hasHeader || index > 0,
                                anyChecked = anyChecked,
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
            // The separate Cancel card — UIKit always pulls the cancel action
            // out of the list into this second card, whatever its position.
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                interactive = false,
                tint = null,
                visible = true,
            ) {
                ActionRow(
                    title = model.cancelTitle,
                    titleColor = palette.accent,
                    titleStyle = cancelTitleStyle,
                    tag = "action-sheet-cancel",
                    onClick = onCancel,
                    enabled = enabled,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One row (actions and the Cancel card's single row)
// ---------------------------------------------------------------------------

/**
 * One full-width row: min height 56 (exactly 56 at font factor 1.0, growing
 * with `scaled(18)` above), the title centred, one line with an ellipsis.
 * The pressed fill and the separator are `drawBehind` — the card's content
 * clip rounds the first/last row's highlight corners — and the separator
 * adds no height. The **44 dp** title padding applies to every row when any
 * row of the sheet is checked (keeps centred titles clear of the
 * checkmark); the Cancel row never has one, so it stays at 16.
 *
 * The checked row draws ticket 06's
 * `Icon(RobocoIcons.Checkmark, accent, 17 pt, Semibold)` in a 20.4 dp box
 * starting 16 dp from the card's start, vertically centred and outside the
 * title's padding; the row's `selected` semantics carries the meaning
 * (TalkBack reads "Selected").
 */
@Composable
private fun ActionRow(
    title: String,
    titleColor: Color,
    titleStyle: TextStyle,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
    separator: Boolean = false,
    anyChecked: Boolean = false,
    enabled: Boolean = true,
) {
    val palette = RobocoTheme.palette
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val controlFill = palette.controlFill
    val hairline = palette.hairline
    val horizontalPadding =
        (if (anyChecked) ActionSheetMetrics.RowCheckedTitlePaddingDp else ActionSheetMetrics.RowTitlePaddingDp).dp
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = ActionSheetMetrics.RowMinHeightDp.dp)
            .drawBehind {
                if (pressed) drawRect(controlFill)
                if (separator) drawRect(hairline, size = Size(size.width, 1f))
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = title,
                onClick = onClick,
            )
            .semantics { if (checked) selected = true }
            .testTag(tag),
    ) {
        BasicText(
            title,
            style = titleStyle.copy(color = titleColor, textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = ActionSheetMetrics.RowVerticalPaddingDp.dp,
                    bottom = ActionSheetMetrics.RowVerticalPaddingDp.dp,
                ),
        )
        if (checked) {
            Icon(
                RobocoIcons.Checkmark,
                tint = palette.accent,
                sizePt = ActionSheetMetrics.CheckmarkSizePt,
                weight = SymbolWeight.Semibold,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = ActionSheetMetrics.CheckmarkStartInsetDp.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The Wallpaper Effect fixture (previews, goldens, the gallery)
// ---------------------------------------------------------------------------

/**
 * The Wallpaper Effect labels — `ios:Design/Wallpaper.swift:49-60`
 * (`allEffects = [.none, .dither, .ascii, .halftone, .scanlines]`). Ticket
 * 97 owns the real call site; this list exists so this ticket's previews,
 * goldens and gallery section can show the checked-row fixture.
 */
internal val WallpaperEffectLabels = listOf("None", "Dither", "ASCII", "Halftone", "Scanlines")

/**
 * The Wallpaper Effect sheet fixture: title, one row per effect, the row at
 * [checked] marked checked (iOS's KVC `"checked"` on the current effect).
 */
internal fun wallpaperEffectSheet(
    checked: Int,
    onSelect: (index: Int) -> Unit,
    onCancel: () -> Unit = {},
): ActionSheetModel = ActionSheetModel(
    title = "Wallpaper Effect",
    actions = WallpaperEffectLabels.mapIndexed { index, label ->
        ActionSheetAction(title = label, checked = index == checked, onSelect = { onSelect(index) })
    },
    onCancel = onCancel,
)

// ---------------------------------------------------------------------------
// Previews (step 8): the Wallpaper Effect fixture, "Dither" checked
// ---------------------------------------------------------------------------

/**
 * The panel as the overlay places it (minus the animation): over the
 * gallery backdrop and the 0.2 scrim, bottom-anchored with the 8 dp gap, at
 * the geometry helpers' width for the preview's own window.
 */
@Composable
private fun ActionSheetPanelPreviewContent() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stackWidth = actionSheetWidth(maxWidth.value, maxHeight.value).dp
        val maxCardHeight = actionSheetMaxCardHeight(maxHeight.value, 0f, 0f).dp
        GalleryBackdrop(Modifier.matchParentSize())
        Box(Modifier.fillMaxSize().background(rgba(0x000000, 0.2)))
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = actionSheetBottomGap(0f).dp)
                .width(stackWidth),
        ) {
            ActionSheetPanel(
                model = wallpaperEffectSheet(checked = 1, onSelect = {}),
                hazeState = null,
                onAction = {},
                onCancel = {},
                maxCardHeight = maxCardHeight,
            )
        }
    }
}

@Preview(name = "ActionSheet light", widthDp = 393, heightDp = 852)
@Composable
private fun ActionSheetLightPreview() {
    RobocoTheme(Appearance.Light) { ActionSheetPanelPreviewContent() }
}

@Preview(name = "ActionSheet dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ActionSheetDarkPreview() {
    RobocoTheme(Appearance.Dark) { ActionSheetPanelPreviewContent() }
}
