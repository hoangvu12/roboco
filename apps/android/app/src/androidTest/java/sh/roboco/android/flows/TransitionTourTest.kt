package sh.roboco.android.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transition tour (android-app#30, the port of
 * `uit:TransitionTourTests.swift`): drives every glass transition slowly,
 * with animations on, for screen recordings and visual motion review —
 * not assertions. [Tour]-annotated, so PR CI skips it; the tablet job
 * (`android-emulator-tablet`, push/dispatch only) runs it and the
 * artefact copy pulls the recordings.
 *
 * `adb shell screenrecord` runs in a background shell thread (bounded at
 * 180 s, the `--time-limit` cap); the tour stops it at the end with
 * `pkill -INT screenrecord` so the mp4 finalizes. Recordings land in
 * `/sdcard/roboco-tour-<name>.mp4`, where `flow-tests.sh`'s artefact
 * step picks them up.
 */
@Tour
@RunWith(AndroidJUnit4::class)
class TransitionTourTest {

    @get:Rule
    val launch = FixtureRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** `testTour`: push + pop, tabs, tab-bar minimize, the new-session sheet in and out. */
    @Test
    fun tour() {
        record("tour") {
            // The front page.
            waitForText("Sessions")
            sleep(1_000)
            // Push a session, then pop.
            tapTag("session-chat-cjk")
            waitForTag("transcript")
            sleep(2_000)
            device.pressBack()
            sleep(2_000)
            // Tabs.
            for (back in listOf("Settings", "Sessions")) {
                device.findObject(androidx.test.uiautomator.By.desc(back))?.click()
                sleep(1_000)
            }
            // Scroll to minimize the tab bar, then back.
            val height = device.displayHeight
            device.swipe(device.displayWidth / 2, height / 3, device.displayWidth / 2, height * 4 / 5, 60)
            sleep(1_000)
            device.swipe(device.displayWidth / 2, height * 4 / 5, device.displayWidth / 2, height / 3, 60)
            sleep(1_000)
            // The new-session sheet in and out (the placeholder today; the
            // handoff motion is what is reviewed).
            tapTag("new-session")
            sleep(2_000)
            device.pressBack()
            sleep(2_000)
        }
    }

    /** `testPushPop`: just push + pop (fast iteration on the tab bar / accessory handoff). */
    @Test
    fun pushPop() {
        record("push-pop") {
            waitForText("Sessions")
            sleep(1_000)
            tapTag("session-chat-cjk")
            sleep(2_000)
            device.pressBack()
            sleep(2_000)
        }
    }

    // ── helpers (best-effort by design — the tour reviews motion, it
    //      asserts nothing) ─────────────────────────────────────────────

    private fun waitForText(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(text)), 500)) {
                return
            }
        }
    }

    private fun waitForTag(tag: String) {
        val appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(
                    androidx.test.uiautomator.Until.hasObject(
                        androidx.test.uiautomator.By.res(appPackage, tag),
                    ),
                    500,
                )
            ) {
                return
            }
        }
    }

    private fun tapTag(tag: String) {
        val appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        device.findObject(androidx.test.uiautomator.By.res(appPackage, tag))?.click()
    }

    private fun sleep(ms: Long) {
        // The tour's whole point is watching the animations run — the
        // sleeps are the tour's content, not state waits.
        Thread.sleep(ms)
    }

    /**
     * Runs [body] while `screenrecord` captures the screen into
     * `/sdcard/roboco-tour-<name>.mp4`.
     */
    private fun record(name: String, body: () -> Unit) {
        val recorder = Thread {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screenrecord --time-limit 180 /sdcard/roboco-tour-$name.mp4",
            )
        }
        recorder.isDaemon = true
        recorder.start()
        try {
            body()
        } finally {
            // Finalize the recording (SIGINT makes screenrecord close the
            // mp4); give it a beat to flush.
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pkill -INT screenrecord || true",
            )
            recorder.join(2_000)
        }
    }
}
