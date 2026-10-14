package sh.roboco.android.sessions

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import sh.roboco.android.core.Prefs
import sh.roboco.android.core.WorkspaceCore
import sh.roboco.android.core.WorkspaceFeed
import sh.roboco.android.core.testPrefsStore
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.Connectivity
import uniffi.roboco_core.ConnectivityState
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineDiagnostics
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.SessionGroup
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * Fixture builders for the front-page tests (android-ui-parity #40):
 * the generated UniFFI records with neutral defaults for every field the
 * ticket does not exercise. The records are plain Kotlin data classes
 * whose initialisation touches no JNA, so these tests run on the plain
 * JVM (the same property `LiveCountsTest` relies on).
 */

/** A row with only the fields the section model reads set apart. */
internal fun row(
    id: String,
    engineId: String = "A",
    indicator: ChatIndicator = ChatIndicator.IDLE,
    deviceId: String = "device-$engineId",
    deviceName: String? = null,
): SessionRow = SessionRow(
    id = id,
    engineId = engineId,
    revision = 1uL,
    title = id,
    hasTitle = true,
    preview = null,
    project = null,
    deviceId = deviceId,
    deviceName = deviceName,
    deviceOnline = true,
    engineOnline = true,
    harness = null,
    harnessLabel = null,
    model = null,
    modelLabel = null,
    reasoning = null,
    branch = null,
    cwd = null,
    indicator = indicator,
    hostIndicator = indicator,
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

/** A user section (engine `A` unless overridden). */
internal fun section(
    id: String,
    name: String = id,
    engineId: String = "A",
    collapsed: Boolean = false,
    rows: List<SessionRow> = emptyList(),
): SectionView = SectionView(
    id = id,
    engineId = engineId,
    name = name,
    collapsed = collapsed,
    sessions = rows,
)

/** A `recent` group, as the core derives it (the engine comes from the rows). */
internal fun group(
    id: String,
    name: String = id,
    rows: List<SessionRow> = emptyList(),
): SessionGroup = SessionGroup(id = id, name = name, sessions = rows)

/** A front page. */
internal fun front(
    pinned: List<SessionRow> = emptyList(),
    sections: List<SectionView> = emptyList(),
    recent: List<SessionRow> = emptyList(),
    groups: List<SessionGroup> = emptyList(),
): FrontPage = FrontPage(pinned = pinned, sections = sections, recent = recent, groups = groups)

/** A workspace snapshot. */
internal fun snapshot(
    revision: ULong,
    front: FrontPage = front(),
    synced: Boolean = true,
): WorkspaceSnapshot = WorkspaceSnapshot(
    revision = revision,
    computedAtMs = 0L,
    pinsReady = false,
    synced = synced,
    front = front,
    projects = emptyList(),
    projectless = emptyList(),
    pullRequests = PullRequestGroups(emptyList(), emptyList(), emptyList()),
    archived = emptyList(),
    devices = emptyList(),
)

/** A paired engine (connected by default). */
internal fun engine(
    engineId: String,
    name: String = engineId,
    state: EngineState = EngineState.Connected,
    lastError: String? = null,
): EngineView = EngineView(
    engineId = engineId,
    name = name,
    host = "host-$engineId",
    baseUrl = "http://127.0.0.1:1",
    deviceId = null,
    platform = null,
    version = null,
    state = state,
    lastError = lastError,
    retryAtMs = null,
    lastConnectedMs = null,
    capabilities = emptyList(),
    notice = null,
    fromCache = false,
    diagnostics = EngineDiagnostics(
        repairedRows = 0u,
        skippedRows = 0u,
        lastDecodeError = null,
        missingStreams = emptyList(),
        generation = 0uL,
    ),
)

/** The payload of a `ConnectivityChanged` event. */
internal fun connectivity(state: ConnectivityState = ConnectivityState.CONNECTED): Connectivity =
    Connectivity(state = state, retryAtMs = null, lastFailure = null, degradedChats = emptyList())

/**
 * The [WorkspaceCore] fake: mutable return values, call logs and counters,
 * and scripted [CoreException]s — [setErrors] is consumed one per call
 * (empty = success). [duringWorkspace] runs inside `workspace()` after the
 * counter, so a test can fire events mid-read (the coalescing assertions).
 * Every call records its thread for the no-FFI-on-main assertions.
 */
internal class FakeWorkspaceCore(
    var ws: WorkspaceSnapshot = snapshot(0uL),
) : WorkspaceCore {

    /** `workspace()` call count. */
    val workspaceCalls = AtomicInteger()

    /** `engines()` call count. */
    val enginesCalls = AtomicInteger()

    /** Every `setSectionCollapsed` pair, in call order. */
    val setCalls = CopyOnWriteArrayList<Pair<String, Boolean>>()

    /** Errors `setSectionCollapsed` throws, one per call (empty = success). */
    val setErrors = ArrayDeque<CoreException>()

    /** The engine list `engines()` serves. */
    var engineList: List<EngineView> = emptyList()

    /**
     * The aggregate posture `connectivity()` serves (ticket 50). Built
     * inline, not through the `connectivity()` fixture helper: inside
     * this class that name resolves to the member and would recurse.
     */
    var connectivityValue: Connectivity = Connectivity(
        state = ConnectivityState.CONNECTED,
        retryAtMs = null,
        lastFailure = null,
        degradedChats = emptyList(),
    )

    /** The threads every call ran on. */
    val callThreads = CopyOnWriteArrayList<Thread>()

    /** Runs inside `workspace()` after the counter, before the return. */
    var duringWorkspace: (() -> Unit)? = null

    override fun workspaceRevision(): ULong {
        callThreads.add(Thread.currentThread())
        return ws.revision
    }

    override fun workspace(): WorkspaceSnapshot {
        workspaceCalls.incrementAndGet()
        callThreads.add(Thread.currentThread())
        duringWorkspace?.invoke()
        return ws
    }

    override fun engines(): List<EngineView> {
        enginesCalls.incrementAndGet()
        callThreads.add(Thread.currentThread())
        return engineList
    }

    override fun connectivity(): Connectivity {
        callThreads.add(Thread.currentThread())
        return connectivityValue
    }

    override fun setSectionCollapsed(sectionId: String, collapsed: Boolean) {
        callThreads.add(Thread.currentThread())
        setCalls.add(sectionId to collapsed)
        setErrors.removeFirstOrNull()?.let { throw it }
    }
}

/** A [Feedback] that records each haptic — the "exactly one Select" assertions. */
internal class RecordingFeedback : Feedback {
    val haptics = CopyOnWriteArrayList<Haptic>()
    val cues = CopyOnWriteArrayList<Cue>()
    override fun haptic(haptic: Haptic) {
        haptics.add(haptic)
    }
    override fun cue(cue: Cue) {
        cues.add(cue)
    }
}

/**
 * Everything a model-level test needs — a fake core, the feed, the prefs,
 * the fold state, the engine-health model (ticket 50) and the front-page
 * model — over one DataStore file and test-scheduler scopes, left to
 * `runTest`'s teardown.
 */
internal class FrontFixture(
    val core: FakeWorkspaceCore,
    val feed: WorkspaceFeed,
    val prefs: Prefs,
    val fold: FoldState,
    val health: EngineHealthModel,
    val model: FrontPageModel,
)

/** Builds a [FrontFixture] over `<dir>/<fileName>.preferences_pb`. */
internal fun TestScope.newFrontFixture(
    dir: File,
    fileName: String,
    ws: WorkspaceSnapshot = snapshot(1uL),
    engineList: List<EngineView> = emptyList(),
): FrontFixture {
    val core = FakeWorkspaceCore(ws)
    core.engineList = engineList
    val io = StandardTestDispatcher(testScheduler)
    val prefsScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    val prefsStore = testPrefsStore(prefsScope, File(dir, "$fileName.preferences_pb"))
    val feed = WorkspaceFeed(core = { core }, scope = CoroutineScope(SupervisorJob() + io), io = io)
    val prefs = Prefs(prefsStore, prefsScope, io = io)
    val fold = FoldState(prefsStore, core, prefsScope, io = io)
    // Ticket 50: the health model over the feed's own flows, on the test
    // scheduler's virtual clock so a grace never waits real time.
    val health = EngineHealthModel(
        engines = feed.engines,
        connectivity = feed.connectivity,
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        clock = { testScheduler.currentTime },
    )
    val model = FrontPageModel(
        feed = feed,
        prefs = prefs,
        fold = fold,
        health = health,
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        compute = io,
    )
    return FrontFixture(core, feed, prefs, fold, health, model)
}
