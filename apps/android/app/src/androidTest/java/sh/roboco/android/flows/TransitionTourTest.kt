package sh.roboco.android.flows

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import java.util.regex.Pattern
import sh.roboco.android.shell.ScopedIds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transition tour (android-app#30, the port of
 * `uit:TransitionTourTests.swift`): drives every glass transition slowly,
 * with animations on, for screen recordings and visual motion review —
 * not assertions. [Tour]-annotated, so PR CI skips it; the tablet job
 * (`android-emulator-tablet`, nightly/dispatch only) runs it and the
 * artefact copy pulls the recordings.
 *
 * `screenrecord` runs through the one UiAutomation connection (bounded at
 * 180 s, the `--time-limit` cap; `executeShellCommand` is already
 * asynchronous); the tour stops it at the end with `pkill -INT
 * screenrecord` so the mp4 finalizes. Recordings land in
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

    /** `testTour`: push + pop, Settings and back, scrolling under the bottom bar, the new-session sheet in and out. */
    @Test
    fun tour() {
        record("tour") {
            // The front page.
            waitForText("Sessions")
            sleep(1_000)
            // Push a session, then pop.
            tapSession("chat-cjk")
            waitForTag("transcript")
            sleep(2_000)
            device.pressBack()
            sleep(2_000)
            // Settings over the list (the one-row bar steps aside), and back.
            tapTag("bottom-settings")
            sleep(2_000)
            device.pressBack()
            sleep(2_000)
            // Scroll under the bottom bar, then back.
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
            tapSession("chat-cjk")
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

    /**
     * Taps a session row by the engine's raw chat id: the row's tag is
     * `session-<scoped id>` (`engine:v1:…`) on a paired phone, never
     * `session-chat-cjk` — see [FlowSemantics.sessionRow].
     */
    private fun tapSession(rawChatId: String) {
        device.findObjects(By.res(Pattern.compile(".*session-.*"))).firstOrNull { row ->
            val id = row.resourceName?.substringAfter("session-")
            id != null && (id == rawChatId || ScopedIds.rawId(id) == rawChatId)
        }?.click()
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
        // UiDevice first, then the UiAutomation with UiDevice's own flags,
        // both on this thread: getUiAutomation() with other flags tears down
        // and reconnects the shared connection, and the old recorder
        // thread's concurrent connect raced it ("Calling from not trusted
        // UID!" / "Already connected.", run 38531061823).
        device
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(Configurator.getInstance().uiAutomationFlags)
        // Asynchronous: returns at once; the fd reaches EOF when screenrecord exits.
        val recording = automation.executeShellCommand("screenrecord --time-limit 180 /sdcard/roboco-tour-$name.mp4")
        try {
            body()
        } finally {
            // No shell here (Runtime.exec): a `|| true` would reach pkill as
            // arguments. SIGINT makes screenrecord finalize the mp4.
            automation.executeShellCommand("pkill -INT screenrecord").close()
            // Wait for screenrecord to exit (EOF), bounded by its 180 s limit.
            runCatching { ParcelFileDescriptor.AutoCloseInputStream(recording).use { it.readBytes() } }
        }
    }
}
