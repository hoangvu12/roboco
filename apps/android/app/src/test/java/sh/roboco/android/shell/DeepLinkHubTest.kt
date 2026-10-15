package sh.roboco.android.shell

import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The hub's state machine (android-ui-parity #36) against a recording
 * [AppRouter] and a recording pairing handler — every row of the ticket's
 * `DeepLinkHubTest` list. Plain JUnit: no Robolectric, no Compose; the
 * seam over `android.util.Log` ("not mocked" on the plain JVM) is
 * replaced in [setUp] and restored in [tearDown].
 */
class DeepLinkHubTest {

    private val logs = mutableListOf<String>()
    private val originalLog = deepLinksLog

    /** `engine:v1:` + base64url-no-pad of `["k1","raw-1"]`. */
    private val knownScoped =
        ScopedIds.PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""["k1","raw-1"]""".toByteArray(Charsets.UTF_8))

    /** How many times the hub asked for the known ids (raw-id opens only). */
    private var knownCalls = 0

    @Before
    fun setUp() {
        deepLinksLog = { message -> logs.add(message) }
    }

    @After
    fun tearDown() {
        deepLinksLog = originalLog
    }

    /** The hub under test, with [knownScoped] as the one known chat. */
    private fun hub(): DeepLinkHub = DeepLinkHub(knownChatIds = {
        knownCalls++
        listOf(knownScoped)
    })

    // --------------------------------------------------------------- held

    @Test
    fun heldUntilAttachThenExactlyOneCall() {
        val router = FakeRouter()
        val hub = hub()
        hub.submit(DeepLink.Settings)
        // Nothing is attached yet: the link is held, nothing called.
        assertTrue(router.calls.isEmpty())
        hub.attachRouter(router)
        assertEquals(listOf("settings"), router.calls)
    }

    @Test
    fun twoSubmitsBeforeAttachDispatchOnlyTheLatest() {
        val router = FakeRouter()
        val hub = hub()
        hub.submit(DeepLink.Settings)
        hub.submit(DeepLink.NewSession("hello"))
        hub.attachRouter(router)
        assertEquals(listOf("new(hello)"), router.calls)
    }

    // ------------------------------------------------------- pairing root

    @Test
    fun pairingRootDropsPendingAndLaterSubmits() {
        val router = FakeRouter()
        val hub = hub()
        hub.submit(DeepLink.Search)
        // The pairing root takes over (the cross-fade target): the pending
        // link is dropped, and the attach below must not dispatch it.
        hub.showingPairingRoot(true)
        hub.attachRouter(router)
        assertTrue(router.calls.isEmpty())
        // Even with a router attached, a submit on the pairing root is
        // dropped (pairingRoot beats an attached router).
        hub.submit(DeepLink.Settings)
        assertTrue(router.calls.isEmpty())
        assertTrue(logs.contains("dropped Settings: pairing root"))
        // Leaving the pairing root re-enables dispatch.
        hub.showingPairingRoot(false)
        hub.submit(DeepLink.Settings)
        assertEquals(listOf("settings"), router.calls)
    }

    @Test
    fun dispatchesAtOnceWhenAttached() {
        val router = FakeRouter()
        val hub = hub()
        hub.attachRouter(router)
        hub.submit(DeepLink.Search)
        assertEquals(listOf("search"), router.calls)
    }

    // ---------------------------------------------------------------- pair

    @Test
    fun pairIsHeldThenDeliveredEvenOnThePairingRoot() {
        val hub = hub()
        hub.showingPairingRoot(true)
        hub.submit(DeepLink.Pair("roboco://pair#url=x"))
        // Held, not dropped, while the pairing root is on screen.
        val received = mutableListOf<String>()
        val unregister = hub.registerPairingHandler { link -> received.add(link) }
        assertEquals(listOf("roboco://pair#url=x"), received)
        unregister()
        // After unregistering, a pair link is held again.
        hub.submit(DeepLink.Pair("roboco://pair#url=y"))
        assertEquals(listOf("roboco://pair#url=x"), received)
        val second = mutableListOf<String>()
        hub.registerPairingHandler { link -> second.add(link) }
        assertEquals(listOf("roboco://pair#url=y"), second)
    }

    @Test
    fun pairNeverReachesARouter() {
        val router = FakeRouter()
        val hub = hub()
        hub.attachRouter(router)
        hub.submit(DeepLink.Pair("roboco://pair"))
        assertTrue(router.calls.isEmpty())
    }

    // -------------------------------------------------------------- routers

    @Test
    fun aStaleDetachDoesNotClearTheNewerRouter() {
        val oldRouter = FakeRouter()
        val newRouter = FakeRouter()
        val hub = hub()
        hub.attachRouter(oldRouter)
        hub.attachRouter(newRouter)
        hub.detachRouter(oldRouter) // stale: from an old composition
        hub.submit(DeepLink.Settings)
        assertTrue(oldRouter.calls.isEmpty())
        assertEquals(listOf("settings"), newRouter.calls)
    }

    @Test
    fun detachHoldsLinksAgainUntilReattach() {
        val router = FakeRouter()
        val hub = hub()
        hub.attachRouter(router)
        hub.detachRouter(router)
        hub.submit(DeepLink.Settings)
        assertTrue(router.calls.isEmpty())
        hub.attachRouter(router)
        assertEquals(listOf("settings"), router.calls)
    }

    // -------------------------------------------------------------- chats

    @Test
    fun rawIdIsResolvedThroughTheKnownIds() {
        val router = FakeRouter()
        val hub = hub()
        hub.attachRouter(router)
        hub.submit(DeepLink.OpenChat("raw-1"))
        assertEquals(listOf("open($knownScoped)"), router.calls)
        assertEquals(1, knownCalls)
    }

    @Test
    fun scopedIdSkipsTheKnownIdLookup() {
        val router = FakeRouter()
        val hub = hub()
        hub.attachRouter(router)
        hub.submit(DeepLink.OpenChat(knownScoped))
        assertEquals(listOf("open($knownScoped)"), router.calls)
        assertEquals(0, knownCalls)
    }

    @Test
    fun ambiguousRawIdIsPassedThroughToFailInTheRouter() {
        val router = FakeRouter()
        val other = ScopedIds.PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""["k2","raw-1"]""".toByteArray(Charsets.UTF_8))
        val hub = DeepLinkHub(knownChatIds = { listOf(knownScoped, other) })
        hub.attachRouter(router)
        hub.submit(DeepLink.OpenChat("raw-1"))
        // The open fails in the router → the "Couldn't open session" toast.
        assertEquals(listOf("open(raw-1)"), router.calls)
    }

    @Test
    fun newSessionPromptIsForwarded() {
        val router = FakeRouter()
        val hub = hub()
        hub.attachRouter(router)
        hub.submit(DeepLink.NewSession("Fix tests"))
        hub.submit(DeepLink.NewSession(null))
        assertEquals(listOf("new(Fix tests)", "new(null)"), router.calls)
    }

    // ---------------------------------------------------------------- rig

    /** Records every call as a readable string. */
    private class FakeRouter : AppRouter {
        val calls = mutableListOf<String>()

        override fun openSession(chatId: String) {
            calls.add("open($chatId)")
        }

        override fun openSession(chatId: String, handoff: DraftHandoff?) {
            calls.add("open($chatId, handoff)")
        }

        override fun presentNewSession(prompt: String?) {
            calls.add("new($prompt)")
        }

        override fun showSettings() {
            calls.add("settings")
        }

        override fun showSearch() {
            calls.add("search")
        }

        override fun showSessionOpenError() {
            calls.add("error")
        }

        override fun popToFrontPage() {
            calls.add("pop")
        }
    }
}
