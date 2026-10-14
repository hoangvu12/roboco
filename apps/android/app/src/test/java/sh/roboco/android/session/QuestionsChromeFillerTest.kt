package sh.roboco.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.roboco_core.InputRequest
import uniffi.roboco_core.UserInputQuestion

/**
 * [QuestionsChromeFiller] — android-ui-parity #91's chrome mapping, the
 * port of `ios:Session/CoreSessionSource.swift:90-94`, pinned on the
 * plain JVM (the FFI records are plain data classes; no JNA is touched):
 * `openInput == null` → `questions == null`, the field-for-field
 * mapping, the dropped `entry_id`, and "the filler changes only
 * `questions`".
 */
class QuestionsChromeFillerTest {

    private val filler = QuestionsChromeFiller()

    private fun input(openInput: InputRequest?): ChromeInput = ChromeInput(
        chatId = "chat-a",
        composer = composerState(chatId = "chat-a").copy(openInput = openInput),
        row = null,
        engine = null,
    )

    private fun wireQuestion(
        id: String = "a",
        header: String = "H",
        question: String = "Q?",
        options: List<String> = listOf("x", "y"),
        multiSelect: Boolean = true,
        prefill: String? = "p",
        multiline: Boolean = true,
    ) = UserInputQuestion(
        id = id,
        header = header,
        question = question,
        options = options,
        multiSelect = multiSelect,
        prefill = prefill,
        multiline = multiline,
    )

    @Test
    fun noOpenInputMeansNoQuestions() {
        val chrome = SessionChrome(title = "T", subtitle = "S")
        val filled = filler.fill(chrome, input(null))
        assertNull(filled.questions)
        assertEquals("nothing else changed", chrome, filled)
    }

    @Test
    fun anOpenInputMapsFieldForField() {
        val request = InputRequest(
            entryId = "e1",
            requestId = "r",
            questions = listOf(wireQuestion()),
        )
        val filled = filler.fill(SessionChrome(), input(request))
        assertEquals(
            SessionChrome.Questions(
                requestId = "r",
                items = listOf(
                    SessionChrome.Question(
                        id = "a",
                        header = "H",
                        text = "Q?",
                        options = listOf("x", "y"),
                        multiSelect = true,
                        prefill = "p",
                        multiline = true,
                    ),
                ),
            ),
            filled.questions,
        )
    }

    @Test
    fun theWireDefaultsPassThroughForNewerEngines() {
        // Roboco's wire fills prefill = null and multiline = false today;
        // the mapping carries both verbatim for a newer engine.
        val request = InputRequest(
            entryId = "e",
            requestId = "r",
            questions = listOf(wireQuestion(prefill = null, multiline = false)),
        )
        val question = filler.fill(SessionChrome(), input(request)).questions!!.items.single()
        assertNull(question.prefill)
        assertEquals(false, question.multiline)
    }

    @Test
    fun twoQuestionsKeepTheirOrder() {
        val request = InputRequest(
            entryId = "e",
            requestId = "r2",
            questions = listOf(
                wireQuestion(id = "q-sync", question = "Which sync strategy?"),
                wireQuestion(id = "q-gates", question = "Which suites?", multiSelect = false),
            ),
        )
        val items = filler.fill(SessionChrome(), input(request)).questions!!.items
        assertEquals(listOf("q-sync", "q-gates"), items.map { it.id })
        assertEquals(listOf("Which sync strategy?", "Which suites?"), items.map { it.text })
        assertEquals(listOf(true, false), items.map { it.multiSelect })
    }

    @Test
    fun theFillerChangesOnlyQuestions() {
        val chrome = SessionChrome(
            title = "T",
            subtitle = "S",
            running = true,
            canSteer = true,
            questions = SessionChrome.Questions("old", emptyList()),
            queue = listOf(SessionChrome.QueuedItem("q1", "text", null)),
        )
        val filled = filler.fill(chrome, input(InputRequest("e", "r", listOf(wireQuestion()))))
        assertEquals("only questions changed", chrome, filled.copy(questions = chrome.questions))
    }
}
