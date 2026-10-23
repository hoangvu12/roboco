package sh.roboco.android.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import sh.roboco.android.MainActivity
import sh.roboco.android.R

/**
 * The notification copy that has no per-kind owner (android-app#28,
 * verbatim from web `notifications.ts:215-220`; the "Background updates
 * paused" pair is new copy, flagged in the ticket's Comments). The
 * session texts live on [NotificationKind.title] — iOS's `Kind.title`
 * (`PushNotifications.swift:18-20`) is both the row title and the banner
 * body.
 */
object NotifyCopy {
    const val LinkLostTitle = "Connection unavailable"
    const val LinkLostReconnecting = "Roboco is trying to reconnect"
    const val LinkLostOffline = "Your device is offline"
    const val BudgetTitle = "Background updates paused"
    const val BudgetText = "Open Roboco to resume."
}

/**
 * Local notifications: versioned channels carrying the desktop chimes,
 * posting, replacing and cancelling (android-app#28).
 *
 * Channel ids carry a version (sounds are immutable once a channel exists,
 * so `migrateChannels` deletes every `session-*` / `runs-*` channel of
 * another version at start). One notification per chat: tag
 * `chat:<engineId>:<chatId>` (the scoped id), id 1 — a newer event replaces
 * the older one, including a change of kind/channel. `link:<engineId>` is
 * the engine outage's notification, cancelled by LinkRestored. The
 * foreground service's ongoing notification posts untagged with id 1.
 */
class Notifier(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    // ── channels ─────────────────────────────────────────────────────────

    /** The session channel of a kind (`session-done-v1`). */
    fun channelId(kind: NotificationKind): String =
        "session-${kind.raw}-v$CHANNEL_VERSION"

    /** The runs channel (the foreground service's ongoing notification). */
    fun runsChannelId(): String = "runs-v$CHANNEL_VERSION"

    /**
     * Delete session/runs channels of other versions (their sounds cannot
     * be edited in place). Call before [ensureChannels] at app start.
     */
    fun migrateChannels() {
        val suffix = "-v$CHANNEL_VERSION"
        manager.notificationChannels
            .filter {
                (it.id.startsWith("session-") || it.id.startsWith("runs-")) &&
                    !it.id.endsWith(suffix)
            }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    /** Create the four channels (idempotent, cheap). */
    fun ensureChannels() {
        for (kind in NotificationKind.entries) {
            val id = channelId(kind)
            if (manager.getNotificationChannel(id) != null) continue
            val channel = NotificationChannel(
                id,
                kind.channelName(),
                when (kind) {
                    NotificationKind.Done -> NotificationManager.IMPORTANCE_DEFAULT
                    NotificationKind.Input -> NotificationManager.IMPORTANCE_HIGH
                    NotificationKind.Failed -> NotificationManager.IMPORTANCE_HIGH
                },
            )
            channel.description = kind.channelDescription()
            channel.setSound(
                Uri.parse("android.resource://${context.packageName}/raw/${kind.chimeResource()}"),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            channel.enableVibration(true)
            channel.vibrationPattern = kind.vibrationPattern()
            manager.createNotificationChannel(channel)
        }
        val runs = runsChannelId()
        if (manager.getNotificationChannel(runs) == null) {
            // Not IMPORTANCE_MIN: that would block Live Update promotion.
            val channel = NotificationChannel(
                runs,
                "Runs in progress",
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = "Shown while an agent run is active"
            // Quiet: a new channel defaults to the system notification
            // sound, which a user raising the importance would then hear.
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
    }

    // ── session events ───────────────────────────────────────────────────

    /**
     * A session event for one chat: replaces any earlier notification for
     * the same chat (the tag), tapping opens the chat's deep link.
     * `subText` is the engine name — only while ≥ 2 engines are paired.
     */
    fun postSessionEvent(
        engineId: String,
        chatId: String,
        chatTitle: String,
        kind: NotificationKind,
        subText: String?,
        nowMs: Long,
    ) {
        val text = kind.title
        val tag = chatTag(engineId, chatId)
        val notification = NotificationCompat.Builder(context, channelId(kind))
            .brand()
            // The core applies the fallback already; a blank title from any
            // other caller still reads "New session" (NotifierTest).
            .setContentTitle(chatTitle.ifBlank { "New session" })
            .setContentText(text)
            .setCategory(
                if (kind == NotificationKind.Failed) {
                    NotificationCompat.CATEGORY_ERROR
                } else {
                    NotificationCompat.CATEGORY_STATUS
                }
            )
            .setShowWhen(true)
            .setWhen(nowMs)
            .setAutoCancel(true)
            .apply {
                if (subText != null) setSubText(subText)
            }
            .setContentIntent(chatTapPendingIntent(tag, chatId))
            .build()
        notify(tag, notification)
    }

    /**
     * The engine link is unavailable: the per-engine outage notification,
     * cancelled by [cancelLinkLost] when the engine reconnects. The copy
     * flips to "Your device is offline" when no validated network exists.
     */
    fun postLinkLost(engineId: String, engineName: String, nowMs: Long) {
        val tag = linkTag(engineId)
        val text = if (hasValidatedNetwork()) {
            NotifyCopy.LinkLostReconnecting
        } else {
            NotifyCopy.LinkLostOffline
        }
        val notification = NotificationCompat.Builder(context, channelId(NotificationKind.Failed))
            .brand()
            .setContentTitle(NotifyCopy.LinkLostTitle)
            .setContentText(text)
            .setSubText(engineName)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setShowWhen(true)
            .setWhen(nowMs)
            .setAutoCancel(true)
            .setContentIntent(chatTapPendingIntent(tag, chatId = null))
            .build()
        notify(tag, notification)
    }

    // ── cancels ──────────────────────────────────────────────────────────

    /** Resolved, or the chat was opened: cancel that chat's notification. */
    fun cancelChat(engineId: String, chatId: String) {
        manager.cancel(chatTag(engineId, chatId), NOTIFICATION_ID)
    }

    /**
     * The chat was opened in the app (the router or the session screen):
     * cancel its notification without knowing the engine — the tag ends
     * with the scoped chat id.
     */
    fun cancelChatFor(chatId: String) {
        val suffix = ":$chatId"
        manager.activeNotifications
            .filter { it.tag != null && it.tag.startsWith("chat:") && it.tag.endsWith(suffix) }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /** LinkRestored: only the lost notification goes. */
    fun cancelLinkLost(engineId: String) {
        manager.cancel(linkTag(engineId), NOTIFICATION_ID)
    }

    /** An engine was forgotten: all of its notifications go. */
    fun cancelEngine(engineId: String) {
        val prefix = "chat:$engineId:"
        manager.activeNotifications
            .filter { it.tag != null && it.tag.startsWith(prefix) }
            .forEach { manager.cancel(it.tag, it.id) }
        cancelLinkLost(engineId)
    }

    // ── the dataSync budget ──────────────────────────────────────────────

    /** `onTimeout`: background updates are paused until the app opens. */
    fun postBudgetExhausted() {
        val notification = NotificationCompat.Builder(context, runsChannelId())
            .brand()
            .setContentTitle(NotifyCopy.BudgetTitle)
            .setContentText(NotifyCopy.BudgetText)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        notify(BUDGET_TAG, notification)
    }

    /** The app returned to the foreground: the budget reset. */
    fun cancelBudgetExhausted() {
        manager.cancel(BUDGET_TAG, NOTIFICATION_ID)
    }

    // ── the foreground service's ongoing notification ────────────────────

    /**
     * The RunWatchService's ongoing notification: channel `runs-v1`,
     * silent, ongoing, a chronometer, and — on API 36 when the platform
     * allows it and a run started on this phone — a promoted Live Update
     * with the status chip. `onDeleteDismissed` receives the deleteIntent
     * taps (a dismissed Live Update is never reposted until the working
     * set changes).
     */
    fun buildOngoing(content: OngoingContent, onDeleteDismissed: PendingIntent?) =
        NotificationCompat.Builder(context, runsChannelId())
            .brand()
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setCategory(
                if (content.working) {
                    NotificationCompat.CATEGORY_PROGRESS
                } else {
                    NotificationCompat.CATEGORY_SERVICE
                },
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(content.whenMs != null)
            .setWhen(content.whenMs ?: System.currentTimeMillis())
            .setUsesChronometer(content.whenMs != null)
            .setRequestPromotedOngoing(content.promoteLiveUpdate)
            .apply {
                // The idle "Staying connected" content (ADR 0010) has no
                // progress bar and no status chip.
                if (content.working) {
                    setProgress(0, 0, true)
                    setShortCriticalText(content.chip)
                }
                if (content.subText != null) setSubText(content.subText)
                setContentIntent(
                    if (content.tapChatId != null) {
                        chatTapPendingIntent("ongoing", content.tapChatId)
                    } else {
                        rootTapPendingIntent()
                    },
                )
                if (onDeleteDismissed != null) setDeleteIntent(onDeleteDismissed)
                if (content.working && android.os.Build.VERSION.SDK_INT >= 36) {
                    setStyle(
                        NotificationCompat.ProgressStyle()
                            .setProgressIndeterminate(true),
                    )
                }
            }
            .build()

    // ── internals ────────────────────────────────────────────────────────

    /**
     * android-next #03's branding, on every builder: the ribbon-bow small
     * icon (status bar and shade), the ribbon blue accent and the launcher
     * artwork as the large icon (expanded notification).
     */
    private fun NotificationCompat.Builder.brand(): NotificationCompat.Builder =
        setSmallIcon(SMALL_ICON)
            .setColor(ContextCompat.getColor(context, R.color.brand_ribbon))
            .apply { largeIcon?.let { setLargeIcon(it) } }

    /**
     * The launcher artwork, decoded once per [Notifier] (the density bucket's
     * `ic_launcher_art`); `null` if it cannot be decoded, and the
     * notification simply goes without a large icon.
     */
    private val largeIcon: Bitmap? by lazy {
        try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_art)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun notify(tag: String, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the policy check and the
            // post (pr699:core/Notifier.kt:139-142): log, never crash.
            Log.w(TAG, "notification $tag skipped: notifications not permitted")
        }
    }

    private fun chatTag(engineId: String, chatId: String) = "chat:$engineId:$chatId"

    private fun linkTag(engineId: String) = "link:$engineId"

    /**
     * The tap: the chat's deep link through an explicit `MainActivity`
     * component (`singleTask`, so a warm app gets it in `onNewIntent` and
     * a cold start holds it in the hub's pending slot). Always the scoped
     * id, so two engines with colliding raw ids cannot misroute.
     */
    private fun chatTapPendingIntent(tag: String, chatId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            if (chatId != null) {
                data = Uri.parse("roboco://chat/$chatId")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun rootTapPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            "root".hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** `ConnectivityManager`: a validated default network exists. */
    private fun hasValidatedNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val TAG = "RobocoNotify"
        const val CHANNEL_VERSION = 1
        const val NOTIFICATION_ID = 1
        const val BUDGET_TAG = "budget"
        val SMALL_ICON = R.drawable.zi_roboco_bow
    }
}

/**
 * The raw chime a kind's channel plays: ChimesSync names them after the
 * desktop files (`done` / `request` / `attention`), not after [NotificationKind.raw]
 * (`input` / `failed`). Kept from resource shrinking by `res/raw/keep_chimes.xml`
 * (referenced by URI string only).
 */
private fun NotificationKind.chimeResource(): String = when (this) {
    NotificationKind.Done -> "chime_done"
    NotificationKind.Input -> "chime_request"
    NotificationKind.Failed -> "chime_attention"
}

/** The system-settings channel name of a kind (Roboco web's labels). */
private fun NotificationKind.channelName(): String = when (this) {
    NotificationKind.Done -> "Task completed"
    NotificationKind.Input -> "Input required"
    NotificationKind.Failed -> "Errors and disconnections"
}

/** The system-settings channel description of a kind. */
private fun NotificationKind.channelDescription(): String = when (this) {
    NotificationKind.Done -> "When an agent finishes a run"
    NotificationKind.Input -> "When an agent needs your response"
    NotificationKind.Failed -> "When a run fails or the connection remains unavailable"
}

/** #699's vibration patterns (pr699:core/Notifier.kt:61-63), in ms. */
private fun NotificationKind.vibrationPattern(): LongArray = when (this) {
    NotificationKind.Done -> longArrayOf(0, 24, 40, 30)
    NotificationKind.Input -> longArrayOf(0, 18, 90, 18)
    NotificationKind.Failed -> longArrayOf(0, 35, 55, 45)
}
