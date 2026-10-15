package sh.roboco.android.core

import android.app.UiModeManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.roboco.android.design.Appearance

/**
 * [AppearanceController] and the [platformNightMode] mapping
 * (android-ui-parity #96). Robolectric does not deliver a real per-app
 * configuration change for `setApplicationNightMode`, so the platform
 * push is tested through a fake [NightModeSink] and the mapping through
 * the constant values — never a shadow.
 *
 * Plain JUnit 4: the controller runs over a temp DataStore on the test
 * scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceControllerTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private class RecordingSink : NightModeSink {
        val applied = mutableListOf<Appearance>()

        override fun apply(appearance: Appearance) {
            applied += appearance
        }
    }

    @Test
    fun thePlatformMappingIsExplicit() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, platformNightMode(Appearance.System))
        assertEquals(UiModeManager.MODE_NIGHT_NO, platformNightMode(Appearance.Light))
        assertEquals(UiModeManager.MODE_NIGHT_YES, platformNightMode(Appearance.Dark))
        // The ticket's table: 0 / 1 / 2 — a `when`, not storedValue.
        assertEquals(0, platformNightMode(Appearance.System))
        assertEquals(1, platformNightMode(Appearance.Light))
        assertEquals(2, platformNightMode(Appearance.Dark))
    }

    @Test
    fun nothingIsAppliedBeforeTheLoadThenExactlyTheStoredValue() = runTest {
        val (prefs, scope) = prefsHolding("stored", Appearance.Dark)
        val sink = RecordingSink()
        val controller = AppearanceController(prefs, sink, scope)

        val job = controller.start()
        // StandardTestDispatcher: the collector is queued, not started, so
        // nothing reaches the platform before the disk read completes.
        assertTrue("nothing applied before the load", sink.applied.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf(Appearance.Dark), sink.applied)

        job.cancel()
        scope.cancel()
        scope.coroutineContext.job.join()
    }

    @Test
    fun changesAfterTheLoadArePushedOnceEach() = runTest {
        val (prefs, scope) = prefsHolding("changes", Appearance.Dark)
        val sink = RecordingSink()
        val controller = AppearanceController(prefs, sink, scope)
        val job = controller.start()
        advanceUntilIdle()
        assertEquals(listOf(Appearance.Dark), sink.applied)

        prefs.setAppearance(Appearance.Light)
        advanceUntilIdle()
        assertEquals(listOf(Appearance.Dark, Appearance.Light), sink.applied)

        // The StateFlow is distinct-until-changed: an equal write adds nothing.
        prefs.setAppearance(Appearance.Light)
        advanceUntilIdle()
        assertEquals(listOf(Appearance.Dark, Appearance.Light), sink.applied)

        job.cancel()
        scope.cancel()
        scope.coroutineContext.job.join()
    }

    /** One live Prefs at a time over a temp file that already holds [appearance]. */
    private suspend fun TestScope.prefsHolding(fileName: String, appearance: Appearance): Pair<Prefs, CoroutineScope> {
        val seeder = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val file = File(tmpFolder.root, "$fileName.preferences_pb")
        // OkioStorage-backed: the File storage cannot overwrite on Windows (see testPrefsStore).
        val seedStore = testPrefsStore(seeder, file)
        val seederPrefs = Prefs(seedStore, seeder, io = StandardTestDispatcher(testScheduler))
        seederPrefs.setAppearance(appearance)
        advanceUntilIdle()
        seeder.cancel()
        seeder.coroutineContext.job.join()

        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = testPrefsStore(scope, file)
        return Prefs(store, scope, io = StandardTestDispatcher(testScheduler)) to scope
    }
}
