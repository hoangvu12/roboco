package sh.roboco.android.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GroupedHeader
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.GroupedRow
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.SheetHost
import sh.roboco.android.shell.SheetStyle
import sh.roboco.android.shell.rememberSheetHostState

/**
 * The "About & Licenses" page of the debug design gallery
 * (android-ui-parity #101), registered by ticket 22's registry as
 * `GallerySection("About & Licenses", screenshotHeightDp = 1200) { AboutGallerySection() }`
 * (the default `Scroll` layout: the section pads its children; the
 * licences host sits in a fixed-height box because `Scroll` forbids an
 * unbounded vertical scroller).
 *
 * Three parts:
 * - the About card — [AboutGalleryCard]: the "About" header, both rows
 *   built from [aboutRows] with the ticket's version fixture, and a
 *   pressed copy of the Licenses row (`previewPressed`);
 * - "Open Licenses" — pushes [LicensesList] over [AboutGallerySections]
 *   in the section's own `NavStackHost` + `SheetHost`, in a fixed
 *   700 dp box;
 * - "Open licence sheet" — presents [LicenceTextSheet] over the same host.
 */
@Composable
fun AboutGallerySection() {
    val stack = remember { NavStack(AboutGalleryRoot) }
    val host = rememberSheetHostState()
    AboutGalleryCard()
    GalleryButton("Open Licenses") { stack.push(AboutGalleryLicensesPage) }
    GalleryButton("Open licence sheet") {
        host.present("licence:gallery-geist", SheetStyle.Page(grabber = false)) {
            LicenceTextSheet(
                title = "Geist and Geist Mono",
                text = AboutGalleryOflFixture,
                onDone = { dismiss(true) },
            )
        }
    }
    // 22's Scroll rule: the unbounded LazyColumn sits in a fixed-height
    // host, not in the scrolling gallery column.
    Box(Modifier.fillMaxWidth().height(700.dp)) {
        SheetHost(host) {
            NavStackHost(stack = stack, active = true) { entry ->
                when (entry.route) {
                    is AboutGalleryLicensesPage -> LicensesList(
                        sections = AboutGallerySections,
                        onOpen = rememberLicencePresenter(),
                        topInset = 24.dp,
                    )

                    else -> GalleryRootPage()
                }
            }
        }
    }
}

/**
 * The About card (the gallery fixture and the `about_rows` goldens): the
 * "About" header, the Version and Licenses rows from [aboutRows] at the
 * ticket's version fixture (`0.9.2` / 9002 / core `0.9.2`), then a
 * `pressed` caption and a pressed copy of the Licenses row, like 70's
 * pressed demo.
 */
@Composable
fun AboutGalleryCard() {
    val rows = remember { aboutRows(versionSubtitle("0.9.2", 9002L, CoreStatus.Loaded("0.9.2"))) }
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(Modifier.fillMaxWidth()) {
        GroupedHeader(AboutSectionTitle)
        GroupedRow(
            title = rows[0].title,
            position = GroupedPosition.First,
            subtitle = rows[0].subtitle,
            icon = rows[0].icon,
            accessories = rows[0].accessories,
            testTag = "gallery-${rows[0].id}",
            onClick = null,
        )
        GroupedRow(
            title = rows[1].title,
            position = GroupedPosition.Last,
            subtitle = rows[1].subtitle,
            icon = rows[1].icon,
            accessories = rows[1].accessories,
            testTag = "gallery-${rows[1].id}",
            onClick = null,
        )
        BasicText(
            "pressed",
            style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 4.dp),
        )
        GroupedRow(
            title = rows[1].title,
            position = GroupedPosition.Single,
            subtitle = rows[1].subtitle,
            icon = rows[1].icon,
            accessories = rows[1].accessories,
            testTag = "gallery-${rows[1].id}-pressed",
            previewPressed = true,
            onClick = null,
        )
    }
}

/** The gallery's pushed Licenses page (not [LicensesRoute]: no app assets here). */
private data object AboutGalleryLicensesPage : NavRoute {
    override val paneTitle: String get() = "Licenses"
}

/** The gallery's root page. */
private data object AboutGalleryRoot : NavRoute {
    override val paneTitle: String get() = "About gallery"
}

@Composable
private fun GalleryRootPage() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(Modifier.fillMaxSize().background(palette.background)) {
        BasicText(
            "About gallery root — use \"Open Licenses\"",
            style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** The gallery button row, like the debug gallery's `GalleryButton`s. */
@Composable
private fun GalleryButton(text: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------
// Fixtures (the gallery, the previews and the goldens share them)
// ---------------------------------------------------------------------------

/**
 * The ticket's fixture sections: the exact entries and subtitles of §B's
 * tables, with short licence texts (the sheet demo).
 */
val AboutGallerySections: List<LicenceSection> = listOf(
    LicenceSection(
        key = "roboco",
        title = "Roboco",
        entries = listOf(
            LicenceEntry(
                id = "roboco",
                title = "Roboco",
                subtitle = "MIT License",
                header = null,
                texts = listOf(AboutGalleryMitFixture),
            ),
            LicenceEntry(
                id = "notices",
                title = ThirdPartyNotices,
                subtitle = null,
                header = null,
                texts = listOf("Roboco's third-party notices (fixture)."),
            ),
        ),
    ),
    LicenceSection(
        key = "assets",
        title = "Fonts and assets",
        entries = listOf(
            LicenceEntry(
                id = "geist",
                title = "Geist and Geist Mono",
                subtitle = "SIL Open Font License 1.1",
                header = null,
                texts = listOf(AboutGalleryOflFixture),
            ),
            LicenceEntry(
                id = "symbols",
                title = "Symbols file icons",
                subtitle = "MIT License",
                header = null,
                texts = listOf(SymbolsHeader, AboutGallerySymbolsFixture),
            ),
            LicenceEntry(
                id = "solar",
                title = "Solar Icons",
                subtitle = "CC BY 4.0",
                header = null,
                texts = listOf(SolarNotice),
            ),
        ),
    ),
    LicenceSection(
        key = "android",
        title = "Android libraries",
        entries = listOf(
            LicenceEntry(
                id = "android:androidx.compose.foundation:foundation",
                title = "Compose Foundation",
                subtitle = "1.10.0 · Apache License 2.0",
                header = "Compose Foundation 1.10.0\nhttps://developer.android.com/jetpack/compose",
                texts = listOf(AboutGalleryApacheFixture),
            ),
            LicenceEntry(
                id = "android:net.java.dev.jna:jna",
                title = "JNA",
                subtitle = "5.19.1 · Apache License 2.0, GNU Lesser General Public License v2.1 or later",
                header = "JNA 5.19.1\nhttps://github.com/java-native-access/jna",
                texts = listOf(AboutGalleryApacheFixture),
            ),
            LicenceEntry(
                id = "android:sh.calvin.reorderable:reorderable",
                title = "Reorderable",
                subtitle = "3.1.0 · Apache License 2.0",
                header = "Reorderable 3.1.0",
                texts = listOf(AboutGalleryApacheFixture),
            ),
        ),
    ),
    LicenceSection(
        key = "rust",
        title = "Rust core",
        entries = listOf(
            LicenceEntry(
                id = "rust:anyhow@1.0.104",
                title = "anyhow",
                subtitle = "1.0.104 · MIT OR Apache-2.0",
                header = "anyhow 1.0.104\nhttps://github.com/dtolnay/anyhow",
                texts = listOf(AboutGalleryMitFixture, AboutGalleryApacheFixture),
            ),
            LicenceEntry(
                id = "rust:serde@1.0.229",
                title = "serde",
                subtitle = "1.0.229 · MIT OR Apache-2.0",
                header = "serde 1.0.229\nhttps://github.com/serde-rs/serde",
                texts = listOf(AboutGalleryMitFixture, AboutGalleryApacheFixture),
            ),
            LicenceEntry(
                id = "rust:uniffi@0.32.2",
                title = "uniffi",
                subtitle = "0.32.2 · MPL-2.0",
                header = "uniffi 0.32.2",
                texts = listOf(AboutGalleryMplFixture),
            ),
        ),
    ),
)

/** A short MIT text standing in for the real licence files. */
internal const val AboutGalleryMitFixture =
    "MIT License (fixture)\n\nCopyright (c) 2026 fixture\n\n" +
    "Permission is granted for this gallery fixture text."

/** A short Apache-2.0 stand-in. */
internal const val AboutGalleryApacheFixture =
    "Apache License 2.0 (fixture)\n\nCopyright 2026 fixture\n\n" +
    "Licensed under the Apache License, Version 2.0 (fixture text)."

/** A short MPL-2.0 stand-in. */
internal const val AboutGalleryMplFixture = "Mozilla Public License 2.0 (fixture)."

/** A short OFL stand-in — the licence-sheet demo's text. */
internal const val AboutGalleryOflFixture =
    "SIL Open Font License 1.1 (fixture)\n\nCopyright 2024 fixture\n\n" +
    "This font software is licensed under the SIL Open Font License, Version 1.1 (fixture text)."

/** A short Symbols license stand-in. */
internal const val AboutGallerySymbolsFixture = "The Symbols icon set is MIT licensed (fixture)."

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "light")
@Composable
private fun AboutGalleryCardLightPreview() {
    RobocoTheme(Appearance.Light) { AboutGalleryCard() }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutGalleryCardDarkPreview() {
    RobocoTheme(Appearance.Dark) { AboutGalleryCard() }
}

@Preview(name = "light")
@Composable
private fun AboutGalleryLicensesLightPreview() {
    RobocoTheme(Appearance.Light) { LicensesListPreviewHost() }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutGalleryLicensesDarkPreview() {
    RobocoTheme(Appearance.Dark) { LicensesListPreviewHost() }
}

@Preview(name = "light")
@Composable
private fun AboutGallerySheetLightPreview() {
    RobocoTheme(Appearance.Light) {
        LicenceTextSheet("Geist and Geist Mono", AboutGalleryOflFixture, onDone = {})
    }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutGallerySheetDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        LicenceTextSheet("Geist and Geist Mono", AboutGalleryOflFixture, onDone = {})
    }
}

/** [LicensesList] in a pushed [NavStackHost] entry, as the goldens host it. */
@Composable
private fun LicensesListPreviewHost() {
    val stack = remember { NavStack(AboutGalleryRoot).apply { push(AboutGalleryLicensesPage) } }
    NavStackHost(stack = stack, active = true) { entry ->
        when (entry.route) {
            is AboutGalleryLicensesPage -> LicensesList(
                sections = AboutGallerySections,
                onOpen = {},
                topInset = 24.dp,
            )

            else -> GalleryRootPage()
        }
    }
}
