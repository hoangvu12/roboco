package sh.roboco.android.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.DebugEntry

/**
 * The accessibility fixture (ticket 129), shared by this section and
 * `TranscriptAccessibilityTest` / `TranscriptA11yScreenshotTest`: one user
 * turn and one reply whose five markdown blocks each become a row —
 * heading, paragraph with a web link, list with a `ja.wikipedia` link, a
 * short fenced code block (kept short so it does **not** overflow: the
 * table is then the only scroller whose `HorizontalScrollAxisRange`
 * max value is positive) and a wide three-column table, whose `wiki`
 * link sits in the table's scroller past its visible width. `working =
 * true` adds the trailing `row-working` row. 7 rows total.
 */
val A11yFixture = listOf(
    DebugEntry("u1", true, "Summarize the launch post in three bullets.", false),
    DebugEntry(
        "a1",
        false,
        """
        |## Links
        |
        |See [the docs](https://example.com/docs) for details.
        |
        |1. first
        |2. second
        |3. third [link](https://ja.wikipedia.org/wiki/x)
        |
        |```kotlin
        |val band = floor(offset / 256f)
        |```
        |
        || Column one with a long description | Column two with a long description too | Column three |
        ||---|---|---|
        || The first cell keeps going for a while here | The middle cell is also quite wide in this table | Read [wiki](https://en.wikipedia.org/wiki/Table) now |
        """.trimMargin(),
        false,
    ),
)

/**
 * The "Transcript accessibility" section of the debug design gallery
 * (ticket 129): the real [TranscriptHost] over the real core, fed the
 * [A11yFixture] with `working = true`, with [LocalTranscriptA11yBounds]
 * on — the invisible nodes are outlined so they can be compared against
 * the painted text: link fragments in `Palette.accent` (violet), exposed
 * widget groups in `Palette.danger` (red). Scroll the table with a
 * finger and the `wiki` outline slides with the painted text (there is
 * no public scroll call — 116 exposes none — and no extra controls by
 * design).
 *
 * The host owns the engine feed's viewport (its `SideEffect` sends the
 * measured width before the `LaunchedEffect` below feeds the entries).
 * Under capture (ticket 22's `LocalGalleryCapture` rule) the section
 * never feeds the engine, so the gallery's own golden is the
 * deterministic pre-frame state; the outlined goldens live in
 * `TranscriptA11yScreenshotTest`, which feeds the fixture and waits for
 * the frame. Build from foundation only: no Material.
 */
@Composable
fun TranscriptA11yGallerySection() {
    val capture = LocalGalleryCapture.current
    val state = remember { TranscriptState() }
    DisposableEffect(state) { onDispose { state.close() } }
    LaunchedEffect(capture) {
        if (!capture) state.engine.setDebugEntries(A11yFixture, working = true)
    }
    CompositionLocalProvider(LocalTranscriptA11yBounds provides true) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
        }
    }
}
