package sh.roboco.android.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * The fail-loud font gate (plain JVM, for the merge check): every path is
 * checked in table order, a missing/empty/short/wrong-magic asset is bad,
 * debug builds throw naming every bad path, release builds store
 * [FontStatus.Missing] and log the same message.
 *
 * The log sink is injected (the production default is `Log.e`), because
 * `android.util.Log` is not mocked in plain-JVM unit tests.
 */
class FontGateTest {

    /** TrueType magic: every bundled face starts `00 01 00 00`. */
    private val magic = byteArrayOf(0, 1, 0, 0)

    private val logs = mutableListOf<String>()

    /** An opener that serves good streams for every face. */
    private fun allGood(): (String) -> InputStream = { ByteArrayInputStream(magic + ByteArray(16)) }

    @After
    fun resetStatus() {
        FontGate.check(allGood(), debug = false, log = { })
    }

    @Test
    fun allFacesPresentReturnsOk() {
        val status = FontGate.check(allGood(), debug = true, log = logs::add)
        assertSame(FontStatus.Ok, status)
        assertSame(FontStatus.Ok, FontGate.status)
        assertTrue("no log for a healthy build", logs.isEmpty())
    }

    @Test
    fun debugBuildThrowsNamingTheSingleMissingFile() {
        val open: (String) -> InputStream = { path ->
            if (path == "fonts/GeistMono-Italic.ttf") throw FileNotFoundException(path)
            ByteArrayInputStream(magic)
        }
        try {
            FontGate.check(open, debug = true, log = logs::add)
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(
                "Missing font asset(s): fonts/GeistMono-Italic.ttf",
                expected.message,
            )
        }
    }

    @Test
    fun debugBuildListsEveryMissingPathInTableOrder() {
        val open: (String) -> InputStream = { path ->
            when (path) {
                "fonts/GeistMono.ttf", "fonts/Geist-Bold.ttf" -> throw FileNotFoundException(path)
                else -> ByteArrayInputStream(magic)
            }
        }
        try {
            FontGate.check(open, debug = true, log = logs::add)
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // Bold (3) sorts before Mono (8) — the table order, not the
            // throw order.
            assertEquals(
                "Missing font asset(s): fonts/Geist-Bold.ttf, fonts/GeistMono.ttf",
                expected.message,
            )
        }
    }

    @Test
    fun emptyStreamIsMissing() {
        val open: (String) -> InputStream = { path ->
            if (path == "fonts/Geist.ttf") ByteArrayInputStream(ByteArray(0)) else ByteArrayInputStream(magic)
        }
        val status = FontGate.check(open, debug = false, log = logs::add)
        assertEquals(listOf("fonts/Geist.ttf"), (status as FontStatus.Missing).paths)
        assertSame(status, FontGate.status)
    }

    @Test
    fun shortStreamIsMissing() {
        // Two of the four magic bytes: a truncated asset must not pass.
        val open: (String) -> InputStream = { path ->
            if (path == "fonts/GeistMono-Medium.ttf") ByteArrayInputStream(byteArrayOf(0, 1))
            else ByteArrayInputStream(magic)
        }
        val status = FontGate.check(open, debug = false, log = logs::add)
        assertEquals(listOf("fonts/GeistMono-Medium.ttf"), (status as FontStatus.Missing).paths)
    }

    @Test
    fun wrongMagicIsMissing() {
        // 4F 54 54 4F = "OTTO", the CFF magic: a substituted file is bad.
        val otto = byteArrayOf(0x4F, 0x54, 0x54, 0x4F)
        val open: (String) -> InputStream = { path ->
            if (path == "fonts/Geist-SemiBold.ttf") ByteArrayInputStream(otto + ByteArray(16))
            else ByteArrayInputStream(magic)
        }
        val status = FontGate.check(open, debug = false, log = logs::add)
        assertEquals(listOf("fonts/Geist-SemiBold.ttf"), (status as FontStatus.Missing).paths)
    }

    @Test
    fun releaseBuildStoresMissingAndLogsTheSameMessage() {
        val open: (String) -> InputStream = { path ->
            if (path == "fonts/GeistMono-Italic.ttf") throw FileNotFoundException(path)
            ByteArrayInputStream(magic)
        }
        val status = FontGate.check(open, debug = false, log = logs::add)
        assertEquals(listOf("fonts/GeistMono-Italic.ttf"), (status as FontStatus.Missing).paths)
        assertSame(status, FontGate.status)
        assertEquals(listOf("Missing font asset(s): fonts/GeistMono-Italic.ttf"), logs)
    }

    @Test
    fun releaseBuildWithAllFacesPresentStaysOkWithoutLogging() {
        val status = FontGate.check(allGood(), debug = false, log = logs::add)
        assertSame(FontStatus.Ok, status)
        assertTrue(logs.isEmpty())
    }
}
