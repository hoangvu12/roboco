package sh.roboco.android.design.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The blur tier decision and the first-blurred-frames crash guard (ticket 07,
 * `GlassTier.kt`).
 *
 * Both are pure JVM logic (the only inputs are an SDK int, booleans and
 * `java.io.File`), so this is a plain unit test — the merge check runs it.
 * The tier truth table and the guard lifecycle are the ticket's acceptance
 * items; the guard's file protocol is pr621's sentinel idea
 * (`pr621:…/design/Glass.kt:43-127`, logic only).
 */
class GlassTierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // -------------------------------------------------------- truth table

    @Test
    fun tierDecisionMatchesTheTruthTable() {
        // (sdk, hw-accelerated, guard tripped, force fallback) → tier
        assertEquals(GlassTier.Blur, decideGlassTier(33, true, false, false))
        assertEquals(GlassTier.Blur, decideGlassTier(35, true, false, false))
        assertEquals(GlassTier.Blur, decideGlassTier(36, true, false, false))
        // API 31–32: Haze *can* blur there, this set chooses the fallback.
        assertEquals(GlassTier.Fallback, decideGlassTier(32, true, false, false))
        assertEquals(GlassTier.Fallback, decideGlassTier(31, true, false, false))
        // Robolectric / software canvas.
        assertEquals(GlassTier.Fallback, decideGlassTier(35, false, false, false))
        // The crash guard tripped last launch.
        assertEquals(GlassTier.Fallback, decideGlassTier(35, true, true, false))
        // The gallery's debug kill switch.
        assertEquals(GlassTier.Fallback, decideGlassTier(35, true, false, true))
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    fun freshGuardIsNotTripped() {
        val guard = GlassCrashGuard(tmp.newFolder())
        assertFalse(guard.tripped)
    }

    @Test
    fun armedGuardTripsANewGuardOnTheSameDir() {
        val dir = tmp.newFolder()
        GlassCrashGuard(dir).armOnce()
        // A *new* guard (a fresh process reading the same cache dir) sees the
        // half-written sentinel: the previous launch died while blurring.
        assertTrue(GlassCrashGuard(dir).tripped)
        assertTrue(File(dir, "glass-blur-trying").isFile)
    }

    @Test
    fun confirmedGuardIsNotTrippedAndArmWritesNothing() {
        val dir = tmp.newFolder()
        val guard = GlassCrashGuard(dir)
        guard.armOnce()
        guard.confirm()
        assertTrue(File(dir, "glass-blur-ok").isFile)

        val relaunched = GlassCrashGuard(dir)
        assertFalse(relaunched.tripped)
        assertFalse(File(dir, "glass-blur-trying").exists())

        // ok on disk means the launch cycle already succeeded: armOnce must
        // not write anything (no new sentinel to trip a later launch).
        relaunched.armOnce()
        assertFalse(File(dir, "glass-blur-trying").exists())
    }

    @Test
    fun trippedIsReadOnceAtConstruction() {
        val dir = tmp.newFolder()
        val guard = GlassCrashGuard(dir) // reads the files once, now: not tripped
        // A sibling arms the sentinel afterwards — the guard already read the
        // files at construction, so `tripped` stays false for this instance:
        // the resolved tier must not flap mid-run.
        GlassCrashGuard(dir).armOnce()
        assertFalse(guard.tripped)
    }

    @Test
    fun aBrokenFilesystemNeverCrashesOrTripsTheGuard() {
        // "dir" is a *file*, so every child write fails — deterministic on
        // Windows and Linux (a read-only directory is not portable).
        val notADir = tmp.newFile("sentinel")
        val guard = GlassCrashGuard(notADir)
        assertFalse(guard.tripped)
        guard.armOnce() // must not throw
        guard.confirm() // must not throw
        assertFalse(guard.tripped)
    }

    @Test
    fun guardWritesAreIdempotentWithinOneInstance() {
        val dir = tmp.newFolder()
        val guard = GlassCrashGuard(dir)
        guard.armOnce()
        guard.armOnce()
        guard.confirm()
        guard.confirm()
        assertTrue(File(dir, "glass-blur-ok").isFile)
        assertFalse(File(dir, "glass-blur-trying").exists())
        assertFalse(guard.tripped)
    }

    @Test
    fun guardWorksOnATemporaryDirectoryTree() {
        // The process singleton is keyed on the app cache dir; the file
        // protocol itself must work on any plain directory.
        val dir = Files.createTempDirectory("glass-guard").toFile()
        try {
            val guard = GlassCrashGuard(dir)
            assertFalse(guard.tripped)
            guard.armOnce()
            guard.confirm()
            assertFalse(GlassCrashGuard(dir).tripped)
        } finally {
            dir.deleteRecursively()
        }
    }
}
