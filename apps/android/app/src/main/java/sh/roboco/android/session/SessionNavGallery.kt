package sh.roboco.android.session

import android.content.res.Configuration
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.DemoRoute
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.shell.LocalAppRouter
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavDemoScreen
import sh.roboco.android.shell.NavEntry
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.transcript.FramePoster
import sh.roboco.android.transcript.ModelPipeline
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.HostCapabilities
import uniffi.roboco_core.HostInfo
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.LiveStatus
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.RoomState
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView

/**
 * The "Session push/pop" gallery page (android-ui-parity #85) — the
 * choreography and the lifecycle together:
 *
 * - a 402 × 874 dp framed, active [NavStackHost] over
 *   `NavStack(DemoRoute("Sessions"))`; "Push session" pushes a
 *   [GallerySessionRoute] whose content is the fake bottom stack (the
 *   50 dp glass capsule) and the fake jump circle, both alpha'd by 85's
 *   multipliers, and wires a real [SessionNavLifecycle] on fakes into
 *   [SessionNavEffects];
 * - a live readout under the frame — `phase · p · stack α · jump α` —
 *   recomposing per transition frame (a debug readout is the one place
 *   the alphas are read in composition);
 * - an event log (`reattach`, `didAppear`, `leave`, `detach`, `markSeen`,
 *   `close`) — the iOS lifecycle made visible;
 * - predictive back works inside the gallery activity, and the gallery's
 *   "Force reduce motion" toggle switches the reduced branch through
 *   [rememberSessionNavAlpha]'s `LocalReduceMotion` read.
 *
 * Foundation `BasicText` and plain clickable rows only — no Material.
 * Registered in `debug/…/design/Gallery.kt` as "Session push/pop"
 * (slug `session_push_pop`).
 */

/**
 * The demo session route: a **plain class** (identity equality, like
 * 76's real route — two pushes are two distinct sessions), hiding the
 * bottom chrome like the real one.
 */
internal class GallerySessionRoute : NavRoute {
    override val hidesBottomChrome: Boolean = true
    override val paneTitle: String = "Session"
}

/** The per-entry retention key of the gallery demo's lifecycle. */
private object GallerySessionNavKey

/**
 * The framed host of the gallery, the goldens and the previews: the
 * 402 × 874 dp hairline-bordered box with an active [NavStackHost]; the
 * root screen is ticket 24's own [NavDemoScreen], the session screens
 * come from [session].
 */
@Composable
internal fun SessionNavGalleryFrame(
    stack: NavStack,
    session: @Composable (NavEntry) -> Unit,
) {
    Box(
        Modifier
            .size(402.dp, 874.dp)
            .hairlineBorder(RobocoTheme.palette.hairline, RectangleShape),
    ) {
        NavStackHost(stack, active = true) { entry ->
            if (entry.route is GallerySessionRoute) session(entry) else NavDemoScreen(entry)
        }
    }
}

/**
 * The pure-visual session page of the goldens and the previews: the nav
 * alpha pair with no lifecycle and no log, over the fake chrome. The
 * frozen (pre-built) stacks the goldens capture render statically.
 */
@Composable
internal fun SessionNavFixtureScreen() {
    val entry = LocalNavEntry.current
    val stack = LocalNavStack.current
    val nav = rememberSessionNavAlpha(stack, entry)
    CompositionLocalProvider(LocalSessionNavAlpha provides nav) {
        ProvideHazeState { SessionNavFakeChromePage(nav) }
    }
}

/**
 * The fake bottom stack and jump of the iOS reference
 * (`SessionViewController.swift:148-165`): the composer stand-in — a
 * 50 dp [GlassSurface] capsule (default tint), 12 dp from the frame's
 * sides and 8 dp above its bottom — and the jump stand-in — ticket 08's
 * 40 dp [GlassCircleButton] with the `arrow.down` glyph, 16 dp in from
 * the trailing edge and 12 dp above the capsule's top. Both alphas are
 * read **only** inside their `graphicsLayer` blocks (snapshot reads, no
 * recomposition), through the exact multipliers 74 and 131 would apply.
 */
@Composable
internal fun SessionNavFakeChromePage(nav: SessionNavAlpha, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    Box(modifier.fillMaxSize().background(palette.background)) {
        // The fake bottom stack, 74's own `bottom-stack` tag.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                .graphicsLayer { alpha = nav.stackAlpha() }
                .testTag(BottomStackTags.Stack),
        ) {
            GlassSurface(modifier = Modifier.fillMaxWidth().height(50.dp)) {}
        }
        // The fake jump button: 8 (above frame bottom) + 50 (capsule) + 12
        // (gap) = 70 dp above the frame's bottom edge, 16 in from the
        // trailing edge — 131's own visibility is forced to full here; the
        // nav multiplier is the only fade.
        GlassCircleButton(
            icon = RobocoIcons.ArrowDown,
            contentDescription = "Jump to latest",
            onClick = {},
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 70.dp)
                .graphicsLayer { alpha = nav.jumpAlpha() }
                .testTag("jump-to-latest"),
            size = 40.dp,
            iconPt = 15f,
            ink = palette.text,
        )
    }
}

/**
 * The gallery's session screen: the nav alpha plus a **real**
 * [SessionNavLifecycle] on fakes (a throwaway [SessionController] over a
 * fake core, a fake-engine [TranscriptState], logging adapters), wired
 * through [SessionNavEffects] exactly as 76's route does — so the log
 * shows the real callback choreography of a push, a predictive back and
 * a pop.
 */
@Composable
private fun SessionNavDemoSession(
    entry: NavEntry,
    onLog: (String) -> Unit,
    onSession: (NavEntry, SessionNavAlpha) -> Unit,
) {
    val stack = LocalNavStack.current
    val nav = rememberSessionNavAlpha(stack, entry)
    // A debug readout hook: the newest session's entry + alpha object.
    SideEffect { onSession(entry, nav) }
    val router = remember { GalleryNavRouter(onLog) }
    val lifecycle = remember(entry) {
        entry.retain(GallerySessionNavKey) { galleryNavLifecycle(onLog) }
    }
    CompositionLocalProvider(LocalSessionNavAlpha provides nav, LocalAppRouter provides router) {
        SessionNavEffects(lifecycle, entry)
        ProvideHazeState { SessionNavFakeChromePage(nav) }
    }
}

/** The gallery's section entry point (registered in `Gallery.kt`). */
@Composable
fun SessionNavGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val stack = remember { NavStack(DemoRoute("Sessions")) }
    val logLines = remember { mutableStateListOf<String>() }
    var sessionEntry by remember { mutableStateOf<NavEntry?>(null) }
    var sessionNav by remember { mutableStateOf<SessionNavAlpha?>(null) }
    val onLog: (String) -> Unit = { line ->
        logLines.add(0, line)
        if (logLines.size > 12) logLines.removeAt(logLines.size - 1)
    }

    Column {
        SessionNavGalleryFrame(stack) { entry ->
            SessionNavDemoSession(entry, onLog) { e, nav ->
                sessionEntry = e
                sessionNav = nav
            }
        }
        // The live readout: phase · p · stack α · jump α. Read in
        // composition on purpose — a debug readout is the one place the
        // multipliers may recompose per frame.
        val entry = sessionEntry
        val nav = sessionNav
        val t = stack.transition
        val phase = if (entry != null) {
            sessionNavPhase(
                hasTransition = t != null,
                kind = t?.kind,
                isTo = t?.to === entry,
                isFrom = t?.from === entry,
                isTop = stack.top === entry,
            )
        } else {
            SessionNavPhase.IdleTop
        }
        val readout = if (entry != null && nav != null) {
            String.format(
                Locale.ROOT,
                "%s · %.2f · %.2f · %.2f",
                phase.name,
                t?.progress ?: 1f,
                nav.stackAlpha(),
                nav.jumpAlpha(),
            )
        } else {
            "no session yet"
        }
        BasicText(
            readout,
            style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary),
            modifier = Modifier.padding(top = 8.dp),
        )
        SessionNavRow("Push session") { stack.push(GallerySessionRoute()) }
        SessionNavRow("Pop") { stack.pop() }
        SessionNavRow("Clear log") { logLines.clear() }
        for (line in logLines) {
            BasicText(line, style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary))
        }
    }
}

/** One 44 dp hit row with an accent-coloured label (24's `DemoRow` look). */
@Composable
private fun SessionNavRow(text: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClickLabel = text, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent))
    }
}

// ---------------------------------------------------------------------------
// The fakes: a logging router, a logging core, a fake-engine transcript
// ---------------------------------------------------------------------------

/** The gallery's router: only the two presence callbacks, logged. */
private class GalleryNavRouter(private val onLog: (String) -> Unit) : AppRouter {
    override fun openSession(chatId: String) {}
    override fun openSession(chatId: String, handoff: DraftHandoff?) {}
    override fun presentNewSession(prompt: String?) {}
    override fun showSettings() {}
    override fun showSearch() {}
    override fun showSessionOpenError() {}
    override fun popToFrontPage() {}
    override fun sessionDidAppear(chatId: String) = onLog("didAppear")
    override fun sessionDidClose(chatId: String) = onLog("close")
}

/**
 * The gallery's [SessionCore] fake: a minimal composer fixture and a
 * call log that feeds the on-screen log. `setViewAttached` is the
 * reattach/detach line, `markSeen` its own.
 */
private class GallerySessionCore(private val onLog: (String) -> Unit) : SessionCore {
    override val chatId: String = "gallery-session"
    override val engineId: String = "11111111-1111-4111-8111-111111111111"

    override fun composer(): ComposerState = ComposerState(
        chatId = chatId,
        revision = 1uL,
        title = "Session",
        host = HostInfo(
            deviceId = "device-1",
            name = "Vu-PC",
            online = true,
            capabilities = HostCapabilities(
                messageQueue = true,
                queueActions = true,
                queueAttachments = true,
                cleanAttachmentText = true,
                queueEditLease = true,
                queuedAttachments = true,
                midTurnSteering = null,
            ),
        ),
        live = LiveStatus(
            indicator = ChatIndicator.IDLE,
            turnRunning = false,
            workingSinceMs = null,
            streaming = false,
            canInterrupt = false,
        ),
        queue = emptyList(),
        pendingSends = emptyList(),
        sendState = null,
        deliveryDegraded = false,
        room = RoomState(connected = true, retryAtMs = null, degraded = false),
        transferProgress = null,
        openInput = null,
        queueError = null,
        lastSubmittedMessageId = null,
        contextUsage = null,
        limit = null,
        scheduledResumeFireAtMs = null,
    )

    override fun sessionRow(): SessionRow? = null
    override fun sessionConfig(): ChatConfig? = null
    override fun engine(): EngineView? = null
    override fun attachTranscript(view: TranscriptView): Boolean = true

    override fun setViewAttached(attached: Boolean) {
        onLog(if (attached) "reattach" else "detach")
    }

    override fun markSeen() = onLog("markSeen")
}

/** The gallery's transcript engine: no FFI, no frames, no-op shutdown. */
private class GalleryTranscriptEngine : TranscriptView(NoHandle) {
    override fun setViewport(width: Float, textScale: Float) {}
    override fun frame(): LayoutFrame = error("the nav gallery never asks the engine for a frame")
    override fun shutdown() {}
    override fun close() {}
}

/** The gallery's poster: no relay hop ever fires (no frames arrive). */
private object GalleryPoster : FramePoster {
    override fun post(callback: Choreographer.FrameCallback) {}
    override fun remove(callback: Choreographer.FrameCallback) {}
}

/**
 * Builds the demo lifecycle. The controller is a real ticket-51
 * controller over the fake core — `isMainThread = { false }` because the
 * gallery composes on main and the fake core makes no FFI call, and
 * `Dispatchers.Unconfined` so the log fills the moment a lifecycle
 * method runs (a debug gallery, not production threading). The transcript
 * is a real ticket-61 state over the fake engine, so `isClosed` is real.
 */
private fun galleryNavLifecycle(onLog: (String) -> Unit): SessionNavLifecycle {
    val core = GallerySessionCore(onLog)
    val controller = SessionController.create(
        core = core,
        hub = SessionEventHub(post = { it.run() }),
        io = Dispatchers.Unconfined,
        isMainThread = { false },
    )
    val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { runnable ->
        Thread(runnable, "session-nav-gallery-models").apply { isDaemon = true }
    }
    val transcript = TranscriptState(
        text = TextSystem(NoHandle),
        poster = GalleryPoster,
        makeModels = { onUpgradeBuilt ->
            ModelPipeline(worker = worker, postMain = { it.run() }, onUpgradeBuilt = onUpgradeBuilt)
        },
        makeEngine = { _, _ -> GalleryTranscriptEngine() },
    )
    return SessionNavLifecycle(
        chatId = core.chatId,
        controller = controller,
        transcript = transcript,
        // `leave()` logs once, from its last step (the save); the demo has
        // no queued edit to cancel.
        edits = QueuedEditCanceller { },
        drafts = DraftSaver { onLog("leave") },
    )
}

// ---------------------------------------------------------------------------
// Previews (step 8): a pop frozen at 0.5, light and dark. The stack is
// built with holdHostlessOps() so the unanimated push completes and pop()
// leaves a freezable transition; `freeze(0.5f)` marks it interactive, so
// the host's driver never animates it.
// ---------------------------------------------------------------------------

@Composable
private fun SessionNavPreviewContent() {
    RobocoTheme {
        val stack = remember {
            NavStack(DemoRoute("Sessions")).apply {
                holdHostlessOps()
                push(GallerySessionRoute(), animated = false)
                pop()
                freeze(0.5f)
            }
        }
        SessionNavGalleryFrame(stack) { SessionNavFixtureScreen() }
    }
}

@Preview(name = "session pop 0.5 light", widthDp = 402, heightDp = 874)
@Composable
private fun SessionNavPopHalfLightPreview() {
    SessionNavPreviewContent()
}

@Preview(name = "session pop 0.5 dark", widthDp = 402, heightDp = 874, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionNavPopHalfDarkPreview() {
    SessionNavPreviewContent()
}
