package sh.roboco.android.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LinearFontScaleDensity
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.PrState
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TypeScale

/**
 * The "Row meta line" fixtures (android-ui-parity #38): rows C, D, E, H
 * and J copy `1-off.png` (tile tones re-checked on the PNG: C/E emerald,
 * D slate, H/J teal), plus X1 (the overflow + merged badge), X2 (the empty
 * branch) and X3 (the draft tone the core cannot produce). Plain
 * [SessionRowVM] literals — no FFI, literal `colorIndex`, never
 * [HomeColorIndex] — and no `Working` status, so no glyph clock is needed.
 */
internal object SessionRowMetaFixtures {
    private fun row(
        id: String,
        title: String,
        harness: String?,
        status: SessionRowVM.Status,
        unseen: Boolean,
        timeLabel: String,
        projectName: String,
        hasProject: Boolean,
        colorIndex: Int,
        branch: String?,
        pr: PrState?,
        prNumber: ULong?,
    ): SessionRowVM = SessionRowVM(
        id = id,
        title = title,
        projectName = projectName,
        hasProject = hasProject,
        colorIndex = colorIndex,
        harness = harness,
        branch = branch,
        pr = pr,
        prNumber = prNumber,
        status = status,
        timeLabel = timeLabel,
        unseen = unseen,
        pinned = false,
        sendFailed = false,
    )

    /** `1-off.png` row C: branch + a closed PR. */
    val c = row(
        "c", "Tool group header colors", "codex", SessionRowVM.Status.Completed, true, "4h",
        "zeron", true, 5, "fix/tool-colors", PrState.Closed, 77u,
    )

    /** Row D: a branch, no PR, slate tile. */
    val d = row(
        "d", "Durable Object hibernation flush", "claude-code", SessionRowVM.Status.Errored, true, "33m",
        "edge", true, 0, "main", null, null,
    )

    /** Row E: an open PR. */
    val e = row(
        "e", "Transcript scroll pinning", "codex", SessionRowVM.Status.Completed, true, "1h",
        "Zeron iOS", true, 5, "scroll-pinning", PrState.Open, 102u,
    )

    /** Row H: project-less — the "Home" tile and the engine device name. */
    val h = row(
        "h", "Clean up ~/Downloads", "claude-code", SessionRowVM.Status.Idle, false, "2h",
        "MacBook Pro", false, 6, null, null, null,
    )

    /** Row J: a project, no branch. */
    val j = row(
        "j", "Draft the launch post", "claude-code", SessionRowVM.Status.Idle, false, "5d",
        "blog", true, 6, null, null, null,
    )

    /** X1: the overflowing meta line + a merged badge. */
    val x1 = row(
        "x1", "Overflowing meta line", "claude-code", SessionRowVM.Status.Idle, false, "1d",
        "a-very-long-project-name-that-runs", true, 2, "feature/an-even-longer-branch-name",
        PrState.Merged, 412u,
    )

    /** X2: the empty branch shows nothing after the name. */
    val x2 = row(
        "x2", "Empty branch", "claude-code", SessionRowVM.Status.Idle, false, "1d",
        "No project", false, 6, "", null, null,
    )

    /** X3: the draft tone (unreachable with today's core). */
    val x3 = row(
        "x3", "Draft tone", "claude-code", SessionRowVM.Status.Idle, false, "1d",
        "roboco", true, 7, "main", PrState.Draft, 9u,
    )

    val all: List<SessionRowVM> = listOf(c, d, e, h, j, x1, x2, x3)
}

/**
 * The "Row meta line" page of the design gallery (android-ui-parity #38):
 * one [Column] with no spacing and no padding, on the palette background,
 * holding full 37 [SessionRow] rows — the meta line comes from the new
 * slot default. Rows abut like iOS rows, so row C sits at section y 0.
 * Then rows C and E again at `k = 1.6` under ticket 22's
 * [LinearFontScaleDensity] (linear, so `factor = min(2, 1.6) = 1.6`
 * exactly) with [LocalTypeScale] re-provided — `RobocoTheme` computed it
 * from the outer density, so the override must replace both locals.
 * Height: 8 × 62 + 2 × 99 = 694 dp, 726 with the Scroll frame's padding
 * → shot height 760.
 *
 * Registered in `Gallery.sections` as
 * `GallerySection("Row meta line", GalleryLayout.Scroll, screenshotHeightDp = 760) { SessionRowMetaGallerySection() }`.
 */
@Composable
fun SessionRowMetaGallerySection() {
    val p = RobocoTheme.palette
    val outer = LocalDensity.current
    Column(Modifier.background(p.background)) {
        for (vm in SessionRowMetaFixtures.all) {
            SessionRow(vm)
        }
        val d = remember(outer.density) { LinearFontScaleDensity(outer.density, 2f) }
        val ts = remember(d) { TypeScale.from(d) }
        CompositionLocalProvider(LocalDensity provides d, LocalTypeScale provides ts) {
            SessionRow(SessionRowMetaFixtures.c)
            SessionRow(SessionRowMetaFixtures.e)
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Row meta – Light", widthDp = 402)
@Composable
private fun SessionRowMetaLightPreview() {
    RobocoTheme(Appearance.Light) {
        SessionRowMetaGallerySection()
    }
}

@Preview(name = "Row meta – Dark", widthDp = 402, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionRowMetaDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        SessionRowMetaGallerySection()
    }
}
