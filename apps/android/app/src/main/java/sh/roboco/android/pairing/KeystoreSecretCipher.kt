package sh.roboco.android.pairing

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretCipher] over a non-exportable Android Keystore AES-256-GCM key —
 * Roboco's Keychain equivalent (iOS `AppModel.swift`'s `enum Keychain`).
 *
 * The key is created lazily on the first **encrypt**, never on decrypt:
 * a missing key on decrypt is a decrypt failure, so a lost key degrades
 * to "pair again" (empty credential → `Parked{Revoked}`) instead of
 * rewriting history. No user-auth binding is set — like iOS's
 * `kSecAttrAccessibleAfterFirstUnlock`, credentials stay readable after
 * first unlock, which a background start needs.
 *
 * `key.encoded == null` by construction, so the key never leaves the
 * Keystore and a backed-up blob is undecryptable anywhere else.
 */
class KeystoreSecretCipher(private val context: Context) : SecretCipher {

    companion object {
        /** Also the AAD prefix — one constant, no new naming surface. */
        const val ALIAS = "roboco.engine-credentials.v1"
        private const val TAG = "Roboco"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = 128
    }

    private val strongBoxAvailable: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val key = getOrCreateKey()
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, key)
            val iv = this.iv
            if (iv == null || iv.size != IV_BYTES) {
                throw GeneralSecurityException("unexpected iv")
            }
            updateAAD(aad)
            val ciphertext = doFinal(plaintext)
            iv + ciphertext
        }
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < IV_BYTES + TAG_BYTES) throw AEADBadTagException("short blob")
        val key = existingKey() ?: throw InvalidKeyException("credential key missing")
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            updateAAD(aad)
            doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }
    }

    /** Drops the key (the recovery path is re-pairing). Tests only. */
    @VisibleForTesting
    fun deleteKey() {
        keyStore().deleteEntry(ALIAS)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        if (strongBoxAvailable) {
            try {
                return generateKey(strongBox = true).also {
                    Log.i(TAG, "credential key created strongbox=true")
                }
            } catch (_: StrongBoxUnavailableException) {
                deleteKeyQuietly()
            } catch (_: ProviderException) {
                // Some OEM builds report StrongBox absence this way.
                deleteKeyQuietly()
            }
        }
        return generateKey(strongBox = false).also {
            Log.i(TAG, "credential key created strongbox=false")
        }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (strongBox) {
            spec.setIsStrongBoxBacked(true)
        }
        generator.init(spec.build())
        return generator.generateKey()
    }

    private fun existingKey(): SecretKey? =
        keyStore().getKey(ALIAS, null) as? SecretKey

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun deleteKeyQuietly() {
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (_: Exception) {
            // A half-registered StrongBox alias is no reason to fail pairing.
        }
    }
}
