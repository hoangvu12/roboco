package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetPanel
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoAlertCard
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.actionSheetBottomGap
import sh.roboco.android.design.actionSheetMaxCardHeight
import sh.roboco.android.design.actionSheetWidth
import sh.roboco.android.design.rgba

/**
 * Ticket 43's goldens: the five prompt **models** through the stateless
 * visuals of tickets 18/19 — [RobocoAlertCard] and [ActionSheetPanel],
 * both with `hazeState = null` (ticket 07's deterministic fallback fill,
 * same as 18's and 19's own goldens, so they never depend on how
 * Robolectric reports hardware acceleration) — over the gallery backdrop
 * and the 0.2 black scrim. The alert card is centred in the window (16 dp
 * inset); the sheet panel is BottomCenter, the geometry helpers' width
 * (377 on this window), 8 dp above the bottom. Device `w393dp-h852dp-
 * xxhdpi`; Roborazzi paths are relative to `src/test/screenshots`, so the
 * goldens land at `src/test/screenshots/sessions/`. Recorded by the batch
 * run (`:app:recordRoborazziDebug`), verified by `verifyRoborazziDebug`.
 *
 * The settled sizes the AC pins are asserted before the capture: the
 * alert's 320 dp card / 288 × 48 field / 140 × 48 buttons with an 8 dp
 * gap; the move sheet's 268.9 dp action card (header 44.9 + 4 × 56) at
 * 377 wide; the delete sheet's 119.8 dp; the "Create on" sheet's 231.8
 * (header 63.8 + 3 × 56).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SessionPromptsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ fixtures

    /** The alert card centred, as ticket 18's host places it (minus the host). */
    @Composable
    private fun SessionPromptAlertGolden(alert: RobocoAlert, fieldValue: TextFieldValue) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            GalleryBackdrop(Modifier.matchParentSize())
            Box(Modifier.fillMaxSize().background(rgba(0x000000, 0.2)))
            Box(
                Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                RobocoAlertCard(
                    alert = alert,
                    fieldValue = fieldValue,
                    onFieldValueChange = {},
                    onButton = {},
                    onImeDone = {},
                    hazeState = null,
                )
            }
        }
    }

    /** The sheet panel bottom-anchored, as ticket 19's overlay rests it (minus the animation). */
    @Composable
    private fun SessionPromptSheetGolden(model: ActionSheetModel) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            GalleryBackdrop(Modifier.matchParentSize())
            Box(Modifier.fillMaxSize().background(rgba(0x000000, 0.2)))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val stackWidth = actionSheetWidth(maxWidth.value, maxHeight.value).dp
                val maxCardHeight = actionSheetMaxCardHeight(maxHeight.value, 0f, 0f).dp
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = actionSheetBottomGap(0f).dp)
                        .width(stackWidth),
                ) {
                    ActionSheetPanel(
                        model = model,
                        hazeState = null,
                        onAction = {},
                        onCancel = {},
                        maxCardHeight = maxCardHeight,
                    )
                }
            }
        }
    }

    private fun px(dp: Float): Float = with(compose.density) { dp.dp.toPx() }

    /** `getBoundsInRoot` is a [androidx.compose.ui.unit.DpRect]; the assertions compare px. */
    private fun SemanticsNodeInteraction.pxBounds(): Rect = with(compose.density) { getBoundsInRoot().toRect() }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).pxBounds()

    /** Sets the alert fixture, waits for it to settle, asserts nothing yet. */
    private fun setAlert(appearance: Appearance, alert: RobocoAlert, fieldText: String = "") {
        compose.setContent {
            RobocoTheme(appearance) {
                SessionPromptAlertGolden(alert, TextFieldValue(fieldText, TextRange(fieldText.length)))
            }
        }
        compose.waitForIdle()
    }

    /** Sets the sheet fixture, waits for it to settle. */
    private fun setSheet(appearance: Appearance, model: ActionSheetModel) {
        compose.setContent {
            RobocoTheme(appearance) { SessionPromptSheetGolden(model) }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------- Rename Session

    @Test
    fun promptRenameSessionLight() {
        setAlert(Appearance.Light, renameSessionAlert("Fix flaky test") {})
        compose.onRoot().captureRoboImage("sessions/prompt_rename_session_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun promptRenameSessionDark() {
        setAlert(Appearance.Dark, renameSessionAlert("Fix flaky test") {})
        compose.onRoot().captureRoboImage("sessions/prompt_rename_session_dark.png")
    }

    // ------------------------------------------------- New Section

    @Test
    fun promptNewSectionLight() {
        // The empty field: the placeholder "Name" shows in tertiary; the
        // card is 320 wide, the field 288 × 48, the buttons 140 × 48.
        setAlert(Appearance.Light, newSectionAlert {})

        val card = bounds("alert")
        assertEquals(px(320f), card.width, 1f)
        val field = bounds("alert-field")
        assertEquals(px(288f), field.width, 1f)
        assertEquals(px(48f), field.height, 1f)
        val cancel = bounds("alert-cancel")
        val create = bounds("alert-primary")
        assertEquals(px(140f), cancel.width, 1f)
        assertEquals(px(140f), create.width, 1f)
        assertEquals(px(48f), create.height, 1f)
        assertEquals("the 8 dp gap between the two capsules", px(8f), create.left - cancel.right, 1f)
        assertTrue(cancel.left < create.left) // Cancel leads

        compose.onRoot().captureRoboImage("sessions/prompt_new_section_light.png")
    }

    // ------------------------------------------------- Move to Section

    private val moveSheetModel = moveToSectionSheet(
        listOf(
            SectionChoice("s1", "Backend"),
            SectionChoice("s2", "Design review"),
            SectionChoice("s3", "Infra"),
        ),
    ) {}

    @Test
    fun promptMoveToSectionLight() {
        setSheet(Appearance.Light, moveSheetModel)

        // 3 sections + No Section: action card 44.9 + 4 × 56 = 268.9 dp, 377 wide.
        val card = bounds("action-sheet-card")
        assertEquals(px(377f), card.width, 1f)
        assertEquals(px(268.9f), card.height, 1f)
        for (i in 0..3) {
            assertEquals("row $i", px(56f), bounds("action-sheet-row-$i").height, 1f)
        }
        val cancel = bounds("action-sheet-cancel")
        assertEquals(px(56f), cancel.height, 1f)
        assertEquals("the 8 dp gap above the Cancel card", px(8f), cancel.top - card.bottom, 1f)

        compose.onRoot().captureRoboImage("sessions/prompt_move_to_section_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun promptMoveToSectionDark() {
        setSheet(Appearance.Dark, moveSheetModel)
        compose.onRoot().captureRoboImage("sessions/prompt_move_to_section_dark.png")
    }

    // ------------------------------------------------- Delete confirm

    private val deleteSheetModel = deleteSectionSheet("Backend") {}

    @Test
    fun promptDeleteSectionLight() {
        setSheet(Appearance.Light, deleteSheetModel)

        // Title + message header 63.8 + one 56 row = 119.8 dp.
        assertEquals(px(119.8f), bounds("action-sheet-card").height, 1f)

        compose.onRoot().captureRoboImage("sessions/prompt_delete_section_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w393dp-h852dp-night-xxhdpi")
    fun promptDeleteSectionDark() {
        setSheet(Appearance.Dark, deleteSheetModel)
        compose.onRoot().captureRoboImage("sessions/prompt_delete_section_dark.png")
    }

    // ------------------------------------------------- Create on

    @Test
    fun promptCreateOnLight() {
        setSheet(
            Appearance.Light,
            createOnSheet(
                listOf(
                    EngineChoice("studio", "Studio Mac", true),
                    EngineChoice("linux", "Linux box", true),
                    EngineChoice("windows", "Windows desktop", true),
                ),
            ) {},
        )

        // Title + message header 63.8 + 3 × 56 = 231.8 dp.
        assertEquals(px(231.8f), bounds("action-sheet-card").height, 1f)

        compose.onRoot().captureRoboImage("sessions/prompt_create_on_light.png")
    }
}
