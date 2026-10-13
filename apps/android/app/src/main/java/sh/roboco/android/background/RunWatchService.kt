package sh.roboco.android.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import sh.roboco.android.notify.Notifier
import sh.roboco.android.notify.OngoingInput
import sh.roboco.android.notify.ongoingContent

/**
 * The run-watching foreground service (android-app#28, type `dataSync`).
 *
 * It owns **no sockets and no wake locks** — the core client does, and the
 * engine's own 15 s pings keep the socket busy (Doze leaves an FGS's
 * network unrestricted). It exists to keep the process in FGS state (not
 * frozen, not cached) while any run is Working, and to show the ongoing
 * notification. The [RunWatchController] decides when it runs.
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
        val notification = buildOngoing(controller.state.value)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        observeController(controller)
        return START_NOT_STICKY
    }

    /** API 35+: the dataSync budget ran out. Stop, latch, stay quiet. */
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
        const val TAG = "RobocoRunWatch"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISMISSED = "sh.roboco.android.runwatch.DISMISSED"
        const val REQUEST_DISMISSED = 28
    }
}
