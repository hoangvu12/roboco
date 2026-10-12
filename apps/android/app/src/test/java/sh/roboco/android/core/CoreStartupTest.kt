package sh.roboco.android.core

import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import sh.roboco.android.pairing.FakeSecretCipher
import sh.roboco.android.pairing.PairedEngineStore
import sh.roboco.android.pairing.newEngine
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.ClientListener
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.CoreConfig
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.ParkReason
import java.io.File

/**
 * The startup pipeline (ticket 71): load → configure → `CoreClient` →
 * publish engines → `Ready`, plus the single `ClientListener`'s routing
 * (pin persistence, engine-view refresh, fan-out to event sinks).
 *
 * Runs the real host core through the generated bindings (the same JNA
 * setup as `CoreLoadTest`). A non-empty credential dials
 * `http://127.0.0.1:9` so the socket fails instantly and the engine is
 * Connecting/Reconnecting, never Parked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoreStartupTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private class Fixture(
        val store: PairedEngineStore,
        val cipher: FakeSecretCipher,
        val scope: CoroutineScope,
        val startup: CoreStartup,
        val readyClients: MutableList<CoreClient>,
    ) {
        /** Shuts the Rust client down and releases the fixture's DataStore. */
        fun close() {
            startup.clientOrNull()?.shutdown()
            scope.cancel()
        }
    }

    /**
     * One `StandardTestDispatcher` per scheduler role, all sharing the
     * test's scheduler so `advanceUntilIdle()` drives every step. The
     * DataStore gets its own cancellable scope (one instance per file).
     */
    private suspend fun TestScope.newFixture(
        storedEngines: List<PairedEngine> = emptyList(),
        newClient: (CoreConfig, List<PairedEngine>, ClientListener) -> CoreClient =
            { config, engines, listener -> CoreClient(config, engines, listener) },
    ): Fixture {
        val io = StandardTestDispatcher(scheduler)
        val compute = StandardTestDispatcher(scheduler)
        val dataStoreScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tmpFolder.root, "core-startup.preferences_pb") },
        )
        val cipher = FakeSecretCipher()
        val store = PairedEngineStore(dataStore, cipher, io)
        for (engine in storedEngines) {
            store.upsert(engine)
        }
        val readyClients = mutableListOf<CoreClient>()
        val startup = CoreStartup(
            context = context,
            store = store,
            scope = backgroundScope,
            io = io,
            compute = compute,
            newClient = newClient,
            onClientReady = { readyClients.add(it) },
        )
        return Fixture(store, cipher, dataStoreScope, startup, readyClients)
    }

    private fun dialEngine(engineId: String, credential: String): PairedEngine =
        newEngine(engineId = engineId, credential = credential, baseUrl = "http://127.0.0.1:9")

    @Test
    fun aFreshInstallReachesReadyWithNoEngines() = runTest {
        val fixture = newFixture()

        assertEquals(CoreStartState.Loading, fixture.startup.state.value)
        assertNull(fixture.startup.engines.value)
        fixture.startup.start()
        assertEquals("load() has not run yet", CoreStartState.Loading, fixture.startup.state.value)
        fixture.startup.start() // idempotent
        advanceUntilIdle()

        assertEquals(CoreStartState.Ready, fixture.startup.state.value)
        assertEquals(emptyList<EngineView>(), fixture.startup.engines.value)
        assertNotNull(fixture.startup.clientOrNull())
        assertEquals(1, fixture.readyClients.size)
        fixture.close()
    }

    @Test
    fun aNewClientFailureFailsTheStartup() = runTest {
        val fixture = newFixture(
            newClient = { _, _, _ -> throw CoreException.Network("no dial") },
        )

        fixture.startup.start()
        advanceUntilIdle()

        val state = fixture.startup.state.value
        assertTrue("expected Failed, got $state", state is CoreStartState.Failed)
        assertTrue((state as CoreStartState.Failed).reason.contains("no dial"))
        assertNull(fixture.startup.engines.value)
        assertNull(fixture.startup.clientOrNull())
        assertTrue(fixture.readyClients.isEmpty())
        fixture.close()
    }

    @Test
    fun anEmptyCredentialParksAsRevokedImmediately() = runTest {
        val revoked = dialEngine("11111111-1111-4111-8111-111111111111", credential = "")
        val fixture = newFixture(storedEngines = listOf(revoked))

        fixture.startup.start()
        advanceUntilIdle()

        assertEquals(CoreStartState.Ready, fixture.startup.state.value)
        val view = fixture.startup.engines.value!!.single()
        val parked = view.state as EngineState.Parked
        assertEquals(ParkReason.REVOKED, parked.reason)
        fixture.close()
    }

    @Test
    fun pinsArrivingOnBackgroundThreadsArePersisted() = runTest {
        val engine = dialEngine("11111111-1111-4111-8111-111111111111", "cred-A")
        val fixture = newFixture(storedEngines = listOf(engine))
        fixture.startup.start()
        advanceUntilIdle()
        assertEquals(CoreStartState.Ready, fixture.startup.state.value)

        thread(name = "rust-thread") {
            fixture.startup.listener.onEvent(
                ClientEvent.EngineIdentityPinned(engine.engineId, "dev-1", "MacBook Pro"),
            )
        }.join()
        advanceUntilIdle()

        val reloaded = fixture.store.load()
        assertEquals("dev-1", reloaded.single().deviceId)
        assertEquals("MacBook Pro", reloaded.single().name)
        assertTrue("the event path must not touch the main looper", shadowOf(Looper.getMainLooper()).isIdle)
        fixture.close()
    }

    @Test
    fun enginesChangedRefreshesTheEngineViews() = runTest {
        val engine = dialEngine("11111111-1111-4111-8111-111111111111", "cred-A")
        val fixture = newFixture(storedEngines = listOf(engine))
        fixture.startup.start()
        advanceUntilIdle()
        val before = fixture.startup.engines.value
        assertEquals(1, before?.size)

        thread(name = "rust-thread") {
            fixture.startup.listener.onEvent(ClientEvent.EnginesChanged)
        }.join()
        advanceUntilIdle()

        assertNotNull(fixture.startup.engines.value)
        assertNotSame("EnginesChanged must re-read engines()", before, fixture.startup.engines.value)
        assertTrue("the event path must not touch the main looper", shadowOf(Looper.getMainLooper()).isIdle)
        fixture.close()
    }

    @Test
    fun everyRegisteredSinkReceivesEventsAndAThrowingSinkStopsNobody() = runTest {
        val fixture = newFixture()
        val receivedFirst = mutableListOf<ClientEvent>()
        val receivedLast = mutableListOf<ClientEvent>()
        fixture.startup.addEventSink { receivedFirst.add(it) }
        fixture.startup.addEventSink { throw IllegalStateException("sink exploded") }
        fixture.startup.addEventSink { receivedLast.add(it) }
        fixture.startup.start()
        advanceUntilIdle()
        assertEquals(CoreStartState.Ready, fixture.startup.state.value)

        thread(name = "rust-thread") {
            fixture.startup.listener.onEvent(ClientEvent.EnginesChanged)
        }.join()

        assertEquals(1, receivedFirst.size)
        assertEquals(1, receivedLast.size)
        assertTrue("the event path must not touch the main looper", shadowOf(Looper.getMainLooper()).isIdle)
        fixture.close()
    }

    @Test
    fun theCredentialNeverAppearsInLogsAcrossAStartup() = runTest {
        val secretA = "cred-SECRET-A"
        val secretB = "cred-SECRET-B"
        val engines = listOf(
            dialEngine("11111111-1111-4111-8111-111111111111", secretA),
            dialEngine("22222222-2222-4222-8222-222222222222", secretB),
        )
        ShadowLog.clear()
        val fixture = newFixture(storedEngines = engines)
        ShadowLog.clear()

        fixture.startup.start()
        advanceUntilIdle()

        assertEquals(CoreStartState.Ready, fixture.startup.state.value)
        val views = fixture.startup.engines.value!!
        assertEquals(2, views.size)
        assertTrue(views.all { it.state !is EngineState.Parked })

        fixture.cipher.rotateKey()
        assertTrue(fixture.store.load().all { it.credential == "" })
        fixture.store.remove(engines[1].engineId)

        val logged = ShadowLog.getLogs().mapNotNull { it.msg }
        assertTrue(
            "a startup log leaked a credential: $logged",
            logged.none { it.contains(secretA) || it.contains(secretB) },
        )
        fixture.close()
    }
}
