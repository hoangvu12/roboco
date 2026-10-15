package sh.roboco.android.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.security.MessageDigest

/**
 * The signer gate (android-next#04): the APK being installed must be
 * signed by the **same key as the running app** —
 * `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`
 * versus our own package — because Android refuses to install an update
 * signed differently (and a mismatch also catches a release APK being
 * installed over a debug/benchmark build, the ticket's behaviour 6).
 *
 * The comparison is over the SHA-256 of each signer's certificate bytes,
 * as sets: the same set means the same signer(s). Lineage/rotation
 * (past signing certificates) is intentionally **not** honoured — Roboco
 * has one release key with no rotation, and a strict comparison is the
 * safe default for an install-over.
 */
interface ApkVerifier {

    /** Verifies the APK at [apkPath] against our own package's signer. */
    fun verify(apkPath: String): Result

    /** The verdict. */
    sealed interface Result {
        /** The signer matches; the APK may be installed over this app. */
        data object Ok : Result

        /** The APK could not be read or parsed (not an APK, unreadable). */
        data object Unreadable : Result

        /** The APK is signed by a different key than this install. */
        data object ForeignSigner : Result
    }
}

/**
 * The production verifier over `PackageManager` (both lookups use
 * `GET_SIGNING_CERTIFICATES`, API 28+; the app's minSdk is 31).
 */
class PackageArchiveVerifier(
    private val pm: PackageManager,
    private val ownPackageName: String,
) : ApkVerifier {

    override fun verify(apkPath: String): ApkVerifier.Result {
        val own = ownSignerDigests() ?: return ApkVerifier.Result.Unreadable
        val archive: PackageInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return ApkVerifier.Result.Unreadable
        val archiveSigners = signerDigests(archive.signingInfo?.apkContentsSigners)
            ?: return ApkVerifier.Result.Unreadable
        return if (archiveSigners == own) ApkVerifier.Result.Ok else ApkVerifier.Result.ForeignSigner
    }

    private fun ownSignerDigests(): Set<String>? {
        val own: PackageInfo = try {
            pm.getPackageInfo(ownPackageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        return signerDigests(own.signingInfo?.apkContentsSigners)
    }

    companion object {
        /**
         * The SHA-256 set of the [signers]' certificate bytes, or null
         * when there are none (an unsigned package or a failed read).
         */
        fun signerDigests(signers: Array<Signature>?): Set<String>? {
            if (signers == null || signers.isEmpty()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            return signers.mapTo(HashSet()) { signature ->
                digest.digest(signature.toByteArray()).toHex()
            }
        }
    }
}
