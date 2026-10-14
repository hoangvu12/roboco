package sh.roboco.android.notify

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.roboco.android.background.RunWatchController
import uniffi.roboco_core.NotificationSink
import uniffi.roboco_core.RunActivity
import uniffi.roboco_core.SessionEvent
import uniffi.roboco_core.SessionEventKind

/**
 * The Kotlin delivery half of the notifications (android-app#28): the
 * `NotificationSink` implementation the core calls on its runtime thread,
 * plus the glue the app feeds it with.
 *
 * Threading: every callback hops to [scope] (Dispatchers.Default —
 * `NotificationManagerCompat.notify` is thread-safe) and is fully
 * wrapped: a coroutine in an app scope must never throw. The gates run at
 * delivery time (the permission may have been revoked between checks):
 * the foreground gate first (iOS shows nothing in front — the banner AND
 * the chime), then the master (pref + permission), then the kind.
 *
 * The run activity feeds the [RunWatchController] (the foreground
 * service); `resolved` and `linkRestored` cancel what they resolve.
 */
class NotificationCenter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: RunWatchController,
    private val prefs: NotificationPrefs = NotificationPrefs.get(context),
    private val notifier: Notifier = Notifier(context),
    private val status: () -> NotificationStatus = { NotificationPermission.status(context) },
    private val engineCount: () -> Int = { 0 },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    },
) : NotificationSink {

    /**
     * The process foreground state (ProcessLifecycleOwner ≥ STARTED), set
     * by the app's lifecycle observer. Volatile: the callbacks arrive on
     * a core thread.
     */
    @Volatile
    var foreground: Boolean = true

    private val attached = AtomicBoolean(false)

    /**
     * The engine count source (the app's startup state); set after
     * construction by [setEngineCountSource] because the application's
     * startup pipeline outlives this constructor call site.
     */
    @Volatile
    private var engineCountSource: () -> Int = engineCount

    /** Point the subText engine-count gate at the live source. */
    fun setEngineCountSource(source: () -> Int) {
        engineCountSource = source
    }

    // ── the sink (a core thread; hop, never block) ───────────────────────

    override fun sessionEvent(event: SessionEvent) {
        scope.launch {
            try {
                val kind = event.kind.notificationKind()
                val allowed = NotificationPolicy.sessionEventAllowed(
                    kind,
                    prefs.state.value,
                    status(),
                    foreground,
                )
                if (!allowed) return@launch
                notifier.postSessionEvent(
                    engineId = event.engineId,
                    chatId = event.chatId,
                    chatTitle = event.chatTitle,
                    kind = kind,
                    subText = engineSubText(event.engineName),
                    nowMs = clock(),
                )
            } catch (error: Throwable) {
                log("session notification failed: $event", error)
            }
        }
    }

    override fun resolved(engineId: String, chatId: String) {
        scope.launch {
            try {
                notifier.cancelChat(engineId, chatId)
            } catch (error: Throwable) {
                log("cancel for $chatId failed", error)
            }
        }
    }

    override fun linkLost(engineId: String, engineName: String) {
        scope.launch {
            try {
                val allowed = NotificationPolicy.linkLostAllowed(
                    prefs.state.value,
                    status(),
                    foreground,
                )
                if (!allowed) return@launch
                notifier.postLinkLost(engineId, engineName, clock())
            } catch (error: Throwable) {
                log("link-lost notification failed for $engineId", error)
            }
        }
    }

    override fun linkRestored(engineId: String) {
        scope.launch {
            try {
                notifier.cancelLinkLost(engineId)
            } catch (error: Throwable) {
                log("link-restored cancel failed for $engineId", error)
            }
        }
    }

    override fun runActivity(activity: RunActivity) {
        scope.launch {
            try {
                val working = activity.working.map { run ->
                    WorkingRun(
                        chatId = run.chatId,
                        title = run.chatTitle,
                        engineName = run.engineName,
                        startedAtMs = run.startedAtMs,
                        fromThisDevice = run.fromThisDevice,
                    )
                }
                // The notifications-on gate is otherwise re-read only on a
                // foreground edge or a pref change — a permission granted
                // while the app is open (the first-session dialog's Allow,
                // or settings) never reached the controller, so the run
                // watch never started for the run that followed. Re-derive
                // it with every activity update, before the controller acts.
                controller.onNotificationsOn(NotificationPolicy.masterOn(prefs.state.value, status()))
                controller.onRunActivity(working, activity.awaiting.toInt())
            } catch (error: Throwable) {
                log("run-activity update failed", error)
            }
        }
    }

    // ── the app-side seams ───────────────────────────────────────────────

    /**
     * Install this sink on the freshly created client (the application's
     * `onClientReady`). Never on the main thread: the FFI call spawns the
     * driver task.
     */
    fun attachTo(client: uniffi.roboco_core.CoreClient) {
        if (!attached.compareAndSet(false, true)) return
        client.setNotificationSink(this)
    }

    /** A chat was opened in the app: cancel its notification. */
    fun cancelChatFor(chatId: String) {
        scope.launch {
            try {
                notifier.cancelChatFor(chatId)
            } catch (error: Throwable) {
                log("open-cancel for $chatId failed", error)
            }
        }
    }

    /** The engine name as subText — only while ≥ 2 engines are paired. */
    private fun engineSubText(engineName: String): String? =
        if (engineCountSource() >= 2) engineName else null

    private companion object {
        const val TAG = "RobocoNotify"
    }
}

/** The FFI kind → the delivery kind (the same three verdicts). */
internal fun SessionEventKind.notificationKind(): NotificationKind = when (this) {
    // UniFFI's Kotlin flat enums are SCREAMING_CASE (cf. `ParkReason.REVOKED`).
    SessionEventKind.DONE -> NotificationKind.Done
    SessionEventKind.INPUT -> NotificationKind.Input
    SessionEventKind.FAILED -> NotificationKind.Failed
}
