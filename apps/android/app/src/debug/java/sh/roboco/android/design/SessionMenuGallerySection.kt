package sh.roboco.android.design

import android.content.ClipboardManager
import android.content.res.Configuration
import android.view.Choreographer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.session.SessionHeader
import sh.roboco.android.session.copyTranscript
import sh.roboco.android.session.sessionMenu
import sh.roboco.android.sessions.ActionOutcome
import sh.roboco.android.sessions.RowActions
import sh.roboco.android.transcript.FramePoster
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.StyleDesc
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * The "Session ⋯ menu" page of the debug design gallery
 * (android-ui-parity #80): the header's pull-down, live — the real
 * [SessionHeader] (52) with "Fix the deploy script" / "roboco @ studio"
 * over ticket 22's striped [GalleryBackdrop], inside the gallery's own
 * [GlassMenuHost] (15) and [ToastHost] (14). Tapping ⋯ opens the real
 * menu content: Pin/Unpin, Copy Transcript, Archive.
 *
 * Try it live (the rows under the fixture, hidden under the Roborazzi
 * capture):
 * - **Pinned** flips the chat's pin state — the next open shows "Unpin"
 *   with the slash icon (the menu is rebuilt on every open);
 * - **Can write** flips 79's offline flag — Pin and Archive grey out
 *   (inert), Copy Transcript stays live;
 * - **Mode** switches the fake [RowActions] between **Ok** (Archive
 *   shows "Archived · Undo" through the real toaster, then the fake
 *   leave logs "pop") and **Offline** (the write is refused with
 *   "Engine not connected");
 * - **Copy Transcript** copies the fake transcript's frame —
 *   "hi", a `---` separator, "Hello! How can I help?" — to the real
 *   clipboard (API 31–32 toasts "Copied", 33+ shows the system overlay);
 * - the log lists the last calls ("pin c1 true", "archive c1 → Ok").
 *
 * Registered in `Gallery.sections` as "Session ⋯ menu" (slug
 * `session_menu`); the panel itself, its placement and animation are
 * ticket 15's (goldens in `SessionMenuScreenshotTest`, not here).
 */
@Composable
fun SessionMenuGallerySection() {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var pinned by remember { mutableStateOf(false) }
    var canWrite by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    // The fake transcript: a NoHandle engine whose frames carry the
    // ticket's copy fixture. Built on main, as the production one is.
    val transcript = remember {
        TranscriptState(TextSystem(NoHandle), SessionMenuNoopPoster) { _, _ -> SessionMenuEngine() }
    }

    // The fake RowActions: records every call, refuses writes while
    // Offline, toasts through the real toaster. The Undo outlives the
    // menu (and the gallery section) like ticket 42's does.
    val actions = remember {
        object : RowActions {
            override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {
                log += "pin $chatId $pinned"
                onResult(if (offline) ActionOutcome.EngineOffline else ActionOutcome.Ok)
            }

            override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {
                if (offline) {
                    log += "archive $chatId → EngineOffline"
                    toaster.show("Engine not connected")
                    onResult(ActionOutcome.EngineOffline)
                } else {
                    log += "archive $chatId → Ok"
                    toaster.show("Archived", "Undo") { unarchive(chatId) { log += "unarchive $chatId" } }
                    onResult(ActionOutcome.Ok)
                }
            }

            override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {
                onResult(ActionOutcome.Ok)
            }
            override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {
                onResult(ActionOutcome.Ok)
            }

            override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {}
            override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {}
            override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {}
            override fun createSection(
                name: String,
                engineId: String?,
                onResult: (ActionOutcome) -> Unit,
                onCreated: (String) -> Unit,
            ) {}
            override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {}
            override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {}
            override fun sectionsFor(engineId: String): List<SectionView> = emptyList()
            override fun report(error: Throwable) {}
        }
    }

    ToastHost {
        GlassMenuHost(hazeState = rememberHazeState()) {
            ProvideHazeState {
                Column(Modifier.fillMaxWidth()) {
                    // The fixture: the header over the striped backdrop.
                    // The menu's scrim covers the whole host while open,
                    // so the live rows below stay inert until it closes.
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        GalleryBackdrop(Modifier.matchParentSize().hazeSource(LocalHazeState.current!!))
                        val menuState = rememberGlassMenuState()
                        SessionHeader(
                            title = "Fix the deploy script",
                            subtitle = "roboco @ studio",
                            edgeEngaged = true,
                            onMore = { anchor ->
                                menuState.open(
                                    anchor,
                                    sessionMenu(
                                        pinned = pinned,
                                        canWrite = canWrite,
                                        onSetPinned = { p -> actions.setPinned("c1", p) },
                                        onCopyTranscript = {
                                            scope.launch {
                                                val clipboard =
                                                    context.getSystemService(ClipboardManager::class.java)
                                                        ?: return@launch
                                                copyTranscript(transcript, clipboard, toaster)
                                            }
                                        },
                                        onShowPreviews = {},
                                        onShowAccounts = {},
                                        onArchive = {
                                            actions.archive("c1") { outcome ->
                                                // 80's leave: only a
                                                // successful archive moves
                                                // away; here it just logs.
                                                if (outcome == ActionOutcome.Ok || outcome == ActionOutcome.Gone) {
                                                    log += "pop"
                                                }
                                            }
                                        },
                                    ),
                                )
                            },
                            showBack = true,
                            onBack = { log += "back" },
                            statusBarTop = 24.dp,
                        )
                    }
                    if (!LocalGalleryCapture.current) {
                        SessionMenuControls(
                            pinned = pinned,
                            canWrite = canWrite,
                            offline = offline,
                            onPinned = { pinned = it },
                            onCanWrite = { canWrite = it },
                            onOffline = { offline = it },
                            log = log,
                        )
                    }
                }
            }
        }
    }
}

/** The live toggles and the call log (never in the Roborazzi capture). */
@Composable
private fun SessionMenuControls(
    pinned: Boolean,
    canWrite: Boolean,
    offline: Boolean,
    onPinned: (Boolean) -> Unit,
    onCanWrite: (Boolean) -> Unit,
    onOffline: (Boolean) -> Unit,
    log: List<String>,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SessionMenuToggleRow("Pinned", if (pinned) "on" else "off") { onPinned(!pinned) }
        SessionMenuToggleRow("Can write", if (canWrite) "on" else "off") { onCanWrite(!canWrite) }
        SessionMenuToggleRow("Mode", if (offline) "Offline" else "Ok") { onOffline(!offline) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                "log",
                style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
            )
            log.takeLast(6).forEach { line ->
                BasicText(
                    line,
                    style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.tertiary),
                )
            }
        }
    }
}

/** The "label … value" toggle row (the chip-menu gallery's row shape). */
@Composable
private fun SessionMenuToggleRow(label: String, value: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        Spacer(Modifier.weight(1f))
        BasicText(value, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent))
    }
}

// ---------------------------------------------------------------------------
// The fake transcript (the ticket's copy fixture), on the UniFFI NoHandle
// fakes like the session tests — no JNA, no real engine.
// ---------------------------------------------------------------------------

/** A frame whose whole transcript is the ticket's copy fixture. */
private class SessionMenuFrame : LayoutFrame(NoHandle) {
    override fun revision() = 1uL
    override fun styleCount() = 0u
    override fun styles(): List<StyleDesc> = emptyList()
    override fun rowCount() = 0u
    override fun totalHeight() = 0f
    override fun plainText() = "hi\n\n---\n\nHello! How can I help?"
    override fun close() {}
}

/** Serves a fresh fixture frame per `frame()` call. */
private class SessionMenuEngine : TranscriptView(NoHandle) {
    override fun setViewport(width: Float, textScale: Float) {}
    override fun frame(): LayoutFrame = SessionMenuFrame()
    override fun shutdown() {}
    override fun close() {}
}

/** A poster that never fires: no relay hop, so no frame is ever applied. */
private object SessionMenuNoopPoster : FramePoster {
    override fun post(callback: Choreographer.FrameCallback) {}
    override fun remove(callback: Choreographer.FrameCallback) {}
}

// ---------------------------------------------------------------------------
// Previews: the fixture in light and dark.
// ---------------------------------------------------------------------------

@Preview(name = "Session ⋯ menu light")
@Composable
private fun SessionMenuGalleryLightPreview() {
    RobocoTheme(Appearance.Light) { SessionMenuGallerySection() }
}

@Preview(name = "Session ⋯ menu dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionMenuGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) { SessionMenuGallerySection() }
}
