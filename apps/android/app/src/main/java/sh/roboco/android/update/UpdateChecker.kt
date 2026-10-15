package sh.roboco.android.update

import java.io.IOException

/**
 * The check decision (android-next#04): one fetch, one parse, one
 * comparison. Pure over the [ManifestSource] seam — the plain-JVM tests
 * drive it with a fake; the HTTP details never reach this class.
 */
class UpdateChecker(
    private val source: ManifestSource,
    private val currentVersion: String,
) {

    /** Runs one check; never throws. */
    suspend fun check(): UpdateCheckResult {
        val text = try {
            source.fetch()
        } catch (e: IOException) {
            // Offline boot, captive portal, transient fetch error — the
            // desktop's backoff path.
            return UpdateCheckResult.Unavailable(UpdateCheckReason.OFFLINE)
        }
        if (text == null) return UpdateCheckResult.Unavailable(UpdateCheckReason.NOT_FOUND)
        val manifest = ReleaseManifest.parse(text)
            ?: return UpdateCheckResult.Unavailable(UpdateCheckReason.NO_MANIFEST)
        val candidate = SemVer.parse(manifest.version)
            ?: return UpdateCheckResult.Unavailable(UpdateCheckReason.NO_MANIFEST)
        val current = SemVer.parse(currentVersion)
            // An unparseable own version never blocks: like the desktop's
            // lenient parse, it reads as up to date, never as "update".
            ?: return UpdateCheckResult.UpToDate
        return if (candidate > current) {
            UpdateCheckResult.Available(manifest)
        } else {
            UpdateCheckResult.UpToDate
        }
    }
}

/** Why a check could not decide — short words for the row's subtitle. */
enum class UpdateCheckReason(val wire: String) {
    /** Network failure (offline, captive portal, timeout). */
    OFFLINE("offline"),

    /** The manifest does not exist (no releases published yet). */
    NOT_FOUND("not found"),

    /** The manifest exists but does not parse, or its version is not a version. */
    NO_MANIFEST("no manifest"),
}

/** One check's outcome. */
sealed interface UpdateCheckResult {

    /** A strictly newer release exists. */
    data class Available(val manifest: ReleaseManifest) : UpdateCheckResult

    /** The current version is the newest (equal or newer). */
    data object UpToDate : UpdateCheckResult

    /** The check failed; retry later with backoff. */
    data class Unavailable(val reason: UpdateCheckReason) : UpdateCheckResult
}

/**
 * The engine-version nudge (the ticket's behaviour 7): one paired engine
 * reporting a version **older than the new release** adds the "Update
 * Roboco on your computer too." line. Engines without a version never
 * nudge, and neither does a release the engines already run.
 */
fun engineNeedsNudge(engineVersions: List<String?>, release: String): Boolean {
    val releaseVersion = SemVer.parse(release) ?: return false
    return engineVersions
        .mapNotNull { SemVer.parse(it) }
        .any { it < releaseVersion }
}
