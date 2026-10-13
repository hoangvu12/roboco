package sh.roboco.android.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The run-watching foreground service's presence (android-app#28,
 * CI-only — no emulator in the worker slots), the acceptance's
 * `adb shell dumpsys activity services sh.roboco.android` check, run
 * through UiDevice's shell:
 *
 * - while a run is Working on a paired engine the service is present;
 * - ≤ 20 s after the last run leaves Working (the 15 s grace plus a
 *   re-check window) it is gone.
 *
 * Prerequisite: ticket 30's flow rig with a paired engine and a way to
 * drive a run (a slow mock run); until that rig lands this test only
 * verifies the absent state — the tracked state, like ui-parity#97's
 * forward-looking flow test.
 */
@RunWith(AndroidJUnit4::class)
class RunWatchServiceTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun theServiceIsAbsentWhenNothingIsWorking() {
        // The fixture is idle and the 15 s stop grace long expired.
        Thread.sleep(20_000)
        assertFalse("the service must be gone 20 s after the last run", servicePresent())
    }

    /** `dumpsys activity services sh.roboco.android` mentions the service. */
    private fun servicePresent(): Boolean {
        val dump = device.executeShellCommand(
            "dumpsys activity services sh.roboco.android",
        )
        return dump.contains("sh.roboco.android.background.RunWatchService")
    }
}
