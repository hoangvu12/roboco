package sh.roboco.android.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
import sh.roboco.android.design.Appearance

/**
 * `Prefs.appearance` (android-ui-parity #96) — the port of the
 * `UserDefaults` integer `"appearance"` read by
 * `SceneDelegate.swift:12-16`: the `System` default for an absent key,
 * the immediate flip with background persistence, the restart round-trip,
 * the unknown-value normalisation (`7` → System, iOS's
 * `?? .unspecified`), the equal-value no-op (nothing emitted, nothing
 * written) and the tap-before-load rule (the user's choice beats a
 * later-arriving first disk read).
 *
 * Plain JUnit 4: DataStore runs over a temp file on the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefsAppearanceTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    /** One live Prefs at a time; the scope is cancellable for the restart tests. */
    private class Live(val prefs: Prefs, val store: DataStore<Preferences>, val scope: CoroutineScope)

    private fun TestScope.newPrefs(fileName: String): Live {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        // OkioStorage-backed: the File storage cannot overwrite on Windows (see testPrefsStore).
        val store = testPrefsStore(scope, File(tmpFolder.root, "$fileName.preferences_pb"))
        return Live(Prefs(store, scope, io = StandardTestDispatcher(testScheduler)), store, scope)
    }

    /** Releases the file so a later [newPrefs] can reopen it (a restart). */
    private suspend fun TestScope.shutdown(live: Live) {
        live.scope.cancel()
        live.scope.coroutineContext.job.join()
    }

    /** Writes [appearance] to disk as a previous process would have. */
    private suspend fun TestScope.seedFile(fileName: String, appearance: Appearance) {
        val first = newPrefs(fileName)
        first.prefs.setAppearance(appearance)
        advanceUntilIdle()
        shutdown(first)
    }

    @Test
    fun appearanceDefaultsToSystemWithAnEmptyStore() = runTest {
        val live = newPrefs("default")
        advanceUntilIdle()
        assertEquals(Appearance.System, live.prefs.appearance.value)
        // awaitLoaded is idempotent and never flips the value.
        live.prefs.awaitLoaded()
        assertEquals(Appearance.System, live.prefs.appearance.value)
        shutdown(live)
    }

    @Test
    fun setAppearanceFlipsImmediatelyAndPersists() = runTest {
        val live = newPrefs("persist")
        advanceUntilIdle()

        live.prefs.setAppearance(Appearance.Dark)
        // The StateFlow flips before the write has run.
        assertEquals(Appearance.Dark, live.prefs.appearance.value)
        advanceUntilIdle()

        // A process restart over the same file reads the stored value.
        shutdown(live)
        val restarted = newPrefs("persist")
        restarted.prefs.awaitLoaded()
        assertEquals(Appearance.Dark, restarted.prefs.appearance.value)
        shutdown(restarted)
    }

    @Test
    fun anUnknownStoredValueReadsAsSystem() = runTest {
        // Seed the file with the raw integer 7 — only an external writer
        // (a backup restore, an older build) can put it there.
        val live = newPrefs("seven")
        live.store.edit { it[PrefKeys.APPEARANCE] = 7 }
        advanceUntilIdle()
        live.prefs.awaitLoaded()
        assertEquals(Appearance.System, live.prefs.appearance.value)
        shutdown(live)
    }

    @Test
    fun settingTheCurrentValueEmitsNothingAndWritesNothing() = runTest {
        val live = newPrefs("same")

        // The StateFlow's emissions and the store's data flow both prove
        // the no-op: nothing notifies, no edit lands.
        val values = mutableListOf<Appearance>()
        val writes = mutableListOf<Preferences>()
        // advanceUntilIdle() never runs backgroundScope-only work, so both
        // collectors run on an unconfined dispatcher (the
        // kotlinx-coroutines-test flow pattern).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            live.prefs.appearance.toList(values)
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            live.store.data.toList(writes)
        }
        advanceUntilIdle()
        assertEquals(listOf(Appearance.System), values)
        assertEquals(1, writes.size)

        // A real change: the StateFlow flips at once, the write lands.
        live.prefs.setAppearance(Appearance.Dark)
        assertEquals("the StateFlow flips before the write has run", Appearance.Dark, live.prefs.appearance.value)
        advanceUntilIdle()
        assertEquals(listOf(Appearance.System, Appearance.Dark), values)
        assertEquals(2, writes.size)

        // The same value: the guard returns before anything is launched —
        // nothing notifies, no edit lands.
        live.prefs.setAppearance(Appearance.Dark)
        advanceUntilIdle()
        assertEquals(listOf(Appearance.System, Appearance.Dark), values)
        assertEquals("no write was made", 2, writes.size)
        shutdown(live)
    }

    @Test
    fun aSetMadeBeforeTheInitialLoadCompletesIsNotOverwritten() = runTest {
        // The disk holds Dark from a previous process.
        seedFile("late", Appearance.Dark)
        val live = newPrefs("late")
        // The first disk read has not run yet (StandardTestDispatcher).
        live.prefs.setAppearance(Appearance.Light)
        assertEquals(Appearance.Light, live.prefs.appearance.value)
        advanceUntilIdle()
        assertEquals("the tap wins over the later-arriving stored value", Appearance.Light, live.prefs.appearance.value)
        shutdown(live)
    }
}
