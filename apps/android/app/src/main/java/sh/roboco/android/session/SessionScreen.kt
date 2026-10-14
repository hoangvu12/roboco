package sh.roboco.android.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import sh.roboco.android.RobocoApplication
import sh.roboco.android.composer.QuestionActions
import sh.roboco.android.composer.QuestionPort
import sh.roboco.android.composer.SessionQuestionsSlot
import sh.roboco.android.composer.rememberQuestionPanelState
import sh.roboco.android.composer.SessionChipMenus
import sh.roboco.android.composer.rememberChipMenuPlatform
import sh.roboco.android.image.ImageViewerRequest
import sh.roboco.android.image.LocalImageViewer
import sh.roboco.android.composer.QueueAction
import sh.roboco.android.composer.QueueActions
import sh.roboco.android.composer.DraftKey
import sh.roboco.android.composer.LocalDrafts
import sh.roboco.android.composer.QueueEditPort
import sh.roboco.android.composer.QueuePanel
import sh.roboco.android.pairing.LocalPairPage
import sh.roboco.android.composer.SessionDraftEffect
import sh.roboco.android.composer.sessionDraftSaver
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavEntryEffect
import sh.roboco.android.shell.NavKind
import sh.roboco.android.transcript.AttachmentReader
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptHost
import sh.roboco.android.transcript.TranscriptImageBinding
import sh.roboco.android.transcript.TranscriptImageLoader
import sh.roboco.android.transcript.TranscriptState
import sh.roboco.android.transcript.firstUserBubbleInRoot
import sh.roboco.android.transcript.rememberLinkRouter
import sh.roboco.android.transcript.transcriptBottomInset
import sh.roboco.android.transcript.widgets.imageTileRadius

/**
 * The Session screen (android-ui-parity #75) — the composition that stacks
 * the parts other tickets build, the port of
 * `ios:Session/SessionViewController.swift:20-30, 60-79, 119-170`:
 *
 * - the per-screen [SessionScreenState] (built by 76's route factory and
 *   held by the route, so it exists before the push for 77's
 *   `prepareArrival()` and survives a tab-host disposal — 85 relies on
 *   both). **Never created here**: a `remember {}` would recreate it on
 *   every tab switch;
 * - the first-composition wiring 51/52/66/85 delegate here: the hold
 *   decision, 77's arrival `bind` (before the engine attaches, so the
 *   deferred own turn precedes the first frame with rows), the one-time
 *   `attach` + `markSeen` (after the frame listener is registered, so
 *   the first frame is never missed), the landing hook, the
 *   transcript's top inset, the header's `edgeEngaged` signal and the
 *   [TranscriptActions];
 * - the layer stack: [SessionScaffold] (background, haze state, the header
 *   over everything) wrapping ticket 74's `SessionBottomStack`, whose
 *   transcript slot carries [OpenRevealLayers] (the transcript host, the
 *   hold blocker, the loader).
 *
 * The `OPEN` probe is wired through the frame listener: the first applied
 * frame with rows produces the JSON ([SessionScreenState.onFrameApplied])
 * and this screen emits it ([OpenProbe.emit]).
 *
 * **Detaching is 85's, not this ticket's** (`onDispose { }` is empty on
 * purpose): a cancelled predictive back and a tab-host disposal also run
 * it, and neither may detach the view (upstream #609's trap). 85 detaches
 * and closes on pop commit only.
 *
 * @param controller 51's screen state holder (chrome, attach, markSeen)
 * @param state the route-held per-screen state; its [SessionScreenState.transcript]
 *   is created on the first main-thread read (60's prewarm built the
 *   engine already)
 * @param commands ticket 78's command set, built once per route by 76
 *   (`CoreSessionCommands` over the route's core). `null` — a fake core
 *   in tests, or no session at all — leaves the composer slot empty
 * @param questionPort ticket 91's FFI seam for the question panel —
 *   76's route builds one `CoreQuestionPort` per open from its core; the
 *   screen wraps it in `QuestionActions` on the app's lifetime scope.
 *   `null` — a fake core in tests — leaves the questions slot empty
 * @param onMore the ⋯ tap (ticket 80 fills it); the [Rect] is the circle's
 *   bounds in window px
 * @param attachments ticket 66's attachment reader; 76's route passes
 *   `AttachmentReader { d, p -> core.client.readAttachment(d, p) }`. When
 *   non-null the transcript actions load and cache images through a
 *   [TranscriptImageBinding] pinned to the controller's host device;
 *   otherwise the defaults stay no-ops
 * @param queueEditPort ticket 83's queue-edit port over this route's
 *   handle (built once per route by 76's `SessionScreenRoute`; the
 *   controller itself is entry-retained next to 78's binding). `null` —
 *   a fake core in tests, or no session at all — leaves the edit lease
 *   uncreated
 * @param queueActions ticket 82's per-route queue actions
 *   (send-now / move / remove), launched on the application scope;
 *   `Edit` never reaches it (83's `beginEdit` owns it)
 * @param engineId ticket 84: the session's owning engine id
 *   (`SessionCore.engineId`), the engine half of the per-chat draft key
 *   (`DraftKey.Chat(engineId, chatId)`). The route passes `core.engineId`;
 *   `null` — a fake core in tests — leaves the drafts unwired: no saver,
 *   no seed text, exactly the pre-84 behaviour
 */
@Composable
fun SessionScreen(
    controller: SessionController,
    state: SessionScreenState,
    modifier: Modifier = Modifier,
    commands: SessionCommands? = null,
    questionPort: QuestionPort? = null,
    onMore: (anchorInWindow: Rect) -> Unit = {},
    attachments: AttachmentReader? = null,
    queueEditPort: QueueEditPort? = null,
    queueActions: QueueActions? = null,
    engineId: String? = null,
) {
    // 1. The per-screen state: the same instance the route holds. The
    //    first main-thread read creates the transcript (76's factory
    //    passed `{ TranscriptState(TextEngine.shared) }`; the app-start
    //    prewarm built the engine already).
    val entry = LocalNavEntry.current
    val transcript = state.transcript

    // 2. Chrome, collected once and passed field by field (51).
    val chrome by controller.chrome.collectAsState()

    // 3. The hold decision — at FIRST composition, before SessionScaffold
    //    below (a `LaunchedEffect`/onWillAppear would be a frame too late:
    //    one frame could show the transcript). `begin` is once-only, so a
    //    host that re-enters composition later changes nothing.
    val stack = LocalNavStack.current
    remember(state) {
        val t = stack.transition
        state.reveal.begin(
            OpenReveal.shouldHold(
                animatedPushToThis = t != null && t.kind == NavKind.Push && t.to === entry && t.animated,
                hasAppeared = entry.hasAppeared,
                arrivalPrepared = state.arrivalPrepared,
            ),
        )
        // 77: bind the arrival hooks before the engine attaches below —
        // the own turn must start before any frame with rows is applied
        // (the deferred own turn of a pre-push prepareArrival runs here).
        state.arrival.bind(
            beginOwnTurn = transcript::beginOwnTurn,
            bubbleBounds = { transcript.firstUserBubbleInRoot() },
        )
    }

    // 4. The one-time start: the frame listener (before `attach`, so the
    //    first frame is never missed), the catch-up for a frame that was
    //    applied before we listened, then `attach` + `markSeen`
    //    (iOS :167-170). Once per screen state; the listener is never
    //    removed here — 85's `close()` clears it.
    val context = LocalContext.current
    DisposableEffect(state) {
        if (!state.started) {
            state.started = true
            // The listener outlives this composition (the route holds the
            // transcript): capture the application context, never the Activity.
            val appContext = context.applicationContext
            transcript.addFrameListener { _, new ->
                state.onFrameApplied(new.rowCount().toLong(), new.buildMicros().toLong())
                    ?.let { OpenProbe.emit(appContext, it) }
            }
            transcript.frame?.let { applied ->
                state.onFrameApplied(applied.rowCount().toLong(), applied.buildMicros().toLong())
                    ?.let { OpenProbe.emit(appContext, it) }
            }
            controller.attach(transcript.engine)
            controller.markSeen()
        }
        onDispose { } // never detach here (ticket 85 does, on pop commit)
    }

    // 5. Landing: the push transition completed. The give-up timer lives
    //    in SessionScaffold, so the gallery and the tests get it too.
    NavEntryEffect(onDidAppear = { _ -> state.reveal.onLanded() })

    // 6. The transcript's top inset: 52's `contentTopInset` (status bar +
    //    44), written every composition pass; the viewport only reacts
    //    when it moves (68 R12).
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    SideEffect { transcript.topInset = SessionHeaderDefaults.contentTopInset(statusBarTop).value }

    // 7. The header's edge signal (52 step 6): content sits under the bar
    //    once the viewport scrolled (68's `scrollY = offset + topInset`).
    val edgeEngaged by remember(transcript) { derivedStateOf { transcript.viewport.scrollY > 0.5f } }

    // 8. The transcript's actions (65): the defaults are no-ops; 66's
    //    binding fills the image pair when the route passed a reader, and
    //    125 fills `openImage` through the root overlay's viewer state —
    //    the tile's downsampled bitmap is the start frame, `loadFull` the
    //    binding's full-size read, and the source rect the unclipped
    //    window rect the tile reports with its tap. 126 fills `openLink`
    //    through its own router: http(s) links open a Custom Tab, image
    //    chips load the full image and open the viewer from the row's
    //    bounds, other schemes go to the system, and the iOS no-op
    //    links (roboco-file:, file:, drive paths, scheme-less paths —
    //    decision TRANSCRIPT-D4) stay no-ops. 81 and 127 replace their
    //    own defaults here later.
    val viewer = LocalImageViewer.current
    val density = LocalDensity.current
    val binding = remember(controller, attachments) {
        attachments?.let {
            TranscriptImageBinding(TranscriptImageLoader.get(context), { controller.hostDeviceId }, it)
        }
    }
    // 126: the link router. Its viewer request is its own, not the tile's
    // `openImage` below: the source is the row (iOS presents the whole
    // RowView), so no corner radius, and the bitmap it passes is already
    // full size, so no second `loadFull` read (66's has no cache).
    val router = rememberLinkRouter(
        loadFull = { reference -> binding?.loadFull(reference) },
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
    val actions = remember(controller, attachments, viewer, router) {
        val openImage: (String, Rect, ImageBitmap?) -> Unit = { reference, source, bitmap ->
            viewer?.show(
                ImageViewerRequest(
                    reference = reference,
                    preview = bitmap,
                    sourceBounds = source,
                    sourceCornerRadius = with(density) {
                        imageTileRadius(source.width.toDp().value, source.height.toDp().value)
                    },
                    label = null, // 137 fills this with the alt text
                    loadFull = if (binding != null) {
                        { binding.loadFull(reference) }
                    } else {
                        null
                    },
                ),
            )
        }
        if (binding != null) {
            TranscriptActions(
                openLink = router::open,
                openImage = openImage,
                loadImage = binding::load,
                cachedImage = binding::cached,
            )
        } else {
            TranscriptActions(openLink = router::open)
        }
    }

    // 9. The bottom stack's state and visibility model (74). 83 feeds
    //    its editing flag from the queue-edit lease (`editingId != null`,
    //    snapshot state): an edit toggle is non-structural — the pill
    //    appears and disappears instantly, like iOS :432-433.
    val bottomState = rememberBottomStackState()

    // 9.5 Ticket 78 + 84: the composer binding — chrome → composer, send
    //     / stop / search over the route's commands, the upload progress
    //     pin and the send-failure reporting. The controller is
    //     entry-retained, so a tab-host disposal never drops the draft;
    //     without commands (tests, previews) the slot stays empty.
    //     84 adds: the draft key, the entry-retained saver (85 reads the
    //     same instance through the same retain key) and the seed text —
    //     read once; the retained controller ignores `initialText` after
    //     its first creation, and a not-yet-loaded store seeds "" with
    //     `pendingLoad` for the effect's late load.
    val drafts = LocalDrafts.current
    val draftKey = engineId?.let { DraftKey.Chat(it, controller.chatId) }
    val draftSaver = if (draftKey != null) {
        remember(entry, draftKey) { entry.sessionDraftSaver(draftKey, drafts) }
    } else {
        null
    }
    val initialDraftText = if (draftKey != null) {
        remember(draftKey) { drafts.peek(draftKey) }
    } else {
        ""
    }
    val composerBinding = if (commands != null) {
        rememberSessionComposer(
            chatId = controller.chatId,
            chrome = chrome,
            commands = commands,
            transcript = transcript,
            banner = controller.filler<StatusBanner>() ?: NoopSendReporter,
            initialText = initialDraftText,
        )
    } else {
        null
    }

    // 9.6 Ticket 83: the queue-edit lease controller — construction,
    //     `binding.queueEdit`, the pill's `onEditingTap` and the
    //     entry-retention (the ticket's step 5), built where 78 built the
    //     binding, with the same lifetime. Null without a port (a test
    //     fake core) or a 73 banner filler: no edit lease, the queue
    //     panel's Edit is a no-op.
    val queueEdit = if (composerBinding != null) {
        rememberSessionQueueEdit(
            chatId = controller.chatId,
            chrome = chrome,
            binding = composerBinding,
            banner = controller.filler<StatusBanner>(),
            port = queueEditPort,
        )
    } else {
        null
    }
    val editing = queueEdit?.isEditing == true
    val model = BottomStackModel.from(chrome, editing = editing)

    // 9.65 Ticket 73: the pill's banner filler (registered by 83 in 76's
    //     BANNER slot). Its EngineParked tap re-pairs through 72's
    //     launcher, captured here in composition.
    val statusBanner = controller.filler<StatusBanner>()
    val pairPage = LocalPairPage.current
    SideEffect {
        statusBanner?.openPairPage = pairPage?.let { launcher ->
            { id: String -> launcher.openPairPage(prefill = null, replaceEngineId = id) }
        }
    }

    // 9.65 Ticket 84: bind the entry-retained saver to the live composer
    //     and 83's controller — the late load while the store was not warm
    //     at seeding, the dispose-time safety-net save (85's dispose-time
    //     `leave()` runs after it: inner effects dispose first) and the
    //     ON_STOP background save. The leave save itself (cancel the edit,
    //     then save) is 85's — this ticket hooks no navigation.
    if (composerBinding != null && draftSaver != null) {
        SessionDraftEffect(draftSaver, composerBinding.composer, queueEdit)
    }

    // 9.7 Ticket 83's screen flags: the lease tracks on-screen-ness for
    //     the "acquired after leaving" case only (a begin that resolves
    //     after the user left releases the row instead of stealing the
    //     composer). The cancel-on-leave itself is 85's (its `leave()`
    //     calls `cancelEdit` through its adapter before 84's save), never
    //     this.
    NavEntryEffect(
        onWillAppear = { _ -> queueEdit?.onScreenShown() },
        onWillDisappear = { _, _ -> queueEdit?.onScreenLeft() },
    )

    // 9.6 Ticket 91: the question panel state and its engine RPC lane.
    //     The actions wrap the route's port on the application-lifetime
    //     scope (71's `appScope`, the same lifetime 82's queue commands
    //     run on — never the composition's), so an answer submitted just
    //     before a committed Back still reaches the engine. Without a
    //     port (tests, previews, a fake core) the questions slot stays
    //     empty, exactly like the composer without commands.
    val app = LocalContext.current.applicationContext
    val questionActions = if (questionPort != null && app is RobocoApplication) {
        remember(questionPort) { QuestionActions(questionPort, app.appScope) }
    } else {
        null
    }
    val questionPanel = if (questionActions != null) {
        rememberQuestionPanelState(controller, questionActions)
    } else {
        null
    }

    // 9.6 Ticket 89: the chip-menu providers and the write-failure
    //     reporting. This SideEffect sits **after** 78's own
    //     `binding.apply(chrome)` (SideEffects run in composition order),
    //     so its closing re-apply is what puts the providers on the
    //     composer's first frame; the map's lambdas themselves run only
    //     at tap time (ticket 59), never during composition. The
    //     provider reads only the controller-refresh snapshot — no FFI
    //     on main; the loads and writes hop to IO inside
    //     [SessionChipMenus].
    composerBinding?.let { binding ->
        val chipMenus = remember(controller) { controller.filler<SessionChipMenus>() }
        val banner = remember(controller) { controller.filler<StatusBanner>() }
        val platform = rememberChipMenuPlatform()
        SideEffect {
            chipMenus?.let { menus ->
                menus.onSettingResult = { t ->
                    if (t == null) banner?.noteSettingSucceeded() else banner?.noteSettingFailed(t)
                }
                // iOS render :488-491: the provider map is rebuilt on
                // every chrome change (the chips it decorates are).
                binding.chipMenus = { chips ->
                    chips.associate { chip -> chip.id to { menus.menu(chip.id, platform) } }
                }
            }
            binding.apply(chrome)
        }
    }

    // 10. The layer stack: the scaffold (background, haze, header) around
    //     74's container, with the open-reveal layers in its transcript
    //     slot. 133's `Modifier.transcriptBottomInset` is the viewport's
    //     bottom inset's only writer: it delivers `covered` in the same
    //     measure pass that moved the stack (never one frame late, the
    //     pr621 trap), so 68 pins the tail under the composer on every
    //     IME animation frame and the runway re-sizes in the same pass
    //     (69). `covered` (not `contentInset`) is the effective viewport
    //     bottom inset — iOS's `adjustedContentInset.bottom`.
    SessionScaffold(
        reveal = state.reveal,
        header = {
            SessionHeader(
                title = chrome.title,
                subtitle = chrome.subtitle,
                edgeEngaged = edgeEngaged,
                onMore = onMore,
                statusBarTop = statusBarTop,
            )
        },
        modifier = modifier,
    ) {
        SessionBottomStack(
            state = bottomState,
            model = model,
            transcript = { insets ->
                OpenRevealLayers(
                    state.reveal,
                    statusBarTop = statusBarTop,
                    // 77: the arrival channel joins the open reveal's own
                    // alpha inside the single graphicsLayer the layers
                    // compose (never a second layer on the host).
                    transcriptAlpha = { state.arrival.transcriptAlpha },
                ) {
                    // 77: UIKit's alpha-0.01 hit-test rule — while the
                    // arrival hides it, the transcript takes no taps and
                    // is absent from semantics, but keeps laying out.
                    Box(Modifier.arrivalHidden { state.arrival.transcriptAlpha }) {
                        TranscriptHost(
                            transcript, actions,
                            // 133, outermost of the host's chain, so the inset
                            // node measures before the host measures and places
                            // its rows; the host itself adds the IME-drag
                            // observer (interactiveImeDismiss) and 68's scroll.
                            Modifier.fillMaxSize().transcriptBottomInset(transcript.viewport, insets.covered),
                        )
                    }
                }
            },
            pill = {
                // 73: the shown banner (83's editing overlay folded in); 74
                // shows and hides the row from the same model.
                StatusPillRow(
                    shownBanner(chrome.banner, editing),
                    onTap = { banner -> statusBanner?.onTap(banner) },
                    tapEnabled = { banner -> bannerTapAction(banner, true) != BannerTap.None },
                )
            },
            queue = {
                // 82 + 83: the queue card above the composer. Its ⋯ menu's
                // Edit opens the queued-message edit lease (83,
                // entry-retained with the composer); everything else goes
                // to the route's QueueActions on the application scope —
                // fire-and-forget, so a Remove tapped just before a
                // committed Back still reaches the engine.
                QueuePanel(
                    items = chrome.queue,
                    glassVisible = glassVisible,
                    animateGlass = animateGlass,
                    writable = chrome.canWrite,
                    onAction = { id, action ->
                        if (action == QueueAction.Edit) queueEdit?.beginEdit(id)
                        else queueActions?.perform(id, action)
                    },
                )
            },
            questions = {
                // 91: the question panel in 74's questions slot — the
                // configure/picks/auto-advance/paging-fade/submit glue
                // around 90's card, fed from the controller's chrome.
                // 74 hides the composer and the queue while
                // chrome.questions != null; the slot stays composed, so
                // the panel keeps its items while collapsing.
                questionPanel?.let { SessionQuestionsSlot(it) }
            },
            composer = {
                // 78: the composer in 74's slot. `shown` is the scope's —
                // the questions panel replaces the composer
                // (`composer.isHidden = asking`, :495) and the hidden
                // composer blurs inside SessionComposerSlot.
                if (composerBinding != null) {
                    // 77: the arrival handoff measures the composer here.
                    SessionComposerSlot(composerBinding, shown, Modifier.arrivalComposerAnchor(state.arrival))
                }
            },
            jump = {}, // 131
            // 77: the arrival channel (85 multiplies its push-pop factor
            // in: nav.stackAlpha() * state.arrival.stackAlpha).
            stackAlpha = { state.arrival.stackAlpha },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
