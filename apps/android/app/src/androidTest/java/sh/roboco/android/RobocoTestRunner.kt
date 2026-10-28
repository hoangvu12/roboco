package sh.roboco.android

import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * The instrumented suite's runner (android-app#30): `AndroidJUnitRunner`
 * plus the device filters, decided **in-process** and merged into the
 * runner's own `notAnnotation` argument before it is parsed.
 *
 * Why not the `-e notAnnotation a,b,c` argument alone: on CI (AGP's test
 * platform + the test orchestrator) a comma-separated value arrived cut at
 * its first comma — the phone run excluded only `@PhysicalDeviceOnly` and
 * still scheduled `@TabletOnly`/`@Tour` (run 38531061823), whose
 * assumption failures the orchestrator reports as failures. Inside the
 * process the bundle goes straight to `RunnerArgs`, which splits commas
 * itself. The orchestrator starts this runner for its listing pass and for
 * every test, so excluded tests are never even listed.
 *
 * Excluded:
 * - `@TabletOnly` below a 600 dp smallest width, `@PhoneOnly` at 600 dp
 *   and above (the tablet job's split shell);
 * - `@PhysicalDeviceOnly` on an emulator;
 * - `@Tour` unless the single-value argument `flowIncludeTour=true` is
 *   passed (the tablet job's `FLOW_INCLUDE_TOUR=1`, see `flow-tests.sh`);
 * - `@org.junit.Ignore`: the orchestrator's listing pass lists ignored
 *   tests, then each runs alone, reports "ignored" and is recorded as a
 *   failure with an empty stack (it never "started"). Filtering them out
 *   keeps the parked port rows out of the report.
 */
class RobocoTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle?) {
        val args = Bundle(arguments ?: Bundle())
        val excluded = LinkedHashSet<String>()
        args.getString(NOT_ANNOTATION)
            ?.split(',')
            ?.map(String::trim)
            ?.filterTo(excluded) { it.isNotEmpty() }
        excluded += IGNORE
        val smallestWidth = targetContext.resources.configuration.smallestScreenWidthDp
        if (smallestWidth < TABLET_MIN_WIDTH_DP) excluded += TABLET_ONLY else excluded += PHONE_ONLY
        if (isEmulator()) excluded += PHYSICAL_DEVICE_ONLY
        if (args.getString(INCLUDE_TOUR) != "true") excluded += TOUR
        args.putString(NOT_ANNOTATION, excluded.joinToString(","))
        super.onCreate(args)
    }

    private fun isEmulator(): Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.PRODUCT.startsWith("sdk") || Build.DEVICE.startsWith("emu")

    private companion object {
        const val NOT_ANNOTATION = "notAnnotation"
        const val INCLUDE_TOUR = "flowIncludeTour"
        const val TABLET_MIN_WIDTH_DP = 600
        const val IGNORE = "org.junit.Ignore"
        const val TABLET_ONLY = "sh.roboco.android.flows.TabletOnly"
        const val PHONE_ONLY = "sh.roboco.android.flows.PhoneOnly"
        const val PHYSICAL_DEVICE_ONLY = "sh.roboco.android.flows.PhysicalDeviceOnly"
        const val TOUR = "sh.roboco.android.flows.Tour"
    }
}
