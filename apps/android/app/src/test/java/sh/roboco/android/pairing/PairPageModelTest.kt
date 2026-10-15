package sh.roboco.android.pairing

import android.content.ClipboardManager
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.roboco.android.R
import sh.roboco.android.shell.SheetHostState
import sh.roboco.android.shell.SheetStyle
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.PairException
import uniffi.roboco_core.PairingTarget

/**
 * The Pair page's state machine and error mapping (android-ui-parity
 * #72): Robolectric (the model calls `android.util.Log` and the
 * UniFFI-generated classes need the Android runtime — android-app#13's
 * rule), with `kotlinx-coroutines-test` — a [StandardTestDispatcher] for
 * the model's scope **and** its `io` lane, so `advanceUntilIdle` drives
 * a redeem deterministically and a fake `pair` can suspend on a
 * [CompletableDeferred] to test "closing the sheet mid-redeem".
 *
 * Covered (the ticket's model acceptance list):
 * - success: `upsert` exactly once, **before** `onPaired`; field
 *   cleared; phase `Idle`; `onPaired` invoked once;
 * - a double submit while redeeming → one `pair` call; a blank or
 *   whitespace-only submit → no call; the URL is trimmed;
 *   `replaceEngineId` is passed through;
 * - `describePairError`'s full table (Refused/Unreachable/InvalidUrl
 *   verbatim/Tailcat/Failed/Storage/other) and the store-failure copy;
 * - the error persists while editing and is replaced by the next submit;
 * - a store write failure: `forget` then `remove`, once each, in that
 *   order, for a **new** engine; neither for an engine already in
 *   `knownEngineIds()`; `knownEngineIds` is read before `pair`;
 * - a redeem that outlives its sheet still persists, without
 *   `onPaired`;
 * - the launcher's openPairPage: prefill without an auto-redeem,
 *   refill while open and idle, ignored while redeeming, keep/reuse
 *   of a mid-redeem dismissal, and a grabber page sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairPageModelTest {

    private val ops = mutableListOf<String>()
    private val backend = FakePairingBackend(ops)
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { scope -> scope.cancel() }
        scopes.clear()
    }

    // ------------------------------------------------------------ fixtures

    /**
     * The app-shaped harness: the launcher's app scope and the model's
     * io lane, both on the runTest scheduler.
     */
    private class PairingHarness(
        val scope: CoroutineScope,
        val io: TestDispatcher,
        val sink: FakeSink,
    )

    private fun TestScope.harness(known: Set<String> = emptySet()): PairingHarness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        scopes += scope
        return PairingHarness(scope, dispatcher, FakeSink(ops, known))
    }

    private fun TestScope.model(
        harness: PairingHarness,
        prefill: String? = null,
        replaceEngineId: String? = null,
    ): PairPageModel = PairPageModel(
        prefill = prefill,
        initialReplaceEngineId = replaceEngineId,
        backend = backend,
        store = harness.sink,
        scope = harness.scope,
        io = harness.io,
    )

    private fun res(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    // ------------------------------------------------------ describePairError

    @Test
    fun refusedMapsToTheRefusedSentence() {
        assertEquals(
            PairMessage.Res(R.string.pair_error_refused),
            describePairError(PairException.Refused()),
        )
    }

    @Test
    fun theErrorResourcesCarryTheTicketsCopy() {
        assertEquals(
            "That pairing link did not work — it may have expired or already have been used.",
            res(R.string.pair_error_refused),
        )
        assertEquals(
            "Could not reach that engine. Check the URL and that the engine is running.",
            res(R.string.pair_error_unreachable),
        )
        assertEquals(
            "That Tailcat invite has expired. Run roboco engine tailcat invite again.",
            res(R.string.pair_error_tailcat_expired),
        )
        assertEquals(
            "Could not reach that engine over Tailcat. Check that it is running and online.",
            res(R.string.pair_error_tailcat_unreachable),
        )
        assertEquals("Tailcat isn't available in this build.", res(R.string.pair_error_tailcat_unavailable))
        assertEquals("Connecting through Tailcat…", res(R.string.pair_submitting_tailcat))
        assertEquals("Pairing failed.", res(R.string.pair_error_failed))
        assertEquals(
            "Couldn't save the engine's credential on this phone.",
            res(R.string.pair_error_store),
        )
        assertEquals("Pair this phone", res(R.string.pair_title))
        assertEquals("Pairing link", res(R.string.pair_section))
        assertEquals("Pair engine", res(R.string.pair_submit))
        assertEquals("Pairing…", res(R.string.pair_submitting))
    }

    @Test
    fun unreachableMapsToTheUnreachableSentence() {
        assertEquals(
            PairMessage.Res(R.string.pair_error_unreachable),
            describePairError(PairException.Unreachable("dial tcp 10.0.2.2:27655: connect: refused")),
        )
    }

    @Test
    fun invalidUrlMapsItsReasonVerbatim() {
        assertEquals(
            PairMessage.Verbatim("Invalid pairing URL path"),
            describePairError(PairException.InvalidUrl("Invalid pairing URL path")),
        )
        assertEquals(
            PairMessage.Verbatim("Pairing URL is missing its fragment credential"),
            describePairError(PairException.InvalidUrl("Pairing URL is missing its fragment credential")),
        )
    }

    @Test
    fun tailcatFailuresMapToTheirSentencesNeverTheReason() {
        assertEquals(
            PairMessage.Res(R.string.pair_error_tailcat_expired),
            describePairError(PairException.TailcatExpired()),
        )
        assertEquals(
            PairMessage.Res(R.string.pair_error_tailcat_unreachable),
            describePairError(PairException.TailcatUnreachable("Tailcat client startup failed")),
        )
        assertEquals(
            PairMessage.Res(R.string.pair_error_tailcat_unavailable),
            describePairError(PairException.TailcatUnavailable("libroboco_tailcat.so is missing")),
        )
    }

    @Test
    fun inviteExpiryRoundsUpToWholeMinutes() {
        assertEquals("5 min", inviteExpiryLabel(300_000))
        assertEquals("5 min", inviteExpiryLabel(299_001))
        assertEquals("2 min", inviteExpiryLabel(60_001))
        assertEquals("1 min", inviteExpiryLabel(60_000))
        assertEquals("under a minute", inviteExpiryLabel(59_999))
        assertEquals("under a minute", inviteExpiryLabel(-5_000))
    }

    @Test
    fun failedStorageAndUnknownErrorsMapToTheFallback() {
        assertEquals(PairMessage.Res(R.string.pair_error_failed), describePairError(PairException.Failed("x")))
        assertEquals(PairMessage.Res(R.string.pair_error_failed), describePairError(PairException.Storage("x")))
        assertEquals(PairMessage.Res(R.string.pair_error_failed), describePairError(RuntimeException("x")))
    }

    // ------------------------------------------------------------- the redeem

    @Test
    fun successPersistsBeforeOnPairedClearsTheFieldAndGoesIdle() = runTest {
        val harness = harness()
        val model = model(harness)
        val paired = mutableListOf<Unit>()
        model.onPaired = {
            ops += "onPaired"
            paired += Unit
        }
        model.text.setTextAndPlaceCursorAtEnd("http://10.0.2.2:27655/pair#token=abc")
        assertTrue(model.canSubmit)

        model.submit()
        advanceUntilIdle()

        assertEquals(listOf("known", "pair", "upsert", "onPaired"), ops)
        assertEquals(1, harness.sink.upserts.size)
        assertEquals(1, paired.size)
        assertEquals("", model.text.text.toString())
        assertEquals(PairPhase.Idle, model.phase)
    }

    @Test
    fun aDoubleSubmitWhileRedeemingIsIgnored() = runTest {
        val harness = harness()
        val model = model(harness)
        val gate = CompletableDeferred<PairedEngine>()
        backend.pairImpl = { _, _ -> gate.await() }
        model.text.setTextAndPlaceCursorAtEnd("http://10.0.2.2:27655/pair#token=abc")

        model.submit()
        assertEquals(PairPhase.Redeeming, model.phase)
        assertFalse(model.canSubmit)
        model.submit() // the double tap on the busy capsule
        advanceUntilIdle()
        gate.complete(newEngine())
        advanceUntilIdle()

        assertEquals(1, backend.pairCalls.size)
    }

    @Test
    fun aBlankOrWhitespaceOnlySubmitNeverPairs() = runTest {
        val harness = harness()
        val model = model(harness)
        assertFalse(model.canSubmit)
        model.submit()
        model.text.setTextAndPlaceCursorAtEnd("   ")
        assertFalse(model.canSubmit)
        model.submit()
        advanceUntilIdle()
        assertTrue(backend.pairCalls.isEmpty())
        assertEquals(PairPhase.Idle, model.phase)
    }

    @Test
    fun theUrlIsTrimmedBeforePair() = runTest {
        val harness = harness()
        val model = model(harness)
        model.text.setTextAndPlaceCursorAtEnd("  http://10.0.2.2:27655/pair#token=abc  \n")
        model.submit()
        advanceUntilIdle()
        assertEquals("http://10.0.2.2:27655/pair#token=abc", backend.pairCalls.single().first)
    }

    @Test
    fun theReplaceEngineIdIsPassedThrough() = runTest {
        val harness = harness()
        val model = model(harness, replaceEngineId = "e1")
        model.text.setTextAndPlaceCursorAtEnd("http://10.0.2.2:27655/pair#token=abc")
        model.submit()
        advanceUntilIdle()
        assertEquals("e1", backend.pairCalls.single().second)
    }

    @Test
    fun aPairFailureShowsTheMappedError() = runTest {
        val harness = harness()
        val model = model(harness)
        model.onPaired = { }
        backend.pairImpl = { _, _ -> throw PairException.Refused() }
        model.text.setTextAndPlaceCursorAtEnd("http://10.0.2.2:27655/pair#token=abc")

        model.submit()
        advanceUntilIdle()

        assertEquals(PairPhase.Error(PairMessage.Res(R.string.pair_error_refused)), model.phase)
        // The link is spent but the field keeps it; nothing was stored.
        assertEquals("http://10.0.2.2:27655/pair#token=abc", model.text.text.toString())
        assertTrue(harness.sink.upserts.isEmpty())
    }

    @Test
    fun theErrorPersistsWhileEditingAndOnlyTheNextSubmitReplacesIt() = runTest {
        val harness = harness()
        val model = model(harness)
        backend.pairImpl = { _, _ -> throw PairException.Refused() }
        model.text.setTextAndPlaceCursorAtEnd("http://10.0.2.2:27655/pair#token=abc")
        model.submit()
        advanceUntilIdle()
        val error = model.phase
        assertTrue(error is PairPhase.Error)

        model.text.setTextAndPlaceCursorAtEnd("https://engine.example.ts.net/pair#token=abc")
        assertEquals(error, model.phase)

        backend.pairImpl = { _, _ -> newEngine() }
        model.submit()
        assertEquals(PairPhase.Redeeming, model.phase)
        advanceUntilIdle()
        assertEquals(PairPhase.Idle, model.phase)
    }

    @Test
    fun acceptScannedFillsTheFieldAndSubmitsAtOnce() = runTest {
        val harness = harness()
        val model = model(harness)
        model.acceptScanned("https://engine.example.ts.net/pair#token=scan")
        assertEquals(PairPhase.Redeeming, model.phase)
        advanceUntilIdle()
        assertEquals("https://engine.example.ts.net/pair#token=scan", backend.pairCalls.single().first)
        assertEquals(1, harness.sink.upserts.size)
    }

    // --------------------------------------------------------- store failure

    @Test
    fun aStoreFailureForANewEngineForgetsThenRemovesExactlyOnce() = runTest {
        val harness = harness()
        val engine = newEngine()
        val gate = CompletableDeferred<PairedEngine>()
        backend.pairImpl = { _, _ -> gate.await() }
        val model = model(harness)
        model.onPaired = { }
        model.text.setTextAndPlaceCursorAtEnd("http://10.0.2.2:27655/pair#token=abc")
        model.submit()
        advanceUntilIdle()
        harness.sink.upsertError = CredentialStoreException(IOException())
        gate.complete(engine)
        advanceUntilIdle()

        assertEquals(listOf("known", "pair", "upsert", "forget", "remove"), ops)
        assertEquals(listOf(engine.engineId), backend.forgets)
        assertEquals(listOf(engine.engineId), harness.sink.removes)
        assertEquals(PairPhase.Error(PairMessage.Res(R.string.pair_error_store)), model.phase)
        // The link is spent; the field keeps its text.
        assertEquals("http://10.0.2.2:27655/pair#token=abc", model.text.text.toString())
        assertTrue(harness.sink.upserts.isNotEmpty())
    }

    @Test
    fun aStoreFailureForAKnownEngineKeepsTheEngineLive() = runTest {
        val harness = harness(known = setOf(newEngine().engineId))
        val model = model(harness)
        model.onPaired = { }
        harness.sink.upsertError = CredentialStoreException(IOException())
        model.text.setTextAndPlaceCursorAtEnd("http://10.0.2.2:27655/pair#token=abc")
        model.submit()
        advanceUntilIdle()

        assertEquals(listOf("known", "pair", "upsert"), ops)
        assertTrue(backend.forgets.isEmpty())
        assertTrue(harness.sink.removes.isEmpty())
        assertEquals(PairPhase.Error(PairMessage.Res(R.string.pair_error_store)), model.phase)
    }

    // ------------------------------------------------ a redeem outliving the sheet

    @Test
    fun aRedeemThatOutlivesTheSheetStillPersistsWithoutOnPaired() = runTest {
        val harness = harness()
        val gate = CompletableDeferred<PairedEngine>()
        backend.pairImpl = { _, _ -> gate.await() }
        val host = SheetHostState()
        val launcher = PairPageLauncher(host, backend, harness.sink, harness.scope, qrScan = null, clipboard = FakeClipboard(), io = harness.io)
        val paired = mutableListOf<Unit>()

        launcher.openPairPage()
        val model = launcher.current!!
        model.onPaired = { paired += Unit }
        model.text.setTextAndPlaceCursorAtEnd("http://10.0.2.2:27655/pair#token=abc")
        model.submit()
        advanceUntilIdle()

        // The sheet closes mid-redeem (35 may even tear it down
        // instantly); the page's onDispose clears onPaired.
        host.dismiss(animated = false)
        model.onPaired = null
        assertSame(model, launcher.current) // kept: still redeeming

        gate.complete(newEngine())
        advanceUntilIdle()

        assertEquals(1, harness.sink.upserts.size)
        assertTrue(paired.isEmpty())
        assertEquals(PairPhase.Idle, model.phase)
    }

    @Test
    fun reopeningDuringARedeemReusesTheRunningModel() = runTest {
        val harness = harness()
        val gate = CompletableDeferred<PairedEngine>()
        backend.pairImpl = { _, _ -> gate.await() }
        val host = SheetHostState()
        val launcher = PairPageLauncher(host, backend, harness.sink, harness.scope, qrScan = null, clipboard = FakeClipboard(), io = harness.io)

        launcher.openPairPage(prefill = "http://10.0.2.2:27655/pair#token=abc")
        val model = launcher.current!!
        model.submit()
        advanceUntilIdle()
        host.dismiss(animated = false)

        // Reopen while the redeem runs: the same model, presented again
        // (the page then shows "Pairing…").
        launcher.openPairPage()
        assertSame(model, launcher.current)
        assertEquals(1, host.entries.size)
        assertTrue(model.phase is PairPhase.Redeeming)

        gate.complete(newEngine())
        advanceUntilIdle()
        // One redeem, one upsert — never a second.
        assertEquals(1, backend.pairCalls.size)
        assertEquals(1, harness.sink.upserts.size)
        assertEquals(PairPhase.Idle, model.phase)
    }

    // ------------------------------------------------------------- launcher

    @Test
    fun openPairPagePresentsAGrabberPageSheetAndDoesNotPair() = runTest {
        val harness = harness()
        val host = SheetHostState()
        val launcher = PairPageLauncher(host, backend, harness.sink, harness.scope, qrScan = null, clipboard = FakeClipboard(), io = harness.io)

        launcher.openPairPage(prefill = "https://h/pair#token=x")

        val entry = host.entries.single()
        assertEquals("pair", entry.key)
        val style = entry.style as SheetStyle.Page
        assertTrue(style.grabber)
        val model = launcher.current!!
        assertEquals("https://h/pair#token=x", model.text.text.toString())
        assertTrue(backend.pairCalls.isEmpty())
        advanceUntilIdle()
        assertTrue(backend.pairCalls.isEmpty())
    }

    @Test
    fun aSecondOpenWhileIdleRefillsTheModel() = runTest {
        val harness = harness()
        val host = SheetHostState()
        val launcher = PairPageLauncher(host, backend, harness.sink, harness.scope, qrScan = null, clipboard = FakeClipboard(), io = harness.io)
        launcher.openPairPage(prefill = "https://a/pair#token=1")
        val model = launcher.current!!

        launcher.openPairPage(prefill = "https://b/pair#token=2", replaceEngineId = "e9")

        assertEquals(1, host.entries.size) // still the one sheet
        assertSame(model, launcher.current)
        assertEquals("https://b/pair#token=2", model.text.text.toString())
        assertEquals("e9", model.replaceEngineId)
        assertEquals(PairPhase.Idle, model.phase)
    }

    @Test
    fun anOpenWhileRedeemingIsIgnored() = runTest {
        val harness = harness()
        val gate = CompletableDeferred<PairedEngine>()
        backend.pairImpl = { _, _ -> gate.await() }
        val host = SheetHostState()
        val launcher = PairPageLauncher(host, backend, harness.sink, harness.scope, qrScan = null, clipboard = FakeClipboard(), io = harness.io)
        launcher.openPairPage(prefill = "https://a/pair#token=1")
        val model = launcher.current!!
        model.submit()
        advanceUntilIdle()

        launcher.openPairPage(prefill = "https://c/pair#token=3")

        assertSame(model, launcher.current)
        assertEquals("https://a/pair#token=1", model.text.text.toString())
        assertTrue(model.phase is PairPhase.Redeeming)
        gate.complete(newEngine())
        advanceUntilIdle()
        assertEquals(1, backend.pairCalls.size)
    }

    @Test
    fun anIdleDismissalClearsTheModelAndAReopenIsFresh() = runTest {
        val harness = harness()
        val host = SheetHostState()
        val launcher = PairPageLauncher(host, backend, harness.sink, harness.scope, qrScan = null, clipboard = FakeClipboard(), io = harness.io)
        launcher.openPairPage(prefill = "https://a/pair#token=1")
        val first = launcher.current!!

        host.dismiss(animated = false)
        assertNull(launcher.current)

        launcher.openPairPage(prefill = "https://b/pair#token=2")
        val second = launcher.current!!
        assertTrue(first !== second)
        assertEquals("https://b/pair#token=2", second.text.text.toString())
    }

    // ------------------------------------------------------------- the fakes

    /** Scripted results; records the op order into [ops]. */
    private class FakePairingBackend(private val ops: MutableList<String>) : PairingBackend {
        var pairImpl: suspend (String, String?) -> PairedEngine = { _, _ -> newEngine() }
        var parseImpl: (String) -> PairingTarget? = { null }
        val pairCalls = mutableListOf<Pair<String, String?>>()
        val forgets = mutableListOf<String>()

        override fun parse(input: String): PairingTarget? = parseImpl(input)

        override suspend fun pair(url: String, replaceEngineId: String?): PairedEngine {
            ops += "pair"
            pairCalls += url to replaceEngineId
            return pairImpl(url, replaceEngineId)
        }

        override suspend fun forget(engineId: String) {
            ops += "forget"
            forgets += engineId
        }
    }

    /** The recording sink: a known set, an optional upsert failure. */
    private class FakeSink(
        private val ops: MutableList<String>,
        private val known: Set<String>,
    ) : PairedEngineSink {
        val upserts = mutableListOf<PairedEngine>()
        val removes = mutableListOf<String>()
        var upsertError: Exception? = null

        override fun knownEngineIds(): Set<String> {
            ops += "known"
            return known
        }

        override suspend fun upsert(engine: PairedEngine) {
            ops += "upsert"
            upserts += engine
            upsertError?.let { throw it }
        }

        override suspend fun remove(engineId: String) {
            ops += "remove"
            removes += engineId
        }
    }

    /** A no-op clipboard: the launcher's constructor needs one. */
    private class FakeClipboard : PairClipboard {
        override fun hasText(): Boolean = false
        override fun readText(): String? = null
        override fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {}
        override fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {}
    }
}
