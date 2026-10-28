package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.ActionSheetHost
import sh.roboco.android.design.ActionSheetHostState
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.PlaceholderTabRoot
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute
import uniffi.roboco_core.WorkspaceSnapshot

/** The gallery's kind picker: each value is one pushed folder screen. */
private enum class FolderDemo(val label: String) {
    Section("Section"),
    Pinned("Pinned"),
    Archived("Archived"),
    ArchivedEmpty("Archived empty"),
}

/**
 * The gallery's [RowActions] fake: the section writes log to [log] (the
 * readout line, e.g. `renameSection(eng1:sec-p0, P1)`), and [failWrites]
 * imitates ticket 42's offline row — the "Engine not connected" toast and
 * [ActionOutcome.EngineOffline], so "Fail writes" shows a refused rename
 * (no retitle) and a refused delete (no pop). A successful rename also
 * hands the trimmed name to [onRenamed] so the gallery's fixture workspace
 * keeps the live prefill.
 */
private class FolderGalleryRowActions(
    private val toaster: Toaster,
    private val failWrites: () -> Boolean,
    private val onRenamed: (String) -> Unit,
    private val log: (String) -> Unit,
) : RowActions {

    private fun ok(onResult: (ActionOutcome) -> Unit) = onResult(ActionOutcome.Ok)

    private fun sectionWrite(what: String, onResult: (ActionOutcome) -> Unit, onOk: () -> Unit = {}) {
        log(what)
        if (failWrites()) {
            toaster.show(SessionPromptCopy.EngineNotConnected)
            onResult(ActionOutcome.EngineOffline)
        } else {
            onOk()
            onResult(ActionOutcome.Ok)
        }
    }

    override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {
        log("setPinned($chatId, $pinned)")
        ok(onResult)
    }

    override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {
        log("archive($chatId)")
        ok(onResult)
    }

    override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {
        log("unarchive($chatId)")
        ok(onResult)
    }
    override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {
        log("delete($chatId)")
        ok(onResult)
    }

    override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {
        log("move($chatId, ${toSection ?: "null"})")
        ok(onResult)
    }

    override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
        log("movePin($chatId)")
        ok(onResult)
    }

    override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {
        log("rename($chatId, $title)")
        ok(onResult)
    }

    override fun createSection(
        name: String,
        engineId: String?,
        onResult: (ActionOutcome) -> Unit,
        onCreated: (sectionId: String) -> Unit,
    ) = sectionWrite("createSection($name, $engineId)", onResult) { onCreated("gallery:s1") }

    override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) =
        sectionWrite("renameSection($sectionId, $name)", onResult) { onRenamed(name) }

    override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) =
        sectionWrite("deleteSection($sectionId)", onResult)

    override fun sectionsFor(engineId: String) = emptyList<uniffi.roboco_core.SectionView>()

    override fun report(error: Throwable) {
        log("report($error)")
    }
}

/**
 * The "Folder screens" page of the debug design gallery
 * (android-ui-parity #45): the pushed folder screen over [FolderFixtures],
 * with the kind picker (Section / Pinned / Archived / Archived-empty) and
 * the "Fail writes" toggle. The screen lives inside a real [NavStackHost]
 * over ticket 26's placeholder root, so the push, the back circle and the
 * post-delete pop all work; a successful rename retitles the screen and
 * feeds the fixture workspace's live prefill.
 *
 * Hosts: the gallery's `GalleryProviders` already wraps every section in
 * `ContextMenuHost { AlertHost { ToastHost { … } } }`, so this section adds
 * the innermost [ActionSheetHost] (the delete confirm sheet, 19) and the
 * [GlassMenuHost] the ⋯ menu needs — together exactly ticket 35's mount
 * order, with the sheet above the fading menu and the alert above both.
 *
 * Fill layout (like every section that owns an in-window sheet host): the
 * `ActionSheetHost` needs the body's bounded height. Registered in
 * `design/Gallery.kt` as
 * `GallerySection("Folder screens", GalleryLayout.Fill, screenshotHeightDp = 852) { FolderScreensGallerySection() }`.
 * No Material controls: plain `BasicText` + `clickable` rows.
 */
@Composable
fun FolderScreensGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val toaster = LocalToaster.current
    val sheets = remember { ActionSheetHostState() }

    var demo by remember { mutableStateOf(FolderDemo.Section) }
    var failWrites by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("—") }
    var sectionName by remember { mutableStateOf(FolderFixtures.SECTION_NAME) }
    val actions = remember(toaster) {
        FolderGalleryRowActions(toaster, { failWrites }, { sectionName = it }, { result = it })
    }
    // Starts on the Section folder (pushed hostless, so the resting top is
    // the screen the picker's other kinds replace).
    val stack = remember {
        NavStack(TabRootRoute(TabId.Sessions)).apply {
            push(FolderRoute.section(FolderFixtures.SECTION_ID, FolderFixtures.SECTION_NAME), animated = false)
        }
    }

    fun routeOf(kind: FolderDemo): FolderRoute = when (kind) {
        FolderDemo.Section -> FolderRoute.section(FolderFixtures.SECTION_ID, sectionName)
        FolderDemo.Pinned -> FolderFixtures.pinnedRoute
        FolderDemo.Archived -> FolderFixtures.archivedRoute
        FolderDemo.ArchivedEmpty -> FolderFixtures.archivedRoute
    }

    /** The workspace per **route** (a leaving entry keeps its own kind while the push animates). */
    fun workspaceOf(route: FolderRoute): WorkspaceSnapshot = when (folderKind(route.folderId)) {
        FolderKind.Pinned -> FolderFixtures.pinnedWorkspace()
        FolderKind.Archived -> FolderFixtures.archivedWorkspace(
            if (demo == FolderDemo.ArchivedEmpty) emptyList() else FolderFixtures.archivedRows,
        )
        FolderKind.Section -> FolderFixtures.sectionWorkspace(name = sectionName)
    }

    fun select(kind: FolderDemo) {
        demo = kind
        val route = routeOf(kind)
        if (stack.top.route == route) return // already showing (or a re-select)
        // Back to the root, then push the new folder — the same two calls
        // 46's header menu and 100's Settings row will make.
        stack.popToRoot(animated = false)
        stack.push(route, animated = true)
    }

    ActionSheetHost(state = sheets) {
        GlassMenuHost {
            CompositionLocalProvider(LocalRowActions provides actions) {
                Column(Modifier.fillMaxSize().background(palette.background)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val labelStyle = uiStyle(Face.SansMedium, typeScale.fixed(13f), palette.text)
                        for (kind in FolderDemo.entries) {
                            DemoRow(
                                text = "Kind: ${kind.label}",
                                selected = demo == kind,
                                style = labelStyle,
                            ) { select(kind) }
                        }
                        DemoRow(
                            text = if (failWrites) "Fail writes: on" else "Fail writes: off",
                            selected = failWrites,
                            style = labelStyle,
                        ) { failWrites = !failWrites }
                        BasicText(
                            "Last result: $result",
                            style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        NavStackHost(stack = stack, active = true) { entry ->
                            val route = entry.route
                            if (route is FolderRoute) {
                                FolderScreen(route = route, workspace = workspaceOf(route), loading = false)
                            } else {
                                PlaceholderTabRoot(TabId.Sessions)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A toggle row: the label in `accent` when on, `secondary` when off. */
@Composable
private fun DemoRow(text: String, selected: Boolean, style: TextStyle, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val ink = if (selected) palette.accent else palette.secondary
    BasicText(
        text,
        style = style.copy(color = ink),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews: the section needs the alert and toast hosts around it (the
// real gallery's GalleryProviders provides them); its own ActionSheetHost
// and GlassMenuHost come with the section.
// ---------------------------------------------------------------------------

@Preview(name = "folder screens gallery light")
@Composable
private fun FolderScreensGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        AlertHost { ToastHost { FolderScreensGallerySection() } }
    }
}

@Preview(name = "folder screens gallery dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderScreensGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        AlertHost { ToastHost { FolderScreensGallerySection() } }
    }
}
