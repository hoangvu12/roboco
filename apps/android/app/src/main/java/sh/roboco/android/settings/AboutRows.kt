package sh.roboco.android.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sh.roboco.android.BuildConfig
import sh.roboco.android.RobocoApplication
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.core.Face
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.settingsTag
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.update.UpdateCenter
import sh.roboco.android.update.UpdateUiState
import sh.roboco.android.update.updateAboutRow
import sh.roboco.android.update.updateRowStrings

/**
 * The Settings "About" section (android-ui-parity #101): the last section
 * of Settings, in iOS's Sign-Out slot (`MoreViewController.swift:217-218`
 * — Roboco has no account, ADR 0004, so the row itself is dropped). Two
 * informational rows: **Version** (app version, version code, core
 * version; long-press copies the line) and **Licenses** (pushes the
 * Licenses screen).
 *
 * Everything visual is ticket 70's `GroupedRow`; this file only feeds it
 * data and wires the copy. Section order in Settings belongs to ticket
 * 103 — Settings calls [rememberAboutSectionState] at the top of its
 * composable and [aboutSection] as its **last** section; until 103 lands
 * the design gallery is the only caller.
 */

/** The row ids, `cell.accessibilityIdentifier = "settings-\(row.id)"` (70). */
object AboutRowIds {
    const val Version = "about:version"
    const val Licenses = "about:licenses"

    /** android-next#04: the Update/Check rows (same `about:` prefix —
     * [settingsSectionFor] maps the whole prefix to the About section). */
    const val Update = "about:update"
    const val Check = "about:check"
}

/** The section header — "About" (android-app#26), not iOS's blank `" "`. */
const val AboutSectionTitle = "About"

/** The ClipData label of the Version row's copy (invisible). */
private const val ClipLabel = "Version"

/** The toast's copy on API 31–32 only (ticket 80's convention). */
private const val CopiedToast = "Copied"

/** The long-press action's TalkBack label. */
const val CopyActionLabel = "Copy"

/** The core part of the subtitle when the core failed to load. */
private const val CoreUnavailable = "unavailable"

/** The licence row's subtitle. */
const val ThirdPartyNotices = "Third-party notices"

/**
 * One About row, pure data: the [GroupedRow] inputs the section renders.
 * The icons are `SF info.circle`/`doc.text` → `RobocoIcons.InfoCircle` /
 * [RobocoIcons.DocText]; the Version row shows no accessory, the
 * Licenses row the disclosure chevron (the iOS `Row` default).
 * [accent] (android-next#04) draws the title and icon in `Palette.accent`
 * — the "Update to Roboco <ver>" row (panel D) and its GitHub twin (F);
 * the renderer maps it, the spec stays pure.
 */
@Immutable
data class AboutRowSpec(
    val id: String,
    val title: String,
    val subtitle: String?,
    val icon: RobocoIcon,
    val accessories: List<GroupedAccessory>,
    val accent: Boolean = false,
)

/**
 * `"Roboco 0.9.2 (9002) · core 0.9.2"` — the Version row's subtitle,
 * exactly. The separator is space, U+00B7 MIDDLE DOT, space. `core` is
 * the cached [CoreStatus.Loaded] version, or the literal `unavailable`
 * when the status is [CoreStatus.Failed] (never a second FFI call: #609
 * called `coreVersion()` in composition, which is both an FFI hop on the
 * main thread and not our copy to make). [buildMarker] (android-next#04,
 * panel F) slots the build type in for non-release builds —
 * `"Roboco 0.9.2 (9002) debug · core 0.9.2"`.
 */
fun versionSubtitle(
    versionName: String,
    versionCode: Long,
    core: CoreStatus,
    buildMarker: String? = null,
): String {
    val coreVersion = when (core) {
        is CoreStatus.Loaded -> core.version
        is CoreStatus.Failed -> CoreUnavailable
    }
    val own = buildString {
        append("Roboco ").append(versionName).append(" (").append(versionCode).append(")")
        if (buildMarker != null) append(' ').append(buildMarker)
    }
    return "$own · core $coreVersion"
}

/**
 * Pure: the About rows, in order — Version, the optional update row
 * (android-next#04, between Version and Licenses as in the approved
 * panel D), Licenses.
 */
fun aboutRows(subtitle: String, updateRow: AboutRowSpec? = null): List<AboutRowSpec> =
    buildList {
        add(
            AboutRowSpec(
                id = AboutRowIds.Version,
                title = "Version",
                subtitle = subtitle,
                icon = RobocoIcons.InfoCircle,
                accessories = emptyList(),
            ),
        )
        updateRow?.let(::add)
        add(
            AboutRowSpec(
                id = AboutRowIds.Licenses,
                title = "Licenses",
                subtitle = ThirdPartyNotices,
                icon = RobocoIcons.DocText,
                accessories = listOf(GroupedAccessory.Disclosure),
            ),
        )
    }

/**
 * The About section's behaviour: the click/long-click dispatch over
 * [rows]. Tapping Licenses pushes [LicensesRoute] (animated); tapping
 * the update row starts the update, tapping the check row checks
 * (android-next#04); tapping Version does nothing; long-pressing
 * Version copies its subtitle.
 */
@Stable
class AboutSectionState internal constructor(
    val rows: List<AboutRowSpec>,
    private val openLicenses: () -> Unit,
    private val copy: (String) -> Unit,
    private val onUpdate: () -> Unit = {},
    private val onCheck: () -> Unit = {},
) {
    /** Licenses → [openLicenses]; Update → [onUpdate]; Check → [onCheck]; Version → nothing. */
    fun onRowClick(id: String) {
        when (id) {
            AboutRowIds.Licenses -> openLicenses()
            AboutRowIds.Update -> onUpdate()
            AboutRowIds.Check -> onCheck()
        }
    }

    /** Version → `copy(subtitle)`; others → nothing. */
    fun onRowLongClick(id: String) {
        if (id == AboutRowIds.Version) {
            rows.firstOrNull { it.id == id }?.subtitle?.let(copy)
        }
    }
}

/**
 * Copies [text] to the clipboard with the ticket's feedback rules: the
 * [Haptic.LongPress] haptic fires once (ticket 05 — Roboco-only, iOS has
 * no such row), `setPrimaryClip` runs on the calling (main) thread and is
 * not marked sensitive, the "Copied" toast shows **only on API 31–32**
 * (the system's own clipboard confirmation covers 33+; ticket 80's rule),
 * and a [RuntimeException] from the clipboard is logged and swallowed.
 *
 * @return false when the copy failed (the string is tiny, so this does
 *   not happen in practice)
 */
internal fun copyToClipboard(
    context: Context,
    toaster: Toaster,
    feedback: Feedback,
    sdkInt: Int,
    text: String,
    label: String = ClipLabel,
): Boolean {
    feedback.haptic(Haptic.LongPress)
    return try {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (sdkInt <= Build.VERSION_CODES.S_V2) toaster.show(CopiedToast)
        true
    } catch (e: RuntimeException) {
        Log.w("Roboco", "copy version failed: $e")
        false
    }
}

/**
 * Remembers the About section state. Call at **screen** level (ticket
 * 103), never inside a `LazyColumn` item.
 *
 * Defaults come from the build (`BuildConfig.VERSION_NAME` /
 * `VERSION_CODE`) and the app's cached [RobocoApplication.core] — both
 * equal `PackageInfo` / `coreVersion()` without a binder or FFI call.
 * Previews and tests pass [core] (and, when needed, `sdkInt`) explicitly.
 *
 * android-next#04: the middle row comes from the app's
 * [RobocoApplication.updates] [UpdateCenter.state] — the update row when
 * an update is known, the Check row otherwise — with its clicks wired to
 * [UpdateCenter.beginUpdate] / [UpdateCenter.checkNow]. Pass [updates]
 * (or a null [updateState] with own callbacks) to inject fakes; a
 * context without the app (previews) shows the idle Check row. The
 * Version subtitle carries the build type on non-release builds (panel F).
 */
@Composable
fun rememberAboutSectionState(
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    core: CoreStatus = (LocalContext.current.applicationContext as RobocoApplication).core,
    sdkInt: Int = Build.VERSION.SDK_INT,
    updateState: StateFlow<UpdateUiState>? = null,
    onUpdate: (() -> Unit)? = null,
    onCheck: (() -> Unit)? = null,
    deviceAbi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "",
): AboutSectionState {
    val nav = LocalNavStack.current
    val toaster = LocalToaster.current
    val feedback = LocalFeedback.current
    val context = LocalContext.current
    val app = context.applicationContext as? RobocoApplication
    val center = app?.updates
    val state: StateFlow<UpdateUiState> = updateState ?: center?.state ?: remember {
        MutableStateFlow(UpdateUiState.Idle)
    }
    val update by state.collectAsState()
    val updateStrings = updateRowStrings()
    val now = remember { System.currentTimeMillis() }
    return remember(
        versionName,
        versionCode,
        core,
        nav,
        toaster,
        feedback,
        sdkInt,
        update,
        updateStrings,
        center,
    ) {
        AboutSectionState(
            rows = aboutRows(
                versionSubtitle(
                    versionName,
                    versionCode,
                    core,
                    buildMarker = if (BuildConfig.UPDATE_INSTALLER_ENABLED) null else BuildConfig.BUILD_TYPE,
                ),
                updateAboutRow(
                    update,
                    updateStrings,
                    installEnabled = BuildConfig.UPDATE_INSTALLER_ENABLED,
                    nowMs = now,
                    deviceAbi = deviceAbi,
                ),
            ),
            openLicenses = { nav.push(LicensesRoute) },
            copy = { text ->
                copyToClipboard(context, toaster, feedback, sdkInt, text)
            },
            onUpdate = onUpdate ?: center?.let { it::beginUpdate } ?: {},
            onCheck = onCheck ?: center?.let { it::checkNow } ?: {},
        )
    }
}

/**
 * Emits the About section — the header plus one card of all rows — as
 * the last section of a Settings-like `LazyListScope`. No
 * `Modifier.animateItem()`: iOS reloads with `animatingDifferences:
 * false`. The Version row is long-clickable ("Copy") but not clickable;
 * the Licenses row is a plain disclosure row; the update/check row
 * (android-next#04) is clickable, its accent mapped to `Palette.accent`
 * for both the icon and the title (panel D/F).
 */
fun LazyListScope.aboutSection(state: AboutSectionState) {
    groupedSection(
        key = "about",
        header = AboutSectionTitle,
        items = state.rows,
        itemKey = { it.id },
    ) { row, position ->
        val accent = if (row.accent) RobocoTheme.palette.accent else RobocoTheme.palette.secondary
        GroupedRow(
            title = row.title,
            position = position,
            subtitle = row.subtitle,
            icon = row.icon,
            iconTint = accent,
            titleStyle = if (row.accent) {
                uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(16f), RobocoTheme.palette.accent)
            } else {
                null
            },
            accessories = row.accessories,
            testTag = settingsTag(row.id),
            onClick = when (row.id) {
                AboutRowIds.Licenses, AboutRowIds.Update, AboutRowIds.Check -> ({ state.onRowClick(row.id) })
                else -> null
            },
            onLongClick = if (row.id == AboutRowIds.Version) ({ state.onRowLongClick(row.id) }) else null,
            onLongClickLabel = if (row.id == AboutRowIds.Version) CopyActionLabel else null,
        )
    }
}