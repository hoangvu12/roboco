package sh.roboco.android.design.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Glass sheet" page of the debug design gallery (ticket 17):
 * a striped "presenting" backdrop with one button per sheet shape of the
 * iOS reference table —
 *
 * - "Select Text (large, Done)": large-only, no grabber, trailing Done
 *   (`TranscriptListView.swift:641-645`);
 * - "Tool detail (medium/large, grabber)": the `[.medium(), .large()]`
 *   sheet of the tool Detail widget (`RowView.swift:244-248`);
 * - "New Session (large, grabber, Close)": the tab shell's page sheet
 *   (`MainTabController.swift:136-148`);
 * - "Stacked: New Project over New Session": the second sheet composed
 *   above the first (`NewSessionViewController.swift:308-317`); the
 *   one-at-a-time rules are ticket 31's, so both are plain `GlassSheet`s.
 *
 * Every sheet's content is a 200-line scrolling text (to try the
 * scroll/detent coupling: swiping up at medium expands to large before the
 * list scrolls, pulling down at the list's top moves the sheet) plus a
 * text field (to try the IME: at medium the sheet grows to large). On the
 * static capture frame no sheet is open, so the golden is the button list
 * over the backdrop.
 *
 * Registered in `Gallery.sections` as
 * `GallerySection("Glass sheet", GalleryLayout.Fill, screenshotHeightDp = 740) { GlassSheetGallerySection() }`
 * (the ticket's reserved line): `Fill` because the section hosts
 * full-screen overlays and `LazyColumn`s.
 */
@Composable
fun GlassSheetGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var open by rememberSaveable { mutableStateOf<SheetDemo?>(null) }
    var stacked by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // The "presenting" content the sheet slides over.
        GalleryBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(
                "Glass sheet — the page-sheet primitive (drag, fling, back, scrim, IME)",
                style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            )
            DemoButton("Select Text (large, Done)") { open = SheetDemo.SelectText }
            DemoButton("Tool detail (medium/large, grabber)") { open = SheetDemo.ToolDetail }
            DemoButton("New Session (large, grabber, Close)") { open = SheetDemo.NewSession }
            DemoButton("Stacked: New Project over New Session") {
                open = SheetDemo.NewSession
                stacked = true
            }
            BasicText(
                "Open a sheet, then: drag the header/grabber slowly and fling; " +
                    "swipe the text up at half screen; pull down at the list's top; " +
                    "focus the field (the IME grows a medium sheet to large); " +
                    "use the predictive back gesture and cancel it.",
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
        }

        when (open) {
            SheetDemo.SelectText -> GlassSheet(
                onDismiss = { open = null },
                title = "Select Text",
                header = { SheetHeader("Select Text", trailing = SheetTrailing.Done) },
                content = { bottomInset -> SheetDemoContent(bottomInset) },
            )
            SheetDemo.ToolDetail -> GlassSheet(
                onDismiss = { open = null },
                state = rememberGlassSheetState(listOf(SheetDetent.Medium, SheetDetent.Large)),
                grabber = true,
                title = "Bash",
                header = { SheetHeader("Bash", trailing = SheetTrailing.Done) },
                content = { bottomInset -> SheetDemoContent(bottomInset) },
            )
            SheetDemo.NewSession -> {
                GlassSheet(
                    onDismiss = {
                        open = null
                        stacked = false
                    },
                    grabber = true,
                    title = "New Session",
                    header = { SheetHeader("New Session", leading = SheetLeading.Close) },
                    content = { bottomInset ->
                        SheetDemoContent(bottomInset, extra = "New Project…") { stacked = true }
                    },
                )
                if (stacked) {
                    // Stacked over the New Session sheet, exactly as
                    // `NewSessionViewController` presents the browser over
                    // the sheet: large, no grabber, leading ✕.
                    GlassSheet(
                        onDismiss = { stacked = false },
                        title = "New Project",
                        header = { SheetHeader("New Project", leading = SheetLeading.Close) },
                        content = { bottomInset -> SheetDemoContent(bottomInset) },
                    )
                }
            }
            null -> {}
        }
    }
}

private enum class SheetDemo { SelectText, ToolDetail, NewSession }

/** One plain-text button row (no Material controls). */
@Composable
private fun DemoButton(text: String, onClick: () -> Unit) {
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

/**
 * A sheet's demo content: a text field at the top (the IME try) and 200
 * mono lines below (the scroll-coupling try), with [bottomInset] added to
 * the list's content padding so the last line can scroll above the
 * gesture bar. [extra], when set, is the "New Project…" button of the
 * stacked demo.
 */
@Composable
private fun SheetDemoContent(bottomInset: Dp, extra: String? = null, onExtra: () -> Unit = {}) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var field by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().background(palette.background),
        contentPadding = PaddingValues(bottom = bottomInset + 16.dp),
    ) {
        item {
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(palette.chip)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
            )
        }
        if (extra != null) {
            item {
                BasicText(
                    extra,
                    style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onExtra)
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
        items(200) { line ->
            BasicText(
                "line ${line + 1}: $ roboco engine start --port ${8675 + line}",
                Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                style = uiStyle(Face.Mono, typeScale.bodyScaled(13f), palette.text),
            )
        }
    }
}
