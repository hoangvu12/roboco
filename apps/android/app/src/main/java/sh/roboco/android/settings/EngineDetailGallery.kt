package sh.roboco.android.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Engine detail" page of the debug design gallery (android-ui-parity
 * #140): the ticket's five fixtures, each [EngineDetailCards] drawn
 * non-lazily on `Palette.background` with a caption —
 *
 *  1. **connected** — "MacBook Pro", every value row shown, the `success`
 *     dot, no footer;
 *  2. **copied** — fixture 1 with the Device ID value in its "Copied"
 *     `success` state;
 *  3. **revoked** — "Studio" parked `REVOKED`: the `tertiary` dot, the
 *     greyed "Rename…", "Pair again" present, "Last connected 2h ago",
 *     the error footer, and the long tailnet URL fading;
 *  4. **incompatible** — "Build box": "Update required" on the Status
 *     value, the sentence in the footer, **no** Pair again;
 *  5. **notice** — fixture 1 with the newer-engine notice footer.
 *
 * The fixtures are built as [EngineDetailUi] directly — no `EngineView`
 * (no core, no FFI), so the page renders in any host; the Roborazzi
 * goldens of [EngineDetailScreenshotTest] build the same five through
 * `engineDetailUi` over 102's `EngineViewFixtures` and the same fixed
 * clock, so the two stay identical. Registered in ticket 22's
 * `design/Gallery.kt` (debug source set) as
 * `GallerySection("Engine detail", screenshotHeightDp = 3200)
 * { EngineDetailGallerySection() }` — the default `Scroll` layout: no
 * in-window host, every click a no-op. The column is capped at the
 * reference phone's 402 dp width so the cards sit at the reference size.
 * Nothing animates, so the goldens are byte-stable without freezing the
 * clock.
 */
@Composable
fun EngineDetailGallerySection() {
    Column(Modifier.widthIn(max = 402.dp)) {
        engineDetailGalleryFixtures.forEach { fixture ->
            EngineDetailCaption(fixture.label)
            Box(Modifier.fillMaxWidth().background(RobocoTheme.palette.background)) {
                EngineDetailCards(
                    ui = fixture.ui,
                    copied = fixture.copied,
                    forgetPhase = ForgetPhase.Idle,
                    onCopyId = {},
                    onRename = {},
                    onPairAgain = {},
                    onForget = {},
                )
            }
        }
    }
}

/** One gallery block: its caption, its model, and the Device ID copy state. */
private data class EngineDetailFixture(
    val label: String,
    val ui: EngineDetailUi,
    val copied: Boolean = false,
)

/**
 * The five fixtures, `EngineDetailUi`s built directly (UniFFI records
 * need Robolectric — the main-source previews must not construct them).
 * The ticket's fixed clock, 1 760 000 000 000 ms, is baked into the
 * precomputed relative times. Fixtures 2 and 5 derive from fixture 1
 * (`copy`) so the shared fields cannot drift.
 */
private val connectedGalleryUi = EngineDetailUi(
    engineId = "e1",
    title = "MacBook Pro",
    status = "Connected",
    connected = true,
    address = "http://192.168.1.20:27655",
    version = "v0.9.2",
    platform = "macOS",
    lastConnected = null,
    deviceId = "3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95",
    footer = null,
    canRename = true,
    showPairAgain = false,
    // ADR 0011 (ticket 04): a room-joined engine shows its room.
    room = "Home fleet",
)

private val engineDetailGalleryFixtures = listOf(
    EngineDetailFixture(
        label = "fixture \u00b7 connected \u2014 every row, success dot",
        ui = connectedGalleryUi,
    ),
    EngineDetailFixture(
        label = "copied \u2014 the Device ID value after a tap",
        ui = connectedGalleryUi,
        copied = true,
    ),
    EngineDetailFixture(
        label = "revoked \u2014 greyed Rename\u2026, Pair again, error footer",
        ui = EngineDetailUi(
            engineId = "e2",
            title = "Studio",
            status = "Session revoked",
            connected = false,
            address = "https://studio.tail1234.ts.net:27655",
            version = "v0.9.1",
            platform = "Linux",
            lastConnected = "2h ago",
            deviceId = "3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95",
            footer = EngineFooter("invalid credential", FooterTone.Error),
            canRename = false,
            showPairAgain = true,
        room = null,
        ),
    ),
    EngineDetailFixture(
        label = "incompatible \u2014 Update required, no Pair again",
        ui = EngineDetailUi(
            engineId = "e3",
            title = "Build box",
            status = "Update required",
            connected = false,
            address = "http://10.0.0.7:27655",
            version = "v0.8.0",
            platform = "Windows",
            lastConnected = "3d ago",
            deviceId = "3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95",
            footer = EngineFooter(
                "Build box runs an older Roboco (0.8.0). Update Roboco on that device to connect.",
                FooterTone.Error,
            ),
            canRename = false,
            showPairAgain = false,
        room = null,
        ),
    ),
    EngineDetailFixture(
        label = "notice \u2014 the newer-engine footer",
        ui = connectedGalleryUi.copy(
            version = "v0.10.0",
            footer = EngineFooter(
                "MacBook Pro runs Roboco 0.10.0, newer than this app (0.9.2). " +
                    "Everything should keep working; if something looks wrong, update the app.",
                FooterTone.Notice,
            ),
        ),
    ),
)

/** The block caption: Geist 12 fixed, secondary, at the header's inset. */
@Composable
private fun EngineDetailCaption(text: String) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    BasicText(
        text,
        style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
        modifier = Modifier.padding(start = 32.dp, top = 16.dp),
    )
}

@Preview(name = "light")
@Composable
private fun EngineDetailGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            EngineDetailGallerySection()
        }
    }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EngineDetailGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            EngineDetailGallerySection()
        }
    }
}

/** Fixture 1 alone, light (the ticket's preview pair: fixtures 1 and 3). */
@Preview(name = "connected-light")
@Composable
private fun EngineDetailConnectedLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            EngineDetailCards(
                ui = engineDetailGalleryFixtures[0].ui,
                copied = false,
                forgetPhase = ForgetPhase.Idle,
                onCopyId = {},
                onRename = {},
                onPairAgain = {},
                onForget = {},
            )
        }
    }
}

/** Fixture 3 alone, dark. */
@Preview(name = "revoked-dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EngineDetailRevokedDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            EngineDetailCards(
                ui = engineDetailGalleryFixtures[2].ui,
                copied = false,
                forgetPhase = ForgetPhase.Idle,
                onCopyId = {},
                onRename = {},
                onPairAgain = {},
                onForget = {},
            )
        }
    }
}
