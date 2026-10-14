package sh.roboco.android.composer

import android.content.Context
import android.util.Log
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The failure sink of the draft store (android-ui-parity #84): production
 * logs `Log.w("Roboco", …)`. Plain-JVM tests swap this — the stub
 * `android.util.Log` throws "not mocked" outside Robolectric (the same
 * seam `sessionLogW` provides for 51). Every line is content-free: never
 * draft text, never a draft key.
 */
internal var draftsLogW: (String, Throwable?) -> Unit = { message, error ->
    if (error == null) Log.w("Roboco", message) else Log.w("Roboco", message, error)
}

/**
 * The draft-store key space (android-ui-parity #84). iOS keys its one
 * `UserDefaults` "drafts" dictionary by raw chat id
 * (`ios:Session/SessionViewController.swift:668-682`); Roboco chat ids are
 * only unique per engine, so a chat key carries the engine id too — which
 * also lets [Drafts.forgetEngine] delete by prefix without ever decoding
 * the scoped id (that format belongs to Rust,
 * `crates/ui/src/engine_registry.rs`). The new-session key keeps the iOS
 * literal (`AppModel.newSessionDraftKey`).
 */
sealed interface DraftKey {
    /** The storage name — one DataStore entry per draft. */
    val storageName: String

    /**
     * One chat's composer draft. [engineId] is the client-minted engine id
     * (`PairedEngine.engineId`, a UUID v4 — never contains `:`);
     * [chatId] is the **scoped** chat id exactly as
     * `SessionHandle.chatId()` returns it (`engine:v1:…`).
     */
    data class Chat(val engineId: String, val chatId: String) : DraftKey {
        override val storageName: String get() = "chat:$engineId:$chatId"
    }

    /**
     * The new-session page's draft, under the iOS literal `"new-session"`.
     * Ticket 92 owns its read/write moments; this ticket only hosts the
     * key.
     */
    data object NewSession : DraftKey {
        override val storageName: String = "new-session"
    }
}

/**
 * Swift `text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty` —
 * the **emptiness test** of `Drafts.save` (the text itself is never
 * trimmed): true when every UTF-16 unit of [text] is in Swift's
 * `CharacterSet.whitespacesAndNewlines`, i.e. U+0009–U+000D, U+0085, or
 * a Unicode separator category (Zs, Zl, Zp). All members are in the BMP,
 * so a per-UTF-16-unit check equals Swift's per-scalar check (a surrogate
 * is never blank).
 *
 * **Not** Kotlin's `isBlank()`/`isWhitespace()`: those treat U+001C–U+001F
 * as whitespace (Swift does not) and miss U+0085.
 */
fun isDraftBlank(text: String): Boolean = text.all { c ->
    c in '\u0009'..'\u000D' ||
        c == '\u0085' ||
        Character.getType(c).let { t ->
            t == Character.SPACE_SEPARATOR.toInt() ||
                t == Character.LINE_SEPARATOR.toInt() ||
                t == Character.PARAGRAPH_SEPARATOR.toInt()
        }
}

/**
 * The phone-local draft store (android-ui-parity #84) — the port of iOS's
 * `Drafts` enum (`ios:Session/SessionViewController.swift:668-682`):
 * unsent composer text per chat, one dictionary keyed by chat, where a
 * blank (Swift-blank) save **removes** the entry (no empty tombstones)
 * and a kept save stores the text **verbatim, untrimmed** (`"  hi\n"`
 * stays `"  hi\n"`). Entries are never pruned — archive and delete do not
 * touch them; the only bulk deletion is [forgetEngine].
 *
 * Roboco adaptations (the ticket's ADAPT lines): the `UserDefaults`
 * dictionary becomes one DataStore entry per draft in
 * `noBackupFilesDir` (drafts reference engine ids and chat ids that are
 * themselves not backed up — a restored backup would only hold orphans);
 * the synchronous `UserDefaults` read becomes an in-memory mirror warmed
 * at app start, with [peek] for synchronous use, [load] (suspending) as
 * the fallback, and the pending-load guard in [SessionDraftSaver].
 *
 * Threading: `save`, `peek`, `forgetEngine` and `warm` are called on the
 * main thread and never block it — [save] updates the mirror
 * synchronously (a `peek` right after it sees the new text: pop, then
 * immediately reopen the same chat) and launches the disk write on
 * [scope], which must outlive every screen
 * (`RobocoApplication.appScope`, never a screen or nav-entry scope —
 * those cancel exactly when the leave and dispose saves fire). Each
 * write job, inside `store.edit`, writes the mirror's **current** value
 * for its key (absent → remove), so writes are order-insensitive: the
 * last mirror state always wins.
 *
 * Before the first disk read finishes, mutations are applied to the
 * pre-load mirror **and** queued; the load replays them on top of the
 * disk map, so a save issued before the load lands still survives it.
 * Writes `awaitLoaded` first. An unreadable first read is logged and
 * treated as an empty store; a failed write is logged content-free.
 */
class Drafts(
    private val store: DataStore<Preferences>,
    /** The process-lifetime scope (`RobocoApplication.appScope`) — outlives every screen. */
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()

    /** storageName → text, the synchronous read behind [peek]. */
    private var mirror = HashMap<String, String>()

    /** Mutations queued while the first disk read is in flight; replayed on the disk map. */
    private val beforeLoad = ArrayList<(MutableMap<String, String>) -> Unit>()

    /** Engine ids whose drafts were forgotten — later chat saves for them are ignored (this process). */
    private val forgotten = HashSet<String>()

    @Volatile
    private var loaded = false

    private val loadedSignal = CompletableDeferred<Unit>()

    private val warmStarted = AtomicBoolean(false)

    /** True once the first disk read finished (the mirror then holds the whole store). */
    val isLoaded: Boolean
        get() = loaded

    /**
     * Starts the first disk read (idempotent; called once from
     * `RobocoApplication.onCreate`, which always precedes any activity's
     * `setContent`). The read runs on [scope] with [io]; a failure is
     * logged and treated as an empty store, and the store is marked
     * loaded either way.
     */
    fun warm() {
        if (!warmStarted.compareAndSet(false, true)) return
        scope.launch(io) {
            val disk: HashMap<String, String> = try {
                val prefs = store.data.first()
                HashMap<String, String>().apply {
                    for ((k, v) in prefs.asMap()) {
                        if (v is String) put(k.name, v)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // IOException and anything else the first read can throw
                // (the corruption handler already reset the file): an
                // empty store, marked loaded — never a crash from the
                // app's scope.
                draftsLogW("drafts load failed", t)
                HashMap()
            }
            synchronized(lock) {
                for (op in beforeLoad) op(disk)
                beforeLoad.clear()
                mirror = disk
                loaded = true
            }
            loadedSignal.complete(Unit)
        }
    }

    /** Suspends until the first disk read finished (starts it if nobody has). */
    suspend fun awaitLoaded() {
        warm()
        loadedSignal.await()
    }

    /**
     * Synchronous read from the mirror; `""` when absent or not loaded
     * yet (the latter is the pending-load case: seed `""`, then
     * [SessionDraftEffect] applies the late load). Main thread.
     */
    fun peek(key: DraftKey): String = synchronized(lock) { mirror[key.storageName] } ?: ""

    /** [awaitLoaded], then [peek] — the late-load fallback. */
    suspend fun load(key: DraftKey): String {
        awaitLoaded()
        return peek(key)
    }

    /**
     * iOS `Drafts.save(chatId, text)`: blank (Swift-blank) removes the
     * entry, otherwise the text is stored verbatim. The mirror is updated
     * synchronously; the disk write runs on [scope] and returns its
     * [Job] (tests join it). A `Chat` key whose engine was forgotten is
     * ignored — a session still on a back stack must not re-create an
     * orphan key on its next leave.
     */
    fun save(key: DraftKey, text: String): Job {
        val name = key.storageName
        if (key is DraftKey.Chat && synchronized(lock) { key.engineId in forgotten }) {
            return Job().apply { complete() }
        }
        mutate { m -> if (isDraftBlank(text)) m.remove(name) else m[name] = text }
        return scope.launch(io) {
            awaitLoaded()
            try {
                store.edit { p ->
                    // The mirror's CURRENT value, whatever raced in since
                    // this job was launched — order-insensitive writes.
                    val v = synchronized(lock) { mirror[name] }
                    val k = stringPreferencesKey(name)
                    if (v == null) p.remove(k) else p[k] = v
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Content-free on purpose: never draft text or keys.
                draftsLogW("draft write failed", null)
            }
        }
    }

    /**
     * Deletes every `chat:<engineId>:*` draft from the mirror and the
     * disk (ticket 140's forget sequence calls this between `removeStored`
     * and `requestPop`), and makes every later `Chat` save for that engine
     * a no-op for the rest of the process. The `new-session` key and other
     * engines' drafts are untouched.
     */
    fun forgetEngine(engineId: String): Job {
        synchronized(lock) { forgotten.add(engineId) }
        val prefix = "chat:$engineId:"
        mutate { m -> m.keys.removeAll { it.startsWith(prefix) } }
        return scope.launch(io) {
            awaitLoaded()
            try {
                store.edit { p ->
                    for (name in p.asMap().keys.map { it.name }) {
                        if (name.startsWith(prefix)) p.remove(stringPreferencesKey(name))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                draftsLogW("draft write failed", null)
            }
        }
    }

    /**
     * One mirror mutation, under [lock]: before the load it is applied to
     * the pre-load mirror (a `peek` right after a `save` is already
     * correct) **and** recorded, so the load replays it on top of the disk
     * map; after the load it is the only application.
     */
    private fun mutate(op: (MutableMap<String, String>) -> Unit) {
        synchronized(lock) {
            op(mirror)
            if (!loaded) beforeLoad.add(op)
        }
    }

    companion object {
        /** Required by `PreferenceDataStoreFactory` (see [createDataStore]). */
        const val FILE_NAME = "drafts.preferences_pb"

        /**
         * The process's single DataStore for [FILE_NAME] (a second active
         * instance on the same file throws). Create once, in
         * `RobocoApplication` — the same shape as ticket 71's
         * `paired_engines.preferences_pb`.
         */
        fun createDataStore(context: Context, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler {
                    draftsLogW("drafts store corrupt; reset", null)
                    emptyPreferences()
                },
                scope = scope,
                produceFile = { File(context.noBackupFilesDir, FILE_NAME) },
            )
    }
}

/**
 * The draft store for composition. Production: `MainActivity` provides
 * `RobocoApplication.drafts`. The default is an in-memory store (no file,
 * nothing persists) so previews, Robolectric hosts without the app and
 * the gallery need no setup — 92's new-session moments read it the same
 * way.
 */
val LocalDrafts: ProvidableCompositionLocal<Drafts> = staticCompositionLocalOf {
    Drafts(InMemoryPreferencesStore(), CoroutineScope(Dispatchers.Unconfined))
}

/**
 * The [LocalDrafts] default: a tiny in-memory `DataStore<Preferences>` —
 * a `MutableStateFlow` as [data], whose [updateData] applies the
 * transform and sets the flow. Never touches a file.
 */
private class InMemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}
