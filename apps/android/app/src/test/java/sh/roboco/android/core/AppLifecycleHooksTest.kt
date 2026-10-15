package sh.roboco.android.core

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The process lifecycle hooks (android-ui-parity #35): a
 * `LifecycleRegistry.createUnsafe` owner driven through ON_START/ON_STOP
 * calls the sink in order (onForeground then afterForeground), `null`
 * sinks are dropped without a crash, a double install returns the same
 * instance and adds no second observer, the real install runs its calls
 * on the `roboco-lifecycle` thread, and `clientReady()` re-sends the
 * current online value once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLifecycleHooksTest {

    /** A sink that records its calls and the threads they ran on. */
    private class RecordingSink : LifecycleSink {
        // Copy-on-write, not Collections.synchronizedList: the test thread
        // iterates (any/count/all) while the roboco-lifecycle thread adds,
        // and a synchronized list's iterator is not guarded
        // (ConcurrentModificationException).
        val calls = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<String>()

        override fun onForeground() {
            calls += "onForeground"
            threads += Thread.currentThread().name
        }

        override fun onBackground() {
            calls += "onBackground"
            threads += Thread.currentThread().name
        }

        override fun setNetworkOnline(online: Boolean) {
            calls += "setNetworkOnline($online)"
            threads += Thread.currentThread().name
        }

        /** Waits (bounded) for a call starting with [prefix] to land — the real executor is async. */
        fun await(prefix: String, timeoutMs: Long = 5_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (calls.any { it.startsWith(prefix) }) return true
                Thread.sleep(10)
            }
            return calls.any { it.startsWith(prefix) }
        }
    }

    /** Runs the command inline, so the direct-executor tests are synchronous. */
    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) {
            command.run()
        }
    }

    /** A lifecycle owner driven by hand (createUnsafe — no thread checks). */
    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val sink = RecordingSink()
    private val owner = TestOwner()

    @Before
    fun resetInstallState() {
        // Robolectric reuses this JVM across classes: forget the
        // process-wide instance so this class installs its own.
        AppLifecycleHooks.resetForTest()
    }

    @After
    fun resetInstallStateAfter() {
        AppLifecycleHooks.resetForTest()
    }

    private fun start() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    private fun stop() {
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    // ------------------------------------------------- ON_START / ON_STOP

    @Test
    fun startAndStopCallTheSinkInOrderThenAfterForeground() {
        val afterForeground = Collections.synchronizedList(mutableListOf<Int>())
        val hooks = AppLifecycleHooks(
            sink = { sink },
            executor = DirectExecutor,
            afterForeground = { afterForeground += 1 },
        )
        owner.lifecycle.addObserver(hooks)

        start()
        assertEquals(listOf("onForeground"), sink.calls)
        assertEquals(listOf(1), afterForeground)

        stop()
        assertEquals(listOf("onForeground", "onBackground"), sink.calls)
    }

    @Test
    fun aNullSinkIsDroppedWithoutCrashing() {
        val hooks = AppLifecycleHooks(
            sink = { null },
            executor = DirectExecutor,
            afterForeground = {},
        )
        owner.lifecycle.addObserver(hooks)

        start()
        stop()
        // No crash; nothing to assert on the sink side.
    }

    // ------------------------------------------------------------- install

    @Test
    fun installRunsItsCallsOnTheRobocoLifecycleThread() {
        AppLifecycleHooks.install(
            app = ApplicationProvider.getApplicationContext<Application>(),
            lifecycle = owner.lifecycle,
            sink = { sink },
        )
        start()
        assertTrue("onForeground never ran: ${sink.calls}", sink.await("onForeground"))
        stop()
        assertTrue("onBackground never ran: ${sink.calls}", sink.await("onBackground"))
        assertTrue(
            "calls ran on ${sink.threads}, expected roboco-lifecycle",
            sink.threads.all { it == "roboco-lifecycle" },
        )
    }

    @Test
    fun aDoubleInstallReturnsTheSameInstanceAndAddsNoObserver() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = AppLifecycleHooks.install(app, owner.lifecycle, sink = { sink })
        val before = owner.registry.observerCount

        val second = AppLifecycleHooks.install(app, owner.lifecycle, sink = { sink })

        assertSame(first, second)
        assertEquals(before, owner.registry.observerCount)
    }

    // ---------------------------------------------------------- clientReady

    @Test
    fun clientReadyResendsTheCurrentOnlineValueOnce() {
        val hooks = AppLifecycleHooks.install(
            app = ApplicationProvider.getApplicationContext<Application>(),
            lifecycle = owner.lifecycle,
            sink = { sink },
        )
        // The install seeds the initial reading; let it land.
        assertTrue("the seed call never ran: ${sink.calls}", sink.await("setNetworkOnline"))
        val seedCalls = sink.calls.count { it.startsWith("setNetworkOnline") }

        hooks.clientReady()
        assertTrue("clientReady never re-sent: ${sink.calls}", sink.await("setNetworkOnline"))
        // Exactly one re-send — count only settles once the executor is
        // done; poll briefly past the re-send.
        val deadline = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < deadline && sink.calls.count { it.startsWith("setNetworkOnline") } < seedCalls + 1) {
            Thread.sleep(10)
        }
        assertEquals(seedCalls + 1, sink.calls.count { it.startsWith("setNetworkOnline") })
    }
}
