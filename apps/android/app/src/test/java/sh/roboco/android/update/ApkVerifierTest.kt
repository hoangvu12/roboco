package sh.roboco.android.update

import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The signer comparison's pure half (android-next#04): the SHA-256 set
 * of each signer's certificate bytes — equal sets match, a different key
 * does not, and an empty/absent set never matches (an unsigned archive
 * refuses). Robolectric: `Signature` is a real class there (stubbed in
 * plain-JVM tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ApkVerifierTest {

    private fun signature(bytes: ByteArray) = Signature(bytes)

    private val releaseKey = signature("release-certificate-bytes".toByteArray())

    private val otherKey = signature("a-different-certificate".toByteArray())

    @Test
    fun theSameSignerMatches() {
        val a = PackageArchiveVerifier.signerDigests(arrayOf(releaseKey))
        val b = PackageArchiveVerifier.signerDigests(arrayOf(signature("release-certificate-bytes".toByteArray())))
        assertEquals(a, b)
    }

    @Test
    fun aDifferentSignerDoesNotMatch() {
        val a = PackageArchiveVerifier.signerDigests(arrayOf(releaseKey))
        val b = PackageArchiveVerifier.signerDigests(arrayOf(otherKey))
        assertFalse(a == b)
    }

    @Test
    fun aSetOfSignersMustMatchAsASet() {
        val single = PackageArchiveVerifier.signerDigests(arrayOf(releaseKey))
        val pair = PackageArchiveVerifier.signerDigests(arrayOf(releaseKey, otherKey))
        val pairSwapped = PackageArchiveVerifier.signerDigests(arrayOf(otherKey, releaseKey))
        assertFalse(single == pair)
        // Order does not matter — it is a set.
        assertEquals(pair, pairSwapped)
    }

    @Test
    fun anAbsentOrEmptySetIsRefused() {
        assertNull(PackageArchiveVerifier.signerDigests(null))
        assertNull(PackageArchiveVerifier.signerDigests(emptyArray()))
    }

    @Test
    fun theDigestIsTheSha256OfTheCertificateBytes() {
        val digests = PackageArchiveVerifier.signerDigests(arrayOf(releaseKey))
        assertEquals(
            setOf(sha256Hex("release-certificate-bytes".toByteArray())),
            digests,
        )
    }
}
