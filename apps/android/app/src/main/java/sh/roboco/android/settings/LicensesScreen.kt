package sh.roboco.android.settings

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.groupedSection
import sh.roboco.android.design.rgba
import sh.roboco.android.design.uiStyle
import sh.roboco.android.design.sheet.SheetHeaderRow
import sh.roboco.android.design.sheet.SheetTrailing
import sh.roboco.android.shell.LargeTitleScaffold
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalPageSheetScope
import sh.roboco.android.shell.LocalSheetHost
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.PageSheetScope
import sh.roboco.android.shell.SheetStyle
import sh.roboco.android.shell.TitleMode
import sh.roboco.android.shell.largeTitleDefaultTopInset

/**
 * The Licenses screen (android-ui-parity #101): a large-title,
 * inset-grouped list with four sections — "Roboco", "Fonts and assets",
 * "Android libraries", "Rust core". Tapping an entry presents the
 * full-height licence-text page sheet (the look of iOS's
 * `SelectTextViewController`, `TranscriptListView.swift:641-691`: large
 * detent, no grabber, centred title, trailing Done).
 *
 * The data is parsed off the main thread and retained by the Licenses
 * [sh.roboco.android.shell.NavEntry] (`retain("licenses")`), so the ≈1–3 MB
 * of strings are freed on pop — never a process-wide singleton. The route
 * is rendered by ticket 26's
 * [sh.roboco.android.shell.ShellRouteContent].
 */

/** The route the Settings "Licenses" row pushes. */
data object LicensesRoute : NavRoute {
    override val paneTitle: String get() = "Licenses"
}

/**
 * The screen's model: [sections] is `null` until the IO load finishes —
 * until then only the large title shows (no spinner; a typical load is
 * < 150 ms), and the rows then appear at once, with no animation (iOS
 * `animatingDifferences: false`).
 */
@Stable
class LicensesModel(repository: LicensesRepository, scope: CoroutineScope) {

    private val _sections = MutableStateFlow<List<LicenceSection>?>(null)

    /** `null` until the repository's `load()` finished; loaded sections never reorder. */
    val sections: StateFlow<List<LicenceSection>?> = _sections.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { _sections.value = repository.load() }
    }
}

/**
 * The Licenses screen, as rendered inside a
 * [sh.roboco.android.shell.NavStackHost]: the model is retained by the
 * current entry and the licence sheet is presented through 31's host.
 */
@Composable
fun LicensesScreen() {
    val entry = LocalNavEntry.current
    val context = LocalContext.current
    val model = entry.retain("licenses") {
        LicensesModel(LicensesRepository(context.assets, context.resources), entry.scope)
    }
    val sections by model.sections.collectAsState()
    val present = rememberLicencePresenter()
    LicensesList(sections, onOpen = present)
}

/**
 * The stateless Licenses list — the [LargeTitleScaffold] (always-large
 * title, the back circle when pushed) over one grouped section per
 * [LicenceSection]. Rows carry **no icon** (the text starts 16 dp inside
 * the card, the separator at 16) and the default disclosure accessory;
 * row subtitles wrap with no line cap. Section order within the loaded
 * list is the sections' own.
 *
 * Previews, the gallery and the goldens pass [topInset] (Robolectric has
 * no real insets); production reads the status bar — 0 inside 104's
 * Settings card, through [largeTitleDefaultTopInset].
 */
@Composable
fun LicensesList(
    sections: List<LicenceSection>?,
    onOpen: (LicenceEntry) -> Unit,
    modifier: Modifier = Modifier,
    // 104: defaults to 25's resolved default, so the screen insets nothing
    // when hosted inside the tablet Settings card.
    topInset: Dp = largeTitleDefaultTopInset(),
) {
    LargeTitleScaffold(
        title = "Licenses",
        mode = TitleMode.Always,
        modifier = modifier,
        topInset = topInset,
        contentPadding = PaddingValues(bottom = LocalBottomChrome.current.contentPadding),
    ) {
        // null = still loading: only the large title shows.
        sections?.forEach { section ->
            groupedSection(
                key = section.key,
                header = section.title,
                items = section.entries,
                itemKey = { it.id },
            ) { entry, position ->
                GroupedRow(
                    title = entry.title,
                    position = position,
                    subtitle = entry.subtitle,
                    icon = null,
                    accessories = listOf(GroupedAccessory.Disclosure),
                    testTag = "licenses-${entry.id}",
                    onClick = { onOpen(entry) },
                )
            }
        }
    }
}

/**
 * The licence-sheet presenter: the function a row's click handler calls.
 * Composed (not a `::` reference — it must read composition locals): it
 * prefers the enclosing sheet's [LocalPageSheetScope] (the tablet
 * Settings dialog, 104, whose card provides it), stacking the licence
 * sheet on it, and falls back to the shell's [LocalSheetHost]. The sheet
 * itself is always 31's `Page(grabber = false)` page sheet at the large
 * detent — never composed inside this screen, or the tab bar (drawn above
 * the top nav layer) would cover it.
 */
@Composable
fun rememberLicencePresenter(): (LicenceEntry) -> Unit {
    val parent = LocalPageSheetScope.current
    val host = LocalSheetHost.current
    return remember(parent, host) {
        { entry: LicenceEntry ->
            val text = entry.fullText() // cheap: joins shared strings
            val key = "licence:${entry.id}"
            val content: @Composable PageSheetScope.() -> Unit = {
                LicenceTextSheet(entry.title, text, onDone = { dismiss(true) })
            }
            val shown = parent?.present(key, SheetStyle.Page(grabber = false), null, content)
                ?: host?.present(key, SheetStyle.Page(grabber = false), null, content)
            if (shown != true) Log.w("Roboco", "licence sheet not presented: $key")
        }
    }
}

// ---------------------------------------------------------------------------
// The licence-text sheet
// ---------------------------------------------------------------------------

/**
 * The licence text sheet — the content of a 31 page entry: ticket 17's
 * [SheetHeaderRow] (centred title, trailing Done) over a selectable,
 * read-only column of the licence text, Geist Regular 13 in `text` on the
 * sheet's `background` page. The text box is inset 16 / 19 / 32 / 19 dp
 * (the 19 models `UITextView`'s 5 dp `lineFragmentPadding`); the bottom
 * adds the navigation-bar inset. Nothing is selected on open (unlike
 * iOS's Select Text, a licence is read, not copied) and long-pressing a
 * word starts an `accent`-handled selection.
 */
@Composable
fun LicenceTextSheet(title: String, text: String, onDone: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val selectionColors = rememberLicenceSelectionColors()
    Column(Modifier.fillMaxSize().testTag("licence-sheet")) {
        SheetHeaderRow(title = title, trailing = SheetTrailing.Done, onTrailing = onDone)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            SelectionContainer(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicText(
                    text,
                    style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.text),
                    modifier = Modifier
                        .padding(start = 19.dp, end = 19.dp, top = 16.dp, bottom = 32.dp + navBottom)
                        .testTag("licence-text"),
                )
            }
        }
    }
}

/**
 * The sheet's selection colours: `accent` handles and an `accent`@0.4
 * highlight. This **overrides** the theme's default wash
 * (`palette.selectionBackground`, the desktop's `accent.selection` at
 * 0x3D/0x59) with the ticket's 0.4 — the same interpretation ticket 127's
 * Select Text sheet uses, so the two full-text sheets match; UIKit draws
 * the highlight with `window.tintColor` and no source alpha. Quantised
 * through ticket 01's [rgba] — never `Color.copy(alpha = …)`.
 */
@Composable
private fun rememberLicenceSelectionColors(): TextSelectionColors {
    val accent = RobocoTheme.palette.accent
    val dark = RobocoTheme.isDark
    return remember(accent, dark) {
        TextSelectionColors(
            handleColor = accent,
            backgroundColor = if (dark) rgba(0x8B7CF6, 0.4) else rgba(0x5B43E8, 0.4),
        )
    }
}
