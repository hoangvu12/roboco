package sh.roboco.android.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch-prompt policy (android-next#04): "Later" dismisses for this
 * launch only (it prompts again on the next launch — a fresh process
 * starts fresh), never a second prompt in the same process, and the
 * prompt is off entirely for non-release builds. Plain JVM.
 */
class LaunchPromptStateTest {

    @Test
    fun anUpdateFoundOnALaunchCheckPrompts() {
        val p = LaunchPromptState(installEnabled = true)
        assertTrue(p.shouldPrompt(updateAvailable = true))
    }

    @Test
    fun noUpdateOrNotEnabledMeansNoPrompt() {
        assertFalse(LaunchPromptState(true).shouldPrompt(updateAvailable = false))
        assertFalse(LaunchPromptState(false).shouldPrompt(updateAvailable = true))
    }

    @Test
    fun laterDismissesForTheRestOfTheLaunch() {
        val p = LaunchPromptState(true)
        p.onShown()
        p.onDeclined()
        assertFalse(p.shouldPrompt(updateAvailable = true))
        // The hourly re-check finds it again: still no second prompt.
        assertFalse(p.shouldPrompt(updateAvailable = true))
    }

    @Test
    fun aPromptedAndAbandonedAlertNeverRepeatsInTheSameProcess() {
        // The user tapped Update (the alert morphed; no decline) and the
        // download was cancelled: the prompt must not come back.
        val p = LaunchPromptState(true)
        p.onShown()
        assertFalse(p.shouldPrompt(updateAvailable = true))
    }

    @Test
    fun aFreshProcessStartsFresh() {
        // "Later" survives only the process; the next launch's state is a
        // new instance, so it prompts again.
        val first = LaunchPromptState(true)
        first.onShown()
        first.onDeclined()
        val second = LaunchPromptState(true)
        assertTrue(second.shouldPrompt(updateAvailable = true))
    }
}
