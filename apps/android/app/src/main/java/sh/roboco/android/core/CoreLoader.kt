package sh.roboco.android.core

import android.util.Log
import sh.roboco.android.BuildConfig

/**
 * The result of loading the core; the placeholder (and later the shell)
 * renders it.
 */
sealed interface CoreStatus {
    data class Loaded(val version: String) : CoreStatus
    data class Failed(val reason: String) : CoreStatus
}

/**
 * Loads the Rust mobile core (`libroboco_mobile.so` via UniFFI/JNA) and
 * checks the version handshake.
 */
object CoreLoader {

    private const val TAG = "Roboco"

    fun load(): CoreStatus {
        return try {
            val version = uniffi.roboco_core.coreVersion()
            if (version != BuildConfig.VERSION_NAME) {
                Log.e(TAG, "core $version does not match app ${BuildConfig.VERSION_NAME}")
                CoreStatus.Failed("core $version does not match app ${BuildConfig.VERSION_NAME}")
            } else {
                Log.i(TAG, "core $version loaded")
                CoreStatus.Loaded(version)
            }
        } catch (error: Throwable) {
            // UnsatisfiedLinkError (stale jniLibs — rebuild without
            // -ProbocoSkipCore) or a UniFFI checksum mismatch land here.
            Log.e(TAG, "could not load the Roboco core", error)
            CoreStatus.Failed(error.message ?: error.javaClass.simpleName)
        }
    }
}
