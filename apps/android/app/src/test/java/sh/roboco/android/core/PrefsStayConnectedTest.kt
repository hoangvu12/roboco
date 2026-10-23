package sh.roboco.android.core

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `Prefs.stayConnected` (ADR 0010): off by default (opt-in), the
 * immediate flip with background persistence, the restart round-trip and
 * the flip-before-load rule. Plain JUnit 4 over a temp-file DataStore,
 * like [PrefsAppearanceTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefsStayConnectedTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private class Live(val prefs: Prefs, val scope: CoroutineScope)

    private fun TestScope.newPrefs(fileName: String): Live {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = testPrefsStore(scope, File(tmpFolder.root, "$fileName.preferences_pb"))
        return Live(Prefs(store, scope, io = StandardTestDispatcher(testScheduler)), scope)
    }

    private suspend fun shutdown(live: Live) {
        live.scope.cancel()
        live.scope.coroutineContext.job.join()
    }

    @Test
    fun stayConnectedIsOffByDefault() = runTest {
        val live = newPrefs("default")
        live.prefs.awaitLoaded()
        assertFalse(live.prefs.stayConnected.value)
        shutdown(live)
    }

    @Test
    fun setStayConnectedFlipsImmediatelyAndPersistsAcrossARestart() = runTest {
        val live = newPrefs("persist")
        advanceUntilIdle()
        live.prefs.setStayConnected(true)
        assertTrue("the StateFlow flips before the write", live.prefs.stayConnected.value)
        advanceUntilIdle()
        shutdown(live)

        val restarted = newPrefs("persist")
        restarted.prefs.awaitLoaded()
        assertTrue(restarted.prefs.stayConnected.value)
        // Turning it off persists too.
        restarted.prefs.setStayConnected(false)
        advanceUntilIdle()
        shutdown(restarted)
        val again = newPrefs("persist")
        again.prefs.awaitLoaded()
        assertFalse(again.prefs.stayConnected.value)
        shutdown(again)
    }

    @Test
    fun aFlipBeforeTheFirstReadWins() = runTest {
        val seed = newPrefs("race")
        advanceUntilIdle()
        seed.prefs.setStayConnected(true)
        advanceUntilIdle()
        shutdown(seed)

        // On and off again before the disk read (which holds true) lands:
        // the user's off sticks.
        val live = newPrefs("race")
        live.prefs.setStayConnected(true)
        live.prefs.setStayConnected(false)
        live.prefs.awaitLoaded()
        assertFalse(live.prefs.stayConnected.value)
        shutdown(live)
    }
}
