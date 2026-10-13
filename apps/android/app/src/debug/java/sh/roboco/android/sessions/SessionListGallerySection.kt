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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Collections
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LinearFontScaleDensity
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TypeScale
import sh.roboco.android.design.uiStyle

/** The gallery's status selector — each value is one overlay state. */
private enum class StatusDemo(val label: String) {
    Content("Content"),
    Loading("Loading"),
    EmptySessions("Empty sessions"),
    EmptyArchived("Empty archived"),
    Blank("Blank"),
}

/**
 * The "Session list" page of the debug design gallery (android-ui-parity
 * #41): the shared list host over [SessionListFixtures] with the ticket's
 * toggles — collapsible, fold "Mobile", current row 3, editing, every
 * overlay status, "Shuffle Recent" (to watch the move animations), the
 * folder + plain-header demo, and the fake font scale 0.85 / 1.0 / 1.6
 * via [LinearFontScaleDensity].
 *
 * Tapping a row shows the `BasicText` readout "open <id>" (no NavStack in
 * the gallery); `onScreen` stays true. The list's safe padding mimics the
 * worked example: top 155 (59 status + 44 nav row + 52 large title),
 * bottom 110 — so the overlay anchor sits where the front page's would.
 *
 * Registered in `design/Gallery.kt` as
 * `GallerySection("Session list", layout = GalleryLayout.Fill, screenshotHeightDp = 852) { SessionListGallerySection() }`.
 * No Material controls: plain `BasicText` + `clickable` rows, the
 * selected value in `accent`.
 */
@Composable
fun SessionListGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    var collapsible by remember { mutableStateOf(true) }
    var foldMobile by remember { mutableStateOf(false) }
    var currentRow3 by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var folderDemo by remember { mutableStateOf(false) }
    var statusKind by remember { mutableStateOf(StatusDemo.Content) }
    var fontScale by remember { mutableStateOf(1f) }
    var recent by remember { mutableStateOf(SessionListFixtures.recentRows) }
    var opened by remember { mutableStateOf<String?>(null) }

    val sections = when {
        statusKind != StatusDemo.Content -> emptyList()
        folderDemo -> SessionListFixtures.folderPlainSections()
        else -> SessionListFixtures.sections(recent = recent)
    }
    // The folder demo is the non-collapsible API: plain headers, one folder.
    val effectiveCollapsible = collapsible && !folderDemo
    val collapsedIds = if (foldMobile) setOf("mobile") else emptySet()
    val emptyKind = when (statusKind) {
        StatusDemo.EmptySessions -> SessionListEmpty.Sessions
        StatusDemo.EmptyArchived -> SessionListEmpty.Archived
        else -> SessionListEmpty.None
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        ControlRow(
            "Collapsible",
            collapsible,
        ) { collapsible = !collapsible }
        ControlRow("Fold \u201cMobile\u201d", foldMobile) { foldMobile = !foldMobile }
        ControlRow("Current = row 3", currentRow3) { currentRow3 = !currentRow3 }
        ControlRow("Editing", editing) { editing = !editing }
        ControlRow("Folder + plain header", folderDemo) { folderDemo = !folderDemo }
        for (kind in StatusDemo.entries) {
            ControlRow("Status: ${kind.label}", statusKind == kind) { statusKind = kind }
        }
        for (factor in listOf(0.85f, 1f, 1.6f)) {
            ControlRow("Fake font scale $factor", fontScale == factor) { fontScale = factor }
        }
        ControlRow("Shuffle Recent", false) {
            recent = recent.toMutableList().apply { Collections.shuffle(this) }
        }

        // The fake font scale: the same linear-density override as the Type
        // gallery, so the factor (and the row heights) hit 0.85 / 1.6
        // exactly, clamped ends included.
        val outer = LocalDensity.current
        val scaled = remember(outer.density, fontScale) { LinearFontScaleDensity(outer.density, fontScale) }
        val scaledTypeScale = remember(scaled) { TypeScale.from(scaled) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(
                LocalDensity provides scaled,
                LocalTypeScale provides scaledTypeScale,
            ) {
                // The model is built inside the fake scale so its
                // animation gate sees the same factor the rows do.
                val model = rememberSessionListModel(
                    sections = sections,
                    collapsible = effectiveCollapsible,
                    collapsedIds = collapsedIds,
                    loading = statusKind == StatusDemo.Loading,
                    empty = emptyKind,
                )
                SessionList(
                    model = model,
                    contentPadding = PaddingValues(top = 155.dp, bottom = 110.dp),
                    onOpenSession = { id -> opened = id },
                    onOpenFolder = { id -> opened = "folder $id" },
                    onToggleSection = { id -> if (id == "mobile") foldMobile = !foldMobile },
                    currentChatId = if (currentRow3) SessionListFixtures.currentRow.id else null,
                    editing = editing,
                )
            }
        }

        BasicText(
            opened?.let { "open $it" } ?: "tap a row",
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.tertiary),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** A toggle row: the label in `accent` when on, `secondary` when off. */
@Composable
private fun ControlRow(text: String, checked: Boolean, onClick: () -> Unit) {
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

// ---------------------------------------------------------------------------
// Previews: light + dark with the fixture, plus one per overlay state.
// All pass their own onOpenSession — rememberSessionListOpener needs a
// NavStackHost, which previews do not have.
// ---------------------------------------------------------------------------

@Composable
private fun SessionListPreviewBody(
    appearance: Appearance,
    sections: List<ListSection> = SessionListFixtures.sections(),
    collapsible: Boolean = true,
    loading: Boolean = false,
    empty: SessionListEmpty = SessionListEmpty.None,
) {
    RobocoTheme(appearance) {
        // Frozen at 0 ns: the spinner renders the cycle-0 pattern and the
        // preview never starts a frame loop.
        ProvideGlyphClock(frozenAtNanos = 0L) {
            val model = rememberSessionListModel(
                sections = sections,
                collapsible = collapsible,
                loading = loading,
                empty = empty,
            )
            SessionList(
                model = model,
                contentPadding = PaddingValues(top = 155.dp, bottom = 110.dp),
                onOpenSession = {},
            )
        }
    }
}

@Preview(name = "Session list – light", widthDp = 393, heightDp = 852)
@Composable
private fun SessionListLightPreview() {
    SessionListPreviewBody(Appearance.Light)
}

@Preview(name = "Session list – dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionListDarkPreview() {
    SessionListPreviewBody(Appearance.Dark)
}

@Preview(name = "Session list – loading", widthDp = 393, heightDp = 852)
@Composable
private fun SessionListLoadingPreview() {
    SessionListPreviewBody(Appearance.Light, sections = emptyList(), loading = true)
}

@Preview(name = "Session list – empty", widthDp = 393, heightDp = 852)
@Composable
private fun SessionListEmptyPreview() {
    SessionListPreviewBody(Appearance.Light, sections = emptyList(), empty = SessionListEmpty.Sessions)
}

@Preview(name = "Session list – empty archived", widthDp = 393, heightDp = 852)
@Composable
private fun SessionListEmptyArchivedPreview() {
    SessionListPreviewBody(Appearance.Light, sections = emptyList(), empty = SessionListEmpty.Archived)
}

@Preview(name = "Session list – blank", widthDp = 393, heightDp = 852)
@Composable
private fun SessionListBlankPreview() {
    SessionListPreviewBody(Appearance.Light, sections = emptyList(), empty = SessionListEmpty.None)
}

@Preview(name = "Session list – folder + plain header", widthDp = 393, heightDp = 852)
@Composable
private fun SessionListFolderPlainPreview() {
    SessionListPreviewBody(Appearance.Light, sections = SessionListFixtures.folderPlainSections(), collapsible = false)
}
