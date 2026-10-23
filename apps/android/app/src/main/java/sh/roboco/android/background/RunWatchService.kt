package sh.roboco.android.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import sh.roboco.android.RobocoApplication
import sh.roboco.android.notify.OngoingInput
import sh.roboco.android.notify.ongoingContent

/**
 * The run-watching foreground service (android-app#28, type `dataSync`;
 * `specialUse` while "Stay connected in background" holds it, ADR 0010).
 *
 * Type choice (ADR 0010): the manifest declares `dataSync|specialUse` and
 * each `startForeground` picks one ([fgsType]):
 * - `dataSync` for run watching and its linger — today's type, bounded
 *   use, and its API 35+ 6 h/24 h background budget is ample for that;
 * - `specialUse` (API 34+) for Stay connected, which is open-ended: the
 *   only types with no time limit on API 35/36 that need no hardware
 *   prerequisite are `specialUse` and `remoteMessaging`, and
 *   `remoteMessaging` is defined as relaying text messages between
 *   devices, which watching agent runs is not. `specialUse` carries the
 *   `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification Play would review;
 *   Roboco ships no Play build (ADR 0009), so nothing gates on it.
 *   `connectedDevice` needs a `CHANGE_NETWORK_STATE`-style prerequisite
 *   we do not otherwise use. Below API 34 there is no `specialUse` and
 *   `dataSync` has no limit, so Stay connected runs as `dataSync` there.
 * A Settings flip while the service is up re-calls `startForeground` with
 * the new type (the app is visible then).
 *
 * It owns **no sockets and no wake locks** — the core client does, and the
 * engine's own 15 s pings keep the socket busy (Doze leaves an FGS's
 * network unrestricted). It exists to keep the process in FGS state (not
 * frozen, not cached) while any run is Working, for the linger after the
 * last one, and for as long as Stay connected is on — and to show the
 * ongoing notification. The [RunWatchController] decides when it runs.
 *
 * Lifecycle facts (the ticket's platform-rules table):
 * - started via `startForegroundService` only while the app is visible
 *   (API 31+); `onStartCommand` calls `ServiceCompat.startForeground`
 *   immediately — well inside the 5 s window — and returns
 *   `START_NOT_STICKY`;
 * - the platform defers the FGS notification ~10 s by default (kept:
 *   runs shorter than that never flash a notification);
 * - API 35+ `onTimeout(startId, fgsType)` (the 6 h/24 h dataSync budget):
 *   stop at once, latch the budget, post "Background updates paused";
 *   the next foreground resets it (the budget resets then too);
 * - API 36: the ongoing notification requests Live Update promotion
 *   ([Notifier.buildOngoing]) only for runs started on this phone and
 *   while the user has not dismissed it.
 */
class RunWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The last content key posted — the repost gate. */
    private var postedKey: String? = null

    /** The controller observer starts once (repeated onStartCommands). */
    private var observing = false

    /** The FGS type last passed to `startForeground` (0 = not yet). */
    private var currentType = 0

    private val app: RobocoApplication
        get() = application as RobocoApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = app.runWatch
        controller.onServiceStarted()
        if (intent?.action == ACTION_DISMISSED) {
            // The ongoing notification was swiped away: never request
            // promotion again until the working set changes.
            controller.onLiveUpdateDismissed()
        }
        // Enter the foreground at once with whatever content is current
        // (the 5 s window is tight; the collector refines it after).
        val state = controller.state.value
        enterForeground(state, buildOngoing(state))
        observeController(controller)
        return START_NOT_STICKY
    }

    /** `startForeground` with the type the state asks for (see the class KDoc). */
    private fun enterForeground(state: RunWatchState, notification: Notification) {
        val type = fgsType(state.persistent)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        currentType = type
    }

    /**
     * API 35+: the dataSync budget ran out. Stop, latch, stay quiet. Never
     * called for `specialUse` (no time limit), so Stay connected is immune.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.i(TAG, "dataSync budget exhausted; stopping")
        app.runWatch.onServiceTimeout(app.notifier)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        postedKey = null
        app.runWatch.onServiceStopped()
        scope.cancel()
        super.onDestroy()
    }

    /** React to the controller: stop, or repost when the content changed. */
    private fun observeController(controller: RunWatchController) {
        if (observing) return
        observing = true
        scope.launch {
            controller.state.collect { state ->
                if (!state.active) {
                    ServiceCompat.stopForeground(this@RunWatchService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                if (fgsType(state.persistent) != currentType) {
                    // Stay connected flipped while the service is up (a
                    // Settings toggle, so the app is visible): re-enter the
                    // foreground under the new type.
                    postedKey = state.contentKey
                    try {
                        enterForeground(state, buildOngoing(state))
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "foreground service type change failed: $error")
                    }
                    return@collect
                }
                val key = state.contentKey
                if (key != postedKey) {
                    postedKey = key
                    val notification = buildOngoing(state)
                    try {
                        NotificationManagerCompat.from(this@RunWatchService)
                            .notify(NOTIFICATION_ID, notification)
                    } catch (error: SecurityException) {
                        // The permission was revoked: the FGS still runs
                        // (its notification lives in the Task Manager).
                        Log.w(TAG, "ongoing notification skipped: $error")
                    }
                }
            }
        }
    }

    private fun buildOngoing(state: RunWatchState): Notification {
        val canPromote = android.os.Build.VERSION.SDK_INT >= 36 &&
            NotificationManagerCompat.from(this).canPostPromotedNotifications()
        val content = ongoingContent(
            OngoingInput(
                working = state.working,
                awaiting = state.awaiting,
                engineCount = state.engineCount,
                liveUpdateDismissed = state.liveUpdateDismissed,
            ),
            System.currentTimeMillis(),
            canPromote,
        )
        return app.notifier.buildOngoing(content, dismissedIntent())
    }

    /** The deleteIntent: the user dismissed the Live Update. */
    private fun dismissedIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            REQUEST_DISMISSED,
            Intent(this, RunWatchService::class.java).setAction(ACTION_DISMISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        /**
         * The FGS type for a state: `specialUse` while Stay connected holds
         * the service on API 34+, `dataSync` otherwise (ADR 0010).
         */
        fun fgsType(persistent: Boolean): Int =
            if (persistent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }

        const val TAG = "RobocoRunWatch"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISMISSED = "sh.roboco.android.runwatch.DISMISSED"
        const val REQUEST_DISMISSED = 28
    }
}
