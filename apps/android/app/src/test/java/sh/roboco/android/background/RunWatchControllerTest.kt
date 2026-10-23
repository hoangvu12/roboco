package sh.roboco.android.background

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.notify.Notifier
import sh.roboco.android.notify.WorkingRun

/**
 * The run-watching foreground service's lifecycle (android-app#28):
 * start (foreground + working + notifications on), keep while working,
 * the 2 min linger (ADR 0010), the immediate stops (notifications off,
 * last engine forgotten), the dataSync timeout latch, the foreground
 * restart, and the opt-in Stay connected mode (ADR 0010). Robolectric for
 * the `Intent`s, virtual time for the linger — the controller's clock is
 * the scheduler's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RunWatchControllerTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun workingRun(chatId: String = "engine:v1:a", fromThisDevice: Boolean = false) =
        WorkingRun(
            chatId = chatId,
            title = "A run",
            engineName = "Mac",
            startedAtMs = 1_000L,
            fromThisDevice = fromThisDevice,
        )

    @Test
    fun startingNeedsForegroundWorkingAndNotificationsOn() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        // Background, no run, no engines: nothing.
        controller.onRunActivity(emptyList(), 0)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        assertEquals(0, controller.starts)
        assertFalse(controller.state.value.active)
        // Foreground + one working run: the service starts, exactly once.
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        assertEquals(1, controller.starts)
        assertTrue(controller.state.value.active)
        // The service acknowledges; more activity does not restart it.
        controller.onServiceStarted()
        controller.onRunActivity(listOf(workingRun(), workingRun("engine:v1:b")), 2)
        assertEquals(1, controller.starts)
        assertTrue(controller.state.value.active)
    }

    @Test
    fun theServiceNeverStartsWhileBackgrounded() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(false)
        // A run starts on the engine while the app is backgrounded: the
        // start is caught and dropped (API 31+), retried on the next
        // foreground.
        controller.onRunActivity(listOf(workingRun()), 0)
        assertEquals(0, controller.starts)
        // The service SHOULD run (it would keep one already up), but the
        // platform forbids starting it from the background: no start.
        assertTrue(controller.state.value.active)
        controller.onForeground(true)
        assertEquals(1, controller.starts)
        assertTrue(controller.state.value.active)
    }

    @Test
    fun notificationsOffOrNoEnginesStopsAtOnce() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onServiceStarted()
        assertTrue(controller.state.value.active)
        // Notifications off: stop now.
        controller.onNotificationsOn(false)
        assertFalse(controller.state.value.active)
        // Back on, then the last engine forgotten: stop now.
        controller.onNotificationsOn(true)
        assertTrue(controller.state.value.active)
        controller.onEngineCount(0)
        assertFalse(controller.state.value.active)
    }

    @Test
    fun theLingerHoldsTheServiceForTwoMinutesAfterTheLastRun() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onServiceStarted()
        // The user leaves the app; the last run leaves Working: the linger
        // opens (the Done notification goes out now, the tap comes later).
        controller.onForeground(false)
        controller.onRunActivity(emptyList(), 0)
        assertTrue("the linger holds the service", controller.state.value.active)
        assertFalse("the linger is not Stay connected", controller.state.value.persistent)
        // 10 s in, a queue handoff flickers Working again: the window
        // resets.
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(controller.state.value.active)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onRunActivity(emptyList(), 0)
        // Well past the old 15 s grace: still up (live sockets for the tap).
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue("still lingering a minute after the last run", controller.state.value.active)
        // The full linger passes: the service stops (the delayed wake flips it).
        advanceTimeBy(RunWatchController.LINGER_MS - 60_000 + 100)
        runCurrent()
        assertFalse("the service stops after the linger", controller.state.value.active)
        assertEquals(1, controller.starts)
    }

    @Test
    fun aRunThatStartsDuringTheLingerIsKeptWhileBackgrounded() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onServiceStarted()
        controller.onForeground(false)
        controller.onRunActivity(emptyList(), 0)
        advanceTimeBy(90_000)
        runCurrent()
        // A run starts on the desktop while the app is backgrounded and the
        // service still lingers: it is kept (no new start needed)...
        controller.onRunActivity(listOf(workingRun("engine:v1:b")), 0)
        advanceTimeBy(RunWatchController.LINGER_MS)
        runCurrent()
        assertTrue(controller.state.value.active)
        assertEquals(1, controller.starts)
        // ...and gets its own linger when it ends.
        controller.onRunActivity(emptyList(), 0)
        advanceTimeBy(RunWatchController.LINGER_MS + 100)
        runCurrent()
        assertFalse(controller.state.value.active)
    }

    // ── Stay connected (ADR 0010) ────────────────────────────────────────

    @Test
    fun stayConnectedStartsTheServiceWithNoRunAndHoldsItInTheBackground() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(true)
        assertFalse(controller.state.value.active)
        // The Settings toggle (the app is visible): the service starts at
        // once, persistent (specialUse), with nothing Working.
        controller.onStayConnected(true)
        assertEquals(1, controller.starts)
        assertTrue(controller.state.value.active)
        assertTrue(controller.state.value.persistent)
        controller.onServiceStarted()
        // Backgrounded for an hour: still up, never restarted.
        controller.onForeground(false)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertTrue(controller.state.value.active)
        assertEquals(1, controller.starts)
        // A run starting and ending while backgrounded changes nothing.
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onRunActivity(emptyList(), 0)
        advanceTimeBy(RunWatchController.LINGER_MS + 100)
        runCurrent()
        assertTrue(controller.state.value.active)
        assertTrue(controller.state.value.persistent)
    }

    @Test
    fun stayConnectedIgnoresTheNotificationsGateButNotTheEngineCount() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onForeground(true)
        controller.onNotificationsOn(false)
        controller.onStayConnected(true)
        // No engine paired: nothing to stay connected to.
        assertFalse(controller.state.value.active)
        assertEquals(0, controller.starts)
        controller.onEngineCount(1)
        assertTrue("notifications off does not stop Stay connected", controller.state.value.active)
        assertEquals(1, controller.starts)
        controller.onServiceStarted()
        // The last engine forgotten: stop at once.
        controller.onEngineCount(0)
        assertFalse(controller.state.value.active)
        assertFalse(controller.state.value.persistent)
    }

    @Test
    fun stayConnectedOnlyStartsWhileForeground() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onEngineCount(1)
        controller.onForeground(false)
        // The stored preference loads while the process runs in the
        // background (Android forbids a background FGS start): no start.
        controller.onStayConnected(true)
        assertEquals(0, controller.starts)
        assertTrue("the service should run", controller.state.value.active)
        // The next app open starts it.
        controller.onForeground(true)
        assertEquals(1, controller.starts)
    }

    @Test
    fun turningStayConnectedOffRestoresTheRunOnlyRules() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(true)
        controller.onStayConnected(true)
        controller.onServiceStarted()
        // Off with nothing Working and no linger open: stop at once.
        controller.onStayConnected(false)
        assertFalse(controller.state.value.active)
        controller.onServiceStopped()

        // On again, a run comes and goes, then off mid-linger: the service
        // falls back to dataSync and the linger still runs out on time.
        controller.onStayConnected(true)
        assertEquals(2, controller.starts)
        controller.onServiceStarted()
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onRunActivity(emptyList(), 0)
        advanceTimeBy(30_000)
        runCurrent()
        controller.onStayConnected(false)
        assertTrue("the linger still holds it", controller.state.value.active)
        assertFalse("now dataSync", controller.state.value.persistent)
        advanceTimeBy(RunWatchController.LINGER_MS - 30_000 + 100)
        runCurrent()
        assertFalse(controller.state.value.active)
    }

    @Test
    fun stayConnectedIsImmuneToTheDataSyncBudgetLatch() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onServiceStarted()
        // A dataSync timeout latched the budget...
        controller.onServiceTimeout(Notifier(context))
        assertFalse(controller.state.value.active)
        controller.onServiceStopped()
        // ...Stay connected (specialUse, no time limit) still runs.
        controller.onStayConnected(true)
        assertTrue(controller.state.value.active)
        assertTrue(controller.state.value.persistent)
        assertEquals(2, controller.starts)
    }

    @Test
    fun theTimeoutLatchesAndTheForegroundRestartsTheService() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        assertEquals(1, controller.starts)
        controller.onServiceStarted()
        // onTimeout (API 35+): the service stops inside the callback, the
        // budget latches.
        controller.onServiceTimeout(Notifier(context))
        assertFalse(controller.state.value.active)
        assertTrue(controller.state.value.budgetExhausted)
        // The run still works, but no start while the budget latches.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, controller.starts)
        // Back to the foreground: the budget resets (the platform's 6 h
        // window resets there too) and the service restarts because a run
        // is still Working.
        controller.onServiceStopped()
        controller.onForeground(false)
        controller.onForeground(true)
        assertEquals(2, controller.starts)
        assertFalse(controller.state.value.budgetExhausted)
        assertTrue(controller.state.value.active)
    }

    @Test
    fun aDismissedLiveUpdateLatchesUntilTheWorkingSetChanges() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        assertFalse(controller.state.value.liveUpdateDismissed)
        // The user swiped the promoted notification away.
        controller.onLiveUpdateDismissed()
        assertTrue(controller.state.value.liveUpdateDismissed)
        // Same working set: nothing changes (no promotion request).
        controller.onRunActivity(listOf(workingRun()), 0)
        assertTrue(controller.state.value.liveUpdateDismissed)
        // The working set changes: promotion may be requested again.
        controller.onRunActivity(listOf(workingRun("engine:v1:b")), 0)
        assertFalse(controller.state.value.liveUpdateDismissed)
    }

    /** A controller under test: starts recorded, never executed, clock = the scheduler. */
    private fun TestScope.controller(starts: MutableList<Intent>): RunWatchController =
        RunWatchController(
            context = context,
            scope = this,
            clock = { testScheduler.currentTime },
            startService = { intent -> starts.add(intent) },
        )
}
