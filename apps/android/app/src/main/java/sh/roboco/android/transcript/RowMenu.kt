package sh.roboco.android.transcript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.WidgetKind
import kotlin.math.max

/**
 * The transcript row's long-press menu (android-ui-parity #128) — the
 * items of `contextMenuInteraction(_:configurationForMenuAtLocation:)`
 * (`ios:Transcript/TranscriptListView.swift:605-631`): **Copy** the row's
 * own text, **Copy Message** the whole message the row belongs to (only
 * when it differs from the row's text), and **Select Text** the message
 * through [TranscriptActions.selectText] (ticket 127's sheet).
 *
 * This file owns the item list, the clipboard writes, the Select Text
 * hand-off, the "not on a control" rule and the public item builder
 * ticket 129 reuses for accessibility custom actions. The gesture, lift,
 * dim, blur, placement, dismissal and the lift haptic are ticket 16's
 * `Modifier.contextMenu`; the panel look is ticket 15; the sheet is 127.
 * Nothing here draws chrome, and nothing here fires a haptic (choosing an
 * item is silent on iOS).
 */
enum class RowMenuKind(val title: String, val icon: RobocoIcon, val testTag: String) {

    /** `doc.on.doc` → drawable `copy` (`TranscriptListView.swift:614`). Copies the row's own text. */
    Copy("Copy", RobocoIcons.DocOnDoc, "row-menu-copy"),

    /** `doc.on.clipboard` → drawable `clipboard` (`:619`). Copies the whole message. */
    CopyMessage("Copy Message", RobocoIcons.DocOnClipboard, "row-menu-copy-message"),

    /** `character.cursor.ibeam` → drawable `text-cursor` (`:625`). Opens the Select Text sheet. */
    SelectText("Select Text", RobocoIcons.CharacterCursorIbeam, "row-menu-select-text"),
}

/**
 * One concrete item: its kind and the **exact text** it acts on. Actions
 * capture strings, never a `LayoutFrame` — the frame may be replaced (and
 * its native handle released, ticket 61) before the user picks an item.
 */
@Immutable
data class RowMenuAction(val kind: RowMenuKind, val text: String)

/**
 * The item list for one row, pure. [block] is the displayed row's
 * `copyText` (`RowDisplay.copy_text`); [message] is
 * `LayoutFrame.messageText(index)` — `null` only for an out-of-range
 * index, otherwise a string that may be empty.
 *
 * The rules, line for line from `TranscriptListView.swift:612-628`:
 *  - **Copy** iff [block] is non-empty; writes [block].
 *  - **Copy Message** iff [message] is non-null, non-empty and differs
 *    from [block]; writes [message].
 *  - **Select Text** takes `message ?: block` — the Swift `??` only falls
 *    back on a **null** message, so an empty [message] hides the item even
 *    when [block] is non-empty (Rust makes that unreachable; the quirk is
 *    kept so the builder matches the source).
 *  - Order is fixed: Copy, Copy Message, Select Text. No menu title, no
 *    sections, no checked, destructive or disabled rows.
 *  - Zero items → the caller builds no menu at all.
 */
fun rowMenuActions(block: String, message: String?): List<RowMenuAction> = buildList {
    if (block.isNotEmpty()) add(RowMenuAction(RowMenuKind.Copy, block))
    if (!message.isNullOrEmpty() && message != block) add(RowMenuAction(RowMenuKind.CopyMessage, message))
    val selectable = message ?: block // Swift `??`: only a null message falls back
    if (selectable.isNotEmpty()) add(RowMenuAction(RowMenuKind.SelectText, selectable))
}

/**
 * The items for a **realized** row, from the applied frame (the
 * `LayoutFrame` the list last applied — `TranscriptListView.swift:606`):
 * the row's displayed model's `copyText` and the message text at
 * `frame.indexOf(key)`. Empty — no menu at all — when the frame, the row,
 * its model or its key is missing from that frame (the Swift `guard …`
 * `return nil`).
 *
 * [TranscriptState.realized] and [TranscriptState.frame] are main-thread
 * state, so this runs on main — a one-off `messageText` FFI call per long
 * press, never cached in composition (the #609 trap: the menu text must
 * not change under the user while streaming). Ticket 129's accessibility
 * custom actions are built from this same entry point.
 */
fun TranscriptState.rowMenuActions(key: ULong): List<RowMenuAction> {
    val frame = frame ?: return emptyList()
    val model = realized[key]?.model ?: return emptyList()
    val index = frame.indexOf(model.display.key) ?: return emptyList()
    return rowMenuActions(model.display.copyText, frame.messageText(index))
}

/**
 * Builds ticket 15's model from the items: plain rows in order, each with
 * its kind's title, symbol and test tag, `text` ink, no title and no
 * sections — `UIMenu(children: actions)` (`TranscriptListView.swift:630`).
 * `null` when there is nothing to show (an empty menu never opens; ticket
 * 16 then plays no haptic and leaves the gesture alone).
 */
fun List<RowMenuAction>.toMenu(perform: (RowMenuAction) -> Unit): Menu? =
    if (isEmpty()) null else Menu(
        children = map { a ->
            MenuItem(
                title = a.kind.title,
                icon = MenuIcon.Symbol(a.kind.icon),
                testTag = a.kind.testTag,
                action = { perform(a) },
            )
        },
    )

/**
 * Runs the chosen item (15 calls the action on the frame after the close
 * starts, so the sheet rises while the menu fades):
 *  - Copy / Copy Message → `ClipData.newPlainText("Roboco", text)` on the
 *    platform [ClipboardManager], main thread — never compose-ui's
 *    deprecated `LocalClipboardManager`, which hides the `ClipData` label
 *    and the failure path. No haptic, no toast on API 33+ (the system
 *    shows its own clipboard overlay; the menu has already closed and
 *    nothing else confirms the copy).
 *  - Select Text → [selectText], ticket 127's sheet.
 */
class RowMenuPerformer(
    private val context: Context,
    private val toaster: Toaster,
    private val selectText: (String) -> Unit,
) {
    /** Runs [a]; never throws (a clipboard failure is logged and toasted). */
    fun perform(a: RowMenuAction) = when (a.kind) {
        RowMenuKind.Copy, RowMenuKind.CopyMessage -> copy(a.text)
        RowMenuKind.SelectText -> selectText(a.text)
    }

    private fun copy(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        try {
            cm.setPrimaryClip(ClipData.newPlainText("Roboco", text))
        } catch (e: RuntimeException) {
            // e.g. a message over the Binder transaction limit: no crash.
            Log.w("Roboco", "row copy failed: $e")
            toaster.show("Could not copy the text.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toaster.show("Copied")
    }
}

/**
 * One performer per [TranscriptHost] — never per row (rows recompose per
 * frame while streaming). The clipboard and toaster are captured; the
 * actions provider is read at call time through [rememberUpdatedState], so
 * a screen can rebuild its callbacks freely.
 */
@Composable
fun rememberRowMenuPerformer(actions: () -> TranscriptActions): RowMenuPerformer {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val current by rememberUpdatedState(actions)
    return remember(context, toaster) {
        RowMenuPerformer(context, toaster) { text -> current().selectText(text) }
    }
}

/**
 * The widget kinds that own their touches (`ios:Transcript/RowView.swift`
 * — `CopyCode` `:202-204`, `Disclosure` `:205-211`, `Image` `:214-234`,
 * `Detail` `:239-249`, `ToolToggle` `:268-277`, and upstream #899's
 * `SubagentLink`, a `ToolToggleControl` over a spawn card, and #967's
 * `Action`, an error card's `UIButton`). Everything else (`Icon`,
 * `ToolStatus`, `Spinner`, `Working`, `Chevron`, `ToolRail`, `Shimmer`) is
 * display-only (`isUserInteractionEnabled = false`) and a long press over
 * it lifts the row.
 */
internal fun WidgetKind.isControl(): Boolean =
    this is WidgetKind.CopyCode || this is WidgetKind.Disclosure || this is WidgetKind.ToolToggle ||
        this is WidgetKind.Detail || this is WidgetKind.Image || this is WidgetKind.SubagentLink ||
        this is WidgetKind.Action

/**
 * True when a touch-down at row-local `(xDp, yDp)` lands on a control
 * widget — the row then opens **no** menu (the Roboco "away from controls"
 * rule; see the ticket's Comments for why this differs from iOS). Pure,
 * row-local dp, no slop, edges left/top-inclusive and right/bottom-
 * exclusive.
 *
 * A widget in scroller `i` (in range) lives in that scroller's content
 * coordinates, so its rect is translated by `(s.x − offset(i), s.y)` and
 * intersected with the viewport `(s.x, s.y, s.w, s.h)`: a touch outside
 * the viewport misses it, and so does one over where the widget would be
 * unscrolled. A null or out-of-range `scroller` falls back to plain row
 * coordinates.
 */
internal fun isOverControl(d: RowDisplay, xDp: Float, yDp: Float, scrollerOffsetDp: (Int) -> Float): Boolean {
    for (w in d.widgets) {
        if (!w.kind.isControl()) continue
        val sc = w.scroller?.toInt()
        var x = xDp
        var y = yDp
        if (sc != null && sc < d.scrollers.size) {
            val s = d.scrollers[sc]
            // Outside the viewport: the widget is masked there.
            if (xDp < s.x || xDp >= s.x + s.w || yDp < s.y || yDp >= s.y + s.h) continue
            // Row-local → scroller content (ScrollerGeometry.rowToContent).
            x = xDp - s.x + scrollerOffsetDp(sc)
            y = yDp - s.y
        }
        if (x >= w.x && x < w.x + max(w.w, 0f) && y >= w.y && y < w.y + max(w.h, 0f)) return true
    }
    return false
}

/**
 * Records the last touch-down in this node's px (`down[0]`, `down[1]`;
 * NaN when no finger is down). Observes only — it never consumes, on any
 * pass. Resets to NaN on release, so the accessibility long-click (no
 * touch) never sees a stale position and skips the control check.
 */
internal fun Modifier.trackRowDown(down: FloatArray): Modifier = pointerInput(down) {
    awaitEachGesture {
        val d = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        down[0] = d.position.x
        down[1] = d.position.y
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.changes.none { it.pressed }) break // released (or cancelled)
        }
        down[0] = Float.NaN
        down[1] = Float.NaN
    }
}
