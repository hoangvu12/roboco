package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.SessionRow

/**
 * The fake [SessionPromptBackend] for the gallery: the three engines of
 * the ticket's fixture — `studio` "Studio Mac" and `linux` "Linux box"
 * connected, `windows` "Windows desktop" offline — with [setConnected] so
 * the ⋯ buttons can re-shape the list before each launch (the launchers
 * read it at invocation time, so no recomposition is needed).
 */
private class FakeSessionPromptBackend : SessionPromptBackend {

    private val engines = mutableListOf(
        EngineChoice("studio", "Studio Mac", true),
        EngineChoice("linux", "Linux box", true),
        EngineChoice("windows", "Windows desktop", false),
    )

    override fun engines(): List<EngineChoice> = engines.toList()

    /** Restores the two-connected default the ⋯ buttons start from. */
    fun reset() {
        setConnected("studio", true)
        setConnected("linux", true)
        setConnected("windows", false)
    }

    fun setConnected(engineId: String, connected: Boolean) {
        val index = engines.indexOfFirst { it.engineId == engineId }
        if (index >= 0) engines[index] = engines[index].copy(connected = connected)
    }
}

/**
 * The gallery's [RowActions] fake: every call logs to [log] (the "Last
 * result" line, e.g. `createSection(Infra 2, linux)`), `onResult` fires
 * synchronously, and [failMode] imitates ticket 42's offline row for the
 * **section** writes only — the "Engine not connected" toast and
 * [ActionOutcome.EngineOffline] — so "Delete fails (offline)" shows a
 * refused delete that never pops.
 */
private class LoggingRowActions(
    private val toaster: Toaster,
    private val failMode: () -> Boolean,
    private val log: (String) -> Unit,
) : RowActions {

    /** The ticket's fixture: Backend + Design review on Studio Mac, Infra on Linux box. */
    private val sections = listOf(
        SectionView(id = "backend", engineId = "studio", name = "Backend", collapsed = false, sessions = emptyList()),
        SectionView(id = "design", engineId = "studio", name = "Design review", collapsed = false, sessions = emptyList()),
        SectionView(id = "infra", engineId = "linux", name = "Infra", collapsed = false, sessions = emptyList()),
    )

    private fun ok(onResult: (ActionOutcome) -> Unit) = onResult(ActionOutcome.Ok)

    private fun sectionWrite(what: String, onResult: (ActionOutcome) -> Unit, onOk: () -> Unit = {}) {
        log(what)
        if (failMode()) {
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
        sectionWrite("renameSection($sectionId, $name)", onResult)

    override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) =
        sectionWrite("deleteSection($sectionId)", onResult)

    override fun sectionsFor(engineId: String): List<SectionView> = sections.filter { it.engineId == engineId }

    override fun report(error: Throwable) {
        log("report($error)")
    }
}

/** The row the launchers take (44 passes the live record): id, engine and displayed title. */
private fun galleryRow(id: String, engineId: String, title: String): SessionRow = SessionRow(
    id = id,
    engineId = engineId,
    revision = 1uL,
    title = title,
    hasTitle = true,
    preview = null,
    project = null,
    deviceId = "device-$engineId",
    deviceName = null,
    deviceOnline = true,
    engineOnline = true,
    harness = null,
    harnessLabel = null,
    model = null,
    modelLabel = null,
    reasoning = null,
    branch = null,
    cwd = null,
    indicator = ChatIndicator.IDLE,
    hostIndicator = ChatIndicator.IDLE,
    workingSinceMs = null,
    lastActivityMs = 0L,
    timeLabel = "now",
    createdAtMs = 0L,
    unseen = false,
    archived = false,
    pinned = false,
    sectionId = null,
    pullRequest = null,
    sendState = null,
    parentChatId = null,
)

/**
 * The "Session Prompts" page of the debug design gallery
 * (android-ui-parity #43): the five prompt launchers over fakes — the
 * nine buttons of the ticket's step 6, the fail-mode switch, and the last
 * result line the fake [RowActions] writes (`createSection(Infra 2,
 * linux)`, `renamed: API`, `popped`, …).
 *
 * The gallery's `GalleryProviders` already wraps every section in
 * `ContextMenuHost { AlertHost { ToastHost { … } } }`, so this section
 * adds only the innermost [ActionSheetHost] — together exactly ticket
 * 35's mount order, and the alerts/toasts of the launchers draw over the
 * sheets. It also provides the [FakeSessionPromptBackend] and the
 * [LoggingRowActions] as the locals [rememberSessionPrompts] reads.
 *
 * Fill layout (like every section that owns an in-window sheet host): the
 * `ActionSheetHost` needs the body's bounded height — the Scroll frame's
 * column would give it unbounded height and collapse the scrim and the
 * bottom anchoring — so the buttons scroll inside instead. (The ticket's
 * "default Scroll" suggestion cannot host the sheet; the registration
 * line therefore uses `GalleryLayout.Fill`.)
 *
 * Registered in `design/Gallery.kt` as
 * `GallerySection("Session Prompts", GalleryLayout.Fill) { SessionPromptsGallerySection() }`.
 */
@Composable
fun SessionPromptsGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val toaster = LocalToaster.current
    val sheets = remember { ActionSheetHostState() }

    var result by remember { mutableStateOf("—") }
    var failMode by remember { mutableStateOf(false) }
    val backend = remember { FakeSessionPromptBackend() }
    val actions = remember { LoggingRowActions(toaster, { failMode }) { result = it } }
    val c1 = remember { galleryRow("c1", "studio", "Fix flaky test") }

    ActionSheetHost(state = sheets) {
        CompositionLocalProvider(
            LocalSessionPromptBackend provides backend,
            LocalRowActions provides actions,
        ) {
            Box(Modifier.fillMaxSize()) {
                GalleryBackdrop(Modifier.matchParentSize())
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val prompts = rememberSessionPrompts()

                    PromptLabel(
                        "Session Prompts",
                        uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
                    )
                    PromptLabel(
                        "The five prompts over fakes: Studio Mac and Linux box connected, " +
                            "Windows desktop offline; sections Backend + Design review (studio), " +
                            "Infra (linux); row \"Fix flaky test\" on studio.",
                        uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    )
                    PromptLabel(
                        if (failMode) "Fail mode: on (section writes fail offline)" else "Fail mode: off",
                        uiStyle(
                            Face.SansMedium,
                            typeScale.fixed(14f),
                            if (failMode) palette.accent else palette.text,
                        ),
                        Modifier.clickable { failMode = !failMode },
                    )

                    val buttonStyle = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent)
                    PromptLabel("Rename Session", buttonStyle, Modifier.clickable { prompts.renameSession(c1) })
                    PromptLabel(
                        "New Section (⋯, 2 connected)",
                        buttonStyle,
                        Modifier.clickable {
                            backend.reset()
                            prompts.newSection()
                        },
                    )
                    PromptLabel(
                        "New Section (⋯, 1 connected)",
                        buttonStyle,
                        Modifier.clickable {
                            backend.reset()
                            backend.setConnected("linux", false) // Linux box offline first
                            prompts.newSection()
                        },
                    )
                    PromptLabel(
                        "New Section (⋯, none)",
                        buttonStyle,
                        Modifier.clickable {
                            backend.reset()
                            backend.setConnected("studio", false)
                            backend.setConnected("linux", false)
                            prompts.newSection()
                        },
                    )
                    PromptLabel(
                        "New Section (row, offline engine)",
                        buttonStyle,
                        Modifier.clickable {
                            backend.reset()
                            prompts.newSection("windows")
                        },
                    )
                    PromptLabel(
                        "Rename Section",
                        buttonStyle,
                        Modifier.clickable {
                            prompts.renameSection("backend", "Backend") { result = "renamed: $it" }
                        },
                    )
                    PromptLabel("Move to Section", buttonStyle, Modifier.clickable { prompts.moveToSection(c1) })
                    PromptLabel(
                        "Delete Section",
                        buttonStyle,
                        Modifier.clickable {
                            failMode = false
                            prompts.confirmDeleteSection("backend", "Backend") { result = "popped" }
                        },
                    )
                    PromptLabel(
                        "Delete fails (offline)",
                        buttonStyle,
                        Modifier.clickable {
                            failMode = true
                            prompts.confirmDeleteSection("backend", "Backend") { result = "popped" }
                        },
                    )

                    PromptLabel(
                        "Last result: $result",
                        uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    )
                }
            }
        }
    }
}

/** A plain text row; with a `clickable` modifier attached it is a button (no Material controls). */
@Composable
private fun PromptLabel(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText(text, style = style, modifier = modifier.padding(vertical = 2.dp))
}

// ---------------------------------------------------------------------------
// Previews: the section needs the alert and toast hosts around it (the
// real gallery's GalleryProviders provides them); its own ActionSheetHost
// comes with the section.
// ---------------------------------------------------------------------------

@Preview(name = "Session prompts gallery light")
@Composable
private fun SessionPromptsGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        AlertHost { ToastHost { SessionPromptsGallerySection() } }
    }
}

@Preview(name = "Session prompts gallery dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionPromptsGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        AlertHost { ToastHost { SessionPromptsGallerySection() } }
    }
}
