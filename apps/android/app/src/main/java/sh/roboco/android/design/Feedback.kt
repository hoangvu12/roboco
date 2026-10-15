package sh.roboco.android.design

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's haptic vocabulary (android-ui-parity #05). Names describe the
 * *moment*, not the vibration: the engine behind [Feedback] decides how each
 * one is felt on this device (a view constant, a composition of primitives,
 * or a predefined effect — see [HapticTable]).
 *
 * The moments map 1:1 to the iOS generator call sites listed in the ticket:
 * `Select` (UISelectionFeedbackGenerator), `Confirm` (impact .medium),
 * `Stop` (impact .rigid), `Light` (impact .light), plus the ones UIKit plays
 * for free: `LongPress` (context-menu lift), `Threshold` (full swipe /
 * pull-to-refresh commit) and `ToggleOn`/`ToggleOff` (UISwitch).
 * `Success`/`Attention`/`Error` cover UINotificationFeedbackGenerator and
 * are reserved for android-app#28 — no ticket in this set fires them.
 */
enum class Haptic {
    Select,
    Confirm,
    Stop,
    Light,
    LongPress,
    Threshold,
    ToggleOn,
    ToggleOff,
    Success,
    Attention,
    Error,
}

/** Sound cues: a no-op stub until android-app#28 adds a sound engine. */
enum class Cue { Done, Request, Attention }

/**
 * How the app asks for feedback. Consumers fire haptics themselves at the
 * iOS call sites (`LocalFeedback.current.haptic(Haptic.Select)`) — nothing
 * here fires on its own, and there is no default tap haptic.
 */
interface Feedback {
    fun haptic(haptic: Haptic)

    fun cue(cue: Cue)

    /** Sound first: it is the channel perceived late. */
    fun both(haptic: Haptic, cue: Cue) {
        cue(cue)
        haptic(haptic)
    }
}

/** Does nothing: previews, inspection, and the default until the app installs the real one. */
object NoFeedback : Feedback {
    override fun haptic(haptic: Haptic) = Unit
    override fun cue(cue: Cue) = Unit
}

/** The engine for the composition tree; `NoFeedback` until [ProvideFeedback] installs one. */
val LocalFeedback = staticCompositionLocalOf<Feedback> { NoFeedback }
