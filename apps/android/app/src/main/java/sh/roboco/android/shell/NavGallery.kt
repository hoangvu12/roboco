package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.hairlineWidth

/**
 * The navigation gallery (ticket 24): demo routes, the demo screen, the fake
 * bottom chrome, and the "Navigation" section of the debug design gallery.
 *
 * Ticket 22 owns the registration line in
 * `apps/android/app/src/debug/java/sh/roboco/android/design/Gallery.kt`:
 * `GallerySection("Navigation", GalleryLayout.Scroll, screenshotHeightDp = 891) { NavGallerySection() }`
 * — that file does not exist yet (ticket 22 is not merged), same as ticket
 * 05's `HapticsGallerySection`.
 *
 * Text styles are monospace/sans-serif stand-ins until ticket 03's Geist type
 * scale lands (`uiStyle(Face.SansSemibold, ts.fixed(30f))` etc.); 03 swaps
 * them, like `ThemeGallerySections`.
 */
internal data class DemoRoute(val title: String, val hides: Boolean = false) : NavRoute {
    override val hidesBottomChrome: Boolean get() = hides
    override val paneTitle: String get() = title
}

// The demo styles stay on the platform font on purpose: it keeps the nav
// goldens (NavDemoScreen in the nav/chrome/session push-pop captures) stable.

/** The demo screen's title (platform sans, 30 sp). */
private fun demoTitleStyle(color: Color) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 30.sp, color = color)

/** The demo buttons (platform sans, 15 sp). */
private fun demoButtonStyle(color: Color) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, color = color)

/** The demo readouts (platform monospace, 12 sp). */
private fun demoMonoStyle(color: Color) =
    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)

/**
 * A demo screen: opaque `Palette.background` fill with a centred title
 * (iOS large-title face, `ios:Shell/MainTabController.swift:130`). Screens at
 * depth ≥ 2 draw a 1-px `Palette.hairline` vertical line on their leading
 * edge so the slide is visible in goldens even though both screens share the
 * background colour. With [showControls] (the gallery) it also shows its
 * depth and the demo buttons.
 */
@Composable
internal fun NavDemoScreen(entry: NavEntry, showControls: Boolean = false) {
    val stack = LocalNavStack.current
    val palette = RobocoTheme.palette
    val depth = stack.entries.indexOf(entry) + 1
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        if (depth >= 2) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(hairlineWidth())
                    .align(Alignment.CenterStart)
                    .background(palette.hairline),
            )
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(entry.route.paneTitle.orEmpty(), style = demoTitleStyle(palette.text))
            if (showControls) {
                BasicText(
                    "depth $depth",
                    style = demoMonoStyle(palette.secondary),
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                DemoRow("Push folder") { stack.push(DemoRoute("Folder")) }
                DemoRow("Push session") { stack.push(DemoRoute("Session", hides = true)) }
                DemoRow("Pop") { stack.pop() }
                DemoRow("Pop to root") { stack.popToRoot() }
                DemoRow("Replace root") { stack.setRoutes(listOf(DemoRoute("Root"))) }
            }
        }
    }
}

/** A 44 dp tall hit row with an accent-coloured label. */
@Composable
private fun DemoRow(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = demoButtonStyle(RobocoTheme.palette.accent))
    }
}

/**
 * The fake bottom chrome of the previews, goldens and the gallery: a 200 ×
 * 64 dp tertiary capsule, centred horizontally, its bottom 16 dp above the
 * host bottom — wrapped so the slot's measured height is 80 dp. The real tab
 * chrome is ticket 26/30's; this one only demonstrates the slot's placement.
 */
@Composable
internal fun NavFakeChrome() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 200.dp, height = 64.dp)
                .background(RobocoTheme.palette.tertiary, RoundedCornerShape(50)),
        )
    }
}

/**
 * The "Navigation" gallery section: a 360 × 640 dp framed, active
 * `NavStackHost` with the fake chrome. Predictive back works inside the
 * gallery activity, and the readout under the frame shows the live transition
 * state and chrome visibility. The gallery's "Force reduce motion" toggle
 * (ticket 22) reaches the host through `LocalReduceMotion`.
 *
 * This demo uses the host's `bottomChrome` slot, which places the chrome
 * by z-order for free — but the phone shell deliberately does **not**
 * (ticket 30): 26 re-keys the host per tab (leaving the slot would break
 * upstream #577's never-leave-composition rule) and the host sits inside
 * the shell's `hazeSource`, which ticket 07's glass rule forbids. The
 * shell keeps the chrome as a sibling overlay and reproduces this z-order
 * with a clip at the incoming screen's leading edge plus a `SrcAtop` dim
 * (`Modifier.chromeVisibility`).
 */
@Composable
internal fun NavGallerySection() {
    val stack = remember { NavStack(DemoRoute("Root")) }
    val palette = RobocoTheme.palette
    Column {
        Box(
            Modifier
                .width(360.dp)
                .height(640.dp)
                .hairlineBorder(palette.hairline, RectangleShape),
        ) {
            NavStackHost(
                stack = stack,
                active = true,
                bottomChrome = { NavFakeChrome() },
            ) { entry ->
                NavDemoScreen(entry, showControls = true)
            }
        }
        val t = stack.transition
        val kind = when (t?.kind) {
            NavKind.Push -> "push"
            NavKind.Pop -> "pop"
            null -> "idle"
        }
        BasicText(
            text = kind + " · %.2f · interactive=${t?.isInteractive ?: false} · %.2f"
                .format(t?.progress ?: 1f, stack.bottomChromeVisibility),
            style = demoMonoStyle(palette.secondary),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Preview(name = "navigation gallery light", widthDp = 400, heightDp = 760)
@Composable
private fun NavGallerySectionPreview() {
    RobocoTheme { NavGallerySection() }
}

@Preview(name = "navigation gallery dark", widthDp = 400, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NavGallerySectionDarkPreview() {
    RobocoTheme { NavGallerySection() }
}
