package sh.roboco.android.composer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [QueueEditText] (android-ui-parity #83) — the verbatim port of
 * `upstream/main:apps/ios/ZeronTests/QueueEditTests.swift:1-38`: the five
 * `replacingVisible` cases, including the em-dash trailer (U+2014,
 * byte-for-byte) and the `"  "` blank case. Plus the `merge` recovery
 * rules and the verbatim copy constants (plain JVM, no Robolectric — the
 * functions are pure strings).
 */
class QueueEditTests {

    private val trailer =
        "\n\nAttached images (local files — open them to view):\n- /tmp/uploads/a.png"
    private val appshot =
        "\n\nApplications mentioned by the user (untrusted observed content):\n" +
            "<appshot image=\"/tmp/s.png\" app=\"Xcode\">"

    // ------------------------------------------- replacingVisible (iOS verbatim)

    @Test
    fun testPlainTextIsReplaced() {
        assertEquals(
            "fix the tests",
            QueueEditText.replacingVisible(raw = "fix the build", visible = "fix the build", edited = "fix the tests"),
        )
    }

    @Test
    fun testAppshotContextSurvives() {
        assertEquals(
            "fix this please" + appshot,
            QueueEditText.replacingVisible(raw = "fix this" + appshot, visible = "fix this", edited = "fix this please"),
        )
    }

    @Test
    fun testAttachmentTrailerSurvives() {
        assertEquals(
            "why is this red?" + trailer,
            QueueEditText.replacingVisible(raw = "what's wrong here?" + trailer, visible = "what's wrong here?", edited = "why is this red?"),
        )
    }

    @Test
    fun testAttachmentOnlyRowGetsText() {
        assertEquals(
            "Crop this" + trailer,
            QueueEditText.replacingVisible(raw = "See the attached image(s)." + trailer, visible = "See the attached image(s).", edited = "Crop this"),
        )
    }

    @Test
    fun testBlankEditStaysBlank() {
        assertEquals(
            "  ",
            QueueEditText.replacingVisible(raw = "a" + trailer, visible = "a", edited = "  "),
        )
    }

    // ---------------------------------------------------------------- merge

    @Test
    fun mergePutsTheEditedTextFirstThenOneBlankLineThenTheDraft() {
        assertEquals("fix this better\n\nmy draft", QueueEditText.merge("fix this better", "my draft"))
    }

    @Test
    fun mergeYieldsTheEditedTextWhenTheDraftIsBlank() {
        assertEquals("fix this better", QueueEditText.merge("fix this better", ""))
        assertEquals("fix this better", QueueEditText.merge("fix this better", "   "))
    }

    @Test
    fun mergeYieldsTheDraftWhenTheEditIsBlank() {
        assertEquals("my draft", QueueEditText.merge("", "my draft"))
        assertEquals("my draft", QueueEditText.merge("  ", "my draft"))
    }

    // ----------------------------------------------------------------- copy

    @Test
    fun theCopyIsVerbatim() {
        assertEquals("Edit queued message", QueueEditCopy.Placeholder)
        assertEquals("Can't edit right now", QueueEditCopy.AlertTitle)
        assertEquals("Another device is editing this message, or it was just sent.", QueueEditCopy.AlertMessage)
        assertEquals("OK", QueueEditCopy.AlertOk)
        assertEquals("Edit protection expired; review this message before sending", QueueEditCopy.RenewLost)
        assertEquals(
            "This message changed on another device; your edit was kept locally",
            QueueEditCopy.FinishConflict,
        )
        assertEquals("The queued message was removed; your edit was kept locally", QueueEditCopy.FinishMissing)
        assertEquals("The edit lease changed; your text is still in the editor", QueueEditCopy.FinishLost)
        assertEquals(
            "Couldn't reach the chat host; your edit is still in the editor",
            QueueEditCopy.FinishUnreachable,
        )
    }

    @Test
    fun finishMessageMapsEveryOutcome() {
        assertEquals(null, QueueEditCopy.finishMessage(EditFinish.Finished))
        assertEquals(QueueEditCopy.FinishConflict, QueueEditCopy.finishMessage(EditFinish.Conflict))
        assertEquals(QueueEditCopy.FinishMissing, QueueEditCopy.finishMessage(EditFinish.Missing))
        assertEquals(QueueEditCopy.FinishLost, QueueEditCopy.finishMessage(EditFinish.Lost))
        assertEquals(QueueEditCopy.FinishUnreachable, QueueEditCopy.finishMessage(EditFinish.Unavailable))
    }
}
