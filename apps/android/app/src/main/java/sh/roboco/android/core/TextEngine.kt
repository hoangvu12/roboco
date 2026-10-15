package sh.roboco.android.core

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.roboco.android.BuildConfig
import uniffi.roboco_core.TextSystem

/**
 * The one process-wide [TextSystem] every transcript shares
 * (`TextEngine.shared`, `Fonts.swift:113-116`). Registered faces + the
 * [AndroidMeasurer] fallback, built lazily off the main thread: reading
 * 1.6 MB of assets, enumerating system fonts and copying the bytes across
 * JNA would stall first-frame rendering, so `RobocoApplication` calls
 * [prewarm] right after the font gate and consumers await through
 * [await] (ticket 61: `TranscriptView(TextEngine.await(), listener)`).
 *
 * The instance is **never closed**: `TextSystem` is `AutoCloseable`, but
 * it lives for the whole process — no `use {}`, no activity lifecycle.
 */
object TextEngine {

    private const val TAG = "Roboco"

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prewarmStarted = false

    @Volatile
    private var system: TextSystem? = null

    /** Set (and logged) when creation ran on the main looper: a diagnostic, not an error. */
    @VisibleForTesting
    @Volatile
    var builtOnMain: Boolean = false
        private set

    /** Stores the application context; first call in `RobocoApplication.onCreate`. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Starts one daemon thread (`roboco-text-engine`) that reads [shared],
     * so the main thread never builds it. Idempotent, and does nothing
     * when the startup font gate failed (release builds show the fatal
     * screen instead of the app).
     */
    fun prewarm() {
        if (FontGate.status !is FontStatus.Ok) return
        synchronized(lock) {
            if (prewarmStarted) return
            prewarmStarted = true
        }
        Thread(
            {
                try {
                    shared
                } catch (failure: FontLoadException) {
                    FontGate.fail(failure.path)
                    // Debug builds crash with the path in logcat; release
                    // builds let the gate swap the activity to the fatal
                    // screen (ticket 03).
                    if (BuildConfig.DEBUG) throw failure
                }
            },
            "roboco-text-engine",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** The shared engine, double-checked lazy over [lock]. */
    val shared: TextSystem
        get() {
            system?.let { return it }
            synchronized(lock) {
                system?.let { return it }
                val built = create()
                system = built
                return built
            }
        }

    /** The API consumers use: the engine, built on a background dispatcher if it isn't yet. */
    suspend fun await(): TextSystem = withContext(Dispatchers.Default) { shared }

    /** Whether [shared] is already built. */
    val isReady: Boolean
        get() = system != null

    private fun create(): TextSystem {
        check(FontGate.status is FontStatus.Ok) {
            "the font gate is ${FontGate.status}: the text engine is only built behind it"
        }
        val context = appContext ?: error("TextEngine.init(context) was not called")
        if (Looper.getMainLooper().isCurrentThread) {
            Log.w(TAG, "TextEngine built on the main thread")
            builtOnMain = true
        }
        val fonts = Fonts.load(context)
        return TextSystem(fonts.faceData, AndroidMeasurer(fonts.typefaces))
    }
}
