package sh.roboco.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [SessionChrome] and [baseChrome] (android-ui-parity #51): the
 * default-equality acceptance row, every row of the ticket's
 * worked-examples table (including the empty-but-non-null host name),
 * the composer-field mappings, and the equality rules for `questions`
 * and `uploadProgress`.
 *
 * Plain JUnit 4, no Robolectric: the uniffi records are plain Kotlin
 * data classes whose initialisation touches no JNA.
 */
class SessionChromeTest {

    @Test
    fun defaultsMatchTheIosStruct() {
        assertEquals(
            SessionChrome(
                title = "",
                subtitle = "",
                running = false,
                canSteer = false,
                canWrite = true,
                placeholder = "Message",
                chips = emptyList(),
                banner = SessionChrome.Banner.None,
                questions = null,
                queue = emptyList(),
                uploadProgress = null,
                error = null,
            ),
            SessionChrome(),
        )
        // `error` is derived but nothing renders it (decision COMPOSER-D2);
        // it still participates in equality like every other field.
        assertNotEquals(SessionChrome(), SessionChrome(error = "x"))
    }

    @Test
    fun baseChromeProducesTheWorkedExamplesTable() {
        fun row(project: String?, harness: String?, host: String?): Pair<String, String> {
            val chrome = baseChrome(
                composerState(hostName = host),
                sessionRow(projectName = project, harnessLabel = harness),
            )
            return chrome.subtitle to chrome.placeholder
        }

        assertEquals("roboco @ Vu-PC" to "Message Claude Code", row("roboco", "Claude Code", "Vu-PC"))
        assertEquals("roboco" to "Message Codex", row("roboco", "Codex", null))
        assertEquals("No project @ Vu-PC" to "Message Claude Code", row(null, "Claude Code", "Vu-PC"))

        // The fourth row: no row at all (dangling chat, iOS `app.row` miss).
        val noRow = baseChrome(composerState(hostName = null), null)
        assertEquals("No project", noRow.subtitle)
        assertEquals("Message the agent", noRow.placeholder)
    }

    @Test
    fun anEmptyNonNullHostNameStillAppendsTheAt() {
        // iOS verbatim (`CoreSessionSource.swift:46`): the name is only
        // null-checked, never blank-checked.
        val chrome = baseChrome(
            composerState(hostName = ""),
            sessionRow(projectName = "roboco", harnessLabel = "Claude Code"),
        )
        assertEquals("roboco @ ", chrome.subtitle)
    }

    @Test
    fun baseChromeMapsTheComposerFields() {
        fun chrome(steering: Boolean?) = baseChrome(
            composerState(midTurnSteering = steering),
            sessionRow(projectName = "roboco", harnessLabel = "Claude Code"),
        )

        // midTurnSteering: null / false / true -> false / false / true.
        assertEquals(false, chrome(null).canSteer)
        assertEquals(false, chrome(false).canSteer)
        assertEquals(true, chrome(true).canSteer)

        val running = baseChrome(composerState(turnRunning = true), null)
        assertEquals(true, running.running)

        val progress = baseChrome(composerState(transferProgress = 0.42), null)
        assertEquals(0.42, progress.uploadProgress!!, 0.0)

        val failed = baseChrome(composerState(queueError = "x"), null)
        assertEquals("x", failed.error)

        // The title is copied verbatim — the core's own fallback included.
        val titled = baseChrome(composerState(title = "New session"), null)
        assertEquals("New session", titled.title)

        // Fields this ticket does not fill stay at their defaults.
        val untouched = baseChrome(composerState(), sessionRow())
        assertEquals(emptyList<SessionChrome.QueuedItem>(), untouched.queue)
        assertEquals(null, untouched.questions)
        assertEquals(SessionChrome.Banner.None, untouched.banner)
        assertEquals(true, untouched.canWrite)
    }

    @Test
    fun questionsCompareByRequestIdAndItems() {
        fun question(id: String) = SessionChrome.Question(
            id = id,
            header = "h",
            text = "t",
            options = listOf("a", "b"),
            multiSelect = false,
        )
        val items = listOf(question("q1"))
        val itemsAgain = listOf(question("q1"))

        // Separate list instances, equal contents: equal (iOS `==`).
        assertEquals(
            SessionChrome.Questions("r1", items),
            SessionChrome.Questions("r1", itemsAgain),
        )
        assertEquals(
            SessionChrome(questions = SessionChrome.Questions("r1", items)),
            SessionChrome(questions = SessionChrome.Questions("r1", itemsAgain)),
        )

        // A different requestId: unequal.
        assertNotEquals(
            SessionChrome.Questions("r1", items),
            SessionChrome.Questions("r2", items),
        )
        assertNotEquals(
            SessionChrome(questions = SessionChrome.Questions("r1", items)),
            SessionChrome(questions = SessionChrome.Questions("r2", items)),
        )

        // A different items list: unequal.
        assertNotEquals(
            SessionChrome.Questions("r1", items),
            SessionChrome.Questions("r1", listOf(question("q1"), question("q2"))),
        )
        assertNotEquals(
            SessionChrome(questions = SessionChrome.Questions("r1", items)),
            SessionChrome(questions = SessionChrome.Questions("r1", listOf(question("other")))),
        )

        // null vs non-null.
        assertNotEquals(SessionChrome(questions = null), SessionChrome(questions = SessionChrome.Questions("r1", items)))
    }

    @Test
    fun uploadProgressTakesPartInEquality() {
        // Roboco ADAPT: iOS's hand-written `==` omits uploadProgress, so a
        // progress-only change never re-rendered and the ring (67) froze.
        assertNotEquals(SessionChrome(uploadProgress = 0.4), SessionChrome(uploadProgress = 0.5))
        assertNotEquals(SessionChrome(uploadProgress = 0.4), SessionChrome(uploadProgress = null))
        assertEquals(SessionChrome(uploadProgress = 0.4), SessionChrome(uploadProgress = 0.4))
    }
}
