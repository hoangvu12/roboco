package ai.roboco.tailcat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import tailcatnative.Client
import tailcatnative.Tailcatnative

/** Tailcat carries bytes; Roboco's engine pairing credential authenticates RPC. */
class RobocoTailcatModule : Module() {
  private var client: Client? = null
  private val lock = Any()
  private val keyAlias = "roboco-engine-connection"

  override fun definition() = ModuleDefinition {
    Name("RobocoTailcat")

    AsyncFunction("openRoute") { address: String, derpMap: String ->
      synchronized(lock) {
        client?.close()
        client = null
        val directory = requireNotNull(appContext.reactContext).noBackupFilesDir.resolve("tailcat")
        check(directory.exists() || directory.mkdirs()) { "Could not create transport state" }
        val next = Tailcatnative.startClient(address, directory.absolutePath, derpMap)
        client = next
        next.url()
      }
    }

    AsyncFunction("loadConnection") {
      synchronized(lock) {
        preferences().getString("connection", null)?.let { stored ->
          val parts = stored.split(":")
          require(parts.size == 2) { "Saved connection is damaged; forget it and pair again" }
          val cipher = Cipher.getInstance("AES/GCM/NoPadding")
          cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(parts[0])))
          String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
        }
      }
    }

    AsyncFunction("saveConnection") { json: String ->
      synchronized(lock) {
        require(json.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Connection is too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val stored = encode(cipher.iv) + ":" + encode(cipher.doFinal(json.toByteArray(Charsets.UTF_8)))
        check(preferences().edit().putString("connection", stored).commit()) { "Could not save connection" }
      }
    }

    AsyncFunction("forgetConnection") {
      synchronized(lock) {
        check(preferences().edit().remove("connection").commit()) { "Could not forget connection" }
      }
    }

    Function("disconnect") { synchronized(lock) { client?.close(); client = null } }
    OnDestroy { synchronized(lock) { client?.close(); client = null } }
  }

  private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build())
    }.generateKey()
  }

  private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
  private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
  private fun preferences() = requireNotNull(appContext.reactContext)
    .getSharedPreferences("roboco-connection", android.content.Context.MODE_PRIVATE)
}
