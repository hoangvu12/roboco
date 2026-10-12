package roboco.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notices sync (android-app#31): the repository's `LICENSE` and
 * `THIRD_PARTY_NOTICES.md` are copied byte-identical into
 * `assets/licenses/` — a sideloaded APK is the whole distribution, so its
 * licenses ship inside it. Settings → About (ticket 26) displays them from
 * there, beside the font licenses `syncRobocoFonts` stages.
 */
class LicensesSyncTest {

    @Test
    fun copiesLicenseAndNoticesByteIdenticalIntoAssetsLicenses() {
        val dir = createTempDir()
        try {
            val license = dir.resolve("LICENSE").apply { writeText("MIT license text\n") }
            val notices =
                dir.resolve("THIRD_PARTY_NOTICES.md").apply { writeText("# Third-party notices\n\n## Android app\n") }

            val task = taskIn(dir)
            task.noticeFiles.from(license, notices)
            task.assetsDir.set(dir.resolve("generated-assets"))

            task.sync()

            val out = dir.resolve("generated-assets/licenses")
            assertEquals(
                "expected exactly LICENSE and THIRD_PARTY_NOTICES.md in assets/licenses",
                setOf("LICENSE", "THIRD_PARTY_NOTICES.md"),
                out.listFiles()!!.map { it.name }.toSet(),
            )
            assertArrayEquals(license.readBytes(), out.resolve("LICENSE").readBytes())
            assertArrayEquals(notices.readBytes(), out.resolve("THIRD_PARTY_NOTICES.md").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rerunOverwritesInsteadOfDuplicating() {
        val dir = createTempDir()
        try {
            val license = dir.resolve("LICENSE").apply { writeText("v1\n") }

            val task = taskIn(dir)
            task.noticeFiles.from(license)
            task.assetsDir.set(dir.resolve("generated-assets"))

            task.sync()
            license.writeText("v2\n")
            task.sync()

            val out = dir.resolve("generated-assets/licenses")
            assertEquals("v2\n", out.resolve("LICENSE").readText())
            assertEquals(1, out.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun taskIn(dir: File): LicensesSync {
        val project =
            ProjectBuilder.builder().withProjectDir(dir.resolve("project").apply { mkdirs() }).build()
        return project.tasks.register("syncNotices", LicensesSync::class.java).get()
    }

    private fun createTempDir(): File {
        val dir = File.createTempFile("notices", ".dir")
        check(dir.delete() && dir.mkdirs()) { "could not create ${dir.path}" }
        return dir
    }
}
