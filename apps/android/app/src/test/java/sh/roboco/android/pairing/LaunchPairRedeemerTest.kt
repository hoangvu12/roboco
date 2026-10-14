package sh.roboco.android.pairing

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.PairedEngine

/**
 * The debug `pair` launch extra's redemption (android-app#30): the
 * redeemer waits for the startup pipeline's client, redeems every URL
 * through `pairEngine` and persists each engine — the same
 * pair-then-upsert contract as the Pair page, minus the UI. The client
 * is a [CoreClient] built with the bindings' `NoHandle` fake-object
 * constructor; the store is the real one on a throwaway DataStore
 * (71's test pattern). Robolectric so `android.util.Log` exists for the
 * failure paths.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LaunchPairRedeemerTest {

    /** A NoHandle CoreClient that records pairEngine calls. */
    private class FakeClient(var enginesBySuffix: Map<String, PairedEngine>) : CoreClient(NoHandle) {
        val pairedUrls = mutableListOf<String>()

        override suspend fun pairEngine(pairingUrl: String, replaceEngineId: String?): PairedEngine {
            pairedUrls += pairingUrl
            val key = pairingUrl.substringAfterLast("token=")
            return checkNotNull(enginesBySuffix[key]) { "unexpected pairing URL $pairingUrl" }
        }
    }

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun redeemsEveryUrlAndPersistsEachEngine() = runTest {
        val a = newEngine(engineId = "engine-a")
        val b = newEngine(engineId = "engine-b")
        val client = FakeClient(mapOf("a" to a, "b" to b))
        val store = store()

        LaunchPairRedeemer(
            client = { client },
            store = store,
            scope = scope,
            io = UnconfinedTestDispatcher(testScheduler),
        ).redeem(listOf("http://h:1/pair#token=a", "http://h:2/pair#token=b"))
        advanceUntilIdle()

        assertEquals(listOf("http://h:1/pair#token=a", "http://h:2/pair#token=b"), client.pairedUrls)
        assertEquals(listOf(a.engineId, b.engineId), store.load().map { it.engineId })
    }

    @Test
    fun anEmptyListIsANoOp() = runTest {
        val client = FakeClient(emptyMap())
        val store = store()
        LaunchPairRedeemer(
            client = { client },
            store = store,
            scope = scope,
            io = UnconfinedTestDispatcher(testScheduler),
        ).redeem(emptyList())
        advanceUntilIdle()
        assertTrue(client.pairedUrls.isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun aFailedRedeemNeverThrowsAndDoesNotStopTheRest() = runTest {
        val b = newEngine(engineId = "engine-b")
        val client = FakeClient(mapOf("b" to b))
        val store = store()

        LaunchPairRedeemer(
            client = { client },
            store = store,
            scope = scope,
            io = UnconfinedTestDispatcher(testScheduler),
        ).redeem(listOf("http://h:1/pair#token=a", "http://h:2/pair#token=b"))
        advanceUntilIdle()

        // The first URL threw inside the fake (unexpected key) — the
        // redeemer logged it and moved on to the second.
        assertEquals(listOf("http://h:1/pair#token=a", "http://h:2/pair#token=b"), client.pairedUrls)
        assertEquals(listOf(b.engineId), store.load().map { it.engineId })
    }

    @Test
    fun waitsForTheClientToAppearBeforeRedeeming() = runTest {
        val a = newEngine(engineId = "engine-a")
        var client: CoreClient? = null
        val store = store()

        LaunchPairRedeemer(
            client = { client },
            store = store,
            scope = backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
        ).redeem(listOf("http://h:1/pair#token=a"))

        // The pipeline is still Loading: nothing redeemed yet.
        runCurrent()
        assertTrue(store.load().isEmpty())

        // The client appears (the startup pipeline flipped to Ready); the
        // poll picks it up within its 100 ms cadence.
        client = FakeClient(mapOf("a" to a))
        advanceTimeBy(200)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf(a.engineId), store.load().map { it.engineId })
    }

    private fun store(): PairedEngineStore = PairedEngineStore(
        testEngineStore(),
        FakeSecretCipher(),
        UnconfinedTestDispatcher(),
    )
}

/** A throwaway DataStore for the store (71's test pattern, pairing-only file). */
private fun testEngineStore(): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
    sh.roboco.android.core.testPrefsStore(
        CoroutineScope(UnconfinedTestDispatcher() + Job()),
        File.createTempFile("launch-pair", ".preferences_pb"),
    )
