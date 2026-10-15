package sh.roboco.android.transcript.widgets

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.isControl
import sh.roboco.android.transcript.isTranscriptControl
import uniffi.roboco_core.WidgetKind

/**
 * The subagent spawn card's tap target (android-next #06, upstream #899's
 * `subagentLink` — a `ToolToggleControl` over the whole card): it opens
 * the subagent once per tap, says "Open subagent: <title>" as a button,
 * claims its down from the composer-dismiss tap like every transcript
 * control, and keeps the row menu away (a control kind); the default
 * actions leave a card inert.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h300dp-mdpi")
class SubagentLinkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val link = WidgetKind.SubagentLink(
        docId = "engine:v1:k:chat--sub--toolu_1",
        title = "Audit the reduced-motion fallback",
        frozen = false,
    )

    @Test
    fun aTapOpensTheSubagentOnceAndSpeaksAsAButton() {
        val opened = mutableListOf<String>()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    // The Rust rect of a 30 pt card in its 38 pt row (T = 1.125).
                    Box(Modifier.absoluteOffset(18.dp, 4.dp).size(324.dp, 33.75.dp)) {
                        SubagentLinkTarget(title = link.title, onOpen = { opened += link.docId })
                    }
                }
            }
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("subagent-link")
        val config = node.fetchSemanticsNode().config
        assertEquals(listOf("Open subagent: Audit the reduced-motion fallback"), config[SemanticsProperties.ContentDescription])
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        node.performClick()
        compose.waitForIdle()
        assertEquals(listOf(link.docId), opened)
    }

    @Test
    fun theLinkIsATranscriptControl() {
        assertTrue("its down never dismisses the composer", link.isTranscriptControl)
        assertTrue("a long press over it opens no row menu", link.isControl())
        assertFalse(WidgetKind.Spinner.isTranscriptControl)
    }

    @Test
    fun theDefaultActionsLeaveTheCardInert() {
        // No crash, no effect: tests and previews build actions without it.
        TranscriptActions().openSubagent(link.docId, link.title, link.frozen)
        var seen: Triple<String, String, Boolean>? = null
        TranscriptActions(openSubagent = { d, t, f -> seen = Triple(d, t, f) })
            .openSubagent(link.docId, link.title, true)
        assertEquals(Triple(link.docId, link.title, true), seen)
    }
}
