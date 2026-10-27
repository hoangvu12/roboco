package sh.roboco.android.transcript

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.WidgetKind

/**
 * The control-kind table of the tap-to-dismiss rule
 * (android-ui-parity #81): exactly the four kinds iOS renders as
 * `UIControl`s (`ios:Transcript/RowView.swift:202-277`, `:318-358`,
 * `ios:Transcript/ToolViews.swift:315`) keep their taps; every other
 * kind lets a tap dismiss the composer **and** do its own thing. Plain
 * JVM — the UniFFI records are data classes, no native core needed.
 */
class TranscriptControlTest {

    @Test
    fun isTranscriptControlIsTrueForExactlyTheControlKinds() {
        val controls = listOf(
            WidgetKind.CopyCode,
            WidgetKind.Disclosure(expanded = true),
            WidgetKind.ToolToggle(detail = 1uL, open = false),
            WidgetKind.Detail(title = "tool detail"),
            WidgetKind.Action(action = "sign-in", label = "Log in"),
        )
        val plain = listOf(
            WidgetKind.Image(reference = "img-1"),
            WidgetKind.Chevron(expanded = false),
            WidgetKind.Icon(name = "checkmark", color = ColorRole.TEXT),
            WidgetKind.ToolStatus(running = true, failed = false),
            WidgetKind.Spinner,
            WidgetKind.Working(sinceMs = null, streaming = true),
            WidgetKind.ToolRail(
                trunkX = 0f,
                bend = 2f,
                branchEnd = 12f,
                rowMid = 9f,
                tops = emptyList(),
                heights = emptyList(),
            ),
            WidgetKind.Shimmer,
        )

        controls.forEach { kind -> assertTrue("$kind is a control", kind.isTranscriptControl) }
        plain.forEach { kind -> assertFalse("$kind is not a control", kind.isTranscriptControl) }
    }
}
