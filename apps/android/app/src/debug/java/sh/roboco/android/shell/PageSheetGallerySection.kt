package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.sheet.SheetHeaderRow
import sh.roboco.android.design.sheet.SheetLeading
import sh.roboco.android.design.uiStyle

/**
 * The "Page sheet" page of the debug design gallery (android-ui-parity
 * #31): a fake presenting screen wrapped in [SheetHost] — 30 striped
 * rows, the large "Sessions" title and a glass `+` bottom-right, all on
 * `background` — with the ticket's four try-outs:
 *
 * - "Present New Session": the placeholder sheet
 *   (`MainTabController.swift:136-148`) — drag it, fling it, scrub the
 *   predictive back gesture, tap its ✕;
 * - "Stack New Project": inside the sheet's page, a `grabber = false`
 *   page sheet titled "New Project"
 *   (`NewSessionViewController.swift:308-317`);
 * - "Hide for handoff (1 s)": the instant handoff hide, with
 *   `complete()` a second later (`MainTabController.swift:170-190`);
 * - "Dismiss (instant)": the un-animated teardown.
 *
 * On the static capture frame no sheet is open, so the golden is the
 * fake screen alone. Registered in `Gallery.sections` as
 * `GallerySection("Page sheet", GalleryLayout.Fill, screenshotHeightDp = 891) { PageSheetGallerySection() }`
 * — `Fill` because the section hosts a full-screen overlay.
 */
@Composable
fun PageSheetGallerySection() {
    val state = rememberSheetHostState()
    val scope = rememberCoroutineScope()

    fun hideForHandoffForASecond() {
        val handoff = state.hideForHandoff()
        scope.launch {
            delay(1000)
            handoff.complete()
        }
    }

    SheetHost(state) { FakePresentingScreen(state, ::hideForHandoffForASecond) }
}

// ---------------------------------------------------------------------------
// The fake presenting screen
// ---------------------------------------------------------------------------

@Composable
private fun FakePresentingScreen(
    state: SheetHostState,
    onHideForHandoff: () -> Unit,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    fun presentNewSession() {
        state.present("new-session", SheetStyle.Page(grabber = true), onDismissed = {}) {
            NewSessionSheetPage(this)
        }
    }

    ProvideHazeState {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // The scrolling content is the screen's haze source
                    // (ticket 07): the glass + bottom-right samples it.
                    .hazeSource(LocalHazeState.current!!)
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    "Sessions",
                    style = uiStyle(Face.SansBold, typeScale.fixed(34f), palette.text),
                )
                GalleryButton("Present New Session") { presentNewSession() }
                GalleryButton("Hide for handoff (1 s)") { onHideForHandoff() }
                GalleryButton("Dismiss (instant)") { state.dismiss(animated = false) }
                BasicText(
                    "Open a sheet, then: drag slowly and release at ~20 % (returns) " +
                        "and ~30 % (dismisses); fling down; scrub the back gesture and " +
                        "cancel it; stack New Project and press back twice; hide for " +
                        "the handoff. Repeat with animator scale 0.",
                    style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                )
                Spacer(Modifier.height(8.dp))
                repeat(30) { index -> StripedRow(index) }
            }
            GlassCircleButton(
                icon = RobocoIcons.Plus,
                contentDescription = "New Session",
                onClick = { presentNewSession() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

/** One of the 30 fake session rows, striped over the background. */
@Composable
private fun StripedRow(index: Int) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (index % 2 == 0) palette.elevated else palette.background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "Session ${index + 1}",
            Modifier.padding(start = 16.dp),
            style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
        )
    }
}

/**
 * The New Session sheet's page: the placeholder plus the gallery's
 * "Stack New Project" button (`NewSessionViewController.swift:308-317`).
 */
@Composable
private fun NewSessionSheetPage(sheetScope: PageSheetScope) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { NewSessionSheetPlaceholder(sheetScope) }
        GalleryButton("Stack New Project") {
            sheetScope.present("new-project", SheetStyle.Page(grabber = false), onDismissed = {}) {
                NewProjectPage(this)
            }
        }
    }
}

/** The stacked New Project page: a `grabber = false` sheet with its own ✕. */
@Composable
private fun NewProjectPage(sheetScope: PageSheetScope) {
    Column(Modifier.fillMaxSize()) {
        SheetHeaderRow(
            title = "New Project",
            leading = SheetLeading.Close,
            onLeading = { sheetScope.dismiss(true) },
        )
        Spacer(Modifier.weight(1f))
    }
}

/** One plain-text button row (no Material controls). */
@Composable
private fun GalleryButton(text: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
    )
}
