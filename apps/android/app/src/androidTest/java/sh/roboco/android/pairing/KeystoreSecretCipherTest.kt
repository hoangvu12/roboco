package sh.roboco.android.pairing

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/**
 * `KeystoreSecretCipher` on real hardware (ticket 71, CI-only: no
 * emulator in the worker slots). Verifies the [SecretCipher] contract
 * against the real `AndroidKeyStore`: blob shape, non-exportability,
 * AAD binding, and the never-create-a-key-on-decrypt rule.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretCipherTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val aad: ByteArray
        get() = "roboco.engine-credentials.v1:11111111-1111-4111-8111-111111111111"
            .toByteArray(Charsets.UTF_8)

    private lateinit var cipher: KeystoreSecretCipher

    @Before
    fun setUp() {
        cipher = KeystoreSecretCipher(context)
        cipher.deleteKey()
    }

    @Test
    fun roundTripsThroughTheKeystoreWithTheBlobShape() {
        val plaintext = "cred-on-device".toByteArray(Charsets.UTF_8)

        val blob = cipher.encrypt(plaintext, aad)
        val again = cipher.encrypt(plaintext, aad)

        assertEquals(12 + plaintext.size + 16, blob.size)
        assertArrayEquals(plaintext, cipher.decrypt(blob, aad))
        assertFalse("randomized encryption must change the blob", blob.contentEquals(again))
        assertArrayEquals(plaintext, cipher.decrypt(again, aad))
    }

    @Test
    fun theFirstEncryptCreatesANonExportableKey() {
        assertNull("precondition: no key before the first encrypt", keystoreKey())

        cipher.encrypt("cred-x".toByteArray(Charsets.UTF_8), aad)

        val key = keystoreKey()
        assertNotNull("the alias must exist after the first encrypt", key)
        assertNull("the Keystore key must not be exportable", key?.encoded)
    }

    @Test
    fun aBlobBoundToOneEngineFailsAnotherEnginesAad() {
        val blob = cipher.encrypt("cred-x".toByteArray(Charsets.UTF_8), aad)
        val otherAad = "roboco.engine-credentials.v1:22222222-2222-4222-8222-222222222222"
            .toByteArray(Charsets.UTF_8)

        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(blob, otherAad) }
    }

    @Test
    fun shortBlobsAreRejected() {
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(ByteArray(27), aad) }
    }

    @Test
    fun decryptNeverCreatesAKey() {
        cipher.deleteKey()
        val blob = "0000000000000000000000000000000000000000".toByteArray(Charsets.UTF_8)

        assertThrows(InvalidKeyException::class.java) { cipher.decrypt(blob, aad) }

        assertNull("a failed decrypt must not create a key", keystoreKey())
    }

    private fun keystoreKey(): java.security.Key? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey(KeystoreSecretCipher.ALIAS, null)?.let { key ->
                assertTrue("expected a SecretKey", key is SecretKey)
                key
            }
}
