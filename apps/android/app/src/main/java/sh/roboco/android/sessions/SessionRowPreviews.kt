package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The session-row fixtures (android-ui-parity #37): the ticket's ten
 * gallery rows plus the four state rows (pressed / current). Every row
 * uses `projectName = "zeron"`, `colorIndex = 6`, `pinned = false`.
 */
internal object SessionRowFixtures {
    private fun row(
        title: String,
        harness: String?,
        status: SessionRowVM.Status,
        unseen: Boolean,
        sendFailed: Boolean,
        timeLabel: String,
    ): SessionRowVM = SessionRowVM(
        id = title.take(8),
        title = title,
        projectName = "zeron",
        hasProject = true,
        colorIndex = 6,
        harness = harness,
        branch = null,
        pr = null,
        prNumber = null,
        status = status,
        timeLabel = timeLabel,
        unseen = unseen,
        pinned = false,
        sendFailed = sendFailed,
    )

    val all: List<Pair<String, SessionRowVM>> = listOf(
        "done-unseen" to row("Tool group header colors", "codex", SessionRowVM.Status.Completed, true, false, "4h"),
        "failed" to row("Durable Object hibernation flush", "claude-code", SessionRowVM.Status.Errored, true, false, "33m"),
        "working" to row("Streaming veil on transcript rows", "cursor", SessionRowVM.Status.Working, false, false, "now"),
        "input" to row("Model picker catalog sync", "claude-code", SessionRowVM.Status.Awaiting, true, false, "2m"),
        "send-failed" to row("Retry the flaky upload", "codex", SessionRowVM.Status.Working, false, true, "now"),
        "completed-seen" to row("Keyboard avoidance for composer", "claude-code", SessionRowVM.Status.Completed, false, false, "5h"),
        "idle-seen" to row("Wrangler deploy hygiene", null, SessionRowVM.Status.Idle, false, false, "1d"),
        "cjk" to row("多言語テキストのレイアウト", "grok", SessionRowVM.Status.Idle, false, false, "10m"),
        "overflow" to row(
            "A very long session title that keeps on going well past the corner word",
            "pi",
            SessionRowVM.Status.Working,
            true,
            false,
            "now",
        ),
        "no-mark" to row("Empty harness string draws no mark", "", SessionRowVM.Status.Idle, false, false, "2d"),
    )

    /** One fixture by key (47's lifted-row preview and golden use `"done-unseen"`). */
    fun sample(key: String): SessionRowVM = all.first { it.first == key }.second
}

/**
 * The "Session row" page of the design gallery (android-ui-parity #37):
 * every fixture row with its caption, then the four state rows built from
 * "idle-seen" — pressed, current, current+pressed (the grey fill replaces
 * `rowActive`, as on iOS) and current at a mid-fade. The press/fade
 * *timing* itself is ticket 41's; this page only draws the values.
 *
 * Registered in `Gallery.sections` as
 * `GallerySection("Session row") { SessionRowGallerySection() }` — the
 * gallery frame scrolls it.
 */
@Composable
fun SessionRowGallerySection() {
    val p = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val idleSeen = SessionRowFixtures.all.first { it.first == "idle-seen" }.second
    Column(Modifier.background(p.background)) {
        for ((caption, vm) in SessionRowFixtures.all) {
            SessionRow(vm)
            Caption(caption)
        }
        Caption("state rows — idle-seen")
        SessionRow(idleSeen, highlight = { 1f })
        Caption("pressed")
        SessionRow(idleSeen, isCurrent = true)
        Caption("current")
        SessionRow(idleSeen, isCurrent = true, highlight = { 1f })
        Caption("current+pressed")
        SessionRow(idleSeen, isCurrent = true, highlight = { 0.5f })
        Caption("current, mid-fade")
    }
}

/** Row caption: mono 11 pt fixed in `tertiary`, 20 dp start padding. */
@Composable
private fun Caption(text: String) {
    BasicText(
        text,
        style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.tertiary),
        modifier = Modifier.padding(start = 20.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Session row – Light", widthDp = 411)
@Composable
private fun SessionRowLightPreview() {
    RobocoTheme(Appearance.Light) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            SessionRowGallerySection()
        }
    }
}

@Preview(name = "Session row – Dark", widthDp = 411, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionRowDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        ProvideGlyphClock(frozenAtNanos = 0L) {
            SessionRowGallerySection()
        }
    }
}
