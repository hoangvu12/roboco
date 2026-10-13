package sh.roboco.android

import android.app.Application
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
import sh.roboco.android.core.AppLifecycleHooks
import sh.roboco.android.core.CoreClientWorkspace
import sh.roboco.android.core.CoreLoader
import sh.roboco.android.core.CoreLifecycleSink
import sh.roboco.android.core.CoreStartState
import sh.roboco.android.core.CoreStartup
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.core.FontGate
import sh.roboco.android.core.Prefs
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.WorkspaceCore
import sh.roboco.android.core.WorkspaceFeed
import sh.roboco.android.core.robocoPrefsStore
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ToasterState
import sh.roboco.android.pairing.KeystoreSecretCipher
import sh.roboco.android.pairing.PairedEngineStore
import sh.roboco.android.sessions.CoreRowActions
import sh.roboco.android.sessions.FoldState
import sh.roboco.android.sessions.FrontPageModel
import sh.roboco.android.sessions.asSessionWrites
import sh.roboco.android.sessions.notices
import sh.roboco.android.shell.RootInput
import sh.roboco.android.shell.RouterHooks
import sh.roboco.android.shell.SearchTabState
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.CoreClient

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
 * `drafts` (#84) — do not reuse them for something else.
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
     * The appearance preference (ticket 96's flow; `System` until 96
     * wires the store): ticket 35 reads it into `RobocoTheme(appearance)`
     * — the port of `window.overrideUserInterfaceStyle`
     * (`SceneDelegate.swift:14`).
     */
    val appearance = MutableStateFlow(Appearance.System)

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
     * The startup pipeline (71's `onClientReady` hook wired by ticket 35:
     * the lifecycle hooks re-send the network state to the client it
     * creates).
     */
    val coreStartup by lazy {
        CoreStartup(
            context = this,
            store = engineStore,
            scope = applicationScope,
            onClientReady = { lifecycleHooks.clientReady() },
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

    /** The Sessions tab's front page model (ticket 40); 46 hosts it. */
    val frontPage: FrontPageModel by lazy {
        FrontPageModel(workspaceFeed, prefs, foldState, applicationScope)
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
            is CoreStatus.Loaded -> combine(coreStartup.state, coreStartup.engines) { s, engines ->
                when (s) {
                    CoreStartState.Loading -> RootInput.Loading
                    is CoreStartState.Failed -> RootInput.CoreFailed(s.reason)
                    CoreStartState.Ready ->
                        engines?.let { RootInput.Ready(hasEngines = it.isNotEmpty()) }
                            ?: RootInput.Loading
                }
            }.distinctUntilChanged()
                .stateIn(applicationScope, SharingStarted.Eagerly, RootInput.Loading)
        }
        // Tickets 40/42/51 register their event sinks here, before start().
        // #40: forcing `workspaceFeed`'s lazy init registers its sink; the
        // start() call below only arms its drains (they skip until the
        // client exists), and the feed's own collector re-triggers once
        // CoreStartup leaves Loading.
        // #42: forcing `rowActions`'s lazy init registers the appEvents
        // sink, and startNoticeToasts arms the app-wide Notice collector
        // before the first event can flow.
        if (core is CoreStatus.Loaded) {
            workspaceFeed.start()
            rowActions.startNoticeToasts(appEvents.notices())
            coreStartup.start()
        }
    }

    /** The live client, or null before `CoreStartState.Ready`. */
    fun clientOrNull(): CoreClient? = coreStartup.clientOrNull()
}
