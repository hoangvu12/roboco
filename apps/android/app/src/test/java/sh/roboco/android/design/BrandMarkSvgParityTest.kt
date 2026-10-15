package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The path data in [BrandMark] must stay byte-identical to the desktop's
 * SVG assets (`crates/ui/assets/icons/<name>-mark.svg`), so the marks match
 * the desktop/iOS glyphs exactly. If this fails after a desktop icon change,
 * re-run `apps/android/scripts/gen_brand_mark_paths.py`.
 */
class BrandMarkSvgParityTest {

    private val dRegex = Regex("""\sd="([^"]*)"""")
    private val viewBoxRegex = Regex("""viewBox="([^"]*)"""")

    /** Walks up from the module directory to the repo root (crates/ui/assets/icons). */
    private fun iconsDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val icons = File(dir, "crates/ui/assets/icons")
            if (icons.isDirectory) return icons
            dir = dir.parentFile
        }
        throw AssertionError("crates/ui/assets/icons not found above ${File("").absoluteFile}")
    }

    private fun svgFor(mark: BrandMark): String =
        File(iconsDir(), "${mark.svgName}-mark.svg").readText()

    private fun dValues(mark: BrandMark): List<String> =
        dRegex.findAll(svgFor(mark)).map { it.groupValues[1] }.toList()

    /** The first `<path …>` element, attributes included. */
    private fun firstPathElement(mark: BrandMark): String {
        val svg = svgFor(mark)
        val start = svg.indexOf("<path")
        assertTrue("no <path> in ${mark.svgName}-mark.svg", start >= 0)
        return svg.substring(start, svg.indexOf('>', start))
    }

    @Test
    fun pathDataEqualsTheSvgDAttributes() {
        for (mark in BrandMark.entries) {
            val ds = dValues(mark)
            assertTrue("${mark.svgName}-mark.svg has no <path> d", ds.isNotEmpty())
            val expected = when (mark) {
                // The three subpaths, joined with one space (ios:Design/HermesMark.swift).
                BrandMark.HERMES -> ds.joinToString(" ")
                // The frame only: the second <path> is a 45%-opacity inner fill
                // iOS's single-path renderer skips (BrandMarks.swift:53-55).
                else -> ds.first()
            }
            assertEquals("${mark.svgName} path data drifted from the SVG asset", expected, mark.pathData)
        }
    }

    @Test
    fun opencodeSvgHasExactlyTwoPaths() {
        val ds = dValues(BrandMark.OPENCODE)
        assertEquals("opencode-mark.svg is expected to carry the frame + inner fill", 2, ds.size)
        assertEquals(BrandMark.OPENCODE.pathData, ds.first())
    }

    @Test
    fun viewBoxesMatchTheSvgAssets() {
        for (mark in BrandMark.entries) {
            val viewBox = viewBoxRegex.find(svgFor(mark))
                ?: throw AssertionError("no viewBox in ${mark.svgName}-mark.svg")
            val numbers = viewBox.groupValues[1].trim().split(Regex("\\s+"))
            assertEquals("viewBox arity for ${mark.svgName}", 4, numbers.size)
            assertEquals("${mark.svgName} viewBox width", numbers[2].toFloat(), mark.viewBoxWidth)
            assertEquals("${mark.svgName} viewBox height", numbers[3].toFloat(), mark.viewBoxHeight)
        }
    }

    @Test
    fun fillRuleMatchesTheFirstSvgPath() {
        for (mark in BrandMark.entries) {
            val evenOdd = firstPathElement(mark).contains("""fill-rule="evenodd"""")
            // evenOdd is true exactly when the first <path> carries fill-rule="evenodd".
            assertEquals(
                "${mark.svgName} fill rule (a mismatch fills the mark's holes solid)",
                evenOdd,
                mark.evenOdd,
            )
        }
    }
}
