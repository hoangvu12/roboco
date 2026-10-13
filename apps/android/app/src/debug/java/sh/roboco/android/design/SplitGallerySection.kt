package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.shell.AppRouter
import sh.roboco.android.shell.DetailRoot
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.shell.SplitRoot
import sh.roboco.android.shell.TabletShellState
import sh.roboco.android.shell.rememberTabletShellState

/**
 * The "Split layout" page of the debug design gallery (android-ui-parity
 * #33), registered in ticket 22's `Gallery.sections` as
 * `GallerySection("Split layout") { SplitGallerySection() }`.
 *
 * The whole 1280 × 800 dp frame ([SplitRootFixture] — the same fixture the
 * goldens capture) renders at its real size and is scaled down to the
 * section's width (`graphicsLayer`, origin top-start) inside a box of
 * `800 dp × scale`, so the panes' geometry is exact whatever the body's
 * width. The default `Scroll` layout fits: the frame is scaled and its
 * sidebar fixture holds no unbounded scroller.
 *
 * The two text buttons under the frame drive the behaviours: "Toggle
 * sidebar" flips the target (`shell.toggleSidebar()`), and "Set collapsed"
 * flips `shell.isCollapsed` — the one write this ticket allows outside
 * ticket 108's `SideEffect`, so Ctrl+B can be seen being ignored. The log
 * line shows every [AppRouter] call the fake router recorded (press
 * Ctrl+N / Ctrl+Shift+F with the field focused, or with nothing focused
 * and the sink's `onKeyShortcut` path).
 */
@Composable
fun SplitGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val shell = rememberTabletShellState()
    val log = remember { mutableStateListOf<String>() }
    val router = remember { RecordingRouter(log) }

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scale = minOf(1f, maxWidth / 1280.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(800.dp * scale),
            ) {
                SplitRootFixture(
                    shell = shell,
                    router = router,
                    modifier = Modifier
                        .requiredSize(1280.dp, 800.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                )
            }
        }

        Row(
            Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            GalleryTextButton("Toggle sidebar") { shell.toggleSidebar() }
            GalleryTextButton(
                "Set collapsed: ${if (shell.isCollapsed) "on" else "off"}",
            ) { shell.isCollapsed = !shell.isCollapsed }
        }
        BasicText(
            "${log.size} router calls · last: ${log.lastOrNull() ?: "—"}",
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The fixed fixture the gallery and the Roborazzi goldens
 * (`SplitRootScreenshotTest`) share: fixed text, no time-dependent
 * content. The sidebar shows the eight flat rows, the detail the current
 * [DetailRoot] plus a focusable field (to try the hardware shortcuts with
 * focus inside the pane).
 */
@Composable
fun SplitRootFixture(
    shell: TabletShellState,
    router: AppRouter = AppRouter.Noop,
    modifier: Modifier = Modifier,
) {
    SplitRoot(
        shell = shell,
        sidebar = { SplitSidebarFixtureRows() },
        detail = { root -> SplitDetailFixtureContent(root) },
        router = router,
        modifier = modifier,
    )
}

/** The sidebar slot's fixture: 8 flat rows, 44 dp tall, no scroller (22's Scroll-layout rule). */
@Composable
private fun SplitSidebarFixtureRows() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(Modifier.fillMaxSize()) {
        repeat(8) { index ->
            BasicText(
                "Session ${index + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(start = 16.dp),
                style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
            )
        }
    }
}

/** The detail slot's fixture: the current root's description, centred, plus a focusable field. */
@Composable
private fun SplitDetailFixtureContent(root: DetailRoot) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var fieldText by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            root.toString(),
            style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
        )
        BasicTextField(
            value = fieldText,
            onValueChange = { fieldText = it },
            modifier = Modifier
                .padding(top = 16.dp)
                .width(240.dp)
                .testTag("split-detail-field"),
            textStyle = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
        )
    }
}

/** One plain-text button (no Material controls): Regular `secondary`, 14 dp. */
@Composable
private fun GalleryTextButton(label: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        label,
        modifier = Modifier.clickable { onClick() },
        style = uiStyle(Face.Sans, typeScale.fixed(14f), palette.secondary),
    )
}

/** The gallery's fake router: every call becomes one visible log line. */
private class RecordingRouter(private val log: SnapshotStateList<String>) : AppRouter {
    override fun openSession(chatId: String) {
        log.add("openSession($chatId)")
    }

    override fun openSession(chatId: String, handoff: DraftHandoff?) {
        log.add("openSession($chatId, handoff)")
    }

    override fun presentNewSession(prompt: String?) {
        log.add("presentNewSession(${prompt ?: "null"})")
    }

    override fun showSettings() {
        log.add("showSettings()")
    }

    override fun showSearch() {
        log.add("showSearch()")
    }

    override fun showSessionOpenError() {
        log.add("showSessionOpenError()")
    }

    override fun popToFrontPage() {
        log.add("popToFrontPage()")
    }

    override fun sessionDidAppear(chatId: String) {
        log.add("sessionDidAppear($chatId)")
    }

    override fun sessionDidClose(chatId: String) {
        log.add("sessionDidClose($chatId)")
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The ticket's preview body: the fixture at full 1280 × 800 dp, sidebar shown. */
@Composable
private fun SplitRootFixturePreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        SplitRootFixture(rememberTabletShellState())
    }
}

@Preview(name = "Split layout – light", widthDp = 1280, heightDp = 800)
@Composable
private fun SplitLayoutLightPreview() {
    SplitRootFixturePreviewBody(Appearance.Light)
}

@Preview(name = "Split layout – dark", widthDp = 1280, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SplitLayoutDarkPreview() {
    SplitRootFixturePreviewBody(Appearance.Dark)
}
