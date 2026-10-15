package sh.roboco.android.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import java.util.Locale
import sh.roboco.android.R
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.settings.AboutRowIds
import sh.roboco.android.settings.AboutRowSpec

/**
 * The Settings → About update row (android-next#04, panels D/E/F): the
 * row spec per [UpdateUiState] — the accent "Update to Roboco <ver>" row
 * (disclosure, size/date subtitle) while an update is known, the GitHub
 * row for non-release builds, and the "Check for Updates" row with its
 * four subtitle states. Pure over injected strings ([UpdateRowStrings])
 * and [deviceAbi] (the row shows the size of the APK this device would
 * download), so the plain-JVM tests drive it with literals.
 */
fun updateAboutRow(
    state: UpdateUiState,
    strings: UpdateRowStrings,
    installEnabled: Boolean,
    nowMs: Long,
    deviceAbi: String,
): AboutRowSpec? = when (state) {
    is UpdateUiState.Available -> {
        if (installEnabled) {
            AboutRowSpec(
                id = AboutRowIds.Update,
                title = strings.updateTitle(state.manifest.version),
                subtitle = updateSubtitle(state.manifest, strings, deviceAbi),
                icon = RobocoIcons.ArrowDown,
                accent = true,
                accessories = listOf(GroupedAccessory.Disclosure),
            )
        } else {
            // Panel F: the release APK can't install over this build
            // (different key); the row links to the releases page.
            AboutRowSpec(
                id = AboutRowIds.Update,
                title = strings.githubTitle(state.manifest.version),
                subtitle = strings.githubSubtitle,
                icon = RobocoIcons.ArrowUpRight,
                accent = true,
                accessories = listOf(GroupedAccessory.Disclosure),
            )
        }
    }

    UpdateUiState.Idle -> checkRow(strings, null)
    UpdateUiState.Checking -> checkRow(strings, strings.checking)
    is UpdateUiState.UpToDate -> checkRow(
        strings,
        if (state.checkedAtMs > 0) {
            strings.upToDateAt(strings.relative(checkedAgo(state.checkedAtMs, nowMs)))
        } else {
            // No known check time: the plain verdict (panel E's note).
            strings.upToDate
        },
    )
    is UpdateUiState.Failed -> checkRow(strings, strings.error(strings.reason(state.reason)))
}

/**
 * "<size> MB · Released <date>", each part optional (old manifests carry
 * neither; the ABI's asset decides the size).
 */
private fun updateSubtitle(
    manifest: ReleaseManifest,
    strings: UpdateRowStrings,
    deviceAbi: String,
): String? {
    val abi = assetAbi(deviceAbi)
    // mbNumber, not sizeMb — the resource's patterns carry their own "MB".
    val size = abi?.let(manifest::apkFor)?.sizeBytes?.let(::mbNumber)
    val date = releaseDateLabel(manifest.date)
    return when {
        size != null && date != null -> strings.sizeDate(size, date)
        size != null -> strings.sizeOnly(size)
        date != null -> strings.dateOnly(date)
        else -> null
    }
}

private fun checkRow(strings: UpdateRowStrings, subtitle: String?): AboutRowSpec = AboutRowSpec(
    id = AboutRowIds.Check,
    title = strings.checkTitle,
    subtitle = subtitle,
    icon = RobocoIcons.Refresh,
    accent = false,
    accessories = emptyList(),
)

/**
 * The row's resolved copy (android-next#04): formatters over the
 * resource's format patterns, not fixed strings, because the titles and
 * subtitles interpolate the release's version, size, date, reason and
 * relative age. [updateRowStrings] resolves every pattern eagerly inside
 * composition; the lambdas only format.
 */
@Immutable
class UpdateRowStrings(
    val updateTitle: (version: String) -> String,
    val sizeDate: (mb: String, date: String) -> String,
    val sizeOnly: (mb: String) -> String,
    val dateOnly: (date: String) -> String,
    val checkTitle: String,
    val checking: String,
    val upToDate: String,
    val upToDateAt: (relative: String) -> String,
    val error: (reason: String) -> String,
    val reason: (UpdateCheckReason) -> String,
    val githubTitle: (version: String) -> String,
    val githubSubtitle: String,
    val relative: (CheckedAgo) -> String,
)

/**
 * Resolves [UpdateRowStrings] from the resources. Every literal is the
 * approved mockup's copy (panels D/E/F). The format patterns are
 * resolved eagerly in composition and remembered on their values, so
 * the instance (and the row specs built from it) stay stable across
 * recompositions; the class's lambdas only format.
 */
@Composable
fun updateRowStrings(): UpdateRowStrings {
    val updateTitlePattern = stringResource(R.string.update_row_update_title)
    val sizeDatePattern = stringResource(R.string.update_row_size_date)
    val sizePattern = stringResource(R.string.update_row_size)
    val datePattern = stringResource(R.string.update_row_released)
    val upToDateAtPattern = stringResource(R.string.update_row_up_to_date_at)
    val errorPattern = stringResource(R.string.update_row_error)
    val githubTitlePattern = stringResource(R.string.update_row_github_title)
    val minutesAgoPattern = stringResource(R.string.update_checked_minutes_ago)
    val hoursAgoPattern = stringResource(R.string.update_checked_hours_ago)
    val offline = stringResource(R.string.update_reason_offline)
    val notFound = stringResource(R.string.update_reason_not_found)
    val noManifest = stringResource(R.string.update_reason_no_manifest)
    val justNow = stringResource(R.string.update_checked_just_now)
    val yesterday = stringResource(R.string.update_checked_yesterday)
    val checkTitle = stringResource(R.string.update_row_check_title)
    val checking = stringResource(R.string.update_row_checking)
    val upToDate = stringResource(R.string.update_row_up_to_date)
    val githubSubtitle = stringResource(R.string.update_row_github_subtitle)

    fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.ROOT, pattern, *args)

    return remember(
        updateTitlePattern,
        sizeDatePattern,
        sizePattern,
        datePattern,
        upToDateAtPattern,
        errorPattern,
        githubTitlePattern,
        minutesAgoPattern,
        hoursAgoPattern,
        offline,
        notFound,
        noManifest,
        justNow,
        yesterday,
        checkTitle,
        checking,
        upToDate,
        githubSubtitle,
    ) {
        UpdateRowStrings(
            updateTitle = { version -> fmt(updateTitlePattern, version) },
            sizeDate = { mb, date -> fmt(sizeDatePattern, mb, date) },
            sizeOnly = { mb -> fmt(sizePattern, mb) },
            dateOnly = { date -> fmt(datePattern, date) },
            checkTitle = checkTitle,
            checking = checking,
            upToDate = upToDate,
            upToDateAt = { relative -> fmt(upToDateAtPattern, relative) },
            error = { reason -> fmt(errorPattern, reason) },
            reason = { reason ->
                when (reason) {
                    UpdateCheckReason.OFFLINE -> offline
                    UpdateCheckReason.NOT_FOUND -> notFound
                    UpdateCheckReason.NO_MANIFEST -> noManifest
                }
            },
            githubTitle = { version -> fmt(githubTitlePattern, version) },
            githubSubtitle = githubSubtitle,
            relative = { ago ->
                when (ago) {
                    CheckedAgo.Now -> justNow
                    is CheckedAgo.Minutes -> fmt(minutesAgoPattern, ago.n)
                    is CheckedAgo.Hours -> fmt(hoursAgoPattern, ago.n)
                    CheckedAgo.Yesterday -> yesterday
                }
            },
        )
    }
}
