package sh.roboco.android.design

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.core.assetPath

/**
 * The icon sizing and weight tests (android-ui-parity #06), Robolectric at
 * mdpi (1 dp = 1 px) so the dp assertions read directly off the measured
 * pixels:
 *
 * - the `sizePt × 1.2` dp layout box for `Icon`, the explicit box for
 *   `IconInBox`, and `NumberCircle`'s box;
 * - the weight rebuild: `zi_plus`'s 1.25 stroke × 1.27 at Semibold,
 *   fill-only `zi_moon` bit-identical, and the (res, weight) cache;
 * - every registry `res` resolves to a real `zi_*` drawable;
 * - the `NumberCircle` digit's ink centring.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class IconSizingTest {

    @get:Rule
    val compose = createComposeRule()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    // -----------------------------------------------------------------
    // Layout boxes
    // -----------------------------------------------------------------

    @Test
    @Config(sdk = [36], qualifiers = "w400dp-h400dp-mdpi")
    fun iconReservesABoxOfOnePointTwoDpPerSizePt() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Column {
                    Icon(RobocoIcons.Plus, Color.Black, 15f, modifier = Modifier.testTag("pt15"))
                    Icon(RobocoIcons.Plus, Color.Black, 11f, modifier = Modifier.testTag("pt11"))
                    Icon(RobocoIcons.Plus, Color.Black, 17f, modifier = Modifier.testTag("pt17"))
                }
            }
        }
        // The accessory plus: 15 semibold on iOS, 18 dp box.
        compose.onNodeWithTag("pt15").assertWidthIsEqualTo(18.dp).assertHeightIsEqualTo(18.dp)
        // The section chevron: 11 pt, 13.2 dp box.
        compose.onNodeWithTag("pt11").assertWidthIsEqualTo(13.2.dp).assertHeightIsEqualTo(13.2.dp)
        // The default symbol size: 17 pt, 20.4 dp box.
        compose.onNodeWithTag("pt17").assertWidthIsEqualTo(20.4.dp).assertHeightIsEqualTo(20.4.dp)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w400dp-h400dp-mdpi")
    fun iconInBoxUsesItsExplicitBox() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                IconInBox(RobocoIcons.TabChat, Color.Black, 24.dp, modifier = Modifier.testTag("box"))
            }
        }
        compose.onNodeWithTag("box").assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w400dp-h400dp-mdpi")
    fun numberCircleMeasuresLikeIcon() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                NumberCircle(7, Color.Black, 17f, modifier = Modifier.testTag("n7"))
            }
        }
        compose.onNodeWithTag("n7").assertWidthIsEqualTo(20.4.dp).assertHeightIsEqualTo(20.4.dp)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w400dp-h400dp-mdpi")
    fun iconDelegatesNumberIconsToNumberCircle() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Icon(RobocoIcon.Number(3), Color.Black, 17f, modifier = Modifier.testTag("n3"))
            }
        }
        compose.onNodeWithTag("n3").assertWidthIsEqualTo(20.4.dp).assertHeightIsEqualTo(20.4.dp)
    }

    // -----------------------------------------------------------------
    // Weight scaling
    // -----------------------------------------------------------------

    @Test
    fun semiboldScalesEveryPlusStrokeByOnePointTwoSeven() {
        val resources = context().resources
        val regular = weightedIconVector(resources, RobocoIcons.Plus, SymbolWeight.Regular)
        val semibold = weightedIconVector(resources, RobocoIcons.Plus, SymbolWeight.Semibold)

        val paths = regular.vectorPaths()
        assertTrue("zi_plus must have at least one path", paths.isNotEmpty())
        assertTrue("zi_plus is a line glyph: every path is stroked", paths.all { it.stroke != null })
        assertTrue(
            "zi_plus carries plus.svg's 1.25 stroke on the 16 grid",
            paths.all { it.strokeLineWidth == 1.25f },
        )

        // (9.5 + 1.25 × 1.27) / 16 × 18 = 12.5 dp of ink — the measured
        // iOS accessory plus is 12.4 pt.
        for (path in semibold.vectorPaths()) {
            assertEquals(1.25f * 1.27f, path.strokeLineWidth, 0.001f)
        }
    }

    @Test
    fun fillOnlyGlyphsAreUnchangedByWeight() {
        val resources = context().resources
        val regular = weightedIconVector(resources, RobocoIcons.Moon, SymbolWeight.Regular)
        val bold = weightedIconVector(resources, RobocoIcons.Moon, SymbolWeight.Bold)

        assertTrue("zi_moon is fill-only", regular.vectorPaths().all { it.stroke == null })
        // Every property of every node is copied verbatim — only stroked
        // paths may change, and only their strokeLineWidth.
        assertEquals(regular.vectorPaths(), bold.vectorPaths())
        assertEquals(regular.root.toList(), bold.root.toList())
    }

    @Test
    fun theWeightedVectorCacheReturnsStableInstances() {
        val resources = context().resources
        val bold = weightedIconVector(resources, RobocoIcons.Moon, SymbolWeight.Bold)
        assertSame(bold, weightedIconVector(resources, RobocoIcons.Moon, SymbolWeight.Bold))
        val light = weightedIconVector(resources, RobocoIcons.Moon, SymbolWeight.Light)
        assertNotSame(bold, light)
        val regular = weightedIconVector(resources, RobocoIcons.Moon, SymbolWeight.Regular)
        assertSame(regular, weightedIconVector(resources, RobocoIcons.Moon, SymbolWeight.Regular))
    }

    // -----------------------------------------------------------------
    // Resources
    // -----------------------------------------------------------------

    @Test
    fun everyRegistryVectorResolvesToAZiDrawable() {
        val resources = context().resources
        for (entry in RobocoIcons.entries) {
            val icon = entry.icon as? RobocoIcon.Vector ?: continue
            // Throws NotFoundException if the drawable does not exist.
            val name = resources.getResourceEntryName(icon.res)
            assertTrue("${entry.name} resolves to \"$name\"", name.startsWith("zi_"))
        }
    }

    // -----------------------------------------------------------------
    // NumberCircle digits
    // -----------------------------------------------------------------

    @Test
    fun theDigitInkCentresOnTheRingCentre() {
        val context = context()
        val typeface = Typeface.createFromAsset(context.assets, Face.SansMedium.assetPath)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
        val box = 20.4f // 17 pt × 1.2 dp, at mdpi also 20.4 px
        val cx = box / 2f
        val cy = box / 2f
        for (n in listOf(0, 1, 7, 9, 12, 50)) {
            val text = n.toString()
            paint.textSize = box * numberCircleDigitFraction(text)
            val origin = digitDrawOrigin(paint, text, cx, cy)
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            assertTrue(
                "Geist Medium must report ink bounds for \"$text\"",
                bounds.width() > 0 && bounds.height() > 0,
            )
            assertEquals("x ink centre of \"$text\"", cx, origin.x + bounds.exactCenterX(), 0.5f)
            assertEquals("y ink centre of \"$text\"", cy, origin.y + bounds.exactCenterY(), 0.5f)
        }
    }
}
