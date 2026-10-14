package sh.roboco.android.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2 of the iOS parity gate (android-app#30): every committed golden is
 * pinned in `apps/android/parity/manifest.json` by a human sign-off —
 * a SHA-256 of the exact PNG, a reviewer and a date — and its checklist
 * item ids exist in `CHECKLIST.md`. This is what makes skipping the
 * review walk impossible: re-recording a golden changes its hash, and a
 * golden without an entry (or an entry without its golden) fails here.
 *
 * Fails when:
 * - a golden PNG under `src/test/screenshots` has no manifest entry;
 * - an entry's golden PNG is missing (record it: `:app:recordRoborazziDebug`);
 * - an entry's golden is recorded but `reviewedSha256` is still null
 *   (the sign-off is pending) or differs from the file's actual SHA-256;
 * - `iosRef` and `adaptation` are both null (every golden cites a source);
 * - an `iosRef` file is missing under `apps/android/parity/ios/`;
 * - a listed checklist item id is missing from `CHECKLIST.md`.
 */
class ParityManifestTest {

    private val entries: List<ParityEntry> =
        ParityJson.parseManifest(ParityPaths.manifest.readText())

    @Test
    fun everyCommittedGoldenHasAnEntry() {
        val listed = entries.map { it.golden }.toSet()
        val unlisted = ParityPaths.goldens().filter { it !in listed }
        assertTrue(
            "goldens without a manifest entry (add one per golden, then sign off):\n" +
                unlisted.joinToString("\n"),
            unlisted.isEmpty(),
        )
    }

    @Test
    fun everyEntryGoldenIsCommitted() {
        val committed = ParityPaths.goldens().toSet()
        val missing = entries.map { it.golden }.filter { it !in committed }
        assertTrue(
            "manifest entries whose golden PNG is missing (record it on CI: " +
                "./gradlew :app:recordRoborazziDebug):\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun everyEntryIsSignedOffAgainstTheExactPixels() {
        val problems = mutableListOf<String>()
        for (entry in entries) {
            val file = ParityPaths.golden(entry.golden)
            if (!file.isFile) continue // covered by everyEntryGoldenIsCommitted
            when (val pinned = entry.reviewedSha256) {
                null -> problems += "${entry.golden}: recorded but not signed off (fill reviewedSha256, reviewer, date in the manifest)"
                else -> {
                    val actual = ParityPaths.sha256(file)
                    if (!pinned.equals(actual, ignoreCase = true)) {
                        problems += "${entry.golden}: reviewedSha256 $pinned ≠ actual $actual (the golden changed without a new sign-off)"
                    }
                    if (entry.reviewer.isNullOrBlank() || entry.date.isNullOrBlank()) {
                        problems += "${entry.golden}: a signed-off entry needs a reviewer and a date"
                    }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyEntryCitesASource() {
        val orphans = entries.filter { it.iosRef == null && it.adaptation == null }
        assertTrue(
            "entries with neither iosRef nor adaptation:\n" +
                orphans.joinToString("\n") { it.golden },
            orphans.isEmpty(),
        )
    }

    @Test
    fun everyIosRefIsCommitted() {
        val problems = entries.mapNotNull { entry ->
            entry.iosRef?.let { ref ->
                if (!ParityPaths.iosRef(ref).isFile) "${entry.golden}: iosRef $ref is missing" else null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyChecklistItemIdExists() {
        val checklist = ParityPaths.checklist.readText()
        val problems = entries.flatMap { entry ->
            entry.checklistItems.filter { item -> !checklist.contains("`$item`") }
                .map { item -> "${entry.golden}: checklist item $item is missing from CHECKLIST.md" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theSsimRatchetsAreArmedOrExplicitlyPending() {
        // The three ratchet entries of §4 (the post-rewrite references).
        val ratchets = setOf(
            "sessions/sessions_root_scrolled_light.png",
            "sessions/sessions_root_grouped_scrolled_light.png",
            "settings/settings_screen_top_light.png",
        )
        assertEquals(
            "the three SSIM ratchet goldens changed — update this test and the manifest together",
            ratchets,
            ratchets intersect entries.map { it.golden }.toSet(),
        )
        // A floor may only be lowered with a manifest note (G4's rule);
        // the test cannot see git history, so it enforces the sanity form:
        // an unusually low floor (below 0.5 — a visibly different render)
        // must carry a note, and null is the pending state, never a skip.
        val problems = entries.filter { it.golden in ratchets }.mapNotNull { entry ->
            when {
                entry.ssimFloor == null ->
                    "${entry.golden}: ssimFloor not baselined yet (first sign-off sets it to score − 0.02)"
                entry.ssimFloor < 0.5 && entry.notes.isNullOrBlank() ->
                    "${entry.golden}: a floor below 0.5 needs a manifest note explaining the lowering"
                else -> null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
