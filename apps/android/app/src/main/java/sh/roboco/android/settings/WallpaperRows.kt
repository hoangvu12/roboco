package sh.roboco.android.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.StateFlow
import sh.roboco.android.core.WallpaperEffects
import sh.roboco.android.core.WallpaperStore
import sh.roboco.android.design.ActionSheetAction
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.LocalActionSheet
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.settingsTag
import uniffi.roboco_core.WallpaperEffect

/**
 * The "Wallpaper" section of the Settings screen (android-ui-parity #97):
 * the port of `MoreViewController.swift:204-211` and its tap handling
 * (`:222-245`). Three rows, all pure functions of [WallpaperStore.State]:
 *
 *  - `wallpaper:choose` — "Choose Wallpaper…" (nothing set) or "Change
 *    Wallpaper…" (set, with the picked photo's name as the subtitle), with
 *    a `photo` icon and a disclosure chevron. Tapping launches the system
 *    photo picker (`PickVisualMedia(ImageOnly)` — the PHPicker port, no
 *    permission, one image); the returned Uri goes to
 *    [WallpaperSectionActions.set] **inside the result callback**, while
 *    the read grant is still valid.
 *  - `wallpaper:effect` (set only) — "Effect" with the current effect's
 *    `"<Label> — <Detail>"` subtitle. Tapping shows the "Wallpaper Effect"
 *    action sheet (ticket 19) with the five effects, the current one
 *    checked; picking an effect sets it even when it is unchanged, like iOS.
 *  - `wallpaper:remove` (set only) — the destructive "Remove Wallpaper"
 *    row, no accessory, no confirmation: one tap removes the wallpaper.
 *
 * Row changes land **instantly** (iOS `animatingDifferences: false`): the
 * rows re-derive from the store's [StateFlow] with no `animateItem`, and
 * the sheet follows ticket 19's own present/exit motion. All row visuals —
 * geometry, fonts, colours, the pressed highlight — come from ticket 70's
 * [GroupedRow]; this file sets no dp, font or colour of its own.
 */

/** The row ids, `MoreViewController.swift:204,207,209`. */
object WallpaperRowIds {
    const val Choose = "wallpaper:choose"
    const val Effect = "wallpaper:effect"
    const val Remove = "wallpaper:remove"
}

/** The section header copy, `MoreViewController.swift:204`. */
const val WallpaperSectionTitle = "Wallpaper"

/**
 * One row of the section, [MoreViewController.swift:10-26] defaults applied:
 * a disclosure accessory and `secondary` icon tint unless stated, `danger`
 * title and icon only for the destructive row. Pure data — [wallpaperRows]
 * builds these and [WallpaperRow] draws them through ticket 70.
 */
@Immutable
data class WallpaperRowSpec(
    val id: String,
    val title: String,
    val subtitle: String?,
    val icon: RobocoIcon,
    val destructive: Boolean,
    val accessories: List<GroupedAccessory>,
)

/**
 * The rows for [state], `MoreViewController.swift:204-211` verbatim:
 * one `choose` row when nothing is set; `choose` + `effect` + `remove`
 * (in that order) when set. A missing [WallpaperStore.State.name] yields
 * **no** subtitle — iOS passes the optional straight through and
 * `UIListContentConfiguration` draws no second line for `nil`.
 *
 * The ellipsis is U+2026 and the dash U+2014, written as Kotlin escapes so
 * an editor or encoding change cannot turn them into `...` or `-`.
 */
fun wallpaperRows(state: WallpaperStore.State): List<WallpaperRowSpec> {
    val choose = WallpaperRowSpec(
        id = WallpaperRowIds.Choose,
        title = if (state.set) "Change Wallpaper\u2026" else "Choose Wallpaper\u2026",
        subtitle = if (state.set) state.name else "Shown behind new chats and the sessions list",
        icon = RobocoIcons.Photo,
        destructive = false,
        accessories = listOf(GroupedAccessory.Disclosure),
    )
    if (!state.set) return listOf(choose)
    val effect = state.effect
    return listOf(
        choose,
        WallpaperRowSpec(
            id = WallpaperRowIds.Effect,
            title = "Effect",
            subtitle = "${WallpaperEffects.label(effect)} \u2014 ${WallpaperEffects.detail(effect)}",
            icon = RobocoIcons.WandAndStars,
            destructive = false,
            accessories = listOf(GroupedAccessory.Disclosure),
        ),
        WallpaperRowSpec(
            id = WallpaperRowIds.Remove,
            title = "Remove Wallpaper",
            subtitle = null,
            icon = RobocoIcons.Trash,
            destructive = true,
            accessories = emptyList(),
        ),
    )
}

/**
 * The "Wallpaper Effect" sheet model, `MoreViewController.swift:234-243`:
 * title "Wallpaper Effect", no message, one `.default` row per effect in
 * [WallpaperEffects.all] order, the row matching [current] checked (iOS's
 * private KVC `"checked"` key), Cancel at ticket 19's default.
 * [onPick] runs for every row — including the already-current one, which
 * iOS still sets (clearing the render cache and bumping the generation).
 */
fun wallpaperEffectSheet(current: WallpaperEffect, onPick: (WallpaperEffect) -> Unit): ActionSheetModel =
    ActionSheetModel(
        title = "Wallpaper Effect",
        message = null,
        actions = WallpaperEffects.all.map { effect ->
            ActionSheetAction(
                title = WallpaperEffects.label(effect),
                checked = effect == current,
                onSelect = { onPick(effect) },
            )
        },
    )

// ---------------------------------------------------------------------------
// The action seam
// ---------------------------------------------------------------------------

/**
 * The store seam over ticket 20's [WallpaperStore], so tests, the gallery
 * and the future screen tests can drive the section with fakes. All three
 * mutators are fire-and-forget, like iOS's; `set`'s `Deferred<Boolean>`
 * result is ignored (a failed import is logged by the store and otherwise
 * silent, `Wallpaper.swift:89-96`).
 */
class WallpaperSectionActions(
    val state: StateFlow<WallpaperStore.State>,
    val set: (Uri) -> Unit,
    val setEffect: (WallpaperEffect) -> Unit,
    val remove: () -> Unit,
) {
    companion object {
        /**
         * The real store: the process-wide instance behind [WallpaperStore.get].
         * `setEffect` is a lambda, not `store::setEffect`, because ticket 20
         * overloads `setEffect(String): Boolean`.
         */
        fun of(store: WallpaperStore) = WallpaperSectionActions(
            state = store.state,
            set = { store.set(it) },
            setEffect = { store.setEffect(it) },
            remove = { store.remove() },
        )
    }
}

// ---------------------------------------------------------------------------
// The state holder
// ---------------------------------------------------------------------------

/**
 * The section's state: the rows as a pure function of the collected store
 * state ([rows] is a snapshot read, so the list re-derives on the next
 * frame with no animation), and [onRowClick] dispatches the three ids.
 * Built only by [rememberWallpaperSectionState], which owns the picker
 * launcher.
 */
@Stable
class WallpaperSectionState internal constructor(
    private val actions: WallpaperSectionActions,
    private val current: State<WallpaperStore.State>,
    private val sheets: ActionSheetPresenter,
    private val launchPicker: () -> Unit,
) {
    /** The rows for the store's current state — a snapshot read. */
    val rows: List<WallpaperRowSpec> get() = wallpaperRows(current.value)

    /**
     * `MoreViewController.swift:222-245`. The sheet reads
     * [WallpaperStore.State.effect] **here, at tap time** — never from a
     * row captured earlier. Any unknown id does nothing.
     */
    fun onRowClick(id: String) {
        when (id) {
            WallpaperRowIds.Choose -> launchPicker()
            WallpaperRowIds.Effect -> sheets.show(
                wallpaperEffectSheet(current.value.effect, actions.setEffect),
            )
            WallpaperRowIds.Remove -> actions.remove()
        }
    }
}

/**
 * The section's state holder. Call at **screen level** (ticket 103 calls
 * this once at the top of `SettingsScreen`), never inside a `LazyColumn`
 * item: the picker launcher's registration would be dropped when the item
 * leaves composition, and a picked image would be lost.
 *
 * The photo picker is the PHPicker port (`Wallpaper.swift:288-315`):
 * `PickVisualMedia(ImageOnly)` — one image, no photo-library permission.
 * While a launch is pending, further taps on `choose` are ignored (UIKit
 * refuses to present twice; Android would start two picker activities).
 */
@Composable
fun rememberWallpaperSectionState(
    actions: WallpaperSectionActions = WallpaperStore.get(LocalContext.current)
        .let { store -> remember(store) { WallpaperSectionActions.of(store) } },
): WallpaperSectionState {
    val current = actions.state.collectAsState()
    val sheets = LocalActionSheet.current
    var pending by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pending = false
        // Start the import now, while the grant on the returned Uri is
        // still valid; never decode here (the store does its own I/O).
        if (uri != null) actions.set(uri)
    }
    val launch = remember(picker) {
        {
            if (!pending) {
                pending = true
                try {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (e: ActivityNotFoundException) {
                    // No photo picker on the device (nor the contract's
                    // ACTION_OPEN_DOCUMENT fallback): reset the guard, log.
                    pending = false
                    Log.w("Wallpaper", "no photo picker on this device", e)
                }
            }
        }
    }
    return remember(actions, current, sheets, launch) {
        WallpaperSectionState(actions, current, sheets, launch)
    }
}

// ---------------------------------------------------------------------------
// The rows
// ---------------------------------------------------------------------------

/**
 * One row of the section: [GroupedRow] with the spec's data, tagged
 * [settingsTag] (so `settings-wallpaper:choose` and so on), merged
 * semantics with `Role.Button` reading the title then the subtitle — all
 * of that ticket 70's. `iconTint` keeps its `secondary` default;
 * [GroupedRow] switches to `danger` when [WallpaperRowSpec.destructive].
 */
@Composable
fun WallpaperRow(
    spec: WallpaperRowSpec,
    position: GroupedPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    previewPressed: Boolean = false,
) {
    GroupedRow(
        title = spec.title,
        position = position,
        modifier = modifier,
        subtitle = spec.subtitle,
        icon = spec.icon,
        destructive = spec.destructive,
        accessories = spec.accessories,
        testTag = settingsTag(spec.id),
        onClick = onClick,
        previewPressed = previewPressed,
    )
}

/**
 * Emits the section into ticket 103's `LargeTitleScaffold` content (or any
 * `LazyColumn`): the "Wallpaper" header and the rows, one lazy item per
 * row, keyed `grouped:wallpaper:row:<id>` so `choose` keeps its identity
 * when it flips between Choose and Change. `state.rows` is read inside
 * this builder, so the items re-derive on the next frame when the store
 * changes. **No `animateItem()`** — iOS reloads with
 * `animatingDifferences: false` (`MoreViewController.swift:219`).
 */
fun LazyListScope.wallpaperSection(state: WallpaperSectionState) {
    groupedSection(
        key = "wallpaper",
        header = WallpaperSectionTitle,
        items = state.rows,
        itemKey = { it.id },
    ) { spec, position ->
        WallpaperRow(spec, position, onClick = { state.onRowClick(spec.id) })
    }
}
