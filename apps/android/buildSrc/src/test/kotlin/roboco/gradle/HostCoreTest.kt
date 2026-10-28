package roboco.gradle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host-core switch (ci-speed#07), against the invocations CI and the
 * docs actually use.
 */
class HostCoreTest {

    @Test
    fun jvmTestInvocationsKeepTheHostCore() {
        // android.yml's debug APK + JVM job.
        assertTrue(
            HostCore.needed(listOf(":app:assembleDebug", ":app:testDebugUnitTest", ":buildSrc:test")),
        )
        assertTrue(HostCore.needed(listOf(":app:testDebugUnitTest", "--tests", "sh.roboco.Foo")))
        assertTrue(HostCore.needed(listOf("verifyRoborazziDebug")))
        assertTrue(HostCore.needed(listOf("check")))
        assertTrue(HostCore.needed(listOf("build")))
        assertTrue(HostCore.needed(listOf(":app:assembleDebugUnitTest")))
    }

    @Test
    fun unknownOrEmptyRequestsKeepTheHostCore() {
        assertTrue(HostCore.needed(emptyList()))
        assertTrue(HostCore.needed(listOf("aD")))
        assertTrue(HostCore.needed(listOf(":app:buildDebugCore")))
    }

    @Test
    fun apkAndDeviceInvocationsSkipTheHostCore() {
        // flow-tests.sh (the emulator jobs).
        assertFalse(HostCore.needed(listOf(":app:connectedDebugAndroidTest")))
        // release.yml and the R8 smoke.
        assertFalse(HostCore.needed(listOf(":app:assembleRelease")))
        assertFalse(HostCore.needed(listOf(":app:assembleBenchmark")))
        assertFalse(HostCore.needed(listOf("clean", ":app:installDebug")))
        assertFalse(HostCore.needed(listOf(":app:assembleDebugAndroidTest")))
    }
}
