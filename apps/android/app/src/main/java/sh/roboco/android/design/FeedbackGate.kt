package sh.roboco.android.design

/**
 * What the app is doing right now, read at most once per decision. The
 * implementation answers from a `@Volatile` field refreshed by the activity
 * lifecycle (STARTED/STOPPED via `LifecycleStartEffect` in `ProvideFeedback`):
 * when the screen turns off the activity stops, so no broadcast receiver or
 * `PowerManager` polling is needed.
 */
interface FeedbackEnvironment {
    /** The app is in the foreground (activity STARTED). */
    val active: Boolean

    val hasVibrator: Boolean
}

/** Why a haptic did not play. Logged by debug builds. */
enum class Skipped(val label: String) {
    NoVibrator("no vibrator"),
    Inactive("app not in foreground"),
    Rate("rate limited"),
}

sealed interface Decision {
    data object Play : Decision
    data class Skip(val why: Skipped) : Decision
}

/**
 * Decides whether a haptic plays: a vibrator, the foreground, and rate
 * limiting. Pure and clock-injected so its rules are unit tested; the engine
 * ([AndroidFeedback]) acts on the result.
 *
 * Rules, in order:
 *  1. `!env.hasVibrator` → [Skipped.NoVibrator]
 *  2. `!env.active` → [Skipped.Inactive]
 *  3. the moment's own [HapticSpec.minGapMs]
 *  4. [FeedbackGate.GLOBAL_HAPTIC_GAP_MS]: two equal-or-lighter haptics never
 *     stack; a higher priority cuts through
 *  5. otherwise `Play`, recorded as the moment's and the global "last" time
 *
 * A skipped event records nothing, so it does not start a gap.
 */
class FeedbackGate(
    private val env: FeedbackEnvironment,
    private val clock: () -> Long,
) {
    private val lastHaptic = HashMap<Haptic, Long>()
    private var lastAnyHaptic = Long.MIN_VALUE / 2
    private var lastAnyHapticPriority = 0

    @Synchronized
    fun haptic(haptic: Haptic): Decision {
        if (!env.hasVibrator) return Decision.Skip(Skipped.NoVibrator)
        if (!env.active) return Decision.Skip(Skipped.Inactive)
        val spec = HapticTable.spec(haptic)
        val now = clock()
        if (now - (lastHaptic[haptic] ?: Long.MIN_VALUE / 2) < spec.minGapMs) return Decision.Skip(Skipped.Rate)
        if (spec.priority <= lastAnyHapticPriority && now - lastAnyHaptic < GLOBAL_HAPTIC_GAP_MS) return Decision.Skip(Skipped.Rate)
        lastHaptic[haptic] = now
        lastAnyHaptic = now
        lastAnyHapticPriority = spec.priority
        return Decision.Play
    }

    companion object {
        const val GLOBAL_HAPTIC_GAP_MS = 25L
    }
}
