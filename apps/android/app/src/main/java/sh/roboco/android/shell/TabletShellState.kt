package sh.roboco.android.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/**
 * The tablet split shell (android-ui-parity #33): the Android port of
 * `ios:Shell/SplitRootController.swift` — a 360 dp sidebar beside a detail
 * column, with a 1 px hairline on the sidebar's trailing edge and ⌘B
 * (Ctrl+B) hiding the sidebar. This file owns the shared shell state and
 * the pane geometry; [SplitRoot] owns the layout and animation.
 *
 * The 840 dp breakpoint that decides *when* the split shows is ticket 35's
 * `SplitBreakpoint` (in `shell/RootKind.kt`), deliberately not defined here.
 */

/**
 * `preferredPrimaryColumnWidth = 360` — about a phone's width, so the
 * sidebar rows read as they do on iPhone
 * (`SplitRootController.swift:40`).
 */
internal val SidebarPreferredWidth = 360.dp

/** `minimumPrimaryColumnWidth` (`SplitRootController.swift:41`). */
internal val SidebarMinWidth = 320.dp

/** `maximumPrimaryColumnWidth` (`SplitRootController.swift:42`). */
internal val SidebarMaxWidth = 400.dp

/**
 * A saved description of what the detail column shows — the tablet
 * equivalent of the `UINavigationController`'s view-controller stack,
 * collapsed to its top screen. Ticket 106 turns each kind into routes on
 * its persistent detail [NavStack]; `SplitRoot` only hands the description
 * to its `detail` slot.
 */
@Immutable
sealed interface DetailRoot {

    /**
     * The embedded new-session page (tickets 93/106). `focusOnAppear` is
     * one-shot: `presentNewSession` sets it `true`, a restore always comes
     * back `false`.
     */
    data class Draft(val prompt: String?, val focusOnAppear: Boolean) : DetailRoot

    /** The open session's transcript. */
    data class Session(val chatId: String) : DetailRoot

    /**
     * Transient: the column let go of its session while collapsed (iOS
     * `setViewControllers([])`, `SplitRootController.swift:113`).
     */
    data object Empty : DetailRoot
}

/**
 * The split shell's state, one instance per window, created by
 * [rememberTabletShellState] **above** ticket 35's tabs/split switch so it
 * survives collapse and expand (iOS keeps one `SplitRootController`).
 *
 * Everything the neighbouring tickets touch lives here:
 * `currentChatId` (105's row highlight, 106/108's carry-over),
 * `sidebarVisible` (Ctrl+B here, 106's `showSearch`/open-error),
 * [detailRoot] (106 records it next to every `detailStack.setRoutes`) and
 * [isCollapsed] (written by exactly one place: ticket 108's `SideEffect`
 * `tablet.isCollapsed = kind != RootKind.Split` in 35's root composition —
 * this file never writes it; the gallery fixture is the documented
 * exception).
 */
@Stable
class TabletShellState internal constructor(
    initialChatId: String?,
    initialSidebarVisible: Boolean,
    initialDetailRoot: DetailRoot,
) {
    /** The one "open session" id; the sidebar reads it for its highlight. */
    var currentChatId: String? by mutableStateOf(initialChatId)

    /** The sidebar show/hide **target**; the animated position is [sidebarFraction]. */
    var sidebarVisible: Boolean by mutableStateOf(initialSidebarVisible)

    /** What the detail column shows (recorded by ticket 106 via [setDetailRoot]). */
    var detailRoot: DetailRoot by mutableStateOf(initialDetailRoot)
        private set

    /**
     * Bumped by every [setDetailRoot] — a fixture slot may `key` on it to
     * force a re-compose; production (106) ignores it.
     */
    var detailGeneration: Int by mutableIntStateOf(0)
        private set

    /**
     * True while the window is below the split breakpoint (ticket 35's
     * `RootKind`). One writer: ticket 108's `SideEffect` in 35's root
     * composition. While true, Ctrl+B is ignored (iOS: `⌘B` only changes
     * `preferredDisplayMode`, a silent no-op when collapsed).
     */
    var isCollapsed: Boolean by mutableStateOf(true)

    /** The animated sidebar position, 0 hidden … 1 shown. Layout-internal. */
    internal val sidebarProgress = Animatable(if (initialSidebarVisible) 1f else 0f)

    /** The live sidebar position (0 hidden … 1 shown), re-snapped from [sidebarVisible] on restore. */
    val sidebarFraction: Float get() = sidebarProgress.value

    /**
     * Records what the detail column shows. Always bumps
     * [detailGeneration], even for an equal root.
     */
    @JvmName("applyDetailRoot")
    fun setDetailRoot(root: DetailRoot) {
        detailRoot = root
        detailGeneration++
    }

    /** ⌘B: flips the show/hide target; [SplitRoot] animates to it. */
    fun toggleSidebar() {
        sidebarVisible = !sidebarVisible
    }

    /**
     * Suspends until the sidebar has finished moving to its current
     * target. Utility only: 106 focuses the search at once, as iOS does.
     */
    suspend fun awaitSidebarSettled() {
        snapshotFlow {
            !sidebarProgress.isRunning && sidebarProgress.value == (if (sidebarVisible) 1f else 0f)
        }.first { it }
    }

    companion object {
        /**
         * `listSaver` over four Bundle-safe values (the detail root as a
         * kind tag + its argument). `Draft.focusOnAppear` is saved as
         * false — it is one-shot. `detailGeneration`, `isCollapsed` and
         * the animated fraction are deliberately not saved: the generation
         * restarts at 0, collapse is re-derived by 108, and the fraction
         * re-snaps from [sidebarVisible] in [SplitRoot]'s first effect.
         */
        val Saver: Saver<TabletShellState, Any> = listSaver(
            save = { state ->
                val (kind, arg) = when (val root = state.detailRoot) {
                    is DetailRoot.Draft -> "draft" to root.prompt
                    is DetailRoot.Session -> "session" to root.chatId
                    DetailRoot.Empty -> "empty" to null
                }
                listOf(state.currentChatId, state.sidebarVisible, kind, arg)
            },
            restore = { values ->
                val arg = values[3] as String?
                val root = when (values[2] as String) {
                    // A saved session without its id (a corrupted bundle)
                    // degrades to the empty detail instead of crashing.
                    "session" -> arg?.let { DetailRoot.Session(it) } ?: DetailRoot.Empty
                    "empty" -> DetailRoot.Empty
                    else -> DetailRoot.Draft(arg, focusOnAppear = false)
                }
                TabletShellState(
                    initialChatId = values[0] as String?,
                    initialSidebarVisible = values[1] as Boolean,
                    initialDetailRoot = root,
                )
            },
        )
    }
}

/**
 * One split instance per window. Restores the chat id, the sidebar target
 * and the detail root; the sidebar snaps (never animates) to the restored
 * target on the first composition.
 */
@Composable
fun rememberTabletShellState(): TabletShellState =
    rememberSaveable(saver = TabletShellState.Saver) {
        // Launch shows the embedded new-session page without the keyboard
        // (SplitRootController.swift:50).
        TabletShellState(
            initialChatId = null,
            initialSidebarVisible = true,
            initialDetailRoot = DetailRoot.Draft(prompt = null, focusOnAppear = false),
        )
    }

/**
 * Where each pane sits, in physical pixels, at one animation fraction.
 *
 *  - `sidebarX`: the sidebar pane's leading edge. 0 when fully shown, −S
 *    when hidden, mid-flight in between.
 *  - `paneX`/`paneWidth`: the detail pane's visible window — the only part
 *    that resizes per animation frame (the pane background).
 *  - `contentWidth`: the width the detail **content** was measured at —
 *    the *target* width (W − S shown, W hidden), never per-frame: the
 *    content is measured once per toggle and slid, like Core Animation
 *    interpolating the final frames.
 */
internal data class SplitGeometry(
    val sidebarX: Int,
    val paneX: Int,
    val paneWidth: Int,
    val contentWidth: Int,
)

/**
 * The pane geometry at sidebar fraction `fraction` (0 hidden … 1 shown)
 * for the target `targetVisible`, in a window `totalPx` wide with an
 * S-px sidebar. The sidebar is clamped to the window width so tiny
 * previews (or an 840 dp window below the sidebar minimum) can't overflow.
 */
internal fun splitGeometry(
    totalPx: Int,
    sidebarPx: Int,
    fraction: Float,
    targetVisible: Boolean,
): SplitGeometry {
    val sidebar = min(sidebarPx, totalPx)
    val shown = (fraction.coerceIn(0f, 1f) * sidebar).roundToInt()
    return SplitGeometry(
        sidebarX = shown - sidebar,
        paneX = shown,
        paneWidth = totalPx - shown,
        contentWidth = if (targetVisible) totalPx - sidebar else totalPx,
    )
}
