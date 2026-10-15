package sh.roboco.android.core

import android.app.UiModeManager
import android.content.Context
import android.util.Log
import sh.roboco.android.design.Appearance

/**
 * Pushes the user's appearance choice to the platform (ticket 96). Called
 * from the application scope's Main dispatcher at startup and on every
 * change; one binder call, safe from any thread.
 */
fun interface NightModeSink {
    fun apply(appearance: Appearance)
}

/**
 * The explicit [Appearance] → `UiModeManager` night-mode mapping
 * (`UiModeManager.setApplicationNightMode`): System drops the per-app
 * override (`MODE_NIGHT_AUTO`), Light forces `UI_MODE_NIGHT_NO`, Dark
 * forces `UI_MODE_NIGHT_YES`. Written as a `when` on purpose — the
 * integers happen to match [Appearance.storedValue], but nothing may rely
 * on that coincidence.
 */
fun platformNightMode(a: Appearance): Int = when (a) {
    Appearance.System -> UiModeManager.MODE_NIGHT_AUTO
    Appearance.Light -> UiModeManager.MODE_NIGHT_NO
    Appearance.Dark -> UiModeManager.MODE_NIGHT_YES
}

/**
 * The production [NightModeSink]: `UiModeManager.setApplicationNightMode`.
 * API 31+ (Roboco's minSdk), no permission. The platform persists the
 * per-app mode itself and applies it to the process **and the starting
 * window (splash) of the next cold start** — the Android replacement for
 * `window.overrideUserInterfaceStyle` reaching the launch screen.
 *
 * A binder failure is logged (tag `Roboco`) and swallowed: a coroutine in
 * the application scope must never throw, and the controller re-applies
 * the value on the next start, so the failure heals itself.
 */
class UiModeNightModeSink(context: Context) : NightModeSink {

    private val ui = context.getSystemService(UiModeManager::class.java)

    override fun apply(appearance: Appearance) {
        try {
            ui?.setApplicationNightMode(platformNightMode(appearance))
        } catch (e: RuntimeException) {
            Log.w(LogTag, "setApplicationNightMode($appearance) failed", e)
        }
    }
}

private const val LogTag = "Roboco"
