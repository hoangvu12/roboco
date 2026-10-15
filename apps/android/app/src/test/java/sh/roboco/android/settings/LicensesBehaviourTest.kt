package sh.roboco.android.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.BuildConfig
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.SheetHost
import sh.roboco.android.shell.SheetHostState
import sh.roboco.android.shell.SheetStyle
import sh.roboco.android.update.UpdateUiState

/**
 * The About section and Licenses screen behaviour (android-ui-parity
 * #101): the card geometry at the ticket's `w402dp` phone, the
 * merged-semantics contract, the long-press copy (clipboard + haptic +
 * the API-31–32-only toast), the push, the licence list's sections, and
 * the licence sheet's presentation, insets and dismissal — all against
 * [AboutGallerySections] (no Gradle-plugin output needed for the UI; the
 * real data files are [LicensesRepository] below and
 * [AndroidLicenceTextsTest]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h891dp-xxhdpi")
class LicensesBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ fakes

    /** The [Feedback] fake: records every haptic by name. */
    private class RecordingFeedback : Feedback {
        val events = mutableListOf<String>()
        override fun haptic(haptic: Haptic) {
            events += haptic.name
        }

        override fun cue(cue: Cue) {}
    }

    /** The [Toaster] fake: records every `show`. */
    private class RecordingToaster : Toaster {
        val texts = mutableListOf<String>()
        override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
            texts += text
        }
    }

    private val feedback = RecordingFeedback()
    private val toaster = RecordingToaster()

    /** The About test root route. */
    private object AboutRoot : NavRoute {
        override val paneTitle: String get() = "About root"
    }

    /** The Licenses test page route (a pushed Licenses entry). */
    private object LicensesPage : NavRoute {
        override val paneTitle: String get() = "Licenses"
    }

    private lateinit var aboutStack: NavStack
    private val hostState: SheetHostState = SheetHostState()
    private var licensesStack: NavStack = NavStack(AboutRoot)

    // ------------------------------------------------------------ hosts

    /** Hosts the About section in a root NavStackHost entry, like 103 will. */
    private fun hostAbout(
        sdkInt: Int = 33,
        core: CoreStatus = CoreStatus.Loaded("0.9.2"),
    ) {
        aboutStack = NavStack(AboutRoot)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalToaster provides toaster,
                    LocalFeedback provides feedback,
                ) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        NavStackHost(stack = aboutStack, active = true) { entry ->
                            if (entry.route === AboutRoot) {
                                // Remembered in composition, not in the LazyColumn DSL.
                                val about = rememberAboutSectionState(
                                    versionName = "0.9.2",
                                    versionCode = 9002L,
                                    core = core,
                                    sdkInt = sdkInt,
                                    // android-next#04: the middle row follows a fixed
                                    // Idle state, never the app's live update check.
                                    updateState = idleUpdates,
                                )
                                LazyColumn(Modifier.fillMaxSize()) {
                                    aboutSection(about)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Hosts the fixture Licenses list inside a sheet host, pushed. */
    private fun hostLicenses() {
        compose.mainClock.autoAdvance = false
        licensesStack = NavStack(AboutRoot).apply { push(LicensesPage) }
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalToaster provides toaster,
                    LocalFeedback provides feedback,
                ) {
                    SheetHost(hostState) {
                        NavStackHost(stack = licensesStack, active = true) { entry ->
                            if (entry.route === LicensesPage) {
                                LicensesList(
                                    sections = AboutGallerySections,
                                    onOpen = rememberLicencePresenter(),
                                    topInset = 24.dp,
                                )
                            } else {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Runs the clock past every spring (0.5 s) and settles the frame. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
    }

    private fun assertDp(actual: Dp, expected: Dp, what: String, tolerance: Float = 1f) {
        assertTrue(
            "$what: $actual dp, expected $expected ± $tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    /** The About section's update state for every test: Idle (panel E's plain Check row). */
    private val idleUpdates = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)

    /**
     * The Version subtitle for 0.9.2 (9002) and [core]. android-next#04's
     * approved panel F puts the build type after the version on non-release
     * builds ("Roboco 0.9.2 (9002) debug · core 0.9.2"); unit tests run the
     * debug variant, so the marker is part of the expected text.
     */
    private fun subtitle(core: String): String {
        val marker = if (BuildConfig.UPDATE_INSTALLER_ENABLED) "" else " ${BuildConfig.BUILD_TYPE}"
        return "Roboco 0.9.2 (9002)$marker \u00B7 $core"
    }

    private fun rowImage(tag: String): PixelMap =
        compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    // -------------------------------------------------------- geometry

    @Test
    fun theAboutCardMatchesTheGeometryTable() {
        hostAbout()
        val version = compose.onNodeWithTag("settings-about:version").getUnclippedBoundsInRoot()
        val check = compose.onNodeWithTag("settings-about:check").getUnclippedBoundsInRoot()
        val licenses = compose.onNodeWithTag("settings-about:licenses").getUnclippedBoundsInRoot()
        assertDp(version.left, 16.dp, "card left")
        assertDp(version.right, 386.dp, "card right")
        assertDp(heightOf(version), 68.dp, "version row height")
        assertDp(heightOf(licenses), 68.dp, "licenses row height")
        // android-next#04 (approved panel E): with no update known, a
        // title-only "Check for Updates" row (52 dp) sits between Version
        // and Licenses; the three rows form one card.
        assertDp(heightOf(check), 52.dp, "idle check row height (title only)")
        assertDp(check.top - version.bottom, 0.dp, "the check row follows Version in the card")
        assertDp(licenses.top - check.bottom, 0.dp, "Licenses follows the check row in the card")
        // The header block: 27 above + 20.8 line box + 10 below = 57.8, the
        // first row's top; the header text starts 32 from the screen.
        assertDp(version.top, 57.8.dp, "first row top = the header block (27 + 20.8 + 10)")
        val header = compose.onNodeWithText("About").getUnclippedBoundsInRoot()
        assertDp(header.left, 32.dp, "header text left")
        assertDp(header.top, 27.dp, "header text top")

        // The title starts 72 dp from the screen (56 inside the card).
        val title = compose.onNodeWithText("Version", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(title.left, 72.dp, "title left")

        // The separator runs from x = 72 to x = 370 (screen), on the Version
        // row's last pixel row; UIKit separator over white ≈ (198, 198, 200).
        val px = rowImage("settings-about:version")
        val y = (heightOf(version).value * 3).toInt() - 1
        fun gray(x: Int, expected: Int, what: String) {
            val c = px[x, y]
            val e = expected / 255f
            // The separator's blue is 200 (198, 198, 200); the white card's is 255.
            val eBlue = if (expected == 255) 1f else 200 / 255f
            assertTrue(
                "$what at ($x, $y): rgb(${c.red}, ${c.green}, ${c.blue}) expected $expected",
                abs(c.red - e) <= 12f / 255f && abs(c.green - e) <= 12f / 255f && abs(c.blue - eBlue) <= 12f / 255f,
            )
        }
        gray(168, 198, "separator start (56 dp inside the card)")
        gray(150, 255, "left of the separator start")
        // The hairline covers [168, 1062) px (butt caps): 1062 px = 354 dp is
        // its exclusive end, so the last lit pixel is 1061.
        gray(1061, 198, "separator end (16 dp before the card edge)")
        gray(1080, 255, "right of the separator end")
    }

    @Test
    fun theChevronAppearsOnlyOnTheLicensesRow() {
        hostAbout()
        // The chevron band (image-local x ≈ 990..1080): UIKit tertiaryLabel
        // over white ≈ (196, 196, 199).
        fun chevronPixels(tag: String): Int {
            val px = rowImage(tag)
            var count = 0
            for (x in 990..1080) {
                for (y in 40..190) {
                    val c = px[x, y]
                    if (abs(c.red - 196f / 255f) <= 14f / 255f &&
                        abs(c.green - 196f / 255f) <= 14f / 255f &&
                        abs(c.blue - 199f / 255f) <= 14f / 255f
                    ) {
                        count++
                    }
                }
            }
            return count
        }
        assertTrue(
            "the Licenses row draws a chevron (${chevronPixels("settings-about:licenses")} px)",
            chevronPixels("settings-about:licenses") > 20,
        )
        assertEquals("the Version row draws no chevron", 0, chevronPixels("settings-about:version"))
    }

    // ------------------------------------------------------- semantics

    @Test
    fun theVersionRowMergesTitleAndSubtitleAndOffersTheCopyLongClick() {
        hostAbout()
        val node = compose.onNodeWithTag("settings-about:version").fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text } ?: ""
        assertTrue("merged text should contain the title: '$text'", text.contains("Version"))
        assertTrue(
            "merged text should contain the subtitle: '$text'",
            text.contains(subtitle("core 0.9.2")),
        )
        // No Role.Button (a plain tap does nothing), but the long-click
        // action is labelled "Copy".
        assertNull("the Version row is not a button", node.config.getOrNull(SemanticsProperties.Role))
        val longClick = node.config.getOrNull(SemanticsActions.OnLongClick)
        assertNotNull("the Version row offers a long-click action", longClick)
        assertEquals("Copy", longClick?.label)
    }

    @Test
    fun theLicensesRowIsAButton() {
        hostAbout()
        val node = compose.onNodeWithTag("settings-about:licenses").fetchSemanticsNode()
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
    }

    // ------------------------------------------------ the long-press copy

    @Test
    fun tappingVersionDoesNothing() {
        hostAbout(sdkInt = 32)
        compose.onNodeWithTag("settings-about:version").performClick()
        compose.waitForIdle()
        assertNull("no clipboard change", context().getSystemService(ClipboardManager::class.java).primaryClip)
        assertTrue("no toast", toaster.texts.isEmpty())
        assertTrue("no haptic", feedback.events.isEmpty())
        assertEquals("no push", 1, aboutStack.entries.size)
    }

    @Test
    fun longPressingVersionCopiesTheSubtitleWithTheToastOnSdk32() {
        hostAbout(sdkInt = 32)
        compose.onNodeWithTag("settings-about:version").performTouchInput { longClick() }
        compose.waitForIdle()
        val clip = context().getSystemService(ClipboardManager::class.java).primaryClip
        assertNotNull("the copy landed on the clipboard", clip)
        assertEquals("Version", clip!!.description.label.toString())
        assertEquals(subtitle("core 0.9.2"), clip.getItemAt(0).text.toString())
        assertEquals(listOf("LongPress"), feedback.events)
        assertEquals(listOf("Copied"), toaster.texts)
    }

    @Test
    fun longPressingVersionShowsNoToastOnSdk33() {
        hostAbout(sdkInt = 33)
        compose.onNodeWithTag("settings-about:version").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(listOf("LongPress"), feedback.events)
        assertTrue("no toast on API 33+", toaster.texts.isEmpty())
        assertNotNull(context().getSystemService(ClipboardManager::class.java).primaryClip)
    }

    @Test
    fun theCoreFailedSubtitleIsCopiedVerbatim() {
        hostAbout(sdkInt = 32, core = CoreStatus.Failed("boom"))
        compose.onNodeWithText(subtitle("core unavailable")).assertExists()
        compose.onNodeWithTag("settings-about:version").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(
            subtitle("core unavailable"),
            context().getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString(),
        )
    }

    @Test
    fun aFailingClipboardIsLoggedAndSwallowed() {
        val broken = object : ContextWrapper(context()) {
            override fun getSystemService(name: String): Any? = null
        }
        val copied = copyToClipboard(broken, toaster, feedback, sdkInt = 32, text = "t")
        assertFalse("the copy failed", copied)
        // The haptic still fired (before the try), and no toast escaped.
        assertEquals(listOf("LongPress"), feedback.events)
        assertTrue(toaster.texts.isEmpty())
    }

    // ------------------------------------------------------------- push

    @Test
    fun tappingLicensesPushesTheLicensesRoute() {
        hostAbout()
        compose.onNodeWithTag("settings-about:licenses").performClick()
        compose.waitForIdle()
        assertTrue("the Licenses route was pushed", aboutStack.entries.last().route is LicensesRoute)
    }

    // -------------------------------------------------- the licences list

    // A taller window than the class's 891 dp: the fixture list (four
    // sections, the JNA subtitle wrapping) runs past 891 dp, and the lazy
    // list never composes the Rust rows below the fold that this test
    // asserts on. The width (and so every row's layout) is unchanged.
    @Config(sdk = [36], qualifiers = "w402dp-h1600dp-xxhdpi")
    @Test
    fun theLicensesListShowsTheFourSectionsInOrder() {
        hostLicenses()
        settle()
        val headings = compose
            .onAllNodes(SemanticsMatcher("is a heading") { it.config.contains(SemanticsProperties.Heading) })
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
            .filterNot { it == "Licenses" }
        assertEquals(
            listOf("Roboco", "Fonts and assets", "Android libraries", "Rust core"),
            headings,
        )
        // The pushed screen shows the large title and the back circle.
        compose.onNodeWithTag("nav-large-title").assertExists()
        compose.onNodeWithTag("nav-back").assertExists()
        // §B's fixture entries and subtitles.
        compose.onNodeWithText("Third-party notices").assertExists()
        compose.onNodeWithText("Geist and Geist Mono").assertExists()
        compose.onNodeWithText("Symbols file icons").assertExists()
        compose.onNodeWithText("Solar Icons").assertExists()
        compose.onNodeWithText("1.10.0 · Apache License 2.0").assertExists()
        compose.onNodeWithText("1.0.104 · MIT OR Apache-2.0").assertExists()
        compose.onNodeWithTag("licenses-geist").assertExists()
        compose.onNodeWithTag("licenses-rust:anyhow@1.0.104").assertExists()
    }

    @Test
    fun untilLoadedOnlyTheLargeTitleShows() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    NavStackHost(stack = NavStack(LicensesPage), active = true) { _ ->
                        LicensesList(sections = null, onOpen = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-large-title").assertExists()
        compose.onNodeWithTag("licenses-geist").assertDoesNotExist()
    }

    // ------------------------------------------------------ the licence sheet

    @Test
    fun tappingALicenceRowPresentsTheSheetAndDoneDismissesIt() {
        hostLicenses()
        settle()
        compose.onNodeWithTag("licenses-geist").performClick()
        settle()
        compose.onNodeWithTag("licence-sheet").assertExists()
        compose.onNodeWithTag("licence-text").assertExists()
        compose.onNodeWithText("Geist and Geist Mono").assertExists()

        // The text sits 19 dp from the sheet's edges and 16 dp below the
        // 52 dp header row.
        val sheet = compose.onNodeWithTag("licence-sheet").getUnclippedBoundsInRoot()
        val text = compose.onNodeWithTag("licence-text", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(text.left - sheet.left, 19.dp, "text start inset")
        assertDp(sheet.right - text.right, 19.dp, "text end inset")
        assertDp(text.top - sheet.top, 68.dp, "text top = header 52 + 16")

        // Done dismisses; the Licenses list is still there.
        compose.onNodeWithTag("sheet-done").performClick()
        settle()
        compose.onNodeWithTag("licence-sheet").assertDoesNotExist()
        compose.onNodeWithTag("licenses-geist").assertExists()
        assertEquals("the sheet entry is gone", 0, hostState.entries.size)
    }

    @Test
    fun thePresenterStacksOnTheEnclosingSheet() {
        compose.mainClock.autoAdvance = false
        val inner = NavStack(LicensesPage)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                SheetHost(hostState) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background))
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            hostState.present("parent", SheetStyle.Page(grabber = false)) {
                NavStackHost(stack = inner, active = true) { _ ->
                    LicensesList(
                        sections = AboutGallerySections,
                        onOpen = rememberLicencePresenter(),
                        topInset = 0.dp,
                    )
                }
            }
        }
        // On the frozen clock the present() write is only seen by the
        // recomposer once waitForIdle sends the snapshot apply
        // notifications; advancing first would run the frames without it
        // (SheetHostTest's present() helper does the same).
        compose.waitForIdle()
        settle()
        compose.onNodeWithTag("licenses-geist").performClick()
        settle()
        // The licence sheet stacked on the parent, not a second root present.
        assertEquals(listOf("parent", "licence:geist"), hostState.entries.map { it.key })
        compose.onNodeWithTag("licence-sheet").assertExists()
        // Dismissing the licence sheet leaves the parent sheet in place.
        compose.onNodeWithTag("sheet-done").performClick()
        settle()
        assertEquals(listOf("parent"), hostState.entries.map { it.key })
    }

    // --------------------------------------------------------- the repository

    @Test
    fun theRepositoryDropsEveryMissingAssetWithoutCrashing() {
        // System resources carry no app assets and no app raw resources:
        // every licence source is "missing", and load() still returns.
        val system = android.content.res.Resources.getSystem()
        val repository = LicensesRepository(system.assets, system)
        // Every asset-backed entry and section drops. Solar Icons is not
        // asset-backed (§B: the in-code `SolarNotice`, since the notices
        // file does not list Solar), so "Fonts and assets" keeps exactly it.
        assertEquals(
            listOf(
                LicenceSection(
                    key = "assets",
                    title = "Fonts and assets",
                    entries = listOf(
                        LicenceEntry(
                            id = "solar",
                            title = "Solar Icons",
                            subtitle = "CC BY 4.0",
                            header = null,
                            texts = listOf(SolarNotice),
                        ),
                    ),
                ),
            ),
            repository.load(),
        )
    }

    @Test
    fun theModelLoadsTheRepoSectionsOffTheMainThread() = runBlocking {
        val app = context()
        val model = LicensesModel(
            LicensesRepository(app.assets, app.resources),
            CoroutineScope(Dispatchers.Unconfined),
        )
        val sections = withTimeout(10_000) { model.sections.filterNotNull().first() }
        // The repo's own licences (syncRobocoLicenses stages them for the
        // test classpath): the Roboco section, with both entries.
        val roboco = sections.firstOrNull { it.title == "Roboco" }
        assertNotNull("the Roboco section loads from the real assets", roboco)
        assertEquals(2, roboco!!.entries.size)
        // Empty sections never exist.
        assertTrue(sections.all { it.entries.isNotEmpty() })
    }
}
