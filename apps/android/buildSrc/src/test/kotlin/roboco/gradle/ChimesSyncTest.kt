package roboco.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The chimes sync (android-app#28): the desktop's three session chimes
 * land byte-identical in `res/raw/` as `chime_<name>.wav`; `appshot.wav`
 * never rides along, and a removed input never lingers.
 */
class ChimesSyncTest {

    @Test
    fun copiesTheThreeChimesByteIdenticalAsChimeRaw() {
        val dir = createTempDir()
        try {
            val done = dir.resolve("done.wav").apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3)) }
            val request = dir.resolve("request.wav").apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 9)) }
            val attention = dir.resolve("attention.wav").apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 8, 8)) }

            val task = taskIn(dir)
            task.chimeFiles.from(done, request, attention)
            task.outputDir.set(dir.resolve("generated-res"))

            task.sync()

            val raw = dir.resolve("generated-res/raw")
            assertEquals(
                "expected exactly the three chimes in res/raw",
                setOf("chime_done.wav", "chime_request.wav", "chime_attention.wav"),
                raw.listFiles()!!.map { it.name }.toSet(),
            )
            assertArrayEquals(done.readBytes(), raw.resolve("chime_done.wav").readBytes())
            assertArrayEquals(request.readBytes(), raw.resolve("chime_request.wav").readBytes())
            assertArrayEquals(attention.readBytes(), raw.resolve("chime_attention.wav").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rerunOverwritesAndARemovedInputNeverLingers() {
        val dir = createTempDir()
        try {
            val done = dir.resolve("done.wav").apply { writeBytes(byteArrayOf(1)) }
            val request = dir.resolve("request.wav").apply { writeBytes(byteArrayOf(2)) }

            val task = taskIn(dir)
            task.chimeFiles.from(done, request)
            task.outputDir.set(dir.resolve("generated-res"))
            task.sync()

            // Rerun with new bytes: overwrite, not duplicate.
            done.writeBytes(byteArrayOf(3))
            task.sync()
            val raw = dir.resolve("generated-res/raw")
            assertArrayEquals(byteArrayOf(3), raw.resolve("chime_done.wav").readBytes())
            assertEquals(2, raw.listFiles()!!.size)

            // Drop an input: its stale copy must not linger.
            val trimmed = taskIn(dir, "trim")
            trimmed.chimeFiles.from(done)
            trimmed.outputDir.set(dir.resolve("generated-res"))
            trimmed.sync()
            assertEquals(
                setOf("chime_done.wav"),
                raw.listFiles()!!.map { it.name }.toSet(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyInputsLeaveNoRawDirectory() {
        val dir = createTempDir()
        try {
            val task = taskIn(dir)
            task.outputDir.set(dir.resolve("generated-res"))
            task.sync()
            assertFalse(dir.resolve("generated-res/raw").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun taskIn(dir: File, name: String = "syncChimes"): ChimesSync {
        val project =
            ProjectBuilder.builder().withProjectDir(dir.resolve("project-$name").apply { mkdirs() }).build()
        return project.tasks.register(name, ChimesSync::class.java).get()
    }

    private fun createTempDir(): File {
        val dir = File.createTempFile("chimes", ".dir")
        check(dir.delete() && dir.mkdirs()) { "could not create ${dir.path}" }
        return dir
    }
}
