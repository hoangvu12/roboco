package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.SessionRow as CoreSessionRow
import uniffi.roboco_core.SectionView

/**
 * The "Swipe rows" page of the debug design gallery (android-ui-parity
 * #44), registered by ticket 22's registry as
 * `GallerySection("Swipe rows", GalleryLayout.Scroll, screenshotHeightDp = 891) {
 * ActionSheetHost { SwipeRowGallerySection() } }` (the ActionSheetHost so
 * 43's sheets have a host if a live row's Move ever runs the real prompt).
 *
 * **Live** (one shared [SwipeCoordinator] + [Modifier.swipeDismissOnTouch]
 * on the plain `Column` — the section is `Scroll`, not a `LazyColumn`):
 * five 62 dp rows through the real row wrapper and ticket 37's row: an
 * unpinned row, a pinned row, a long title, an Archived-list row and a
 * "engine refused" row whose fake `archive` reports refusal 300 ms late —
 * the destructive slide-out, then the spring back. A fake
 * [SessionRowCommands] appends to the log line ("archive s5", …); taps
 * log "open …".
 *
 * **Static** ([SwipeRowStaticBlock], one coordinator per row,
 * `previewOffset`): closed; leading open (74 dp); trailing open (148 dp);
 * trailing at 50 % (equal 50/50 split, not armed); trailing armed at
 * 70 % (Archive covers the gap); archived open (74 dp). The static block
 * is also the Roborazzi golden and the two `@Preview`s.
 */
@Composable
fun SwipeRowGallerySection() {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("—") }
    val cmd = remember(scope) {
        SwipeGalleryCommands(onLog = { log = it }, refuseArchive = "s5", scope = scope)
    }
    val coordinator = rememberSwipeCoordinator()
    val wrapper = remember(coordinator, cmd) { sessionRowWrapper(coordinator, cmd) }

    Column(Modifier.fillMaxWidth().swipeDismissOnTouch(coordinator)) {
        Caption("Live — swipe a row, long-press it, tap a pane; last event:")
        Caption(log)
        Spacer(Modifier.height(12.dp))
        liveRows.forEach { ctx ->
            LiveRow(ctx, wrapper, onOpen = { log = "open ${ctx.vm.id}" })
        }
        Spacer(Modifier.height(24.dp))
        Caption("Static — the golden block (closed, 74, 148, 50 %, 70 %, archived):")
        SwipeRowStaticBlock()
    }
}

/**
 * The static block: six fixed [SwipeRow]s, each with its own coordinator
 * and a signed `previewOffset` (positive = leading revealed, negative =
 * trailing), no gestures. Composed at a fixed 360 dp width so the 60 %
 * arming rule lands on 180 dp / 252 dp exactly — the Roborazzi golden
 * (`sessions/swipe_rows_light.png` / `_dark.png`) and the previews.
 */
@Composable
internal fun SwipeRowStaticBlock(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val cmd = remember { SwipeGalleryCommands(onLog = { }) }
    Column(
        modifier
            .width(360.dp)
            .background(palette.background),
    ) {
        Caption("Closed")
        StaticRow(ffRow("t1", "Tool group header colors"), offset = null, cmd = cmd)
        Caption("Leading open — 74 dp, Pin (Unpin when pinned)")
        StaticRow(ffRow("t2", "Durable Object hibernation flush", pinned = true), offset = 74.dp, cmd = cmd)
        Caption("Trailing open — 148 dp: Archive at the edge, Move inside")
        StaticRow(ffRow("t3", "多言語テキストのレイアウト"), offset = (-148).dp, cmd = cmd)
        Caption("Trailing at 50 % — the equal 50/50 split, not armed")
        StaticRow(ffRow("t4", "Keyboard avoidance for composer"), offset = (-180).dp, cmd = cmd)
        Caption("Trailing armed at 70 % — Archive covers the gap")
        StaticRow(ffRow("t5", "Wrangler deploy hygiene"), offset = (-252).dp, cmd = cmd)
        Caption("Archived list — 74 dp, Unarchive only")
        StaticRow(ffRow("t6", "Clean up ~/Downloads"), offset = (-74).dp, cmd = cmd, archived = true)
    }
}

// ---------------------------------------------------------------------------
// The rows
// ---------------------------------------------------------------------------

/** One live row: the real wrapper (swipe + context menu) around 37's row. */
@Composable
private fun LiveRow(ctx: SessionRowContext, wrapper: SessionRowWrapper, onOpen: () -> Unit) {
    val height = LocalTypeScale.current.scaledDp(62f)
    Box(Modifier.fillMaxWidth().height(height)) {
        with(PlainItemScope) {
            wrapper(ctx) { m ->
                val src = remember { MutableInteractionSource() }
                SessionRow(
                    vm = ctx.vm,
                    modifier = m
                        .fillMaxSize()
                        .clickable(interactionSource = src, indication = null, role = Role.Button) { onOpen() },
                )
            }
        }
    }
}

/** One static row: [SwipeRow] with `previewOffset`, plain content. */
@Composable
private fun StaticRow(row: CoreSessionRow, offset: Dp?, cmd: SwipeGalleryCommands, archived: Boolean = false) {
    val palette = RobocoTheme.palette
    val height = LocalTypeScale.current.scaledDp(62f)
    val vm = remember(row) { SessionRowVM.from(row, homeColorIndex = 6) }
    val res = LocalContext.current.resources
    val strings: (Int) -> String = { id -> res.getString(id) }
    val (lead, trail) = remember(archived, vm.pinned, palette) {
        sessionSwipeActions({ row }, { vm }, archived, cmd, palette.accent, palette.secondary, strings)
    }
    Box(Modifier.fillMaxWidth().height(height)) {
        SwipeRow(id = row.id, leading = lead, trailing = trail, previewOffset = offset) {
            SessionRow(vm = vm)
        }
    }
}

// ---------------------------------------------------------------------------
// The fake commands and fixtures
// ---------------------------------------------------------------------------

/** Row caption: mono 11 pt fixed in `tertiary`, the gallery's row-caption pattern. */
@Composable
private fun Caption(text: String) {
    BasicText(
        text,
        style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.tertiary),
    )
}

/**
 * The gallery's [SessionRowCommands]: every call appends to the log, the
 * archive refusal is 300 ms late (like 42's IO-queue outcome), and the
 * sections list two sections on the row's engine.
 */
private class SwipeGalleryCommands(
    private val onLog: (String) -> Unit,
    private val refuseArchive: String? = null,
    private val scope: CoroutineScope? = null,
) : SessionRowCommands {

    override fun setPinned(chatId: String, pinned: Boolean) {
        onLog(if (pinned) "pin $chatId" else "unpin $chatId")
    }

    override fun archive(chatId: String, onAccepted: (Boolean) -> Unit) {
        onLog("archive $chatId")
        if (chatId == refuseArchive) {
            scope?.launch {
                delay(300)
                onAccepted(false)
            }
        } else {
            onAccepted(true)
        }
    }

    override fun unarchive(chatId: String) {
        onLog("unarchive $chatId")
    }

    override fun move(chatId: String, sectionId: String?) {
        onLog("move $chatId=${sectionId ?: "none"}")
    }

    override fun moveToSectionSheet(row: CoreSessionRow) {
        onLog("move sheet ${row.id}")
    }

    override fun renameSession(row: CoreSessionRow) {
        onLog("rename ${row.id}")
    }

    override fun deleteSession(row: CoreSessionRow) {
        onLog("delete ${row.id}")
    }

    override fun newSection(engineId: String?) {
        onLog("newSection engine=${engineId ?: "none"}")
    }

    override fun sectionsFor(engineId: String): List<SectionView> = listOf(
        SectionView(id = "work", engineId = engineId, name = "Work", collapsed = false, sessions = emptyList()),
        SectionView(id = "home", engineId = engineId, name = "Home", collapsed = false, sessions = emptyList()),
    )
}

/**
 * A `LazyItemScope` for hosts that are not a `LazyColumn` (the gallery's
 * plain `Column`, the tests): the wrapper never touches the parent-size
 * helpers, and `animateItem` is 41's, not the wrapper's.
 */
internal object PlainItemScope : LazyItemScope {
    override fun Modifier.fillParentMaxSize(fraction: Float): Modifier = this

    override fun Modifier.fillParentMaxWidth(fraction: Float): Modifier = this

    override fun Modifier.fillParentMaxHeight(fraction: Float): Modifier = this
}

/** One gallery row: the FFI record + its per-row archived flag. */
private fun galleryCtx(row: CoreSessionRow, archived: Boolean): SessionRowContext = SessionRowContext(
    row = row,
    vm = SessionRowVM.from(row, homeColorIndex = 6),
    sectionId = if (archived) "archived" else "recent",
    archived = archived,
    reorderable = false,
    editing = false,
    isCurrent = false,
)

private val liveRows: List<SessionRowContext> = listOf(
    galleryCtx(ffRow("s1", "Swipe me both ways — Pin on the left"), archived = false),
    galleryCtx(ffRow("s2", "Already pinned — the left pane says Unpin", pinned = true), archived = false),
    galleryCtx(ffRow("s3", "A very long session title that keeps on going well past the corner word"), archived = false),
    galleryCtx(ffRow("s4", "Archived-list row — left swipe only, Unarchive"), archived = true),
    galleryCtx(ffRow("s5", "Engine offline — Archive slides out, then springs back"), archived = false),
)

private fun ffRow(
    id: String,
    title: String,
    pinned: Boolean = false,
    engineId: String = "eng-A",
): CoreSessionRow = CoreSessionRow(
    id = id,
    engineId = engineId,
    revision = 1uL,
    title = title,
    hasTitle = true,
    preview = null,
    project = null,
    deviceId = "device-A",
    deviceName = "MacBook Pro",
    deviceOnline = true,
    engineOnline = true,
    harness = "codex",
    harnessLabel = null,
    model = null,
    modelLabel = null,
    reasoning = null,
    branch = "main",
    cwd = null,
    indicator = ChatIndicator.IDLE,
    hostIndicator = ChatIndicator.IDLE,
    workingSinceMs = null,
    lastActivityMs = 0L,
    timeLabel = "2h",
    createdAtMs = 0L,
    unseen = false,
    archived = false,
    pinned = pinned,
    sectionId = null,
    pullRequest = null,
    sendState = null,
    parentChatId = null,
)

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Swipe rows – Light", widthDp = 360)
@Composable
private fun SwipeRowStaticLightPreview() {
    RobocoTheme(Appearance.Light) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            SwipeRowStaticBlock()
        }
    }
}

@Preview(name = "Swipe rows – Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SwipeRowStaticDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            SwipeRowStaticBlock()
        }
    }
}
