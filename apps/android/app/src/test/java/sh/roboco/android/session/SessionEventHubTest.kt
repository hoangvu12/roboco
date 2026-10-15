package sh.roboco.android.session

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.ClientEvent

/**
 * [SessionEventHub] (android-ui-parity #51): the routing table (which
 * event refreshes whom), the both-maps registration/unregistration,
 * and the #621/#609-shaped threading rule — `onClientEvent` from any
 * (background) thread only posts; the fan-out happens inside
 * `dispatchOnMain` when the posted `Runnable` runs.
 *
 * Plain JUnit 4, no Robolectric: the hub's main-thread hop is injected
 * (`post`), so no `Handler`/`Looper` is ever created here.
 */
class SessionEventHubTest {

    /** Records which listener fired. */
    private class RecordingListener(private val log: CopyOnWriteArrayList<String>, private val tag: String) :
        SessionEventListener {
        override fun requestRefresh() {
            log += tag
        }
    }

    /** A hub whose posted runnables run synchronously on the calling thread. */
    private fun syncHub() = SessionEventHub(post = { it.run() })

    private fun workspace() = ClientEvent.WorkspaceChanged(2uL)

    @Test
    fun sessionAndComposerChangedReachOnlyThatChat() {
        val hub = syncHub()
        val log = CopyOnWriteArrayList<String>()
        hub.register("chat-a") { log += "a" }
        hub.register("chat-b") { log += "b" }

        hub.onClientEvent(ClientEvent.ComposerChanged("chat-a", 2uL))
        assertEquals(listOf("a"), log.toList())

        log.clear()
        hub.onClientEvent(ClientEvent.SessionChanged("chat-a", 3uL))
        assertEquals(listOf("a"), log.toList())

        log.clear()
        hub.onClientEvent(ClientEvent.ComposerChanged("chat-b", 2uL))
        assertEquals(listOf("b"), log.toList())

        // No controller registered for a chat: nobody fires.
        log.clear()
        hub.onClientEvent(ClientEvent.ComposerChanged("chat-zz", 2uL))
        assertTrue(log.isEmpty())
    }

    @Test
    fun workspaceConnectivityAndEnginesChangedReachEveryone() {
        val hub = syncHub()
        val log = CopyOnWriteArrayList<String>()
        hub.register("chat-a", RecordingListener(log, "a"))
        hub.register("chat-b", RecordingListener(log, "b"))

        hub.onClientEvent(workspace())
        assertEquals(listOf("a", "b"), log.toList())

        log.clear()
        hub.onClientEvent(ClientEvent.ConnectivityChanged(connectivity()))
        assertEquals(listOf("a", "b"), log.toList())

        log.clear()
        hub.onClientEvent(ClientEvent.EnginesChanged)
        assertEquals(listOf("a", "b"), log.toList())
    }

    @Test
    fun noticeAndEngineIdentityPinnedReachNoOne() {
        val hub = syncHub()
        val log = CopyOnWriteArrayList<String>()
        hub.register("chat-a") { log += "a" }
        hub.register("chat-b") { log += "b" }

        hub.onClientEvent(ClientEvent.Notice("reverted an overlay write"))
        hub.onClientEvent(ClientEvent.EngineIdentityPinned(engineId = "e", deviceId = "d", name = "n"))

        assertTrue(log.isEmpty())
    }

    @Test
    fun unregisterStopsBothPerChatAndGlobalDelivery() {
        val hub = syncHub()
        val log = CopyOnWriteArrayList<String>()
        val remove = hub.register("chat-a") { log += "a" }
        hub.register("chat-b") { log += "b" }

        remove()

        // Per-chat event for the removed chat: nothing.
        hub.onClientEvent(ClientEvent.ComposerChanged("chat-a", 2uL))
        assertTrue(log.isEmpty())

        // Global event: the removed listener must not fire either (it was
        // removed from `all`, not just from its chat's set).
        hub.onClientEvent(workspace())
        assertEquals(listOf("b"), log.toList())

        // Re-registering the same chat id works after removal.
        hub.register("chat-a") { log += "a2" }
        hub.onClientEvent(ClientEvent.SessionChanged("chat-a", 4uL))
        assertEquals(listOf("b", "a2"), log.toList())
    }

    @Test
    fun onClientEventFromABackgroundThreadDefersToThePostedRunnable() {
        val posted = CopyOnWriteArrayList<Runnable>()
        val hub = SessionEventHub(post = { posted += it })
        val log = CopyOnWriteArrayList<String>()
        hub.register("chat-a") { log += "a" }

        // A Rust callback thread calls in. Only the hop is queued.
        val thread = Thread { hub.onClientEvent(ClientEvent.ComposerChanged("chat-a", 2uL)) }
        thread.start()
        thread.join()
        assertTrue(log.isEmpty())
        assertEquals(1, posted.size)

        // The fan-out happens inside dispatchOnMain, when the runnable runs.
        posted.single().run()
        assertEquals(listOf("a"), log.toList())
    }

    @Test
    fun manyEventsFanOutOncePerEventOnThePostedRunnable() {
        val posted = CopyOnWriteArrayList<Runnable>()
        val hub = SessionEventHub(post = { posted += it })
        val log = CopyOnWriteArrayList<String>()
        hub.register("chat-a") { log += "a" }

        repeat(3) { hub.onClientEvent(ClientEvent.ComposerChanged("chat-a", 2uL)) }
        assertEquals(3, posted.size)
        // The listener has not run yet: fan-out only happens on run.
        assertTrue(log.isEmpty())
        posted.forEach { it.run() }
        assertEquals(listOf("a", "a", "a"), log.toList())
    }
}
