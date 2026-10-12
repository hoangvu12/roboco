package sh.roboco.android.design

import android.os.VibrationEffect
import android.os.VibrationEffect.Composition as C
import android.view.HapticFeedbackConstants as H
import java.util.Locale

/**
 * The 8 primitives probed (and the `ALL` test fixture), in this order: also
 * the gallery header order. Probe via
 * `Vibrator.areAllPrimitivesSupported(id)` once per process.
 */
val ALL_PRIMITIVES: List<Int> = listOf(
    C.PRIMITIVE_CLICK,
    C.PRIMITIVE_TICK,
    C.PRIMITIVE_LOW_TICK,
    C.PRIMITIVE_THUD,
    C.PRIMITIVE_SPIN,
    C.PRIMITIVE_QUICK_RISE,
    C.PRIMITIVE_SLOW_RISE,
    C.PRIMITIVE_QUICK_FALL,
)

/** One primitive in a composition: [scale] 0..1, [delayMs] precedes it. */
data class Step(val primitive: Int, val scale: Float, val delayMs: Int = 0)

/**
 * How a [Haptic] is felt. [HapticPlanner] walks a ladder from the richest
 * effect the device can render down to a predefined pulse:
 *
 * 1. The platform's own `performHapticFeedback` constant (OEM tuned, so it
 *    feels native) when [preferView].
 * 2. The first [compositions] alternative whose primitives are all supported.
 * 3. A view constant for the moments that do not prefer one (the iOS-fallback
 *    rung: `Stop`, `Light`, `Success`, `Attention`, `Error`).
 * 4. The [predefined] effect.
 */
data class HapticSpec(
    val haptic: Haptic,
    /** Rung 1 may use the view constant. */
    val preferView: Boolean,
    /** SDK level -> `HapticFeedbackConstants` value, or null when this device has none for the moment. */
    val view: (sdk: Int) -> Int?,
    /** Alternatives tried in order; the first whose primitives are all supported wins. */
    val compositions: List<List<Step>>,
    /** The `VibrationEffect.EFFECT_*` used at rung 4. */
    val predefined: Int,
    /** 0 light, 1 action, 2 event, 3 alert. */
    val priority: Int,
    /** The same moment never repeats inside this many ms. */
    val minGapMs: Long,
)

/**
 * The rendering table. Every value here is the ticket's port of upstream PR
 * #699's design, trimmed to the 11 moments the iOS app plays.
 */
object HapticTable {
    fun spec(haptic: Haptic): HapticSpec = all[haptic.ordinal]

    val all: List<HapticSpec> by lazy { Haptic.entries.map(::build) }

    /** The iOS source text shown in the gallery. */
    fun iosSource(haptic: Haptic): String = when (haptic) {
        Haptic.Select -> "selection · ComposerBar.swift:371"
        Haptic.Confirm -> "impact .medium · ComposerBar.swift:653"
        Haptic.Stop -> "impact .rigid · ComposerBar.swift:642"
        Haptic.Light -> "impact .light · RowView.swift:335"
        Haptic.LongPress -> "UIKit context menu"
        Haptic.Threshold -> "UIKit full swipe / refresh"
        Haptic.ToggleOn -> "UISwitch on"
        Haptic.ToggleOff -> "UISwitch off"
        Haptic.Success, Haptic.Attention, Haptic.Error -> "reserved (android-app#28)"
    }

    private fun api(level: Int, constant: Int): (Int) -> Int? = { sdk -> if (sdk >= level) constant else null }

    private fun api(level: Int, constant: Int, below: Int): (Int) -> Int? = { sdk -> if (sdk >= level) constant else below }

    private fun build(haptic: Haptic): HapticSpec = when (haptic) {
        // A choice was made (selection generator). API 34 has the dedicated segment tick.
        Haptic.Select -> HapticSpec(
            haptic, true, api(34, H.SEGMENT_TICK, H.CONTEXT_CLICK),
            listOf(listOf(Step(C.PRIMITIVE_TICK, 0.7f)), listOf(Step(C.PRIMITIVE_CLICK, 0.35f))),
            VibrationEffect.EFFECT_TICK, priority = 0, minGapMs = 50,
        )
        // A committed action: send, create, save, start (impact .medium).
        Haptic.Confirm -> HapticSpec(
            haptic, true, api(30, H.CONFIRM),
            listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.65f)), listOf(Step(C.PRIMITIVE_TICK, 0.9f))),
            VibrationEffect.EFFECT_CLICK, priority = 1, minGapMs = 120,
        )
        // iOS impact .rigid: one sharp tap. REJECT is an error buzz on most OEM tunings,
        // so it is the fallback rung only (devices without CLICK/TICK primitives).
        Haptic.Stop -> HapticSpec(
            haptic, false, api(30, H.REJECT),
            listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.9f)), listOf(Step(C.PRIMITIVE_TICK, 1.0f))),
            VibrationEffect.EFFECT_CLICK, priority = 1, minGapMs = 120,
        )
        // iOS impact .light. KEYBOARD_TAP is subject to the separate keyboard-vibration
        // toggle, so it is the fallback rung only.
        Haptic.Light -> HapticSpec(
            haptic, false, api(0, H.KEYBOARD_TAP),
            listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.4f)), listOf(Step(C.PRIMITIVE_TICK, 0.6f))),
            VibrationEffect.EFFECT_TICK, priority = 1, minGapMs = 80,
        )
        // A context menu lifts on long press (UIKit plays it for free).
        Haptic.LongPress -> HapticSpec(
            haptic, true, api(0, H.LONG_PRESS),
            listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.7f)), listOf(Step(C.PRIMITIVE_TICK, 1.0f))),
            VibrationEffect.EFFECT_CLICK, priority = 1, minGapMs = 300,
        )
        // A swipe / drag crossed its commit threshold (UISwipeActionsConfiguration,
        // UIRefreshControl).
        Haptic.Threshold -> HapticSpec(
            haptic, true, api(34, H.GESTURE_THRESHOLD_ACTIVATE),
            listOf(listOf(Step(C.PRIMITIVE_CLICK, 0.55f)), listOf(Step(C.PRIMITIVE_TICK, 0.9f))),
            VibrationEffect.EFFECT_CLICK, priority = 1, minGapMs = 150,
        )
        // Switches: a rising pair for on, a falling pair for off (system toggle constants from API 34).
        Haptic.ToggleOn -> HapticSpec(
            haptic, true, api(34, H.TOGGLE_ON),
            listOf(
                listOf(Step(C.PRIMITIVE_LOW_TICK, 0.5f), Step(C.PRIMITIVE_TICK, 0.8f, 20)),
                listOf(Step(C.PRIMITIVE_TICK, 0.5f), Step(C.PRIMITIVE_CLICK, 0.6f, 25)),
            ),
            VibrationEffect.EFFECT_CLICK, priority = 1, minGapMs = 80,
        )
        Haptic.ToggleOff -> HapticSpec(
            haptic, true, api(34, H.TOGGLE_OFF),
            listOf(
                listOf(Step(C.PRIMITIVE_TICK, 0.7f), Step(C.PRIMITIVE_LOW_TICK, 0.4f, 20)),
                listOf(Step(C.PRIMITIVE_CLICK, 0.5f), Step(C.PRIMITIVE_TICK, 0.4f, 25)),
            ),
            VibrationEffect.EFFECT_TICK, priority = 1, minGapMs = 80,
        )
        // Soft rise, then a small settle: a turn completed, a transfer arrived.
        // Reserved (android-app#28); CONFIRM is the fallback rung only.
        Haptic.Success -> HapticSpec(
            haptic, false, api(30, H.CONFIRM),
            listOf(
                listOf(Step(C.PRIMITIVE_QUICK_RISE, 0.45f), Step(C.PRIMITIVE_LOW_TICK, 0.55f, 40)),
                listOf(Step(C.PRIMITIVE_CLICK, 0.5f), Step(C.PRIMITIVE_TICK, 0.5f, 60)),
            ),
            VibrationEffect.EFFECT_CLICK, priority = 2, minGapMs = 400,
        )
        // Two crisp taps: something needs you. Reserved (android-app#28).
        Haptic.Attention -> HapticSpec(
            haptic, false, api(30, H.REJECT),
            listOf(
                listOf(Step(C.PRIMITIVE_TICK, 0.85f), Step(C.PRIMITIVE_TICK, 0.85f, 90)),
                listOf(Step(C.PRIMITIVE_CLICK, 0.6f), Step(C.PRIMITIVE_CLICK, 0.6f, 90)),
            ),
            VibrationEffect.EFFECT_DOUBLE_CLICK, priority = 2, minGapMs = 400,
        )
        // A short, heavy double: failed or refused. Reserved (android-app#28).
        Haptic.Error -> HapticSpec(
            haptic, false, api(30, H.REJECT),
            listOf(
                listOf(Step(C.PRIMITIVE_CLICK, 1.0f), Step(C.PRIMITIVE_THUD, 0.8f, 60)),
                listOf(Step(C.PRIMITIVE_CLICK, 1.0f), Step(C.PRIMITIVE_CLICK, 0.9f, 70)),
            ),
            VibrationEffect.EFFECT_HEAVY_CLICK, priority = 3, minGapMs = 400,
        )
    }
}

/** What the device can do, probed once at start. */
data class HapticCapabilities(
    val sdk: Int,
    /** Primitive ids this vibrator renders (`areAllPrimitivesSupported`). */
    val primitives: Set<Int>,
    /** A window view is attached for `performHapticFeedback`. */
    val hasView: Boolean,
)

/** The effect chosen for one moment on one device. */
sealed interface HapticPlan {
    data class ViewConstant(val constant: Int) : HapticPlan

    data class Composition(val steps: List<Step>) : HapticPlan

    data class Predefined(val effect: Int) : HapticPlan
}

/** The ladder: which rung a moment takes on one device. */
object HapticPlanner {

    fun plan(haptic: Haptic, caps: HapticCapabilities): HapticPlan {
        val spec = HapticTable.spec(haptic)
        if (spec.preferView && caps.hasView) {
            spec.view(caps.sdk)?.let { return HapticPlan.ViewConstant(it) }
        }
        spec.compositions.firstOrNull { steps -> steps.all { it.primitive in caps.primitives } }
            ?.let { return HapticPlan.Composition(it) }
        // The fallback rung for Stop, Light, Success, Attention and Error.
        if (caps.hasView) {
            spec.view(caps.sdk)?.let { return HapticPlan.ViewConstant(it) }
        }
        return HapticPlan.Predefined(spec.predefined)
    }

    /** `"View SEGMENT_TICK"` / `"Comp LOW_TICK 0.50 + TICK 0.80 @20"` / `"Predef EFFECT_CLICK"` (gallery + logs). */
    fun describe(plan: HapticPlan): String = when (plan) {
        is HapticPlan.ViewConstant -> "View ${viewConstantName(plan.constant)}"
        is HapticPlan.Composition -> "Comp " + plan.steps.joinToString(" + ") { step ->
            buildString {
                append(primitiveName(step.primitive))
                append(' ')
                append("%.2f".format(Locale.ROOT, step.scale))
                if (step.delayMs != 0) {
                    append(" @")
                    append(step.delayMs)
                }
            }
        }
        is HapticPlan.Predefined -> "Predef ${effectName(plan.effect)}"
    }

    /** `"CLICK"`, `"TICK"`, … for the 8 probed primitives (else the int as text). */
    fun primitiveName(id: Int): String = when (id) {
        C.PRIMITIVE_CLICK -> "CLICK"
        C.PRIMITIVE_TICK -> "TICK"
        C.PRIMITIVE_LOW_TICK -> "LOW_TICK"
        C.PRIMITIVE_THUD -> "THUD"
        C.PRIMITIVE_SPIN -> "SPIN"
        C.PRIMITIVE_QUICK_RISE -> "QUICK_RISE"
        C.PRIMITIVE_SLOW_RISE -> "SLOW_RISE"
        C.PRIMITIVE_QUICK_FALL -> "QUICK_FALL"
        else -> id.toString()
    }

    private fun viewConstantName(constant: Int): String = when (constant) {
        H.SEGMENT_TICK -> "SEGMENT_TICK"
        H.CONTEXT_CLICK -> "CONTEXT_CLICK"
        H.CONFIRM -> "CONFIRM"
        H.REJECT -> "REJECT"
        H.KEYBOARD_TAP -> "KEYBOARD_TAP"
        H.LONG_PRESS -> "LONG_PRESS"
        H.GESTURE_THRESHOLD_ACTIVATE -> "GESTURE_THRESHOLD_ACTIVATE"
        H.TOGGLE_ON -> "TOGGLE_ON"
        H.TOGGLE_OFF -> "TOGGLE_OFF"
        else -> constant.toString()
    }

    private fun effectName(effect: Int): String = when (effect) {
        VibrationEffect.EFFECT_CLICK -> "EFFECT_CLICK"
        VibrationEffect.EFFECT_TICK -> "EFFECT_TICK"
        VibrationEffect.EFFECT_DOUBLE_CLICK -> "EFFECT_DOUBLE_CLICK"
        VibrationEffect.EFFECT_HEAVY_CLICK -> "EFFECT_HEAVY_CLICK"
        else -> effect.toString()
    }
}
