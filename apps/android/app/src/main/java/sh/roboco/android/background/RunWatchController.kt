package sh.roboco.android.background

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.roboco.android.notify.Notifier
import sh.roboco.android.notify.WorkingRun

/**
 * The state the service renders: the working set (already projected to
 * [WorkingRun]s), the awaiting count, the paired-engine count, the
 * budget/dismissal flags, and whether the service should be running.
 * `contentKey` is the repost identity: the working set / awaiting / titles
 * change it — the chronometer ticks without reposts.
 */
data class RunWatchState(
    val working: List<WorkingRun>,
    val awaiting: Int,
    val engineCount: Int,
    val budgetExhausted: Boolean,
    val liveUpdateDismissed: Boolean,
    /** True while the foreground service should be up. */
    val active: Boolean,
    /**
     * True while "Stay connected in background" holds the service (ADR
     * 0010): the service runs as `specialUse` (no time limit) instead of
     * `dataSync`. False whenever [active] is false.
     */
    val persistent: Boolean = false,
) {
    val contentKey: String
        get() = working.joinToString("|") { "${it.chatId}:${it.title}" } +
            "#$awaiting@$engineCount"
}

/**
 * The foreground-service lifecycle owner (android-app#28), a process
 * singleton created in `RobocoApplication.onCreate`. It never owns
 * sockets — the core client (also a process singleton) does; the service
 * only keeps the process out of the cached/frozen set so the engine
 * connections and their notifications live.
 *
 * Inputs: `RunActivity` from the core, the process foreground state, the
 * notifications-on gate, the paired-engine count and the Stay connected
 * preference. Behaviour (the ticket's Lifecycle table, amended by ADR
 * 0010):
 * - **Start** while the app is foreground AND (`working` is non-empty AND
 *   notifications are on, OR Stay connected is on) (API 31+ forbids
 *   background FGS starts — the exception is caught and logged, never
 *   crashes);
 * - **Keep** while `working` is non-empty, foreground or not (a run that
 *   starts while the service is up is included);
 * - **Linger** for [LINGER_MS] (2 min) after `working` empties (ADR
 *   0010). It covers the queue-handoff flicker (the desktop's "coalesced
 *   working states", the old 15 s grace) and, mainly, the time between
 *   the Done notification and the user's tap: Android 14+ freezes a cached
 *   app ~10 s after its service stops, and the engine drops the silent
 *   sockets 45 s later, so a tap within the linger lands on live sockets
 *   instead of a reconnect. Longer would leave the "Staying connected"
 *   notification up for no one; most taps come within a minute or two;
 * - **Stop** after the linger, or at once when notifications turn off or
 *   the last engine is forgotten (unless Stay connected holds it; the
 *   last engine forgotten stops it either way);
 * - **Stay connected** (the opt-in Settings toggle, ADR 0010): while on
 *   and ≥ 1 engine is paired the service runs whether or not a run is
 *   Working and whether or not notifications are on, so the process and
 *   its sockets live in the background; [RunWatchState.persistent] tells
 *   the service to run as `specialUse` (no time limit). Off restores the
 *   rules above. It still only *starts* while the app is foreground (the
 *   toggle lives in Settings, and every later app open retries);
 * - **Background-started runs:** with the service down there is no way
 *   to cover them. Android 12+ refuses `startForegroundService` from the
 *   background (no exemption applies: no FCM, no exact alarm, no battery
 *   optimisation opt-out), and a cached Android 14+ process is frozen and
 *   never sees the run start anyway. A run that starts while the service
 *   is up (a run, the linger or Stay connected) is kept;
 * - **onTimeout** (the API 35+ dataSync budget): the flag latches (forcing
 *   `active` false — the service `stopSelf()`s in its callback), a quiet
 *   "Background updates paused" notification posts; the next foreground
 *   clears it and restarts the service if runs are still Working (the
 *   budget resets on foreground).
 *
 * The decisions are pure Kotlin behind a `clock` seam; the tests drive
 * them with a fake clock and virtual time, observing the started intents
 * and [state].
 */
class RunWatchController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val serviceClass: Class<*> = RunWatchService::class.java,
    private val notifier: Notifier = Notifier(context),
    private val startService: (Intent) -> Unit = { intent ->
        ContextCompat.startForegroundService(context, intent)
    },
) {

    /** How many `startForegroundService` calls went out (tests). */
    var starts: Int = 0
        private set

    private var working: List<WorkingRun> = emptyList()
    private var awaiting: Int = 0
    private var engineCount: Int = 0
    private var notificationsOn: Boolean = false
    private var foreground: Boolean = false
    private var stayConnected: Boolean = false

    /** When `working` became empty; the linger counts from here. */
    private var emptySinceMs: Long? = null

    private var budgetExhausted: Boolean = false
    private var liveUpdateDismissed: Boolean = false

    // Declared after the fields `stateOf` reads: property initializers run
    // in order, and an earlier `_state` would hand RunWatchState a null
    // `working` (an NPE in its non-null parameter check).
    private val _state = MutableStateFlow(stateOf(active = false))
    val state: StateFlow<RunWatchState> = _state.asStateFlow()

    /** The mirror of "the service is up" (the service reports both edges). */
    private var serviceRunning: Boolean = false

    /** A start went out and `onStartCommand` has not acknowledged it yet. */
    private var startInFlight: Boolean = false

    /** The delayed stop-grace check. */
    private var stopCheck: Job? = null

    /**
     * The inputs arrive from two threads (the sink's Default lane and the
     * app's main lane); every transition and the snapshot run under this
     * lock so the state is never torn.
     */
    private val gate = Any()

    // ── inputs ───────────────────────────────────────────────────────────

    /** The core's aggregate activity (arrives off the main thread). */
    fun onRunActivity(newWorking: List<WorkingRun>, newAwaiting: Int) {
        synchronized(gate) {
            val workingSetChanged = newWorking.map { "${it.chatId}:${it.title}:${it.fromThisDevice}" } !=
                working.map { "${it.chatId}:${it.title}:${it.fromThisDevice}" }
            val hadWorking = working.isNotEmpty()
            working = newWorking
            awaiting = newAwaiting
            if (working.isNotEmpty()) {
                emptySinceMs = null
                // The WORKING SET changed (an awaiting-only change does
                // not): a dismissed Live Update may be promoted again.
                if (workingSetChanged) liveUpdateDismissed = false
            } else if (hadWorking) {
                // The linger opens only when the last run LEAVES Working:
                // an activity that was never non-empty must not hold (or
                // start) the service.
                emptySinceMs = clock()
            }
            update()
        }
    }

    /** The process lifecycle (≥ STARTED = foreground). */
    fun onForeground(isForeground: Boolean) {
        synchronized(gate) {
            val was = foreground
            foreground = isForeground
            if (isForeground && !was && budgetExhausted) {
                // The dataSync budget resets when the user brings the app
                // to the foreground.
                budgetExhausted = false
                notifier.cancelBudgetExhausted()
            }
            update()
        }
    }

    /** The notifications-on gate (the master pref + the permission). */
    fun onNotificationsOn(on: Boolean) {
        synchronized(gate) {
            notificationsOn = on
            update()
        }
    }

    /** Settings → "Stay connected in background" (ADR 0010). */
    fun onStayConnected(on: Boolean) {
        synchronized(gate) {
            stayConnected = on
            update()
        }
    }

    /** The paired-engine count (parked engines count as paired). */
    fun onEngineCount(count: Int) {
        synchronized(gate) {
            engineCount = count
            update()
        }
    }

    // ── the service's reports ────────────────────────────────────────────

    /** The service's `onTimeout(startId, fgsType)` (API 35+). */
    fun onServiceTimeout(notifier: Notifier) {
        synchronized(gate) {
            budgetExhausted = true
            notifier.postBudgetExhausted()
            update()
        }
    }

    /** The ongoing notification's deleteIntent: the Live Update was dismissed. */
    fun onLiveUpdateDismissed() {
        synchronized(gate) {
            if (liveUpdateDismissed) return
            liveUpdateDismissed = true
            update()
        }
    }

    /** The service's `onStartCommand` acknowledges it is up. */
    fun onServiceStarted() {
        synchronized(gate) {
            serviceRunning = true
            startInFlight = false
        }
    }

    /** The service went down (its stop path or `onDestroy`). */
    fun onServiceStopped() {
        synchronized(gate) {
            serviceRunning = false
            startInFlight = false
        }
    }

    // ── the decision ─────────────────────────────────────────────────────

    private fun stateOf(active: Boolean, persistent: Boolean = false) = RunWatchState(
        working = working,
        awaiting = awaiting,
        engineCount = engineCount,
        budgetExhausted = budgetExhausted,
        liveUpdateDismissed = liveUpdateDismissed,
        active = active,
        persistent = active && persistent,
    )

    /** Stay connected holds the service on its own (ADR 0010). */
    private fun persistent(): Boolean = stayConnected && engineCount > 0

    private fun shouldRun(): Boolean {
        // Stay connected runs as specialUse: neither the dataSync budget
        // nor the notifications gate applies (the user asked for the
        // connection itself, not only for its notifications).
        if (persistent()) return true
        if (budgetExhausted) return false
        if (!notificationsOn || engineCount == 0) return false
        if (working.isNotEmpty()) return true
        // The linger: queue handoffs, and the tap on the Done notification.
        val since = emptySinceMs ?: return false
        return clock() - since < LINGER_MS
    }

    private fun update() {
        val run = shouldRun()
        if (run && !serviceRunning && !startInFlight && foreground) {
            startInFlight = true
            starts += 1
            try {
                startService(Intent(context, serviceClass))
            } catch (error: ForegroundServiceStartNotAllowedException) {
                // API 31: cannot start an FGS from the background — the
                // next foreground window retries. Never crash.
                startInFlight = false
                Log.w(TAG, "RunWatchService start not allowed: $error")
            } catch (error: IllegalStateException) {
                startInFlight = false
                Log.w(TAG, "RunWatchService start failed: $error")
            } catch (error: SecurityException) {
                startInFlight = false
                Log.w(TAG, "RunWatchService start not permitted: $error")
            }
        }
        _state.value = stateOf(run, persistent())
        scheduleStopCheck(run)
    }

    /**
     * A service held only by the linger needs a wake at its deadline. The
     * wake is re-armed from the linger's own start, so turning Stay
     * connected off mid-linger still stops on time.
     */
    private fun scheduleStopCheck(run: Boolean) {
        val since = emptySinceMs
        if (run && working.isEmpty() && since != null && !persistent()) {
            if (stopCheck?.isActive != true) {
                val wait = (since + LINGER_MS - clock()).coerceAtLeast(0L) + 50
                stopCheck = scope.launch {
                    delay(wait)
                    synchronized(gate) {
                        stopCheck = null
                        if (working.isEmpty()) update()
                    }
                }
            }
        } else {
            stopCheck?.cancel()
            stopCheck = null
        }
    }

    companion object {
        private const val TAG = "RobocoRunWatch"

        /**
         * How long the service outlives the last Working run (ADR 0010):
         * long enough to tap the Done notification and come back to live
         * sockets, short enough that the ongoing notification does not
         * linger for no one. See the class KDoc.
         */
        const val LINGER_MS = 120_000L
    }
}
