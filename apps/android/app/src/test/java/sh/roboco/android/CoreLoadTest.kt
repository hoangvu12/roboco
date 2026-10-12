package sh.roboco.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.core.CoreLoader
import sh.roboco.android.core.CoreStatus

/**
 * The Robolectric core smoke test (android-app#13, satisfying ticket 10's
 * JVM acceptance): with `jna.library.path` set by Gradle, the generated
 * bindings load the host core and agree with the app's version derivation.
 *
 * Must run under Robolectric: the generated Kotlin references
 * `android.os.Build` (`android = true` in uniffi.toml).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoreLoadTest {

    @Test
    fun coreVersionMatchesTheAppVersion() {
        assertEquals(BuildConfig.VERSION_NAME, uniffi.roboco_core.coreVersion())
    }

    @Test
    fun layoutFixtureMarkdownIsNonEmpty() {
        // Ticket 05/10's lab fixture: the transcript pipeline's own input.
        assertTrue(uniffi.roboco_core.layoutFixtureMarkdown().isNotBlank())
    }

    @Test
    fun coreLoaderReportsLoaded() {
        val status = CoreLoader.load()
        assertTrue("expected Loaded, got $status", status is CoreStatus.Loaded)
        assertEquals(BuildConfig.VERSION_NAME, (status as CoreStatus.Loaded).version)
    }
}
