package sh.roboco.android.pairing

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.roboco_core.PairedEngine

/**
 * Persistent storage for the phone's paired engines (ticket 71) — Roboco's
 * port of the iOS Keychain block, spread over N engines.
 *
 * Layout: readable metadata as one JSON array under `engines` (pairing
 * order) in a no-backup Preferences DataStore; each credential as
 * `cred:<engineId>` = `Base64(iv(12) ‖ ciphertext ‖ tag(16))`, AES-256-GCM
 * encrypted with a Keystore key through [SecretCipher] and bound to the
 * engine id by AAD. Only the credential is encrypted: a lost key yields
 * `credential == ""` → `Parked{Revoked}` → "Pair again", never a
 * vanished engine and never a crash.
 *
 * All suspend calls run on [io] and are serialized by one mutex, which
 * also covers `pendingPins` and the encrypt-then-edit pair. Metadata is
 * never rewritten on read failures; every write drops orphan `cred:`
 * keys.
 */
class PairedEngineStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()

    /** Ids of the last list this store loaded or wrote; see [has]. */
    @Volatile
    private var knownIds: Set<String> = emptySet()

    /** Pins that raced ahead of [upsert]; memory only, never persisted. */
    private val pendingPins = HashMap<String, PendingPin>()

    private data class PendingPin(val deviceId: String, val name: String?)

    /**
     * The stored engines in pairing order, with decrypted credentials.
     * One unreadable entry degrades to `credential == ""` — the engine
     * stays listed; an unreadable store reads as empty, never throws.
     */
    suspend fun load(): List<PairedEngine> = withContext(io) {
        mutex.withLock {
            val stored = try {
                val prefs = dataStore.data.first()
                decodeMetas(prefs)?.let { metas -> prefs to metas }
            } catch (_: IOException) {
                null
            }
            if (stored == null) {
                Log.w(TAG, "paired engines unreadable")
                knownIds = emptySet()
                return@withLock emptyList<PairedEngine>()
            }
            val (prefs, metas) = stored
            knownIds = metas.mapTo(HashSet()) { it.engineId }
            metas.map { meta ->
                val credential = when (val blob = prefs[credKey(meta.engineId)]) {
                    null -> {
                        Log.w(TAG, "engine credential missing engine=${meta.engineId}")
                        ""
                    }
                    else -> decryptCredential(blob, meta.engineId)
                }
                meta.toPaired(credential)
            }
        }
    }

    /**
     * Whether this engine was persisted *before the current redeem* —
     * ticket 72's re-pair/dedupe check. In-memory only, so it is safe on
     * any thread; `false` until [load] finishes.
     */
    fun has(engineId: String): Boolean = engineId in knownIds

    /**
     * Stores a pairing result (ticket 72, right after `pairEngine`).
     * Replaces an existing record with the same id in place, appends
     * otherwise; merges a pin that raced ahead of this upsert. Encrypts
     * before the atomic edit, so a failure throws
     * [CredentialStoreException] with the store unchanged.
     */
    suspend fun upsert(engine: PairedEngine): Unit = withContext(io) {
        mutex.withLock {
            val blob: ByteArray = try {
                cipher.encrypt(engine.credential.toByteArray(Charsets.UTF_8), aad(engine.engineId))
            } catch (error: Exception) {
                throw CredentialStoreException(error)
            }
            val stored = mergePendingPin(engine)
            val base64 = Base64.getEncoder().encodeToString(blob)
            val meta = stored.meta()
            var writtenIds = knownIds
            try {
                dataStore.edit { prefs ->
                    val metas = decodeCurrent(prefs).toMutableList()
                    val index = metas.indexOfFirst { it.engineId == stored.engineId }
                    if (index >= 0) metas[index] = meta else metas.add(meta)
                    rewrite(prefs, metas)
                    prefs[credKey(stored.engineId)] = base64
                    writtenIds = metas.mapTo(HashSet()) { it.engineId }
                }
            } catch (error: IOException) {
                throw CredentialStoreException(error)
            }
            knownIds = writtenIds
            Log.i(TAG, "engine stored engine=${stored.engineId}")
        }
    }

    /**
     * Persists an `EngineIdentityPinned` event (the `CoreStartup` listener
     * calls this). A pin for an id not yet stored is kept in memory and
     * merged by the next [upsert]; failures are logged, never thrown.
     */
    suspend fun pin(engineId: String, deviceId: String, name: String?): Unit = withContext(io) {
        mutex.withLock {
            try {
                val metas = decodeMetas(dataStore.data.first())
                    ?: run {
                        pendingPins[engineId] = PendingPin(deviceId, name)
                        return@withLock
                    }
                val index = metas.indexOfFirst { it.engineId == engineId }
                if (index < 0) {
                    pendingPins[engineId] = PendingPin(deviceId, name)
                    return@withLock
                }
                val old = metas[index]
                val updated = old.copy(
                    deviceId = deviceId,
                    name = if (name.isNullOrBlank()) old.name else name,
                )
                if (updated == old) return@withLock
                dataStore.edit { prefs ->
                    val current = decodeCurrent(prefs)
                    val at = current.indexOfFirst { it.engineId == engineId }
                    if (at >= 0) {
                        val next = current.toMutableList()
                        next[at] = updated
                        rewrite(prefs, next)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "engine pin not saved engine=$engineId cause=${error.javaClass.simpleName}")
            }
        }
    }

    /**
     * Drops an engine after `forgetEngine` (ticket 140): the metadata
     * element, its credential and any pending pin. Unknown id = no-op.
     * IO failure throws [CredentialStoreException].
     */
    suspend fun remove(engineId: String): Unit = withContext(io) {
        mutex.withLock {
            pendingPins.remove(engineId)
            var writtenIds: Set<String>? = null
            try {
                dataStore.edit { prefs ->
                    val current = decodeCurrent(prefs)
                    if (current.none { it.engineId == engineId }) return@edit
                    val metas = current.filterNot { it.engineId == engineId }
                    rewrite(prefs, metas)
                    writtenIds = metas.mapTo(HashSet()) { it.engineId }
                }
            } catch (error: IOException) {
                throw CredentialStoreException(error)
            }
            // A captured var can't be smart-cast; copy it to a val first.
            val newIds = writtenIds
            if (newIds != null) {
                knownIds = newIds
            }
        }
    }

    /** Resets the store (tests; the key is not deleted — re-pair recovers). */
    @VisibleForTesting
    suspend fun clearAll(): Unit = withContext(io) {
        mutex.withLock {
            dataStore.edit { it.clear() }
            pendingPins.clear()
            knownIds = emptySet()
        }
    }

    /** A pinned identity merges into a pairing result that has none yet;
     * a blank pinned name counts as unknown, like everywhere else. */
    private fun mergePendingPin(engine: PairedEngine): PairedEngine {
        if (engine.deviceId != null) return engine
        val pending = pendingPins.remove(engine.engineId) ?: return engine
        return engine.copy(
            deviceId = pending.deviceId,
            name = engine.name ?: pending.name?.takeIf { it.isNotBlank() },
        )
    }

    private fun decryptCredential(base64: String, engineId: String): String =
        try {
            val blob = Base64.getDecoder().decode(base64)
            String(cipher.decrypt(blob, aad(engineId)), Charsets.UTF_8)
        } catch (error: Exception) {
            Log.w(TAG, "engine credential unreadable engine=$engineId cause=${error.javaClass.simpleName}")
            ""
        }

    /** Writes the metadata list and drops `cred:` keys with no entry left. */
    private fun rewrite(prefs: MutablePreferences, metas: List<EngineMeta>) {
        prefs[ENGINES] = EngineMetaJson.encode(metas)
        val liveIds = metas.mapTo(HashSet()) { it.engineId }
        for (name in prefs.asMap().keys.map { it.name }) {
            if (name.startsWith(CRED_PREFIX) && name.substring(CRED_PREFIX.length) !in liveIds) {
                prefs.remove(stringPreferencesKey(name))
            }
        }
    }

    /** Absent = no engines; `null` = present but unreadable. */
    private fun decodeMetas(prefs: Preferences): List<EngineMeta>? {
        val text = prefs[ENGINES] ?: return emptyList()
        return EngineMetaJson.decode(text)
    }

    /** The stored list for edits: absent or unreadable counts as empty. */
    private fun decodeCurrent(prefs: Preferences): List<EngineMeta> =
        decodeMetas(prefs) ?: emptyList()

    private fun credKey(engineId: String) = stringPreferencesKey("$CRED_PREFIX$engineId")

    /** Must stay equal to `KeystoreSecretCipher.ALIAS` (the ticket reuses the alias here). */
    private fun aad(engineId: String): ByteArray =
        "roboco.engine-credentials.v1:$engineId".toByteArray(Charsets.UTF_8)

    companion object {
        /** Required by `PreferenceDataStoreFactory`. */
        const val FILE_NAME = "paired_engines.preferences_pb"

        private const val TAG = "Roboco"
        private const val CRED_PREFIX = "cred:"
        private val ENGINES = stringPreferencesKey("engines")

        /**
         * The process's single DataStore for [FILE_NAME] (a second instance
         * on the same file throws). Create once in `RobocoApplication`.
         */
        fun createDataStore(context: Context, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler {
                    Log.w(TAG, "paired engines store corrupt; reset")
                    emptyPreferences()
                },
                scope = scope,
                produceFile = { File(context.noBackupFilesDir, FILE_NAME) },
            )
    }
}
