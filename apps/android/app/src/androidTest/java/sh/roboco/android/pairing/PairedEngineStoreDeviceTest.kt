package sh.roboco.android.pairing

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.roboco_core.PairedEngine
import java.io.File
import java.util.UUID

/**
 * `PairedEngineStore` against the real Android Keystore (ticket 71,
 * CI-only: no emulator in the worker slots). The lost-key degradation and
 * the no-plaintext-at-rest sweep over the whole data dir.
 *
 * Each test uses its own file under `noBackupFilesDir/device-test`, so
 * the app's own `paired_engines.preferences_pb` DataStore stays the only
 * instance on that file (one DataStore per file per process).
 */
@RunWith(AndroidJUnit4::class)
class PairedEngineStoreDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class DeviceFixture(val store: PairedEngineStore, val scope: CoroutineScope)

    private fun newFixture(): Pair<DeviceFixture, File> {
        val dir = File(context.noBackupFilesDir, "device-test")
        dir.mkdirs()
        val file = File(dir, "store-${UUID.randomUUID()}.preferences_pb")
        return newStoreOver(file) to file
    }

    private fun newStoreOver(file: File): DeviceFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file },
        )
        return DeviceFixture(PairedEngineStore(dataStore, KeystoreSecretCipher(context)), scope)
    }

    /** Releases the file for the next DataStore (one instance per file). */
    private fun closeFixture(fixture: DeviceFixture) {
        val job = fixture.scope.coroutineContext.job
        job.cancel()
        runBlocking { job.join() }
    }

    private fun newEngine(engineId: String, credential: String): PairedEngine = PairedEngine(
        engineId = engineId,
        baseUrl = "http://10.0.2.2:27655",
        credential = credential,
        sessionId = "s1",
        label = "Pixel 9",
        pairedAtMs = 1_760_000_000_000,
        deviceId = null,
        name = null,
    )

    @Test
    fun aLostKeyDegradesAndTheNextPairEncryptsWithAFreshKey() = runBlocking {
        val engineId = "11111111-1111-4111-8111-111111111111"
        val (first, file) = newFixture()
        first.store.upsert(newEngine(engineId, "cred-DEVICE-SECRET"))
        closeFixture(first)

        KeystoreSecretCipher(context).deleteKey()

        val second = newStoreOver(file)
        val degraded = second.store.load()
        assertEquals("a lost key must not crash or vanish the engine", 1, degraded.size)
        assertEquals("", degraded.single().credential)
        assertEquals("http://10.0.2.2:27655", degraded.single().baseUrl)
        assertEquals("s1", degraded.single().sessionId)

        second.store.upsert(degraded.single().copy(credential = "cred-DEVICE-SECRET-2"))
        assertEquals(
            "the next upsert re-encrypts with the fresh key",
            "cred-DEVICE-SECRET-2",
            second.store.load().single().credential,
        )
        closeFixture(second)
    }

    @Test
    fun noCredentialBytesAtRestUnderTheDataDir() = runBlocking {
        val secret = "cred-ATREST-${UUID.randomUUID()}"
        val (fixture, _) = newFixture()
        fixture.store.upsert(newEngine("22222222-2222-4222-8222-222222222222", secret))
        closeFixture(fixture)

        val needle = secret.toByteArray(Charsets.UTF_8)
        val files = context.dataDir.walkBottomUp().filter { it.isFile }.toList()
        assertTrue(files.isNotEmpty())
        for (candidate in files) {
            val bytes = candidate.readBytes()
            assertTrue("$candidate leaks the credential", indexOf(bytes, needle) < 0)
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
