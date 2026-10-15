package sh.roboco.android.session

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.RobocoApplication
import sh.roboco.android.composer.ComposerBar
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.ComposerEditHost
import sh.roboco.android.composer.ComposerMorphState
import sh.roboco.android.composer.ChipMenuProvider
import sh.roboco.android.composer.DeliveryMode
import sh.roboco.android.composer.QueueEditController
import sh.roboco.android.composer.QueueEditPort
import sh.roboco.android.composer.SendResult
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.shell.LocalNavEntryOrNull
import sh.roboco.android.shell.NavEntry
import sh.roboco.android.transcript.RunwayHooks
import uniffi.roboco_core.FileMatch

/**
 * Ticket 83's `QueueEditController` as the composer sees it
 * (android-ui-parity #78): 83 lands after this ticket and implements this
 * interface with members of exactly these names. Snapshot-state backed.
 */
interface QueuedEditGate {
    /** "Edit queued message" while an edit is open, else null. */
    val placeholderOverride: String?

    /**
     * iOS `if editingQueueId != null { endEdit(commit: text); return
     * .replaced }` (`SessionViewController.swift:86-89`). Called on
     * **every** send; a non-null result is returned as [SendResult.Replaced]
     * with no FFI send, no runway and no IME change. `null` (no edit open)
     * is a no-op and the normal send runs.
     */
    fun interceptSend(body: String): SendResult?
}

/**
 * Ticket 73's [StatusBanner], narrowed so tests and the gallery can fake
 * it: the send-failure reporting side of the pill. `StatusBanner`
 * implements it (its two methods already exist).
 */
interface SendReporter {
    /** A send returned normally — clears the sticky send failure. */
    fun noteSendSucceeded()

    /** `handle.send` threw — holds `Couldn't send: <errorText>` until the next success. */
    fun noteSendFailed(error: Throwable)
}

/** The screen's reporter while 73's filler is not registered: a silent no-op. */
internal object NoopSendReporter : SendReporter {
    override fun noteSendSucceeded() {}
    override fun noteSendFailed(error: Throwable) {}
}

/**
 * The glue between one open session and its composer
 * (android-ui-parity #78) — the port of
 * `ios:Session/SessionViewController.swift:84-103, 479-491`:
 *
 * - [apply] pushes every [SessionChrome] into the [ComposerController]
 *   (running, canSteer, placeholder, chips, chip menus, canWrite) and the
 *   upload progress into the transcript's ring — on every chrome change
 *   and once at creation (iOS `render`, and the placeholder override
 *   while a queued edit is open, which iOS resets by mistake);
 * - [onSend] routes a `DeliveryMode` to exactly one FFI send, deciding the
 *   runway **before** the call (the optimistic echo can reach the frame
 *   relay while the send is still suspended), reporting failures to
 *   ticket 73's pill and keeping the draft on error;
 * - [onStop] fire-and-forgets `interrupt`;
 * - [mentionSearch] wraps the engine's file search, rethrowing
 *   cancellation (ticket 86 cancels stale queries).
 *
 * Retained per nav entry (ticket 24's `NavEntry.retain`, via
 * [rememberSessionComposer]): a tab-host disposal never drops the draft,
 * the images or an open edit's stash.
 */
@Stable
class SessionComposerBinding internal constructor(
    /** 54's controller, shared with 82/83/84/86/87/88; retained per nav entry. */
    val composer: ComposerController,
    morph: ComposerMorphState,
    private val commands: SessionCommands,
    /** The transcript seam (61/67/69): the upload pin and the two runway calls. */
    private val transcript: RunwayHooks,
    /** 73's pill for this route (or a fake in tests / the gallery). */
    private val banner: SendReporter,
    /** The nav entry's scope (main, `Main.immediate`); `onStop` launches here. */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Production logs `Log.w("RobocoSession", …)`; plain tests swap this. */
    private val logW: (String, Throwable) -> Unit = { message, error ->
        Log.w("RobocoSession", message, error)
    },
) {
    /**
     * 56's morph state, refreshed by [rememberSessionComposer] on every
     * composition: the state itself is composition-scoped while the
     * binding is entry-retained, and 81 reads `binding.morph` from the
     * screen body.
     */
    var morph: ComposerMorphState by mutableStateOf(morph)

    /** Set by 83 when its queue-edit controller exists. */
    var queueEdit: QueuedEditGate? by mutableStateOf(null)

    /** Set by 89 when its chip-menu provider map lands; `emptyMap()` until then. */
    var chipMenus: (List<ComposerChip>) -> Map<String, ChipMenuProvider> = { emptyMap() }

    /** Replaced by [rememberSessionComposer]'s SideEffect (blur + IME hide). */
    internal var dismissKeyboard: () -> Unit = {}

    /**
     * `composer.onSend` (54's contract, `SessionViewController.swift:84-101`).
     * Runs on main up to the first suspension (54 launches it
     * UNDISPATCHED); the FFI runs under `io + NonCancellable`, so leaving
     * the screen mid-send never drops the Rust future.
     *
     * Steps (the ticket's spec table):
     * 1. `queueEdit.interceptSend(text)` — a queued edit's commit; non-null
     *    → returned as-is (no send, no runway, no IME change);
     * 2. `queued = composer.running && mode == Queue` — read **before**
     *    anything else (iOS `shown.running`);
     * 3. the runway, **before** the FFI call: on Roboco the core creates
     *    the optimistic echo before the RPC completes, so calling
     *    `beginOwnTurn` after the await would snapshot the echo as an old
     *    user row and the prompt would never glide;
     * 4. one `commands.send` under `io + NonCancellable`, every `Throwable`
     *    caught (nothing cancels under `NonCancellable`);
     * 5. ok → `noteSendSucceeded()` + `dismissKeyboard()` + [SendResult.Sent]
     *    (the continuation after `withContext` already resumes in a later
     *    main dispatch, exactly iOS's next-main-loop-turn keyboard
     *    dismissal); error → `noteSendFailed(t)` + [SendResult.Kept] —
     *    text, images and focus stay.
     */
    internal suspend fun onSend(text: String, images: List<StagedImage>, mode: DeliveryMode): SendResult {
        queueEdit?.interceptSend(text)?.let { return it }
        val queued = composer.running && mode == DeliveryMode.Queue
        if (queued) transcript.expectQueuedTurn() else transcript.beginOwnTurn()
        val error: Throwable? = try {
            withContext(io + NonCancellable) { commands.send(text, images, mode) }
            null
        } catch (t: Throwable) {
            t
        }
        return if (error == null) {
            banner.noteSendSucceeded()
            dismissKeyboard()
            SendResult.Sent
        } else {
            banner.noteSendFailed(error)
            SendResult.Kept
        }
    }

    /**
     * `composer.onStop` (`SessionViewController.swift:102`): fire-and-forget
     * on the entry scope, `interrupt` under `io + NonCancellable`, errors
     * logged and swallowed (iOS `try?`). No UI change — the running flag
     * clears when the core reports it; the Stop haptic is 54's
     * `primaryAction()`, never here.
     */
    internal fun onStop() {
        scope.launch {
            withContext(io + NonCancellable) {
                try {
                    commands.interrupt()
                } catch (t: Throwable) {
                    logW("interrupt failed", t)
                }
            }
        }
    }

    /**
     * `composer.mentionSearch` (`CoreSessionSource.swift:247-249`): the
     * engine's workspace search on `io`. A `CancellationException` is
     * **rethrown** (86 cancels stale queries); any other `Throwable`
     * yields `[]` (iOS `try?`).
     */
    internal suspend fun mentionSearch(query: String): List<FileMatch> = withContext(io) {
        try {
            commands.searchFiles(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * The render fan-out (`SessionViewController.swift:479-491`): every
     * chrome change pushes the composer-facing fields into the controller
     * and the upload progress into the transcript. Snapshot writes of
     * equal values are no-ops, so re-applying the whole mapping is cheap.
     * `onChipTap` is **not** set (iOS never sets it in a session —
     * branch taps stay inert; android-next #01's context chip opens its
     * card through the menu map, not `onChipTap`).
     */
    internal fun apply(chrome: SessionChrome) {
        applyChrome(
            composer,
            chrome,
            placeholderOverride = queueEdit?.placeholderOverride,
            menus = chipMenus(chrome.chips),
        )
        transcript.uploadProgress = chrome.uploadProgress
    }
}

/**
 * The chrome → controller mapping (`render:484-491`), with the Roboco
 * placeholder adaptation: the queued-edit override (83) wins over the
 * chrome's placeholder — iOS resets "Edit queued message" back to
 * "Message <harness>" on the next chrome change, an upstream wart this
 * port does not copy.
 */
internal fun applyChrome(
    c: ComposerController,
    chrome: SessionChrome,
    placeholderOverride: String?,
    menus: Map<String, ChipMenuProvider>,
) {
    c.running = chrome.running
    c.canSteer = chrome.canSteer
    c.placeholder = placeholderOverride ?: chrome.placeholder
    c.chips = chrome.chips
    c.chipMenus = menus
    c.canWrite = chrome.canWrite
}

/**
 * The session's composer binding in composition (78's step 5): the
 * controller and the binding are **entry-retained** (ticket 24's
 * `NavEntry.retain` — a tab-host disposal, ticket 26, never drops the
 * draft), the chrome is applied on the first composition (iOS
 * `render(animated: false)` at open, `:169`) and on every change, and the
 * controller's three hooks are pointed at the binding.
 *
 * Without a `NavStackHost` (previews, Robolectric tests, the gallery) the
 * controller and the binding fall back to `remember { … }` with the
 * composition's own scope — [LocalNavEntryOrNull] instead of a try/catch
 * around the throwing [sh.roboco.android.shell.LocalNavEntry].
 *
 * @param chatId the session's scoped chat id (the retention key)
 * @param chrome the screen's collected `controller.chrome` value (75)
 * @param commands the route's command set (76 builds it once per route)
 * @param transcript the screen's [RunwayHooks] (61/67/69)
 * @param banner 73's `StatusBanner` for this route (`controller.filler`)
 * @param initialText 84 passes `drafts.peek(DraftKey.Chat(engineId, chatId))`
 */
@Composable
fun rememberSessionComposer(
    chatId: String,
    chrome: SessionChrome,
    commands: SessionCommands,
    transcript: RunwayHooks,
    banner: SendReporter,
    initialText: String = "",
): SessionComposerBinding {
    val entry = LocalNavEntryOrNull.current
    val feedback = LocalFeedback.current

    // The controller: entry-retained, seeded with the current chrome
    // before the first frame so the composer never flashes the default
    // "Message" placeholder.
    val composer = if (entry != null) {
        remember(entry) {
            entry.retain("session-composer:$chatId") {
                ComposerController(entry.scope, feedback, initialText)
                    .also { controller -> applyChrome(controller, chrome, null, emptyMap()) }
            }
        }
    } else {
        // Previews / tests without a nav entry: the controller lives with
        // the composition.
        val scope = rememberCoroutineScope()
        remember(scope) { ComposerController(scope, feedback, initialText) }
    }

    // The morph: composition-scoped (56), refreshed onto the retained
    // binding by the SideEffect below — a host that re-enters composition
    // brings a fresh morph state.
    val morph = rememberComposerMorphState(alwaysCard = false, hasImages = { composer.images.isNotEmpty() })
    val keyboard = LocalSoftwareKeyboardController.current

    val binding = if (entry != null) {
        remember(entry) {
            entry.retain(sessionComposerBindingKey(chatId)) {
                SessionComposerBinding(composer, morph, commands, transcript, banner, entry.scope)
            }
        }
    } else {
        val scope = rememberCoroutineScope()
        remember(scope, commands, transcript, banner) {
            SessionComposerBinding(composer, morph, commands, transcript, banner, scope)
        }
    }

    // Every recomposition: the fresh morph, the keyboard dismiss and the
    // current chrome. The `queueEdit` override and the chip menus are read
    // through [SessionComposerBinding.apply] at call time.
    SideEffect {
        binding.morph = morph
        binding.dismissKeyboard = {
            morph.blur()
            keyboard?.hide()
        }
        binding.apply(chrome)
    }

    // The three hooks (SessionViewController.swift:84-103). Nothing to
    // undo on dispose: the controller dies with its nav entry and ticket
    // 85 owns the lifecycle — never detach here (upstream #609's trap).
    DisposableEffect(binding) {
        composer.onSend = binding::onSend
        composer.onStop = binding::onStop
        composer.mentionSearch = binding::mentionSearch
        onDispose { }
    }

    return binding
}

/**
 * The bottom stack's composer slot (78's step 6, into ticket 74's
 * `SessionBottomStack` composer slot): [ComposerBar] at the slot's full
 * width (74 applies the 12 dp side margins, the 768 dp cap and the IME
 * placement), tagged `session-composer`.
 *
 * **Hide → blur (74 delegates this here):** when `shown` goes
 * `true → false` (a question opened, `composer.isHidden = asking`,
 * `SessionViewController.swift:495`) the field drops focus so the IME
 * cannot keep typing into an invisible field — iOS leaves a hidden
 * `UITextView` first responder. Text, images and an open edit survive
 * the hide: only the slot hides, never the retained controller.
 */
@Composable
fun SessionComposerSlot(binding: SessionComposerBinding, shown: Boolean, modifier: Modifier = Modifier) {
    var wasShown by remember { mutableStateOf(shown) }
    LaunchedEffect(shown) {
        if (wasShown && !shown) binding.morph.blur()
        wasShown = shown
    }
    ComposerBar(
        controller = binding.composer,
        morph = binding.morph,
        modifier = modifier.fillMaxWidth().testTag("session-composer"),
    )
}

// ---------------------------------------------------------------------------
// Ticket 83: the queue-edit lease controller, wired next to 78's binding
// ---------------------------------------------------------------------------

/** 83's host-adapter retention key (per chat id, on the session's nav entry). */
private val QueueEditHostKey = "session-queue-edit-host"

/** 83's controller retention key (per chat id, on the session's nav entry). */
private val QueueEditControllerKey = "session-queue-edit"

/** 83's entry-close key: disposes the controller with the session's other retained state. */
private val QueueEditDisposeKey = "session-queue-edit-dispose"

/**
 * 78's composer-binding retention key, per chat id, on the session's nav
 * entry — one named key for the two readers (78's creation site and 85's
 * [sessionQueueEditGate] lookup), so the string never forks.
 */
private fun sessionComposerBindingKey(chatId: String): String = "session-composer-binding:$chatId"

/**
 * Ticket 85's read-only seam into 78's entry-retained binding: the live
 * [QueuedEditGate] of [chatId] (83's `QueueEditController` once its wiring
 * ran), or null when the binding or the edit lease was never created for
 * this entry (no commands — a test fake core — or before the screen's
 * first composition). The nav-leave path (`SessionNavLifecycle.leave()`)
 * cancels an open queued edit through exactly this instance, at call
 * time rather than composition time.
 */
internal fun NavEntry.sessionQueueEditGate(chatId: String): QueuedEditGate? =
    retainedOrNull<SessionComposerBinding>(sessionComposerBindingKey(chatId))?.queueEdit

/**
 * Ticket 83's [QueueEditController] in composition — the construction and
 * wiring step of the ticket (its step 5), built where 78 builds its
 * binding, with the **same lifetime**: the host and the controller are
 * retained on the Session nav entry (`entry.retain`), so a tab-host
 * disposal never drops the stash, and the whole controller dies with the
 * entry (the [QueueEditDisposeKey] below disposes it where the session's
 * other controllers are disposed).
 *
 * What this wires:
 * - `binding.queueEdit = edit` — 78's [QueuedEditGate] hook (its `onSend`
 *   step 1 commits through `interceptSend`; its `applyChrome` writes
 *   `placeholderOverride` over the chrome's placeholder);
 * - `banner.onEditingTap = edit::cancelEdit` — 73's pill tap while the
 *   `Editing` overlay shows;
 * - the host's composition-scoped half ([ComposerEditHost.refresh]) on
 *   every recomposition — morph, keyboard, alerts, the chrome's
 *   placeholder. `binding.morph` is read here after 78's own `SideEffect`
 *   refreshed it (both effects run in composition order, 78's first).
 *
 * Null (and no edit) when: there is no port (a test fake core, previews,
 * the gallery — those build their own stack), no 73 [StatusBanner] filler
 * for this route, no nav entry, or no [RobocoApplication] behind the
 * context (the release scope needs the app's outliving job).
 *
 * The screen's own reads — 74's `BottomStackModel.from(chrome, editing)`
 * and the queue panel's `Edit` routing — stay in `SessionScreen`, which
 * reads [QueueEditController.isEditing] / `beginEdit` off the returned
 * controller. Screen on/off flags and the dispose are wired there too
 * (the `NavEntryEffect` / the entry's close).
 */
@Composable
fun rememberSessionQueueEdit(
    chatId: String,
    chrome: SessionChrome,
    binding: SessionComposerBinding,
    banner: StatusBanner?,
    port: QueueEditPort?,
): QueueEditController? {
    if (port == null || banner == null) return null
    val entry = LocalNavEntryOrNull.current ?: return null
    val app = LocalContext.current.applicationContext as? RobocoApplication ?: return null
    val alerts = LocalAlerts.current
    val keyboard = LocalSoftwareKeyboardController.current

    // The release scope: the app's SupervisorJob (it outlives every
    // screen) with the main dispatcher — a finish a screen started still
    // reaches the engine after its nav entry (and the entry's scope)
    // closed.
    val releaseScope = remember(app) {
        CoroutineScope(app.appScope.coroutineContext + Dispatchers.Main.immediate)
    }

    // The host: retained with the controller, seeded from the first
    // composition, refreshed every recomposition by the SideEffect below.
    val host = remember(entry) {
        entry.retain("$QueueEditHostKey:$chatId") {
            ComposerEditHost(
                composer = binding.composer,
                morph = binding.morph,
                keyboard = { null },
                alerts = { Alerts.Noop },
                banner = banner,
                chromePlaceholder = { "Message" },
            )
        }
    }

    // The controller: the session's entry scope (main, immediate) for
    // the begin coroutine and the renewal, the release scope for the
    // finishes.
    val edit = remember(entry) {
        entry.retain("$QueueEditControllerKey:$chatId") {
            QueueEditController(
                port = port,
                host = host,
                scope = entry.scope,
                releaseScope = releaseScope,
            )
        }
    }

    // The entry's close (a committed pop, a popToRoot, a setRoutes
    // removal — the same moment the route's own CloseKey releases the
    // session): dispose the controller, cancelling any open edit before
    // the entry's scope is gone. Reverse creation order closes this
    // before the route's release.
    remember(entry) {
        entry.retain("$QueueEditDisposeKey:$chatId") {
            object : AutoCloseable {
                override fun close() {
                    edit.dispose()
                }
            }
        }
    }

    // Every recomposition: the fresh morph / keyboard / alerts / chrome
    // placeholder onto the retained host, and the two hooks. 78's own
    // SideEffect (registered earlier in the same composition) has
    // refreshed `binding.morph` before this one runs.
    SideEffect {
        host.refresh(
            morph = binding.morph,
            keyboard = { keyboard },
            alerts = { alerts },
            chromePlaceholder = { chrome.placeholder },
        )
        binding.queueEdit = edit
        banner.onEditingTap = { edit.cancelEdit() }
    }

    return edit
}
