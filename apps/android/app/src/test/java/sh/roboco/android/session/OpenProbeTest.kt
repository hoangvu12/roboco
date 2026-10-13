package sh.roboco.android.session

import java.io.File
import java.nio.file.Files
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [OpenProbe] (android-ui-parity #75): the JSON format (root locale, iOS's
 * exact `%.1f` / `%d` / `%.2f`), the atomic file write and the missing-dir
 * failure. Plain JUnit: [OpenProbe.log] is swapped for the JVM (the stub
 * `android.util.Log` throws "not mocked"), exactly as the
 * `LaunchOptions` tests do.
 */
class OpenProbeTest {

    private var savedLog: ((Int, String) -> Unit)? = null
    private val logged = mutableListOf<Pair<Int, String>>()
    private var savedLocale: Locale? = null

    @Before
    fun swapLog() {
        savedLog = OpenProbe.log
        OpenProbe.log = { level, msg -> logged += level to msg }
    }

    @After
    fun restoreLog() {
        OpenProbe.log = savedLog!!
        savedLocale?.let { Locale.setDefault(it) }
    }

    // --------------------------------------------------------------- json

    @Test
    fun jsonMatchesTheIosFormat() {
        assertEquals(
            "{\"openMs\":412.3,\"rows\":57,\"layoutPassMs\":3.21}",
            OpenProbe.json(openMs = 412.34, rows = 57, buildMicros = 3214),
        )
    }

    @Test
    fun jsonIsLocaleIndependent() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale("vi", "VN"))
        assertEquals(
            "a vi default locale must not turn the decimal point into a comma",
            "{\"openMs\":412.3,\"rows\":57,\"layoutPassMs\":3.21}",
            OpenProbe.json(openMs = 412.34, rows = 57, buildMicros = 3214),
        )
    }

    @Test
    fun jsonZeroesPadToTheirWidths() {
        assertEquals(
            "{\"openMs\":0.0,\"rows\":1,\"layoutPassMs\":0.00}",
            OpenProbe.json(openMs = 0.0, rows = 1, buildMicros = 0),
        )
    }

    // --------------------------------------------------- the file write

    @Test
    fun writeAtomicallyLeavesOnlyOpenJsonAndReplacesIt() {
        val dir = Files.createTempDirectory("openprobe").toFile()
        try {
            val first = OpenProbe.json(12.0, 3, 1000)
            assertTrue(OpenProbe.writeAtomically(dir, first))
            assertEquals(listOf("open.json"), dir.listFiles()!!.map { it.name })
            assertEquals(first, File(dir, "open.json").readText())

            val second = OpenProbe.json(99.9, 9, 9000)
            assertTrue(OpenProbe.writeAtomically(dir, second))
            assertEquals("a second write replaces the first", second, File(dir, "open.json").readText())
            assertEquals(
                "no tmp file survives the rename",
                listOf("open.json"),
                dir.listFiles()!!.map { it.name },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writeAtomicallyWithoutADirReturnsFalseAndLogsOnce() {
        logged.clear()
        assertFalse("iOS's try: a null dir is not a crash", OpenProbe.writeAtomically(null, "{}"))
        assertEquals(1, logged.size)
        assertEquals(OpenProbe.WARN, logged.single().first)
        assertEquals("open.json: no external files dir", logged.single().second)
    }
}
