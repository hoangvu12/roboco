package sh.roboco.android.core

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `Prefs.groupByProject` (android-ui-parity #40) — the port of
 * `AppModel.groupByProject` (`ios:App/AppModel.swift:16-33`): the `false`
 * default for an absent key, the immediate flip with background
 * persistence, the restart round-trip, and the same-value no-op (the iOS
 * `guard`).
 *
 * Plain JUnit 4: DataStore runs over a temp file on the test scheduler,
 * through the OkioStorage-backed [testPrefsStore] — the File-backed factory
 * commits with `File.renameTo` when SDK_INT reads the stub's 0, and on
 * Windows `renameTo` cannot replace the file, so every second write failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefsTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    /** One live Prefs at a time; the scope is cancellable for the restart test. */
    private fun TestScope.newPrefs(fileName: String): Pair<Prefs, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = testPrefsStore(scope, File(tmpFolder.root, "$fileName.preferences_pb"))
        return Prefs(store, scope, io = StandardTestDispatcher(testScheduler)) to scope
    }

    @Test
    fun groupByProjectDefaultsToFalse() = runTest {
        val (prefs, _) = newPrefs("default")
        advanceUntilIdle()
        assertEquals(false, prefs.groupByProject.value)
        // awaitLoaded is idempotent and never flips the value.
        prefs.awaitLoaded()
        assertEquals(false, prefs.groupByProject.value)
    }

    @Test
    fun settingTheSameValueCausesNoEmission() = runTest {
        val (prefs, _) = newPrefs("same")
        advanceUntilIdle()
        val emissions = mutableListOf<Boolean>()
        // advanceUntilIdle() never runs backgroundScope-only work, so a
        // plain backgroundScope collector would never start: collect on an
        // unconfined dispatcher (the kotlinx-coroutines-test flow pattern).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { prefs.groupByProject.toList(emissions) }
        advanceUntilIdle()
        assertEquals(listOf(false), emissions)

        // The same value twice: the StateFlow never notifies (the iOS guard).
        prefs.setGroupByProject(false)
        advanceUntilIdle()
        assertEquals(listOf(false), emissions)

        prefs.setGroupByProject(true)
        advanceUntilIdle()
        assertEquals(listOf(false, true), emissions)

        prefs.setGroupByProject(true)
        advanceUntilIdle()
        assertEquals(listOf(false, true), emissions)
    }

    @Test
    fun aFlipLandsImmediatelyAndPersists() = runTest {
        val (prefs, scope) = newPrefs("persist")
        advanceUntilIdle()

        prefs.setGroupByProject(true)
        // The StateFlow flips before the write has run.
        assertEquals(true, prefs.groupByProject.value)
        advanceUntilIdle()

        // A process restart over the same file reads the stored value.
        scope.cancel()
        scope.coroutineContext.job.join()
        val (restarted, _) = newPrefs("persist")
        restarted.awaitLoaded()
        assertEquals(true, restarted.groupByProject.value)
    }

    @Test
    fun togglingFlipsBothWays() = runTest {
        val (prefs, _) = newPrefs("toggle")
        advanceUntilIdle()
        prefs.toggleGroupByProject()
        assertEquals(true, prefs.groupByProject.value)
        advanceUntilIdle()
        prefs.toggleGroupByProject()
        assertEquals(false, prefs.groupByProject.value)
    }
}
