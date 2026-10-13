package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.BottomChromeInsets
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/** The gallery's state picker: each value is one [SearchResults] fixture. */
private enum class SearchDemo(val label: String) {
    Loading("Loading"),
    Recent("Recent"),
    Hits("Hits"),
    NoResults("No results"),
}

/**
 * The "Search screen" page of the debug design gallery
 * (android-ui-parity #49): the ticket's four [SearchResults] states over
 * [SearchFixtures], switched by a segmented control of plain `BasicText`
 * buttons, each shown in the full 412 × 700 dp phone frame the goldens
 * use (scaled down to fit the body, like the pairing root's frame) with
 * the shell's chrome insets (`BottomChromeInsets(24.dp, 150.dp)`) and a
 * fixed 24 dp status bar. The list state is remembered, so switching the
 * fixture keeps the scroll — the review can check the title/scroll
 * behaviour live.
 *
 * It follows the gallery's Light/Dark and reduce-motion toggles
 * (the list's item animations honour `LocalReduceMotion` via 41's rule;
 * the loading spinner animates through 11's shared glyph clock, static
 * under the capture's forced reduce motion). Row taps go nowhere (no
 * NavStack in the gallery), and the rows use the pass-through wrapper —
 * 44's swipe/menu hookup is the stateful [SearchScreen]'s, reviewed on a
 * device.
 *
 * Registered in `design/Gallery.kt` as
 * `GallerySection("Search screen", GalleryLayout.Fill, 700) { SearchGallerySection() }`.
 * No Material controls: plain `BasicText` + `clickable` rows.
 */
@Composable
fun SearchGallerySection() {
    val palette = RobocoTheme.palette

    var demo by remember { mutableStateOf(SearchDemo.Recent) }
    val results = when (demo) {
        SearchDemo.Loading -> SearchFixtures.loading
        SearchDemo.Recent -> SearchFixtures.recentResults
        SearchDemo.Hits -> SearchFixtures.hitsResults
        SearchDemo.NoResults -> SearchFixtures.noResults
    }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (kind in SearchDemo.entries) {
                DemoToggle(kind.label, demo == kind) { demo = kind }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val scale = minOf(1f, maxWidth / 412.dp, maxHeight / 700.dp)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalBottomChrome provides BottomChromeInsets(24.dp, 150.dp)) {
                    SearchScreenContent(
                        results = results,
                        listState = listState,
                        onOpenSession = {},
                        topInset = 24.dp,
                        modifier = Modifier
                            .requiredSize(412.dp, 700.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                }
            }
        }
    }
}

/** One segmented-control button: SemiBold `accent` when on, `secondary` when off. */
@Composable
private fun DemoToggle(label: String, selected: Boolean, onSelect: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        label,
        style = uiStyle(
            Face.SansSemibold,
            typeScale.fixed(13f),
            if (selected) palette.accent else palette.secondary,
        ),
        modifier = Modifier
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews: light + dark with the Recent fixture (the gallery's hosts —
// ContextMenuHost/AlertHost/ToastHost — come from the real gallery).
// ---------------------------------------------------------------------------

@Preview(name = "search gallery light")
@Composable
private fun SearchGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        SearchGallerySection()
    }
}

@Preview(name = "search gallery dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SearchGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        SearchGallerySection()
    }
}
