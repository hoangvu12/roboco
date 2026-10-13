package sh.roboco.android

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaged chimes (android-app#28): the three raw resources the
 * syncChimes Gradle task stages are byte-identical to the desktop chimes
 * under crates/ui/assets/sounds (done, request, attention) — the channels
 * play exactly the bytes the desktop plays. Plain JVM over the roboco.*
 * system properties the build sets (the GeneratedAssetsTest pattern).
 */
class ChimeBytesTest {

    private val repoRoot = File(System.getProperty("roboco.repoRoot"))
    private val generatedChimes = File(System.getProperty("roboco.generatedChimes"))

    /** The ticket's SHA-256 pins (byte-for-byte from the desktop). */
    private val expectedSha256 = mapOf(
        "chime_done" to "7939ec94ace85a0f676acd64a8279e38bea97ecf1ace2d301620e5e952850e49",
        "chime_request" to "b118dc358feff7378479cf395d6dc8420798a5749ad3692ecca18ffdb209f0ee",
        "chime_attention" to "dee15160d42948205ac9d3c2e9512e856a3d4f1e769355c0433adfb27366eb54",
    )

    @Test
    fun theThreeChimesAreStagedAndMatchTheDesktopByteForByte() {
        for ((name, sha) in expectedSha256) {
            val generated = generatedChimes.resolve("raw/$name.wav")
            assertTrue("$name missing from the generated res", generated.isFile)
            val bytes = generated.readBytes()
            // The pinned digest…
            assertEquals("$name differs from the desktop chime", sha, sha256(bytes))
            // …and the actual source file, both at once.
            val source = repoRoot.resolve("crates/ui/assets/sounds/${name.removePrefix("chime_")}.wav")
            assertArrayEquals("$name is not byte-identical to $source", source.readBytes(), bytes)
            // A WAV, and long enough to be a real chime (0.52–0.65 s at
            // 48 kHz 16-bit stereo ≈ 50–62 kB).
            assertTrue("$name is too short", bytes.size > 50_000)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            // String(bytes, offset, length): bytes 8..11.
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        }
    }

    @Test
    fun appshotNeverRidesAlong() {
        // #699 committed mastered copies; Roboco does not re-master, and
        // the appshot cue is not a session chime.
        val raw = generatedChimes.resolve("raw")
        assertTrue(raw.isDirectory)
        assertEquals(
            "expected exactly the three session chimes",
            setOf("chime_done.wav", "chime_request.wav", "chime_attention.wav"),
            raw.listFiles()!!.map { it.name }.toSet(),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
