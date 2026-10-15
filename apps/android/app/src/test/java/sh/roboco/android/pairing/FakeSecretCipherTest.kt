package sh.roboco.android.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/**
 * The [SecretCipher] contract, exercised through the JVM fake (no Android
 * APIs, so this is a plain-JVM test the merge check runs directly). The
 * blob shape, AAD binding and failure semantics here are the ones
 * [KeystoreSecretCipher] must satisfy on a device — its round-trip is the
 * instrumented `KeystoreSecretCipherTest` (CI-only).
 */
class FakeSecretCipherTest {

    private val engineId = "11111111-1111-4111-8111-111111111111"
    private val aad = "roboco.engine-credentials.v1:$engineId".toByteArray(Charsets.UTF_8)

    @Test
    fun roundTripsUtf8ThroughTheBlobFormat() {
        val cipher = FakeSecretCipher()
        val plaintext = "cred-sécrèt-π".toByteArray(Charsets.UTF_8)

        val blob = cipher.encrypt(plaintext, aad)

        assertEquals(12 + plaintext.size + 16, blob.size)
        assertArrayEquals(plaintext, cipher.decrypt(blob, aad))
    }

    @Test
    fun twoEncryptionsOfTheSamePlaintextDiffer() {
        val cipher = FakeSecretCipher()
        val plaintext = "cred-same".toByteArray(Charsets.UTF_8)

        val first = cipher.encrypt(plaintext, aad)
        val second = cipher.encrypt(plaintext, aad)

        assertFalse(first.contentEquals(second))
        assertArrayEquals(plaintext, cipher.decrypt(first, aad))
        assertArrayEquals(plaintext, cipher.decrypt(second, aad))
    }

    @Test
    fun aBlobForOneEngineFailsAnotherEnginesAad() {
        val cipher = FakeSecretCipher()
        val otherAad = "roboco.engine-credentials.v1:22222222-2222-4222-8222-222222222222"
            .toByteArray(Charsets.UTF_8)
        val blob = cipher.encrypt("cred-swapped".toByteArray(Charsets.UTF_8), aad)

        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(blob, otherAad) }
    }

    @Test
    fun rotatingTheKeyFailsOldBlobsButNotNewOnes() {
        val cipher = FakeSecretCipher()
        val blob = cipher.encrypt("cred-old".toByteArray(Charsets.UTF_8), aad)

        cipher.rotateKey()

        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(blob, aad) }
        val fresh = cipher.encrypt("cred-new".toByteArray(Charsets.UTF_8), aad)
        assertArrayEquals(
            "cred-new".toByteArray(Charsets.UTF_8),
            cipher.decrypt(fresh, aad),
        )
    }

    @Test
    fun blobsShorterThanIvPlusTagAreRejectedBeforeDecrypting() {
        val cipher = FakeSecretCipher()

        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(ByteArray(27), aad) }
    }

    @Test
    fun failureKnobsThrowGeneralSecurityException() {
        val cipher = FakeSecretCipher(failEncrypt = true)

        assertThrows(GeneralSecurityException::class.java) {
            cipher.encrypt("cred-x".toByteArray(Charsets.UTF_8), aad)
        }

        val failingDecrypt = FakeSecretCipher(failDecrypt = true)
        val blob = FakeSecretCipher().encrypt("cred-x".toByteArray(Charsets.UTF_8), aad)
        assertThrows(GeneralSecurityException::class.java) { failingDecrypt.decrypt(blob, aad) }
    }

    @Test
    fun encryptionsDifferBetweenCipherInstances() {
        val blobA = FakeSecretCipher().encrypt("cred-x".toByteArray(Charsets.UTF_8), aad)
        val blobB = FakeSecretCipher().encrypt("cred-x".toByteArray(Charsets.UTF_8), aad)

        assertNotEquals(blobA.toList(), blobB.toList())
    }
}
