package sh.roboco.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.roboco.android.background.RunWatchController
import sh.roboco.android.composer.CoreChipMenuCore
import sh.roboco.android.accounts.CoreAccountsCore
import sh.roboco.android.accounts.SessionAccountUsage
import sh.roboco.android.composer.Drafts
import sh.roboco.android.composer.QueueChromeFiller
import sh.roboco.android.composer.SessionChipMenus
import sh.roboco.android.core.AppLifecycleHooks
import sh.roboco.android.core.AppearanceController
import sh.roboco.android.core.CoreClientWorkspace
import sh.roboco.android.core.CoreLoader
import sh.roboco.android.core.CoreLifecycleSink
import sh.roboco.android.core.CoreStartState
import sh.roboco.android.core.CoreStartup
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.core.FontGate
import sh.roboco.android.core.Prefs
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UiModeNightModeSink
import sh.roboco.android.core.WorkspaceCore
import sh.roboco.android.core.WorkspaceFeed
import sh.roboco.android.core.robocoPrefsStore
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.AlertHostState
import sh.roboco.android.design.ToasterState
import sh.roboco.android.update.RELEASES_PAGE
import sh.roboco.android.notify.NotificationCenter
import sh.roboco.android.notify.NotificationPermission
import sh.roboco.android.notify.NotificationPolicy
import sh.roboco.android.notify.NotificationPrefs
import sh.roboco.android.notify.Notifier
import sh.roboco.android.newsession.NewSessionDraftStore
import sh.roboco.android.pairing.KeystoreSecretCipher
import sh.roboco.android.pairing.LaunchPairRedeemer
import sh.roboco.android.pairing.PairedEngineStore
import sh.roboco.android.session.CanWriteFiller
import sh.roboco.android.session.ChromeFillerRegistry
import sh.roboco.android.session.CoreLimitedCore
import sh.roboco.android.session.CoreSessionCore
import sh.roboco.android.session.CoreSessionOpener
import sh.roboco.android.session.LimitedBanner
import sh.roboco.android.session.QuestionsChromeFiller
import sh.roboco.android.session.SessionChipsFiller
import sh.roboco.android.session.StatusBanner
import sh.roboco.android.session.installSessionRoutes
import sh.roboco.android.sessions.CoreRowActions
import sh.roboco.android.sessions.EngineHealthModel
import sh.roboco.android.sessions.FoldState
import sh.roboco.android.sessions.FrontPageModel
import sh.roboco.android.sessions.asSessionWrites
import sh.roboco.android.sessions.notices
import sh.roboco.android.shell.DeepLinkHub
import sh.roboco.android.shell.RootInput
import sh.roboco.android.shell.RouterHooks
import sh.roboco.android.shell.SearchTabState
import sh.roboco.android.update.UpdateCenter
import sh.roboco.android.composer.openInCustomTab
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The Roboco app shell: verifies the bundled fonts, loads the Rust mobile
 * core, then starts the client pipeline (ticket 71) — the stored paired
 * engines load on IO, the single `CoreClient` is built from them, and the
 * splash (ticket 35) holds on `CoreStartState.Loading` until the engine
 * list is known.
 *
 * Property names are reserved for the parity tickets (android-ui-parity):
 * `prefs`, `workspaceFeed`, `foldState`, `frontPage` (#40, all landed
 * now), `appEvents`, `appToaster`, `rowActions` (#42, landed now),
 * `deepLinks` (#36, landed), `drafts` (#84), `newSessionDrafts` (#92) —
 * do not reuse them for something else.
 */
class RobocoApplication : Application() {

    lateinit var core: CoreStatus
        private set

    /**
     * Application-wide scope for state that outlives the activity
     * (ticket 40): Main.immediate, like the iOS main queue — the front
     * page's mutators flip their StateFlows synchronously on the calling
     * (main) thread and hop to serial IO lanes for the FFI and the disk.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Ticket 71's name for the process-lifetime, main-immediate scope:
     * sibling tickets build their outliving scopes on it (83's
     * queue-edit finishes, 84's draft writes), so work a screen starts
     * still runs after its nav entry closes. Same instance as the
     * private [applicationScope] — one SupervisorJob for the process.
     */
    val appScope: CoroutineScope
        get() = applicationScope

    /**
     * The Search tab's query holder (ui-parity #29): app-scoped so the
     * query survives tab switches **and activity recreations**, but never
     * process death (it is not saveable — the query is gone on a fresh
     * process, like iOS's). `rememberSearchTabState` resolves it.
     */
    val searchTabState = SearchTabState()

    /**
     * The app root's hook holder (ticket 32, provided by 35 as
     * `LocalRouterHooks` around the root): one instance for the app's
     * lifetime. Tickets 76 (`SessionRouteFactory`) and 95
     * (`HandoffAnimator`) assign their implementations at startup; the
     * fields are volatile so the assignments are visible to the router's
     * open coroutines.
     */
    val routerHooks = RouterHooks()

    /**
     * The ordered ChromeFiller factories (ticket 76 + 73/78/79/82/89/91):
     * the filler tickets register into this in `onCreate`, before the
     * first possible open. Empty until they land — 51's base chrome
     * fills the screen alone.
     */
    val chromeFillers = ChromeFillerRegistry()

    /**
     * The process-wide deep-link hub (ticket 36): holds `roboco://` links
     * and `route` extras until a shell can take them (dispatched after the
     * shell's first frame), drops everything but `pair` while the pairing
     * root is on screen, and hands `roboco://pair…` to the handler ticket
     * 139 registers. App-lifetime on purpose: a link held while the
     * splash is up survives activity recreation.
     *
     * [knownChatIds] reads the workspace snapshot — an in-memory list of
     * scoped ids (`allSessionIds` below), never a blocking FFI call on the
     * main thread, and only ever for a link with a **raw** engine id.
     */
    val deepLinks: DeepLinkHub by lazy {
        DeepLinkHub(knownChatIds = { workspaceFeed.snapshot.value.allSessionIds() })
    }

    /**
     * The appearance preference (ticket 96): the DataStore-backed
     * [Prefs.appearance] StateFlow — `System` until the first disk read —
     * fed into the one root `RobocoTheme(appearance)`, the port of
     * `window.overrideUserInterfaceStyle` (`SceneDelegate.swift:14`).
     */
    val appearance: StateFlow<Appearance>
        get() = prefs.appearance

    /** Writes the appearance preference (ticket 96) — see [Prefs.setAppearance]. */
    fun setAppearance(a: Appearance) = prefs.setAppearance(a)

    /**
     * Ticket 96: pushes the stored appearance to the platform's per-app
     * night mode after the first disk read and on every change — the
     * splash of the next cold start, the bar-icon defaults and the
     * `values-night` resources follow it. Forced in `onCreate` (before
     * `coreStartup.start()`, order irrelevant: it waits on the DataStore
     * read, never on the client).
     */
    private val appearanceController: AppearanceController by lazy {
        AppearanceController(prefs, UiModeNightModeSink(this), applicationScope)
    }

    /**
     * Exactly one DataStore per process for the paired-engines file (a
     * second instance on the same file throws).
     */
    val engineDataStore by lazy {
        PairedEngineStore.createDataStore(this, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    /** Keystore-encrypted credentials + DataStore metadata (ticket 71). */
    val engineStore by lazy { PairedEngineStore(engineDataStore, KeystoreSecretCipher(this)) }

    /**
     * The debug `pair` launch extra's redemption (android-app#30): pairs
     * the flow rig's minted URLs through the live client before the shell
     * is drawn. No-op for an empty list (every non-rig launch).
     */
    private val launchPairRedeemer by lazy {
        LaunchPairRedeemer(
            client = ::clientOrNull,
            store = engineStore,
            scope = applicationScope,
        )
    }

    /**
     * android-app#30: true from [redeemLaunchPairs] until the redeemed
     * engine is in the engine list (or nothing was redeemed). While it is
     * up, an empty engine list reads as `Loading`, not the pairing root: a
     * fresh install starts `Ready` with no engines, and the pairing root
     * would make the deep-link hub drop the held `route` extra
     * (`DeepLinkHub.showingPairingRoot` clears it) before the redeem lands.
     */
    private val launchPairGate = MutableStateFlow(false)

    /** android-app#30: redeem the `pair` extra's URLs (debug builds only —
     * `LaunchOptions.parse` drops the extra otherwise). */
    fun redeemLaunchPairs(urls: List<String>) {
        if (urls.isEmpty()) return
        launchPairGate.value = true
        launchPairRedeemer.redeem(urls) { redeemed ->
            if (redeemed == 0) {
                launchPairGate.value = false
            } else {
                // The engine list refreshes on the core's EnginesChanged
                // event, which can trail pairEngine's return: keep the gate
                // until the list shows an engine, bounded by the
                // first-frame hold's budget.
                applicationScope.launch {
                    withTimeoutOrNull(LAUNCH_PAIR_GATE_MS) {
                        coreStartup.engines.first { !it.isNullOrEmpty() }
                    }
                    launchPairGate.value = false
                }
            }
        }
    }

    /**
     * The phone-local per-chat draft store (ticket 84) — unsent composer
     * text per session under `chat:<engineId>:<scoped chat id>`, plus the
     * iOS `"new-session"` key (ticket 92 reads and writes it). One
     * DataStore per process over `noBackupFilesDir/drafts.preferences_pb`
     * (drafts reference engine ids that are themselves not backed up, so
     * a restored backup would only hold orphans); the in-memory mirror
     * warms in `onCreate`, saves are blank-removing and verbatim (iOS
     * `Drafts`), and the disk writes run on [appScope] so they outlive
     * every screen. Ticket 140's forget sequence drops an engine's
     * drafts through `forgetEngine`.
     */
    val drafts by lazy {
        Drafts(
            Drafts.createDataStore(this, CoroutineScope(SupervisorJob() + Dispatchers.IO)),
            appScope,
        )
    }

    /**
     * The New Session page's sticky state (ticket 92): the options draft
     * as JSON under `newSessionDraft` (the iOS `UserDefaults` key) in one
     * DataStore per process over `noBackupFilesDir/new_session.preferences_pb`
     * (engine-scoped ids — the same no-backup rule as the drafts), the
     * typed text in [drafts] under the iOS `"new-session"` key, and the
     * staged images in memory only. Every draft change is persisted on
     * [appScope]; the mirror warms in `onCreate`, and the debug
     * `-harness`-style extra is applied once on the first read after the
     * load. Ticket 140's forget sequence resets or strips it through
     * `forgetEngine`.
     */
    val newSessionDrafts by lazy {
        NewSessionDraftStore(
            NewSessionDraftStore.createDataStore(this, CoroutineScope(SupervisorJob() + Dispatchers.IO)),
            drafts,
            appScope,
        )
    }

    /**
     * The startup pipeline (71's `onClientReady` hook wired by ticket 35:
     * the lifecycle hooks re-send the network state to the client it
     * creates).
     */
    val coreStartup by lazy {
        CoreStartup(
            context = this,
            store = engineStore,
            scope = applicationScope,
            onClientReady = { client ->
                lifecycleHooks.clientReady()
                // android-app#28: the notification driver arms as soon as
                // the client exists (this runs on the startup pipeline's IO
                // lane — never the main thread).
                notifications.attachTo(client)
            },
        )
    }

    /**
     * The process lifecycle / network hooks (ticket 35): foreground and
     * background `onForeground()`/`onBackground()` and the default-network
     * `setNetworkOnline(Boolean)` — installed **once** in `onCreate`,
     * before the startup pipeline runs (71 relies on this order; its
     * `onClientReady` re-sends the network state to the client it
     * creates). Never registered from an activity (#609/#621).
     */
    lateinit var lifecycleHooks: AppLifecycleHooks
        private set

    /**
     * The app root's input (ticket 35): `Loading` while 71's pipeline
     * loads, `Ready`/`CoreFailed` after — derived from `coreStartup`'s
     * state and engine list exactly as 71's Implementation states, so the
     * splash (FirstFrameHold) holds until the engine list is known. The
     * list includes parked (revoked) engines: they count as paired and
     * keep the shell up with cached data.
     */
    lateinit var rootInput: StateFlow<RootInput>
        private set

    /**
     * The phone-local preference store (ticket 40): the Sessions tab's
     * Group by Project flag, the phone-local section folds and the
     * offline fold overrides — engine-local data (ADR 0004), never sent
     * to an engine.
     */
    val prefs: Prefs by lazy { Prefs(robocoPrefsStore, applicationScope) }

    /**
     * The workspace FFI seam, resolved at call time: whatever client
     * `CoreStartup` holds right now (null before `Ready`).
     */
    private val liveCore: WorkspaceCore by lazy {
        CoreClientWorkspace(client = { coreStartup.clientOrNull() })
    }

    /**
     * The single Kotlin `StateFlow<WorkspaceSnapshot?>` owner (ticket 40,
     * the settled feed of this set — 27, 42, 49, 92 and 110 read it as
     * `app.workspaceFeed.snapshot` / `.engines`). Its event sink is
     * registered lazily, but `onCreate` forces this property above
     * `coreStartup.start()` so no event is ever missed, and the first
     * read waits for `CoreStartState.Ready` — before that there is no
     * client to read through.
     */
    val workspaceFeed: WorkspaceFeed by lazy {
        val feed = WorkspaceFeed(core = { liveCore }, scope = applicationScope)
        coreStartup.addEventSink { event -> feed.onEvent(event) }
        applicationScope.launch {
            coreStartup.state.first { it !is CoreStartState.Loading }
            feed.start()
        }
        feed
    }

    /**
     * The fold state (ticket 40): phone-local folds for Pinned/Recent/
     * groups, engine-synced folds for user sections, offline overrides
     * pushed on reconnect. One instance per process — the tablet sidebar
     * (105) shares it.
     */
    val foldState: FoldState by lazy { FoldState(robocoPrefsStore, liveCore, applicationScope) }

    /**
     * The multi-engine health model (ticket 50): the 4 s show-slow /
     * hide-fast grace over the feed's engines and connectivity, feeding
     * both the Sessions connection line and the offline `home:*` header
     * glyphs (through [frontPage]'s `liveOverrides`). One instance per
     * process; the debug gallery builds its own over MutableStateFlows.
     */
    val engineHealth: EngineHealthModel by lazy { EngineHealthModel(workspaceFeed, applicationScope) }

    /** The Sessions tab's front page model (tickets 40 and 50); 46 hosts it. */
    val frontPage: FrontPageModel by lazy {
        FrontPageModel(workspaceFeed, prefs, foldState, engineHealth, applicationScope)
    }

    /**
     * The app's core-event fan-out (ticket 42): one [MutableSharedFlow]
     * every part of the UI can collect, re-emitted from the single client
     * listener with a non-blocking `tryEmit` on the Rust callback thread
     * — 64 slots of slack, and a full buffer drops rather than blocks
     * the callback. The sink is registered in [rowActions]'s lazy init,
     * which `onCreate` forces before [coreStartup] starts, so no event
     * is ever missed.
     */
    val appEvents = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64)

    /**
     * The app-scoped toast state (ticket 42's wiring of ticket 14):
     * created once next to the client, **not** inside a composable — an
     * app-lifetime [CoreRowActions] holding an Activity-scoped host's
     * `remember { ToasterState() }` would toast into a dead host after a
     * rotation. Ticket 35 passes it to the root
     * `ToastHost(state = appToaster)`; until then only the gallery and
     * the row actions write toasts through it.
     */
    val appToaster: ToasterState by lazy { ToasterState() }

    /**
     * The app-scoped alert state (android-next#04, the [appToaster]
     * precedent): the update center — an app-lifetime service, not a
     * screen — shows its alerts through it, so a prompt raised while no
     * activity is composed (or after a rotation) still lands in the live
     * root host. `RobocoAppContent` passes it to `RootHosts` (the alert
     * host is the root's own; this is only the shared state behind it).
     */
    val appAlerts: AlertHostState by lazy { AlertHostState() }

    /**
     * The in-app update center (android-next#04): the scheduled manifest
     * checks, the launch prompt (over [appAlerts], once per process,
     * deferred until the shell is idle), the download/verify/install
     * flow, and the state the Settings → About update rows read. Started
     * in `onCreate` (its 2 s initial delay waits out the first frame);
     * the foreground observer below feeds it the process's foreground
     * edges (the hourly cadence and the install resume).
     */
    val updates: UpdateCenter by lazy {
        UpdateCenter(
            context = this,
            scope = applicationScope,
            alerts = appAlerts,
            engineVersions = { workspaceFeed.engines.value.map { it.version } },
            rootReady = {
                val input = rootInput.value
                input is RootInput.Ready && input.hasEngines
            },
            openReleases = {
                openInCustomTab(
                    this,
                    RELEASES_PAGE,
                    dark = appearance.value != Appearance.Light &&
                        (appearance.value == Appearance.Dark ||
                            resources.configuration.isNightModeActive),
                )
            },
        )
    }

    /**
     * The one object every session/section write goes through (ticket
     * 42) — the [sh.roboco.android.sessions.RowActions] instance ticket
     * 35 provides as `LocalRowActions` inside the root `ToastHost`.
     * Writes run on the serial IO lane, errors toast, a successful
     * archive shows "Archived · Undo", and the `ClientEvent.Notice`
     * collector is started once here. App lifetime: a toast's Undo must
     * outlive the screen that raised it (iOS's `[weak self]` on
     * `AppModel`).
     */
    val rowActions: CoreRowActions by lazy {
        coreStartup.addEventSink { event -> appEvents.tryEmit(event) }
        CoreRowActions(
            writes = { clientOrNull()?.asSessionWrites() },
            sections = { workspaceFeed.snapshot.value?.front?.sections.orEmpty() },
            toaster = appToaster,
            scope = applicationScope,
        )
    }

    /**
     * The app's notification centre (android-app#28): the `NotificationSink`
     * the core calls, the delivery gates, and the posting. Its scope hops
     * the core-thread callbacks onto `Dispatchers.Default`
     * (`NotificationManagerCompat.notify` is thread-safe; the main thread is
     * never blocked). Forcing the lazy init runs `migrateChannels()` +
     * `ensureChannels()` in `onCreate` — cheap and idempotent.
     */
    val notifications: NotificationCenter by lazy {
        val center = NotificationCenter(
            context = this,
            // One lane: the core delivers callbacks in order (an older
            // RunActivity must never land after a newer one, a Resolved
            // never before its event); parallel Default hops could reorder.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
            controller = runWatch,
            prefs = notificationPrefs,
            notifier = notifier,
        )
        center.setEngineCountSource { coreStartup.engines.value?.size ?: 0 }
        center
    }

    /** The phone-local notification choices (android-app#28; #99's rows). */
    val notificationPrefs: NotificationPrefs by lazy { NotificationPrefs.get(this) }

    /** The notification poster (channels, migration, copy, cancels). */
    val notifier: Notifier by lazy {
        Notifier(this).also {
            it.migrateChannels()
            it.ensureChannels()
        }
    }

    /**
     * The run-watching foreground service's controller (android-app#28):
     * starts `RunWatchService` while any run is Working and notifications
     * are on, stops it 2 min after the last run leaves Working, and keeps
     * it up while "Stay connected in background" is on (ADR 0010). Never owns
     * sockets — the core client does; the service keeps the process out
     * of the cached/frozen set.
     */
    val runWatch: RunWatchController by lazy {
        RunWatchController(this, applicationScope, notifier = notifier)
    }

    /**
     * Wave-10 43 (#626): the system is short of memory — shrink the core's
     * warm sessions ([sh.roboco.android.background.trimIsCritical]). Off
     * main: an eviction sizes transcripts.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val critical = sh.roboco.android.background.trimIsCritical(level) ?: return
        val client = clientOrNull() ?: return
        appScope.launch(Dispatchers.IO) { client.trimMemory(critical) }
    }

    override fun onCreate() {
        super.onCreate()
        // #609's Fonts.init swallowed missing faces (review bug 8); a
        // missing font must fail startup loudly, naming the files. Debug
        // builds crash here; release builds store FontStatus.Missing and
        // MainActivity shows FontsMissingScreen instead of the app.
        FontGate.check(assets, BuildConfig.DEBUG)
        // Ticket 60: the shared text engine (one Rust TextSystem over the
        // same font bytes) is built off the main thread — the prewarm
        // daemon races the first transcript open. A late load failure
        // reports itself through FontGate.fail.
        TextEngine.init(this)
        TextEngine.prewarm()
        core = CoreLoader.load()
        // Ticket 84: warm the drafts mirror now — Application.onCreate
        // precedes every activity's setContent, so `peek` is nearly
        // always loaded by the time a session opens (one opened before
        // the read finishes falls back to the effect's late load).
        drafts.warm()
        // Ticket 92: the same for the new-session options mirror — a
        // page opened before the read finishes takes the late-load path.
        newSessionDrafts.warm()
        // Ticket 35: the process lifecycle hooks and the default-network
        // monitor, installed exactly once per process — never from an
        // Activity (#609 leaked one observer per onCreate) and never from
        // onStart/onStop (#621 redialed on every rotation). Before
        // `coreStartup.start()`: the sink resolves the live client, and
        // `clientReady()` (wired into CoreStartup above) re-sends the
        // network state to the client it creates. The workspace feed
        // re-pulls through ClientEvent after onForeground(), so
        // `afterForeground` is a no-op (iOS's refreshWorkspace).
        lifecycleHooks = AppLifecycleHooks.install(
            app = this,
            lifecycle = ProcessLifecycleOwner.get().lifecycle,
            sink = { clientOrNull()?.let(::CoreLifecycleSink) },
        )
        // Ticket 35: the root input derives from 71's startup state and
        // engine list; Loading holds the splash until the first root is
        // known, so a paired user never sees the pairing root flash.
        rootInput = when (val c = core) {
            is CoreStatus.Failed -> MutableStateFlow(RootInput.CoreFailed(c.reason))
            is CoreStatus.Loaded -> combine(coreStartup.state, coreStartup.engines, launchPairGate) { s, engines, pairing ->
                when (s) {
                    CoreStartState.Loading -> RootInput.Loading
                    is CoreStartState.Failed -> RootInput.CoreFailed(s.reason)
                    CoreStartState.Ready -> when {
                        engines == null -> RootInput.Loading
                        // android-app#30: the launch `pair` extra is still
                        // being redeemed — no pairing root (see launchPairGate).
                        engines.isEmpty() && pairing -> RootInput.Loading
                        else -> RootInput.Ready(hasEngines = engines.isNotEmpty())
                    }
                }
            }.distinctUntilChanged()
                .stateIn(applicationScope, SharingStarted.Eagerly, RootInput.Loading)
        }
        // Ticket 79 (offline read-only): `SessionChrome.canWrite`
        // derived from the owning engine's state — `true` only for
        // `EngineState.Connected` (writes to anything else are refused
        // at once by the core, android-app#06). Registered in 76's
        // CAN_WRITE slot (10), the first filler, so later fillers (73's
        // banner) can read `chrome.canWrite`; the factory returns the
        // same stateless object for every open.
        chromeFillers.register(ChromeFillerRegistry.CAN_WRITE) { CanWriteFiller }
        // Ticket 78 (chips): the session composer's chip derivation,
        // registered at slot 20 — after 79's CAN_WRITE (10), before 73's
        // BANNER (30), the iOS `refresh()` order. 73 owns the send-failure
        // state, so this registry never gets a second send-failure filler.
        chromeFillers.register(ChromeFillerRegistry.CHIPS) { SessionChipsFiller() }
        // Ticket 73 (status banner): the pill's chrome derivation and its
        // sticky send-failure slot, registered in 76's BANNER slot (30) —
        // the exact line of 73's wiring contract (`StatusBanner(
        // retryDelivery = { (core as CoreSessionCore).handle.retryDelivery() })`),
        // never a second send-failure filler (78 registers no fallback).
        // 83's queue-edit recovery reports through this filler's
        // `noteFailureMessage`, and the pill's tap cancels an open edit
        // through its `onEditingTap`.
        chromeFillers.register(ChromeFillerRegistry.BANNER) { core ->
            StatusBanner(retryDelivery = { (core as CoreSessionCore).handle.retryDelivery() })
        }
        // Ticket 89 (chip menus): the content and actions behind the
        // model / effort / PR chips — slot 21, right after the chips it
        // decorates. The filler stores the controller-refresh snapshot
        // and keeps the deferred cache; the loads and writes run on IO
        // inside it (never on main, the #609 trap).
        chromeFillers.register(ChromeFillerRegistry.CHIP_MENUS) { sessionCore ->
            SessionChipMenus(CoreChipMenuCore.of(sessionCore))
        }
        // android-next#05 (plan usage, folded into the context chip):
        // the worse-of tint over #01's context chip and the context
        // menu's account half (the login in use, its windows, the other
        // logins, "Accounts & Usage…") — slot 22, after the menus. The
        // loads and switches run on the filler's own IO scope; fill
        // touches no FFI.
        chromeFillers.register(ChromeFillerRegistry.USAGE_CHIP) { _ ->
            SessionAccountUsage(CoreAccountsCore(client = ::clientOrNull))
        }
        // The failover port (orchestration v2 ticket 09 + 07's pills): the
        // Limited banner — derives `SessionChrome.limited` from the
        // composer's limit record, loads the failover candidates behind
        // the banner (TTL 30 s), and runs the strip's actions (Resume,
        // arm/cancel the durable resume, the account switch). Slot 25,
        // after the usage chip, before 73's banner. The loads and
        // switches run on the filler's own IO scope; fill touches no FFI.
        chromeFillers.register(ChromeFillerRegistry.LIMITED) { core ->
            LimitedBanner(CoreLimitedCore((core as CoreSessionCore).client))
        }
        // Ticket 82 (queue panel): the queue mapping into
        // `SessionChrome.queue` — gate labels, `visibleText`, order —
        // registered in 76's QUEUE slot (50), so every opened route fills
        // it after the banner and chips. The registry hands each open a
        // fresh instance; the per-route `QueueActions` over the live
        // handle is built by the factory below (never a shared filler).
        chromeFillers.register(ChromeFillerRegistry.QUEUE) { _ -> QueueChromeFiller() }
        // Ticket 91 (question panel): the open input request mapped into
        // `SessionChrome.questions` (request id + one Question per
        // UserInputQuestion, field for field, entry_id dropped) —
        // registered in 76's QUESTIONS slot (40), after the banner and
        // chips, before the queue, the iOS `refresh()` order. The filler
        // makes no FFI call; the panel's per-route QuestionActions over
        // the live handle is built by 75's screen from 76's route core.
        chromeFillers.register(ChromeFillerRegistry.QUESTIONS) { _ -> QuestionsChromeFiller() }
        // Ticket 76: the real session route factory. Every open runs off
        // main through 32's `openSessionRoute`, over `coreStartup.sessionEvents`
        // (51's hub — the same instance the client's events already fan out
        // into) and the filler registry above. A refused id returns null and
        // the shell shows the open-error toast: no screen, no placeholder.
        // android-app#28's cancel-on-open seam rides along: opening a chat
        // (the session screen's first composition) or closing it (a committed
        // pop) cancels its notification through `onSeen`.
        installSessionRoutes(
            routerHooks,
            CoreSessionOpener(::clientOrNull),
            coreStartup.sessionEvents,
            chromeFillers,
            onSeen = { chatId -> notifications.cancelChatFor(chatId) },
            queueScope = applicationScope,
        )
        // Tickets 40/42/51 register their event sinks here, before start().
        // #40: forcing `workspaceFeed`'s lazy init registers its sink; the
        // start() call below only arms its drains (they skip until the
        // client exists), and the feed's own collector re-triggers once
        // CoreStartup leaves Loading.
        // #42: forcing `rowActions`'s lazy init registers the appEvents
        // sink, and startNoticeToasts arms the app-wide Notice collector
        // before the first event can flow.
        // #96: arming the appearance controller pushes the stored choice
        // to the platform's per-app night mode (async: it first waits for
        // the DataStore read, so the platform's own persisted mode already
        // covered the splash of this launch).
        appearanceController.start()
        // android-app#28: the notification pipeline — channels migrated and
        // created, the foreground gates fed from the process lifecycle (a
        // process-wide observer, exactly once: never per activity — #609's
        // leak), the run-watching service controller fed with the engine
        // count and the notifications-on gate (master pref + permission;
        // the permission can be revoked from system settings, so it is
        // re-read on every change and foreground edge). Forcing
        // `notifications` also installs the sink on the client the moment
        // 71's pipeline reports it ready.
        notifications.foreground = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    notifications.foreground = true
                    runWatch.onForeground(true)
                    refreshNotificationsOn()
                    // android-next#04: the update loop's hourly cadence and
                    // the install resume ride the same foreground edge.
                    updates.onForeground(true)
                }

                override fun onStop(owner: LifecycleOwner) {
                    notifications.foreground = false
                    runWatch.onForeground(false)
                    updates.onForeground(false)
                }
            },
        )
        applicationScope.launch {
            coreStartup.engines.collect { engines ->
                runWatch.onEngineCount(engines?.size ?: 0)
            }
        }
        applicationScope.launch {
            notificationPrefs.state.collect { refreshNotificationsOn() }
        }
        // ADR 0010: Settings → "Stay connected in background" holds the
        // service (false until the DataStore read lands; the flip from
        // Settings arrives while the app is visible, so the start is legal).
        applicationScope.launch {
            prefs.stayConnected.collect { runWatch.onStayConnected(it) }
        }
        if (core is CoreStatus.Loaded) {
            workspaceFeed.start()
            rowActions.startNoticeToasts(appEvents.notices())
            coreStartup.start()
        }
        // android-next#04: the update checks start with the process (the
        // launch check's 2 s delay waits out the first frame). Debug and
        // benchmark builds check too — the center's BuildConfig flag only
        // turns the install path into the releases-page link.
        updates.start()
    }

    /**
     * Re-derive the run-watching service's `notificationsOn` gate (the
     * master pref + the live permission status). Also called by
     * MainActivity when the permission dialog returns.
     */
    internal fun refreshNotificationsOn() {
        runWatch.onNotificationsOn(
            NotificationPolicy.masterOn(
                notificationPrefs.state.value,
                NotificationPermission.status(this, notificationPrefs),
            ),
        )
    }

    /** The live client, or null before `CoreStartState.Ready`. */
    fun clientOrNull(): CoreClient? = coreStartup.clientOrNull()
}

/**
 * Every session id the workspace snapshot carries (ticket 36's
 * `knownChatIds`): pinned, sections, recent, groups, projects,
 * projectless, pull requests and archived — all **scoped** ids
 * (`engine:v1:…`), all already in memory (`emptyList()` while the
 * snapshot is null). Deduplicated: `recent`, `groups`, `projects` and
 * `projectless` deliberately overlap, and the hub's raw-id resolution
 * counts **distinct** matching scoped ids.
 */
private fun WorkspaceSnapshot?.allSessionIds(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        front.pinned.forEach { add(it.id) }
        front.sections.forEach { section -> section.sessions.forEach { add(it.id) } }
        front.recent.forEach { add(it.id) }
        front.groups.forEach { group -> group.sessions.forEach { add(it.id) } }
        projects.forEach { project -> project.sessions.forEach { add(it.id) } }
        addAll(projectless.map { it.id })
        addAll(pullRequests.open.map { it.id })
        addAll(pullRequests.merged.map { it.id })
        addAll(pullRequests.closed.map { it.id })
        addAll(archived.map { it.id })
    }.distinct()
}

/**
 * The launch-pair gate's bound (android-app#30): the first-frame hold's own
 * budget for a `pair` launch (`MainActivity.PAIR_HOLD_TIMEOUT_MS`).
 */
private const val LAUNCH_PAIR_GATE_MS = 20_000L
