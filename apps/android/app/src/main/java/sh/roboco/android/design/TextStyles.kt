package sh.roboco.android.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import sh.roboco.android.core.Face

/**
 * The base [TextStyle] every UI label starts from — what a plain `UILabel`
 * gives the iOS app for free. `uiStyle` layers the face, size and colour on
 * top of it.
 */
object RobocoTextDefaults {

    /**
     * - `includeFontPadding = false` — `UILabel` has no font padding; the
     *   natural Geist line box is ascent + descent = 1.3 × size (the
     *   `usWin` metrics Compose would add otherwise are 1088/262 sans,
     *   1088/394 mono);
     * - natural line height — `UILabel` uses the font's line height;
     * - `letterSpacing = 0` — iOS sets no tracking anywhere in UI text;
     * - `FontSynthesis.None` — iOS draws the exact face: no fake bold for a
     *   Mono 600-italic request (it must resolve to MonoItalic 400, like
     *   Rust's `FaceRole::of`);
     * - `TextDirection.Content` — follows the string, like `UILabel`'s
     *   natural alignment.
     */
    @Suppress("DEPRECATION") // PlatformTextStyle(includeFontPadding) documents intent; default since 1.5
    val Base: TextStyle = TextStyle(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeight = TextUnit.Unspecified,
        letterSpacing = 0.sp,
        fontSynthesis = FontSynthesis.None,
        textDirection = TextDirection.Content,
    )
}

/**
 * The UI text style for one face at one size — the `Fonts.ui(face, size)`
 * port. Each consumer ticket names its own face and size, exactly as the
 * iOS call site does: no per-screen type roles exist.
 *
 * @param size the size from [TypeScale]: `ts.fixed(pt)` for fixed UIKit
 *   points, `ts.scaled(pt)` for Dynamic-Type labels, `ts.bodyScaled(pt)` for
 *   raw body-scaled text.
 */
@Composable
@ReadOnlyComposable
fun uiStyle(face: Face, size: TextUnit, color: Color = Color.Unspecified): TextStyle =
    RobocoTextDefaults.Base.copy(
        fontFamily = LocalFonts.current.family(face),
        fontWeight = face.weight,
        fontStyle = face.style,
        fontSize = size,
        color = color,
    )
