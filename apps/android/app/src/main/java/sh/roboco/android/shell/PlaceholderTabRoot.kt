package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * Placeholder rows per tab (items 1…40); the interim content until
 * tickets 46 (Sessions), 103 (Settings) and 49 (Search) land.
 */
private const val PLACEHOLDER_ROWS = 40

/**
 * The interim tab roots (android-ui-parity #26): a `LazyColumn` that
 * stands in until the real screens land — a large-title header (Geist
 * SemiBold 30, like `MainTabController.nav`'s large titles; ticket 25
 * owns the real ones) and 44 dp rows in Geist Regular 17 (body-scaled).
 * Tapping row N pushes [PlaceholderDetailRoute] through [LocalNavStack],
 * so per-tab back stacks can be exercised.
 *
 * The list registers itself as the tab's scroll-to-top handler, so a
 * re-select at the root scrolls it to item 0 (animated, also under Reduce
 * Motion). The bottom content padding is #30's
 * [LocalBottomChrome.current] content padding (the chrome group + 8 dp,
 * from the same `barBottom` the bar layout uses); the status bar tops it.
 */
@Composable
fun PlaceholderTabRoot(tab: TabId) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val listState = rememberLazyListState()
    RegisterScrollToTop(listState)
    val stack = LocalNavStack.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("tab-root-${tab.identifier}"),
        state = listState,
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = LocalBottomChrome.current.contentPadding,
        ),
    ) {
        item(key = "title") {
            BasicText(
                text = tab.label,
                modifier = Modifier
                    .height(52.dp)
                    .padding(start = 16.dp),
                style = uiStyle(Face.SansSemibold, typeScale.fixed(30f), palette.text),
            )
        }
        items(PLACEHOLDER_ROWS) { index ->
            val row = index + 1
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable { stack.push(PlaceholderDetailRoute(tab, row)) }
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(
                    text = "Placeholder row $row",
                    style = uiStyle(Face.Sans, typeScale.scaled(17f), palette.text),
                )
            }
        }
    }
}

/**
 * A placeholder pushed page: a full-screen `palette.background` page with
 * a centred "<Tab> row N" in Geist Medium 17. Back is ticket 24's (the
 * host's predictive back / pop).
 */
@Composable
fun PlaceholderDetail(route: PlaceholderDetailRoute) {
    val palette = RobocoTheme.palette
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "${route.tab.label} row ${route.row}",
            style = uiStyle(Face.SansMedium, LocalTypeScale.current.scaled(17f), palette.text),
        )
    }
}
