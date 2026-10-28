package sh.roboco.android.session

import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The bottom stack's pure geometry (android-ui-parity #74), the arithmetic
 * of the UIKit constraints of `ios:Session/SessionViewController.swift:152-165`
 * and the inset math of `:367-379`. Pure functions over plain floats, so
 * the unit tests pin the ticket's worked-examples table directly.
 *
 * **Units:** every parameter and result is a float in the caller's unit —
 * the layout passes px, the unit tests pass dp (density 1, where the two
 * coincide numerically). The default arguments are the dp tokens' values,
 * which the container overrides with their px conversions; pass the
 * spacing constants explicitly whenever the space is not dp.
 */
object BottomStackMetrics {

    /** `leading >= safe leading + 12` (`:154`). */
    val SideInset = 12.dp

    /** `width <= 768` (`:155`). */
    val MaxWidth = 768.dp

    /** `bottom.axis = .vertical; bottom.spacing = 8` (`:120-121`). */
    val Spacing = 8.dp

    /** `bottomBottom = keyboardLayoutGuide.top − 8` (`:162`). */
    val KeyboardGap = 8.dp

    /** `covered = maxY − stackTop + 8` (`:371`). */
    val InsetPad = 8.dp

    /** The fade's top sits 44 above the stack's top (`:139`). */
    val FadeAbove = 44.dp

    /** `jump trailing = safe trailing − 16` (`:163`). */
    val JumpTrailing = 16.dp

    /** `jump bottom = stack top − 12` (`:164`). */
    val JumpAbove = 12.dp

    /** The status pill row's height (`:609`). */
    val PillHeight = 30.dp

    /**
     * The stack's width: `min(safeWidth − 24, 768)`, never negative —
     * the required `≥ leading + 12` and `≤ 768` constraints win over the
     * high-priority fill (`:154-161`).
     */
    fun stackWidth(W: Float, safeL: Float, safeR: Float, side: Float = SideInset.value, maxW: Float = MaxWidth.value): Float =
        (W - safeL - safeR - 2f * side).coerceIn(0f, maxW)

    /** The stack's x: centred on the safe-area centre (`centerX`, `:153`). */
    fun stackX(W: Float, safeL: Float, safeR: Float, w: Float): Float =
        safeL + (W - safeL - safeR - w) / 2f

    /**
     * The stack's bottom edge: `H − max(ime, nav) − 8`. With no keyboard
     * this is the safe-area bottom minus 8, and the keyboardLayoutGuide
     * animates it every IME frame (`:162`).
     */
    fun stackBottom(H: Float, ime: Float, nav: Float, gap: Float = KeyboardGap.value): Float =
        H - max(ime, nav) - gap

    /** The composer's resting height (one line, no attachments): `ComposerBar.restHeight`. */
    val ComposerRestHeight = 50.dp

    /**
     * Wave-10 42 (upstream #1014): beside the tablet sidebar the resting
     * composer is centred on the sidebar toolbar's row — [toolbarMid] up
     * from the window bottom — instead of the fixed gap above the nav
     * inset, so the two sit level (it may sink into the safe area, as the
     * toolbar does). The keyboard still lifts it: once the IME top passes
     * that rest, the keyboard rule wins, smoothly.
     */
    fun stackBottomBesideToolbar(
        H: Float,
        ime: Float,
        toolbarMid: Float,
        restHeight: Float = ComposerRestHeight.value,
        gap: Float = KeyboardGap.value,
    ): Float = kotlin.math.min(H - toolbarMid + restHeight / 2f, H - ime - gap)

    /**
     * How much of the window the stack plus its pad covers:
     * `maxY − stackTop + 8` (`:371`) — the transcript's effective
     * `adjustedContentInset.bottom`.
     */
    fun covered(H: Float, stackTop: Float, gap: Float = InsetPad.value): Float =
        H - stackTop + gap

    /** The iOS-literal content inset: `max(0, covered − navBottom)` (`:372`). */
    fun contentInset(covered: Float, nav: Float): Float = max(0f, covered - nav)

    /** The fade's top edge: `stackTop − 44` (`:139`). */
    fun fadeTop(stackTop: Float, above: Float = FadeAbove.value): Float = stackTop - above

    /**
     * The jump slot's top-left corner: right edge at `W − safeR − 16`,
     * bottom edge at `stackTop − 12`, at the slot's own measured size
     * (`:163-164`).
     */
    fun jumpOrigin(
        W: Float,
        safeR: Float,
        stackTop: Float,
        jumpWidth: Float,
        jumpHeight: Float,
        trailing: Float = JumpTrailing.value,
        above: Float = JumpAbove.value,
    ): Pair<Float, Float> =
        W - safeR - trailing - jumpWidth to stackTop - above - jumpHeight

    /**
     * A slot's settled extent: 0 when hidden; the measured height plus the
     * 8-dp gap below it for the pill and the queue; the height itself for
     * the questions panel and the composer (the bottom-most shown slot
     * carries no gap, `:120-121`).
     */
    fun slotTarget(slot: BottomStackSlot, shown: Boolean, h: Float, spacing: Float = Spacing.value): Float =
        if (!shown) {
            0f
        } else {
            when (slot) {
                BottomStackSlot.Limited, BottomStackSlot.Pill, BottomStackSlot.Queue -> h + spacing
                BottomStackSlot.Questions, BottomStackSlot.Composer -> h
            }
        }

    /**
     * The visible height of a slot whose content is drawn at its full
     * measured height [h] while its extent animates: `extent × h / (h + gap)`
     * — the fraction of the slot's box the shrinking extent still covers,
     * with the gap's share clipped away. 0 for a degenerate slot.
     */
    fun visibleHeight(extent: Float, h: Float, gap: Float = Spacing.value): Float =
        if (h + gap <= 0f) 0f else extent * h / (h + gap)
}
