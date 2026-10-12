package sh.roboco.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import sh.roboco.android.core.CoreLoader
import sh.roboco.android.core.CoreStartup
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.core.FontAssets
import sh.roboco.android.pairing.KeystoreSecretCipher
import sh.roboco.android.pairing.PairedEngineStore
import uniffi.roboco_core.CoreClient

/**
 * The Roboco app shell: verifies the bundled fonts, loads the Rust mobile
 * core, then starts the client pipeline (ticket 71) — the stored paired
 * engines load on IO, the single `CoreClient` is built from them, and the
 * splash (ticket 35) holds on `CoreStartState.Loading` until the engine
 * list is known.
 *
 * Property names are reserved for the parity tickets (android-ui-parity):
 * `prefs`, `workspaceFeed`, `foldState`, `frontPage` (#40), `appEvents`
 * (#42), `drafts` (#84) — do not reuse them for something else.
 */
class RobocoApplication : Application() {

    lateinit var core: CoreStatus
        private set

    /** Application-wide scope for state that outlives the activity. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * The startup pipeline. Ticket 35 replaces the `onClientReady`
     * default with `{ lifecycleHooks.clientReady() }`.
     */
    val coreStartup by lazy { CoreStartup(this, engineStore, appScope) }

    override fun onCreate() {
        super.onCreate()
        // #609's Fonts.init swallowed missing faces (review bug 8); a
        // missing font must fail startup loudly, naming the files.
        FontAssets.requireAll(assets)
        core = CoreLoader.load()
        // Tickets 40/42/51 register their event sinks here, before start().
        if (core is CoreStatus.Loaded) {
            coreStartup.start()
        }
    }

    /** The live client, or null before `CoreStartState.Ready`. */
    fun clientOrNull(): CoreClient? = coreStartup.clientOrNull()
}
