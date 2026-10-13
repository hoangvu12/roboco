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
 * the 15 s stop grace, the immediate stops (notifications off, last
 * engine forgotten), the dataSync timeout latch and the foreground
 * restart. Robolectric for the `Intent`s, virtual time for the grace —
 * the controller's clock is the scheduler's clock.
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
    fun theStopGraceHoldsForFifteenSeconds() = runTest {
        val starts = mutableListOf<Intent>()
        val controller = controller(starts)
        controller.onNotificationsOn(true)
        controller.onEngineCount(1)
        controller.onForeground(true)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onServiceStarted()
        // The last run leaves Working: the grace window opens.
        controller.onRunActivity(emptyList(), 0)
        assertTrue("the 15 s grace holds the service", controller.state.value.active)
        // 10 s in, a queue handoff flickers Working again: the window
        // resets.
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(controller.state.value.active)
        controller.onRunActivity(listOf(workingRun()), 0)
        controller.onRunActivity(emptyList(), 0)
        // The full 15 s pass: the service stops (the delayed wake flips it).
        advanceTimeBy(15_100)
        runCurrent()
        assertFalse("the service stops 15 s after the last run", controller.state.value.active)
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
