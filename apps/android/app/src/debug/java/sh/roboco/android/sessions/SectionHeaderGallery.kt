package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Section header" fixtures (android-ui-parity #39): the ticket's
 * six states — the front page's sections, a collapsed one, the
 * multi-engine title, the overflow + live glyph case and a long count.
 * Plain [SectionHeaderState] literals: no FFI, nothing from ticket 40/41
 * beyond the shared data types.
 *
 * Debug-only; if ticket 41's fixtures need the list, move the object to
 * the test source set later.
 */
internal object SectionHeaderFixtures {
    /** "Pinned 2": the front page's pinned group. */
    val pinned = SectionHeaderState("pinned", "Pinned", 2, collapsed = false, live = null)

    /** "P0 2" with a working session: the spinner glyph. */
    val p0 = SectionHeaderState("p0", "P0", 2, collapsed = false, live = SectionLive.Spinner)

    /** "Mobile 2" folded, awaiting input: the input dot and a right chevron. */
    val mobile = SectionHeaderState("mobile", "Mobile", 2, collapsed = true, live = SectionLive.Input)

    /** A multi-engine title (ticket 40's collision suffix shape). */
    val homeMbp = SectionHeaderState("home-mbp", "Home · MacBook Pro", 1, collapsed = false, live = null)

    /** The overflow case: capped at W − 120, fading, live, huge count. */
    val longTitle = SectionHeaderState(
        "long",
        "A section with a name far too long to fit on one line",
        128,
        collapsed = false,
        live = SectionLive.Spinner,
    )

    /** "Recent 4" folded: no glyph. */
    val recent = SectionHeaderState("recent", "Recent", 4, collapsed = true, live = null)

    val all: List<SectionHeaderState> = listOf(pinned, p0, mobile, homeMbp, longTitle, recent)
}

/**
 * The "Section header" page of the design gallery (android-ui-parity #39):
 * the six fixtures as tap-to-fold rows, mimicking ticket 41's host — the
 * `clickable` (indication = null, `Role.Button`) and the press →
 * highlight wiring live **outside** the row, in its modifier, and `key`
 * keeps each row's chevron state alive across a fold so the change
 * animates instead of snapping. Tapping a row flips its `collapsed` and
 * the chevron springs; holding a finger down shows the pressed fill.
 * Under the rows, a mono caption notes the spring.
 *
 * Registered in `Gallery.sections` as
 * `GallerySection("Section header", screenshotHeightDp = 480) { SectionHeaderGallerySection() }`.
 */
@Composable
fun SectionHeaderGallerySection() {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    var states by remember { mutableStateOf(SectionHeaderFixtures.all) }
    Column(Modifier.background(palette.background)) {
        for (s in states) {
            key(s.id) {
                val src = remember { MutableInteractionSource() }
                val pressed by src.collectIsPressedAsState()
                SectionHeaderRow(
                    s,
                    highlight = { if (pressed) 1f else 0f },
                    Modifier.clickable(
                        interactionSource = src,
                        indication = null,
                        role = Role.Button,
                    ) {
                        states = states.map { if (it.id == s.id) it.copy(collapsed = !it.collapsed) else it }
                    },
                )
            }
        }
        BasicText(
            "tap to fold · spring 0.85 / 733.83",
            style = uiStyle(Face.Mono, ts.fixed(11f), palette.tertiary),
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The ticket's preview body: the six fixtures plus a pressed seventh row. */
@Composable
private fun SectionHeaderPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        // Frozen at 0 ns: the spinner rows render the cycle-0 pattern and
        // the preview never starts a frame loop.
        ProvideGlyphClock(frozenAtNanos = 0L) {
            Column(Modifier.background(RobocoTheme.palette.background)) {
                SectionHeaderFixtures.all.forEach { SectionHeaderRow(it, highlight = { 0f }) }
                SectionHeaderRow(SectionHeaderFixtures.all[1], highlight = { 1f })
            }
        }
    }
}

@Preview(name = "Section header – light", widthDp = 402, heightDp = 300)
@Composable
private fun SectionHeaderLightPreview() {
    SectionHeaderPreviewBody(Appearance.Light)
}

@Preview(name = "Section header – dark", widthDp = 402, heightDp = 300, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SectionHeaderDarkPreview() {
    SectionHeaderPreviewBody(Appearance.Dark)
}
