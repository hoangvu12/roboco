package roboco.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The notices sync (android-app#31 + ui-parity#101): the repository's
 * `LICENSE` and `THIRD_PARTY_NOTICES.md` are copied byte-identical into
 * `assets/licenses/` — a sideloaded APK is the whole distribution, so its
 * licenses ship inside it. Settings → About (ticket 26) displays them from
 * there, beside the font licenses `syncRobocoFonts` stages; #101 adds the
 * committed SPDX fallback texts under `assets/licenses/spdx/`.
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

    @Test
    fun spdxTextsLandUnderAssetsLicensesSpdx() {
        val dir = createTempDir()
        try {
            val apache = dir.resolve("Apache-2.0.txt").apply { writeText("Apache License\nVersion 2.0\n") }
            val lgpl = dir.resolve("LGPL-2.1-or-later.txt").apply { writeText("GNU LESSER GENERAL PUBLIC LICENSE\n") }

            val task = taskIn(dir)
            task.noticeFiles.from(dir.resolve("LICENSE").apply { writeText("MIT\n") })
            task.spdxFiles.from(apache, lgpl)
            task.assetsDir.set(dir.resolve("generated-assets"))

            task.sync()

            val spdx = dir.resolve("generated-assets/licenses/spdx")
            assertEquals(
                setOf("Apache-2.0.txt", "LGPL-2.1-or-later.txt"),
                spdx.listFiles()!!.map { it.name }.toSet(),
            )
            assertArrayEquals(apache.readBytes(), spdx.resolve("Apache-2.0.txt").readBytes())
            assertArrayEquals(lgpl.readBytes(), spdx.resolve("LGPL-2.1-or-later.txt").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noSpdxInputsLeavesNoSpdxDirectoryAndRemovedSpdxFilesDoNotLinger() {
        val dir = createTempDir()
        try {
            val license = dir.resolve("LICENSE").apply { writeText("MIT\n") }
            val out = dir.resolve("generated-assets")

            // No spdxFiles: nothing but the notices, and no empty spdx dir
            // (the merge would otherwise zip an empty folder).
            taskIn(dir).apply {
                noticeFiles.from(license)
                assetsDir.set(out)
            }.sync()
            val licenses = out.resolve("licenses")
            assertEquals(
                setOf("LICENSE"),
                licenses.listFiles()!!.map { it.name }.toSet(),
            )

            // Then stage one, sync, drop it from the inputs, sync again: the
            // stale text must not linger (the spdx dir is reset, not merged).
            val spdx = dir.resolve("Apache-2.0.txt").apply { writeText("Apache\n") }
            taskIn(dir, "stageSpdx").apply {
                noticeFiles.from(license)
                spdxFiles.from(spdx)
                assetsDir.set(out)
            }.sync()
            assertEquals(
                setOf("Apache-2.0.txt"),
                licenses.resolve("spdx").listFiles()!!.map { it.name }.toSet(),
            )
            taskIn(dir, "dropSpdx").apply {
                noticeFiles.from(license)
                assetsDir.set(out)
            }.sync()
            assertEquals(
                "a removed SPDX text must not linger",
                setOf("LICENSE"),
                licenses.listFiles()!!.map { it.name }.toSet(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun taskIn(dir: File, name: String = "syncNotices"): LicensesSync {
        val project =
            ProjectBuilder.builder().withProjectDir(dir.resolve("project-$name").apply { mkdirs() }).build()
        return project.tasks.register(name, LicensesSync::class.java).get()
    }

    private fun createTempDir(): File {
        val dir = File.createTempFile("notices", ".dir")
        check(dir.delete() && dir.mkdirs()) { "could not create ${dir.path}" }
        return dir
    }

    @Test
    fun theGoBundleIsStagedUnderLicensesGoWithItsLayoutAndTheKratosLicense() {
        val dir = createTempDir()
        try {
            val bundle = dir.resolve("bundle").apply { mkdirs() }
            bundle.resolve("DEPENDENCIES.txt").writeText("module\tversion\tlicense_files\n")
            bundle.resolve("modules").mkdirs()
            bundle.resolve("modules/001-x-LICENSE.txt").writeText("x licence\n")
            val kratos = dir.resolve("LICENSE.kratos").apply { writeText("MIT kratos\n") }

            val task = taskIn(dir)
            task.goBundleDir.set(bundle)
            task.goLicenseFiles.from(kratos)
            task.assetsDir.set(dir.resolve("generated-assets"))
            task.sync()

            val go = dir.resolve("generated-assets/licenses/go")
            assertArrayEquals(
                bundle.resolve("modules/001-x-LICENSE.txt").readBytes(),
                go.resolve("modules/001-x-LICENSE.txt").readBytes(),
            )
            assertArrayEquals(kratos.readBytes(), go.resolve("LICENSE.kratos").readBytes())
            assertEquals(true, go.resolve("DEPENDENCIES.txt").isFile)

            // Unset again: no stale go/ survives a rerun.
            val bare = taskIn(dir, "bare")
            bare.assetsDir.set(dir.resolve("generated-assets"))
            bare.sync()
            assertEquals(false, go.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
