package sh.roboco.android.sessions

import androidx.compose.ui.text.input.KeyboardCapitalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.ActionSheetStyle
import sh.roboco.android.design.AlertButtonRole

/**
 * The pure builders of android-ui-parity #43 (plain JUnit, no Robolectric:
 * the models are data classes over no Android types): every string, role,
 * field option, button order and submit rule of the ticket's Spec tables,
 * the curly-quoted delete title, `trimmedName`'s guard, the sheet action
 * orders and callbacks, and every `newSectionTarget` case.
 */
class SessionPromptModelsTest {

    // ------------------------------------------------------------ the copy

    @Test
    fun theCopyIsVerbatim() {
        assertEquals("Rename Session", SessionPromptCopy.RenameSessionTitle)
        assertEquals("New Section", SessionPromptCopy.NewSectionTitle)
        assertEquals("Name", SessionPromptCopy.NewSectionPlaceholder)
        assertEquals("Rename Section", SessionPromptCopy.RenameSectionTitle)
        assertEquals("Move to Section", SessionPromptCopy.MoveToSectionTitle)
        assertEquals("No Section", SessionPromptCopy.NoSection)
        assertEquals("Its sessions move back to the main list.", SessionPromptCopy.DeleteSectionMessage)
        assertEquals("Delete Section", SessionPromptCopy.DeleteSectionAction)
        assertEquals("Create on", SessionPromptCopy.CreateOnMessage)
        assertEquals("Cancel", SessionPromptCopy.Cancel)
        assertEquals("Create", SessionPromptCopy.Create)
        assertEquals("Rename", SessionPromptCopy.Rename)
        assertEquals("Engine not connected", SessionPromptCopy.EngineNotConnected)
    }

    @Test
    fun theDeleteTitleWrapsTheNameInCurlyQuotes() {
        assertEquals("Delete “Backend”?", SessionPromptCopy.deleteSectionTitle("Backend"))
        // U+201C / U+201D, byte-checked on SessionListController.swift:503.
        assertEquals("Delete \u201CBackend\u201D?", SessionPromptCopy.deleteSectionTitle("Backend"))
    }

    @Test
    fun trimmedNameTrimsAndRejectsBlankText() {
        assertEquals("Deploy", trimmedName("  Deploy  "))
        assertEquals("Deploy", trimmedName("Deploy"))
        assertNull(trimmedName(""))
        assertNull(trimmedName("   "))
        assertNull(trimmedName("\t"))
        // Kotlin's trim() also drops newlines (the ADAPT: pasted newlines).
        assertNull(trimmedName("\n"))
        assertEquals("A B", trimmedName(" A B "))
    }

    // ------------------------------------------------------------ alerts

    /** [SessionPromptCopy.Rename]/[SessionPromptCopy.Create] handlers must pass through this. */
    private val submitted = mutableListOf<String>()

    private fun submitSink(value: String) {
        submitted += value
    }

    @Test
    fun renameSessionAlertMatchesTheSpec() {
        val alert = renameSessionAlert("Fix flaky test", ::submitSink)

        assertEquals(SessionPromptCopy.RenameSessionTitle, alert.title)
        assertNull("no message", alert.message)
        val field = alert.field!!
        assertEquals("Fix flaky test", field.text)
        assertEquals("", field.placeholder)
        assertEquals(KeyboardCapitalization.Sentences, field.capitalization)
        assertTrue("autocorrect on (UITextField default)", field.autocorrect)

        assertEquals(2, alert.buttons.size)
        val cancel = alert.buttons[0]
        val rename = alert.buttons[1]
        assertEquals("Cancel", cancel.title)
        assertEquals(AlertButtonRole.Cancel, cancel.role)
        assertEquals("Rename", rename.title)
        assertEquals(AlertButtonRole.Default, rename.role)
        assertTrue("iOS never disables these buttons", !cancel.requiresText && !rename.requiresText)

        // Cancel has no handler; Rename submits the trimmed text once.
        submitted.clear()
        cancel.onClick("  Deploy  ")
        assertTrue("Cancel must not submit", submitted.isEmpty())
        rename.onClick("  Deploy  ")
        assertEquals(listOf("Deploy"), submitted)
    }

    @Test
    fun newSectionAlertMatchesTheSpec() {
        val alert = newSectionAlert(::submitSink)

        assertEquals(SessionPromptCopy.NewSectionTitle, alert.title)
        assertNull("no message", alert.message)
        val field = alert.field!!
        assertEquals("", field.text)
        assertEquals("Name", field.placeholder)
        assertEquals(KeyboardCapitalization.Words, field.capitalization)
        assertTrue("autocorrect on (UITextField default)", field.autocorrect)

        assertEquals(2, alert.buttons.size)
        assertEquals("Cancel", alert.buttons[0].title)
        assertEquals(AlertButtonRole.Cancel, alert.buttons[0].role)
        assertEquals("Create", alert.buttons[1].title)
        assertEquals(AlertButtonRole.Default, alert.buttons[1].role)
        assertTrue(!alert.buttons[0].requiresText && !alert.buttons[1].requiresText)

        submitted.clear()
        alert.buttons[1].onClick("  Deploy  ")
        assertEquals(listOf("Deploy"), submitted)
    }

    @Test
    fun renameSectionAlertMatchesTheSpec() {
        val alert = renameSectionAlert("Backend", ::submitSink)

        assertEquals(SessionPromptCopy.RenameSectionTitle, alert.title)
        assertNull("no message", alert.message)
        val field = alert.field!!
        assertEquals("Backend", field.text)
        assertEquals("", field.placeholder)
        assertEquals(KeyboardCapitalization.Sentences, field.capitalization)
        assertTrue(field.autocorrect)

        assertEquals(2, alert.buttons.size)
        assertEquals("Cancel", alert.buttons[0].title)
        assertEquals(AlertButtonRole.Cancel, alert.buttons[0].role)
        assertEquals("Rename", alert.buttons[1].title)
        assertEquals(AlertButtonRole.Default, alert.buttons[1].role)

        submitted.clear()
        alert.buttons[1].onClick("  API ")
        assertEquals(listOf("API"), submitted)
    }

    @Test
    fun blankTextNeverSubmitsFromAnyAlert() {
        for (alert in listOf(
            renameSessionAlert("Fix flaky test", ::submitSink),
            newSectionAlert(::submitSink),
            renameSectionAlert("Backend", ::submitSink),
        )) {
            for (blank in listOf("", "   ", "\t")) {
                submitted.clear()
                alert.buttons[1].onClick(blank)
                assertTrue("blank \"$blank\" must dismiss without a write", submitted.isEmpty())
            }
        }
    }

    // ------------------------------------------------------------ sheets

    private val picked = mutableListOf<String>()

    @Test
    fun moveToSectionSheetMatchesTheSpec() {
        val sheet = moveToSectionSheet(
            listOf(
                SectionChoice("s1", "Backend"),
                SectionChoice("s2", "Design review"),
                SectionChoice("s3", "Infra"),
            ),
        ) { picked += (it ?: "No Section") }

        assertEquals(SessionPromptCopy.MoveToSectionTitle, sheet.title)
        assertNull("no message", sheet.message)
        assertEquals("Cancel", sheet.cancelTitle)
        assertEquals(4, sheet.actions.size)
        val titles = sheet.actions.map { it.title }
        assertEquals(listOf("Backend", "Design review", "Infra", "No Section"), titles)
        assertTrue("no destructive rows", sheet.actions.all { it.style == ActionSheetStyle.Default })
        assertTrue("no checkmark on the current section (not iOS)", sheet.actions.none { it.checked })

        picked.clear()
        sheet.actions[0].onSelect()
        sheet.actions[2].onSelect()
        sheet.actions[3].onSelect()
        assertEquals(listOf("s1", "s3", "No Section"), picked)
    }

    @Test
    fun anEmptySectionListStillOffersNoSection() {
        val sheet = moveToSectionSheet(emptyList()) { picked += (it ?: "No Section") }
        assertEquals(1, sheet.actions.size)
        assertEquals("No Section", sheet.actions[0].title)
        picked.clear()
        sheet.actions[0].onSelect()
        assertEquals(listOf("No Section"), picked)
    }

    @Test
    fun deleteSectionSheetMatchesTheSpec() {
        var deleted = 0
        val sheet = deleteSectionSheet("Backend") { deleted++ }

        assertEquals("Delete “Backend”?", sheet.title)
        assertEquals(SessionPromptCopy.DeleteSectionMessage, sheet.message)
        assertEquals("Cancel", sheet.cancelTitle)
        assertEquals(1, sheet.actions.size)
        val action = sheet.actions[0]
        assertEquals("Delete Section", action.title)
        assertEquals(ActionSheetStyle.Destructive, action.style)
        assertEquals(0, deleted)
        action.onSelect()
        assertEquals(1, deleted)
    }

    @Test
    fun createOnSheetMatchesTheSpec() {
        val sheet = createOnSheet(
            listOf(
                EngineChoice("studio", "Studio Mac", true),
                EngineChoice("linux", "Linux box", true),
                EngineChoice("windows", "Windows desktop", false),
            ),
        ) { picked += it }

        assertEquals(SessionPromptCopy.NewSectionTitle, sheet.title)
        assertEquals(SessionPromptCopy.CreateOnMessage, sheet.message)
        assertEquals("Cancel", sheet.cancelTitle)
        assertEquals(listOf("Studio Mac", "Linux box", "Windows desktop"), sheet.actions.map { it.title })
        assertTrue(sheet.actions.all { it.style == ActionSheetStyle.Default })
        assertTrue("no checked rows", sheet.actions.none { it.checked })

        picked.clear()
        sheet.actions[1].onSelect()
        assertEquals(listOf("linux"), picked)
    }

    // --------------------------------------------------- newSectionTarget

    private fun choice(id: String, connected: Boolean) = EngineChoice(id, id, connected)

    @Test
    fun oneConnectedEngineGoesStraightToTheAlert() {
        assertEquals(
            NewSectionTarget.Engine("A"),
            newSectionTarget(null, listOf(choice("A", true))),
        )
    }

    @Test
    fun severalConnectedEnginesOfferTheChooserInListOrder() {
        assertEquals(
            NewSectionTarget.Choose(listOf(choice("A", true), choice("B", true))),
            newSectionTarget(null, listOf(choice("A", true), choice("B", true), choice("C", false))),
        )
    }

    @Test
    fun noConnectedEngineToasts() {
        assertEquals(
            NewSectionTarget.NoneConnected,
            newSectionTarget(null, listOf(choice("A", false))),
        )
        assertEquals(NewSectionTarget.NoneConnected, newSectionTarget(null, emptyList()))
    }

    @Test
    fun anExplicitEngineWinsOnlyWhenConnected() {
        assertEquals(
            NewSectionTarget.NoneConnected,
            newSectionTarget("C", listOf(choice("A", true), choice("C", false))),
        )
        assertEquals(
            NewSectionTarget.Engine("A"),
            newSectionTarget("A", listOf(choice("A", true), choice("B", true))),
        )
        // An unknown id counts as not connected.
        assertEquals(
            NewSectionTarget.NoneConnected,
            newSectionTarget("X", listOf(choice("A", true))),
        )
    }
}
