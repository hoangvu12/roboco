package sh.roboco.android.update

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check decision (android-next#04): [UpdateChecker] over a fake
 * source — newer / equal / older, the missing (404) manifest, offline
 * (IOException), and unparseable documents. Only strictly newer versions
 * count. Plain JVM, no network.
 */
class UpdateCheckerTest {

    /** [text] null = the 404 (no manifest); [offline] = the fetch throws. */
    private class FakeSource(
        private val text: String?,
        private val offline: Boolean = false,
    ) : ManifestSource {
        override suspend fun fetch(): String? {
            if (offline) throw IOException("offline")
            return text
        }
    }

    private fun checker(current: String, manifest: String?) =
        UpdateChecker(FakeSource(manifest), current)

    @Test
    fun aStrictlyNewerManifestVersionIsAvailable() = runTest {
        val result = checker("0.9.2", """{"version": "0.10.0"}""").check()
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("0.10.0", (result as UpdateCheckResult.Available).manifest.version)
    }

    @Test
    fun aNewerPreReleaseCountsAsNewer() = runTest {
        val result = checker("1.0.0-rc.1", """{"version": "1.0.0"}""").check()
        assertTrue(result is UpdateCheckResult.Available)
    }

    @Test
    fun theSameVersionIsUpToDate() = runTest {
        assertTrue(checker("0.9.2", """{"version": "0.9.2"}""").check() is UpdateCheckResult.UpToDate)
    }

    @Test
    fun anOlderManifestVersionIsUpToDate() = runTest {
        assertTrue(checker("0.10.0", """{"version": "0.9.9"}""").check() is UpdateCheckResult.UpToDate)
    }

    @Test
    fun aNewerPreReleaseThanTheReleaseIsUpToDate() = runTest {
        // The user runs the release; a pre-release of the same version is
        // lower, not newer.
        assertTrue(checker("1.0.0", """{"version": "1.0.0-rc.2"}""").check() is UpdateCheckResult.UpToDate)
    }

    @Test
    fun aMissingManifestIsNotFound() = runTest {
        val result = checker("0.9.2", null).check()
        assertEquals(UpdateCheckReason.NOT_FOUND, (result as UpdateCheckResult.Unavailable).reason)
    }

    @Test
    fun anOfflineSourceIsOffline() = runTest {
        val result = UpdateChecker(FakeSource(null, offline = true), "0.9.2").check()
        assertEquals(UpdateCheckReason.OFFLINE, (result as UpdateCheckResult.Unavailable).reason)
    }

    @Test
    fun anUnparseableManifestIsNoManifest() = runTest {
        assertEquals(
            UpdateCheckReason.NO_MANIFEST,
            (checker("0.9.2", "{oops").check() as UpdateCheckResult.Unavailable).reason,
        )
        assertEquals(
            UpdateCheckReason.NO_MANIFEST,
            (checker("0.9.2", """{"version": "dev-build"}""").check() as UpdateCheckResult.Unavailable).reason,
        )
    }

    @Test
    fun anUnparseableCurrentVersionNeverBlocks() = runTest {
        // Like the desktop's lenient parse: an unparseable own version
        // reads as up to date, never as "update available".
        assertTrue(
            UpdateChecker(FakeSource("""{"version": "0.10.0"}"""), "dev-build").check()
                is UpdateCheckResult.UpToDate,
        )
    }

    // ------------------------------------------------ the engine nudge

    @Test
    fun theEngineNudgeFiresOnlyForEnginesOlderThanTheNewRelease() {
        val release = "0.10.0"
        assertTrue(engineNeedsNudge(listOf("0.9.2", null), release))
        assertTrue(engineNeedsNudge(listOf("0.9.2", "0.10.0"), release))
        // Everything already at the new version: no nudge.
        assertTrue(!engineNeedsNudge(listOf("0.10.0", "0.10.0"), release))
        // Newer engines: no nudge (the engine is ahead, not behind).
        assertTrue(!engineNeedsNudge(listOf("0.11.0"), release))
        // No versioned engine at all: no nudge.
        assertTrue(!engineNeedsNudge(listOf(null, null), release))
        assertTrue(!engineNeedsNudge(emptyList(), release))
        // An unparseable release: no nudge.
        assertTrue(!engineNeedsNudge(listOf("0.9.2"), "dev"))
    }
}
