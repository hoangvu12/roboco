package sh.roboco.android.core

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
import sh.roboco.android.session.SessionEventHub
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.ClientListener
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.CoreConfig
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.RoomRecord
import uniffi.roboco_core.ParkReason
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The startup pipeline (ticket 71): load → configure → `CoreClient` →
 * publish engines → `Ready`, plus the single `ClientListener`'s routing
 * (pin persistence, engine-view refresh, fan-out to event sinks).
 *
 * Runs the real host core through the generated bindings (the same JNA
 * setup as `CoreLoadTest`). A non-empty credential dials
 * `http://127.0.0.1:9` so the socket fails instantly and the engine is
 * Connecting/Reconnecting, never Parked.
 *
 * A plain [Application]: `RobocoApplication.onCreate` starts the app's own
 * pipeline (its own `CoreClient`, `Main.immediate` collectors and the
 * default main-posting session hub), which posts to the main looper on its
 * own schedule and made the "event path must not touch the main looper"
 * assertions fail regardless of what the event path did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CoreStartupTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * The real core's `onEvent` callbacks (the revoked park's
     * EnginesChanged) must reach this sandbox's handle map, not the last
     * sandbox to initialize the bindings — see [UniffiSandbox].
     */
    @Before
    fun claimCallbacks() = UniffiSandbox.claimCallbacks()

    private class Fixture(
        val store: PairedEngineStore,
        val cipher: FakeSecretCipher,
        val scope: CoroutineScope,
        val startup: CoreStartup,
        val readyClients: MutableList<CoreClient>,
        /** Ticket 51's one main hop per event, captured instead of posted to main. */
        val hubHops: AtomicInteger,
    ) {
        /**
         * Starts the pipeline and suspends until it leaves `Loading`.
         * The pipeline runs in `backgroundScope`, which
         * `advanceUntilIdle()` does not drive; suspending the body does.
         */
        suspend fun startAndAwait(): CoreStartState {
            startup.start()
            return startup.state.first { it != CoreStartState.Loading }
        }

        /** Polls (real time) until [engineId] is persisted with [deviceId]. */
        suspend fun awaitPinned(engineId: String, deviceId: String): List<PairedEngine> =
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    var engines = store.load()
                    while (engines.none { it.engineId == engineId && it.deviceId == deviceId }) {
                        delay(10)
                        engines = store.load()
                    }
                    engines
                }
            }

        /** Shuts the Rust client down and releases the fixture's DataStore. */
        fun close() {
            startup.clientOrNull()?.shutdown()
            scope.cancel()
        }
    }

    /**
     * One `StandardTestDispatcher` per scheduler role, all sharing the
     * test's scheduler. Work launched in `backgroundScope` is awaited
     * explicitly (see [Fixture.startAndAwait]). The DataStore gets its own
     * cancellable scope (one instance per file).
     */
    private suspend fun TestScope.newFixture(
        storedEngines: List<PairedEngine> = emptyList(),
        newClient: (CoreConfig, List<PairedEngine>, List<RoomRecord>, ClientListener) -> CoreClient =
            { config, engines, rooms, listener -> CoreClient(config, engines, rooms, listener) },
    ): Fixture {
        val io = StandardTestDispatcher(testScheduler)
        val compute = StandardTestDispatcher(testScheduler)
        val dataStoreScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
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
        // The session hub's main hop (ticket 51) is the one intended main
        // post per event; count it here so the looper assertions below
        // check that pins, the engines refresh and the sinks never need main.
        val hubHops = AtomicInteger()
        val startup = CoreStartup(
            context = context,
            store = store,
            scope = backgroundScope,
            io = io,
            compute = compute,
            newClient = newClient,
            onClientReady = { readyClients.add(it) },
            sessionEvents = SessionEventHub { hubHops.incrementAndGet() },
        )
        return Fixture(store, cipher, dataStoreScope, startup, readyClients, hubHops)
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
        fixture.startAndAwait() // start() again: idempotent
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
            newClient = { _, _, _, _ -> throw CoreException.Network("no dial") },
        )

        fixture.startAndAwait()
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

        fixture.startAndAwait()
        advanceUntilIdle()

        assertEquals(CoreStartState.Ready, fixture.startup.state.value)
        // The core parks on its own thread, possibly after the Ready
        // snapshot; the EnginesChanged refresh then republishes it.
        val view = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                fixture.startup.engines.first { it?.singleOrNull()?.state is EngineState.Parked }!!.single()
            }
        }
        val parked = view.state as EngineState.Parked
        assertEquals(ParkReason.REVOKED, parked.reason)
        fixture.close()
    }

    @Test
    fun pinsArrivingOnBackgroundThreadsArePersisted() = runTest {
        val engine = dialEngine("11111111-1111-4111-8111-111111111111", "cred-A")
        val fixture = newFixture(storedEngines = listOf(engine))
        fixture.startAndAwait()
        advanceUntilIdle()
        assertEquals(CoreStartState.Ready, fixture.startup.state.value)

        // Flush whatever framework startup left on main, so the idle
        // check below sees only what the event path posts.
        shadowOf(Looper.getMainLooper()).idle()
        thread(name = "rust-thread") {
            fixture.startup.listener.onEvent(
                ClientEvent.EngineIdentityPinned(engine.engineId, "dev-1", "MacBook Pro"),
            )
        }.join()

        val reloaded = fixture.awaitPinned(engine.engineId, "dev-1")
        assertEquals("dev-1", reloaded.single().deviceId)
        assertEquals("MacBook Pro", reloaded.single().name)
        assertTrue("the event path must not touch the main looper", shadowOf(Looper.getMainLooper()).isIdle)
        assertTrue("the event still hops to main through the session hub", fixture.hubHops.get() >= 1)
        fixture.close()
    }

    @Test
    fun enginesChangedRefreshesTheEngineViews() = runTest {
        val engine = dialEngine("11111111-1111-4111-8111-111111111111", "cred-A")
        // Counts reads: an equal list is deduped by the StateFlow, so the
        // published reference alone can't show that engines() re-ran.
        val engineReads = AtomicInteger()
        val fixture = newFixture(
            storedEngines = listOf(engine),
            newClient = { config, engines, rooms, listener ->
                object : CoreClient(config, engines, rooms, listener) {
                    override fun engines(): List<EngineView> =
                        super.engines().also { engineReads.incrementAndGet() }
                }
            },
        )
        fixture.startAndAwait()
        advanceUntilIdle()
        assertEquals(1, fixture.startup.engines.value?.size)
        val readsBefore = engineReads.get()

        // Flush whatever framework startup left on main, so the idle
        // check below sees only what the event path posts.
        shadowOf(Looper.getMainLooper()).idle()
        thread(name = "rust-thread") {
            fixture.startup.listener.onEvent(ClientEvent.EnginesChanged)
        }.join()
        // The collector lives in backgroundScope, which advanceUntilIdle()
        // does not drive; suspending the body lets it run.
        withTimeout(5_000) {
            while (engineReads.get() == readsBefore) delay(10)
        }

        assertNotNull(fixture.startup.engines.value)
        // At least one read, not exactly one: the engine really dials
        // 127.0.0.1:9, and every view change (Connecting -> Reconnecting,
        // the dial error, the retry time) makes the real core emit its own
        // EnginesChanged from its own thread (mobile-client `publish`).
        // CoreStartup refreshes once per event (coalesced), so a core event
        // landing beside the synthetic one is a second legitimate read.
        // CI b2e14660 (ubuntu, where the refused dial is fastest) got
        // readsBefore + 2. The core's timing races the test; the app code
        // doesn't double-refresh.
        assertTrue(
            "EnginesChanged must re-read engines(): before=$readsBefore after=${engineReads.get()}",
            engineReads.get() >= readsBefore + 1,
        )
        assertTrue("the event path must not touch the main looper", shadowOf(Looper.getMainLooper()).isIdle)
        assertTrue("the event still hops to main through the session hub", fixture.hubHops.get() >= 1)
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
        fixture.startAndAwait()
        advanceUntilIdle()
        assertEquals(CoreStartState.Ready, fixture.startup.state.value)

        // Flush whatever framework startup left on main, so the idle
        // check below sees only what the event path posts.
        shadowOf(Looper.getMainLooper()).idle()
        thread(name = "rust-thread") {
            fixture.startup.listener.onEvent(ClientEvent.EnginesChanged)
        }.join()

        assertEquals(1, receivedFirst.size)
        assertEquals(1, receivedLast.size)
        assertTrue("the event path must not touch the main looper", shadowOf(Looper.getMainLooper()).isIdle)
        assertTrue("the event still hops to main through the session hub", fixture.hubHops.get() >= 1)
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

        fixture.startAndAwait()
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
