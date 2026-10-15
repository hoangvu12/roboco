package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassMenuPanel
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuElement
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuLine
import sh.roboco.android.design.glass.MenuSection
import sh.roboco.android.design.glass.MenuTrigger
import sh.roboco.android.design.glass.glassMenu
import sh.roboco.android.design.uiStyle

/**
 * The "GlassMenu" page of the debug design gallery (android-ui-parity #15),
 * registered by ticket 22's registry as
 * `GallerySection("GlassMenu", GalleryLayout.Fill) { GlassMenuGallerySection() }`.
 *
 * The section wraps itself in its own [GlassMenuHost] over [GalleryBackdrop]
 * (the host makes the backdrop the haze source the panels blur), then
 * shows: the placement stage with live demo buttons near all four corners
 * and the centre (tap / long-press / deferred / drill-in / inline + brand
 * marks / inline deferred "Branch" / long title / 40-row scroll), the
 * composer + attach demo for the IME check, and the ticket's fixtures A–D
 * as static panels.
 *
 * No Material controls: plain `BasicText` capsules in `palette.controlFill`.
 */
@Composable
fun GlassMenuGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val haze = rememberHazeState()

    // Deferred demo state: a hoisted cached instance (loads once across
    // opens) and a counting uncached one.
    var cachedLoads by remember { mutableStateOf(0) }
    val branchDeferred = remember {
        MenuElement.Deferred(cached = true) {
            cachedLoads++
            delay(1500)
            listOf(MenuItem("main") {}, MenuItem("feature/glass-menu") {})
        }
    }
    var uncachedOpens by remember { mutableStateOf(0) }
    var taps by remember { mutableStateOf(0) }
    var lastPick by remember { mutableStateOf("—") }
    var field by remember { mutableStateOf("") }

    fun pick(name: String) {
        lastPick = name
    }

    GlassMenuHost(hazeState = haze) {
        Box(Modifier.fillMaxSize()) {
            GalleryBackdrop(Modifier.fillMaxSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Label("GlassMenu", uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text))
                Label(
                    "Live menus open over this backdrop; last pick: $lastPick",
                    uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                )

                // --------------------------------------------- placement stage
                Label(
                    "Placement stage: corners + centre exercise the anchored placement",
                    uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                ) {
                    DemoButton(
                        "Tap: delivery menu",
                        Modifier.align(Alignment.TopStart),
                        menu = { deliveryMenu(::pick) },
                    )
                    DemoButton(
                        "Long-press: delivery menu (taps: $taps)",
                        Modifier.align(Alignment.TopEnd),
                        trigger = MenuTrigger.LongPress,
                        onClick = { taps++ },
                        menu = { deliveryMenu(::pick) },
                    )
                    DemoButton(
                        "Drill-in: Move to Section",
                        Modifier.align(Alignment.Center),
                        menu = { sessionRowMenu(::pick) },
                    )
                    DemoButton(
                        "Model menu (inline + brand marks)",
                        Modifier.align(Alignment.BottomStart),
                        menu = { modelMenu() },
                    )
                    DemoButton(
                        "Checkout (inline deferred Branch)",
                        Modifier.align(Alignment.BottomEnd),
                        menu = { checkoutMenu(branchDeferred) },
                    )
                }

                // --------------------------------------------- deferred demos
                DemoButton(
                    "Deferred 1.5 s (cached): loads = $cachedLoads",
                    menu = { Menu(children = listOf(branchDeferred)) },
                )
                DemoButton(
                    "Uncached (counts opens): opens = $uncachedOpens",
                    menu = {
                        uncachedOpens++
                        groupByProjectMenu()
                    },
                )

                // --------------------------------------------- long title + scroll
                DemoButton("Long PR title", menu = { longPrMenu(::pick) })
                DemoButton("40 rows (scroll)", menu = { fortyRowsMenu(::pick) })

                // --------------------------------------------- composer + attach
                Label(
                    "Focus the field, then open the attach menu — the keyboard must stay up:",
                    uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it },
                        modifier = Modifier
                            .weight(1f)
                            .background(palette.chip)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
                    )
                    Spacer(Modifier.width(12.dp))
                    DemoButton("+", menu = { attachMenu() })
                }

                // --------------------------------------------- static fixtures
                Label(
                    "Fixtures A–D (static GlassMenuPanel over the backdrop):",
                    uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                )
                StaticPanel("A — delivery", fixtureALines())
                StaticPanel("B — queue row ⋯", fixtureBLines())
                StaticPanel("C — model menu", fixtureCLines())
                StaticPanel("D — reasoning effort (drilled)", fixtureDLines())
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** A demo capsule: plain `BasicText` in `controlFill`, no Material. */
@Composable
private fun DemoButton(
    text: String,
    modifier: Modifier = Modifier,
    trigger: MenuTrigger = MenuTrigger.Tap,
    onClick: (() -> Unit)? = null,
    menu: () -> Menu,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.text),
        modifier = modifier
            .background(palette.controlFill, CapsuleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .glassMenu(trigger = trigger, onClick = onClick, menu = menu),
    )
}

/** One static fixture panel with its caption. */
@Composable
private fun StaticPanel(caption: String, lines: List<MenuLine>) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(caption, uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary))
        GlassMenuPanel(
            lines = lines,
            onRow = {},
            onSubmenu = {},
            onBack = {},
        )
    }
}

@Composable
private fun Label(text: String, style: TextStyle) {
    BasicText(text, style = style)
}

// ---------------------------------------------------------------------------
// The ticket's fixtures A–D, as pre-flattened lines for the static panels
// and the Roborazzi goldens (GlassMenuScreenshotTest).
// ---------------------------------------------------------------------------

/** A — delivery menu (title, subtitle row, destructive row). */
fun fixtureALines(): List<MenuLine> = listOf(
    MenuLine.Title("While the agent works"),
    MenuLine.Row(MenuItem("Queue for next turn", icon = MenuIcon.Symbol(RobocoIcons.QueueForNextTurn)) {}),
    MenuLine.Row(
        MenuItem("Steer now", subtitle = "Deliver into the running turn", icon = MenuIcon.Symbol(RobocoIcons.ArrowTurnDownRight)) {},
    ),
    MenuLine.Row(MenuItem("Stop and send", icon = MenuIcon.Symbol(RobocoIcons.StopCircle), destructive = true) {}),
)

/** B — queue row ⋯ (disabled row, disabled destructive row — both keep icon ink). */
fun fixtureBLines(): List<MenuLine> = listOf(
    MenuLine.Row(MenuItem("Edit", icon = MenuIcon.Symbol(RobocoIcons.Pencil)) {}),
    MenuLine.Row(MenuItem("Move Up", icon = MenuIcon.Symbol(RobocoIcons.ArrowUp), disabled = true) {}),
    MenuLine.Row(MenuItem("Move Down", icon = MenuIcon.Symbol(RobocoIcons.ArrowDown)) {}),
    MenuLine.Row(MenuItem("Remove", icon = MenuIcon.Symbol(RobocoIcons.Trash), destructive = true, disabled = true) {}),
)

/** C — model menu (inline sections with brand marks, one pending deferred). */
fun fixtureCLines(): List<MenuLine> = listOf(
    MenuLine.Title("Model"),
    MenuLine.Header("Claude Code", MenuIcon.Brand("claude-code")),
    MenuLine.Row(MenuItem("Opus 4.5", checked = true) {}),
    MenuLine.Row(MenuItem("Sonnet 4.5") {}),
    MenuLine.Separator,
    MenuLine.Header("Codex", MenuIcon.Brand("codex")),
    MenuLine.Row(MenuItem("GPT-5", subtitle = "Default") {}),
    MenuLine.Separator,
    MenuLine.Header("Cursor", MenuIcon.Brand("cursor")),
    MenuLine.Loading(pendingModelRows()),
)

/** D — reasoning effort, drilled (back row, checked row, no icons). */
fun fixtureDLines(): List<MenuLine> = listOf(
    MenuLine.Back("Reasoning effort"),
    MenuLine.Separator,
    MenuLine.Row(MenuItem("Low") {}),
    MenuLine.Row(MenuItem("Medium", checked = true) {}),
    MenuLine.Row(MenuItem("High") {}),
)

/** The never-resolving deferred of fixture C's "Cursor" section. */
private fun pendingModelRows(): MenuElement.Deferred =
    MenuElement.Deferred(cached = true) { emptyList() }

// ---------------------------------------------------------------------------
// The live demo menus
// ---------------------------------------------------------------------------

private fun deliveryMenu(pick: (String) -> Unit): Menu = Menu(
    title = "While the agent works",
    children = listOf(
        MenuItem("Queue for next turn", icon = MenuIcon.Symbol(RobocoIcons.QueueForNextTurn)) { pick("Queue for next turn") },
        MenuItem("Steer now", subtitle = "Deliver into the running turn", icon = MenuIcon.Symbol(RobocoIcons.ArrowTurnDownRight)) { pick("Steer now") },
        MenuItem("Stop and send", icon = MenuIcon.Symbol(RobocoIcons.StopCircle), destructive = true) { pick("Stop and send") },
    ),
)

private fun attachMenu(): Menu = Menu(
    children = listOf(
        MenuItem("Photo Library", icon = MenuIcon.Symbol(RobocoIcons.PhotoOnRectangle)) {},
        MenuItem("Take Photo", icon = MenuIcon.Symbol(RobocoIcons.Camera)) {},
        MenuItem("Choose File", icon = MenuIcon.Symbol(RobocoIcons.Folder)) {},
        MenuItem("Paste Image", icon = MenuIcon.Symbol(RobocoIcons.DocOnClipboard)) {},
    ),
)

private fun sessionRowMenu(pick: (String) -> Unit): Menu = Menu(
    children = listOf(
        MenuItem("Pin", icon = MenuIcon.Symbol(RobocoIcons.Pin)) { pick("Pin") },
        MenuSection(
            title = "Move to Section",
            icon = MenuIcon.Symbol(RobocoIcons.Folder),
            inline = false,
            children = listOf(
                MenuItem("Bugs", icon = MenuIcon.Symbol(RobocoIcons.Folder)) { pick("Move to Section › Bugs") },
                MenuItem("Research", icon = MenuIcon.Symbol(RobocoIcons.Folder)) { pick("Move to Section › Research") },
                MenuItem("No Section", icon = MenuIcon.Symbol(RobocoIcons.Tray)) { pick("No Section") },
                MenuItem("New Section…", icon = MenuIcon.Symbol(RobocoIcons.FolderBadgePlus)) { pick("New Section…") },
            ),
        ),
        MenuItem("Rename…", icon = MenuIcon.Symbol(RobocoIcons.Pencil)) { pick("Rename…") },
        MenuItem("Archive", icon = MenuIcon.Symbol(RobocoIcons.Archivebox), destructive = true) { pick("Archive") },
    ),
)

private fun modelMenu(): Menu = Menu(
    title = "Model",
    children = listOf(
        MenuSection(
            title = "Claude Code",
            icon = MenuIcon.Brand("claude-code"),
            inline = true,
            children = listOf(
                MenuItem("Opus 4.5", checked = true) {},
                MenuItem("Sonnet 4.5") {},
            ),
        ),
        MenuSection(
            title = "Codex",
            icon = MenuIcon.Brand("codex"),
            inline = true,
            children = listOf(MenuItem("GPT-5", subtitle = "Default") {}),
        ),
    ),
)

private fun checkoutMenu(branch: MenuElement.Deferred): Menu = Menu(
    children = listOf(
        MenuItem("New worktree", checked = true) {},
        MenuSection(title = "Branch", inline = true, children = listOf(branch)),
    ),
)

private fun groupByProjectMenu(): Menu = Menu(
    children = listOf(
        MenuItem("New Section…", icon = MenuIcon.Symbol(RobocoIcons.FolderBadgePlus)) {},
        MenuItem("Collapse All", icon = MenuIcon.Symbol(RobocoIcons.RectangleCompressVertical)) {},
        MenuElement.Deferred(cached = false) {
            listOf(MenuItem("Group by Project", checked = true, icon = MenuIcon.Symbol(RobocoIcons.SquareStack3dUp)) {})
        },
        MenuItem("Archived", icon = MenuIcon.Symbol(RobocoIcons.Archivebox)) {},
    ),
)

private fun longPrMenu(pick: (String) -> Unit): Menu = Menu(
    title = "Fix queue panel layout while the agent steers mid-turn (#12488)",
    children = listOf(
        MenuItem("Open Pull Request", icon = MenuIcon.Symbol(RobocoIcons.Safari)) { pick("Open Pull Request") },
        MenuItem("Copy Link", icon = MenuIcon.Symbol(RobocoIcons.Link)) { pick("Copy Link") },
    ),
)

private fun fortyRowsMenu(pick: (String) -> Unit): Menu = Menu(
    children = (1..40).map { index ->
        MenuItem("Row $index", icon = MenuIcon.Symbol(RobocoIcons.Folder)) { pick("Row $index") }
    },
)

// ---------------------------------------------------------------------------
// Previews (step 9): fixtures A and C, light and dark.
// ---------------------------------------------------------------------------

@Preview(name = "GlassMenu A light")
@Composable
private fun GlassMenuPanelALightPreview() {
    PanelPreview(Appearance.Light, fixtureALines())
}

@Preview(name = "GlassMenu A dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GlassMenuPanelADarkPreview() {
    PanelPreview(Appearance.Dark, fixtureALines())
}

@Preview(name = "GlassMenu C light")
@Composable
private fun GlassMenuPanelCLightPreview() {
    PanelPreview(Appearance.Light, fixtureCLines())
}

@Preview(name = "GlassMenu C dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GlassMenuPanelCDarkPreview() {
    PanelPreview(Appearance.Dark, fixtureCLines())
}

@Composable
private fun PanelPreview(appearance: Appearance, lines: List<MenuLine>) {
    RobocoTheme(appearance) {
        Box(
            Modifier
                .size(360.dp, 360.dp)
                .background(RobocoTheme.palette.background),
        ) {
            GlassMenuPanel(
                lines = lines,
                onRow = {},
                onSubmenu = {},
                onBack = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
