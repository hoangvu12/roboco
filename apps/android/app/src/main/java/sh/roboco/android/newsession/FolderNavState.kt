package sh.roboco.android.newsession

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.DriveEntry
import uniffi.roboco_core.FolderListing
import uniffi.roboco_core.PreparedPath

/**
 * The typed-path state machine and the locations rail's data (android-ui-
 * parity #112): the search/typed-path field's behaviour and the drives
 * load, as the web add-project palette's flow (`state/add-space.ts`)
 * adapted to IME-Go probing. The rail and the field themselves are
 * `FolderLocationsHeader.kt`; this file owns the behaviour between them
 * and 110/111's browser parts.
 */

/** The non-core fallback for a failed typed-path create (PR #685's copy). */
internal const val FOLDER_CREATE_FALLBACK = "Couldn't create the folder."

// ---------------------------------------------------------------------------
// The host seam over 110/111
// ---------------------------------------------------------------------------

/**
 * What this ticket needs from 110/111's folder browser: the browsing state
 * and the two write hooks. Implemented over the pair by
 * [FolderBrowserHostAdapter]; tests implement it on a fake.
 */
interface FolderBrowserHost {
    /** The selected engine's scoped device id; null = no engines. */
    val deviceId: String?

    val deviceOnline: Boolean

    /** The last good listing (110 keeps it across failures). */
    val listing: FolderListing?

    /** The engine the [listing] came from (iOS's `listingDevice`). */
    val listingDeviceId: String?

    /**
     * The path of the last successful **pathless** load on this engine;
     * null until known. The Home chip's root. 110 resets it on engine
     * change.
     */
    val home: String?

    val loading: Boolean

    /** 111's shared write guard: ✕, rows, chips and the field all read it. */
    val submitting: Boolean

    /**
     * 110's generation-guarded load. `gitHint` is the git flag 111 uses
     * for the folder if it is created (the tapped row's `isRepo`, or the
     * probe's `gitDetected` for a typed path).
     */
    fun load(path: String?, showHidden: Boolean = false, gitHint: Boolean? = null)

    /** 111's shared submitting setter (no `creatingProject`: no Use spinner). */
    fun setSubmitting(busy: Boolean)

    /**
     * 111's create body without its entry guard: dedupe, `createSpace`,
     * `onCreated` + dismiss on success, status
     * `Couldn't create the project on <device>.` on failure. True on
     * success.
     */
    suspend fun createProjectAt(path: String, gitDetected: Boolean): Boolean
}

/**
 * [FolderBrowserHost] over 110's [FolderBrowserController] and 111's
 * [FolderBrowserActions] — the ticket's adapter table, one row per member.
 */
class FolderBrowserHostAdapter(
    private val controller: FolderBrowserController,
    private val actions: FolderBrowserActions,
) : FolderBrowserHost {
    override val deviceId: String? get() = controller.engine?.id
    override val deviceOnline: Boolean get() = controller.engine?.online == true
    override val listing: FolderListing? get() = controller.listing
    override val listingDeviceId: String? get() = controller.listingEngineId
    override val home: String? get() = controller.home
    override val loading: Boolean get() = controller.loading
    override val submitting: Boolean get() = actions.submitting
    override fun load(path: String?, showHidden: Boolean, gitHint: Boolean?) =
        controller.load(path, isRepoHint = gitHint, showHidden = showHidden)
    override fun setSubmitting(busy: Boolean) = actions.setSubmitting(busy)
    override suspend fun createProjectAt(path: String, gitDetected: Boolean): Boolean =
        actions.createProjectAt(path, gitDetected)
}

// ---------------------------------------------------------------------------
// The calls seam over the core
// ---------------------------------------------------------------------------

/**
 * Test seam over the core: the drives listing and the typed-path probe
 * (android-app#09's `listDrives` / `prepareSpacePath`) plus 110's host-path
 * join. `prepareSpacePath` rides 111's [ProjectWriteApi] (never a second
 * wrapper over the FFI); [FolderNavState] script it in tests.
 */
interface FolderBrowserCalls {
    /** `ListDrives`: the engine's browse roots beyond home, in engine order. */
    suspend fun listDrives(deviceId: String): List<DriveEntry>

    /** `PrepareSpacePath`: interprets [path] on the engine; `createIfMissing` runs `create_dir_all` there. */
    suspend fun prepareSpacePath(deviceId: String, path: String, createIfMissing: Boolean): PreparedPath

    /** `host_path_join`: the first matching row's full path on plain-text Go. */
    fun join(base: String, name: String): String
}

/**
 * The live [FolderBrowserCalls]: `listDrives` over the app's [CoreClient]
 * (the same provider 110's source uses, IO-dispatched like it) and the
 * probe/join over 111's [ProjectWriteApi] / 110's [HostPathOps]. With no
 * client the drives call throws — `FolderNavState` swallows it into the
 * Home-only rail, exactly like the web's `.catch(() => drives: [])`.
 */
class CoreFolderCalls(
    private val client: () -> CoreClient?,
    private val api: ProjectWriteApi,
    private val paths: HostPathOps = FfiHostPaths,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FolderBrowserCalls {

    override suspend fun listDrives(deviceId: String): List<DriveEntry> {
        val c = client() ?: throw IllegalStateException(FolderWriteCopy.Disconnected)
        return withContext(io) { c.listDrives(deviceId) }
    }

    override suspend fun prepareSpacePath(
        deviceId: String,
        path: String,
        createIfMissing: Boolean,
    ): PreparedPath = api.prepareSpacePath(deviceId, path, createIfMissing)

    override fun join(base: String, name: String): String = paths.join(base, name)
}

// ---------------------------------------------------------------------------
// The typed-path state
// ---------------------------------------------------------------------------

/**
 * Where a typed path stands. `Idle`/`Failed` leave the Use button 111's;
 * `Missing` re-titles it **Create and add**; `Probing` disables it with
 * 110's `Loading…` status.
 */
sealed interface TypedPath {
    data object Idle : TypedPath
    data object Probing : TypedPath

    /** The engine-normalized path does not exist (yet); [error] carries a failed create's text for retry. */
    data class Missing(val path: String, val deviceId: String, val error: String? = null) : TypedPath
    data class Failed(val message: String) : TypedPath
}

/**
 * 111's Use button, overridden by [FolderNavState]: `title`/`onClick` null
 * keep 111's own value; `enabled` is the override's own guard.
 */
@Immutable
data class UseOverride(val title: String?, val enabled: Boolean, val onClick: (() -> Unit)?)

// ---------------------------------------------------------------------------
// The state holder
// ---------------------------------------------------------------------------

/**
 * The rail + field's brain. Plain text filters the folder rows
 * (`folderRows`); path-shaped text probes the engine only on IME **Go**
 * (the web probes on every keystroke — the ADAPT that saves one RPC per
 * keystroke on a phone link); a missing path turns the Use button into
 * **Create and add**.
 *
 * Staleness: every query edit and every navigation bumps `probeGen`, so an
 * in-flight probe reply for an older query or engine is dropped; the
 * drives load has its own `drivesGen` **and** re-checks the device id, so
 * a slow reply from engine A never flips engine B's rail. All state is
 * Compose snapshot state, mutated on the main thread; the FFI calls run
 * inside [scope] (110's screen scope) and their replies land back on main.
 */
@Stable
class FolderNavState(
    private val host: FolderBrowserHost,
    private val calls: FolderBrowserCalls,
    private val scope: CoroutineScope,
) {
    /** The field's text: the filter, or the typed path. */
    var query by mutableStateOf("")
        private set

    /** The engine's drives (engine order); empty until the load lands, or Home-only after a failure. */
    var drives by mutableStateOf<List<DriveEntry>>(emptyList())
        private set

    /** Where the typed path stands. */
    var typed by mutableStateOf<TypedPath>(TypedPath.Idle)
        private set

    /** Home then [drives] (web `locationRows` without the query filter). */
    val chips: List<LocationChipModel> by derivedStateOf {
        locationChips(drives.map { it.name to it.path })
    }

    /**
     * The selected chip: the one whose root holds the listing's path most
     * specifically. `null` with no listing, a listing from another engine,
     * or an unknown home.
     */
    val selectedChip: Int? by derivedStateOf { selectedLocation(listingPath, host.home, chips) }

    /** 111's status, when a typed path owns it; `null` keeps 110's. */
    val statusOverride: String? by derivedStateOf {
        when (val t = typed) {
            TypedPath.Idle -> null
            TypedPath.Probing -> FolderBrowserCopy.Loading
            is TypedPath.Missing -> t.error ?: t.path
            is TypedPath.Failed -> t.message
        }
    }

    /** 111's Use button, when a typed path owns it; `null` keeps 111's. */
    val useOverride: UseOverride? by derivedStateOf {
        when (val t = typed) {
            TypedPath.Idle -> null
            TypedPath.Probing -> UseOverride(title = null, enabled = false, onClick = null)
            is TypedPath.Missing -> UseOverride(
                title = FolderHeaderTokens.CreateAndAdd,
                enabled = !host.loading && !host.submitting && host.deviceId == t.deviceId,
                onClick = { createTyped() },
            )
            is TypedPath.Failed -> null
        }
    }

    /** The listing's path, only when it belongs to the selected engine (else no chip is selected). */
    private val listingPath: String?
        get() {
            if (host.listingDeviceId != host.deviceId) return null
            return host.listing?.path
        }

    private var probeGen = 0
    private var drivesGen = 0
    private var lastDeviceId: String? = null
    private var lastOnline = false

    /** True while a `.`-prefixed query has the hidden names shown (the reload toggle). */
    private var showingHidden = false

    // ------------------------------------------------------------- the field

    /**
     * Any edit: `query = text`, the probe generation bumps (in-flight
     * replies die) and the typed state resets. A **non-path** edit that
     * flips the `.`-prefix flag reloads the current listing with hidden
     * names on/off (the engine only returns `.`-names when asked); the
     * query itself is never cleared. A path-shaped edit never toggles the
     * flag (web `:291-301`).
     */
    fun onQueryChange(text: String) {
        if (text == query) return
        query = text
        probeGen++
        typed = TypedPath.Idle
        if (manualPathQuery(text)) return
        val hidden = text.startsWith(".")
        if (hidden == showingHidden) return
        val listing = host.listing ?: return
        if (host.listingDeviceId != host.deviceId) return
        showingHidden = hidden
        host.load(listing.path, showHidden = hidden)
    }

    /**
     * IME **Go** (focus clearing is the field's job, and it always runs):
     * a path-shaped query probes; non-blank plain text browses the first
     * matching row (web `openActive`); a blank query does nothing. Never
     * while 111 is submitting.
     */
    fun onGo() {
        if (host.submitting) return
        val text = query
        if (manualPathQuery(text)) {
            probe(text.trim())
            return
        }
        if (text.isBlank()) return
        val listing = host.listing ?: return
        if (host.listingDeviceId != host.deviceId) return
        val first = filteredFolders(listing.entries, text).firstOrNull() ?: return
        navigate(calls.join(listing.path, first.name), first.isRepo)
    }

    // -------------------------------------------------------------- the rail

    /**
     * Tapping a chip browses its root — Home means the engine user's home
     * (a pathless load). Allowed while a load is in flight (110's
     * generation guard supersedes the older load), ignored while
     * submitting.
     */
    fun onPickLocation(chip: LocationChipModel) {
        if (host.submitting) return
        navigate(chip.path, gitHint = null)
    }

    /**
     * The engine picker's feed (110's screen runs this from a
     * `LaunchedEffect(engine?.id, engine?.online)`): an engine change
     * resets everything and reloads the drives; an offline→online flip of
     * the same engine reloads only the drives (the listing reload stays
     * 110's business — the pr621 trap).
     */
    fun onDevice(id: String?, online: Boolean) {
        if (id != lastDeviceId) {
            lastDeviceId = id
            query = ""
            typed = TypedPath.Idle
            probeGen++
            showingHidden = false
            drives = emptyList()
            if (id != null) loadDrives(id)
        } else if (online && !lastOnline && id != null) {
            loadDrives(id)
        }
        lastOnline = online
    }

    // --------------------------------------------------------- navigation

    /**
     * Browse [path] (null = home), clearing the query, the typed state and
     * the hidden flag — 110's row taps and this ticket's chip taps and
     * successful probes all go through here.
     */
    fun navigate(path: String?, gitHint: Boolean?) {
        query = ""
        typed = TypedPath.Idle
        probeGen++
        showingHidden = false
        host.load(path, showHidden = false, gitHint = gitHint)
    }

    /** 110's list rows for the current query. */
    fun <T> rows(entries: List<T>, name: (T) -> String, isDir: (T) -> Boolean): FolderRows<T> =
        folderRows(entries, query, name, isDir)

    // ------------------------------------------------------- typed paths

    /** `prepareSpacePath(createIfMissing = false)` — Go never creates; creating takes the explicit Use tap. */
    private fun probe(path: String) {
        val dev = host.deviceId ?: return
        val gen = ++probeGen
        typed = TypedPath.Probing
        scope.launch {
            try {
                val result = calls.prepareSpacePath(dev, path, createIfMissing = false)
                if (gen != probeGen || host.deviceId != dev) return@launch
                if (result.exists) {
                    navigate(result.path, result.gitDetected)
                } else {
                    typed = TypedPath.Missing(result.path, dev)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen != probeGen || host.deviceId != dev) return@launch
                typed = TypedPath.Failed(coreErrorText(e, FolderBrowserCopy.ReadFailed))
            }
        }
    }

    /**
     * **Create and add**: `setSubmitting(true)`, re-prepare with
     * `createIfMissing = true` (always the normalized `Missing.path`, never
     * the raw query), then 111's create body. A failed create keeps the
     * `Missing` state with the error as its text — the status shows it
     * verbatim and Use still reads Create and add, so a retry is one tap.
     */
    private fun createTyped() {
        val m = typed as? TypedPath.Missing ?: return
        if (host.loading || host.submitting) return
        val dev = host.deviceId ?: return
        if (dev != m.deviceId) return
        host.setSubmitting(true)
        scope.launch {
            try {
                val prepared = try {
                    calls.prepareSpacePath(dev, m.path, createIfMissing = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    typed = TypedPath.Missing(m.path, dev, coreErrorText(e, FOLDER_CREATE_FALLBACK))
                    return@launch
                }
                typed = TypedPath.Idle
                query = ""
                host.createProjectAt(prepared.path, prepared.gitDetected)
            } finally {
                host.setSubmitting(false)
            }
        }
    }

    // ----------------------------------------------------------- drives

    /**
     * `ListDrives` in [scope]: any failure (an older engine's `Unsupported`,
     * the 6 s timeout, a dropped client) is swallowed into the Home-only
     * rail, like the web's `.catch(() => drives: [])`; a stale reply (a
     * newer device switch bumped `drivesGen`, or the engine moved on) is
     * dropped without a state change.
     */
    private fun loadDrives(id: String) {
        val gen = ++drivesGen
        scope.launch {
            val loaded = try {
                calls.listDrives(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
            if (gen == drivesGen && host.deviceId == id) drives = loaded
        }
    }
}

/**
 * The sheet body's [FolderNavState]: 110's controller + 111's actions
 * behind the [FolderBrowserHostAdapter], the calls seam, and the screen's
 * `rememberCoroutineScope` — a reply that lands after the screen goes away
 * dies with it, like 110's loads and 111's writes.
 */
@Composable
internal fun rememberFolderNavState(
    controller: FolderBrowserController,
    actions: FolderBrowserActions,
    calls: FolderBrowserCalls,
): FolderNavState {
    val scope = rememberCoroutineScope()
    return remember(controller, actions, calls) {
        FolderNavState(FolderBrowserHostAdapter(controller, actions), calls, scope)
    }
}
