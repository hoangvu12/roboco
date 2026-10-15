package sh.roboco.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The generated asset/res trees, from the `roboco.*` system properties the
 * Gradle build sets (android-app#13). Plain JVM: reads PNG IHDR bytes, no
 * Android APIs, so the merge check can run it cheaply.
 */
class GeneratedAssetsTest {

    private val repoRoot = File(System.getProperty("roboco.repoRoot"))
    private val generatedAssets = File(System.getProperty("roboco.generatedAssets"))
    private val generatedRes = File(System.getProperty("roboco.generatedRes"))
    private val generatedFonts = File(System.getProperty("roboco.generatedFonts"))
    private val generatedLicenses = File(System.getProperty("roboco.generatedLicenses"))

    @Test
    fun licenseAndThirdPartyNoticesAreBundledByteIdentical() {
        // android-app#31: a sideloaded APK is the whole distribution, so the
        // repo's LICENSE and THIRD_PARTY_NOTICES.md ship inside it (the
        // Android section rides in the notices file).
        val license = generatedLicenses.resolve("licenses/LICENSE")
        val notices = generatedLicenses.resolve("licenses/THIRD_PARTY_NOTICES.md")
        assertTrue("LICENSE missing from the generated assets", license.isFile)
        assertTrue("THIRD_PARTY_NOTICES.md missing from the generated assets", notices.isFile)
        assertArrayEquals(
            "LICENSE differs from the repo root copy",
            repoRoot.resolve("LICENSE").readBytes(),
            license.readBytes(),
        )
        assertArrayEquals(
            "THIRD_PARTY_NOTICES.md differs from the repo root copy",
            repoRoot.resolve("THIRD_PARTY_NOTICES.md").readBytes(),
            notices.readBytes(),
        )
        assertTrue("THIRD_PARTY_NOTICES.md has no Android section", notices.readText().contains("## Android app"))
    }

    @Test
    fun allTwelveFontsExistAndAreByteIdenticalToTheSource() {
        // The app's own list is the source of truth here: this test then
        // verifies the Gradle sync produced exactly those faces.
        for (file in sh.roboco.android.core.FontAssets.FILES) {
            val source = repoRoot.resolve("crates/ui/assets/fonts").resolve(file)
            val generated = generatedFonts.resolve("fonts").resolve(file)
            assertTrue("$file missing from the generated fonts", generated.isFile)
            assertArrayEquals("$file differs from the source face", source.readBytes(), generated.readBytes())
        }
        // The licenses ride along with the fonts.
        assertTrue(generatedFonts.resolve("licenses/Geist-OFL.txt").isFile)
        assertTrue(generatedFonts.resolve("licenses/vscode-symbols-LICENSE.txt").isFile)
    }

    @Test
    fun iconPngsExistAt96x96() {
        for (name in listOf(
            "tool-terminal.png",
            "tool-bot.png",
            "fileicon-files-rust.png",
            "fileicon-folders-folder-src.png",
            "fileicon-files-angular-directive-dark.png",
            "fileicon-folders-folder-actions-dark.png",
        )) {
            val png = generatedAssets.resolve("icons").resolve(name)
            assertTrue("$name missing from the generated icons", png.isFile)
            val (width, height) = pngSize(png)
            assertEquals("$name is $width x $height", 96, width)
            assertEquals(96, height)
        }
    }

    @Test
    fun vectorDrawablesExist() {
        for (name in listOf("zi_tab_chat.xml", "zi_tab_settings.xml", "zi_plus.xml", "zi_roboco_bow.xml")) {
            val xml = generatedRes.resolve("drawable").resolve(name)
            assertTrue("$name missing from the generated res", xml.isFile)
            val text = xml.readText()
            assertTrue("$name is not a VectorDrawable", text.contains("<vector"))
        }
    }

    @Test
    fun noBrandMarksOrLogoLeakIntoTheOutputs() {
        fun filesUnder(root: File): List<String> =
            root.walkTopDown().filter { it.isFile }.map { it.name }.toList()

        val outputs = filesUnder(generatedAssets) + filesUnder(generatedRes)
        for (name in outputs) {
            // `file-markdown`/`julia-markdown` are file icons, not brand marks.
            assertFalse("$name must not exist", name.endsWith("-mark.xml") || name.endsWith("-mark.png"))
            assertFalse("$name must not exist", name.startsWith("roboco_logo") || name.startsWith("zi_roboco_logo"))
        }
    }

    /** Reads the IHDR chunk: (width, height). */
    private fun pngSize(png: File): Pair<Int, Int> {
        val bytes = png.readBytes().copyOfRange(0, 24)
        assertTrue("not a PNG: ${png.name}", bytes.take(4) == listOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        assertEquals("first chunk is not IHDR", "IHDR", String(bytes, 12, 4, Charsets.US_ASCII))
        val width = readInt(bytes, 16)
        val height = readInt(bytes, 20)
        return width to height
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
