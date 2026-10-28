package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.GlassTextCapsule
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.SessionRow

/**
 * The "Pinned reorder" page of the debug design gallery
 * (android-ui-parity #47): a fake **two-engine** pinned list in 41's
 * standalone [SessionList] — engine A carries three of ticket 37's
 * fixture rows, engine B two (the PR-badge row among them) — with the
 * Edit/Done [GlassTextCapsule] exactly as the Pinned folder screen calls
 * it, the drag handles, the engine-block clamp, the auto-scroll and the
 * lift look. The readout line shows the last dispatched [PinMove]
 * (`move <id> after=<id> before=<id>`); "Fail next" makes the dispatch
 * answer [ActionOutcome.EngineOffline] with the ticket 42 toast, so the
 * snap-back is visible.
 *
 * Registered in `design/Gallery.kt` as
 * `GallerySection("Pinned reorder", GalleryLayout.Fill, screenshotHeightDp = 852) { PinnedReorderGallerySection() }`.
 * Fill: the section owns the LazyColumn. No Material controls: plain
 * `BasicText` + `clickable` rows and 08's glass capsule.
 */
@Composable
fun PinnedReorderGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val toaster = LocalToaster.current

    var editing by remember { mutableStateOf(false) }
    var failNext by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("no move yet") }

    // The fixture: ticket 37's rows, two engines. Engine A (the merge
    // order puts the paired engine first) is 37's first three Recent
    // rows; engine B is the PR-badge row and the launch post, re-idd to
    // engine B — the clamp keeps the two blocks apart.
    val rows = remember { pinnedReorderGalleryRows() }
    val listState = rememberLazyListState()
    val engineById = remember(rows) { rows.associate { r -> r.id to r.engineId } }
    val keys = remember(rows) { rows.map { r -> PinKey(r.id, r.engineId) } }
    val reorderState = remember { PinReorderState(keys) }
    LaunchedEffect(keys) { reorderState.adopt(keys) }
    val lazyState = rememberReorderableLazyListState(
        lazyListState = listState,
        // The same padding the gallery's host list gets: the safe padding
        // mimics the front page's (155 top / 110 bottom, 41's worked
        // example), so the auto-scroll starts at the visible edges.
        scrollThresholdPadding = PaddingValues(top = 155.dp, bottom = 110.dp),
    ) { from, to -> reorderState.move(from.key, to.key) }
    val actions = remember(toaster) {
        PinnedReorderGalleryActions(toaster, { failNext }) { mv -> result = mv.toString() }
    }
    val reorder = SessionListReorder(
        lazyState = lazyState,
        state = reorderState,
        engineOf = { id -> engineById.getValue(id) },
        dispatch = { mv, onResult -> actions.movePin(mv.id, mv.after, mv.before, onResult) },
    )
    val model = rememberSessionListModel(
        sections = listOf(ListSection(id = "pinned", header = null, rows = rows)),
        collapsible = false,
    )

    Column(Modifier.fillMaxSize().background(palette.background)) {
        GlassTextCapsule(
            text = if (editing) "Done" else "Edit",
            emphasized = editing,
            onClick = { editing = !editing },
            modifier = Modifier.padding(start = 16.dp, top = 8.dp).testTag("pinned-edit"),
        )
        PinnedReorderToggleRow(
            if (failNext) "Fail next drop: on" else "Fail next drop: off",
            failNext,
        ) { failNext = !failNext }
        BasicText(
            result,
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.tertiary),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SessionList(
                model = model,
                state = listState,
                contentPadding = PaddingValues(top = 155.dp, bottom = 110.dp),
                onOpenSession = { },
                reorderable = true,
                editing = editing,
                reorder = reorder,
            )
        }
    }
}

/** The gallery's two-engine fixture: 37's rows, the last two re-idd to engine B. */
private fun pinnedReorderGalleryRows(): List<SessionRow> = listOf(
    SessionListFixtures.recentRows[0],
    SessionListFixtures.recentRows[1],
    SessionListFixtures.recentRows[2],
    SessionListFixtures.currentRow.copy(engineId = "B", deviceId = "device-B", pinned = true),
    SessionListFixtures.recentRows[3].copy(engineId = "B", deviceId = "device-B", pinned = true),
)

/** A toggle row: the label in `accent` when on, `secondary` when off. */
@Composable
private fun PinnedReorderToggleRow(text: String, checked: Boolean, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(
            Face.SansMedium,
            typeScale.fixed(13f),
            if (checked) palette.accent else palette.secondary,
        ),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp, end = 16.dp),
    )
}

/**
 * The gallery's [RowActions] fake: only `movePin` matters — it logs the
 * move to the readout line and answers [ActionOutcome.Ok], or, when
 * "Fail next" is on, shows ticket 42's "Engine not connected" toast and
 * answers [ActionOutcome.EngineOffline] so the local order snaps back.
 */
private class PinnedReorderGalleryActions(
    private val toaster: Toaster,
    private val failNext: () -> Boolean,
    private val onMove: (PinMove) -> Unit,
) : RowActions {

    override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
        val mv = PinMove(chatId, after, before)
        onMove(mv)
        if (failNext()) {
            toaster.show(SessionPromptCopy.EngineNotConnected)
            onResult(ActionOutcome.EngineOffline)
        } else {
            onResult(ActionOutcome.Ok)
        }
    }

    override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {}
    override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {}
    override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {}
    override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {}
    override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {}
    override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {}
    override fun createSection(
        name: String,
        engineId: String?,
        onResult: (ActionOutcome) -> Unit,
        onCreated: (sectionId: String) -> Unit,
    ) {}
    override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {}
    override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {}
    override fun sectionsFor(engineId: String): List<SectionView> = emptyList()
    override fun report(error: Throwable) {}
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Pinned reorder – light", widthDp = 393, heightDp = 852)
@Composable
private fun PinnedReorderLightPreview() {
    RobocoTheme(Appearance.Light) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            PinnedReorderGallerySection()
        }
    }
}

@Preview(name = "Pinned reorder – dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PinnedReorderDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            PinnedReorderGallerySection()
        }
    }
}
