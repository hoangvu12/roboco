package sh.roboco.android.transcript.widgets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight

/**
 * The code-block copy button (android-ui-parity #119; iOS `CopyButton`,
 * `ios:Transcript/RowView.swift:317-344`): ticket 65's [WidgetBox] fills
 * the cell Rust reserves at the code card's top-right corner — exactly
 * `(w.x, w.y, w.w, w.h)` dp, 44 × 32 at text scale 1, never inside 116's
 * body scroller (`w.scroller == null`; the widget is emitted before
 * `begin_scroller`).
 *
 * The glyph is `square.on.square` at 13 pt Medium in `tertiary` at rest;
 * a tap writes the payload to the clipboard, plays the `Light` haptic and
 * swaps instantly (no animation, either direction) to `checkmark` at 13 pt
 * Semibold in `accent` for [Motion.CopyConfirmHoldMs] — **1400 ms** —
 * then back. Every tap schedules its **own** revert and never cancels an
 * earlier one (iOS `asyncAfter`), so two quick taps revert on the first
 * timer; see [CopyConfirmState].
 *
 * A failed clipboard write (Android's Binder has a transaction-size limit
 * iOS's pasteboard lacks) logs and leaves the glyph idle with no haptic,
 * so the UI never claims a copy that did not happen. No toast: the
 * checkmark is the confirmation, as on iOS.
 *
 * ADAPT: the confirmed state is `remember(payload)`, so it survives row
 * re-layouts while the block is unchanged (append-only streaming keeps
 * the widget slot positionally stable); iOS rebuilds every widget view on
 * `configure`, so a streamed chunk would drop a showing check there. A
 * payload change resets to idle, as iOS's new button would. Leaving
 * composition cancels the timers with the scope (iOS's `[weak self]`
 * no-op).
 *
 * The button draws no background, border, ripple or press overlay
 * (`.custom` UIButton with no configuration; UIKit's highlight dimming is
 * dropped — see the ticket's Comments). Accessibility is one merged node
 * labelled "Copy code" with `Role.Button`.
 */
@Composable
fun CopyCodeButton(payload: String, modifier: Modifier = Modifier) =
    CopyCodeButton(payload, modifier, writeClip = ::writeCodeToClipboard)

/**
 * Test seam: [writeClip] replaces the platform clipboard (no mocking
 * library is on the test classpath). Returns false to exercise the
 * failure path.
 */
@Composable
internal fun CopyCodeButton(
    payload: String,
    modifier: Modifier,
    writeClip: (Context, String) -> Boolean,
) {
    val context = LocalContext.current
    val feedback = LocalFeedback.current
    val scope = rememberCoroutineScope()
    // Keyed on the payload only: a row re-layout with the same block keeps
    // the confirmed state (the ADAPT above); a changed block resets.
    val state = remember(payload) { CopyConfirmState() }
    CopyCodeGlyph(
        copied = state.copied,
        modifier = modifier
            .fillMaxSize()
            .testTag("copy-code")
            // No indication, no ripple (iOS draws no pressed state). The
            // down event stays unconsumed, so a drag that starts here
            // still scrolls the transcript; the consumed up event never
            // reaches ticket 65's tap observer.
            .clickable(interactionSource = null, indication = null, role = Role.Button) {
                if (writeClip(context, payload)) {
                    feedback.haptic(Haptic.Light) // UIImpactFeedbackGenerator(.light)
                    state.confirm(scope)
                }
            }
            .semantics { contentDescription = "Copy code" },
    )
}

/**
 * The stateless look, for previews, the gallery and the goldens: the
 * 13 pt glyph (a 15.6 dp box, fixed — it follows neither the system font
 * scale nor the Rust text scale, like `SymbolConfiguration(pointSize: 13)`)
 * centred in the incoming rect, `SquareOnSquare`/Medium/`tertiary` at rest
 * and `Checkmark`/Semibold/`accent` while copied. The swap is the
 * caller's; there is no crossfade here. The glyph itself is decorative
 * (`contentDescription = null`) — the button node carries the label.
 */
@Composable
internal fun CopyCodeGlyph(copied: Boolean, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            icon = if (copied) RobocoIcons.Checkmark else RobocoIcons.SquareOnSquare,
            tint = if (copied) palette.accent else palette.tertiary,
            sizePt = 13f,
            weight = if (copied) SymbolWeight.Semibold else SymbolWeight.Medium,
            modifier = Modifier.testTag(if (copied) "copy-code-check" else "copy-code-copy"),
            contentDescription = null,
        )
    }
}

/**
 * Per-tap independent revert timers, like iOS `asyncAfter`: [confirm]
 * never cancels an earlier timer, so the first pending deadline to fire
 * reverts the glyph — a check armed at t = 1.0 s is reverted at t = 1.4 s
 * by the t = 0 s tap's timer, exactly as on iOS.
 */
@Stable
internal class CopyConfirmState {
    var copied by mutableStateOf(false)
        private set

    /** Shows the check and schedules this tap's own revert. */
    fun confirm(scope: CoroutineScope) {
        copied = true
        scope.launch {
            delay(Motion.CopyConfirmHoldMs)
            copied = false
        }
    }
}

/**
 * Puts [payload] on the clipboard as plain text (label `Code`, invisible
 * to the user; no sensitive flag, like tickets 80 and 101). Returns false
 * when the platform refused the clip — Android's clipboard goes over
 * Binder, whose transaction limit a huge code block can exceed
 * (`TransactionTooLargeException`); the failure is logged and the glyph
 * stays idle. Main thread (the click handler).
 */
internal fun writeCodeToClipboard(context: Context, payload: String): Boolean {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
    return try {
        cm.setPrimaryClip(ClipData.newPlainText("Code", payload))
        true
    } catch (e: RuntimeException) {
        Log.w("Roboco", "copy code failed: $e")
        false
    }
}

// ---------------------------------------------------------------------------
// Previews — the debug fixture (CopyCodeGallerySection.kt) is not on this
// source set's classpath, so the two glyph states are shown directly.
// ---------------------------------------------------------------------------

@Preview(name = "CopyCode light")
@Composable
private fun CopyCodeLightPreview() {
    CopyCodePreviewBody()
}

@Preview(name = "CopyCode dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CopyCodeDarkPreview() {
    CopyCodePreviewBody()
}

/** Both states side by side on the code-card colour, inside [RobocoTheme]. */
@Composable
private fun CopyCodePreviewBody() {
    RobocoTheme(Appearance.System) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CopyCodePreviewCell(copied = false)
            CopyCodePreviewCell(copied = true)
        }
    }
}

/** One 44 × 32 dp cell on `palette.codeBackground`, the Rust card colour. */
@Composable
private fun CopyCodePreviewCell(copied: Boolean) {
    Box(
        Modifier
            .size(44.dp, 32.dp)
            .background(RobocoTheme.palette.codeBackground),
    ) {
        CopyCodeGlyph(copied)
    }
}
