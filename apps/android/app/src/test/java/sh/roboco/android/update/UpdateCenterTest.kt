package sh.roboco.android.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.AlertHostState

/**
 * The update center's behaviour (android-next#04) over fakes: the launch
 * prompt shows **once per process** with the approved copy (panel A) and
 * the engine nudge, "Later" silences it for the process, the hourly
 * re-check never re-prompts, the download morphs the alert in place
 * (panel B, no second alert), the checksum and signer gates refuse
 * (files deleted), the one-time permission ask appears when Android
 * hasn't granted installs (panel C) and resumes on the foreground edge,
 * the install status broadcast drives the confirm intent and the failure
 * alert, and non-release builds never install — they open the releases
 * page. Robolectric for the resources and `Intent`s; virtual time for
 * the schedule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class UpdateCenterTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    /** The fake APK's bytes; the manifest's sha256 matches them. */
    private val apkBytes = APK_BYTES

    private val manifestJson: String
        get() {
            val sha = sha256Hex(APK_BYTES)
            return """
                {"version": "0.10.0", "date": "2026-10-20T13:37:00Z",
                 "files": {"roboco-0.10.0-android-arm64-v8a.apk":
                   {"sha256": "$sha", "size": ${APK_BYTES.size}}}
                }
            """.trimIndent()
        }

    private class FakeFeed(private val json: String?) : ManifestSource {
        var calls = 0
        override suspend fun fetch(): String? {
            calls++
            return json
        }
    }

    private companion object {
        /** The fake APK's bytes (nested classes read it; the manifest's sha matches). */
        val APK_BYTES = "a tiny fake apk, fortyish bytes long".toByteArray()
    }
    private class FakeApkSource : ApkByteSource {
        var opens = 0
        var opened: String? = null
        override fun open(url: String): Pair<InputStream, Long?> {
            opens++
            opened = url
            return ByteArrayInputStream(APK_BYTES) to APK_BYTES.size.toLong()
        }
    }

    private class FakeVerifier(var result: ApkVerifier.Result) : ApkVerifier {
        var verified: String? = null
        override fun verify(apkPath: String): ApkVerifier.Result {
            verified = apkPath
            return result
        }
    }

    private class FakeInstaller(
        var canInstall: Boolean = false,
    ) : ApkInstaller {
        val installed = mutableListOf<File>()
        val openedSettings = mutableListOf<Unit>()
        var nextSessionId = 7
        override fun canInstall(): Boolean = canInstall
        override fun openUnknownSourcesSettings() {
            openedSettings += Unit
        }
        override suspend fun install(apk: File): Int {
            installed += apk
            return nextSessionId++
        }
    }

    /** Builds a center over the fakes; [block] configures before start. */
    private fun TestScope.center(
        feed: ManifestSource = FakeFeed(manifestJson),
        apkSource: FakeApkSource = FakeApkSource(),
        verifier: FakeVerifier = FakeVerifier(ApkVerifier.Result.Ok),
        installer: FakeInstaller = FakeInstaller(),
        installEnabled: Boolean = true,
        alerts: AlertHostState = AlertHostState(),
        engineVersions: List<String?> = listOf("0.9.2"),
        currentVersion: String = "0.9.2",
        openReleases: () -> Unit = {},
        clock: () -> Long = { 1_000_000L },
        initialDelayMs: Long = 0L,
    ): UpdateCenter {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return UpdateCenter(
            context = context,
            scope = backgroundScope,
            alerts = alerts,
            feed = feed,
            apkSource = apkSource,
            verifier = verifier,
            installer = installer,
            checker = UpdateChecker(feed, currentVersion),
            io = dispatcher,
            clock = clock,
            initialDelayMs = initialDelayMs,
            installEnabled = installEnabled,
            currentVersion = currentVersion,
            engineVersions = { engineVersions },
            rootReady = { true },
            deviceAbi = { "arm64-v8a" },
            openReleases = openReleases,
        )
    }

    // ------------------------------------------------------ the prompt (A)

    @Test
    fun aLaunchCheckPromptsOnceWithTheApprovedCopy() = runTest {
        val alerts = AlertHostState()
        val feed = FakeFeed(manifestJson)
        val center = center(feed = feed, alerts = alerts)
        center.onForeground(true)
        center.start()
        runCurrent()

        val presented = alerts.current
        assertNotNull("the prompt presented", presented)
        val alert = presented!!.alert
        assertEquals("Update Available", alert.title)
        assertEquals("Roboco 0.10.0 is available. You have 0.9.2.\nUpdate Roboco on your computer too.", alert.message)
        assertEquals(2, alert.buttons.size)
        assertEquals("Update", alert.buttons[0].title)
        assertTrue("Update is the preferred button", alert.buttons[0].preferred)
        assertTrue("Update morphs the alert in place", alert.buttons[0].morphsInPlace)
        assertEquals("Later", alert.buttons[1].title)
        assertEquals(AlertButtonRole.Cancel, alert.buttons[1].role)

        // The hourly re-check finds it again: no second prompt in the
        // process — nothing queued, the same alert still up.
        advanceTimeBy(UpdateSchedule.CHECK_INTERVAL_MS + 5_000)
        runCurrent()
        assertSame(presented, alerts.current)
        assertTrue(alerts.queue.isEmpty())
        assertEquals(2, feed.calls)
    }

    @Test
    fun laterDismissesForTheRestOfTheLaunch() = runTest {
        val alerts = AlertHostState()
        val center = center(alerts = alerts)
        center.onForeground(true)
        center.start()
        runCurrent()
        assertNotNull(alerts.current)

        // "Later": the alert's own handler.
        alerts.current!!.alert.buttons[1].onClick("")
        // And a later check never re-prompts (nothing queued).
        advanceTimeBy(UpdateSchedule.CHECK_INTERVAL_MS + 5_000)
        runCurrent()
        assertTrue(alerts.queue.isEmpty())
    }

    @Test
    fun upToDateAndFailedChecksNeverPrompt() = runTest {
        // Up to date (the same version) and failed checks (no manifest, junk).
        // Round 52: the second feed was `{"version": "0.9.9"}`, a valid and
        // strictly newer release than 0.9.2, which SHOULD prompt; a failed
        // check is a null fetch or an unparseable body.
        listOf(
            FakeFeed("""{"version": "0.9.2"}"""),
            FakeFeed(null),
            FakeFeed("not a manifest"),
        ).forEach { feed ->
            val alerts = AlertHostState()
            val center = center(feed = feed, alerts = alerts)
            center.onForeground(true)
            center.start()
            runCurrent()
            assertNull(alerts.current)
        }
    }

    @Test
    fun thePromptWaitsForTheShellToBeIdle() = runTest {
        val alerts = AlertHostState()
        var ready = false
        val dispatcher = StandardTestDispatcher(testScheduler)
        val center = UpdateCenter(
            context = context,
            scope = backgroundScope,
            alerts = alerts,
            feed = FakeFeed(manifestJson),
            checker = UpdateChecker(FakeFeed(manifestJson), "0.9.2"),
            io = dispatcher,
            initialDelayMs = 0,
            // Round 52: a release build. The constructor's default is
            // BuildConfig.UPDATE_INSTALLER_ENABLED, false in the debug
            // unit-test variant, and non-release builds never prompt
            // (nonReleaseBuildsNeverPrompt), so this test could never pass.
            installEnabled = true,
            currentVersion = "0.9.2",
            rootReady = { ready },
            deviceAbi = { "arm64-v8a" },
        )
        center.onForeground(true)
        center.start()
        runCurrent()
        // An update is known, but the pairing root is still up: no prompt.
        assertNull(alerts.current)
        // The shell settles: the prompt presents within one poll.
        ready = true
        advanceTimeBy(2_000)
        runCurrent()
        assertNotNull(alerts.current)
    }

    @Test
    fun noEngineVersionMeansNoNudgeLine() = runTest {
        val alerts = AlertHostState()
        val center = center(alerts = alerts, engineVersions = listOf(null, "0.10.0"))
        center.onForeground(true)
        center.start()
        runCurrent()
        assertEquals("Roboco 0.10.0 is available. You have 0.9.2.", alerts.current!!.alert.message)
    }

    @Test
    fun nonReleaseBuildsNeverPrompt() = runTest {
        val alerts = AlertHostState()
        val center = center(installEnabled = false, alerts = alerts)
        center.onForeground(true)
        center.start()
        runCurrent()
        assertNull(alerts.current)
    }

    // ------------------------------------------------- the update flow (B)

    @Test
    fun beginUpdateMorphsThePromptIntoTheDownloadingAlert() = runTest {
        val alerts = AlertHostState()
        val apkSource = FakeApkSource()
        val installer = FakeInstaller(canInstall = true)
        val center = center(apkSource = apkSource, installer = installer, alerts = alerts)
        center.onForeground(true)
        center.start()
        runCurrent()
        val promptId = alerts.current!!.id

        center.beginUpdate()
        runCurrent()

        // The SAME alert (panel B is a morph, not a second alert).
        assertEquals(promptId, alerts.current!!.id)
        val alert = alerts.current!!.alert
        assertEquals("Updating Roboco", alert.title)
        assertEquals("Downloading 0.10.0…", alert.message)
        assertNotNull("the progress section is up", alert.progress)
        assertEquals(1, alert.buttons.size)
        assertEquals("Cancel", alert.buttons[0].title)
        assertEquals(AlertButtonRole.Cancel, alert.buttons[0].role)

        // Verified, signer-checked, handed to the installer; the alert's
        // job ends at the hand-off (it fades out).
        assertTrue(installer.installed.size == 1)
        assertTrue(apkSource.opened!!.endsWith("roboco-0.10.0-android-arm64-v8a.apk"))
        // ...and the cached APK is what was handed over.
        val staged = installer.installed[0]
        assertTrue(staged.isFile)
        assertTrue(staged.name == "roboco-0.10.0-android-arm64-v8a.apk")
    }

    @Test
    fun beginUpdateFromTheRowShowsTheDownloadingAlertWhenNoneIsUp() = runTest {
        val alerts = AlertHostState()
        val installer = FakeInstaller(canInstall = true)
        // Up to date on the launch check, then a manual check finds the
        // release: no prompt, the row is the entry.
        var json = """{"version": "0.9.2"}"""
        val feed = object : ManifestSource {
            override suspend fun fetch(): String? = json
        }
        val center = center(feed = feed, installer = installer, alerts = alerts)
        center.onForeground(true)
        center.start()
        runCurrent()
        assertNull(alerts.current)

        json = manifestJson
        center.checkNow()
        runCurrent()
        // Manual discovery never prompts.
        assertNull(alerts.current)

        center.beginUpdate()
        runCurrent()
        assertEquals("Updating Roboco", alerts.current!!.alert.title)
        assertTrue(installer.installed.size == 1)
    }

    @Test
    fun aChecksumMismatchRefusesAndCleansUp() = runTest {
        val alerts = AlertHostState()
        val wrong = manifestJson.replace(sha256Hex(apkBytes), "0000")
        val apkSource = FakeApkSource()
        val verifier = FakeVerifier(ApkVerifier.Result.Ok)
        val center = center(
            feed = FakeFeed(wrong),
            apkSource = apkSource,
            verifier = verifier,
            installer = FakeInstaller(canInstall = true),
            alerts = alerts,
        )
        center.onForeground(true)
        center.start()
        runCurrent()
        center.beginUpdate()
        runCurrent()

        val alert = alerts.current!!.alert
        assertEquals("Update Failed", alert.title)
        assertEquals(
            "The download didn't match its published checksum, so it was discarded. Try again.",
            alert.message,
        )
        // Nothing reached the signer or the installer.
        assertNull(verifier.verified)
        val dir = File(context.cacheDir, "updates")
        assertEquals(emptyList<File>(), dir.listFiles()?.toList().orEmpty())
    }

    @Test
    fun aForeignSignerRefusesAndCleansUp() = runTest {
        val alerts = AlertHostState()
        val verifier = FakeVerifier(ApkVerifier.Result.ForeignSigner)
        val installer = FakeInstaller(canInstall = true)
        val center = center(verifier = verifier, installer = installer, alerts = alerts)
        center.onForeground(true)
        center.start()
        runCurrent()
        center.beginUpdate()
        runCurrent()

        assertEquals("Update Failed", alerts.current!!.alert.title)
        assertEquals(
            "The update is signed by a different key than this install, so it can't replace it. " +
                "Install it from the releases page instead.",
            alerts.current!!.alert.message,
        )
        assertTrue("the signer gate ran", verifier.verified != null)
        assertTrue("nothing was installed", installer.installed.isEmpty())
        val dir = File(context.cacheDir, "updates")
        assertEquals(emptyList<File>(), dir.listFiles()?.toList().orEmpty())
    }

    @Test
    fun aVerifiedCachedApkIsReusedWithoutAnotherDownload() = runTest {
        val alerts = AlertHostState()
        val apkSource = FakeApkSource()
        val installer = FakeInstaller(canInstall = true)
        val center = center(apkSource = apkSource, installer = installer, alerts = alerts)
        center.onForeground(true)
        center.start()
        runCurrent()
        center.beginUpdate()
        runCurrent()
        assertEquals(1, apkSource.opens)
        assertEquals(1, installer.installed.size)

        // The hand-off faded the first download alert out (no host is
        // composed here, so the fade's completion is driven by hand).
        alerts.removed(alerts.current!!.id)

        // A second Update re-uses the cached, still-verified copy.
        center.beginUpdate()
        runCurrent()
        assertEquals("still one download", 1, apkSource.opens)
        assertEquals(2, installer.installed.size)
    }

    // --------------------------------------------- the permission ask (C)

    @Test
    fun withoutInstallPermissionTheAskAppearsAndResumesOnReturn() = runTest {
        val alerts = AlertHostState()
        val installer = FakeInstaller(canInstall = false)
        val center = center(installer = installer, alerts = alerts)
        center.onForeground(true)
        center.start()
        runCurrent()
        center.beginUpdate()
        runCurrent()

        // Panel C: the ask, in place of the install.
        val alert = alerts.current!!.alert
        assertEquals("Allow Roboco to Install Updates", alert.title)
        assertEquals(
            "Android asks once. Turn on \"Allow from this source\" for Roboco, then come back.",
            alert.message,
        )
        assertEquals("Open Settings", alert.buttons[0].title)
        assertTrue(alert.buttons[0].preferred)
        assertEquals("Not Now", alert.buttons[1].title)
        assertTrue("nothing installed yet", installer.installed.isEmpty())

        // "Open Settings" walks the user to the system screen.
        alert.buttons[0].onClick("")
        assertEquals(1, installer.openedSettings.size)

        // They come back and grant it: the install resumes.
        installer.canInstall = true
        center.onForeground(true)
        runCurrent()
        assertEquals(1, installer.installed.size)
    }

    // ------------------------------------------ the install status path

    @Test
    fun theStatusBroadcastDrivesTheConfirmIntentAndFailures() = runTest {
        val alerts = AlertHostState()
        val installer = FakeInstaller(canInstall = true)
        val center = center(installer = installer, alerts = alerts)
        center.onForeground(true)
        center.start()
        runCurrent()
        center.beginUpdate()
        runCurrent()
        val sessionId = 7
        val staged = installer.installed[0]

        // STATUS_PENDING_USER_ACTION: the confirm intent is started.
        val confirm = Intent(Intent.ACTION_INSTALL_PACKAGE)
        center.onInstallStatus(
            Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, confirm),
        )
        val started = shadowOf(context).nextStartedActivity
        assertNotNull("the confirm intent was started", started)
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, started!!.action)

        // STATUS_SUCCESS: the staged APK is cleaned up. (No host is
        // composed here, so the hand-off's fade-out completes by hand.)
        alerts.removed(alerts.current!!.id)
        center.onInstallStatus(
            Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS),
        )
        assertFalse(staged.isFile)

        // A failure status: the failure alert with the system's message.
        center.onInstallStatus(
            Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_BLOCKED)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "blocked by policy"),
        )
        assertEquals("Update Failed", alerts.current!!.alert.title)
        assertEquals("Roboco couldn't be updated. blocked by policy", alerts.current!!.alert.message)
    }

    // --------------------------------------------------- non-release (F)

    @Test
    fun nonReleaseBuildsOpenTheReleasesPageInsteadOfInstalling() = runTest {
        val alerts = AlertHostState()
        var opened = 0
        val installer = FakeInstaller(canInstall = true)
        val center = center(
            installEnabled = false,
            installer = installer,
            alerts = alerts,
            openReleases = { opened++ },
        )
        center.onForeground(true)
        center.start()
        runCurrent()
        // The check ran and found the release (the About row shows it).
        assertTrue(center.state.value is UpdateUiState.Available)
        assertNull("no prompt on a non-release build", alerts.current)

        center.beginUpdate()
        runCurrent()
        assertEquals(1, opened)
        assertTrue("never installs", installer.installed.isEmpty())
    }

    @Test
    fun anUnknownAbiOpensTheReleasesPage() = runTest {
        val installer = FakeInstaller(canInstall = true)
        var opened = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val center = UpdateCenter(
            context = context,
            scope = backgroundScope,
            alerts = AlertHostState(),
            feed = FakeFeed(manifestJson),
            checker = UpdateChecker(FakeFeed(manifestJson), "0.9.2"),
            io = dispatcher,
            initialDelayMs = 0,
            deviceAbi = { "riscv64" },
            openReleases = { opened++ },
        )
        center.onForeground(true)
        center.start()
        runCurrent()
        assertTrue(center.state.value is UpdateUiState.Available)

        center.beginUpdate()
        runCurrent()
        assertEquals(1, opened)
        assertTrue(installer.installed.isEmpty())
    }
}
