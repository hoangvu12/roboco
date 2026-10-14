package sh.roboco.android.pairing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.R

/**
 * [InboundPairing] (android-ui-parity #139) — behaviour A and table D
 * over a fake [PairPageControl] that records its calls in order, plus
 * the `pairingRootStatusOf` table E. Plain JUnit +
 * `kotlinx-coroutines-test`: a [StandardTestDispatcher] for the scope
 * **and** the worker lane (so `advanceUntilIdle` drives the off-main
 * parse deterministically), the 72 gallery regex as `accepts` (never
 * the native parser), and a recording `log` (so no `android.util.Log`
 * is ever touched).
 *
 * Log safety: every recorded line must be one of the four fixed
 * strings — the pairing token never reaches a log line.
 */
class InboundPairingTest {

    // ------------------------------------------------------ behaviour D

    @Test
    fun validLinkWithThePageDownDismissesThenOpensAtTheRoot() = runTest {
        val control = FakeControl()
        val logs = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        val inbound = newInbound(control, logs, accepts = { accepted += it; StandInAccepts(it) })

        inbound.onPairLink("roboco://pair#url=http%3A%2F%2Fh%2Fpair%23token%3Dx")
        advanceUntilIdle()

        assertEquals(listOf("dismiss", "frame", "open"), control.ops)
        assertEquals(listOf("http://h/pair#token=x" to null), control.opened)
        assertEquals(listOf("http://h/pair#token=x"), accepted)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun validLinkWithThePageUpRefillsInPlaceWithoutDismissing() = runTest {
        val control = FakeControl()
        control.showing = true
        control.showReplace = "e1"
        val logs = mutableListOf<String>()
        val inbound = newInbound(control, logs)

        inbound.onPairLink("roboco://pair#url=http%3A%2F%2Fh%2Fpair%23token%3Dx")
        advanceUntilIdle()

        assertEquals(listOf("open"), control.ops)
        assertEquals(listOf("http://h/pair#token=x" to "e1"), control.opened)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun aRedeemInProgressWinsOverTheLink() = runTest {
        val control = FakeControl()
        control.redeeming = true
        val logs = mutableListOf<String>()
        val inbound = newInbound(control, logs)

        inbound.onPairLink("roboco://pair#url=http%3A%2F%2Fh%2Fpair%23token%3Dx")
        advanceUntilIdle()

        assertTrue(control.ops.isEmpty())
        assertTrue(control.opened.isEmpty())
        assertEquals(listOf("pair link ignored: redeem in progress"), logs)
    }

    @Test
    fun aRedeemStartingDuringTheFrameWinsOverTheLink() = runTest {
        val control = FakeControl()
        control.redeemingOnFrame = true
        val logs = mutableListOf<String>()
        val inbound = newInbound(control, logs)

        inbound.onPairLink("roboco://pair#url=http%3A%2F%2Fh%2Fpair%23token%3Dx")
        advanceUntilIdle()

        // The dismissal and the frame still happened; the open did not.
        assertEquals(listOf("dismiss", "frame"), control.ops)
        assertTrue(control.opened.isEmpty())
        assertEquals(listOf("pair link ignored: redeem in progress"), logs)
    }

    // ------------------------------------------------------ behaviour A

    @Test
    fun aLinkWithoutAUrlNeverReachesTheParser() = runTest {
        val control = FakeControl()
        val logs = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        val inbound = newInbound(control, logs, accepts = { accepted += it; StandInAccepts(it) })

        inbound.onPairLink("roboco://pair")
        advanceUntilIdle()

        assertTrue(accepted.isEmpty())
        assertTrue(control.ops.isEmpty())
        assertEquals(listOf("pair link ignored: no url"), logs)
    }

    @Test
    fun aUrlTheCoreRejectsIsIgnored() = runTest {
        val control = FakeControl()
        val logs = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        val inbound = newInbound(control, logs, accepts = { accepted += it; StandInAccepts(it) })

        inbound.onPairLink("roboco://pair#url=hello")
        advanceUntilIdle()

        assertEquals(listOf("hello"), accepted)
        assertTrue(control.ops.isEmpty())
        assertEquals(listOf("pair link ignored: not a pairing URL"), logs)
    }

    @Test
    fun noLoggedLineEverCarriesALinkUrlOrToken() = runTest {
        val control = FakeControl()
        control.redeeming = true
        val logs = mutableListOf<String>()
        val inbound = newInbound(control, logs)

        // Every logging path this ticket owns.
        inbound.onPairLink("roboco://pair")
        inbound.onPairLink("roboco://pair#url=hello")
        inbound.onPairLink("roboco://pair#url=http%3A%2F%2Fh%2Fpair%23token%3Dx")
        advanceUntilIdle()

        assertEquals(
            listOf(
                "pair link ignored: no url",
                "pair link ignored: not a pairing URL",
                "pair link ignored: redeem in progress",
            ),
            logs,
        )
        logs.forEach { line ->
            assertFalse(line, "token" in line)
            assertFalse(line, "http" in line)
            assertFalse(line, "%3A" in line)
        }
    }

    // ------------------------------------------------------- table E

    @Test
    fun pairingRootStatusFollowsTableE() {
        val resolve: (PairMessage) -> String = { message ->
            when (message) {
                is PairMessage.Res -> "res:" + message.id
                is PairMessage.Verbatim -> message.text
            }
        }
        assertEquals(PairingRootStatus.None, pairingRootStatusOf(null, false, resolve))
        assertEquals(PairingRootStatus.None, pairingRootStatusOf(PairPhase.Idle, false, resolve))
        assertEquals(PairingRootStatus.None, pairingRootStatusOf(PairPhase.Idle, true, resolve))
        assertEquals(PairingRootStatus.Redeeming, pairingRootStatusOf(PairPhase.Redeeming, true, resolve))
        assertEquals(PairingRootStatus.Redeeming, pairingRootStatusOf(PairPhase.Redeeming, false, resolve))
        // The page shows its own error line; the root stays quiet.
        assertEquals(
            PairingRootStatus.None,
            pairingRootStatusOf(PairPhase.Error(PairMessage.Res(R.string.pair_error_refused)), true, resolve),
        )
        // A redeem that outlived its page surfaces on the root.
        assertEquals(
            PairingRootStatus.Error("x"),
            pairingRootStatusOf(PairPhase.Error(PairMessage.Verbatim("x")), false, resolve),
        )
    }

    // -------------------------------------------------------- the rig

    /**
     * 72's gallery parse stand-in as the `accepts` predicate: matches
     * `scheme://host/pair#token=<alphabet>`, so `hello` fails while the
     * table URLs pass.
     */
    private fun StandInAccepts(input: String): Boolean =
        Regex("^(https?)://([^/?#@]+)(/.*)?/pair#token=[A-Za-z0-9_-]+$")
            .matchEntire(input.trim()) != null

    /** The rig: the scope and the worker lane both on the test scheduler. */
    private fun TestScope.newInbound(
        control: PairPageControl,
        logs: MutableList<String>,
        accepts: (String) -> Boolean = { StandInAccepts(it) },
    ): InboundPairing = InboundPairing(
        control = control,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        accepts = accepts,
        worker = StandardTestDispatcher(testScheduler),
        log = { logs += it },
    )

    /** The fake control: records every call in order, flips on demand. */
    private class FakeControl : PairPageControl {
        val ops = mutableListOf<String>()
        val opened = mutableListOf<Pair<String, String?>>()
        var showing = false
        var redeeming = false
        var showReplace: String? = null
        var redeemingOnFrame = false

        override val isShowing: Boolean get() = showing
        override val isRedeeming: Boolean get() = redeeming
        override val showingReplaceEngineId: String?
            get() = if (showing) showReplace else null

        override fun dismissOtherPresentations() {
            ops += "dismiss"
        }

        override suspend fun awaitFrame() {
            ops += "frame"
            if (redeemingOnFrame) redeeming = true
        }

        override fun open(prefill: String, replaceEngineId: String?) {
            ops += "open"
            opened += prefill to replaceEngineId
        }
    }
}
