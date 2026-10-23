package sh.roboco.android.sidechats

import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.RobocoApplication
import sh.roboco.android.core.Face
import sh.roboco.android.core.TextEngine
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.design.uiStyle
import sh.roboco.android.image.ImageViewerRequest
import sh.roboco.android.image.LocalImageViewer
import sh.roboco.android.session.SessionEventHub
import sh.roboco.android.session.SessionHeader
import sh.roboco.android.session.SessionHeaderDefaults
import sh.roboco.android.session.copyTranscript
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavEntry
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.transcript.AttachmentReader
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptHost
import sh.roboco.android.transcript.TranscriptImageBinding
import sh.roboco.android.transcript.engineReconnects
import sh.roboco.android.transcript.TranscriptImageLoader
import sh.roboco.android.transcript.TranscriptState
import sh.roboco.android.transcript.rememberLinkRouter
import sh.roboco.android.transcript.transcriptBottomInset
import sh.roboco.android.transcript.widgets.imageTileRadius
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.SessionHandle
import uniffi.roboco_core.TextSystem

/** The viewer's copy, verbatim from upstream #899's `SubagentViewController`. */
object SubagentCopy {
    const val Subagent = "Subagent"
    const val NothingYet = "Nothing from this subagent yet."
    const val OpenFailed = "Couldn't open this subagent."
}

/**
 * The header subtitle: iOS's `"Subagent · <parent title>"`, with the
 * Roboco addition of the live state while the subagent is not done —
 * `"Running · …"` while it works (a background subagent keeps running
 * after its spawn call resolved) and `"Failed · …"` once it failed. Pure.
 */
fun subagentSubtitle(state: AgentState, parentTitle: String): String {
    val word = when (state) {
        AgentState.Running -> "Running"
        AgentState.Done -> SubagentCopy.Subagent
        AgentState.Failed -> "Failed"
    }
    return if (parentTitle.isBlank()) word else "$word · $parentTitle"
}

/**
 * A subagent's transcript, read-only (android-next #06, the port of
 * upstream #899's `SubagentViewController`): the doc a spawn card links
 * to. Roboco's engine serves that doc itself — the same
 * `WatchDocMessages` stream desktop's subagent tab subscribes to
 * (`crates/ui` `watch_subagent_doc`), never #899's edge blob — so the
 * transcript is live while the subagent runs, including the claude-code
 * `run_in_background` shape whose spawn call resolved long before the
 * subagent finished, stops changing once it is done, and streams again if
 * it resumes. No composer: the parent agent drives the subagent.
 *
 * [state] follows the spawn chip in the transcript that opened this
 * viewer ([spawnerId]: the chat, or the subagent above for a nested
 * spawn), re-read on that transcript's `SessionChanged` — the same chip
 * the card's running dots read, so the two never disagree.
 *
 * Built off main by [buildSubagentRoute]; the transcript state is made on
 * the first main-thread read. [start] runs on first composition and
 * [release] once, through the entry's close safety net (a committed pop).
 */
@Stable
class SubagentRoute internal constructor(
    /** The subagent's doc id (scoped): the read-only session's key. */
    val docId: String,
    /** The spawn's description — the bar title. */
    val title: String,
    /** The transcript whose spawn chip this is (its chat, or the subagent above). */
    val spawnerId: String,
    /**
     * The chat at the root of the spawn chain ([spawnerId] itself unless
     * this is a nested spawn): relative image references resolve against
     * its cwd, which a subagent doc id has none of.
     */
    val rootChatId: String,
    /** The spawner's display title, for the subtitle. */
    val parentTitle: String,
    /** The engine device that serves attachments (`composer.host.deviceId`). */
    val hostDeviceId: String,
    private val client: CoreClient,
    private val handle: SessionHandle,
    private val hub: SessionEventHub,
    /** The app-lifetime scope teardown and state reads ride on (never the entry's). */
    private val appScope: CoroutineScope,
    private val text: TextSystem,
    initialState: AgentState,
    /** One serial lane per viewer: attach, the state reads and detach keep their order. */
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : NavRoute {

    override val hidesBottomChrome: Boolean get() = true

    override val paneTitle: String get() = title

    /** The subagent's lifecycle, as its spawn chip says. Snapshot state (main). */
    var state: AgentState by mutableStateOf(initialState)
        private set

    private var transcriptImpl: TranscriptState? = null

    /** The layout engine. MAIN THREAD ONLY: created on first read. */
    val transcript: TranscriptState
        get() = transcriptImpl ?: TranscriptState(text).also { transcriptImpl = it }

    private var started = false
    private var released = false
    private var unregister: (() -> Unit)? = null

    /**
     * First composition (main): bind the layout engine to the read-only
     * session and mark it on screen — on the serial lane, attach first
     * (iOS `setViewAttached` then `engine.attach`; the order is not
     * observable, the frames come from the same handle) — then follow the
     * spawn chip's state.
     */
    internal fun start() {
        if (started || released) return
        started = true
        val view = transcript.engine
        appScope.launch(io) {
            try {
                if (!view.attach(client, docId)) Log.w(TAG, "subagent transcript attach returned false")
                handle.setViewAttached(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "subagent attach failed", t)
            }
        }
        unregister = hub.register(spawnerId) { refreshState() }
        refreshState()
    }

    /** Re-read the spawn chip off main; publish on main. */
    private fun refreshState() {
        if (released) return
        appScope.launch(io) {
            val next = try {
                // A fresh wrapper per read: close it here (the core keeps the
                // session itself warm), never leave it to the GC.
                client.session(spawnerId)?.let { spawner ->
                    try {
                        spawner.subagents().firstOrNull { it.docId == docId }?.let { agentState(it.state) }
                    } finally {
                        spawner.close()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "subagent state read failed", t)
                null
            } ?: return@launch
            withContext(Dispatchers.Main) {
                if (!released) state = next
            }
        }
    }

    /**
     * The committed pop (the entry's close safety net): stop following the
     * chip, close the layout engine (main), then detach and close the
     * session on the lane — the doc stays warm in the core for a re-open.
     * Idempotent.
     */
    fun release() {
        if (released) return
        released = true
        unregister?.invoke()
        unregister = null
        transcriptImpl?.close()
        appScope.launch(io) {
            try {
                handle.setViewAttached(false)
                client.closeSession(docId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "subagent release failed", t)
            }
        }
    }

    /**
     * The image reader for the transcript (the host engine's `readImage`).
     * Relative references resolve against the root chat's cwd
     * ([rootChatId]), however deep the spawn.
     */
    internal val attachments: AttachmentReader = object : AttachmentReader {
        override suspend fun read(engineDeviceId: String, reference: String): ByteArray =
            client.readImage(engineDeviceId, reference, rootChatId)
    }

    @Composable
    fun Content() {
        val entry = LocalNavEntry.current
        // The close safety net (the NavEntry contract): any removal path
        // releases exactly once.
        remember(entry) {
            entry.retain(CloseKey) {
                object : AutoCloseable {
                    override fun close() = release()
                }
            }
        }
        remember(entry) {
            start()
            true
        }
        SubagentScreen(this)
    }

    private object CloseKey

    private companion object {
        const val TAG = "RobocoSubagent"
    }
}

/** What a spawn card (or a sheet row) asks to open. */
data class SubagentOpenRequest(
    val docId: String,
    val title: String,
    /** The card said it had finished (done or failed). */
    val frozen: Boolean,
    /** The transcript the card sits in: the chat, or the subagent above. */
    val spawnerId: String,
    val parentTitle: String,
    /** The chat at the root of the spawn chain (relative images resolve in its cwd). */
    val rootChatId: String = spawnerId,
)

/**
 * Off main: open the subagent's doc read-only (`CoreClient.openSubagent` —
 * the cache seed and the subscription start, no round trip) and build its
 * route. `null` when there is no client or the core refused the id.
 */
suspend fun buildSubagentRoute(
    app: RobocoApplication,
    request: SubagentOpenRequest,
    io: CoroutineDispatcher = Dispatchers.IO,
): SubagentRoute? = withContext(io) {
    try {
        val client = app.clientOrNull() ?: return@withContext null
        val handle = client.openSubagent(request.docId)
        val host = try {
            handle.composer().host.deviceId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ""
        }
        SubagentRoute(
            docId = request.docId,
            title = request.title,
            spawnerId = request.spawnerId,
            rootChatId = request.rootChatId,
            parentTitle = request.parentTitle,
            hostDeviceId = host,
            client = client,
            handle = handle,
            hub = app.coreStartup.sessionEvents,
            appScope = app.appScope,
            text = TextEngine.shared,
            initialState = if (request.frozen) AgentState.Done else AgentState.Running,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Log.w("RobocoSubagent", "open subagent failed", t)
        null
    }
}

/**
 * Opens subagent viewers over one screen (the session, or a viewer for a
 * nested spawn): the doc opens off main, then the route is pushed — only
 * if this screen is still on top and idle (a late open never pushes over
 * a screen the user already left, the `leaveAfterArchive` rule). One open
 * at a time; a failed open toasts "Couldn't open this subagent."
 */
class SubagentOpener(
    private val app: RobocoApplication,
    private val nav: NavStack,
    private val entry: NavEntry,
    private val scope: CoroutineScope,
    private val toaster: Toaster,
) {
    private var opening = false

    /** Main thread. */
    fun open(request: SubagentOpenRequest) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                val route = buildSubagentRoute(app, request)
                when {
                    route == null -> toaster.show(SubagentCopy.OpenFailed)
                    nav.top !== entry || nav.transition != null -> route.release()
                    else -> nav.push(route, animated = true)
                }
            } finally {
                opening = false
            }
        }
    }
}

/**
 * The opener for the screen composing this (the [LocalNavStack] /
 * [LocalNavEntry] it lives in); `null` outside the app (previews, tests).
 */
@Composable
fun rememberSubagentOpener(): SubagentOpener? {
    val app = LocalContext.current.applicationContext as? RobocoApplication ?: return null
    val nav = LocalNavStack.current
    val entry = LocalNavEntry.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    return remember(app, nav, entry, toaster, scope) { SubagentOpener(app, nav, entry, scope, toaster) }
}

/** The ⋯ menu: one row, Copy Transcript (`SubagentViewController`). Pure. */
fun subagentMenu(onCopyTranscript: () -> Unit): Menu = Menu(
    title = null,
    children = listOf(
        MenuItem(
            title = "Copy Transcript",
            icon = MenuIcon.Symbol(RobocoIcons.DocOnDoc),
            testTag = "subagent-menu-copy",
            action = onCopyTranscript,
        ),
    ),
)

/**
 * The viewer screen: the read-only transcript under the session header
 * (back, the spawn's title over [subagentSubtitle], a ⋯ with Copy
 * Transcript), with iOS's reveal — the spinner until the first row lands,
 * then the transcript; "Nothing from this subagent yet." after 8 s
 * without one. Nested spawn cards open their own viewer on top.
 */
@Composable
internal fun SubagentScreen(route: SubagentRoute, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = RobocoTheme.palette
    val toaster = LocalToaster.current
    val transcript = route.transcript
    val scope = rememberCoroutineScope()
    val menuState = rememberGlassMenuState()
    val opener = rememberSubagentOpener()

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    SideEffect { transcript.topInset = SessionHeaderDefaults.contentTopInset(statusBarTop).value }
    val edgeEngaged by remember(transcript) { derivedStateOf { transcript.viewport.scrollY > 0.5f } }
    val revealed by remember(transcript) { derivedStateOf { (transcript.frame?.rowCount() ?: 0u) > 0u } }
    var gaveUp by remember(route) { mutableStateOf(false) }
    LaunchedEffect(route) {
        delay(GiveUpMs)
        gaveUp = true
    }

    // The transcript's actions — SessionScreen's image and link wiring
    // over this doc's host device, plus nested subagent cards.
    val viewer = LocalImageViewer.current
    val density = LocalDensity.current
    val binding = remember(route) {
        TranscriptImageBinding(
            loader = TranscriptImageLoader.get(context),
            engineDeviceId = { route.hostDeviceId },
            reader = route.attachments,
            retries = engineReconnects(context, { route.hostDeviceId }),
            chatId = route.rootChatId,
        )
    }
    val router = rememberLinkRouter(
        loadFull = { reference -> binding.loadFull(reference) },
        showImage = { reference, source, bitmap ->
            viewer?.show(
                ImageViewerRequest(
                    reference = reference,
                    preview = bitmap,
                    sourceBounds = source,
                    sourceCornerRadius = 0.dp,
                    label = null,
                    loadFull = null,
                ),
            )
        },
    )
    val actions = remember(route, viewer, router, opener) {
        val openImage: (String, Rect, ImageBitmap?, String?) -> Unit = { reference, source, bitmap, label ->
            viewer?.show(
                ImageViewerRequest(
                    reference = reference,
                    preview = bitmap,
                    sourceBounds = source,
                    sourceCornerRadius = with(density) {
                        imageTileRadius(source.width.toDp().value, source.height.toDp().value)
                    },
                    label = label,
                    loadFull = { binding.loadFull(reference) },
                ),
            )
        }
        TranscriptActions(
            openLink = router::open,
            openImage = openImage,
            loadImage = binding::load,
            cachedImage = binding::cached,
            imageRetries = binding.retries,
            openSubagent = { docId, title, frozen ->
                opener?.open(
                    SubagentOpenRequest(
                        docId = docId,
                        title = title,
                        frozen = frozen,
                        spawnerId = route.docId,
                        parentTitle = route.title,
                        rootChatId = route.rootChatId,
                    ),
                )
            },
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .testTag("subagent-viewer"),
    ) {
        ProvideHazeState {
            val haze = LocalHazeState.current
            // The transcript is always composed (rows lay out and the tail
            // is placed while hidden); it shows with its first row.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (haze != null) Modifier.hazeSource(haze) else Modifier)
                    .graphicsLayer { alpha = if (revealed) 1f else 0f },
            ) {
                TranscriptHost(
                    transcript,
                    actions,
                    Modifier
                        .fillMaxSize()
                        .transcriptBottomInset(transcript.viewport, navBarBottom)
                        .testTag("subagent-transcript"),
                )
            }
            if (!revealed) {
                if (!gaveUp) {
                    StatusGlyph(
                        StatusGlyphKind.Spinner,
                        Modifier.align(Alignment.Center).testTag("subagent-loading"),
                        scale = 1.75f,
                    )
                } else {
                    BasicText(
                        SubagentCopy.NothingYet,
                        Modifier.align(Alignment.Center).testTag("subagent-empty"),
                        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(15f), palette.secondary),
                    )
                }
            }
            SessionHeader(
                title = route.title,
                subtitle = subagentSubtitle(route.state, route.parentTitle),
                edgeEngaged = edgeEngaged,
                onMore = { anchor ->
                    menuState.open(
                        anchor,
                        subagentMenu(onCopyTranscript = {
                            scope.launch {
                                val clipboard = context.applicationContext
                                    .getSystemService(ClipboardManager::class.java) ?: return@launch
                                copyTranscript(transcript, clipboard, toaster)
                            }
                        }),
                    )
                },
                statusBarTop = statusBarTop,
            )
        }
    }
}

/** iOS `asyncAfter(deadline: .now() + 8)`: the empty state replaces the spinner. */
private const val GiveUpMs = 8_000L
