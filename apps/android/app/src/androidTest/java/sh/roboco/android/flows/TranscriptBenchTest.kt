package sh.roboco.android.flows

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import sh.roboco.android.debug.TranscriptLabActivity
import java.io.File

/**
 * The transcript bench (android-app#30, the port of
 * `uit:ScrollPerformanceTests.swift`'s `testHitchRatioInApp`): launches
 * the Transcript Lab with `turns 300, bench true, top true` (the lab
 * launch extras of ui-parity #135/#136), waits for the lab's bench
 * result JSON, and asserts `hitchRatioMsPerS < 5` per phase.
 *
 * **Physical device only** (`assumeFalse(isEmulator)`): emulators give
 * no meaningful frame-timing signal, so CI's emulator job skips this —
 * it runs on a real device via `flow-tests.sh` against a plugged phone.
 *
 * The bench-result JSON is ticket 136's emission (`bench-result.json` in
 * the app's external files dir, one object per phase); until 136 lands
 * it never appears and the test skips with the ticket named — the
 * forward-looking pattern the pre-rig flow tests set.
 */
@RunWith(AndroidJUnit4::class)
@PhysicalDeviceOnly
class TranscriptBenchTest {

    @Test
    fun hitchRatioInApp() {
        // Emulators have no honest frame timing.
        assumeTrue("physical device only", !isEmulator())

        // Launch the lab with the bench extras (135/#136's contract).
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(target, TranscriptLabActivity::class.java)
            .putExtra("turns", 300)
            .putExtra("bench", true)
            .putExtra("top", true)
        InstrumentationRegistry.getInstrumentation().startActivitySync(intent)

        // Ticket 136 writes the result once the scripted scroll settles;
        // wait for it (the bench runs a fixed script, bounded in time).
        val resultFile = benchResultFile()
        val deadline = System.currentTimeMillis() + 120_000
        while (!resultFile.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
        }
        assumeTrue(
            "the lab's bench-result.json never appeared (ui-parity #136 has not landed)",
            resultFile.exists(),
        )

        val report = resultFile.readText()
        val json = JSONObject(report)
        val phases = json.getJSONArray("phases")
        assertTrue("the bench must report at least one phase", phases.length() > 0)
        for (i in 0 until phases.length()) {
            val phase = phases.getJSONObject(i)
            assertTrue(
                "phase $i must be named",
                phase.optString("name").isNotBlank(),
            )
            val hitch = phase.optDouble("hitchRatioMsPerS")
            assertTrue(
                "phase ${phase.optString("name")}: hitchRatioMsPerS must be < 5 " +
                    "(was $hitch)",
                hitch < 5.0,
            )
        }
    }

    /** The lab's result location: the app's external files dir (the flow script pulls it). */
    private fun benchResultFile(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "bench-result.json")
    }

    /** The standard emulator fingerprint check. */
    private fun isEmulator(): Boolean =
        (Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") || Build.PRODUCT.contains("sdk") ||
            Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu"))
}
