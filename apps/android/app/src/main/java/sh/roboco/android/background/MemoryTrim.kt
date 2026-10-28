package sh.roboco.android.background

/**
 * Wave-10 43 (open upstream PR #626, Android side): what one
 * `ComponentCallbacks2.onTrimMemory(level)` asks of the core's warm
 * sessions. `null` — nothing: `TRIM_MEMORY_UI_HIDDEN` is the backgrounding
 * the lifecycle observer already answers (`onBackground` closes the quiet
 * sessions), and the mildest running level leaves the budget alone;
 * `false` — a shrunken budget (a quarter); `true` — critical: keep only
 * what is on screen, busy, or the most recently used. The levels are the
 * framework's values, spelled out because several constants are
 * deprecated from API 34 (the system still delivers them). Pure.
 */
fun trimIsCritical(level: Int): Boolean? = when {
    level == TrimLevels.UI_HIDDEN -> null
    level >= TrimLevels.BACKGROUND -> true
    level >= TrimLevels.RUNNING_CRITICAL -> true
    level >= TrimLevels.RUNNING_LOW -> false
    else -> null
}

/** `ComponentCallbacks2.TRIM_MEMORY_*`. */
object TrimLevels {
    const val RUNNING_MODERATE = 5
    const val RUNNING_LOW = 10
    const val RUNNING_CRITICAL = 15
    const val UI_HIDDEN = 20
    const val BACKGROUND = 40
    const val MODERATE = 60
    const val COMPLETE = 80
}
