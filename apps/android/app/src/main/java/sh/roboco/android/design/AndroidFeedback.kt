package sh.roboco.android.design

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect
import java.lang.ref.WeakReference

private const val TAG = "RobocoFeedback"

/**
 * What this device can render, probed once per process (the `Vibrator`
 * binder calls are not free) and cached in a `@Volatile` companion field.
 */
data class HapticHardware(
    val sdk: Int,
    val primitives: Set<Int>,
    val hasVibrator: Boolean,
) {
    fun caps(hasView: Boolean) = HapticCapabilities(sdk, primitives, hasView)

    companion object {
        @Volatile
        private var cached: HapticHardware? = null

        fun probe(context: Context, vibrator: Vibrator?): HapticHardware {
            cached?.let { return it }
            val hardware = HapticHardware(
                sdk = Build.VERSION.SDK_INT,
                primitives = if (vibrator == null) emptySet() else ALL_PRIMITIVES.filter { vibrator.areAllPrimitivesSupported(it) }.toSet(),
                hasVibrator = vibrator?.hasVibrator() == true,
            )
            cached = hardware
            return hardware
        }
    }
}

/**
 * How a [HapticPlan] reaches the platform. The engine asks the output, so
 * tests can record plans instead of vibrating.
 */
interface HapticOutput {
    /** A view is attached to a window. */
    val hasView: Boolean

    /** True when the platform accepted the plan. */
    fun perform(plan: HapticPlan): Boolean
}

/**
 * The real output: `performHapticFeedback` on the window's root view for view
 * constants (OEM tuned, and it honours the system "Touch feedback" setting by
 * itself), the `Vibrator` for compositions and predefined effects.
 */
class SystemHapticOutput(private val vibrator: Vibrator?) : HapticOutput {

    private var viewRef = WeakReference<View?>(null)

    /** The window's view, for `performHapticFeedback`. */
    fun attach(view: View?) {
        viewRef = WeakReference(view)
    }

    override val hasView: Boolean
        get() = viewRef.get()?.isAttachedToWindow == true

    override fun perform(plan: HapticPlan): Boolean = try {
        when (plan) {
            is HapticPlan.ViewConstant ->
                viewRef.get()?.takeIf { it.isAttachedToWindow }?.performHapticFeedback(plan.constant) == true
            is HapticPlan.Composition -> vibrate(
                VibrationEffect.startComposition().apply {
                    plan.steps.forEach { addPrimitive(it.primitive, it.scale, it.delayMs) }
                }.compose(),
            )
            is HapticPlan.Predefined -> vibrate(VibrationEffect.createPredefined(plan.effect))
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "haptic failed", e)
        false
    }

    private fun vibrate(effect: VibrationEffect): Boolean {
        val v = vibrator ?: return false
        if (Build.VERSION.SDK_INT >= 33) {
            // Touch usage: obeys the user's touch-feedback intensity.
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            // Maps to touch intensity on these releases.
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
        return true
    }
}

/**
 * Haptics for the whole app. See [FeedbackGate] for when a moment plays and
 * [HapticPlanner] for how it is felt on this hardware.
 *
 * Debug builds log one line per event to the `RobocoFeedback` tag: what
 * played (and as what) or why it did not.
 */
class AndroidFeedback(
    private val hardware: HapticHardware,
    private val output: HapticOutput,
    clock: () -> Long = SystemClock::uptimeMillis,
    private val log: ((String) -> Unit)? = null,
) : Feedback {

    /** The activity is STARTED; false until `ProvideFeedback`'s ON_START. */
    @Volatile
    private var foreground = false

    private val env = object : FeedbackEnvironment {
        override val active: Boolean get() = foreground
        override val hasVibrator: Boolean get() = hardware.hasVibrator
    }

    private val gate = FeedbackGate(env, clock)

    /** The app is in the foreground (activity STARTED). Nothing plays otherwise. */
    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
    }

    override fun haptic(haptic: Haptic) {
        when (val decision = gate.haptic(haptic)) {
            is Decision.Skip -> debug("haptic $haptic skip: ${decision.why.label}")
            Decision.Play -> {
                var plan = HapticPlanner.plan(haptic, hardware.caps(output.hasView))
                var ok = output.perform(plan)
                if (!ok && plan is HapticPlan.ViewConstant) {
                    // A detached or refusing view would otherwise give silence:
                    // re-plan without the view and run that instead.
                    plan = HapticPlanner.plan(haptic, hardware.caps(hasView = false))
                    ok = output.perform(plan)
                }
                debug("haptic $haptic ${if (ok) "play" else "fail"} ${HapticPlanner.describe(plan)}")
            }
        }
    }

    override fun cue(cue: Cue) {
        debug("cue $cue skip: no sound engine (android-app#28)")
    }

    private fun debug(message: String) {
        log?.invoke(message)
    }
}

/**
 * Installs the engine for the composition tree. Call **once**, at the root
 * (the app root and the gallery activity): the content of dialogs and popups
 * inherits `LocalFeedback` and uses the root window's view, which stays
 * attached while a dialog is up. Inspection mode (previews) gets
 * [NoFeedback].
 */
@Composable
fun ProvideFeedback(content: @Composable () -> Unit) {
    if (LocalInspectionMode.current) {
        CompositionLocalProvider(LocalFeedback provides NoFeedback, content = content)
        return
    }
    val context = LocalContext.current
    val view = LocalView.current
    val feedback = remember {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        val output = SystemHapticOutput(vibrator)
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        output to AndroidFeedback(
            HapticHardware.probe(context, vibrator),
            output,
            log = if (debuggable) { m -> Log.d(TAG, m) } else null,
        )
    }
    DisposableEffect(view) {
        feedback.first.attach(view)
        onDispose { feedback.first.attach(null) }
    }
    LifecycleStartEffect(feedback.second) {
        feedback.second.setForeground(true)
        onStopOrDispose { feedback.second.setForeground(false) }
    }
    CompositionLocalProvider(LocalFeedback provides feedback.second, content = content)
}
