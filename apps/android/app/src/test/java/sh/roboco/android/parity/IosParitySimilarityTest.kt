package sh.roboco.android.parity

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G4 of the iOS parity gate (android-app#30): the perceptual ratchet.
 * For every manifest entry with an `iosRef` **and** an `ssimFloor`, the
 * committed golden is compared against its iOS reference by windowed
 * SSIM over luminance ([Ssim]), both sides normalized to 393 × 764:
 *
 * - the Android golden is cropped (top 54 dp, bottom 34 dp) and scaled
 *   by 1/3 (the 393 dp @ 3× phone goldens);
 * - the iOS reference is cropped (status bar 0–54 pt, home indicator
 *   bottom 34 pt) and scaled by 393/460.
 *
 * The score must not fall below the entry's `ssimFloor`. The initial
 * floor is the first signed-off score − 0.02, and a floor may only be
 * lowered with a manifest note (checked by [ParityManifestTest]).
 *
 * Until the goldens are recorded and signed off (the recording pass on
 * CI), this test fails with the actionable reason — that is the ratchet
 * arming, not flakiness. Plain JVM: `javax.imageio`, no Robolectric.
 */
class IosParitySimilarityTest {

    private val entries: List<ParityEntry> =
        ParityJson.parseManifest(ParityPaths.manifest.readText())

    @Test
    fun everyRatchetEntryMeetsItsFloor() {
        val problems = mutableListOf<String>()
        for (entry in entries.filter { it.iosRef != null }) {
            val golden = ParityPaths.golden(entry.golden)
            val reference = ParityPaths.iosRef(entry.iosRef!!)
            if (!golden.isFile) {
                problems += "${entry.golden}: not recorded yet (./gradlew :app:recordRoborazziDebug)"
                continue
            }
            // Every entry with a reference prints its score, floor or not,
            // so the first sign-off can baseline it (the pending failure
            // lives in ParityManifestTest). A scoring error is recorded
            // per entry: one bad pair must not hide the other scores (verify
            // run 38622776189's crash on the first entry printed none).
            val score = try {
                score(golden, reference)
            } catch (e: Exception) {
                problems += "${entry.golden}: could not score against ${entry.iosRef} ($e)"
                continue
            }
            val floor = entry.ssimFloor
            println("parity ssim ${entry.golden}: score $score (floor ${floor ?: "not baselined yet"})")
            if (floor == null) continue
            if (score < floor) {
                problems += "${entry.golden}: SSIM $score < floor $floor (regression, or lower the floor with a manifest note)"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun score(golden: java.io.File, reference: java.io.File): Double {
        // The phone goldens are 393 dp at xxhdpi (3 px/dp); the settings
        // family is 402 dp at xxhdpi — both normalize through their own
        // pixel width, so only the density is passed here.
        val a = Ssim.luminance(Ssim.golden393(golden, pxPerDp = 3f))
        val b = Ssim.luminance(Ssim.ios393(reference))
        return Ssim.score(a, b)
    }
}
