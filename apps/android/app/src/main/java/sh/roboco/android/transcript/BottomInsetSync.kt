package sh.roboco.android.transcript

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * The inset sync of ticket 133 — the port of
 * `ios:Session/SessionViewController.swift:367-379`
 * (`viewDidLayoutSubviews`): hand the bottom stack's `covered` inset to
 * the viewport **in the same layout pass** in which 74 measured the
 * stack, before the host measures and places its rows. 68 applies the
 * 0.5 dp threshold (S4), runs 69's runway refresh through
 * `onGeometryChanged` and pins `offset = maxOffset` while following
 * (R11) — so while following, the content's bottom edge stays at
 * `stackTop − 8` on every IME animation frame, and the prompt row stays
 * under the header while the runway holds. Not following, the offset is
 * untouched and the content under the stack is simply covered or
 * uncovered (S5).
 *
 * iOS solves this with Auto Layout: `viewDidLayoutSubviews` runs in the
 * same layout pass that moved the bottom stack, so the list gets the new
 * inset before the frame commits — there is no one-frame lag. Compose
 * has no "after the parent laid out" hook; 74 subcomposes the transcript
 * slot *after* computing `stackTop` in the same measure pass, so this
 * node's `measure` is the earliest same-pass point. **Never** publish
 * `covered` through `onGloballyPositioned`, `onSizeChanged`,
 * `snapshotFlow` or a `LaunchedEffect`: all of them run after placement,
 * one frame late (the pr621 trap, S6).
 *
 * Placement: outermost element of the host's chain (passed through the
 * `modifier` argument), before 65's `testTag`/`clipToBounds`/`scrollable`,
 * so it measures first. The session screen (75) applies it to the host
 * it hands 74's `SessionBottomStack`; the Transcript Lab (135) reuses it
 * with its own `covered`. The argument is 74's `SessionBottomInsets.covered`
 * (iOS `adjustedContentInset.bottom`), **not** `contentInset`.
 *
 * Writing snapshot state during measure is allowed (LazyListState does
 * the same); 65's placement lambda reads `viewport.offset` after this
 * call in the same pass, so the rows are placed at the pinned offset in
 * that frame.
 */
fun Modifier.transcriptBottomInset(viewport: TranscriptViewport, covered: Dp): Modifier =
    this then BottomInsetElement(viewport, covered.value)

/** The element: equality by viewport identity and the raw dp value. */
private data class BottomInsetElement(
    val viewport: TranscriptViewport,
    val covered: Float,
) : ModifierNodeElement<BottomInsetNode>() {

    override fun create(): BottomInsetNode = BottomInsetNode(viewport, covered)

    override fun update(node: BottomInsetNode) {
        val changed = node.viewport !== viewport || node.covered != covered
        node.viewport = viewport
        node.covered = covered
        // Explicit; Compose's auto-invalidation on update does the same
        // (shouldAutoInvalidate defaults to true — do not turn it off).
        // The host's constraints are fixed, so this invalidation is what
        // re-runs measure when `covered` changes.
        if (changed) node.invalidateMeasurement()
    }
}

/**
 * The layout node: delivers the inset, then forwards the measurement
 * unchanged. `measure` runs on the main thread in the layout phase, the
 * only phase in which the IME inset read by 74 is already current
 * (Choreographer's INSETS_ANIMATION callback writes it after
 * recomposition and before traversal).
 */
private class BottomInsetNode(
    var viewport: TranscriptViewport,
    var covered: Float,
) : Modifier.Node(), LayoutModifierNode {

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        // Main thread, layout phase. 68's changeGeometry applies the
        // 0.5 dp threshold, runs 69's refresh, pins the offset while
        // following and reports; sub-0.5 changes do nothing, and because
        // the threshold compares against the last applied value the
        // accumulated drift is bounded by 0.5 dp (S4/S7).
        viewport.setBottomInset(covered)
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}
