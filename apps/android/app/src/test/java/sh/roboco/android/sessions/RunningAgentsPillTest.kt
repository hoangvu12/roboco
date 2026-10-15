package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.roboco.android.core.Face
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.SessionRow

/**
 * The sessions list's "● N" pill (android-next #06, upstream #899's
 * `SessionCell.agents`): the FFI count reaches the view model, the row's
 * label speaks it, and the hand layout puts the pill before the status
 * glyph and the word — `ceil(text) + 12` wide, `R(18k)` high, centred on
 * the title line, 4 dp into the title's gap — with the title ending 8 dp
 * before it. No pill, no change.
 *
 * Plain JUnit 4 (no JNA: every `from` passes `homeColorIndex = 6`).
 */
class RunningAgentsPillTest {

    private fun row(running: UInt): SessionRow = SessionRow(
        id = "abc",
        engineId = "engine:v1:alpha",
        revision = 1uL,
        title = "Streaming veil on transcript rows",
        hasTitle = true,
        preview = null,
        project = null,
        deviceId = "device",
        deviceName = "MacBook Pro",
        deviceOnline = true,
        engineOnline = true,
        harness = "claude-code",
        harnessLabel = null,
        model = null,
        modelLabel = null,
        reasoning = null,
        branch = null,
        cwd = null,
        // Idle: no corner word, so the label is title, project, agents.
        indicator = ChatIndicator.IDLE,
        hostIndicator = ChatIndicator.IDLE,
        workingSinceMs = null,
        runningSubagents = running,
        lastActivityMs = 0L,
        timeLabel = "now",
        createdAtMs = 0L,
        unseen = false,
        archived = false,
        pinned = false,
        sectionId = null,
        pullRequest = null,
        sendState = null,
        parentChatId = null,
    )

    @Test
    fun theCountReachesTheViewModelAndTheLabel() {
        val vm = SessionRowVM.from(row(4u), homeColorIndex = 6)
        assertEquals(4, vm.runningSubagents)
        assertEquals("Streaming veil on transcript rows, MacBook Pro, 4 subagents running", sessionRowA11yLabel(vm))
        val one = SessionRowVM.from(row(1u), homeColorIndex = 6)
        assertEquals("Streaming veil on transcript rows, MacBook Pro, 1 subagent running", sessionRowA11yLabel(one))
        val none = SessionRowVM.from(row(0u), homeColorIndex = 6)
        assertEquals(0, none.runningSubagents)
        assertEquals("Streaming veil on transcript rows, MacBook Pro", sessionRowA11yLabel(none))
        assertNull(runningAgentsLabel(0))
    }

    @Test
    fun thePillLeadsTheGlyphAndShortensTheTitle() {
        val medium = Face.SansMedium
        val plain = SessionRowGeometry.frames(411f, 1f, medium, wordWidth = 13.8f, hasGlyph = true)
        assertNull(plain.agents)
        val f = SessionRowGeometry.frames(411f, 1f, medium, wordWidth = 13.8f, hasGlyph = true, agentsTextWidth = 17.3f)
        val pill = f.agents!!
        assertEquals("ceil(17.3) + 12", 30f, pill.w, 0.001f)
        assertEquals(18f, pill.h, 0.001f)
        // Its right edge sits 4 dp into the title's 10 dp gap before the glyph.
        assertEquals(f.glyph!!.x - SessionRowGeometry.TITLE_GAP + 4f, pill.x + pill.w, 0.001f)
        // Centred on the 22 dp title line at 10.
        assertEquals(10f + (22f - 18f) / 2f, pill.y, 0.001f)
        // The title ends 8 dp before the pill.
        assertEquals(pill.x - 8f, f.title.x + f.title.w, 0.001f)
        assertEquals(plain.word, f.word)
        assertEquals(plain.glyph, f.glyph)
    }
}
