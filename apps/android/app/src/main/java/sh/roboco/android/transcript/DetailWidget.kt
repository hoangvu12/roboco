package sh.roboco.android.transcript

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * `WidgetKind.Detail`'s view (ticket 127; iOS `ios:Transcript/
 * RowView.swift:239-250`): a bare `UIControl` — **no visual**, no image, no
 * background, no pressed state, no haptic — that opens the mono
 * tool-detail sheet with the widget's payload. Ticket 65's [WidgetBox]
 * places it at `(w.x, w.y)` sized `(w.w, w.h)`, in the scroller's content
 * box when `w.scroller` is set.
 *
 * `clickable` consumes the up event, so 65's Final-pass tap observer never
 * fires `actions.onTap()` for it (iOS controls keep their taps); the down
 * event stays unconsumed, so a drag that starts on the widget still
 * scrolls the transcript. Rust never constructs the kind today (tool
 * details open inline through `ToolToggle`, ticket 120), so only the
 * gallery and synthetic tests exercise it — the port exists because the
 * painter handles it and the generated `when` must stay exhaustive.
 *
 * @param onOpen called with the title and the payload (`payload ?: ""` — a
 *   missing payload opens an empty sheet, exactly as `RowView.swift:243`).
 */
@Composable
internal fun DetailWidget(
    title: String,
    payload: String,
    onOpen: (title: String, text: String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag("transcript-detail")
            .semantics {
                contentDescription = "$title details"
                role = Role.Button
            }
            // A tap target with no indication and no pressed state — a bare
            // UIControl draws nothing when touched (no ripple either).
            .clickable(interactionSource = null, indication = null) {
                onOpen(title, payload)
            },
    )
}
