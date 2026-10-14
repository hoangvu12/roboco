package sh.roboco.android.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS `PushNotifications.Kind` (`ios:App/PushNotifications.swift:13-23`).
 * [title] is also the notification body text (android-app#28's copy table)
 * and the Settings row title (android-ui-parity#99).
 */
enum class NotificationKind(val raw: String, val title: String) {
    Done("done", "Run finished"),
    Input("input", "Waiting on your input"),
    Failed("failed", "Run failed");

    val prefKey: String
        get() = "notifications.$raw"
}

/**
 * The phone-local notification choices (android-app#28's delivery gates;
 * android-ui-parity#99's Settings section reads and writes the same
 * store). iOS `UserDefaults` keys kept verbatim:
 * `"notifications.enabled"` / `"notifications.<kind>"` / `"notifications.asked"`,
 * all defaulting to true except `asked`. `sync()` (the edge push) is
 * dropped: there is no edge (ADR 0003/0004).
 *
 * SharedPreferences, not DataStore: the delivery path reads it off the
 * main thread and must never suspend (a notification arriving during
 * startup cannot wait for a DataStore read). Process-wide singleton via
 * [get]; #99 adds its remaining members here. The constructor is
 * `internal` for #99's tests, which build one over a Robolectric-backed
 * `SharedPreferences` instead of the singleton file (the ticket's test
 * seam; production always goes through [get]).
 */
class NotificationPrefs internal constructor(private val sp: SharedPreferences) {

    /** The observable choices; flips synchronously on every write. */
    @Immutable
    data class Snapshot(
        val enabled: Boolean,
        val done: Boolean,
        val input: Boolean,
        val failed: Boolean,
    ) {
        fun isOn(kind: NotificationKind): Boolean = when (kind) {
            NotificationKind.Done -> done
            NotificationKind.Input -> input
            NotificationKind.Failed -> failed
        }
    }

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** The master switch (on unless turned off here; the system permission is separate). */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) {
            sp.edit().putBoolean(KEY_ENABLED, value).apply()
            publish()
        }

    fun isOn(kind: NotificationKind): Boolean = sp.getBoolean(kind.prefKey, true)

    fun set(kind: NotificationKind, on: Boolean) {
        sp.edit().putBoolean(kind.prefKey, on).apply()
        publish()
    }

    /** android-app#28's first-session ask gate: asked once, ever. */
    var asked: Boolean
        get() = sp.getBoolean(KEY_ASKED, false)
        set(value) {
            sp.edit().putBoolean(KEY_ASKED, value).apply()
        }

    /** android-ui-parity#99: a POST_NOTIFICATIONS request was issued before. */
    var permissionRequested: Boolean
        get() = sp.getBoolean(KEY_PERMISSION_REQUESTED, false)
        set(value) {
            sp.edit().putBoolean(KEY_PERMISSION_REQUESTED, value).apply()
        }

    /** android-ui-parity#99: the system dialog will no longer show (a final denial). */
    var permissionBlocked: Boolean
        get() = sp.getBoolean(KEY_PERMISSION_BLOCKED, false)
        set(value) {
            sp.edit().putBoolean(KEY_PERMISSION_BLOCKED, value).apply()
        }

    private fun snapshot() = Snapshot(
        enabled = enabled,
        done = isOn(NotificationKind.Done),
        input = isOn(NotificationKind.Input),
        failed = isOn(NotificationKind.Failed),
    )

    private fun publish() {
        _state.value = snapshot()
    }

    companion object {
        const val FILE = "notifications"
        private const val KEY_ENABLED = "notifications.enabled"
        private const val KEY_ASKED = "notifications.asked"
        private const val KEY_PERMISSION_REQUESTED = "notifications.permissionRequested"
        private const val KEY_PERMISSION_BLOCKED = "notifications.permissionBlocked"

        /** Process-wide singleton on the application context. */
        @Volatile
        private var instance: NotificationPrefs? = null

        /** The application [instance] was built on. */
        @Volatile
        private var owner: Context? = null

        /**
         * The singleton, keyed to the application it was built on. In
         * production there is exactly one application per process, so this
         * builds once; a JVM that hosts several applications in turn
         * (Robolectric rebuilds the app per test method while statics
         * survive) gets a fresh instance over the current application's
         * file instead of a stale one writing into a dead app's storage.
         */
        @Synchronized
        fun get(context: Context): NotificationPrefs {
            val app = context.applicationContext
            instance?.let { if (owner === app) return it }
            return NotificationPrefs(
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE),
            ).also {
                instance = it
                owner = app
            }
        }
    }
}
