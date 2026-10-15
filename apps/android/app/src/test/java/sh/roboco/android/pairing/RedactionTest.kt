package sh.roboco.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The only debug-safe rendering of a [uniffi.roboco_core.PairedEngine]:
 * the generated `toString()` prints the credential, so every print must
 * go through [redacted] (ticket 71's redacted-logging rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RedactionTest {

    @Test
    fun redactedNeverContainsTheCredential() {
        val engine = newEngine(credential = "cred-hush")

        val text = engine.redacted()

        assertFalse(text.contains("cred-hush"))
        assertTrue(text.contains("credential=<redacted 9 chars>"))
    }

    @Test
    fun redactedNamesTheEngineHostAndPin() {
        val engine = newEngine(
            engineId = "11111111-1111-4111-8111-111111111111",
            baseUrl = "http://10.0.2.2:27655",
            deviceId = "dev-1",
        )

        val text = engine.redacted()

        assertTrue(text.contains("engineId=11111111-1111-4111-8111-111111111111"))
        assertTrue(text.contains("host=10.0.2.2"))
        assertTrue(text.contains("deviceId=dev-1"))
    }

    @Test
    fun aTailcatEngineShowsOnlyItsFingerprint() {
        val engine = newTailcatEngine()

        val text = engine.redacted()

        assertFalse(text.contains("SECRET"))
        assertTrue(text.contains("host=tailcat:3f9a12bc"))
        assertTrue(text.contains("transport=tailcat"))
        assertEquals("Tailcat \u00B7 3f9a12bc", tailcatAddressLabel(engine.baseUrl))
        assertNull(tailcatAddressLabel("http://10.0.2.2:27655"))
    }

    @Test
    fun anUnparseableBaseUrlsHostIsAQuestionMark() {
        val engine = newEngine(baseUrl = "not a url")

        assertTrue(engine.redacted().contains("host=?"))
    }
}
