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
) {
    val contentKey: String
        get() = working.joinToString("|") { "${it.chatId}:${it.title}" } + "#$awaiting"
}

/**
 * The foreground-service lifecycle owner (android-app#28), a process
 * singleton created in `RobocoApplication.onCreate`. It never owns
 * sockets — the core client (also a process singleton) does; the service
 * only keeps the process out of the cached/frozen set so the engine
 * connections and their notifications live.
 *
 * Inputs: `RunActivity` from the core, the process foreground state, the
 * notifications-on gate and the paired-engine count. Behaviour (the
 * ticket's Lifecycle table):
 * - **Start** while the app is foreground AND `working` is non-empty AND
 *   notifications are on (API 31+ forbids background FGS starts — the
 *   exception is caught and logged, never crashes);
 * - **Keep** while `working` is non-empty, foreground or not (a run that
 *   starts while the service is up is included);
 * - **Stop** after `working` has been empty for 15 s continuously (the
 *   queue-handoff grace — the desktop's "coalesced working states"), or
 *   at once when notifications turn off or the last engine is forgotten;
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

    private val _state = MutableStateFlow(stateOf(active = false))
    val state: StateFlow<RunWatchState> = _state.asStateFlow()

    /** How many `startForegroundService` calls went out (tests). */
    var starts: Int = 0
        private set

    private var working: List<WorkingRun> = emptyList()
    private var awaiting: Int = 0
    private var engineCount: Int = 0
    private var notificationsOn: Boolean = false
    private var foreground: Boolean = false

    /** When `working` became empty; the 15 s stop grace counts from here. */
    private var emptySinceMs: Long? = null

    private var budgetExhausted: Boolean = false
    private var liveUpdateDismissed: Boolean = false

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
            working = newWorking
            awaiting = newAwaiting
            if (working.isNotEmpty()) {
                emptySinceMs = null
                // The WORKING SET changed (an awaiting-only change does
                // not): a dismissed Live Update may be promoted again.
                if (workingSetChanged) liveUpdateDismissed = false
            } else if (emptySinceMs == null) {
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

    private fun stateOf(active: Boolean) = RunWatchState(
        working = working,
        awaiting = awaiting,
        engineCount = engineCount,
        budgetExhausted = budgetExhausted,
        liveUpdateDismissed = liveUpdateDismissed,
        active = active,
    )

    private fun shouldRun(): Boolean {
        if (budgetExhausted) return false
        if (!notificationsOn || engineCount == 0) return false
        if (working.isNotEmpty()) return true
        // The 15 s stop grace: Working flickers across queue handoffs.
        val since = emptySinceMs ?: return false
        return clock() - since < STOP_GRACE_MS
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
        _state.value = stateOf(run)
        scheduleStopCheck(run)
    }

    /** A run stopping on the grace needs a wake at the deadline. */
    private fun scheduleStopCheck(run: Boolean) {
        if (run && working.isEmpty() && emptySinceMs != null) {
            if (stopCheck?.isActive != true) {
                stopCheck = scope.launch {
                    delay(STOP_GRACE_MS + 50)
                    synchronized(gate) {
                        if (working.isEmpty()) update()
                    }
                }
            }
        } else {
            stopCheck?.cancel()
            stopCheck = null
        }
    }

    private companion object {
        const val TAG = "RobocoRunWatch"
        const val STOP_GRACE_MS = 15_000L
    }
}
