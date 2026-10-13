package sh.roboco.android.design

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.roboco.android.core.Face
import sh.roboco.android.shell.LargeTitleScaffold

/** The gallery's fake refresh durations (the ticket's three toggles). */
private val GalleryRefreshDurations = listOf(100L, 2000L, 5000L)

/** Each duration's toggle label. */
private fun durationLabel(ms: Long): String = when (ms) {
    100L -> "0.1 s"
    2000L -> "2 s"
    5000L -> "5 s"
    else -> "$ms ms"
}

/**
 * The "Pull to refresh" page of the debug design gallery (android-ui-parity
 * #48): a real [LargeTitleScaffold] with 20 plain 64-dp rows and a
 * pull-to-refresh state whose fake `refresh()` delays one of three
 * durations — 0.1 s (the spinner holds the 0.6 s floor), 2 s, and 5 s (cut
 * to 3 s by `holdRefresh`'s backstop). Pull the list to watch the gap open
 * with rubber-band resistance, the threshold haptic at 80 dp, the 60-dp
 * hold with the 21-dp cell spinner, and the settle back; the readout at the
 * bottom shows the phase and the last measured spin.
 *
 * Registered in `design/Gallery.kt` (ticket 22) as
 * `GallerySection("Pull to refresh", GalleryLayout.Fill, 891) { PullToRefreshGallerySection() }`
 * — `Fill`, because the scaffold owns the `LazyColumn`. At rest nothing
 * animates, so the gallery golden is byte-stable.
 */
@Composable
fun PullToRefreshGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    var durationMs by remember { mutableStateOf(GalleryRefreshDurations.first()) }
    var lastSpinMs by remember { mutableStateOf<Long?>(null) }
    val state = rememberPullToRefreshState(onRefresh = { delay(durationMs) })

    // The readout's spin measurement: Refreshing → the spinner started,
    // Closing → it ended. Wall clock, not the fake's own duration — the
    // 5 s fake is cut to 3 s by the timing window, and the readout must
    // say so (≈ 600 / 2000 / 3000 ms).
    LaunchedEffect(state) {
        var startedAt = 0L
        snapshotFlow { state.phase }.collect { phase ->
            when (phase) {
                PullPhase.Refreshing -> startedAt = SystemClock.elapsedRealtime()
                PullPhase.Closing -> lastSpinMs = SystemClock.elapsedRealtime() - startedAt
                PullPhase.Idle -> Unit
            }
        }
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp)) {
            for (duration in GalleryRefreshDurations) {
                ToggleText(durationLabel(duration), durationMs == duration) { durationMs = duration }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LargeTitleScaffold(
                title = "Sessions",
                topInset = 0.dp,
                showBack = false,
                onBack = {},
                pullToRefresh = state,
            ) {
                items(20) { index ->
                    Box(Modifier.fillMaxWidth().height(64.dp)) {
                        BasicText(
                            "Row $index",
                            Modifier.padding(start = 20.dp),
                            style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text),
                        )
                    }
                }
            }
        }
        BasicText(
            "phase: ${state.phase} · last spin: ${lastSpinMs?.toString() ?: "—"} ms",
            Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp, end = 16.dp),
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.tertiary),
        )
    }
}

/** A toggle label: `accent` when selected, `secondary` when not. */
@Composable
private fun ToggleText(text: String, checked: Boolean, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(
            Face.SansMedium,
            typeScale.fixed(13f),
            if (checked) palette.accent else palette.secondary,
        ),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = 0.dp, top = 2.dp, bottom = 2.dp, end = 16.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews: light + dark at rest (the pull is a gesture; the preview shows
// the resting scaffold and the "—" readout).
// ---------------------------------------------------------------------------

@Preview(name = "Pull to refresh – light", widthDp = 393, heightDp = 852)
@Composable
private fun PullToRefreshGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            PullToRefreshGallerySection()
        }
    }
}

@Preview(name = "Pull to refresh – dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PullToRefreshGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            PullToRefreshGallerySection()
        }
    }
}
