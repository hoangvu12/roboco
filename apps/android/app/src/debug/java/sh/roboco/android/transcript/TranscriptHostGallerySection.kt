package sh.roboco.android.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.DebugEntry
import kotlin.random.Random

/**
 * The transcript-host section of the debug design gallery (ticket 65):
 * a full-bleed host over the `background` role, fed the [HostFixture]
 * through the real core (`setDebugEntries`), with an "Append row" control
 * at the bottom-right that re-sends the whole list with one new entry —
 * the new row's key was never seen, so it fades in over 280 ms. Toggle
 * Light/Dark to watch the rows repaint without rebuilding their models;
 * scroll to see the realized band.
 *
 * "Stream" (ticket 118) scripts the iOS lab's stream (Debug/
 * TranscriptLabViewController.swift:102-123): it appends a user turn and
 * an empty live assistant row, then feeds it [HostFixture]'s markdown two
 * to five words at a time every 45 ms with `working` on — the growing row
 * keeps its key, so the veil fades each new chunk in over 220 ms. While
 * the stream runs the control is disabled and drawn with the tertiary
 * colour.
 *
 * Under capture (ticket 22's `LocalGalleryCapture` rule) the section never
 * feeds the engine: an unfed transcript has no rows, so the golden is the
 * deterministic pre-frame state (background + the controls). The row
 * goldens live in `TranscriptHostScreenshotTest`, which waits for the
 * frame; the gallery screenshot golden just pins this section's chrome.
 *
 * Build from foundation only: `BasicText` + `clickable` over the theme's
 * `NoIndication` — no Material button.
 */
@Composable
fun TranscriptHostGallerySection() {
    val capture = LocalGalleryCapture.current
    val palette = RobocoTheme.palette
    val state = remember { TranscriptState() }
    DisposableEffect(state) { onDispose { state.close() } }
    val scope = rememberCoroutineScope()

    // Every call re-sends the whole list, so the list lives here and grows.
    var entries by remember { mutableStateOf(HostFixture) }
    var appended by remember { mutableIntStateOf(0) }
    var streaming by remember { mutableStateOf(false) }
    LaunchedEffect(entries, capture, streaming) {
        // While a stream runs, the stream coroutine owns the engine feed
        // (it re-sends with `working = true` after every word); feeding
        // here too would flip the working flag off mid-stream.
        if (!capture && !streaming) state.engine.setDebugEntries(entries, working = false)
    }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.BottomEnd)) {
            Box(
                Modifier
                    .clickable(enabled = !streaming) {
                        if (streaming) return@clickable // unreachable: disabled
                        streaming = true
                        // The iOS lab's `send`: two new entries (the ids
                        // cannot collide with `u1`/`a1`/`u2` or `x<n>`),
                        // then the timer feeds the words. The rows first
                        // reach the engine with the first word, 45 ms in.
                        val n = entries.size
                        entries = entries + listOf(
                            DebugEntry(
                                id = "u$n",
                                user = true,
                                text = "Stream the plan again, slowly.",
                                streaming = false,
                            ),
                            DebugEntry(id = "a$n", user = false, text = "", streaming = true),
                        )
                        scope.launch {
                            // Split keeps empty pieces, like Swift's
                            // `split(separator: " ", omittingEmptySubsequences: false)`.
                            val words = HostFixture[1].text.split(" ")
                            var i = 0
                            while (i < words.size) {
                                delay(45)
                                val step = Random.nextInt(2, 6) // 2...5, like Int.random(in: 2...5)
                                val next = words.subList(i, minOf(words.size, i + step))
                                    .joinToString(" ")
                                i += step
                                val lastEntry = entries.last()
                                val grown = lastEntry.copy(
                                    text = lastEntry.text + (if (lastEntry.text.isEmpty()) "" else " ") + next,
                                    streaming = i < words.size,
                                )
                                entries = entries.toMutableList().also { it[it.lastIndex] = grown }
                                streaming = grown.streaming
                                state.engine.setDebugEntries(entries, working = grown.streaming)
                            }
                        }
                    }
                    .padding(16.dp),
            ) {
                BasicText(
                    "Stream",
                    style = TextStyle(color = if (streaming) palette.tertiary else palette.accent),
                )
            }
            Box(
                Modifier
                    .clickable {
                        appended++
                        entries = entries + DebugEntry(
                            id = "x$appended",
                            user = false,
                            text = "Appended row $appended.",
                            streaming = false,
                        )
                    }
                    .padding(16.dp),
            ) {
                BasicText("Append row", style = TextStyle(color = palette.accent))
            }
        }
    }
}
