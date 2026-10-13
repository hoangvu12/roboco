package sh.roboco.android.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.roboco.android.design.Palette
import sh.roboco.android.design.StatusGlyphKind

/**
 * The corner precedence and its colours/glyphs (android-ui-parity #37):
 * [corner] over all 5 statuses × unseen ∈ {true, false} × sendFailed ∈
 * {true, false} (the ticket's 20 cases), the verbatim words, and every
 * [RowCorner.color] / [RowCorner.glyph] pair against the ticket's literal
 * ARGB table (light and dark).
 *
 * Plain JUnit 4 — [Palette.Light]/[Palette.Dark] are pure singletons and
 * the glyph kinds are data values; nothing needs a device.
 */
class SessionRowCornerTest {

    private fun vm(
        status: SessionRowVM.Status,
        unseen: Boolean,
        sendFailed: Boolean,
    ): SessionRowVM = SessionRowVM(
        id = "x",
        title = "T",
        projectName = "zeron",
        hasProject = true,
        colorIndex = 6,
        harness = null,
        branch = null,
        pr = null,
        prNumber = null,
        status = status,
        timeLabel = "now",
        unseen = unseen,
        pinned = false,
        sendFailed = sendFailed,
    )

    // ---------------------------------------------------------- precedence

    @Test
    fun allFiveStatusesSeenAndUnseenFailedSendOrNot() {
        for (status in SessionRowVM.Status.entries) {
            for (unseen in listOf(false, true)) {
                for (sendFailed in listOf(false, true)) {
                    val expected: RowCorner? = when {
                        sendFailed -> RowCorner.SendFailed
                        status == SessionRowVM.Status.Working -> RowCorner.Working
                        status == SessionRowVM.Status.Awaiting -> RowCorner.Input
                        status == SessionRowVM.Status.Errored -> RowCorner.Failed
                        status == SessionRowVM.Status.Completed && unseen -> RowCorner.Done
                        else -> null // Completed && !unseen, or Idle → the time label
                    }
                    assertEquals(
                        "$status unseen=$unseen failed-send=$sendFailed",
                        expected,
                        corner(vm(status, unseen, sendFailed)),
                    )
                }
            }
        }
    }

    @Test
    fun sendFailedBeatsEveryLiveStatus() {
        for (status in SessionRowVM.Status.entries) {
            for (unseen in listOf(false, true)) {
                assertEquals(
                    "sendFailed beats $status unseen=$unseen",
                    RowCorner.SendFailed,
                    corner(vm(status, unseen, sendFailed = true)),
                )
            }
        }
    }

    @Test
    fun completedSeenAndIdleFallBackToTheTimeLabel() {
        assertNull(corner(vm(SessionRowVM.Status.Completed, unseen = false, sendFailed = false)))
        assertNull(corner(vm(SessionRowVM.Status.Idle, unseen = false, sendFailed = false)))
        assertNull(corner(vm(SessionRowVM.Status.Idle, unseen = true, sendFailed = false)))
    }

    @Test
    fun theWordsAreVerbatim() {
        assertEquals("Failed", RowCorner.SendFailed.word)
        assertEquals("Working", RowCorner.Working.word)
        assertEquals("Input", RowCorner.Input.word)
        assertEquals("Failed", RowCorner.Failed.word)
        assertEquals("Done", RowCorner.Done.word)
    }

    // --------------------------------------------------------- tone table

    /**
     * [glyph] builds the expected kind from a palette: the glyph tints with
     * the same palette's tone (ticket #37 Step 4 — e.g. `SendFailed.glyph(
     * Palette.Dark) == Dot(Color(0xFFF87171))`), so light and dark differ.
     */
    private fun tone(corner: RowCorner, light: Int, dark: Int, glyph: (Palette) -> StatusGlyphKind) {
        assertEquals("$corner light", light, corner.color(Palette.Light).toArgb())
        assertEquals("$corner dark", dark, corner.color(Palette.Dark).toArgb())
        assertEquals("$corner glyph (light)", glyph(Palette.Light), corner.glyph(Palette.Light))
        assertEquals("$corner glyph (dark)", glyph(Palette.Dark), corner.glyph(Palette.Dark))
    }

    @Test
    fun sendFailedIsDangerAndItsDot() {
        tone(
            RowCorner.SendFailed,
            0xFFDC2626.toInt(),
            0xFFF87171.toInt(),
        ) { StatusGlyphKind.Dot(it.danger) }
        // The literal forms from the acceptance table.
        assertEquals(StatusGlyphKind.Dot(Color(0xFFF87171.toInt())), RowCorner.SendFailed.glyph(Palette.Dark))
    }

    @Test
    fun workingIsTheStatusWorkingToneAndTheSpinner() {
        tone(RowCorner.Working, 0x8C5B43E8.toInt(), 0x8C8B7CF6.toInt()) { StatusGlyphKind.Spinner }
    }

    @Test
    fun inputIsTheStatusInputToneAndItsDot() {
        tone(RowCorner.Input, 0x995B43E8.toInt(), 0x998B7CF6.toInt()) { StatusGlyphKind.Dot(it.status.input) }
    }

    @Test
    fun failedIsTheStatusFailedToneAndItsDot() {
        tone(RowCorner.Failed, 0xA6DC2626.toInt(), 0xA6F87171.toInt()) { StatusGlyphKind.Dot(it.status.failed) }
    }

    @Test
    fun doneIsTheStatusDoneToneAndItsCheck() {
        tone(RowCorner.Done, 0xE615803D.toInt(), 0xE634D399.toInt()) { StatusGlyphKind.Check(it.status.done) }
        assertEquals(StatusGlyphKind.Check(Color(0xE615803D.toInt())), RowCorner.Done.glyph(Palette.Light))
    }
}
