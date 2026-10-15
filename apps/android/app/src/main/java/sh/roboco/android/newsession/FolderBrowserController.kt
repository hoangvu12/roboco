package sh.roboco.android.newsession

import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.FolderListing
import uniffi.roboco_core.hostPathJoin
import uniffi.roboco_core.hostPathName
import uniffi.roboco_core.hostPathParent

/**
 * The folder browser's browsing brain (android-ui-parity #110): the load
 * guard, the status strings and the "listing usable" rule — the port of
 * `ios:Threads/NewProjectViewController.swift:98-160` (with the tap guard
 * of `pr685:…:161-162`), minus everything 111/112 add on top.
 *
 * The screen (`NewProjectScreen.kt`) draws the nav bar, the engine menu
 * and the folder rows; this file never draws. The core is reached only
 * through [FolderBrowserSource], host-path arithmetic only through
 * [HostPathOps], so plain-JVM tests need no native library. Everything
 * mutates on the main thread; the FFI call runs inside
 * [CoreFolderBrowserSource] on `Dispatchers.IO`, never on main.
 */

/** android-app#09 `listFolders`: returns null on any failure (the iOS `try?`), never throws except cancellation. */
// A plain interface: a fun interface's abstract method may not declare
// a default (showHidden), and nothing builds a source from a SAM lambda.
interface FolderBrowserSource {
    /** `showHidden` also lists `.`-folders (the engine's only switch, `query: "."`) — ticket 112 turns it on. */
    suspend fun listFolders(deviceId: String, path: String?, showHidden: Boolean = false): FolderListing?
}

/**
 * The source over the live core: resolves [client] at call time (null →
 * `null` — iOS's `try?` on a missing client), calls `listFolders` on [io]
 * and swallows every non-cancellation failure with a log. The
 * `showHidden` flag passes straight through — 112's `.`-query reload is
 * the only caller that sets it.
 */
class CoreFolderBrowserSource(
    private val client: () -> CoreClient?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FolderBrowserSource {
    override suspend fun listFolders(
        deviceId: String,
        path: String?,
        showHidden: Boolean,
    ): FolderListing? {
        val c = client() ?: return null
        return try {
            withContext(io) { c.listFolders(deviceId = deviceId, path = path, showHidden = showHidden) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("FolderBrowser", "listFolders failed", e)
            null
        }
    }
}

/**
 * Seam over the Rust host-path helpers so JVM tests do not need the
 * native library: one level up, a join, the last component. The rules
 * live in android-app#09 (`crates/mobile-client/src/host_paths.rs`);
 * `pr621:apps/android/app/src/main/java/sh/zeron/android/ui/FolderBrowser.kt:57-130`
 * is the logic reference.
 */
interface HostPathOps {
    /** `C:\Users` → `C:\`; `C:\` → null; `/home` → `/`; `/` → null; `\\srv\share\x` → `\\srv\share`. */
    fun parent(path: String): String?

    /** `\` on a Windows base, `/` otherwise; no separator if the base ends in one. */
    fun join(base: String, name: String): String

    /** The last component, or the path itself for a root (`C:\`, `/`). */
    fun name(path: String): String
}

/** The real host-path helpers: android-app#09's free functions over the FFI. */
object FfiHostPaths : HostPathOps {
    override fun parent(path: String): String? = hostPathParent(path)
    override fun join(base: String, name: String): String = hostPathJoin(base, name)
    override fun name(path: String): String = hostPathName(path)
}

/** One row of the folder list: `".."` (the parent) or a directory from the listing. */
sealed interface FolderRow {
    /** The list's key: unique within one listing. */
    val key: String

    /** `items.append("..")` — `Parent Folder`, always first when present. */
    data object Parent : FolderRow {
        override val key: String = ".."
    }

    /** `listing.entries.filter(\.isDir)` — files are never listed. */
    data class Folder(val name: String, val isRepo: Boolean) : FolderRow {
        override val key: String get() = "dir:$name"
    }
}

/** The screen's copy, verbatim from the Swift source (see the ticket's copy table). */
object FolderBrowserCopy {
    const val DefaultTitle = "New Project"
    const val ParentFolder = "Parent Folder"

    /** `Loading…` — U+2026, as in the Swift source. */
    const val Loading = "Loading…"

    /** Straight apostrophe U+0027, as in the Swift source. */
    const val ReadFailed = "Couldn't read that folder."
    const val NoEngines = "No paired engines yet."
    const val Engines = "Engines"
    const val Devices = "Devices"
    const val Online = "Online"
    const val Offline = "Offline"

    /** `"\(device.name) is offline."` */
    fun offline(name: String) = "$name is offline."
}

/**
 * The load-guard state holder (the `load(_:)` / `didSelectItemAt` /
 * host-picker behaviour of `NewProjectViewController.swift`):
 *
 * - **Generation guard** — every load bumps a private generation counter;
 *   a reply whose generation is stale is dropped without any state
 *   change, exactly like iOS's `guard generation == loadGeneration`.
 *   Switching engines or tapping twice quickly can never show the older
 *   reply. Cancelling the previous job is never a substitute — UniFFI
 *   futures may complete after cancellation — only the compare guarantees
 *   a late reply is never applied.
 * - **No clearing on failure** — a failed or in-flight load keeps the last
 *   good `listing` (iOS keeps the last rows); the tap guard, not an empty
 *   list, protects the user.
 * - **`listingUsable`** — iOS's `useButton.isEnabled` made declarative:
 *   false while loading, true after a success, and after a failure true
 *   only when the last good listing came from the currently selected
 *   engine. Ticket 111 draws it.
 *
 * All mutators are main-thread only; the reply is applied on [scope]
 * (main). Screen disposed → the scope is cancelled; in-flight replies are
 * discarded.
 */
@Stable
class FolderBrowserController(
    private val source: FolderBrowserSource,
    private val paths: HostPathOps,
    private val scope: CoroutineScope,
    /** Non-null → engines/engine are set synchronously in init (no load — no coroutine may launch during composition). */
    initialHosts: List<HostOption>? = null,
) {
    /** The live host list (iOS reads `hostOptions` once; Roboco's list is live — see the ticket's Adaptation). */
    var engines: List<HostOption> by mutableStateOf(emptyList())
        private set

    /** The selected engine; the first online one by default, else the first. */
    var engine: HostOption? by mutableStateOf(null)
        private set

    /** The last good listing; never cleared by a failure or a load. */
    var listing: FolderListing? by mutableStateOf(null)
        private set

    /** The engine the [listing] came from (iOS's `listingDevice`). */
    var listingEngineId: String? by mutableStateOf(null)
        private set

    /** `isRepo` of the row tapped to reach [listing]; null = unknown (home, parent, engine switch). For 111/112. */
    var listingIsRepo: Boolean? by mutableStateOf(null)
        private set

    /** The listing's path (iOS's `path`). */
    var path: String? by mutableStateOf(null)
        private set

    /**
     * The engine user's home path: the last successful **pathless** load's
     * `listing.path`; null until known. 112's Home chip root. Reset on
     * engine change (a pathless load re-resolves it on success).
     */
    var home: String? by mutableStateOf(null)
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    /** Owned by ticket 111 (create project / new folder); this ticket only reads it in the tap guard. */
    var submitting: Boolean by mutableStateOf(false)

    /** The status string of the last load state; ticket 111 draws it. */
    var status: String by mutableStateOf("")

    /** Bumps on every applied listing; the screen uses it in row keys (scroll retention). */
    var listingSerial: Int by mutableIntStateOf(0)
        private set

    /** Set by ticket 111; called on main after each applied (non-superseded) listing. */
    var onListingApplied: ((engineId: String, listing: FolderListing) -> Unit)? = null

    init {
        initialHosts?.let { hosts ->
            engines = hosts
            engine = hosts.firstOrNull { it.online } ?: hosts.firstOrNull()
        }
    }

    /** The parent row (when the path has one) plus the directories, in engine order. */
    val rows: List<FolderRow> by derivedStateOf {
        val l = listing ?: return@derivedStateOf emptyList()
        buildList {
            if (paths.parent(l.path) != null) add(FolderRow.Parent)
            for (entry in l.entries) if (entry.isDir) add(FolderRow.Folder(entry.name, entry.isRepo))
        }
    }

    /** The nav-bar title: the folder name, or `New Project` for home/roots. */
    val title: String by derivedStateOf {
        listing?.let { l -> paths.name(l.path) }?.takeIf { it.isNotEmpty() } ?: FolderBrowserCopy.DefaultTitle
    }

    /** True when the current listing's path has a parent — the `..` row's own check, read by 112's filtered list. */
    val listingHasParent: Boolean
        get() = listing?.let { paths.parent(it.path) != null } ?: false

    /**
     * Ticket 112's navigation hook: when non-null, row taps browse through
     * it (clearing 112's query and typed-path state) instead of calling
     * [load] directly; null (the default, and what the tests use unless
     * they wire it) keeps the direct load. The tap guard and the path
     * arithmetic stay here either way.
     */
    var onNavigate: ((path: String?, gitHint: Boolean?) -> Unit)? = null

    /** The engine capsule's label: the selected engine's name, or the empty copy. */
    val capsuleTitle: String get() = engine?.name ?: FolderBrowserCopy.NoEngines

    /** The engine menu's title: `Engines` when more than one engine is paired, else `Devices` (the web rule). */
    val menuTitle: String get() = if (engines.size > 1) FolderBrowserCopy.Engines else FolderBrowserCopy.Devices

    /** iOS's `useButton.isEnabled`, made declarative (see the class doc). Read by ticket 111. */
    val listingUsable: Boolean
        get() {
            val e = engine ?: return false
            return !loading && listing != null && listingEngineId == e.id
        }

    private var generation = 0
    private var started = false

    /**
     * First call: engines = hosts, default pick, `load(null)` — also when
     * init already set them from `initialHosts` (the replayed StateFlow
     * value). Later calls: the ticket's host-list rules — refresh
     * [engines]; a null [engine] plus a non-empty list (or a selected id
     * that vanished) re-runs the default pick and loads; otherwise the
     * same-id entry replaces [engine] (fresh name/online) **without**
     * reloading.
     */
    fun updateEngines(hosts: List<HostOption>) {
        checkMainThread()
        if (!started) {
            started = true
            engines = hosts
            engine = hosts.firstOrNull { it.online } ?: hosts.firstOrNull()
            load(null)
            return
        }
        engines = hosts
        val current = engine
        when {
            current == null && hosts.isNotEmpty() -> {
                engine = hosts.firstOrNull { it.online } ?: hosts.firstOrNull()
                home = null
                load(null)
            }
            current != null && hosts.none { it.id == current.id } -> {
                // The selected engine was forgotten while the sheet was open:
                // the default pick runs again (may be null → the no-engines status).
                engine = hosts.firstOrNull { it.online } ?: hosts.firstOrNull()
                home = null
                load(null)
            }
            current != null -> engine = hosts.first { it.id == current.id }
            else -> Unit // still no engines: nothing to pick, nothing to reload
        }
    }

    /** Picks an engine (also the current one — iOS reloads at home either way) and reloads its home. */
    fun selectEngine(id: String) {
        checkMainThread()
        val picked = engines.firstOrNull { it.id == id } ?: return
        engine = picked
        home = null
        load(null)
    }

    /**
     * `load(_:)`: `Loading…` + a generation bump, then the FFI call on
     * the scope; a stale reply changes nothing at all. With no engine:
     * the no-engines status and a generation bump (so a forgotten
     * engine's in-flight reply can never apply), nothing else. A
     * successful **pathless** load records [home] (112's Home chip root);
     * `showHidden` asks the engine for `.`-folders (112's `.`-query
     * reload — 110 itself never sets it).
     */
    fun load(path: String?, isRepoHint: Boolean? = null, showHidden: Boolean = false) {
        checkMainThread()
        val e = engine ?: run {
            generation++
            loading = false
            status = FolderBrowserCopy.NoEngines
            return
        }
        loading = true
        status = FolderBrowserCopy.Loading
        val gen = ++generation
        scope.launch {
            val result = source.listFolders(e.id, path, showHidden)
            // A newer load (another engine or folder) superseded this one.
            if (gen != generation) return@launch
            loading = false
            if (result == null) {
                val live = engines.firstOrNull { it.id == e.id } ?: e
                status =
                    if (live.online) FolderBrowserCopy.ReadFailed else FolderBrowserCopy.offline(live.name)
                // Keep browsing the last good folder — but only on the same engine.
                return@launch
            }
            Snapshot.withMutableSnapshot {
                listingEngineId = e.id
                listing = result
                this@FolderBrowserController.path = result.path
                listingIsRepo = isRepoHint
                status = result.path
                if (path == null) home = result.path
                listingSerial++
            }
            onListingApplied?.invoke(e.id, result)
        }
    }

    /**
     * `didSelectItemAt` with the `pr685:…:161-162` guard: taps are ignored
     * while loading, while 111's submitting is set, and when the listing
     * came from another engine. Parent → up one level; folder → down (the
     * row's `isRepo` becomes the next listing's hint). 112's wiring routes
     * the browse through [onNavigate] (clearing its query); the direct
     * [load] stays the default.
     */
    fun tap(row: FolderRow) {
        checkMainThread()
        if (loading || submitting) return
        val e = engine ?: return
        val l = listing ?: return
        if (listingEngineId != e.id) return
        when (row) {
            FolderRow.Parent -> paths.parent(l.path)?.let { browseTo(it, null) }
            is FolderRow.Folder -> browseTo(paths.join(l.path, row.name), row.isRepo)
        }
    }

    /** The tap's browse: 112's hook when wired, else the direct load. */
    private fun browseTo(path: String?, gitHint: Boolean?) {
        val nav = onNavigate
        if (nav != null) nav(path, gitHint) else load(path, isRepoHint = gitHint)
    }

    /**
     * Main-thread assertion, skipped when there is no looper at all (plain
     * JVM tests); Robolectric runs the test on the main looper, so it holds
     * there too.
     */
    private fun checkMainThread() {
        val my = Looper.myLooper() ?: return
        check(my == Looper.getMainLooper()) { "FolderBrowserController mutations must run on the main thread" }
    }
}

/**
 * The sheet-body wiring (ticket 92 step 10's pattern, mirrored exactly):
 * the app's single workspace feed snapshot mapped through
 * `WorkspaceSnapshot.toNewSessionOptions()` into the hosts flow, the
 * [CoreFolderBrowserSource] over `RobocoApplication.clientOrNull()`, and
 * the controller over both. The StateFlow replays its current value, so
 * the first `collect` performs the first-call branch (default pick +
 * `load(null)`) right after the first frame; the initial value is the
 * current snapshot, not `null`, so the first frame already has the
 * default engine.
 */
@Composable
fun rememberFolderBrowserController(
    hosts: StateFlow<List<HostOption>?>,
    source: FolderBrowserSource,
    paths: HostPathOps = FfiHostPaths,
): FolderBrowserController {
    val scope = rememberCoroutineScope()
    val controller = remember { FolderBrowserController(source, paths, scope, initialHosts = hosts.value) }
    LaunchedEffect(hosts) {
        hosts.filterNotNull().collect { controller.updateEngines(it) }
    }
    return controller
}
