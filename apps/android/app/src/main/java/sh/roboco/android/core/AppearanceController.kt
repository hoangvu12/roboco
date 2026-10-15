package sh.roboco.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Reconciles the persisted appearance preference with the platform's
 * per-app night mode (ticket 96): after [Prefs]'s first disk read it
 * applies the stored value once, then every change — the StateFlow
 * collector receives the current value first, so a platform/DataStore
 * drift (a cleared app-data partition that left the per-app mode behind)
 * heals on the very first launch after it.
 *
 * [start] launches in [scope] (the application scope, Main.immediate in
 * the app) and never completes: the collector runs for the process's
 * lifetime, exactly like the iOS `reload()` + window override pair.
 */
class AppearanceController(
    private val prefs: Prefs,
    private val sink: NightModeSink,
    private val scope: CoroutineScope,
) {

    /** Arms the controller: nothing is pushed before the first disk read. */
    fun start(): Job = scope.launch {
        prefs.awaitLoaded()
        // StateFlow: current value first, then distinct changes only.
        prefs.appearance.collect { sink.apply(it) }
    }
}
