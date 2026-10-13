package sh.roboco.android.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import sh.roboco.android.core.Face
import sh.roboco.android.core.GeistMetrics
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The composer's text slot (android-ui-parity #57): **one**
 * [BasicTextField] for both the resting capsule and the card — never two
 * fields, never `key()`ed on the mode: the focused instance leaving the
 * composition drops the IME (the pr621 lesson). It owns everything about
 * the text: the Geist body style, the caret, the insets, the placeholder,
 * the slot height (minimum, growth, line limits, internal scrolling), the
 * height animations (180 ms tween while typing, the morph spring when the
 * mode flips), the #593 two-line resting preview, and the Ctrl+Enter send.
 *
 * The port of the text half of `ios:Composer/ComposerBar.swift`:
 *
 * - **height** — [ComposerTextMetrics.targetHeight] over the **measured**
 *   line height (`getLineBottom(0) − getLineTop(0)`); the field is
 *   measured at the target height and an outer clip box animates its own
 *   height to it (UIKit: the model frame jumps, only the presentation
 *   animates — and it keeps Compose's internal scroller from shifting the
 *   text toward the caret during a 180 ms growth);
 * - **preview** — in the capsule a `BasicText(maxLines = 2, Ellipsis)`
 *   copy is drawn over the field's own (alpha-0) text layer: whole lines
 *   ending in `…`, never a scrolled view whose third line bleeds into the
 *   bottom inset (#593). Compose's `BasicTextField` cannot ellipsize, so
 *   the field stays mounted and focusable underneath;
 * - **keys** — `onPreviewKeyEvent` consumes Ctrl+Enter / Ctrl+NumPadEnter
 *   exactly (⌘↩ with an exact modifier match, `:667-674`): the first
 *   `KeyDown` only, always consumed (never a newline), acting through
 *   [ComposerController.keyboardSend] — which does nothing without
 *   content, so it can never trigger Stop.
 *
 * Placed by ticket 55's `ComposerSurface` layout in its `text` slot: the
 * x/width come from 55 (the target mode's width, COMPOSER-D1), and the
 * measured height is the slot height. 56 (or the gallery) supplies
 * `isCard` — the boolean target, not a progress value.
 *
 * Not here: the "IME hidden while focused → blur" rule (56's `imeBlurStep`
 * owns it — this file adds no IME observer), the morph itself, and 53's
 * mention transformation is only installed here, not defined.
 *
 * @param controller 54: the value, placeholder, mention index and
 *   `keyboardSend()`
 * @param isCard 56's `isCard` — the **target** mode (iOS flips `isCard`
 *   at once, then animates)
 * @param focusRequester 56's `morph.focusRequester` in production (78
 *   passes it); a local one in the gallery/tests
 * @param modifier applied to the outer slot box — 78 passes
 *   `Modifier.composerInput(morph)`; focus requests and focus events pass
 *   through to the field, the only focus target inside
 * @param onFocusChange optional focus hook; production focus tracking is
 *   56's `composerInput` (this only de-duplicates attach-time events)
 * @param onHeightChange iOS `onHeightChange` — fired when the target
 *   moves more than 0.5 dp. No production consumer (78 DROPs it); the
 *   gallery/tests count it
 * @param textHidden 95: hide the text layer for the handoff capture
 *   (iOS `textView.alpha = 0`)
 * @param onTextRect 95: the text rect in root coordinates — the slot
 *   inset by the top/bottom insets, height at least 20 dp
 */
@Composable
fun ComposerTextField(
    controller: ComposerController,
    isCard: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    onHeightChange: () -> Unit = {},
    textHidden: Boolean = false,
    onTextRect: ((Rect) -> Unit)? = null,
) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    val density = LocalDensity.current

    // Fonts.ui(.sans, UIFontMetrics(.body).scaledValue(for: 16.5)):
    // body-scaled, unclamped and unrounded — NOT TypeScale.scaled (which
    // clamps and rounds to 17), not 16.5.sp. All three text pieces (field,
    // placeholder, preview) share one style, plus UIKit's greedy
    // .byWordWrapping so they break at the same places.
    val size = typeScale.bodyScaled(16.5f)
    val base = uiStyle(Face.Sans, size).copy(lineBreak = LineBreak.Simple, hyphens = Hyphens.None)
    val textStyle = base.copy(color = palette.text)
    val placeholderStyle = base.copy(color = palette.tertiary)
    // Only used before the first layout: Geist's hhea line height.
    val fallbackLineDp = with(density) { size.toDp().value } * GeistMetrics.LINE_HEIGHT

    val text = controller.value.text
    val preview = !isCard
    val showPreview = preview && text.isNotEmpty()
    // iOS reads the window's vertical size class; Android's compact-height
    // class boundary is screenHeightDp < 480.
    val compactHeight = LocalConfiguration.current.screenHeightDp < ComposerTextMetrics.CompactHeightBelowDp
    // De-duplicates onFocusChange: onFocusChanged also fires once with
    // false at attach.
    var focused by remember { mutableStateOf(false) }

    // The legacy field lays the whole (transformed) text out at the inner
    // width with unbounded height, so this is the FULL content height.
    // (Named textLayout: a `layout` state would shadow MeasureScope.layout
    // inside the slot's measure lambda below.)
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // 53: the @file accent styling, installed (not defined) here.
    val transformation = rememberMentionTransformation(controller.mentions, controller.value.composition)
    val previewText = remember(text, transformation) { transformation.filter(AnnotatedString(text)).text }

    // ── the height math (recomputeTextHeight, :531-549) ──────────────────
    // L is measured, not 1.3 × size: Android lines are whole pixels.
    val lineDp = textLayout?.let { l ->
        with(density) { (l.getLineBottom(0) - l.getLineTop(0)).toDp().value }
    } ?: fallbackLineDp
    val contentH = textLayout?.let { l ->
        // The preview height is the first min(lineCount, 2) whole lines.
        val lastPreviewLine = (min(l.lineCount, ComposerTextMetrics.PreviewLines) - 1).coerceAtLeast(0)
        val px = if (preview) l.getLineBottom(lastPreviewLine) else l.size.height.toFloat()
        with(density) { px.toDp().value }
    } ?: lineDp
    val target = ComposerTextMetrics.targetHeight(isCard, compactHeight, lineDp, contentH)

    // ── the height animator ──────────────────────────────────────────────
    val height = remember { Animatable(target) }
    val last = remember { HeightLast(isCard, text, controller.alwaysCard) }
    val cardNow = rememberUpdatedState(isCard)
    val textNow = rememberUpdatedState(text)
    val alwaysNow = rememberUpdatedState(controller.alwaysCard)
    val reduceNow = rememberUpdatedState(LocalReduceMotion.current)
    val notifyNow = rememberUpdatedState(onHeightChange)
    LaunchedEffect(target) {
        // iOS `before` — the previous target constant, not the value.
        val prev = height.targetValue
        val alwaysChanged = alwaysNow.value != last.alwaysCard
        val cause = when {
            cardNow.value != last.isCard -> HeightCause.Morph
            textNow.value != last.text -> HeightCause.TextEdit
            else -> HeightCause.Reflow
        }
        last.isCard = cardNow.value
        last.text = textNow.value
        last.alwaysCard = alwaysNow.value
        if (!last.initialized) {
            // First composition (the draft set before the window): snap,
            // no animation, no onHeightChange (:101).
            height.snapTo(target)
            last.initialized = true
            return@LaunchedEffect
        }
        if (abs(target - prev) > ComposerTextMetrics.AnimateThresholdDp) notifyNow.value()
        when (cause) {
            HeightCause.Morph ->
                // The mode flipped: ride the morph spring, unless Reduce
                // Motion is on (iOS gates updateMode, :332-333) or the
                // flip came from alwaysCard itself (chipsAlwaysVisible's
                // didSet passes animated: false, :78).
                if (reduceNow.value || alwaysChanged) {
                    height.snapTo(target)
                } else {
                    val spec: AnimationSpec<Float> = Motion.ComposerMorph.spec()
                    last.spec = spec
                    height.animateTo(target, spec)
                }
            HeightCause.TextEdit ->
                // textChanged (:556): animate only past the 0.5 dp
                // threshold, else snap. NOT gated by Reduce Motion (iOS
                // never gates this one; ticket 04).
                if (abs(target - prev) > ComposerTextMetrics.AnimateThresholdDp) {
                    val spec: AnimationSpec<Float> = Motion.composerTextHeight()
                    last.spec = spec
                    height.animateTo(target, spec)
                } else {
                    height.snapTo(target)
                }
            HeightCause.Reflow -> {
                // Width / font scale changed the wrap: retarget a running
                // animation with its own spec; otherwise snap (:565-570).
                val spec = last.spec
                if (height.isRunning && spec != null) {
                    height.animateTo(target, spec)
                } else {
                    height.snapTo(target)
                }
            }
        }
    }

    // iOS ⌘↩ (exact modifier match). KeyDown with repeatCount == 0 acts;
    // KeyDown repeats and KeyUp are consumed silently so holding the keys
    // cannot re-send; everything else (plain Enter, Shift+Enter, …) falls
    // through to the field's newline.
    fun handleKey(event: KeyEvent): Boolean {
        if (!ComposerTextMetrics.isCtrlEnter(
                event.key,
                event.isCtrlPressed,
                event.isShiftPressed,
                event.isAltPressed,
                event.isMetaPressed,
            )
        ) {
            return false
        }
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
            controller.keyboardSend()
        }
        return true
    }

    // The slot box: the field is measured at the TARGET height, the box's
    // own height is the ANIMATED value (clipped). Reading height.value in
    // the measure lambda makes the tween and the spring relayout only.
    Box(
        modifier
            .testTag("composer-text-slot")
            .clipToBounds()
            .layout { m, c ->
                val targetPx = target.dp.roundToPx()
                val placeable = m.measure(Constraints.fixed(c.maxWidth, targetPx))
                layout(placeable.width, c.constrainHeight(height.value.dp.roundToPx())) {
                    placeable.place(0, 0)
                }
            }
            .onGloballyPositioned { coords ->
                if (onTextRect != null) onTextRect(coords.composerTextRect(density, isCard))
            },
    ) {
        BasicTextField(
            value = controller.value,
            // The whole TextFieldValue, composition region included:
            // stripping it breaks Gboard and 53's composition-aware styling.
            onValueChange = { controller.value = it },
            modifier = Modifier
                .fillMaxSize()
                .testTag("composer-input")
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused != focused) {
                        focused = it.isFocused
                        onFocusChange(it.isFocused)
                    }
                }
                .onPreviewKeyEvent(::handleKey)
                // TalkBack reads the text once there is any; while empty
                // the placeholder stands in ("Message, edit box").
                .semantics { if (text.isEmpty()) contentDescription = controller.placeholder },
            textStyle = textStyle,
            // Palette.accent — the caret and the selection handles
            // (tintColor, :167); the selection wash comes from
            // RobocoTheme's LocalTextSelectionColors.
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = ComposerKeyboardOptions,
            singleLine = false,
            // The height is this ticket's layout, never the field's
            // maxLines (the pr609 trap).
            maxLines = Int.MAX_VALUE,
            minLines = 1,
            visualTransformation = transformation,
            onTextLayout = { textLayout = it },
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize()) {
                    if (text.isEmpty()) {
                        // placeholderLabel: tertiary, one line, tail-…,
                        // never interactive. Hidden by the RAW text: a
                        // space or IME composition counts (:551).
                        BasicText(
                            controller.placeholder,
                            Modifier
                                .padding(top = ComposerTextMetrics.InsetTopDp.dp)
                                .clearAndSetSemantics { },
                            style = placeholderStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // The field's own text layer, inset like
                    // textContainerInset; alpha 0 under the preview (the
                    // field stays focusable — the preview overlay taps
                    // through to the hidden full layout).
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                top = ComposerTextMetrics.InsetTopDp.dp,
                                bottom = ComposerTextMetrics.insetBottom(isCard).dp,
                            )
                            .graphicsLayer { this.alpha = if (showPreview || textHidden) 0f else 1f },
                    ) {
                        inner()
                    }
                    if (showPreview && !textHidden) {
                        // The #593 resting preview: at most two whole
                        // lines ending in "…", over the hidden field, in
                        // the same style and the same transformation.
                        // TalkBack must not read the truncated copy.
                        BasicText(
                            previewText,
                            Modifier
                                .padding(top = ComposerTextMetrics.InsetTopDp.dp)
                                .clearAndSetSemantics {
                                    this[SemanticsProperties.TestTag] = "composer-input-preview"
                                },
                            style = textStyle,
                            maxLines = ComposerTextMetrics.PreviewLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        )
    }
}

/**
 * The UIKit keyboard defaults `UITextView` ships with (`:163-181` sets no
 * traits): sentence capitalization, autocorrect on, the plain keyboard,
 * the default (newline) return key. Nothing on the soft keyboard sends.
 */
private val ComposerKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.Sentences,
    autoCorrectEnabled = true,
    keyboardType = KeyboardType.Text,
    imeAction = ImeAction.Default,
)

/** Why the height target moved — which animation owns the retarget. */
private enum class HeightCause { Morph, TextEdit, Reflow }

/**
 * The plain (non-snapshot) record of the last `(isCard, text, alwaysCard)`
 * the height effect saw, plus its bookkeeping: whether the first
 * composition has been absorbed and which spec a running animation uses
 * (a reflow retargets a running animation with its own spec).
 */
private class HeightLast(
    var isCard: Boolean,
    var text: String,
    var alwaysCard: Boolean,
) {
    var initialized = false
    var spec: AnimationSpec<Float>? = null
}

/**
 * The text rect for ticket 95's handoff (DraftHandoff.swift:24-30): the
 * slot's bounds inset by the top/bottom insets, height at least 20 dp.
 */
private fun androidx.compose.ui.layout.LayoutCoordinates.composerTextRect(density: Density, isCard: Boolean): Rect {
    val bounds = boundsInRoot()
    val topPx = with(density) { ComposerTextMetrics.InsetTopDp.dp.toPx() }
    val bottomPx = with(density) { ComposerTextMetrics.insetBottom(isCard).dp.toPx() }
    val minHeightPx = with(density) { 20.dp.toPx() }
    val top = bounds.top + topPx
    val height = max(minHeightPx, bounds.height - topPx - bottomPx)
    return Rect(bounds.left, top, bounds.right, top + height)
}
