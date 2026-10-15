package sh.roboco.android.benchmark

import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The port of `testHitchRatioInApp`
 * (`apps/ios/ZeronUITests/ScrollPerformanceTests.swift:52-76`): launch the
 * lab with `--ei turns 300 --ez bench true`, sleep through the ~21.6 s run
 * **without polling**, then read `bench-result` and assert the hitch
 * ratio stays under Apple's "good" 5 ms/s for both phases.
 *
 * The sleep rule is the iOS comment verbatim: every UiAutomator query
 * snapshots the app's accessibility tree on its main thread and would
 * itself cause the hitches being measured — so the launch goes through
 * `getUiAutomation(FLAG_DONT_USE_ACCESSIBILITY)` and nothing touches
 * UiDevice until the 32 s are over. Physical devices only: an emulator's
 * hitch numbers are meaningless.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptBenchTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The iOS skip rule: no emulator jank numbers. */
    private fun isEmulator(): Boolean =
        Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.contains("generic") ||
            Build.PRODUCT.contains("sdk")

    @Test
    fun hitchRatioInApp() {
        assumeFalse(isEmulator())

        // Launch without enabling accessibility (see the class doc). The
        // descriptor's output is drained before close — closing it first
        // can kill `am` with SIGPIPE before it starts the activity.
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
                .executeShellCommand("am start -W -n $TARGET/$LAB --ei turns 300 --ez bench true"),
        ).use { it.readBytes() }

        // No UiAutomator during the run: the whole bench (about 21.6 s)
        // must finish inside this window, like the iOS 32 s sleep.
        SystemClock.sleep(32_000)

        val device = UiDevice.getInstance(instrumentation)
        assertNotNull("bench-result never appeared", device.wait(Until.findObject(By.res("bench-result")), 30_000))

        var json: String? = null
        for (attempt in 0 until 20) {
            val text = device.findObject(By.res("bench-result"))?.text
            if (text != null && text.contains("streaming")) {
                json = text
                break
            }
            SystemClock.sleep(2_000)
        }
        val result = checkNotNull(json) { "bench-result never showed the full result JSON" }

        // The artifact: bench.json beside the test output (CI picks it up
        // as additionalTestOutput), printed like the iOS `BENCH` log.
        val outDir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File)
            ?: instrumentation.targetContext.externalCacheDir
            ?: instrumentation.targetContext.cacheDir
        File(outDir, "bench.json").writeText(result)
        println("BENCH $result")

        val o = JSONObject(result)
        for (phase in listOf("idle", "streaming")) {
            assertTrue(
                "$phase hitch ratio ${o.getJSONObject(phase).optDouble("hitchRatioMsPerS", 99.0)} ms/s >= 5",
                o.getJSONObject(phase).optDouble("hitchRatioMsPerS", 99.0) < 5.0,
            )
        }
    }
}
