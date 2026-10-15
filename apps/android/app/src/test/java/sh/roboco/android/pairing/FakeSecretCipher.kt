package sh.roboco.android.pairing

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Test [SecretCipher]: real `AES/GCM/NoPadding` over an in-memory 32-byte
 * [SecureRandom] key, the same blob format (iv(12) ‖ ciphertext ‖ tag(16))
 * and AAD handling as [KeystoreSecretCipher]. The two must stay
 * wire-compatible — the JVM "no plaintext at rest" check runs through this
 * one, so it must not store plaintext anywhere.
 *
 * Knobs: [failEncrypt]/[failDecrypt] throw [GeneralSecurityException] to
 * exercise the store's failure paths; [rotateKey] drops the old key like a
 * Keystore invalidation, so previously written blobs fail their tag check.
 */
class FakeSecretCipher(
    var failEncrypt: Boolean = false,
    var failDecrypt: Boolean = false,
) : SecretCipher {

    private val random = SecureRandom()
    private var key: ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    /** A fresh random key; old blobs then fail with [AEADBadTagException]. */
    fun rotateKey() {
        key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        if (failEncrypt) throw GeneralSecurityException("encrypt disabled")
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val iv = iv.copyOf()
            updateAAD(aad)
            iv + doFinal(plaintext)
        }
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (failDecrypt) throw GeneralSecurityException("decrypt disabled")
        if (blob.size < IV_BYTES + TAG_BYTES) throw AEADBadTagException("short blob")
        return Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
            )
            updateAAD(aad)
            doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
    }
}
