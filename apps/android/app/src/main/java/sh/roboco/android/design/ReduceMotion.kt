package sh.roboco.android.design

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * The platform's Reduce Motion state (ticket 04), adapted from
 * `UIAccessibility.isReduceMotionEnabled`.
 *
 * Android has no single "reduce motion" switch: the accessibility "Remove
 * animations" switch zeroes both `ANIMATOR_DURATION_SCALE` and
 * `TRANSITION_ANIMATION_SCALE` (developer options set them one at a time).
 * Either one at 0 counts as reduced. `WINDOW_ANIMATION_SCALE` is not read: it
 * only affects window transitions, which Compose does not drive.
 *
 * Consumers must branch on [isReduced] / [LocalReduceMotion] exactly at the 13
 * iOS behaviours that obey Reduce Motion — not on Compose's own duration
 * scaling, which cannot express the static frames, frame loops and debug
 * override the parity port needs.
 */
object ReduceMotion {

    /** The system's "remove animations" state, read live through a ContentObserver. */
    private var systemReduced by mutableStateOf(false)

    /**
     * Debug-only override (the design gallery's "Force reduce motion" toggle,
     * ticket 22). `null` follows the system, `true` forces reduced, `false`
     * forces full motion. Only debug code writes this.
     */
    var debugOverride: Boolean? by mutableStateOf(null)

    /**
     * The effective state: the debug override, else the system state. Reads
     * snapshot state, so it is safe from any thread; in coroutines follow it
     * with `snapshotFlow { ReduceMotion.isReduced }`.
     */
    val isReduced: Boolean get() = debugOverride ?: systemReduced

    private var installed = false
    private var observer: ContentObserver? = null
    private var resolver: ContentResolver? = null

    /**
     * Starts following the system setting. Idempotent. Call on the main thread
     * with any Context: the *application* context is kept (an Activity context
     * would leak), and the observer is process-wide, living for the life of
     * the app and never unregistered outside tests.
     */
    @MainThread
    fun install(context: Context) {
        val cr = context.applicationContext.contentResolver
        systemReduced = readSystem(cr)
        if (installed && cr === resolver) return
        // A different resolver means a new Application (Robolectric builds one
        // per test): move the observer, or it keeps listening to a dead one.
        observer?.let { obs -> resolver?.unregisterContentObserver(obs) }
        resolver = cr
        installed = true
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemReduced = readSystem(cr)
            }
        }
        observer = contentObserver
        for (name in listOf(Settings.Global.ANIMATOR_DURATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE)) {
            cr.registerContentObserver(Settings.Global.getUriFor(name), false, contentObserver)
        }
    }

    /** True when either animation scale is 0 (`Settings.Global.getFloat`, no permission). */
    fun readSystem(cr: ContentResolver): Boolean =
        Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f

    /** Unregisters the observer and clears every field. Tests only. */
    @VisibleForTesting
    internal fun resetForTest() {
        observer?.let { obs -> resolver?.unregisterContentObserver(obs) }
        observer = null
        resolver = null
        installed = false
        debugOverride = null
        systemReduced = false
    }
}

/** Reduce Motion as seen by composition: `true` when animations should be dropped. */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * Follows the system "remove animations" setting (and
 * [ReduceMotion.debugOverride]) and provides [LocalReduceMotion]. Only readers
 * recompose when the value flips.
 *
 * `RobocoTheme` (ticket 01) wraps its content in this — whichever of tickets
 * 01/04 landed second made that edit; ticket 35's app root relies on it being
 * inside `RobocoTheme`. `@Preview`s are safe: `LocalInspectionMode` suppresses
 * the ContentObserver registration (layoutlib has no real ContentResolver
 * observer support), so previews render with full motion unless
 * [ReduceMotion.debugOverride] is set.
 */
@Composable
fun ProvideReduceMotion(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    // remember runs during composition, before the local below is read, so the
    // very first frame already sees the system value (unlike SideEffect).
    remember(context, preview) {
        if (!preview) ReduceMotion.install(context)
        Unit
    }
    CompositionLocalProvider(LocalReduceMotion provides ReduceMotion.isReduced, content = content)
}
