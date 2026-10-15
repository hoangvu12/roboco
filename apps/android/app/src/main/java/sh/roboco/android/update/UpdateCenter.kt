package sh.roboco.android.update

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.BuildConfig
import sh.roboco.android.R
import sh.roboco.android.composer.openInCustomTab
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.AlertProgress
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoAlert

/**
 * The app-wide update service (android-next#04): the scheduled checks
 * (launch, hourly while foreground, backoff on failure), the launch
 * prompt (once per process, deferred until the shell is idle), the
 * download → verify → sign-check → PackageInstaller flow behind the
 * approved alert panels A–C, and the Settings → About row's state.
 *
 * Everything the user sees goes through the app's own [Alerts] host and
 * the About rows ([sh.roboco.android.settings.rememberAboutSectionState]
 * reads [state]); this class never composes. All state writes happen on
 * the caller's [scope] (the app's Main.immediate lane); the network,
 * file and PackageManager work hops to [io]. Nothing is logged — the
 * feed URL carries no secrets, but there is also nothing worth a log
 * line here (errors surface in the row and the alerts).
 *
 * Non-release builds ([installEnabled] false — debug and benchmark):
 * they still check and show availability, but their Update action opens
 * the releases page instead of installing, and the launch prompt is off
 * (their signer differs from the release key, so an install-over would
 * fail — the ticket's behaviour 6, made explicit in the constructor's
 * default).
 */
class UpdateCenter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val alerts: Alerts,
    private val feed: ManifestSource = HttpManifestSource(),
    private val apkSource: ApkByteSource = HttpApkByteSource(),
    private val verifier: ApkVerifier = PackageArchiveVerifier(
        context.packageManager,
        context.packageName,
    ),
    private val installer: ApkInstaller = PackageInstallerApkInstaller(context),
    private val checker: UpdateChecker = UpdateChecker(feed, BuildConfig.VERSION_NAME),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The first check's delay (the desktop's 2 s post-first-frame). */
    private val initialDelayMs: Long = UpdateSchedule.INITIAL_DELAY_MS,
    /** `BuildConfig.UPDATE_INSTALLER_ENABLED` — release builds only. */
    private val installEnabled: Boolean = BuildConfig.UPDATE_INSTALLER_ENABLED,
    /** The running app's version (for the prompt's "You have …"). */
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    /** The paired engines' versions, for the nudge line (workspace feed). */
    private val engineVersions: () -> List<String?> = { emptyList() },
    /** The shell is idle: past pairing/onboarding, a root is on screen. */
    private val rootReady: () -> Boolean = { false },
    /** The device's primary ABI (`Build.SUPPORTED_ABIS[0]`). */
    private val deviceAbi: () -> String = { Build.SUPPORTED_ABIS.firstOrNull() ?: "" },
    /** Opens the releases page (a Custom Tab in production). */
    private val openReleases: () -> Unit = { openInCustomTab(context, RELEASES_PAGE, dark = false) },
) {

    private val downloader = ApkDownloader(io)

    /** The newest check's outcome — the About rows read this. */
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** The launch prompt's once-per-process policy. */
    private val prompt = LaunchPromptState(installEnabled)

    /** True while the process is foregrounded (drives the hourly checks). */
    private val foregroundGate = MutableStateFlow(false)

    /** The showing alert is one of ours, morphable in place (read from
     * the download lane's progress callback too, so volatile). */
    @Volatile
    private var morphOwned = false

    /** The download job (null when idle). */
    private var downloadJob: Job? = null

    /** The verified APK staged for install (the cache file). */
    private var staged: File? = null

    /** The PackageInstaller session we are waiting on, if any. */
    private var activeSessionId: Int? = null

    /** A check is running (guard against stacked checks). */
    private var checking = false

    private var started = false
    private var promptJob: Job? = null

    // ------------------------------------------------------------------
    // The schedule
    // ------------------------------------------------------------------

    /**
     * Arms the loop: the launch check after [initialDelayMs], then hourly
     * after successes and the desktop's backoff after failures, paused
     * while the process is backgrounded. Idempotent; called once from
     * `RobocoApplication.onCreate`.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch { sweepCache() }
        scope.launch {
            delay(initialDelayMs)
            var failures = 0
            while (isActive) {
                if (!foregroundGate.value) foregroundGate.first { it }
                when (val result = runCheck(manual = false)) {
                    // A check was already running (a manual one): a check
                    // just happened either way, so the next scheduled one
                    // stays an hour out — and the loop always suspends
                    // here, never spins on the main lane.
                    null -> delay(UpdateSchedule.CHECK_INTERVAL_MS)
                    is UpdateCheckResult.Unavailable -> {
                        failures += 1
                        delay(UpdateSchedule.retryDelayMs(failures))
                    }
                    else -> delay(UpdateSchedule.CHECK_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * The process crossed the foreground edge: the loop gates on this,
     * and a staged-but-blocked install resumes when the user comes back
     * from granting "install unknown apps".
     */
    fun onForeground(foreground: Boolean) {
        foregroundGate.value = foreground
        if (foreground && staged != null && activeSessionId == null && downloadJob == null) {
            scope.launch {
                if (installer.canInstall()) commitStaged()
            }
        }
    }

    /** The Settings → About "Check for Updates" row. */
    fun checkNow() {
        scope.launch { runCheck(manual = true) }
    }

    // ------------------------------------------------------------------
    // The checks
    // ------------------------------------------------------------------

    /** `null` when a check was already running (its result lands anyway). */
    private suspend fun runCheck(manual: Boolean): UpdateCheckResult? {
        // A check already in flight owns the state (the loop and the row
        // can race); its result lands in the state either way.
        if (checking) return null
        checking = true
        try {
            _state.value = UpdateUiState.Checking
            val result = checker.check()
            val now = clock()
            when (result) {
                is UpdateCheckResult.Available -> {
                    _state.value = UpdateUiState.Available(result.manifest, now)
                    // Only the scheduled checks prompt; a manual check's
                    // discovery is the user already looking at About.
                    if (!manual) armPrompt()
                }
                UpdateCheckResult.UpToDate -> _state.value = UpdateUiState.UpToDate(now)
                is UpdateCheckResult.Unavailable -> _state.value = UpdateUiState.Failed(result.reason, now)
            }
            return result
        } finally {
            checking = false
        }
    }

    /**
     * Arms the deferred launch prompt: never over pairing/onboarding and
     * never over another alert — it waits for a ready root and a quiet
     * alert host, then presents once.
     */
    private fun armPrompt() {
        if (!prompt.shouldPrompt(state.value is UpdateUiState.Available)) return
        promptJob?.cancel()
        promptJob = scope.launch {
            while (isActive) {
                if (rootReady() && !alerts.isShowing) {
                    presentLaunchPrompt()
                    return@launch
                }
                delay(PROMPT_POLL_MS)
            }
        }
    }

    private fun presentLaunchPrompt() {
        prompt.onShown()
        val available = state.value as? UpdateUiState.Available ?: return
        val nudge = engineNeedsNudge(engineVersions(), available.manifest.version)
        val message = context.getString(
            R.string.update_alert_message,
            available.manifest.version,
            currentVersion,
        ) + (if (nudge) "\n" + context.getString(R.string.update_alert_engine_nudge) else "")
        morphOwned = true
        alerts.show(
            RobocoAlert(
                title = context.getString(R.string.update_alert_title),
                message = message,
                buttons = listOf(
                    AlertButton(
                        context.getString(R.string.update_alert_update),
                        preferred = true,
                        morphsInPlace = true,
                    ) { beginUpdate() },
                    AlertButton(
                        context.getString(R.string.update_alert_later),
                        role = AlertButtonRole.Cancel,
                    ) {
                        prompt.onDeclined()
                        morphOwned = false
                    },
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // The update flow (panels B and C)
    // ------------------------------------------------------------------

    /**
     * The Update action (the alert's morph button or the About row):
     * installs on release builds, opens the releases page otherwise, and
     * also falls back to the page when this release has no APK for the
     * device's ABI.
     */
    fun beginUpdate() {
        if (downloadJob != null) return
        if (!installEnabled) {
            openReleases()
            return
        }
        val available = state.value as? UpdateUiState.Available ?: return
        val abi = assetAbi(deviceAbi())
        if (abi == null) {
            openReleases()
            return
        }
        val asset = available.manifest.apkFor(abi)
        if (asset == null) {
            openReleases()
            return
        }
        val job = scope.launch { runUpdate(asset, available.manifest) }
        downloadJob = job
        job.invokeOnCompletion { if (downloadJob === job) downloadJob = null }
    }

    /** The download alert's Cancel button. */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    private suspend fun runUpdate(asset: ApkAsset, manifest: ReleaseManifest) {
        val dir = updatesCacheDir(context.cacheDir).apply { mkdirs() }
        val dest = File(dir, asset.name)
        val url = "$RELEASES_DOWNLOAD/${asset.name}"
        morphOrShow(downloadingAlert(manifest.version, 0f, null))
        val result: ApkDownloader.Result
        val cachedSha = if (dest.isFile) {
            withContext(io) { runCatching { sha256Hex(dest) }.getOrNull() }
        } else {
            null
        }
        if (sha256Matches(cachedSha, asset.sha256)) {
            // A cached, still-verified copy: skip the 20 MB download.
            result = ApkDownloader.Result.Done(dest, dest.length(), cachedSha!!)
        } else {
            var lastShownBytes = 0L
            var lastShownAt = 0L
            // The progress morphs hop onto the caller's (main) lane — the
            // download lane must not touch the alert state directly. One
            // pending morph at a time, replaced per tick and cancelled at
            // the flow's decision point, so a stale bar never lands after
            // the failure or hand-off alert.
            var pendingMorph: Job? = null
            result = try {
                downloader.download(url, dest, apkSource) { read, total ->
                    val now = clock()
                    if (read - lastShownBytes >= PROGRESS_MIN_BYTES || now - lastShownAt >= PROGRESS_MIN_MS) {
                        lastShownBytes = read
                        lastShownAt = now
                        pendingMorph?.cancel()
                        pendingMorph = scope.launch {
                            morphOrShow(
                                downloadingAlert(
                                    manifest.version,
                                    fractionOf(read, total ?: asset.sizeBytes),
                                    progressLabel(read, total ?: asset.sizeBytes),
                                ),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                ApkDownloader.Result.Cancelled
            }
            pendingMorph?.cancel()
            pendingMorph = null
        }
        when (result) {
            ApkDownloader.Result.Cancelled -> Unit // the handler cleaned up
            is ApkDownloader.Result.Failed -> {
                val message = when (result.failure) {
                    ApkDownloader.Failure.Network -> context.getString(R.string.update_failed_network)
                    ApkDownloader.Failure.Disk -> context.getString(R.string.update_failed_storage)
                }
                showFailure(message)
            }
            is ApkDownloader.Result.Done -> {
                if (!sha256Matches(result.sha256, asset.sha256)) {
                    dest.delete()
                    showFailure(context.getString(R.string.update_failed_checksum))
                    return
                }
                when (withContext(io) { verifier.verify(dest.absolutePath) }) {
                    ApkVerifier.Result.Ok -> handOffToInstaller(dest)
                    ApkVerifier.Result.Unreadable -> {
                        dest.delete()
                        showFailure(context.getString(R.string.update_failed_install_bare))
                    }
                    ApkVerifier.Result.ForeignSigner -> {
                        dest.delete()
                        showFailure(context.getString(R.string.update_failed_signer))
                    }
                }
            }
        }
    }

    /**
     * The verified APK's hand-off: the permission ask first if Android
     * has not granted "install unknown apps" yet (panel C, resumed when
     * the user returns), otherwise straight to the session.
     */
    private suspend fun handOffToInstaller(file: File) {
        staged = file
        if (!installer.canInstall()) {
            morphOrShow(permissionAlert())
            return
        }
        commitStaged()
    }

    private suspend fun commitStaged() {
        val file = staged ?: return
        // The download alert's job ends here: "stays in front until the
        // download is verified and handed to the installer".
        if (morphOwned) {
            alerts.dismissAll()
            morphOwned = false
        }
        activeSessionId = installer.install(file)
    }

    /** The PackageInstaller status broadcast (via [ApkInstallReceiver]). */
    fun onInstallStatus(intent: Intent) {
        when (val status = installStatusOf(intent, activeSessionId)) {
            ApkInstallStatus.Ignored -> Unit
            ApkInstallStatus.Success -> {
                activeSessionId = null
                cleanStaged()
            }
            is ApkInstallStatus.Confirm -> {
                // STATUS_PENDING_USER_ACTION: Android's own install
                // confirmation. Started from the app context.
                context.startActivity(
                    status.confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            is ApkInstallStatus.Failed -> {
                activeSessionId = null
                cleanStaged()
                val message = status.message
                val text = if (message.isNullOrBlank()) {
                    context.getString(R.string.update_failed_install_bare)
                } else {
                    context.getString(R.string.update_failed_install, message)
                }
                showFailure(text)
            }
        }
    }

    private fun cleanStaged() {
        staged?.delete()
        staged = null
    }

    // ------------------------------------------------------------------
    // The alerts
    // ------------------------------------------------------------------

    /**
     * Shows [alert] — or morphs the one we own into it, keeping the card
     * (presentation, animations) alive: the approved panels' A→B→C
     * transitions are one alert changing in place, never a second one.
     */
    private fun morphOrShow(alert: RobocoAlert) {
        if (morphOwned && alerts.isShowing) {
            alerts.updateCurrent(alert)
        } else {
            alerts.show(alert)
        }
        morphOwned = true
    }

    /** The failure alert (morphs in place; "Try Again" restarts the flow). */
    private fun showFailure(message: String) {
        morphOrShow(
            RobocoAlert(
                title = context.getString(R.string.update_failed_title),
                message = message,
                buttons = listOf(
                    AlertButton(
                        context.getString(R.string.update_failed_retry),
                        preferred = true,
                        morphsInPlace = true,
                    ) { beginUpdate() },
                    AlertButton(
                        context.getString(R.string.update_failed_not_now),
                        role = AlertButtonRole.Cancel,
                    ) { morphOwned = false },
                ),
            ),
        )
    }

    private fun downloadingAlert(
        version: String,
        fraction: Float,
        label: String?,
    ): RobocoAlert = RobocoAlert(
        title = context.getString(R.string.update_downloading_title),
        message = context.getString(R.string.update_downloading_message, version),
        progress = AlertProgress(fraction, label),
        buttons = listOf(
            AlertButton(
                context.getString(R.string.update_downloading_cancel),
                role = AlertButtonRole.Cancel,
            ) {
                cancelDownload()
                morphOwned = false
            },
        ),
    )

    private fun permissionAlert(): RobocoAlert = RobocoAlert(
        title = context.getString(R.string.update_permission_title),
        message = context.getString(R.string.update_permission_message),
        buttons = listOf(
            AlertButton(
                context.getString(R.string.update_permission_open),
                preferred = true,
            ) { installer.openUnknownSourcesSettings() },
            AlertButton(
                context.getString(R.string.update_permission_not_now),
                role = AlertButtonRole.Cancel,
            ) { morphOwned = false },
        ),
    )

    private fun progressLabel(read: Long, total: Long?): String? {
        if (total == null || total <= 0L) return null
        return context.getString(R.string.update_progress_of_mb, mbNumber(read), mbNumber(total))
    }

    private fun fractionOf(read: Long, total: Long?): Float {
        if (total == null || total <= 0L) return 0f
        return (read.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------
    // The cache
    // ------------------------------------------------------------------

    /** Drops staged APKs older than a day — installs clean up eagerly,
     * this sweep covers the paths that never got a status broadcast. */
    private suspend fun sweepCache() {
        withContext(io) {
            val dir = updatesCacheDir(context.cacheDir)
            val cutoff = clock() - STALE_CACHE_MS
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
    }

    private companion object {
        /** How often the deferred prompt re-checks the shell's idleness. */
        const val PROMPT_POLL_MS = 1_000L

        /** Progress updates: at least this many bytes… */
        const val PROGRESS_MIN_BYTES = 128L * 1024

        /** …or this much time between two alert morphs. */
        const val PROGRESS_MIN_MS = 200L

        /** A staged APK nobody installed for this long is stale. */
        const val STALE_CACHE_MS = 24L * 60 * 60 * 1000
    }
}
