package sh.roboco.android.composer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.roboco.android.core.testPrefsStore

/**
 * [Drafts] (android-ui-parity #84) — the store's whole contract on the
 * plain JVM, over the repo's Okio-backed [testPrefsStore] for the
 * restart-verification rows (the ticket's Verification section) and
 * [FakePrefsStore] for the pre-load rows:
 *
 * - the save rule: Swift-blank removes, otherwise verbatim and untrimmed;
 * - the synchronous mirror (`peek` right after `save`) and the disk
 *   landing after the write job joins;
 * - restarts: a new DataStore in a new scope over the same file (the
 *   ticket's restart pattern — the old scope is cancelled and joined
 *   first, one active instance per file);
 * - order-insensitive writes (each job writes the mirror's current
 *   value);
 * - a save issued before the first read completes survives the load,
 *   over an older disk value;
 * - `forgetEngine` removes only that engine's drafts and makes later
 *   saves for it no-ops;
 * - the `new-session` literal key and engine-scoped chat keys.
 *
 * `StandardTestDispatcher(testScheduler)` serves as `io` and the
 * store scopes' dispatcher; [foregroundAppScope] is the Drafts scope (the
 * app-scope stand-in). The log seam (`draftsLogW`) is swapped because
 * the stub `android.util.Log` throws outside Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftsTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val logged = mutableListOf<Pair<String, Throwable?>>()
    private var savedLog: ((String, Throwable?) -> Unit)? = null

    @Before
    fun swapLog() {
        savedLog = draftsLogW
        draftsLogW = { message, error -> logged += message to error }
    }

    @After
    fun restoreLog() {
        draftsLogW = savedLog!!
    }

    private class Fixture(
        val store: DataStore<Preferences>,
        val storeScope: CoroutineScope,
        val drafts: Drafts,
    )

    /** One Drafts over a real file store; the store scope is cancellable for restarts. */
    private fun TestScope.newDrafts(): Fixture {
        val storeScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = testPrefsStore(storeScope, storeFile())
        return Fixture(store, storeScope, Drafts(store, foregroundAppScope(), StandardTestDispatcher(testScheduler)))
    }

    /** A process restart: new DataStore + new Drafts over the same file, in a new scope. */
    private suspend fun TestScope.restart(f: Fixture): Fixture {
        f.storeScope.cancel()
        f.storeScope.coroutineContext.job.join()
        return newDrafts()
    }

    private fun storeFile(): File = File(tmp.root, "t.preferences_pb")

    /** The names currently on the disk (the store's own view). */
    private suspend fun diskNames(store: DataStore<Preferences>): Set<String> =
        store.data.first().asMap().keys.map { it.name }.toSet()

    /** The value currently on the disk for [key], or null (absent). */
    private suspend fun diskValue(store: DataStore<Preferences>, key: DraftKey): String? =
        store.data.first()[stringPreferencesKey(key.storageName)]

    // ── keys ────────────────────────────────────────────────────────────

    @Test
    fun chatKeysCarryEngineAndScopedChatIdNewSessionKeepsTheLiteral() {
        assertEquals("chat:e1:engine:v1:abc", DraftKey.Chat("e1", "engine:v1:abc").storageName)
        // The forget prefix never clips a longer engine id: "E10" does not
        // match "chat:E1:".
        assertEquals("chat:E10:c", DraftKey.Chat("E10", "c").storageName)
        assertEquals("new-session", DraftKey.NewSession.storageName)
    }

    // ── the save rule ───────────────────────────────────────────────────

    @Test
    fun blankTextFollowsSwiftsWhitespaceAndNewlinesExactly() {
        // Swift CharacterSet.whitespacesAndNewlines: U+0009–U+000D, U+0085
        // and the Zs/Zl/Zp separator categories. Everything else is
        // content.
        val blank = listOf(
            "", " ", "  ",
            "\u0009", "\u000A", "\u000B", "\u000C", "\u000D",
            "\u0085",
            "\u00A0", "\u1680", "\u2000", "\u2001", "\u2002", "\u2003", "\u2004",
            "\u2005", "\u2006", "\u2007", "\u2008", "\u2009", "\u200A",
            "\u2028", "\u2029", "\u202F", "\u205F", "\u3000",
            "\n\t \r", " \u000B\u0085\u3000\u2028",
        )
        for (text in blank) {
            assertTrue("expected blank: ${text.map { it.code }}", isDraftBlank(text))
        }
        val content = listOf(
            "a", " a ", "a ", " a",
            // Kotlin's isBlank() treats these as blank; Swift does not.
            "\u001C", "\u001D", "\u001E", "\u001F",
            // Zero-width space: category Cf, not in Swift's set.
            "\u200B", "\u200Ba", "a\u200B",
        )
        for (text in content) {
            assertFalse("expected content: ${text.map { it.code }}", isDraftBlank(text))
        }
    }

    @Test
    fun savingKeepsTheTextVerbatimAcrossPeekAndRestart() = runTest {
        val f = newDrafts()
        val key = DraftKey.Chat("e1", "engine:v1:abc")
        f.drafts.save(key, "  hi\n")
        // The mirror is synchronous: a peek right after the save sees it,
        // untrimmed — pop, then immediately reopen the same chat.
        assertEquals("  hi\n", f.drafts.peek(key))
        advanceUntilIdle()
        assertEquals("  hi\n", diskValue(f.store, key))
        // A restart (a new DataStore in a new scope over the same file):
        val g = restart(f)
        assertEquals("  hi\n", g.drafts.load(key))
    }

    @Test
    fun savingBlankRemovesTheEntryFromMirrorAndDisk() = runTest {
        val f = newDrafts()
        f.drafts.warm()
        advanceUntilIdle()
        val key = DraftKey.Chat("e1", "c1")
        f.drafts.save(key, "x").join()
        f.drafts.save(key, " \n ").join()
        advanceUntilIdle()
        assertEquals("", f.drafts.peek(key))
        assertFalse(diskNames(f.store).contains(key.storageName))
    }

    @Test
    fun rapidSavesAreOrderInsensitiveTheLastMirrorStateWins() = runTest {
        val f = newDrafts()
        f.drafts.warm()
        advanceUntilIdle()
        val key = DraftKey.Chat("e1", "c1")
        val a = f.drafts.save(key, "a")
        val b = f.drafts.save(key, "ab")
        val c = f.drafts.save(key, "abc")
        // Joined out of issue order: every job writes the mirror's CURRENT
        // value for its key, so whatever lands last, the disk ends "abc".
        b.join()
        a.join()
        c.join()
        advanceUntilIdle()
        assertEquals("abc", f.drafts.peek(key))
        assertEquals("abc", diskValue(f.store, key))
    }

    // ── the pending load ────────────────────────────────────────────────

    @Test
    fun aSaveIssuedBeforeTheLoadCompletesSurvivesTheLoad() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = FakePrefsStore(mapOf("chat:e1:c1" to "old"), gate)
        val drafts = Drafts(store, foregroundAppScope(), StandardTestDispatcher(testScheduler))
        drafts.warm()
        runCurrent() // the read is now parked on the gate
        assertFalse(drafts.isLoaded)

        val key = DraftKey.Chat("e1", "c1")
        val write = drafts.save(key, "new")
        // The pre-load mutation is already in the mirror.
        assertEquals("new", drafts.peek(key))

        gate.complete(Unit)
        write.join()
        advanceUntilIdle()
        // The disk held "old"; the replay wrote "new" on top of it, and
        // the write job persisted the mirror's value.
        assertEquals("new", drafts.peek(key))
        assertEquals("new", store.stored(key))
    }

    // ── forget ──────────────────────────────────────────────────────────

    @Test
    fun forgetEngineRemovesOnlyThatEnginesDraftsAndIgnoresLaterSaves() = runTest {
        val f = newDrafts()
        f.drafts.warm()
        advanceUntilIdle()
        val e1a = DraftKey.Chat("E1", "c1")
        val e1b = DraftKey.Chat("E1", "c2")
        val e2 = DraftKey.Chat("E2", "c1")
        f.drafts.save(e1a, "a").join()
        f.drafts.save(e1b, "b").join()
        f.drafts.save(e2, "e2").join()
        f.drafts.save(DraftKey.NewSession, "ns").join()
        advanceUntilIdle()

        f.drafts.forgetEngine("E1").join()
        advanceUntilIdle()

        assertEquals("", f.drafts.peek(e1a))
        assertEquals("", f.drafts.peek(e1b))
        assertEquals("e2", f.drafts.peek(e2))
        assertEquals("ns", f.drafts.peek(DraftKey.NewSession))
        val names = diskNames(f.store)
        assertTrue(names.none { it.startsWith("chat:E1:") })
        assertTrue(names.contains(e2.storageName))
        assertTrue(names.contains("new-session"))

        // A later save for the forgotten engine changes nothing — not the
        // mirror, not the disk.
        f.drafts.save(DraftKey.Chat("E1", "c3"), "x").join()
        advanceUntilIdle()
        assertEquals("", f.drafts.peek(DraftKey.Chat("E1", "c3")))
        assertFalse(diskNames(f.store).contains("chat:E1:c3"))
    }

    @Test
    fun twoEnginesWithCollidingChatIdsKeepSeparateDrafts() = runTest {
        val f = newDrafts()
        f.drafts.warm()
        advanceUntilIdle()
        val scoped = "engine:v1:same"
        f.drafts.save(DraftKey.Chat("E1", scoped), "one").join()
        f.drafts.save(DraftKey.Chat("E2", scoped), "two").join()
        advanceUntilIdle()
        assertEquals("one", f.drafts.peek(DraftKey.Chat("E1", scoped)))
        assertEquals("two", f.drafts.peek(DraftKey.Chat("E2", scoped)))
    }

    // ── the new-session key ─────────────────────────────────────────────

    @Test
    fun theNewSessionKeyRoundTripsUnderTheLiteralName() = runTest {
        val f = newDrafts()
        f.drafts.save(DraftKey.NewSession, "start")
        advanceUntilIdle()
        assertEquals("start", f.drafts.peek(DraftKey.NewSession))
        assertEquals(setOf("new-session"), diskNames(f.store))
        val g = restart(f)
        assertEquals("start", g.drafts.load(DraftKey.NewSession))
    }

    // ── failure paths ───────────────────────────────────────────────────

    @Test
    fun anUnreadableStoreIsLoggedAndTreatedAsEmpty() = runTest {
        val drafts = Drafts(ThrowingPrefsStore(), foregroundAppScope(), StandardTestDispatcher(testScheduler))
        drafts.warm()
        advanceUntilIdle()
        assertTrue(drafts.isLoaded)
        assertEquals("", drafts.peek(DraftKey.NewSession))
        assertEquals(listOf("drafts load failed"), logged.map { it.first })
        assertTrue(logged.single().second is IOException)
    }

    @Test
    fun aWriteFailureIsLoggedContentFreeAndTheMirrorKeepsTheText() = runTest {
        val drafts = Drafts(WriteFailingPrefsStore(), foregroundAppScope(), StandardTestDispatcher(testScheduler))
        drafts.warm()
        advanceUntilIdle()
        val key = DraftKey.Chat("e1", "c1")
        drafts.save(key, "text").join()
        advanceUntilIdle()
        assertEquals("text", drafts.peek(key))
        assertEquals(listOf("draft write failed"), logged.map { it.first })
        assertNull(logged.single().second)
    }

    /** Every read throws IOException: the first-read failure path. */
    private class ThrowingPrefsStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("unreadable") }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("unreadable")
    }

    /** Reads succeed; every update throws IOException: the write-failure path. */
    private class WriteFailingPrefsStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("no space")
    }
}
