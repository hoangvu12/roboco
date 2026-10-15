package sh.roboco.android.sidechats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.FadingText
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.sheet.GlassSheet
import sh.roboco.android.design.sheet.GlassSheetDefaults
import sh.roboco.android.design.sheet.SheetDetent
import sh.roboco.android.design.sheet.rememberGlassSheetState
import sh.roboco.android.design.uiStyle
import sh.roboco.android.sessions.SectionHeaderRow
import sh.roboco.android.sessions.SectionHeaderState
import sh.roboco.android.sessions.SectionLive
import sh.roboco.android.sessions.SessionRow
import sh.roboco.android.sessions.rememberRowHighlight

/** The sheet's three foldable sections, in order (upstream #899's `Section`). */
enum class SideChatsSection(val id: String, val title: String) {
    Running("running", "Running"),
    Chats("chats", "Side chats"),
    Finished("finished", "Finished agents"),
}

/**
 * Which sections are folded, remembered across sheets for the process
 * (iOS keeps the set in `UserDefaults`; Roboco keeps it in memory — a
 * relaunch unfolds everything). Snapshot state, main thread only.
 */
object SideChatsFolds {
    private val folded = mutableStateMapOf<SideChatsSection, Boolean>()

    fun isFolded(section: SideChatsSection): Boolean = folded[section] == true

    fun toggle(section: SideChatsSection) {
        folded[section] = !isFolded(section)
    }

    /** Unfold everything (tests: the memory is process-wide). */
    internal fun clear() {
        folded.clear()
    }
}

/**
 * Whether the Side Chats & Agents sheet shows, plus the one action that
 * waits for its dismissal (iOS `dismiss(animated:) { onNew() }`: the new
 * side chat and the fork start once the sheet is gone). Main thread only.
 */
@Stable
class SideChatsSheetController {
    var showing by mutableStateOf(false)
        private set

    private var afterDismiss: (() -> Unit)? = null

    fun open() {
        showing = true
    }

    /** Run [action] once the sheet has animated away. */
    internal fun dismissThen(action: () -> Unit) {
        afterDismiss = action
    }

    /** `GlassSheet`'s `onDismiss`: the sheet reached `Hidden`. */
    internal fun onDismissed() {
        showing = false
        val action = afterDismiss
        afterDismiss = null
        action?.invoke()
    }
}

/**
 * The Side Chats & Agents sheet (android-next #06, the port of upstream
 * #899's `SideChatsViewController`): everything hanging off one session —
 * the subagents its agent is running (live tail and model), its side
 * chats (forked, started by hand or spawned through the MCP server), and
 * the subagents that finished — in three foldable sections. Picking a row
 * opens it on top of the session; the header's + offers New Side Chat and
 * Fork into Side Chat; ✓ dismisses; the sheet closes itself once nothing
 * is left in it.
 *
 * A `GlassSheet` at `[Medium, Large]` with a grabber. Host it as the
 * **last child of the session screen** so it covers the header, the
 * transcript and the bottom stack.
 *
 * @param canAdd whether the + shows: the engine serves side chats and is
 *   writable
 */
@Composable
fun SideChatsOverlay(
    controller: SideChatsSheetController,
    ui: SideChatsUi,
    canAdd: Boolean,
    onOpenChat: (chatId: String) -> Unit,
    onOpenAgent: (SideChatsAgent) -> Unit,
    onNew: () -> Unit,
    onFork: () -> Unit,
) {
    if (!controller.showing) return
    val detents = remember { listOf(SheetDetent.Medium, SheetDetent.Large) }
    GlassSheet(
        onDismiss = { controller.onDismissed() },
        modifier = Modifier.testTag("side-chats-sheet"),
        state = rememberGlassSheetState(detents),
        grabber = true,
        title = SideChatsCopy.Title,
        header = {
            SideChatsHeader(
                canAdd = canAdd,
                onNew = {
                    controller.dismissThen(onNew)
                    dismiss()
                },
                onFork = {
                    controller.dismissThen(onFork)
                    dismiss()
                },
                onDone = { dismiss() },
            )
        },
    ) { bottomInset ->
        // iOS `reload`: an emptied sheet (the last side chat archived)
        // closes itself.
        val empty = ui.total == 0
        LaunchedEffect(empty) { if (empty) dismiss() }
        SideChatsList(
            ui = ui,
            bottomInset = bottomInset.value,
            onOpenChat = { id ->
                onOpenChat(id)
                dismiss()
            },
            onOpenAgent = { agent ->
                onOpenAgent(agent)
                dismiss()
            },
        )
    }
}

/** The sheet's copy, verbatim from upstream #899. */
object SideChatsCopy {
    const val Title = "Side Chats & Agents"
    const val NewSideChat = "New Side Chat"
    const val ForkIntoSideChat = "Fork into Side Chat"
    const val AddLabel = "Add side chat"
}

/** The + menu (iOS `UIMenu` on the leading bar item). Pure. */
fun sideChatsAddMenu(onNew: () -> Unit, onFork: () -> Unit): Menu = Menu(
    title = null,
    children = listOf(
        MenuItem(
            title = SideChatsCopy.NewSideChat,
            icon = MenuIcon.Symbol(RobocoIcons.QuestionmarkBubble),
            testTag = "side-chats-new",
            action = onNew,
        ),
        MenuItem(
            title = SideChatsCopy.ForkIntoSideChat,
            icon = MenuIcon.Symbol(RobocoIcons.ArrowTriangleBranch),
            testTag = "side-chats-fork",
            action = onFork,
        ),
    ),
)

/**
 * The sheet's nav bar: [SheetHeaderRow][sh.roboco.android.design.sheet.SheetHeaderRow]'s
 * geometry (52 dp, 16 dp insets, 44 dp glass circles, the centred
 * SemiBold 17 title) with the + menu leading and the accent ✓ trailing.
 */
@Composable
private fun SideChatsHeader(
    canAdd: Boolean,
    onNew: () -> Unit,
    onFork: () -> Unit,
    onDone: () -> Unit,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val menuState = rememberGlassMenuState()
    // Written during layout, read only in the click lambda.
    var addBounds by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxWidth().height(GlassSheetDefaults.HeaderHeight)) {
        BasicText(
            SideChatsCopy.Title,
            Modifier
                .align(Alignment.Center)
                .semantics { heading() },
            style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (canAdd) {
            GlassCircleButton(
                icon = RobocoIcons.Plus,
                contentDescription = SideChatsCopy.AddLabel,
                onClick = { menuState.open(addBounds, sideChatsAddMenu(onNew, onFork)) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .onGloballyPositioned { addBounds = it.boundsInWindow() }
                    .testTag("side-chats-add"),
            )
        }
        GlassCircleButton(
            icon = RobocoIcons.Checkmark,
            contentDescription = "Done",
            onClick = onDone,
            filled = palette.accent,
            ink = Color.White,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .testTag("sheet-done"),
        )
    }
}

/**
 * The three sections (iOS's diffable snapshot): a header row each — the
 * count over all its rows, folded or not; Running keeps its spinner while
 * folded — then its rows unless folded. Empty sections are left out.
 */
@Composable
internal fun SideChatsList(
    ui: SideChatsUi,
    bottomInset: Float,
    onOpenChat: (String) -> Unit,
    onOpenAgent: (SideChatsAgent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val openChat by rememberUpdatedState(onOpenChat)
    val openAgent by rememberUpdatedState(onOpenAgent)
    LazyColumn(
        modifier.fillMaxSize().testTag("side-chats-list"),
        contentPadding = PaddingValues(bottom = (bottomInset + 16f).dp),
    ) {
        section(SideChatsSection.Running, ui.running.size, live = true) {
            items(ui.running, key = { "agent:${it.docId}" }) { agent ->
                SubagentRowView(agent, onClick = { openAgent(agent) })
            }
        }
        section(SideChatsSection.Chats, ui.chats.size, live = false) {
            items(ui.chats, key = { "chat:${it.id}" }) { vm ->
                val src = remember { MutableInteractionSource() }
                val highlight = rememberRowHighlight(src)
                SessionRow(
                    vm,
                    modifier = Modifier.clickable(src, indication = null, role = Role.Button) { openChat(vm.id) },
                    highlight = { highlight.value },
                )
            }
        }
        section(SideChatsSection.Finished, ui.finished.size, live = false) {
            items(ui.finished, key = { "agent:${it.docId}" }) { agent ->
                SubagentRowView(agent, onClick = { openAgent(agent) })
            }
        }
    }
}

/** One section: its header (a tap folds it) and, unfolded, its rows. */
private fun LazyListScope.section(
    section: SideChatsSection,
    count: Int,
    live: Boolean,
    rows: LazyListScope.() -> Unit,
) {
    if (count == 0) return
    val folded = SideChatsFolds.isFolded(section)
    item(key = "header:${section.id}") {
        val src = remember { MutableInteractionSource() }
        val highlight = rememberRowHighlight(src, fadeOnRelease = false)
        val feedback = LocalFeedback.current
        SectionHeaderRow(
            SectionHeaderState(
                id = "side-${section.id}",
                title = section.title,
                count = count,
                collapsed = folded,
                live = if (live) SectionLive.Spinner else null,
            ),
            highlight = { highlight.value },
            modifier = Modifier.clickable(src, indication = null, role = Role.Button) {
                feedback.haptic(Haptic.Select) // iOS `toggle`: UISelectionFeedbackGenerator
                SideChatsFolds.toggle(section)
            },
        )
    }
    if (!folded) rows()
}

/** `now` / `34m` / `4h` / `2d` (`mc::relative_time_label`, pure Kotlin so the sheet stays FFI-free). */
internal fun relativeAgentTime(atMs: Long, nowMs: Long): String {
    val secs = (nowMs - atMs).coerceAtLeast(0L) / 1000L
    return when {
        secs < 60L -> "now"
        secs < 3_600L -> "${secs / 60L}m"
        secs < 86_400L -> "${secs / 3_600L}h"
        else -> "${secs / 86_400L}d"
    }
}

/** A subagent row's spoken label (iOS `SubagentCell.configure`). Pure. */
internal fun subagentRowA11yLabel(agent: SideChatsAgent): String {
    val word = when (agent.state) {
        AgentState.Running -> "Running"
        AgentState.Done -> "Done"
        AgentState.Failed -> "Failed"
    }
    return listOfNotNull(
        "Subagent",
        agent.title,
        word,
        agent.model?.let(::capitalizedModel),
        if (agent.running) agent.tail else null,
    ).joinToString(", ")
}

/** "haiku" → "Haiku" (iOS `prefix(1).uppercased() + dropFirst()`). */
internal fun capitalizedModel(model: String): String =
    if (model.isEmpty()) model else model.substring(0, 1).uppercase() + model.substring(1)

/**
 * A subagent row (the port of `SubagentCell`): its status glyph stands
 * where a side chat shows its harness mark, so the two read apart —
 *
 * ```
 * ⠿  Audit the reduced-motion fallback ··········· Haiku
 *    Reading RowView.swift…                (running: live tail)
 * ✓  Check CJK line breaking ······················ 12m
 * ```
 *
 * 62k tall while running (title at 10k, tail at 35k), 46k once finished
 * (the title centred). The pressed fill is the session row's: controlFill,
 * 16 dp continuous corners, inset 8 × 1.
 */
@Composable
internal fun SubagentRowView(
    agent: SideChatsAgent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val running = agent.running
    val glyph = when (agent.state) {
        AgentState.Running -> StatusGlyphKind.Spinner
        AgentState.Done -> StatusGlyphKind.Check(palette.status.done)
        AgentState.Failed -> StatusGlyphKind.Dot(palette.status.failed)
    }
    // Running: which model it runs on. Finished: when it was spawned.
    val trailing = if (running) agent.model?.let(::capitalizedModel) else relativeAgentTime(agent.spawnedAtMs, nowMs)
    val trailingColor = when {
        running -> palette.secondary
        agent.state == AgentState.Failed -> palette.status.failed
        else -> palette.status.time
    }
    val src = remember { MutableInteractionSource() }
    val highlight = rememberRowHighlight(src)
    val shape = rememberSmoothRoundedShape(16.dp)
    val titleH = ts.scaledDp(22f)
    Box(
        modifier
            .fillMaxWidth()
            .height(ts.scaledDp(if (running) 62f else 46f))
            .drawWithCache {
                val inset = 8.dp.toPx()
                val vInset = 1.dp.toPx()
                val outline = shape.createOutline(
                    Size(size.width - 2f * inset, size.height - 2f * vInset),
                    layoutDirection,
                    this,
                )
                onDrawBehind {
                    val h = highlight.value.coerceIn(0f, 1f)
                    if (h > 0f) translate(inset, vInset) { drawOutline(outline, palette.controlFill, alpha = h) }
                }
            }
            .clickable(src, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                testTag = "subagent-${agent.docId}"
                contentDescription = subagentRowA11yLabel(agent)
            },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .align(if (running) Alignment.TopStart else Alignment.CenterStart)
                .padding(top = if (running) ts.scaledDp(10f) else 0.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(titleH), verticalAlignment = Alignment.CenterVertically) {
                // The 20 dp mark column, the 12 dp glyph centred in it.
                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp)) { StatusGlyph(glyph) }
                }
                Spacer(Modifier.width(14.dp))
                FadingText(
                    agent.title,
                    uiStyle(
                        Face.SansMedium,
                        ts.scaled(if (running) 16.5f else 15.5f),
                        if (running) palette.text else palette.text.copy(alpha = 0.88f),
                    ),
                    Modifier.weight(1f),
                )
                if (trailing != null) {
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        trailing,
                        style = uiStyle(Face.SansMedium, ts.scaled(13f), trailingColor).copy(textAlign = TextAlign.End),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val tail = agent.tail
            if (running && tail != null) {
                FadingText(
                    tail,
                    uiStyle(Face.Sans, ts.scaled(13.5f), palette.secondary),
                    Modifier
                        .padding(start = 34.dp, top = ts.scaledDp(3f))
                        .height(ts.scaledDp(18f)),
                )
            }
        }
    }
}
