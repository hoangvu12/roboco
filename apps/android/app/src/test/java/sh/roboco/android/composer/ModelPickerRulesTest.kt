package sh.roboco.android.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave-10 38's picker rules (upstream #966 `ModelPickerViewController`)
 * on the plain JVM: dedupe, the search threshold and filter, the empty
 * lines, the switcher's short Claude, the header line, the first page and
 * the page notices.
 */
class ModelPickerRulesTest {

    private fun row(id: String, label: String = id) = ModelPickerRow(id = id, label = label, detail = null)

    @Test
    fun dedupeKeepsTheFirstOfEachId() {
        assertEquals(listOf("a", "b"), dedupeModels(listOf(row("a", "A1"), row("b"), row("a", "A2"))).map { it.id })
        assertEquals("A1", dedupeModels(listOf(row("a", "A1"), row("a", "A2"))).single().label)
    }

    @Test
    fun searchShowsPastTwelveAndMatchesNameOrIdCaseFolded() {
        assertFalse(showsModelSearch((1..12).map { row("m$it") }))
        assertTrue(showsModelSearch((1..13).map { row("m$it") }))
        val rows = listOf(row("gpt-5", "GPT 5"), row("claude-fable-5", "Fable 5"), row("o4-mini", "o4 mini"))
        assertEquals(listOf("claude-fable-5"), filterModels(rows, "  FABLE ").map { it.id })
        assertEquals(listOf("o4-mini"), filterModels(rows, "o4-").map { it.id })
        assertEquals(rows, filterModels(rows, "   "))
        assertEquals(ModelPickerCopy.NoModels, modelEmptyText(""))
        assertEquals(ModelPickerCopy.NoMatches, modelEmptyText("zzz"))
        assertEquals("Search Codex models", ModelPickerCopy.searchPlaceholder("Codex"))
    }

    @Test
    fun theHeaderTheSwitcherAndTheFirstPage() {
        val claude = ModelPickerTab("claude-code", "Claude Code")
        val codex = ModelPickerTab("codex", "Codex", footer = "Your next message continues this chat with Codex.")
        assertEquals("Claude Code · Studio", pickerSubtitle(listOf(claude), "Studio"))
        assertEquals("Studio", pickerSubtitle(listOf(claude, codex), "Studio"))
        assertEquals("Claude", switcherTitle("Claude Code"))
        assertEquals("Codex", switcherTitle("Codex"))
        assertEquals(1, initialPickerPage(listOf(claude, codex), "codex"))
        assertEquals(0, initialPickerPage(listOf(claude, codex), "pi"))
        assertEquals("note", pickerPageNotice(claude, page = 0, initial = 0, note = "note"))
        assertNull(pickerPageNotice(claude, page = 0, initial = 1, note = "note"))
        assertEquals(codex.footer, pickerPageNotice(codex, page = 1, initial = 0, note = "note"))
    }
}
