package sh.roboco.android.benchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/**
 * The lab launcher constants and helpers (android-ui-parity #136), the
 * `launchLab` helper of `apps/ios/ZeronUITests/ScrollPerformanceTests.swift:12-18`:
 * target package, the debug lab activity (135's), the intent with the
 * `turns`/flag extras, and the wait for the transcript host.
 */

/** The measured app's package (`sh.roboco.android`). */
const val TARGET = "sh.roboco.android"

/** 135's debug-only lab activity (exported, so `am start`/Macrobenchmark reach it). */
const val LAB = "sh.roboco.android.debug.TranscriptLabActivity"

/** One lab launch: `-lab -turns <turns>` plus boolean flag extras. */
fun labIntent(turns: Int = 300, vararg flags: String): Intent =
    Intent()
        .setClassName(TARGET, LAB)
        .putExtra("turns", turns)
        .apply { flags.forEach { putExtra(it, true) } }

/**
 * Waits up to 10 s for the transcript host (`transcript`, ticket 65's
 * test tag on the host box, exposed as a resource id by the lab root) —
 * the iOS helper's `waitForExistence` of the scroll view.
 */
fun MacrobenchmarkScope.awaitTranscript() {
    check(device.wait(Until.hasObject(By.res("transcript")), 10_000)) {
        "transcript host never appeared within 10 s"
    }
}
