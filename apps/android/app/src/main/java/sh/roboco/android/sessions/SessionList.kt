package sh.roboco.android.sessions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.roboco.android.core.Face
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.LocalNavEntryOrNull
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavEntryEffect
import uniffi.roboco_core.SessionRow as CoreSessionRow

/**
 * The deselect fade — the stand-in for UIKit's unstated
 * `deselectItem(at:animated: true)` (`SessionListController.swift:176`):
 * [Motion.RowHighlightFadeOutMs] ms ease-in-out, tuned side by side with
 * an iOS 26 simulator. A [TweenSpec] on purpose: there is no iOS spring
 * to settle-match here, only the animated deselect's fade.
 */
val RowHighlightFadeOut: TweenSpec<Float> = tween(Motion.RowHighlightFadeOutMs, easing = Motion.EaseInOut)

// ---------------------------------------------------------------------------
// The model
// ---------------------------------------------------------------------------

/**
 * The list's built state: [items] (keyed, de-duped, folded) plus the
 * derived [status] and the item-animation gate [animate]. Built by
 * [rememberSessionListModel] — the items are `remember`ed over 40's
 * sections, so a recomposition with equal inputs reuses the same list.
 *
 * [animate] is false while the list is off screen, and for the one frame
 * after it comes back on screen or after a font-scale change — the port
 * of iOS's `dataSource.apply(s, animatingDifferences: animated &&
 * view.window != nil)` plus the non-animated `invalidateLayout()` on a
 * font-size change. Everything else (inserts, moves, removals) animates.
 */
@Stable
class SessionListModel internal constructor(
    val items: List<SessionListItem>,
    val status: SessionListStatus,
    /** false while off screen, and for the one frame after coming on screen or after a font-scale change. */
    val animate: Boolean,
)

/**
 * Builds [SessionListModel] from ticket 40's [ListSection]s: the items
 * through [buildSessionListItems] (keyed on the sections, the fold and
 * the live overrides), the status through [sessionListStatus] over the
 * built items (leading items do not count — they never reach this model),
 * and the animation gate decided **synchronously in composition** (a
 * `LaunchedEffect` would run after the frame's layout, which is too late
 * for the `Modifier.animateItem` chain that frame already carries).
 *
 * The gate's bookkeeping writes land in [SideEffect]: the write triggers
 * the next frame's recomposition, so exactly one frame (the flip frame
 * itself) sees the stale value and lays out without item animation.
 *
 * @param onScreen screens inside a `NavStackHost` pass
 *   [rememberNavEntryOnScreen]; the default (true) fits hosts that are
 *   always composed, like the gallery.
 */
@Composable
fun rememberSessionListModel(
    sections: List<ListSection>,
    collapsible: Boolean = false,
    collapsedIds: Set<String> = emptySet(),
    liveOverrides: Map<String, SectionLive> = emptyMap(),
    loading: Boolean = false,
    empty: SessionListEmpty = SessionListEmpty.None,
    onScreen: Boolean = true,
): SessionListModel {
    val items = remember(sections, collapsible, collapsedIds, liveOverrides) {
        buildSessionListItems(sections, collapsible, collapsedIds, liveOverrides)
    }
    val status = sessionListStatus(items, loading, empty)
    val factor = LocalTypeScale.current.factor
    var lastFactor by remember { mutableFloatStateOf(factor) }
    var lastOnScreen by remember { mutableStateOf(onScreen) }
    val animate = onScreen && lastOnScreen && factor == lastFactor
    SideEffect {
        lastFactor = factor
        lastOnScreen = onScreen
    }
    return SessionListModel(items, status, animate)
}

/**
 * True between the entry's `willAppear` and its next `didDisappear`
 * (ticket 24's [NavEntryEffect]) — the Android port of
 * `view.window != nil`: a list covered by a pushed session, or on an
 * inactive tab, applies changes without animation, and the first frame
 * back on screen lays out without animation too.
 *
 * Outside a `NavStackHost` (the gallery, previews, plain hosts) there is
 * no entry to track: always true.
 */
@Composable
fun rememberNavEntryOnScreen(): Boolean {
    // Null without a NavStackHost above: the gallery, a preview, a test host.
    val entry = LocalNavEntryOrNull.current
    if (entry == null) return true
    val stack = LocalNavStack.current
    var onScreen by remember(entry) { mutableStateOf(entry.hasAppeared || stack.top == entry) }
    NavEntryEffect(
        onWillAppear = { onScreen = true },
        onDidDisappear = { onScreen = false },
    )
    return onScreen
}

// ---------------------------------------------------------------------------
// The item slots 44/46/47 wrap
// ---------------------------------------------------------------------------

/**
 * Everything the row wrappers (44's swipe + context menu, 47's drag
 * handle) need about one session item: the FFI record (engineId,
 * archived, …), 37's view model, its section, its per-row flags and
 * whether it is the session open beside this list.
 */
@Immutable
data class SessionRowContext(
    /** The FFI record, for 44/47 (`engineId`, `archived`, …). */
    val row: CoreSessionRow,
    val vm: SessionRowVM,
    val sectionId: String,
    /** `archivedRow(vm.id)`: iOS `archivedRows`, per row for 49/105. */
    val archived: Boolean,
    /** iOS `reorderable` — drag handles in edit mode (47). */
    val reorderable: Boolean,
    /** Edit mode: taps disabled, the item stays composed and keyed. */
    val editing: Boolean,
    /** `vm.id == currentChatId` — the session open beside this list. */
    val isCurrent: Boolean,
)

/**
 * `row(m)` draws the item; `m` is placed **first** in the row's modifier
 * chain — before this ticket's `clickable` — which is exactly where
 * ticket 16's `Modifier.contextMenu` must go. Wrappers may also wrap
 * `row(...)` in their own `Box` (44's `SwipeRow`). The wrapper must NOT
 * add its own `Modifier.animateItem` (see `sessionListItems`).
 */
typealias SessionRowWrapper = @Composable LazyItemScope.(
    ctx: SessionRowContext,
    row: @Composable (Modifier) -> Unit,
) -> Unit

/** `header(m)` passes `m` as 39's [SectionHeaderRow] `modifier` (46: `Modifier.contextMenu(LiftHighlight.SectionHeader) { … }`). */
typealias SectionHeaderWrapper = @Composable LazyItemScope.(
    state: SectionHeaderState,
    header: @Composable (Modifier) -> Unit,
) -> Unit

/** The identity row wrapper: the item draws itself with a plain `Modifier`. */
val PassThroughRowWrapper: SessionRowWrapper = { _, row -> row(Modifier) }

/** The identity header wrapper. */
val PassThroughHeaderWrapper: SectionHeaderWrapper = { _, header -> header(Modifier) }

// ---------------------------------------------------------------------------
// The emitter
// ---------------------------------------------------------------------------

/**
 * Everything `sessionListItems` needs for the Pinned Edit-mode reorder
 * (android-ui-parity #47): the library's state (built by the host over
 * the list's own `LazyListState`, so the drag and the scroll share one
 * state), the local order state machine, the id → engine map and ticket
 * 42's dispatch. Pass it only from the Pinned folder screen (and its
 * gallery / tests) — every other list keeps `null` and today's item
 * structure.
 */
@Stable
class SessionListReorder(
    /** `rememberReorderableLazyListState(<the list's LazyListState>, …)`. */
    val lazyState: ReorderableLazyListState,

    /** The drag/commit state machine; its `order` sorts the session items. */
    val state: PinReorderState,

    /** id → engineId, built from the same snapshot rows the keys come from. */
    val engineOf: (String) -> String,

    /** Ticket 42: `{ mv, done -> rowActions.movePin(mv.id, mv.after, mv.before, done) }`. */
    val dispatch: (PinMove, onResult: (ActionOutcome) -> Unit) -> Unit,
)

/**
 * Emits the list's items into any `LazyColumn`: one `item(key, contentType)`
 * per [SessionListItem] — key `"s:<id>"`/`"h:<id>"`/`"f:<id>"`,
 * contentType `"session"`/`"sectionHeader"`/`"plainHeader"`/`"folder"` —
 * each at its fixed height (62k/40k/46k scaled, the plain header a fixed
 * 41 dp), contiguous (no spacing, no separators), on the host's clear
 * background.
 *
 * `Modifier.animateItem` is applied **once, on the item's outer box**
 * (Compose defaults: fade in, placement and fade out are each a
 * critically damped 400-stiffness spring; placement uses a 1-px
 * visibility threshold); wrappers must not apply it again.
 * Ticket 47 is the one exception: with a non-null `reorder` it replaces
 * the outer box for session items with the library's `ReorderableItem`
 * and hands it this same modifier as `animateItemModifier`. Under reduce
 * motion the placement spec is null (items jump, fades stay), and while
 * [SessionListModel.animate] is false the modifier is absent entirely.
 *
 * The pressed / deselect highlight is timed here — one
 * `MutableInteractionSource` per item, `clickable(indication = null,
 * …)` on the item, [rememberRowHighlight] feeding the row's
 * `highlight: () -> Float` — and drawn by the item composable (37's
 * `SessionRow`, 39's [SectionHeaderRow], this file's [FolderRow]), never
 * by this list and never with a ripple.
 *
 * **Recipe for screens inside [sh.roboco.android.shell.LargeTitleScaffold]**
 * (tickets 40/45/46 — they do not use [SessionList]): call
 * `leadingItems(); sessionListItems(model, …)` inside the scaffold's
 * `content`, pass the scaffold's `listState` as the scroll state, and put
 * `SessionListStatusOverlay(model.status, PaddingValues(top = topInset +
 * 44.dp + 52.dp, bottom = bottomInset))` in the scaffold's `backdrop`
 * slot — behind the clear list; ticket 46 layers the wallpaper under it.
 *
 * @param onOpenSession the tap handler — [rememberSessionListOpener] on
 *   the phone, the tablet override; the session item disables it while
 *   [editing] (iOS: no selection while editing).
 * @param archivedRow per-row archived flag for 49/105 (Search hits);
 *   defaults to [archivedRows] for the whole list.
 * @param reorder ticket 47's hookup; non-null only for the Pinned folder
 *   screen — see [SessionListReorder]. The session items are then sorted
 *   by `reorder.state.order` and their outer box becomes the library's
 *   `ReorderableItem` (the item-root rule), with the Edit-mode drag
 *   handle, the lift look and the Move Up / Move Down actions inside.
 */
fun LazyListScope.sessionListItems(
    model: SessionListModel,
    onOpenSession: (String) -> Unit,
    onOpenFolder: (String) -> Unit = {},
    onToggleSection: (String) -> Unit = {},
    archivedRows: Boolean = false,
    archivedRow: (chatId: String) -> Boolean = { archivedRows },
    reorderable: Boolean = false,
    editing: Boolean = false,
    currentChatId: String? = null,
    rowWrapper: SessionRowWrapper = PassThroughRowWrapper,
    headerWrapper: SectionHeaderWrapper = PassThroughHeaderWrapper,
    reorder: SessionListReorder? = null,
) {
    // 47: while a drag (or its commit) is in flight the local order — not
    // the model — positions the session items; the read is in the content
    // lambda's scope, so every move re-registers the items in the new
    // order and 41's `animateItem` placement moves the neighbours.
    val entries =
        if (reorder != null) sessionItemsInOrder(model.items, reorder.state.order) else model.items
    for (entry in entries) {
        when (entry) {
            is SessionListItem.Header -> item(key = entry.key, contentType = entry.contentType) {
                val ts = LocalTypeScale.current
                Box(itemAnimation(model.animate).fillMaxWidth().height(ts.scaledDp(HeaderHeightPt))) {
                    val state = entry.state
                    val src = remember { MutableInteractionSource() }
                    val h = rememberRowHighlight(src, fadeOnRelease = false)
                    headerWrapper(state) { m ->
                        // 46: the header's own pressed fill snaps to 0 while
                        // the context menu lifts it (LocalRowLiftState,
                        // provided around this lambda by 46's header
                        // wrapper) — the "double pressed fill" rule.
                        val lift = LocalRowLiftState.current
                        SectionHeaderRow(
                            state,
                            highlight = { if (lift?.isLifted == true) 0f else h.value },
                            modifier = m
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = src,
                                    indication = null,
                                    role = Role.Button,
                                ) { onToggleSection(state.id) },
                        )
                    }
                }
            }

            is SessionListItem.PlainHeader -> item(key = entry.key, contentType = entry.contentType) {
                Box(itemAnimation(model.animate).fillMaxWidth().height(PlainHeaderHeight)) {
                    PlainSectionHeader(entry.title)
                }
            }

            is SessionListItem.Folder -> item(key = entry.key, contentType = entry.contentType) {
                val ts = LocalTypeScale.current
                Box(itemAnimation(model.animate).fillMaxWidth().height(ts.scaledDp(FolderHeightPt))) {
                    val vm = entry.vm
                    val src = remember { MutableInteractionSource() }
                    val h = rememberRowHighlight(src, fadeOnRelease = true)
                    FolderRow(
                        vm,
                        highlight = { h.value },
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = src,
                                indication = null,
                            ) { onOpenFolder(vm.id) },
                    )
                }
            }

            is SessionListItem.Session -> {
                if (reorder == null) {
                    item(key = entry.key, contentType = entry.contentType) {
                        val ts = LocalTypeScale.current
                        Box(itemAnimation(model.animate).fillMaxWidth().height(ts.scaledDp(SessionHeightPt))) {
                            SessionItemBody(
                                entry = entry,
                                onOpenSession = onOpenSession,
                                archivedRow = archivedRow,
                                reorderable = reorderable,
                                editing = editing,
                                currentChatId = currentChatId,
                                rowWrapper = rowWrapper,
                            )
                        }
                    }
                } else {
                    item(key = entry.key, contentType = entry.contentType) {
                        ReorderableSessionItem(
                            entry = entry,
                            model = model,
                            onOpenSession = onOpenSession,
                            archivedRow = archivedRow,
                            reorderable = reorderable,
                            editing = editing,
                            currentChatId = currentChatId,
                            rowWrapper = rowWrapper,
                            reorder = reorder,
                        )
                    }
                }
            }
        }
    }
}

/** `(62 * TypeScale.factor).rounded()` (`SessionCell.swift:49`). */
private const val SessionHeightPt = 62f

// ---------------------------------------------------------------------------
// The session item's shared body and its reorderable variant (47)
// ---------------------------------------------------------------------------

/**
 * The session item's inner body (41): one highlight source, the
 * [SessionRowContext] and the row wrapper over 37's [SessionRow]. Shared
 * verbatim by the plain item (41's outer `Box` + `animateItem`) and the
 * reorderable item (47's `ReorderableItem` + `Row`), so the wrapper chain
 * — 44's `SwipeRow` and context menu — is the same in both.
 */
@Composable
private fun LazyItemScope.SessionItemBody(
    entry: SessionListItem.Session,
    onOpenSession: (String) -> Unit,
    archivedRow: (chatId: String) -> Boolean,
    reorderable: Boolean,
    editing: Boolean,
    currentChatId: String?,
    rowWrapper: SessionRowWrapper,
) {
    val vm = entry.vm
    val src = remember { MutableInteractionSource() }
    val h = rememberRowHighlight(src, fadeOnRelease = true)
    val ctx = SessionRowContext(
        row = entry.row,
        vm = vm,
        sectionId = entry.sectionId,
        archived = archivedRow(vm.id),
        reorderable = reorderable,
        editing = editing,
        isCurrent = vm.id == currentChatId,
    )
    rowWrapper(ctx) { m ->
        // 44: the pressed fill reads 0 while the context menu
        // lifts this row (LocalRowLiftState, provided by
        // 44's wrapper around this lambda).
        val lift = LocalRowLiftState.current
        SessionRow(
            vm = vm,
            modifier = m
                .fillMaxSize()
                .clickable(
                    interactionSource = src,
                    indication = null,
                    enabled = !editing,
                ) { onOpenSession(vm.id) },
            isCurrent = ctx.isCurrent,
            highlight = { if (lift?.isLifted == true) 0f else h.value },
            // meta: the slot's default — 38's SessionRowMetaLine.
        )
    }
}

/**
 * The reorderable session item (47) — the **item root** the library
 * requires: `ReorderableItem` replaces 41's outer `Box` (a nested
 * `ReorderableItem` would draw the dragged row *under* later rows —
 * `zIndex` only orders siblings — and 41's outer `animateItem` would
 * fight the library's `translationY`), and 41's own `animateItem`
 * modifier is handed over as `animateItemModifier`, so it is still
 * applied exactly once and dropped for the dragged row. `ReorderableItem`
 * adds the dragged row's `zIndex(1f)` and `translationY` itself.
 *
 * `enabled` clamps the drop targets to the dragged row's engine block
 * (the library removes disabled keys from its `reorderableKeys`); inside,
 * the content `Row` carries the lift look, the narrowed content and the
 * drag handle slot. Taps, swipes and the long-press menu need nothing
 * here: 41's `clickable(enabled = !editing)` and 44's wrapper already
 * switch them off while editing.
 */
@Composable
private fun LazyItemScope.ReorderableSessionItem(
    entry: SessionListItem.Session,
    model: SessionListModel,
    onOpenSession: (String) -> Unit,
    archivedRow: (chatId: String) -> Boolean,
    reorderable: Boolean,
    editing: Boolean,
    currentChatId: String?,
    rowWrapper: SessionRowWrapper,
    reorder: SessionListReorder,
) {
    val ts = LocalTypeScale.current
    val vm = entry.vm
    val reduce = LocalReduceMotion.current
    val anim = itemAnimation(model.animate)
    ReorderableItem(
        state = reorder.lazyState,
        key = entry.key,
        modifier = Modifier.fillMaxWidth().height(ts.scaledDp(SessionHeightPt)),
        enabled = editing &&
            (reorder.state.dragEngine == null || reorder.engineOf(vm.id) == reorder.state.dragEngine),
        animateItemModifier = anim,
    ) { isDragging ->
        // The handle slot needs the library's item scope for
        // `Modifier.draggableHandle` (its member, the library's own
        // documented pattern for passing it to a child composable).
        val itemScope = this
        val progress by animateFloatAsState(
            targetValue = if (isDragging) 1f else 0f,
            animationSpec = when {
                reduce -> snap() // Reduce motion: the lift snaps 0 / 1.
                isDragging -> tween(EditMetrics.LiftInMs, easing = Motion.EaseOut)
                else -> tween(EditMetrics.LiftOutMs, easing = Motion.EaseInOut)
            },
            label = "pinned-row-lift",
        )
        val stepActions = reorderStepActions(reorder, vm.id, editing)
        Row(
            Modifier
                .fillMaxSize()
                .liftedRow(progress = { progress })
                .semantics { if (stepActions.isNotEmpty()) customActions = stepActions },
        ) {
            // The content narrows by the handle slot's animated width; the
            // row wrapper (44's swipe + menu) and 37's row stay untouched.
            Box(Modifier.weight(1f).fillMaxHeight()) {
                SessionItemBody(
                    entry = entry,
                    onOpenSession = onOpenSession,
                    archivedRow = archivedRow,
                    reorderable = reorderable,
                    editing = editing,
                    currentChatId = currentChatId,
                    rowWrapper = rowWrapper,
                )
            }
            ReorderHandleSlot(
                itemScope = itemScope,
                id = vm.id,
                visible = editing,
                enabled = editing && !reorder.state.committing,
                onDragStarted = { reorder.state.start(vm.id) },
                onDragStopped = {
                    reorder.state.end()?.let { mv ->
                        reorder.dispatch(mv) { outcome ->
                            reorder.state.committed(outcome == ActionOutcome.Ok)
                        }
                    }
                },
            )
        }
    }
}

/**
 * The Edit-mode accessibility actions (47): "Move Up" / "Move Down" —
 * the Android replacement for VoiceOver's built-in reorder support
 * (TalkBack cannot drag). Each is present only when the row has a
 * same-engine neighbour in that direction; invoking it swaps the pair,
 * applies the step locally and dispatches the same `PinMove` rule a drag
 * would have. While idle there are none (the row's click is 41's, the
 * swipe actions are 44's, which drops them while editing).
 */
@Composable
private fun reorderStepActions(
    reorder: SessionListReorder,
    id: String,
    editing: Boolean,
): List<CustomAccessibilityAction> {
    if (!editing || reorder.state.committing) return emptyList()
    val order = reorder.state.order
    val index = order.indexOfFirst { it.id == id }
    if (index < 0) return emptyList()
    return buildList {
        for (direction in intArrayOf(-1, 1)) {
            val step = MovePinNeighbours.step(order, index, direction) ?: continue
            val (newOrder, move) = step
            val label = if (direction < 0) "Move Up" else "Move Down"
            add(
                CustomAccessibilityAction(label) {
                    reorder.state.applyStep(newOrder)
                    reorder.dispatch(move) { outcome ->
                        reorder.state.committed(outcome == ActionOutcome.Ok)
                    }
                    true
                },
            )
        }
    }
}

/**
 * Reorders [items] by the local drag [order] (47): the session entries
 * re-sorted by the order's ids (a model row missing from [order] keeps
 * its model position, at the end of the session run), while every
 * non-session item keeps its position and the session slots — however
 * interleaved — take the sorted sessions in turn. With [order] equal to
 * the model order the result is the input, structurally.
 */
private fun sessionItemsInOrder(
    items: List<SessionListItem>,
    order: List<PinKey>,
): List<SessionListItem> {
    val sessions = LinkedHashMap<String, SessionListItem.Session>()
    for (item in items) {
        if (item is SessionListItem.Session) sessions.putIfAbsent(item.vm.id, item)
    }
    val ordered = ArrayList<SessionListItem.Session>(sessions.size)
    val seen = HashSet<String>()
    for (key in order) {
        if (seen.add(key.id)) {
            sessions[key.id]?.let { ordered.add(it) }
        }
    }
    for (item in items) {
        if (item is SessionListItem.Session && item.vm.id !in seen) ordered.add(item)
    }
    val queue = ArrayDeque<SessionListItem.Session>(ordered)
    return items.map { item ->
        if (item is SessionListItem.Session) queue.removeFirst() else item
    }
}

/** `(40 * TypeScale.factor).rounded()` (`SessionCell.swift:243`). */
private const val HeaderHeightPt = 40f

/** `(46 * TypeScale.factor).rounded()` (`SessionCell.swift:198`). */
private const val FolderHeightPt = 46f

/** 18 + ⌈1.3·13⌉ + 6 — UIKit self-sizes `plainHeader()`; computed here as fixed. */
private val PlainHeaderHeight = 41.dp

/**
 * The item's animation modifier, applied once on the item's outer box:
 * Compose's `animateItem` defaults — fade in, placement (unless reduce
 * motion jumps) and fade out, each a critically damped 400-stiffness
 * spring. Absent entirely while [animate] is false (off screen, the
 * frame back on screen, the font-scale frame). The spec itself lives in
 * [Motion.ListItemPlacement].
 */
@Composable
private fun LazyItemScope.itemAnimation(animate: Boolean): Modifier {
    if (!animate) return Modifier
    val placement: FiniteAnimationSpec<IntOffset>? =
        if (LocalReduceMotion.current) {
            null // Reduce motion: items jump into place; the fades stay (opacity only).
        } else {
            Motion.ListItemPlacement
        }
    return Modifier.animateItem(placementSpec = placement)
}

// ---------------------------------------------------------------------------
// The plain header and the folder row
// ---------------------------------------------------------------------------

/**
 * The non-collapsible header — `UIListContentConfiguration.plainHeader()`
 * (`SessionListController.swift:71-81`): the title in `Fonts.ui(.sansSemibold,
 * 13)` (fixed — a UIKit point, whatever the type scale) and `secondary`,
 * one line with ellipsis, margins 18 top / 20 leading / 6 bottom / 20
 * trailing, clear background. Not selectable (`shouldSelectItemAt`
 * returns `collapsible`, `:188-191`): no `clickable`, no highlight, no
 * press state — only the `heading()` semantics a plain header gives.
 */
@Composable
private fun PlainSectionHeader(title: String, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Box(
        modifier
            .fillMaxSize()
            .semantics { heading() }
            .padding(top = 18.dp, start = 20.dp, bottom = 6.dp, end = 20.dp),
    ) {
        BasicText(
            title,
            style = uiStyle(Face.SansSemibold, ts.fixed(13f), palette.secondary),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The folder row's fixed geometry (`FolderCell`, `SessionCell.swift:198-230`). */
private object FolderRowTokens {
    /** `UIImage(systemName: vm.symbol, pointSize: 16, weight: .regular)` — `secondary` tint. */
    const val IconPt = 16f

    /** The 16 pt symbol's reserved box: 16 × 1.2 = 19.2 dp (ticket 06). */
    val IconBox = 19.2.dp

    /** `imageToTextPadding = 18` — the text starts 18 dp after the icon box. */
    val IconTextGap = 18.dp

    /** The measured system disclosure: 13 pt semibold, box 15.6 dp, `tertiary`. */
    const val ChevronPt = 13f
    val ChevronBox = 15.6.dp

    /** Margins leading/trailing 20 (`cell.layoutMargins`); the chevron box's trailing edge. */
    val LeadingInset = 20.dp
    val TrailingInset = 20.dp

    /** The text ends 8 dp before the chevron box. */
    val TextChevronGap = 8.dp

    /** `controlFill` highlight: radius 16, insets 1 top/bottom and 8 start/end. */
    val FillCornerRadius = 16.dp
    val FillHorizontalInset = 8.dp
    val FillVerticalInset = 1.dp
}

/**
 * The folder row — the port of `FolderCell.configure` /
 * `updateConfiguration` (`ios:Threads/SessionCell.swift:198-230`): the
 * 16 pt symbol tinted `secondary` at x = 20 (a 19.2 dp box even when
 * `forSfName` resolves nothing, so the text never shifts), the name in
 * SansMedium 17 scaled `secondary` with `"  <count>"` in Sans 17 scaled
 * `tertiary` (two spaces), one line with ellipsis, and the 13 pt
 * semibold chevron 20 from the trailing edge. The pressed fill is
 * `controlFill × highlight` in a 16 dp rounded rect inset 1/8/1/8 — same
 * rule as the session row, timed by this ticket's
 * [rememberRowHighlight] and drawn here in the draw phase.
 *
 * Hand layout with absolute [androidx.compose.ui.layout.Placeable.place],
 * like the session and header rows (the iOS frames are absolute and do
 * not mirror in RTL). One a11y node: `folder-<id>` with
 * `"<name>, <count>"`; the list's `clickable` arrives in [modifier],
 * outermost, so it survives the clear.
 */
@Composable
private fun FolderRow(vm: FolderRowVM, highlight: () -> Float, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val nameStyle = uiStyle(Face.SansMedium, ts.scaled(17f), palette.secondary)
    val countStyle = uiStyle(Face.Sans, ts.scaled(17f), palette.tertiary)
    // "  \(vm.count)" — two spaces, per FolderCell.configure (:213).
    val label = remember(vm.name, vm.count, nameStyle, countStyle) {
        buildAnnotatedString {
            withStyle(nameStyle.toSpanStyle()) { append(vm.name) }
            withStyle(countStyle.toSpanStyle()) { append("  ${vm.count}") }
        }
    }
    val icon = RobocoIcons.forSfName(vm.symbol)
    val fillShape = rememberSmoothRoundedShape(FolderRowTokens.FillCornerRadius)
    // Draw-phase read: a press or fade redraws, never recomposes.
    val hl by rememberUpdatedState(highlight)
    Layout(
        content = {
            if (icon != null) {
                Icon(icon, palette.secondary, FolderRowTokens.IconPt, SymbolWeight.Regular)
            } else {
                // Keep the reserved 19.2 dp box so the text does not shift.
                Spacer(Modifier.size(FolderRowTokens.IconBox))
            }
            BasicText(
                label,
                style = nameStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(RobocoIcons.ChevronRight, palette.tertiary, FolderRowTokens.ChevronPt, SymbolWeight.Semibold)
        },
        modifier = modifier
            .fillMaxWidth()
            .drawWithCache {
                val l = FolderRowTokens.FillHorizontalInset.toPx()
                val t = FolderRowTokens.FillVerticalInset.toPx()
                val outline = fillShape.createOutline(
                    Size(size.width - 2f * l, size.height - 2f * t),
                    layoutDirection,
                    this,
                )
                onDrawBehind {
                    val a = hl().coerceIn(0f, 1f)
                    if (a > 0f) translate(l, t) { drawOutline(outline, palette.controlFill, alpha = a) }
                }
            }
            .clearAndSetSemantics {
                testTag = "folder-${vm.id}"
                contentDescription = "${vm.name}, ${vm.count}"
            },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val iconP = measurables[0].measure(Constraints())
        val chevronBox = FolderRowTokens.ChevronBox.roundToPx()
        val chevronP = measurables[2].measure(Constraints())
        val textX = (FolderRowTokens.LeadingInset + FolderRowTokens.IconBox + FolderRowTokens.IconTextGap).roundToPx()
        val textMaxWidth = (w - FolderRowTokens.TrailingInset.roundToPx() - chevronBox -
            FolderRowTokens.TextChevronGap.roundToPx() - textX).coerceAtLeast(0)
        val textP = measurables[1].measure(Constraints(maxWidth = textMaxWidth))
        val iconX = FolderRowTokens.LeadingInset.roundToPx()
        val chevronX = w - FolderRowTokens.TrailingInset.roundToPx() - chevronBox
        layout(w, h) {
            // place(), not placeRelative(): absolute, like the other rows.
            iconP.place(iconX, (h - iconP.height) / 2)
            textP.place(textX, (h - textP.height) / 2)
            chevronP.place(chevronX, (h - chevronP.height) / 2)
        }
    }
}

// ---------------------------------------------------------------------------
// The highlight timer
// ---------------------------------------------------------------------------

/**
 * The pressed / deselect highlight, 0…1 — the port of the cell's
 * `isHighlighted || isSelected` background and the animated
 * `deselectItem`. Drawn by the item composable through the returned
 * [State]'s value, read only in draw lambdas.
 *
 *  - **Press** → snap to 1 (a running fade is cancelled first, so a
 *    re-press takes effect at once — the collector is never blocked
 *    behind the fade, which runs in a child job).
 *  - **Release** → with `fadeOnRelease = true` (session and folder rows,
 *    which draw `isSelected` on iOS), animate to 0 over
 *    [RowHighlightFadeOut]; with `false` (the header, which ignores
 *    `isSelected`), snap to 0 — the fill clears on release with no fade.
 *  - **Cancel** → snap to 0.
 *
 * Compose's `clickable` inside a scrollable already delays
 * `PressInteraction.Press` by the tap timeout (UIKit's
 * `delaysContentTouches`); no tuning here. Reduced motion keeps the fade
 * (opacity only).
 */
@Composable
fun rememberRowHighlight(src: MutableInteractionSource, fadeOnRelease: Boolean = true): State<Float> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(src, fadeOnRelease) {
        var fadeJob: Job? = null
        val scope = this
        src.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    fadeJob?.cancel()
                    a.snapTo(1f)
                }

                is PressInteraction.Release -> {
                    fadeJob?.cancel()
                    if (fadeOnRelease) {
                        fadeJob = scope.launch { a.animateTo(0f, RowHighlightFadeOut) }
                    } else {
                        a.snapTo(0f)
                    }
                }

                is PressInteraction.Cancel -> {
                    fadeJob?.cancel()
                    a.snapTo(0f)
                }
            }
        }
    }
    return a.asState()
}

// ---------------------------------------------------------------------------
// The standalone host
// ---------------------------------------------------------------------------

/**
 * The standalone host: the page background, the [backdrop], the
 * [SessionListStatusOverlay] and the `LazyColumn` — for the gallery,
 * tests and any screen without a `LargeTitleScaffold`. The list itself
 * has no background and no padding modifier; [contentPadding] keeps rows
 * scrolling under the chrome and doubles as the overlay's safe padding.
 *
 * Screens inside a `LargeTitleScaffold` (the front page, folder pages,
 * Search) do **not** use this: see the recipe on
 * [sessionListItems].
 *
 * The tap callbacks are wrapped in `rememberUpdatedState` and stable
 * proxies, so a new lambda from the host never recomposes every item —
 * the click handlers read the latest value at click time.
 *
 * @param onOpenSession [rememberSessionListOpener] by default (needs a
 *   `LocalNavStack`); the gallery and previews pass their own.
 * @param leadingItems items before everything else (50's connection
 *   line) — the caller's own keys and types; they do not count for the
 *   empty/status derivation.
 * @param refreshWrapper ticket 48's pull-to-refresh container slot.
 * @param backdrop ticket 46's wallpaper, behind the clear list.
 * @param reorder ticket 47's Pinned reorder hookup (the gallery and the
 *   ticket's hosts); `null` for every other list.
 */
@Composable
fun SessionList(
    model: SessionListModel,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    onOpenSession: (String) -> Unit = rememberSessionListOpener(),
    onOpenFolder: (String) -> Unit = {},
    onToggleSection: (String) -> Unit = {},
    archivedRows: Boolean = false,
    archivedRow: (chatId: String) -> Boolean = { archivedRows },
    reorderable: Boolean = false,
    editing: Boolean = false,
    currentChatId: String? = null,
    rowWrapper: SessionRowWrapper = PassThroughRowWrapper,
    headerWrapper: SectionHeaderWrapper = PassThroughHeaderWrapper,
    reorder: SessionListReorder? = null,
    leadingItems: LazyListScope.() -> Unit = {},
    refreshWrapper: @Composable (list: @Composable () -> Unit) -> Unit = { it() },
    backdrop: @Composable BoxScope.() -> Unit = {},
) {
    val palette = RobocoTheme.palette
    val openSessionState = rememberUpdatedState(onOpenSession)
    val openFolderState = rememberUpdatedState(onOpenFolder)
    val toggleSectionState = rememberUpdatedState(onToggleSection)
    // Stable proxies: remembered once, reading the State at click time.
    val openSessionClick = remember { { id: String -> openSessionState.value(id) } }
    val openFolderClick = remember { { id: String -> openFolderState.value(id) } }
    val toggleSectionClick = remember { { id: String -> toggleSectionState.value(id) } }

    Box(modifier.fillMaxSize().background(palette.background)) {
        backdrop()
        SessionListStatusOverlay(model.status, safePadding = contentPadding)
        refreshWrapper {
            LazyColumn(
                state = state,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                leadingItems()
                sessionListItems(
                    model = model,
                    onOpenSession = openSessionClick,
                    onOpenFolder = openFolderClick,
                    onToggleSection = toggleSectionClick,
                    archivedRows = archivedRows,
                    archivedRow = archivedRow,
                    reorderable = reorderable,
                    editing = editing,
                    currentChatId = currentChatId,
                    rowWrapper = rowWrapper,
                    headerWrapper = headerWrapper,
                    reorder = reorder,
                )
            }
        }
    }
}
