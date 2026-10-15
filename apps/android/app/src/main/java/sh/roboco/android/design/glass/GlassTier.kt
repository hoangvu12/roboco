package sh.roboco.android.design.glass

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import java.io.File

/**
 * Which material a composition draws: the Haze backdrop blur, or the flat
 * fallback fill. The look is identical whichever layer decides — when Haze
 * itself cannot blur at draw time it draws our `fallbackColorEffect`, which
 * is the same fallback fill.
 */
enum class GlassTier { Blur, Fallback }

/**
 * The tier decision (ticket 07's table): `Fallback` when the debug kill
 * switch is on, the crash guard tripped, the host view is not hardware
 * accelerated (Robolectric draws software), or the API is below
 * [Glass.BlurMinSdk]; otherwise `Blur`.
 */
fun decideGlassTier(
    sdkInt: Int,
    hardwareAccelerated: Boolean,
    guardTripped: Boolean,
    forceFallback: Boolean,
): GlassTier =
    if (forceFallback || guardTripped || !hardwareAccelerated || sdkInt < Glass.BlurMinSdk) {
        GlassTier.Fallback
    } else {
        GlassTier.Blur
    }

/** The composition-wide tier; `Fallback` outside [ProvideGlass]. */
val LocalGlassTier = staticCompositionLocalOf { GlassTier.Fallback }

/**
 * pr621's crash sentinel (`pr621:apps/android/app/src/main/java/sh/zeron/android/design/Glass.kt:43-127`,
 * logic only): a previous launch that died during the first blurred frames
 * leaves the sentinel armed, and the next launch runs the fallback fill
 * instead of risking the same RenderNode/RenderEffect crash.
 *
 * The protocol is two files in [dir] (the app cache dir):
 *  - `glass-blur-trying` — armed before the first blurred frame;
 *  - `glass-blur-ok` — written after two clean frames; arming it removed.
 *
 * [tripped] is `trying` present without `ok`, read once in the constructor
 * so the tier never flaps mid-process. Every file operation is wrapped in
 * `runCatching`: a failing filesystem must never crash the app or trip the
 * guard.
 *
 * pr621's look (PixelCopy snapshots, CPU box blur) is **not** ported — only
 * the sentinel idea.
 */
class GlassCrashGuard(private val dir: File) {

    private val trying = File(dir, TRYING)
    private val ok = File(dir, OK)

    /** True when a previous launch died between arming and confirming. */
    val tripped: Boolean = trying.exists() && !ok.exists()

    @Volatile
    private var armed = false

    @Volatile
    private var confirmed = false

    /**
     * Arms the sentinel before the first blurred frame. Runs synchronously
     * on the main thread from a `SideEffect`; a single tiny write. No-op
     * when this launch cycle is already confirmed or armed.
     */
    fun armOnce() {
        if (confirmed || armed || ok.exists()) return
        runCatching { trying.writeText("1") }
        armed = true
    }

    /**
     * Confirms two clean blurred frames: writes `ok` and removes `trying`.
     * Called from `Dispatchers.IO`. Idempotent.
     */
    fun confirm() {
        if (confirmed) return
        if (!ok.exists()) {
            runCatching {
                ok.writeText("1")
                trying.delete()
            }
        }
        confirmed = true
    }

    /** True when this launch cycle needs no arming or confirming. */
    val settled: Boolean
        get() = confirmed || ok.exists()

    companion object {
        private const val TRYING = "glass-blur-trying"
        private const val OK = "glass-blur-ok"

        @Volatile
        private var instance: GlassCrashGuard? = null

        /** The process singleton on the app cache dir. */
        fun get(context: Context): GlassCrashGuard =
            instance ?: synchronized(this) {
                instance ?: GlassCrashGuard(context.applicationContext.cacheDir).also { instance = it }
            }
    }
}

/**
 * Resolves [LocalGlassTier] for a composition (ticket 07). `RobocoTheme`
 * wraps its content in this (next to `ProvideReduceMotion`), so every
 * [GlassSurface] reads the tier without extra wiring. The
 * "Force fallback glass" gallery toggle flips [Glass.forceFallback] and this
 * re-resolves live.
 */
@Composable
fun ProvideGlass(content: @Composable () -> Unit) {
    val view = LocalView.current
    val guard = remember { GlassCrashGuard.get(view.context) }
    val forceFallback = Glass.forceFallback
    val tier = remember(forceFallback) {
        decideGlassTier(Build.VERSION.SDK_INT, view.isHardwareAccelerated, guard.tripped, forceFallback)
    }
    CompositionLocalProvider(LocalGlassTier provides tier) { content() }
}
