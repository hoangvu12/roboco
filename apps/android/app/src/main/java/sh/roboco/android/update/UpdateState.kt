package sh.roboco.android.update

import androidx.compose.runtime.Immutable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Settings → About rows (and the launch prompt's owners) read
 * (android-next#04): the outcome of the newest check, plus the moment it
 * ran. [Downloading]/install states live in the alert only — the row
 * keeps showing the release while the alert carries the progress.
 */
@Immutable
sealed interface UpdateUiState {

    /** No check has completed in this process yet. */
    data object Idle : UpdateUiState

    /** A check is running (manual or scheduled). */
    data object Checking : UpdateUiState

    /** The newest check found no newer release. */
    data class UpToDate(val checkedAtMs: Long) : UpdateUiState

    /** The newest check failed; the row shows the reason. */
    data class Failed(val reason: UpdateCheckReason, val checkedAtMs: Long?) : UpdateUiState

    /** A strictly newer release exists. */
    data class Available(val manifest: ReleaseManifest, val checkedAtMs: Long) : UpdateUiState
}

/**
 * The "checked <n> ago" bucket (panel E's subtitle suffix): `just now`
 * under a minute, minutes under an hour, hours under a day, `yesterday`
 * beyond — the row's formatter maps the bucket to the localized label.
 */
sealed interface CheckedAgo {
    data object Now : CheckedAgo
    data class Minutes(val n: Long) : CheckedAgo
    data class Hours(val n: Long) : CheckedAgo
    data object Yesterday : CheckedAgo
}

/** Buckets [checkedAtMs] against [nowMs] (wall clock, both in ms). */
fun checkedAgo(checkedAtMs: Long, nowMs: Long): CheckedAgo {
    val age = (nowMs - checkedAtMs).coerceAtLeast(0)
    return when {
        age < 60_000 -> CheckedAgo.Now
        age < 60 * 60_000 -> CheckedAgo.Minutes(age / 60_000)
        age < 24 * 60 * 60_000 -> CheckedAgo.Hours(age / (60 * 60_000))
        else -> CheckedAgo.Yesterday
    }
}

/**
 * `<n>` — the MB number alone (one decimal, a whole number keeps no
 * trailing `.0`); the resource patterns append the unit (the row's
 * "24.5 MB", the progress label's "10.3 of 24.5 MB").
 */
fun mbNumber(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return String.format(Locale.ROOT, "%.1f", mb).removeSuffix(".0")
}

/**
 * `Oct 20` — the manifest's ISO-8601 date as an English month and day
 * (panel D's "Released Oct 20"). Null for a missing or unparseable date
 * (old manifests); the row's subtitle then keeps the size alone.
 */
fun releaseDateLabel(isoDate: String?): String? {
    if (isoDate.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(isoDate).format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }
}

/** The SHA-256 of [bytes] as lowercase hex. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

/** The SHA-256 of [file]'s contents as lowercase hex (streamed). */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    file.inputStream().use { stream ->
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (n > 0) digest.update(buffer, 0, n)
        }
    }
    return digest.digest().toHex()
}

/**
 * The digest gate for a downloaded APK: the manifest's expected hex must
 * be present and must match (case-insensitively — the workflow writes
 * lowercase). A missing entry refuses, per the ticket ("refuse on
 * mismatch **or missing entry**").
 */
fun sha256Matches(actual: String?, expected: String?): Boolean {
    if (actual == null || expected == null) return false
    return actual.equals(expected, ignoreCase = true)
}

/** The download cache directory: `<cacheDir>/updates/`. */
internal fun updatesCacheDir(cacheRoot: File): File = File(cacheRoot, "updates")

// ---------------------------------------------------------------------------
// The launch-prompt policy
// ---------------------------------------------------------------------------

/**
 * "Later" once per launch (the ticket's behaviour 2): the launch prompt
 * appears at most **once per process**, "Later" (or any abandonment)
 * silences it for the rest of the process, and a fresh process starts
 * fresh — a new instance prompts again. Non-release builds never prompt
 * (their Update action links to the releases page instead).
 */
class LaunchPromptState(
    /** The `BuildConfig` flag: false for debug and benchmark builds. */
    val installEnabled: Boolean,
) {
    /** The prompt was shown in this process. */
    var shown: Boolean = false
        private set

    /** The user answered "Later" in this process. */
    var declined: Boolean = false
        private set

    /** Whether the launch prompt may present now. */
    fun shouldPrompt(updateAvailable: Boolean): Boolean =
        installEnabled && updateAvailable && !shown && !declined

    /** The prompt presented (recorded before the handler runs). */
    fun onShown() {
        shown = true
    }

    /** The user answered "Later". */
    fun onDeclined() {
        declined = true
    }
}
