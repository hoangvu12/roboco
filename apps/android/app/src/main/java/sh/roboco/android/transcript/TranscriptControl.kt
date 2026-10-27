package sh.roboco.android.transcript

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastForEach
import uniffi.roboco_core.WidgetKind

/**
 * The per-screen latch of "tap on the transcript puts the composer away"
 * (android-ui-parity #81): the host's Initial-pass observer arms it with
 * the down's pointer, every [transcriptControl] marker under the finger
 * claims that pointer, and the observer refuses to dismiss an up whose
 * down was claimed. Main thread only — it is touched exclusively during
 * pointer dispatch, never from composition.
 *
 * This is the port of the iOS `UIControl` superview walk
 * (`ios:Session/SessionViewController.swift:352-358`): Compose has no
 * public "node at point" API, so the controls mark themselves instead.
 * Hit testing — which nodes are actually under the finger — stays
 * Compose's, so clipping, scroller offsets and z-order are exact, like
 * UIKit's `touch.view`.
 */
@Stable
class TranscriptTapGate {
    private var armed: PointerId? = null
    private var claimed = false

    /** The observer's down: a fresh gesture starts, nothing has claimed it. */
    internal fun arm(id: PointerId) {
        armed = id
        claimed = false
    }

    /** A marked control saw its own down: the gesture belongs to it. */
    internal fun claim(id: PointerId) {
        if (id == armed) claimed = true
    }

    /** True when [id] is this gesture's down and a control claimed it. */
    internal fun isClaimed(id: PointerId): Boolean = claimed && armed == id
}

/**
 * Null outside the session screen (the Transcript Lab, previews, tests
 * that do not provide one): the marker is then inert. Ticket 75's screen
 * provides the gate around the host.
 */
val LocalTranscriptTapGate = staticCompositionLocalOf<TranscriptTapGate?> { null }

/**
 * iOS `view is UIControl`: a tap whose **down** lands inside this node's
 * bounds never dismisses the composer — the control keeps its tap (the
 * code-block copy button, the disclosure / tool toggle, the Detail
 * widget). Ticket 65's [WidgetBox] applies it to the widget rect of the
 * control kinds only, outermost on that box. The node also makes the rect
 * hit-testable, so a control blocks the dismiss even before its owner
 * ticket (119/120/127) fills the branch.
 */
fun Modifier.transcriptControl(): Modifier = this then TranscriptControlElement

/**
 * Exhaustive on purpose (no `else`): a new Rust `WidgetKind` must decide
 * whether it is a control. True for exactly the kinds iOS renders as
 * `UIControl`s (`ios:Transcript/RowView.swift:202-277`, `:318-358`,
 * `ios:Transcript/ToolViews.swift:315`); everything else — image tiles,
 * chevrons, icons, status glyphs, the rail, the shimmer — lets a tap
 * dismiss (their own handlers still fire, upstream #585).
 */
val WidgetKind.isTranscriptControl: Boolean
    get() = when (this) {
        WidgetKind.CopyCode, is WidgetKind.Disclosure, is WidgetKind.ToolToggle, is WidgetKind.Detail,
        is WidgetKind.SubagentLink, is WidgetKind.Action -> true
        is WidgetKind.Image, is WidgetKind.Chevron, is WidgetKind.Icon, is WidgetKind.ToolStatus,
        WidgetKind.Spinner, is WidgetKind.Working, is WidgetKind.ToolRail, WidgetKind.Shimmer -> false
    }

/**
 * The marker node: observes the Initial pass only. The pass runs
 * ancestors → descendants, so by the time a marked widget sees its down,
 * the host's observer (an ancestor) has already armed the gate — the
 * claim lands after the arm and before the up. The node never consumes:
 * links, image tiles and the scroll still get every event.
 */
private class TranscriptControlNode :
    Modifier.Node(),
    PointerInputModifierNode,
    CompositionLocalConsumerModifierNode {

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        val gate = currentValueOf(LocalTranscriptTapGate) ?: return
        pointerEvent.changes.fastForEach { if (it.changedToDownIgnoreConsumed()) gate.claim(it.id) }
    }

    override fun onCancelPointerInput() {}
}

/** A singleton element: it never changes, so one node serves every marker. */
private data object TranscriptControlElement : ModifierNodeElement<TranscriptControlNode>() {
    override fun create() = TranscriptControlNode()
    override fun update(node: TranscriptControlNode) {}
}
