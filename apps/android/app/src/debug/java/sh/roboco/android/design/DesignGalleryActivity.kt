package sh.roboco.android.design

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.util.Locale
import sh.roboco.android.core.Face

private const val TAG = "RobocoGallery"

private const val EXTRA_SECTION = "section"
private const val EXTRA_APPEARANCE = "appearance"
private const val EXTRA_REDUCE = "reduce"

/**
 * The debug-only design gallery (android-ui-parity #22): every design part
 * of this set in isolation, in light and dark, with reduce motion forced on
 * or off. Exists only in the debug build — `src/debug` + the debug manifest.
 *
 * Reached by `adb shell am start -n sh.roboco.android/.design.DesignGalleryActivity`
 * or through `MainActivity --es route gallery` ([GalleryLaunch]). Like iOS's
 * debug Transcript Lab (`-lab`), it is a plain debug screen on
 * `Palette.background` with an inline SemiBold 17 title; unlike iOS, Roboco
 * does not ship the gallery at all.
 */
class DesignGalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = GalleryStart.from(intent)
        if (savedInstanceState == null && start.reduce != null) {
            ReduceMotion.debugOverride = start.reduce
        }
        if (start.section != null && Gallery.section(start.section) == null) {
            Log.w(TAG, "No gallery section named ${start.section}")
        }
        setContent { GalleryApp(start) }
    }

    override fun onDestroy() {
        // debugOverride is process-wide; never stay forced after leaving the
        // gallery. Rotation (isFinishing == false) keeps it.
        if (isFinishing) ReduceMotion.debugOverride = null
        super.onDestroy()
    }
}

/**
 * The parsed launch extras: a section (name or slug — resolution happens in
 * [Gallery]), the appearance, and an optional reduce-motion override.
 */
data class GalleryStart(val section: String?, val appearance: Appearance, val reduce: Boolean?) {
    companion object {
        fun from(intent: Intent): GalleryStart = GalleryStart(
            section = intent.getStringExtra(EXTRA_SECTION),
            appearance = when (intent.getStringExtra(EXTRA_APPEARANCE)?.lowercase(Locale.ROOT)) {
                "light" -> Appearance.Light
                "dark" -> Appearance.Dark
                else -> Appearance.System
            },
            // `--ez reduce false` forces full motion; absent = follow the system.
            reduce = if (intent.hasExtra(EXTRA_REDUCE)) intent.getBooleanExtra(EXTRA_REDUCE, false) else null,
        )
    }
}

/**
 * The gallery screen: top bar (title, appearance segments, Force reduce
 * motion, status line) above the index or the open section. The appearance
 * choice is instant, survives rotation and is never persisted.
 */
@Composable
fun GalleryApp(start: GalleryStart) {
    var appearance by rememberSaveable { mutableIntStateOf(start.appearance.storedValue) }
    var selected by rememberSaveable { mutableStateOf(Gallery.section(start.section)?.name) }

    GalleryProviders(Appearance.fromStored(appearance)) {
        val dark = RobocoTheme.isDark
        val view = LocalView.current
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        val section = selected?.let(Gallery::section)
        BackHandler(enabled = selected != null) { selected = null }

        Column(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            GalleryTopBar(
                title = section?.name ?: "Design Gallery",
                sectionOpen = section != null,
                appearance = Appearance.fromStored(appearance),
                onAppearance = { appearance = it.storedValue },
                onBack = { selected = null },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (section == null) {
                    GalleryIndex(onOpen = { selected = it.name })
                } else {
                    // Keyed by name, so switching sections resets the section's state.
                    key(section.name) { GallerySectionFrame(section) }
                }
            }
        }
    }
}

@Composable
private fun GalleryTopBar(
    title: String,
    sectionOpen: Boolean,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    onBack: () -> Unit,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp),
        ) {
            // Row 1: back control + centred title (inline nav title, 44 dp).
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                if (sectionOpen) {
                    BasicText(
                        "‹ Sections",
                        style = uiStyle(Face.SansMedium, typeScale.fixed(17f), palette.accent),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onBack)
                            .semantics { contentDescription = "Sections" },
                        maxLines = 1,
                    )
                }
                BasicText(
                    title,
                    style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .semantics { heading() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            // Row 2: appearance segments and the reduce toggle, 30 dp capsules,
            // horizontally scrollable so narrow phones never clip.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.selectableGroup()) {
                    GallerySegment("System", appearance == Appearance.System) { onAppearance(Appearance.System) }
                    Spacer(Modifier.width(4.dp))
                    GallerySegment("Light", appearance == Appearance.Light) { onAppearance(Appearance.Light) }
                    Spacer(Modifier.width(4.dp))
                    GallerySegment("Dark", appearance == Appearance.Dark) { onAppearance(Appearance.Dark) }
                }
                Spacer(Modifier.width(12.dp))
                GalleryForceToggle()
            }
            Spacer(Modifier.height(6.dp))
            // Row 3: the status line.
            GalleryStatusLine()
        }
        // The bar separator: 1 physical px, full width.
        Box(Modifier.fillMaxWidth().height(hairlineWidth()).background(palette.hairline))
    }
}

/** One System/Light/Dark capsule (`MoreViewController.swift:200-202` copy). */
@Composable
private fun GallerySegment(text: String, selected: Boolean, onSelect: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .height(30.dp)
            .clip(CapsuleShape)
            .background(if (selected) palette.text else palette.controlFill)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = null,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = uiStyle(Face.SansMedium, typeScale.fixed(13f), if (selected) palette.background else palette.text),
            maxLines = 1,
        )
    }
}

/** The process-wide Force reduce motion toggle (two-state: on ↔ follow system). */
@Composable
private fun GalleryForceToggle() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val on = ReduceMotion.debugOverride == true
    Box(
        Modifier
            .height(30.dp)
            .clip(CapsuleShape)
            .background(if (on) palette.accent else palette.controlFill)
            .toggleable(
                value = on,
                interactionSource = null,
                indication = null,
                role = Role.Switch,
                onValueChange = {
                    ReduceMotion.debugOverride = if (ReduceMotion.debugOverride == true) null else true
                },
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            "Force reduce motion",
            style = uiStyle(Face.SansMedium, typeScale.fixed(13f), if (on) rgb(0xFFFFFF) else palette.text),
            maxLines = 1,
        )
    }
}

/**
 * The gallery indicator (row 3): the ticket-04 manual check reads it —
 * `animator_duration_scale 0` must flip it to `reduce=true (system)` live.
 */
@Composable
private fun GalleryStatusLine() {
    val dark = RobocoTheme.isDark
    val reduce = LocalReduceMotion.current
    val suffix = when (ReduceMotion.debugOverride) {
        true -> " (forced)"
        false -> " (forced off)"
        null -> " (system)"
    }
    BasicText(
        String.format(
            Locale.ROOT,
            "dark=%b · reduce=%b%s · factor=%.3f · API %d",
            dark,
            reduce,
            suffix,
            LocalTypeScale.current.factor,
            Build.VERSION.SDK_INT,
        ),
        style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(11f), RobocoTheme.palette.secondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The section index: header plus one 48 dp row per section. */
@Composable
private fun GalleryIndex(onOpen: (GallerySection) -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val sections = Gallery.sections
    val bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        item(key = "header") {
            BasicText(
                if (sections.size == 1) "1 section" else "${sections.size} sections",
                style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.tertiary),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                maxLines = 1,
            )
        }
        itemsIndexed(sections, key = { _, section -> section.name }) { index, section ->
            IndexRow(section) { onOpen(section) }
            if (index < sections.lastIndex) {
                // Separator: hairline, inset 16 at the start; none after the last row.
                Box(
                    Modifier
                        .padding(start = 16.dp)
                        .fillMaxWidth()
                        .height(hairlineWidth())
                        .background(palette.hairline),
                )
            }
        }
    }
}

@Composable
private fun IndexRow(section: GallerySection, onOpen: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onOpen)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            section.name,
            style = uiStyle(Face.SansMedium, typeScale.fixed(16f), palette.text),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Decorative chevron: cleared from semantics so the row's merged
        // text is exactly the section name.
        BasicText(
            "›",
            style = uiStyle(Face.Sans, typeScale.fixed(20f), palette.tertiary),
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

// ---------------------------------------------------------------------------
// Previews: the index in both appearances and the top bar alone.
// ---------------------------------------------------------------------------

@Preview(name = "Gallery index - light")
@Composable
private fun GalleryIndexLightPreview() {
    GalleryProviders(Appearance.Light) {
        GalleryIndex(onOpen = {})
    }
}

@Preview(name = "Gallery index - dark")
@Composable
private fun GalleryIndexDarkPreview() {
    GalleryProviders(Appearance.Dark) {
        GalleryIndex(onOpen = {})
    }
}

@Preview(name = "Gallery top bar")
@Composable
private fun GalleryTopBarPreview() {
    GalleryProviders(Appearance.Light) {
        GalleryTopBar(
            title = "Motion lab",
            sectionOpen = true,
            appearance = Appearance.Dark,
            onAppearance = {},
            onBack = {},
        )
    }
}
