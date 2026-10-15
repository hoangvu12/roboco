package sh.roboco.android.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.core.Face
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.design.uiStyle
import sh.roboco.android.sessions.SessionsScreenActions
import sh.roboco.android.sessions.sessionsOptionsMenu

/**
 * The "Sidebar toolbar" page of the debug design gallery (ticket 107),
 * registered in ticket 22's `Gallery.sections` as
 * `GallerySection("Sidebar toolbar", GalleryLayout.Scroll,
 * screenshotHeightDp = 900) { SidebarToolbarGallerySection() }`.
 *
 * Five 360 × 200 dp frames, each on ticket 22's striped busy backdrop
 * ([GlassStage] — the toolbar is the backdrop's sibling, never inside the
 * hazeSource it blurs) with 105's slot geometry mimicked (full width ×
 * 44 dp, bottom 24 dp above the stage):
 *
 *  - (a) at rest — the `[gear ⋯]` capsule and the accent `+`;
 *  - (b) `visibility = 0.5` — the mid-fade of a folder push (the row's
 *    alpha; taps are blocked there);
 *  - (c) the ellipsis held pressed (a toggle emits a fake
 *    `PressInteraction.Press` into the slot's interaction source: 07/08's
 *    swell + highlight on the slot, the capsule still);
 *  - (d) the ⋯ menu, opened **from the ellipsis** through a real
 *    [GlassMenuHost] with ticket 46's [sessionsOptionsMenu] over a
 *    recorder — tap ⋯ to open it, tap a row to watch the recorder's log
 *    line change (light/dark and Force-reduce-motion come from the
 *    gallery's own toggles);
 *  - (e) RTL — the capsule mirrors to the right, the gear rightmost, the
 *    `+` on the left.
 */
@Composable
fun SidebarToolbarGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ToolbarFrame("(a) at rest") { ToolbarAtRest() }
        ToolbarFrame("(b) visibility 0.5 — the folder-push fade") {
            SidebarToolbarContent(
                visibility = { 0.5f },
                onSettings = {},
                onOptions = {},
                onNewSession = {},
            )
        }
        PressedOptionsFrame()
        MenuFrame()
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ToolbarFrame("(e) RTL — capsule right, gear rightmost, + left") { ToolbarAtRest() }
        }
    }
}

/** Frames (a)/(e)'s slot content: the toolbar at rest, nothing wired. */
@Composable
private fun ToolbarAtRest() {
    SidebarToolbarContent(
        visibility = { 1f },
        onSettings = {},
        onOptions = {},
        onNewSession = {},
    )
}

/** One captioned frame: the caption plus [ToolbarStage]'s 360 × 200 stage. */
@Composable
private fun ToolbarFrame(caption: String, content: @Composable BoxScope.() -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(caption, style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary))
        ToolbarStage(content = content)
    }
}

/**
 * The 360 × 200 dp stage: ticket 22's backdrop carrying the `hazeSource`
 * the capsule's glass samples, the toolbar slot (105's placement mimicked)
 * as its sibling, and [overlay] (the pressed frame's toggle) placed inside
 * the stage, above the slot.
 */
@Composable
private fun ToolbarStage(
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val haze = rememberHazeState()
    CompositionLocalProvider(LocalHazeState provides haze) {
        GlassStage(
            height = 200.dp,
            backdropModifier = Modifier.hazeSource(haze),
        ) {
            overlay()
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .height(SidebarToolbarDefaults.ItemSize),
            ) {
                content()
            }
        }
    }
}

/**
 * Frame (c): the ellipsis held pressed. A "Hold to press" toggle emits a
 * fake [PressInteraction.Press] into the toolbar's [optionsSource]
 * (ticket 08's gallery convention), so the slot's swell and highlight can
 * be inspected hands-free — including with Force reduce motion on (no
 * scale, the highlight snapping in).
 */
@Composable
private fun PressedOptionsFrame() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val toggleStyle = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent)
    val optionsSource = remember { MutableInteractionSource() }
    var holding by remember { mutableStateOf(false) }
    var press by remember { mutableStateOf<PressInteraction.Press?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            "(c) the ellipsis held pressed (07/08's glassPress, the capsule still)",
            style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
        )
        ToolbarStage(
            overlay = {
                BasicText(
                    text = if (holding) "Release" else "Hold to press",
                    style = toggleStyle,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = {
                                val p = press
                                if (p == null) {
                                    PressInteraction.Press(Offset.Zero).also {
                                        press = it
                                        optionsSource.tryEmit(it)
                                    }
                                } else {
                                    optionsSource.tryEmit(PressInteraction.Release(p))
                                    press = null
                                }
                                holding = !holding
                            },
                        ),
                )
            },
        ) {
            SidebarToolbarContent(
                visibility = { 1f },
                onSettings = {},
                onOptions = {},
                onNewSession = {},
                optionsSource = optionsSource,
            )
        }
    }
}

/**
 * Frame (d): the real ⋯ flow. The stage sits inside a [GlassMenuHost] and
 * the ellipsis opens ticket 46's options menu (built at tap time over a
 * recorder) anchored to the 44 dp slot — the panel grows up from it,
 * covering the slot. The status line lists the recorder's last call.
 */
@Composable
private fun MenuFrame() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val actions = remember { RecordingActions() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            "(d) the ⋯ menu (46's rows) — tap ⋯ to open it, tap a row to log",
            style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
        )
        BasicText(
            text = actions.calls.lastOrNull() ?: "no calls yet",
            style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.subline),
        )
        GlassMenuHost(hazeState = rememberHazeState()) {
            val menu = rememberGlassMenuState()
            ToolbarStage {
                SidebarToolbarContent(
                    visibility = { 1f },
                    onSettings = {},
                    onOptions = { anchor -> menu.open(anchor, sessionsOptionsMenu(actions)) },
                    onNewSession = {},
                )
            }
        }
    }
}

/**
 * The recorder behind frame (d)'s menu: every [SessionsScreenActions] call
 * becomes one log line (snapshot state, so the caption updates live),
 * nothing else happens.
 */
private class RecordingActions : SessionsScreenActions {
    val calls = mutableStateListOf<String>()
    override val groupByProject: Boolean = false
    override fun setGroupByProject(on: Boolean) {
        calls += "setGroupByProject($on)"
    }
    override fun newSection() {
        calls += "newSection()"
    }
    override fun collapseAll() {
        calls += "collapseAll()"
    }
    override fun toggleSection(id: String) {
        calls += "toggleSection($id)"
    }
    override fun openPinned() {
        calls += "openPinned()"
    }
    override fun openArchived() {
        calls += "openArchived()"
    }
    override fun openSection(id: String, name: String) {
        calls += "openSection($id)"
    }
    override fun renameSection(id: String, currentName: String) {
        calls += "renameSection($id)"
    }
    override fun deleteSection(id: String) {
        calls += "deleteSection($id)"
    }
    override fun userSectionName(id: String): String? = null
}
