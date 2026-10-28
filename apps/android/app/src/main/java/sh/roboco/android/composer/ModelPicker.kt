package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.HarnessMark
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.sheet.GlassSheet
import sh.roboco.android.design.sheet.SheetDetent
import sh.roboco.android.design.sheet.rememberGlassSheetState
import sh.roboco.android.design.uiStyle

/**
 * The phone's tabbed model picker (wave-10 38, upstream #966's
 * `Session/ModelPickerViewController.swift`, behaviour spec): a glass
 * sheet over the session whose left-aligned "Model" title says where the
 * chat runs; with more than one provider a switcher (brand mark + name)
 * sits over swipeable pages, the current provider first. Models sit on
 * the sheet — no cards, no rules — the current one in a soft accent tint
 * with an accent check; loading shows soft bars; a list longer than
 * [MODEL_SEARCH_THRESHOLD] gets a search field ("Search <agent> models",
 * "No matches"). A pick lands, the sheet closes a beat later.
 *
 * The content is caller-provided ([ModelPickerRequest]): ticket 38 offers
 * the chat's own provider; ticket 39 adds the others when the host can
 * switch provider, with their notes.
 */

/** One provider's page: its tab and what picking there means. */
data class ModelPickerTab(
    val harness: String,
    val label: String,
    /** A notice over this page's list ("Your next message continues this chat with Codex."). */
    val footer: String? = null,
)

/** One model row: its id, name and what it's for. */
data class ModelPickerRow(
    val id: String,
    val label: String,
    val detail: String?,
)

/** Everything one opening of the picker needs; built at tap time. */
class ModelPickerRequest(
    val tabs: List<ModelPickerTab>,
    /** The chat's provider: its page opens first. */
    val harness: String,
    /** The chat's model (ticked), `null` when unknown. */
    val model: String?,
    /** Where the chat runs (the computer's name). */
    val hostName: String,
    /** A notice over the chat's own provider page. */
    val note: String? = null,
    /** One provider's models (loaded when the picker opens; never throws). */
    val load: suspend (harness: String) -> List<ModelPickerRow>,
    /** A pick: the provider page and the row. */
    val onPick: (harness: String, row: ModelPickerRow) -> Unit,
)

/** Who shows the picker (the session screen's [ModelPickerController]). */
fun interface ModelPickerPresenter {
    fun present(request: ModelPickerRequest)
}

/**
 * Whether the picker shows, and with what. Host [ModelPickerOverlay] over
 * the session screen; [present] from a tap (main thread).
 */
@Stable
class ModelPickerController : ModelPickerPresenter {
    var request: ModelPickerRequest? by mutableStateOf(null)
        private set

    override fun present(request: ModelPickerRequest) {
        this.request = request
    }

    /** The sheet reached `Hidden`. */
    internal fun onDismissed() {
        request = null
    }
}

/** The picker's copy (upstream's, verbatim). */
object ModelPickerCopy {
    const val Title = "Model"
    const val NoModels = "No models available"
    const val NoMatches = "No matches"
    const val Close = "Close"

    fun searchPlaceholder(label: String) = "Search $label models"
}

/** A list longer than this gets a search field (OpenCode can offer hundreds). */
const val MODEL_SEARCH_THRESHOLD = 12

/** One row per model id, first wins (a catalog can repeat one). Pure. */
fun dedupeModels(rows: List<ModelPickerRow>): List<ModelPickerRow> = rows.distinctBy { it.id }

/** Rows matching the trimmed, case-folded query in their name or id. Pure. */
fun filterModels(rows: List<ModelPickerRow>, query: String): List<ModelPickerRow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return rows
    return rows.filter { it.label.lowercase().contains(q) || it.id.lowercase().contains(q) }
}

/** Whether a page shows its search field. Pure. */
fun showsModelSearch(rows: List<ModelPickerRow>): Boolean = rows.size > MODEL_SEARCH_THRESHOLD

/** An empty page's line: nothing offered, or nothing matching. Pure. */
fun modelEmptyText(query: String): String =
    if (query.isBlank()) ModelPickerCopy.NoModels else ModelPickerCopy.NoMatches

/** The switcher's segment title — short on room: "Claude", not "Claude Code". Pure. */
fun switcherTitle(label: String): String = if (label == "Claude Code") "Claude" else label

/** The line under "Model": the provider and the host with one tab, the host with several. Pure. */
fun pickerSubtitle(tabs: List<ModelPickerTab>, hostName: String): String {
    val only = tabs.singleOrNull() ?: return hostName
    return "${only.label} · $hostName"
}

/** The page that opens first: the chat's provider, else the first. Pure. */
fun initialPickerPage(tabs: List<ModelPickerTab>, harness: String): Int =
    tabs.indexOfFirst { it.harness == harness }.coerceAtLeast(0)

/** A page's notice: its own footer, else the request's note on the chat's own page. Pure. */
fun pickerPageNotice(tab: ModelPickerTab, page: Int, initial: Int, note: String?): String? =
    tab.footer ?: if (page == initial) note else null

/** The picker over the session screen while [controller] holds a request. */
@Composable
fun ModelPickerOverlay(controller: ModelPickerController) {
    val request = controller.request ?: return
    key(request) {
        ModelPickerSheet(request, onDismissed = { controller.onDismissed() })
    }
}

@Composable
private fun ModelPickerSheet(request: ModelPickerRequest, onDismissed: () -> Unit) {
    val detents = remember { listOf(SheetDetent.Medium, SheetDetent.Large) }
    val initial = remember(request) { initialPickerPage(request.tabs, request.harness) }
    val pager = rememberPagerState(initialPage = initial, pageCount = { request.tabs.size })
    var current by remember(request) { mutableStateOf(request.harness to request.model) }
    var picked by remember(request) { mutableStateOf(false) }
    val rows = remember(request) { mutableStateMapOf<String, List<ModelPickerRow>>() }
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current

    // The current provider first, then the rest, so a swipe lands on a
    // loaded list.
    LaunchedEffect(request) {
        val order = listOf(initial) + request.tabs.indices.filter { it != initial }
        for (ix in order) {
            val tab = request.tabs[ix]
            launch {
                val loaded = try {
                    request.load(tab.harness)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    emptyList()
                }
                rows[tab.harness] = dedupeModels(loaded)
            }
        }
    }

    GlassSheet(
        onDismiss = onDismissed,
        modifier = Modifier.testTag("model-picker"),
        state = rememberGlassSheetState(detents),
        grabber = true,
        title = ModelPickerCopy.Title,
        // The close circle is glass: it sits after the sheet's haze source
        // (GlassSheet's rule), level with the title row in the content.
        glass = {
            val sheet = this
            Box(Modifier.fillMaxSize()) {
                GlassCircleButton(
                    icon = RobocoIcons.Xmark,
                    contentDescription = ModelPickerCopy.Close,
                    onClick = { sheet.dismiss() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = ModelPickerHeaderMetrics.CloseTop, end = ModelPickerHeaderMetrics.EndPadding)
                        .testTag("model-picker-close"),
                    size = ModelPickerHeaderMetrics.CloseSize,
                    iconPt = 13f,
                    weight = SymbolWeight.Semibold,
                    ink = RobocoTheme.palette.secondary,
                )
            }
        },
    ) { bottomInset ->
        val sheet = this
        Column(Modifier.fillMaxSize()) {
            ModelPickerHeader(
                subtitle = pickerSubtitle(request.tabs, request.hostName),
                harness = request.tabs.singleOrNull()?.harness,
            )
            if (request.tabs.size > 1) {
                ProviderSwitcher(
                    tabs = request.tabs,
                    selected = pager.currentPage,
                    onSelect = { ix ->
                        if (ix != pager.currentPage) feedback.haptic(Haptic.Select)
                        scope.launch { pager.animateScrollToPage(ix) }
                    },
                )
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                val tab = request.tabs[page]
                ModelPickerPage(
                    tab = tab,
                    rows = rows[tab.harness],
                    current = current,
                    notice = pickerPageNotice(tab, page, initial, request.note),
                    bottomInset = bottomInset,
                ) { row ->
                    if (!picked) {
                        picked = true
                        current = tab.harness to row.id
                        feedback.haptic(Haptic.Light)
                        request.onPick(tab.harness, row)
                        // A beat to see the selection land.
                        scope.launch {
                            delay(220)
                            sheet.dismiss()
                        }
                    }
                }
            }
        }
    }
}

/** The header's geometry, shared by the title row and the close circle laid over it. */
private object ModelPickerHeaderMetrics {
    val TopPadding = 18.dp
    val EndPadding = 18.dp
    val CloseSize = 36.dp

    /** The title column is ~49 dp (22 + 3 + 13 sp lines): the circle centred on it. */
    val CloseTop = TopPadding + 6.dp
}

/** "Model" over where it runs (with one provider, its mark first); room on the right for the close circle. */
@Composable
private fun ModelPickerHeader(subtitle: String, harness: String?) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = ModelPickerHeaderMetrics.EndPadding, top = ModelPickerHeaderMetrics.TopPadding, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).testTag("model-picker-title")) {
            BasicText(
                ModelPickerCopy.Title,
                style = uiStyle(Face.SansSemibold, typeScale.fixed(22f), palette.text),
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (harness != null) {
                    HarnessMark(harness, 13.dp, palette.secondary)
                    Spacer(Modifier.width(5.dp))
                }
                BasicText(
                    subtitle,
                    style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The close circle's slot ([ModelPicker]'s `glass`, over this row).
        Spacer(Modifier.width(12.dp + ModelPickerHeaderMetrics.CloseSize))
    }
}

/**
 * The provider switcher: a capsule track of segments (brand mark + name),
 * the chosen one on a light thumb. It scrolls when the providers don't
 * fit.
 */
@Composable
private fun ProviderSwitcher(tabs: List<ModelPickerTab>, selected: Int, onSelect: (Int) -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val thumb = if (RobocoTheme.isDark) Color.White.copy(alpha = 0.14f) else Color.White
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 14.dp)
            .fillMaxWidth()
            .height(40.dp)
            .clip(CapsuleShape)
            .background(palette.controlFill)
            .horizontalScroll(rememberScrollState())
            .padding(3.dp)
            .testTag("model-picker-switcher"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { ix, tab ->
            val chosen = ix == selected
            Row(
                Modifier
                    .height(34.dp)
                    .clip(CapsuleShape)
                    .background(if (chosen) thumb else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(ix) }
                    .padding(horizontal = 12.dp)
                    .semantics { this.selected = chosen }
                    .testTag("model-picker-tab-${tab.harness}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HarnessMark(tab.harness, 15.dp, if (chosen) palette.text else palette.secondary)
                Spacer(Modifier.width(6.dp))
                BasicText(
                    switcherTitle(tab.label),
                    style = uiStyle(
                        Face.SansMedium,
                        typeScale.fixed(14f),
                        if (chosen) palette.text else palette.secondary,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** One provider's models: the notice, the search once long, the rows (or the loading bars). */
@Composable
private fun ModelPickerPage(
    tab: ModelPickerTab,
    rows: List<ModelPickerRow>?,
    current: Pair<String, String?>,
    notice: String?,
    bottomInset: Dp,
    onPick: (ModelPickerRow) -> Unit,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var query by remember(tab.harness) { mutableStateOf("") }
    val visible = rows?.let { filterModels(it, query) }.orEmpty()
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("model-picker-list-${tab.harness}"),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = bottomInset + 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (rows != null && showsModelSearch(rows)) {
            item(key = "search") {
                ModelSearchField(query, { query = it }, ModelPickerCopy.searchPlaceholder(tab.label))
            }
        }
        if (notice != null) {
            item(key = "notice") { ModelPickerNotice(notice) }
        }
        if (rows == null) {
            items(4, key = { "skeleton-$it" }) { ix -> ModelSkeletonRow(ix) }
        } else if (visible.isEmpty()) {
            item(key = "empty") {
                BasicText(
                    modelEmptyText(query),
                    style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.tertiary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp)
                        .testTag("model-picker-empty"),
                )
            }
        } else {
            items(visible, key = { "model:${it.id}" }) { row ->
                ModelRow(
                    row = row,
                    current = current.first == tab.harness && current.second == row.id,
                    onClick = { onPick(row) },
                )
            }
        }
    }
}

/** A model: its name over one or two lines of what it's for; the current one tinted and checked. */
@Composable
private fun ModelRow(row: ModelPickerRow, current: Boolean, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (current) palette.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
            .semantics { selected = current }
            .testTag("model-picker-row-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                row.label,
                style = uiStyle(Face.SansMedium, typeScale.fixed(16f), palette.text),
            )
            val detail = row.detail
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                BasicText(
                    detail,
                    style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                RobocoIcons.Checkmark,
                palette.accent,
                14f,
                SymbolWeight.Semibold,
                Modifier.alpha(if (current) 1f else 0f),
            )
        }
    }
}

/** What to know before picking: a quiet tinted line over the list. */
@Composable
private fun ModelPickerNotice(text: String) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.controlFill)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag("model-picker-notice"),
    ) {
        Icon(RobocoIcons.InfoCircle, palette.secondary, 13f, SymbolWeight.Medium)
        Spacer(Modifier.width(8.dp))
        BasicText(text, style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary))
    }
}

/** A loading row: two soft bars (static — no infinite animation to keep tests idle). */
@Composable
private fun ModelSkeletonRow(seed: Int) {
    val palette = RobocoTheme.palette
    val title = listOf(0.3f, 0.38f, 0.26f, 0.34f)[seed % 4]
    val detail = listOf(0.6f, 0.48f, 0.55f, 0.42f)[seed % 4]
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 16.dp, bottom = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth(title)
                .height(13.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(palette.controlFill),
        )
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier
                .fillMaxWidth(detail)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(palette.controlFill),
        )
    }
}

/** The page's search field, once the list is long. */
@Composable
private fun ModelSearchField(query: String, onQuery: (String) -> Unit, placeholder: String) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text)
    Row(
        Modifier
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp)
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.controlFill)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RobocoIcons.Magnifyingglass, palette.secondary, 14f, SymbolWeight.Medium)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier
                .weight(1f)
                .testTag("model-picker-search"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        BasicText(placeholder, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.tertiary), maxLines = 1)
                    }
                    inner()
                }
            },
        )
    }
}
