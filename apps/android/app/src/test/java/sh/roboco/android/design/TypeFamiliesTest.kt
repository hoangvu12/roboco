package sh.roboco.android.design

import android.content.Context
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.core.FontGate
import sh.roboco.android.core.FontLoadException
import sh.roboco.android.core.FontStatus
import sh.roboco.android.core.Fonts

/**
 * The type system end to end under Robolectric (native graphics, so real
 * TTFs load): the families resolve the 12 shipped faces through ticket
 * 60's `ChainFont`s (the shared `FontChain` typefaces), `uiStyle` renders
 * the italic faces #609 omitted, `RobocoTheme` provides the locals and
 * rebuilds the type scale on a font-scale change, and GeistMono's glyph
 * advances prove the text is Geist, not a system fallback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TypeFamiliesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun familiesResolveEightSansAndFourMonoFaces() {
        val families = RobocoFonts.families(appContext())
        val geist = families.geist as FontListFontFamily
        val mono = families.geistMono as FontListFontFamily
        assertEquals(8, geist.size)
        assertEquals(4, mono.size)
        // Geist: 400/500/600/700 × normal/italic. GeistMono: 400/500/600
        // normal + 400 italic — the four faces #609's Theme.kt omitted.
        assertEquals(
            setOf(
                400 to FontStyle.Normal, 500 to FontStyle.Normal,
                600 to FontStyle.Normal, 700 to FontStyle.Normal,
                400 to FontStyle.Italic, 500 to FontStyle.Italic,
                600 to FontStyle.Italic, 700 to FontStyle.Italic,
            ),
            geist.map { it.weight.weight to it.style }.toSet(),
        )
        assertEquals(
            setOf(
                400 to FontStyle.Normal, 500 to FontStyle.Normal,
                600 to FontStyle.Normal, 400 to FontStyle.Italic,
            ),
            mono.map { it.weight.weight to it.style }.toSet(),
        )
        // family(face) routes by the mono flag, never by weight.
        assertSame(geist, families.family(Face.SansBold))
        assertSame(mono, families.family(Face.MonoItalic))
    }

    @Test
    fun uiStyleRendersTheItalicFaces() {
        var sansStyle: TextStyle? = null
        var monoStyle: TextStyle? = null
        compose.setContent {
            RobocoTheme {
                val typeScale = LocalTypeScale.current
                BasicText("sans semibold italic 123", style = uiStyle(Face.SansSemiboldItalic, typeScale.fixed(17f)))
                BasicText("mono italic 123", style = uiStyle(Face.MonoItalic, typeScale.fixed(13f)))
                sansStyle = uiStyle(Face.SansSemiboldItalic, typeScale.fixed(17f))
                monoStyle = uiStyle(Face.MonoItalic, typeScale.fixed(13f))
            }
        }
        compose.onNodeWithText("sans semibold italic 123").assertExists()
        compose.onNodeWithText("mono italic 123").assertExists()

        val families = RobocoFonts.families(appContext())
        assertEquals(families.geist, sansStyle?.fontFamily)
        assertEquals(FontWeight.SemiBold, sansStyle?.fontWeight)
        assertEquals(FontStyle.Italic, sansStyle?.fontStyle)
        // Synthesis off: the Mono italic request stays the 400-italic face
        // instead of being fake-bolded.
        assertEquals(families.geistMono, monoStyle?.fontFamily)
        assertEquals(FontWeight.Normal, monoStyle?.fontWeight)
        assertEquals(FontSynthesis.None, monoStyle?.fontSynthesis)
    }

    @Test
    fun themeProvidesTheFontsAndTheTypeScale() {
        var fonts: RobocoFontFamilies? = null
        var factor = Float.NaN
        compose.setContent {
            RobocoTheme {
                fonts = LocalFonts.current
                factor = LocalTypeScale.current.factor
            }
        }
        compose.waitForIdle()
        assertEquals(1f, factor)
        assertEquals(RobocoFonts.families(appContext()), fonts)
    }

    @Test
    fun fontScaleChangeRebuildsTheTypeScale() {
        var factor = Float.NaN
        compose.setContent { RobocoTheme { factor = LocalTypeScale.current.factor } }
        compose.waitForIdle()
        assertEquals(1f, factor)

        // Settings → Display → Font size: a configuration change. The
        // activity is recreated, LocalDensity changes, and the theme
        // rebuilds the scale — 2.0 clamps to the 1.6 ceiling.
        RuntimeEnvironment.setFontScale(2f)
        compose.activityRule.scenario.recreate()
        compose.setContent { RobocoTheme { factor = LocalTypeScale.current.factor } }
        compose.waitForIdle()
        assertEquals(1.6f, factor)
    }

    @Test
    fun fixedConvertsBackUnderTheRealContextDensity() {
        // The real context density carries the platform's non-linear
        // FontScaleConverter: at 1.3, 17 sp ≈ 20.9 dp (factor ≈ 1.229),
        // which is under the linear 1.3 — proving the curve is in use.
        RuntimeEnvironment.setFontScale(1.3f)
        val density = Density(appContext())
        assertEquals(1.3f, density.fontScale)
        val typeScale = TypeScale.from(density)
        assertTrue("factor ${typeScale.factor} should be > 1", typeScale.factor > 1.0f)
        assertTrue("factor ${typeScale.factor} should be < 1.3", typeScale.factor < 1.3f)
        assertEquals(13f, with(density) { typeScale.fixed(13f).toDp().value }, 0.01f)
    }

    @Test
    fun italicFacesResolveThroughTheChainLoader() {
        // Ticket 60's swap: the italic faces #609 omitted now resolve
        // through ChainLoader, which the load counter proves (and the
        // composition does not throw).
        RobocoFonts.resetForTest()
        try {
            compose.setContent {
                RobocoTheme {
                    val typeScale = LocalTypeScale.current
                    BasicText(
                        "sans semibold italic chain",
                        style = uiStyle(Face.SansSemiboldItalic, typeScale.fixed(17f)),
                    )
                    BasicText(
                        "mono italic chain",
                        style = uiStyle(Face.MonoItalic, typeScale.fixed(13f)),
                    )
                }
            }
            compose.onNodeWithText("sans semibold italic chain").assertExists()
            compose.onNodeWithText("mono italic chain").assertExists()
            compose.waitForIdle()
            assertTrue(
                "ChainLoader resolved ${RobocoFonts.chainLoads.get()} typefaces, expected at least 1",
                RobocoFonts.chainLoads.get() > 0,
            )
        } finally {
            RobocoFonts.resetForTest()
        }
    }

    @Test
    fun chainLoaderFailureReturnsDefaultInReleaseAndThrowsInDebug() {
        // §7's Compose-loader row: an injected Fonts failure (release)
        // returns Typeface.DEFAULT for one frame and marks the gate
        // Missing; (debug) rethrows the FontLoadException.
        val path = "fonts/Geist.ttf"
        Fonts.resetForTest()
        try {
            FontGate.check({ ByteArrayInputStream(byteArrayOf(0, 1, 0, 0)) }, debug = false, log = {})
            // Seeds Fonts' memo with the failure: load() rethrows it here
            // (fail-loud) and every later Fonts.load(context) rethrows the
            // same memoised exception without reading assets.
            val seeded = assertThrows(FontLoadException::class.java) {
                Fonts.load({ _ -> throw FileNotFoundException(path) }, { emptyMap() }, { null })
            }
            assertEquals(path, seeded.path)

            val releaseTypeface = loadChainTypeface(appContext(), Face.Sans, debug = false)
            assertSame(Typeface.DEFAULT, releaseTypeface)
            assertEquals(FontStatus.Missing(listOf(path)), FontGate.status)

            try {
                loadChainTypeface(appContext(), Face.Sans, debug = true)
                fail("expected FontLoadException")
            } catch (expected: FontLoadException) {
                assertEquals(path, expected.path)
            }
        } finally {
            Fonts.resetForTest()
            FontGate.check({ ByteArrayInputStream(byteArrayOf(0, 1, 0, 0)) }, debug = false, log = {})
        }
    }

    @Test
    fun geistMonoGlyphsAdvanceSixHundredPerEm() {
        // "iiii0000" in GeistMono at fixed(10): every glyph advances
        // 600/1000 em (hmtx), so the width is 48 dp ±0.5. Roboto's i/0 mix
        // is clearly narrower — the text really is Geist, not a fallback.
        var monoWidthDp = 0f
        var systemWidthDp = 0f
        compose.setContent {
            RobocoTheme {
                val typeScale = LocalTypeScale.current
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                monoWidthDp = measurer.measure(
                    "iiii0000",
                    uiStyle(Face.Mono, typeScale.fixed(10f)),
                ).size.width / density.density
                systemWidthDp = measurer.measure(
                    "iiii0000",
                    RobocoTextDefaults.Base.copy(
                        fontFamily = FontFamily.Default,
                        fontSize = typeScale.fixed(10f),
                    ),
                ).size.width / density.density
            }
        }
        compose.waitForIdle()
        assertEquals("GeistMono 'iiii0000' at 10 dp", 48f, monoWidthDp, 0.5f)
        assertTrue("system font was $systemWidthDp dp, expected clearly under 44", systemWidthDp < 44f)
    }
}
