package sh.roboco.android.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The iOS 26 `UISwitch` port (android-ui-parity #98): the settings-row
 * toggle. Every measurement comes from `2-settings.png` re-measured for
 * this ticket (1 pt = 1.1443 px): a 63 × 28 dp capsule track, a 37 × 24 dp
 * capsule knob (not a circle — the iOS 26 knob), 2 dp inset, 22 dp travel,
 * `Palette.accent` on-tint and the system switch grey off-track. The only
 * `UISwitch` upstream ever creates is `MoreViewController.swift:72-80`
 * (stock control, `onTintColor = Palette.accent`,
 * `accessibilityIdentifier = "toggle-\(row.id)"`, `.valueChanged`).
 *
 * Motion is the Liquid-Glass-knob "lift" that replaced the iOS 7–18 knob
 * stretch: while the finger is down the knob becomes an enlarged
 * translucent lens (scale + translucency + rim + soft shadow), and it
 * springs back on release. ★ values are tuning constants, not
 * measurements — see the ticket's Comments.
 *
 * No Material: no `Switch`, no ripple, no
 * `minimumInteractiveComponentSize` (Compose's pointer hit testing already
 * extends the 63 × 28 layout to the 48 dp minimum touch target).
 */
object RobocoSwitchTokens {

    /** The track's width (`2-settings.png`: 71.98 px / 1.1443 = 62.9 dp). */
    val Width = 63.dp

    /** The track's height (27.94 dp), so a capsule of radius 14. */
    val Height = 28.dp

    /** The knob's width (36.9 dp) — a horizontal capsule, radius 12. */
    val ThumbWidth = 37.dp

    /** The knob's height (23.96 dp). */
    val ThumbHeight = 24.dp

    /** The knob's inset from the track's top, bottom and near end (2.0 dp measured). */
    val ThumbInset = 2.dp

    /** `Width − 2·ThumbInset − ThumbWidth` = 22 dp of slide. */
    val Travel = 22.dp

    /** Thumb slide and track cross-fade: 0.35 s, ζ 0.85 (settle-matched 539.14). ★ */
    val Slide = IosSpring("switchSlide", 0.35, 0.85f)

    /** The lens growing in/out: 0.25 s critically damped (1364.09). ★ */
    val Lift = IosSpring("switchLift", 0.25, 1f)

    /** The lifted knob's scale about its own centre → 51.8 × 33.6 dp, overhanging the track. ★ */
    const val LiftScale = 1.4f

    /** The lens's translucency at full lift (the grey track shows through). ★ */
    const val LiftedThumbAlpha = 0.35f

    /** The lens's rim stroke (white, alpha = lift). ★ */
    val LiftRim = 1.dp

    /** The lens's soft shadow alpha at full lift. ★ */
    const val LiftShadowAlpha = 0.12f

    /** The lens's shadow blur radius. ★ */
    val LiftShadowRadius = 6.dp

    /** The lens's shadow y offset. ★ */
    val LiftShadowDy = 1.dp

    /**
     * The system switch OFF track, both modes: `rgba(0x787880, 0.42)` =
     * `0x6B787880`, compositing to `#C6C6CA` on light `elevated` and
     * `#3C3C41` on dark. Much darker than `controlFill`, matching the
     * measured `#C7C7C9` plateau. The dark difference comes from the card
     * behind it, so one value serves both appearances.
     */
    val OffTrack: Color = rgba(0x787880, 0.42)
}

/**
 * `accessibilityIdentifier = "toggle-\(row.id)"`
 * (`MoreViewController.swift:76`) — the switch's test tag. Rows that use
 * it: `notify:enabled`, `notify:done`, `notify:input`, `notify:failed` and
 * `groupByProject`.
 */
fun toggleTag(id: String): String = "toggle-$id"

/**
 * The switch's position math (pure, unit-tested at density 1; dp → px
 * happens at the call sites).
 */
internal object SwitchMath {

    /**
     * The knob's leading edge in px for [fraction] 0..1 (0 = OFF, 1 = ON):
     * `inset + f·travel` in LTR; `width − inset − thumb − f·travel` in RTL,
     * because iOS mirrors `UISwitch` (ON on the left).
     */
    fun thumbLeft(fraction: Float, widthPx: Float, insetPx: Float, thumbWidthPx: Float, rtl: Boolean): Float {
        val travel = widthPx - 2f * insetPx - thumbWidthPx
        val f = fraction.coerceIn(0f, 1f)
        return if (!rtl) insetPx + f * travel else widthPx - insetPx - thumbWidthPx - f * travel
    }

    /**
     * The new drag fraction, clamped to [0, 1]. [dxPx] is the pointer's
     * movement since the last drag step; it is negated in RTL, where
     * dragging left turns the switch on.
     */
    fun dragFraction(current: Float, dxPx: Float, travelPx: Float, rtl: Boolean): Float =
        (current + (if (rtl) -dxPx else dxPx) / travelPx).coerceIn(0f, 1f)

    /** A released drag commits to ON at (and past) the halfway point. */
    fun releaseValue(fraction: Float): Boolean = fraction >= 0.5f
}

/**
 * The stateless painter: the 63 × 28 dp track and the knob at
 * [fraction] (0 = OFF, 1 = ON) and [lift] (0 = resting, 1 = full lens).
 * Both lambdas are read **only in the draw phase**, so animating them
 * never recomposes — the switch redraws per frame. The lifted lens draws
 * outside the 63 × 28 bounds on purpose (no clip): the layout stays 63 × 28.
 */
@Composable
fun RobocoSwitchVisual(fraction: () -> Float, lift: () -> Float, modifier: Modifier = Modifier) {
    val on = RobocoTheme.palette.accent
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // One Paint, mutated per draw: the lens shadow needs a real
    // setShadowLayer, which Compose's DrawScope cannot express.
    val shadowPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Spacer(
        modifier
            .size(RobocoSwitchTokens.Width, RobocoSwitchTokens.Height)
            .drawBehind {
                val f = fraction().coerceIn(0f, 1f)
                val lens = lift().coerceIn(0f, 1f)
                // The track colour follows the thumb: OFF grey ↔ accent.
                drawRoundRect(lerp(RobocoSwitchTokens.OffTrack, on, f), cornerRadius = CornerRadius(size.height / 2f))
                val tw = RobocoSwitchTokens.ThumbWidth.toPx()
                val th = RobocoSwitchTokens.ThumbHeight.toPx()
                val left = SwitchMath.thumbLeft(f, size.width, RobocoSwitchTokens.ThumbInset.toPx(), tw, rtl)
                // The lens scales about the resting knob's centre.
                val s = 1f + (RobocoSwitchTokens.LiftScale - 1f) * lens
                val w = tw * s
                val h = th * s
                val cx = left + tw / 2f
                val cy = size.height / 2f
                val rect = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                val thumbAlpha = 1f - (1f - RobocoSwitchTokens.LiftedThumbAlpha) * lens
                if (lens < 0.001f) {
                    // At rest: opaque white, no rim, no shadow (measured).
                    drawRoundRect(Color.White, rect.topLeft, rect.size, CornerRadius(h / 2f))
                } else {
                    // The lens: translucent white fill + its shadow through
                    // a native paint, then the white rim at alpha = lens.
                    drawIntoCanvas { canvas ->
                        shadowPaint.color = Color.White.copy(alpha = thumbAlpha).toArgb()
                        shadowPaint.setShadowLayer(
                            RobocoSwitchTokens.LiftShadowRadius.toPx(),
                            0f,
                            RobocoSwitchTokens.LiftShadowDy.toPx(),
                            Color.Black.copy(alpha = RobocoSwitchTokens.LiftShadowAlpha * lens).toArgb(),
                        )
                        canvas.nativeCanvas.drawRoundRect(
                            rect.left, rect.top, rect.right, rect.bottom, h / 2f, h / 2f, shadowPaint,
                        )
                    }
                    drawRoundRect(
                        Color.White,
                        rect.topLeft,
                        rect.size,
                        CornerRadius(h / 2f),
                        alpha = lens,
                        style = Stroke(RobocoSwitchTokens.LiftRim.toPx()),
                    )
                }
            },
    )
}

/**
 * The controlled settings-row switch: it draws [checked] and *requests*
 * changes through [onCheckedChange]. `UISwitch` flips itself and reports
 * via `.valueChanged`; here the caller owns the value, so it can refuse a
 * change and the thumb springs back (ticket 99's denied-permission path).
 *
 * Semantics are set by hand — `Modifier.toggleable` would add its own
 * click gesture and compete with the drag. TalkBack reads
 * "Switch, On/Off"; inside a ticket 70 row the merged node reads the row
 * title and subtitle first, and a double-tap triggers this `onClick`.
 *
 * @param testTag the caller's [toggleTag]; `null` for untagged uses
 * @param enabled `false` refuses every flip silently — no haptic, no
 *   callback, the thumb springs back (wave-10 40's locked agent rows)
 */
@Composable
fun RobocoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    enabled: Boolean = true,
) {
    val reduce = LocalReduceMotion.current
    val feedback = LocalFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestChecked by rememberUpdatedState(checked)
    val latestOnChange by rememberUpdatedState(onCheckedChange)
    val latestEnabled by rememberUpdatedState(enabled)
    val fraction = remember { Animatable(if (checked) 1f else 0f) }
    val lift = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Bumped on every drag release: a drag whose `dragging = true` never
    // reached a recomposition leaves the slide keys unchanged on release
    // (false → true → false), and a refused value would leave the thumb
    // parked at the drag's end fraction. The counter forces the spring-back.
    var releases by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * A user-driven value change: the haptic fires *before* the callback
     * (the feel is not delayed by the caller's work), and only for a real
     * change — `.valueChanged` never fires for a programmatic `isOn` write.
     */
    fun commit(value: Boolean) {
        if (value == latestChecked || !latestEnabled) return
        feedback.haptic(if (value) Haptic.ToggleOn else Haptic.ToggleOff)
        latestOnChange(value)
    }

    // The slide. Relaunches on every checked/dragging/reduce change and
    // every drag release;
    // relaunching cancels a running slide, and Animatable.animateTo
    // continues from the current value and velocity — never snapTo first.
    // While dragging, the thumb follows the finger instead.
    LaunchedEffect(checked, dragging, reduce, releases) {
        if (dragging) return@LaunchedEffect
        val target = if (checked) 1f else 0f
        if (reduce) fraction.snapTo(target) else fraction.animateTo(target, RobocoSwitchTokens.Slide.spec())
    }

    // The lens: in while the finger is down (no lift under reduce motion),
    // out on release or cancel.
    LaunchedEffect(pressed, reduce) {
        val target = if (pressed && !reduce) 1f else 0f
        if (reduce) lift.snapTo(target) else lift.animateTo(target, RobocoSwitchTokens.Lift.spec())
    }

    RobocoSwitchVisual(
        fraction = { if (dragging) dragFraction else fraction.value },
        lift = { lift.value },
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                onClick { commit(!latestChecked); true }
            }
            .pointerInput(rtl) {
                val travelPx = RobocoSwitchTokens.Travel.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Never consume the down: the LazyColumn above must be
                    // able to start a vertical scroll from this touch.
                    pressed = true
                    try {
                        var over = 0f
                        val slopChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            change.consume()
                            over = overSlop
                        }
                        if (slopChange == null) {
                            // No horizontal drag: either an up (a tap, if it
                            // was not consumed by a parent) or a cancel (the
                            // parent scroll took the gesture) — nothing else.
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up != null && up.changedToUp()) {
                                up.consume()
                                commit(!latestChecked)
                            }
                            return@awaitEachGesture
                        }
                        // The drag: seed the fraction from the visual thumb,
                        // then follow the finger 1:1 (direct manipulation).
                        dragFraction = SwitchMath.dragFraction(fraction.value, over, travelPx, rtl)
                        dragging = true
                        val completed = horizontalDrag(slopChange.id) { change ->
                            dragFraction = SwitchMath.dragFraction(dragFraction, change.positionChange().x, travelPx, rtl)
                            change.consume()
                        }
                        val end = dragFraction
                        // AwaitPointerEventScope is restricted-suspension:
                        // the Animatable and the state writes go through the
                        // composition scope. The snap keeps the release
                        // spring starting where the finger left the thumb,
                        // and `dragging = false` + the caller's checked
                        // write land in the same frame, so the slide effect
                        // relaunches once, already aimed at the new side.
                        scope.launch {
                            fraction.snapTo(end)
                            dragging = false
                            releases++
                            if (completed) commit(SwitchMath.releaseValue(end))
                        }
                    } finally {
                        pressed = false
                    }
                }
            },
    )
}
