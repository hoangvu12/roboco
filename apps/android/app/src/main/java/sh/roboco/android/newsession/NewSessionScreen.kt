package sh.roboco.android.newsession

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import sh.roboco.android.RobocoApplication
import sh.roboco.android.composer.ComposerBar
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.SendResult
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.composer.rememberComposerController
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.notify.NotificationPermission
import sh.roboco.android.notify.findActivity
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.shell.LocalPageSheetScope
import uniffi.roboco_core.FileMatch

/**
 * The New Session page (android-ui-parity #93) — the port of
 * `NewSessionViewController` (`ios:Session/NewSessionViewController.swift`),
 * a composer-first canvas: "tap, type, send".
 *
 * Phone: the content of 31's large-detent page sheet (32 presents it — the
 * ✕ is the sheet bar's). Tablet (106): embedded in the detail column with
 * `embedded = true`, no ✕, and the caller's one-shot [focusOnAppear].
 *
 * This entry builds 92's page model over the app's single graph; everything
 * after that lives in [NewSessionScreenBody] so tests can drive the real
 * page over a fake model (the built-in catalog is FFI — a fake catalog
 * keeps the JVM honest, as in 92's model tests).
 *
 * [onCreated] is 94's: it fires after the create + first send, with the
 * captured handoff (or null when the page left composition mid-flight).
 */
@Composable
fun NewSessionScreen(
    prompt: String?,
    embedded: Boolean,
    onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit,
    onClose: () -> Unit = {},
    /** iOS `!embedded`; 106 passes its one-shot flag (launch false, ⌘N true). */
    focusOnAppear: Boolean = !embedded,
    /** The `@file` search seam; tests pass a fake. */
    fileSearch: NewSessionFileSearch = rememberCoreFileSearch(),
    modifier: Modifier = Modifier,
) {
    val ns = rememberNewSessionModel(prompt)
    NewSessionScreenBody(ns, embedded, onCreated, onClose, focusOnAppear, fileSearch, modifier)
}

/**
 * The page over a built model: the composer (created with 92's
 * `initialText` and seeded with its `initialImages` **before the first
 * frame**, like iOS's `init`), 92's remember-on-close/background effect,
 * the morph pinned to the card (`alwaysCard`), the focus-on-appear wait,
 * the canvas — and 94's create + first send: the handoff capture state
 * on the page and composer roots, the offline `canWrite` gate (Send
 * disabled, chips live) and the `onSend` handler that creates the chat,
 * captures the handoff and calls `onCreated`.
 */
@Composable
internal fun NewSessionScreenBody(
    ns: NewSessionModel,
    embedded: Boolean,
    onCreated: (chatId: String, handoff: DraftHandoff?) -> Unit = { _, _ -> },
    onClose: () -> Unit,
    focusOnAppear: Boolean,
    fileSearch: NewSessionFileSearch,
    modifier: Modifier = Modifier,
    // The chip title chain's labels: the core's catalog in the app; tests and
    // previews pass GalleryLabels to stay FFI-free (FakeCatalog's intent).
    labels: ChipLabels = CoreChipLabels,
    // android-next #06: the side-chat draft's hero line and its own bar.
    headline: String = NewSessionHeadline,
    navBar: Boolean = true,
) {
    val composer = rememberComposerController(initialText = ns.initialText)

    // ── 94: the handoff capture, the offline gate, the send handler ─────

    // The capture state: the page root records the scene, the composer
    // root records the composer. The sheet's own shape (31's 38 dp top
    // corners) rides into the record — 95 clips the scene picture with
    // it; the embedded column has no sheet scope, so a rectangle. A
    // plain field, set in composition, before the first draw.
    val capture = rememberHandoffCaptureState()
    capture.sceneShape = LocalPageSheetScope.current?.pageShape ?: RectangleShape

    // The send handler: 92's page model, the core client (71's holder —
    // `null` before the startup load finishes fails exactly like iOS's
    // `guard let client`), the ambient alerts, the activity for the
    // one-time notification ask (android-app#28) and the capture.
    val alerts = LocalAlerts.current
    val context = LocalContext.current
    val app = context.applicationContext as RobocoApplication
    // Unwrap the ContextWrapper chain to the activity (the ask needs a
    // ComponentActivity; a preview context unwraps to null and the ask
    // is skipped).
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    val latestOnCreated by rememberUpdatedState(onCreated)
    val handler = remember {
        NewSessionSendHandler(
            page = ModelNewSessionPage(ns),
            creator = NewSessionCreator(CoreNewSessionCore { app.clientOrNull() }),
            alerts = alerts,
            askAfterFirstSession = {
                activity?.let { target -> NotificationPermission.askAfterFirstSession(target) }
            },
            capture = capture::capture,
            onCreated = { chatId, handoff -> latestOnCreated(chatId, handoff) },
        )
    }

    // The offline gate: Send disabled while the target engine is offline
    // (79's rule: no grace, no debounce); the chips stay live — the
    // canvas passes `chipsCanWrite = true` below, so the user can move
    // off an offline engine. Reading the value here (not only inside
    // the SideEffect) makes a flip recompose this scope, so the push
    // below always sees the fresh value.
    val canWrite by remember {
        derivedStateOf {
            val o = ns.currentOptions
            newSessionCanWrite(ns.draft, o?.projects ?: emptyList(), o?.hosts ?: emptyList())
        }
    }
    val canWriteNow = canWrite
    SideEffect { composer.canWrite = canWriteNow }

    remember(composer) {
        // Runs once, before the first frame (iOS sets these in `init` and
        // `viewDidLoad`).
        bindNewSessionComposer(composer, project = { ns.project }, ns.initialImages, fileSearch)
        // android-next #06: a side chat searches its parent's project (iOS
        // `sideOf`: the parent's device and space; none when projectless).
        ns.side?.let { side -> composer.mentionSearch = sideChatMentionSearch(side, fileSearch) }
        // 94: the real send handler replaces the `Kept` stub the bind
        // installs for direct-bind tests.
        composer.onSend = { text, images, _ -> handler.send(text, images) }
        true
    }
    NewSessionDraftEffect(ns, composer)
    // 109: the chip strip's data. 113: the project / run-on menu map —
    // the page's liveness guard (the iOS [weak self] of the browser's
    // onCreated), 110's presenter seam, and the menu glue over 92's
    // model, whose providers read the live model at tap time. 114: the
    // checkout / model / effort map (refs through 71's client).
    val alive = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    val newProjectPresenter = rememberNewProjectPresenter()
    val projectMenus = remember(ns, newProjectPresenter) {
        ProjectRunOnMenus(ns, newProjectPresenter, alive::get)
    }
    val refSource = remember { CoreRefSource(client = { app.clientOrNull() }) }
    val checkoutMenus = remember(ns) {
        CheckoutModelEffortMenus(
            draft = { ns.draft },
            project = { ns.project },
            models = { ns.models },
            selectedModel = { ns.selectedModel },
            update = ns::update,
            refSource = refSource,
        )
    }
    val chipMenus = remember(projectMenus, checkoutMenus) {
        newSessionChipMenus(projectMenus.providers(), checkoutMenus.providers())
    }
    NewSessionChipsEffect(ns, composer, chipMenus, labels)
    val morph = rememberComposerMorphState(
        alwaysCard = true,
        hasImages = { composer.images.isNotEmpty() },
        hideSuggestions = { composer.hideSuggestions() },
    )
    val keyboard = LocalSoftwareKeyboardController.current
    val canvasState = remember { NewSessionCanvasState() }
    NewSessionFocusOnAppear(focusOnAppear, embedded, canvasState) { morph.focus() }
    NewSessionCanvas(
        harness = ns.draft.harness,
        embedded = embedded,
        onClose = onClose,
        onBackgroundTap = { morph.blur(); keyboard?.hide() },
        composer = {
            ComposerBar(
                composer,
                morph,
                Modifier
                    .testTag("new-session-composer")
                    .handoffComposer(capture),
                textHidden = capture.textHidden,
                onTextRect = capture::onTextRect,
                chipsCanWrite = true,
            )
        },
        state = canvasState,
        // 94: the page root records the scene (its Layout holds the
        // backdrop, the nav bar and the composer; 31's grabber stays
        // outside). Chained after the caller's modifier so the recording
        // covers everything the sheet-sized node draws.
        modifier = modifier.handoffScene(capture),
        headline = headline,
        navBar = navBar,
    )
}

/**
 * The canvas's composer configuration — the `viewDidLoad` block
 * (`NewSessionViewController.swift:145-165`) as one idempotent bind, so
 * the screen's `remember(composer)` and the tests share the exact rules:
 *
 * - `chipsAlwaysVisible` (always a card here), the placeholder, the seed
 *   images;
 * - `onChipTap = { _, _ in }`: chips open their own menus (109/113/114);
 * - `attachMenu` stays **null**: 87's `ComposerAttachMenuButton` inside the
 *   shared `ComposerBar` already builds the menu per open with
 *   `limit = 8 − images.size`;
 * - `mentionSearch` is **always** set — `AppModel.searchFiles` with the
 *   project read **at call time** (`self.project`, `:236`), `chatId = null`,
 *   and errors read as no matches (the `try?` of `AppModel.swift:607-610`).
 *   Cancellation still propagates: 86 uses it to drop stale queries;
 * - the `Kept` send stub, which the screen overwrites with 94's real
 *   handler (direct-bind tests keep the stub).
 */
internal fun bindNewSessionComposer(
    composer: ComposerController,
    project: () -> ProjectOption?,
    initialImages: List<StagedImage>,
    fileSearch: NewSessionFileSearch,
) {
    composer.alwaysCard = true
    composer.placeholder = "Describe the task"
    composer.images = initialImages
    composer.onChipTap = {}
    composer.mentionSearch = { q ->
        try {
            project()?.let { p -> fileSearch.search(p.device, p.id, q) } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }
    if (composer.onSend == null) composer.onSend = { _, _, _ -> SendResult.Kept }
}

/**
 * A side chat's `@` search (android-next #06, iOS `mentionSearch` with
 * `sideOf`): the parent's project on the parent's device, `[]` for a
 * projectless parent; errors read as no matches, cancellation propagates.
 */
internal fun sideChatMentionSearch(
    side: SideChatParent,
    fileSearch: NewSessionFileSearch,
): suspend (String) -> List<FileMatch> = { q ->
    val space = side.projectId
    if (space == null) {
        emptyList()
    } else {
        try {
            fileSearch.search(side.deviceId, space, q)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

/**
 * Focus on appear (`viewDidAppear`, `:215-217`): the sheet waits for the
 * present motion to settle (Compose has no appear callback tied to the
 * transition — 31 slides the page with `Modifier.offset`, so
 * `positionInWindow` stops moving), the embedded column takes the keyboard
 * on the first frame. One-shot per screen instance — a plain
 * `LaunchedEffect(Unit)`, never re-run on recomposition, harness change or
 * a stacked sheet's return.
 */
@Composable
internal fun NewSessionFocusOnAppear(
    focusOnAppear: Boolean,
    embedded: Boolean,
    state: NewSessionCanvasState,
    focus: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (!focusOnAppear) return@LaunchedEffect
        if (embedded) withFrameNanos { } else awaitPlacementSettled { state.pageCoordinates }
        focus()
    }
}

/**
 * Returns once the page's window position is non-null and unchanged for
 * two consecutive frames, or after [SettleTimeoutNanos] (a never-settling
 * host). `withFrameNanos` ticks on every vsync, so the two still frames
 * arrive after the sheet's spring rests.
 */
internal suspend fun awaitPlacementSettled(coords: () -> LayoutCoordinates?) {
    var last: Float? = null
    var stable = 0
    var started = -1L
    while (true) {
        val now = withFrameNanos { it }
        if (started < 0L) started = now
        val y = coords()?.takeIf { it.isAttached }?.positionInWindow()?.y
        if (y != null && y == last && ++stable >= 2) return
        if (y != last) stable = 0
        last = y
        if (now - started > SettleTimeoutNanos) return
    }
}

/** The settle cap: 1000 ms of frames, against a never-settling host. */
private const val SettleTimeoutNanos = 1_000_000_000L
