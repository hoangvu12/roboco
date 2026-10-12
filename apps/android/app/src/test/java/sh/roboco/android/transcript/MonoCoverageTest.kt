package sh.roboco.android.transcript

import java.awt.Font
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.core.FontChain

/**
 * The font-side half of the mono 2-cell rule (ticket 63): inside
 * [FontChain.isWide]'s ranges, the four Geist Mono faces the app ships
 * cover exactly U+3003 `〃` and U+301C `〜` — each at a 1-cell
 * 600/1000-em advance (parsed from `head`/`hmtx`/`cmap` when the ticket
 * was written; `MonoWide.isDoubleWidth` excludes that pair because Rust
 * shapes covered text itself, so it lays them out at one cell and never
 * asks the host measurer).
 *
 * If a font update changes the covered set this test fails, and
 * `MonoWide.isDoubleWidth` (ticket 60's `FontChain.isDoubleWidth`) must be
 * revisited together with the measurer — doubling a covered code point
 * would push every later glyph on a mono line one cell past its Rust
 * position.
 *
 * Plain JVM, like the repo's other pin tests: `java.awt.Font` needs no
 * display, and the TTFs are read from `roboco.repoRoot` (the Gradle test
 * task sets it) — the APK copies are generated assets
 * (`build/generated/roboco-fonts`), not files under `src/main/assets`.
 */
class MonoCoverageTest {

    @Test
    fun geistMonoCoversExactlyTheTwoExcludedWideCodePoints() {
        val root = System.getProperty("roboco.repoRoot")
            ?: throw AssertionError("roboco.repoRoot is not set; run from the Gradle test task")
        for (name in MONO_FACES) {
            val file = File(root, "crates/ui/assets/fonts/$name")
            assertTrue("missing font: ${file.path}", file.isFile)
            val font = Font.createFont(Font.TRUETYPE_FONT, file)
            val covered = ArrayList<Int>()
            var cp = 0
            while (cp <= MAX_CODE_POINT) {
                if (FontChain.isWide(cp) && font.canDisplay(cp)) covered.add(cp)
                cp++
            }
            assertEquals(
                "$name: the wide-range coverage changed; update FontChain.isDoubleWidth (and the measurer rule)",
                listOf(0x3003, 0x301C),
                covered,
            )
        }
    }

    private companion object {
        /** The four mono roles the app ships (buildSrc `RobocoFonts.FILES`). */
        val MONO_FACES = listOf(
            "GeistMono.ttf",
            "GeistMono-Medium.ttf",
            "GeistMono-SemiBold.ttf",
            "GeistMono-Italic.ttf",
        )

        /** [FontChain.isWide]'s highest range ends at 0x3FFFD. */
        const val MAX_CODE_POINT = 0x3FFFD
    }
}
