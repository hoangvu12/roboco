package sh.roboco.android.pairing

/**
 * Authenticated encryption for small secrets, so the production
 * implementation and the test fake stay wire-compatible. Blob format:
 * `iv(12) ‖ ciphertext ‖ tag(16)` — exactly what [KeystoreSecretCipher]
 * writes and [decrypt] accepts.
 *
 * The `aad` binds a blob to its engine id, so a `cred:` value copied to
 * another engine fails its tag check instead of decrypting.
 */
interface SecretCipher {

    /**
     * Encrypts `plaintext` bound to `aad`. The implementation generates a
     * fresh random IV per call; encrypting twice yields different blobs.
     */
    @Throws(java.security.GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray

    /**
     * Decrypts a [encrypt]-shaped blob; any tamper, truncation, key loss
     * or AAD mismatch throws. Never creates a key.
     */
    @Throws(java.security.GeneralSecurityException::class)
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * The store could not write its state. The edit is atomic, so the store
 * is unchanged — callers (ticket 72) treat this as "couldn't save the
 * credential on this phone".
 */
class CredentialStoreException(cause: Throwable) : Exception("credential store write failed", cause)
