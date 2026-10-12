package sh.roboco.android.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the "no Material chrome" rule at the source level: nothing under
 * `src/main` may import material3 / material-icons or name a Material theme.
 * Extended by ui-parity#07: `dev.chrisbanes.haze.blur.materials`
 * (HazeMaterials) is equally banned — the iOS look is this repo's own
 * glass recipe, not Haze's presets.
 *
 * `user.dir` is the Gradle module directory (`apps/android/app`), so the scan
 * covers the whole app module, not just the design package.
 */
class NoMaterialImportsTest {

    private val materialImport = Regex("^\\s*import\\s+androidx\\.compose\\.material3\\b")
    private val materialIconsImport = Regex("^\\s*import\\s+androidx\\.compose\\.material\\.icons\\b")
    private val materialThemeReference = Regex("\\bMaterial(Expressive)?Theme\\b")
    private val hazeMaterialsImport = Regex("^\\s*import\\s+dev\\.chrisbanes\\.haze\\.blur\\.materials\\b")

    private fun sourceFiles(root: File): List<File> {
        val out = mutableListOf<File>()
        root.walkTopDown().forEach { f ->
            if (f.isFile && (f.extension == "kt" || f.extension == "java")) out += f
        }
        return out
    }

    @Test
    fun mainSourcesImportNoMaterial() {
        val main = File("src/main")
        assertTrue("src/main not found under ${File(".").absolutePath}", main.isDirectory)
        val files = sourceFiles(main)
        assertTrue("no Kotlin/Java files scanned under src/main", files.isNotEmpty())

        val offenders = mutableListOf<String>()
        for (file in files) {
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val number = index + 1
                    when {
                        materialImport.containsMatchIn(line) ||
                            materialIconsImport.containsMatchIn(line) ||
                            hazeMaterialsImport.containsMatchIn(line) ||
                            materialThemeReference.containsMatchIn(line) ->
                            offenders += "${file.invariantSeparatorsPath}:$number: ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "Material leaked into main sources (BRIEF: no Material components for visible chrome; " +
                "HazeMaterials presets are not the iOS look):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
