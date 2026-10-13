package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import sh.roboco.android.composer.ComposerBar
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.DeliveryMode
import sh.roboco.android.composer.MentionSuggestionsHost
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.previewMorphState
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.session.ChipSource
import sh.roboco.android.session.SendReporter
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.SessionCommands
import sh.roboco.android.session.SessionComposerSlot
import sh.roboco.android.session.StatusPillRow
import sh.roboco.android.session.rememberSessionComposer
import sh.roboco.android.session.sessionChips
import sh.roboco.android.transcript.RunwayHooks
import uniffi.roboco_core.FileMatch

/**
 * The "Session composer" page of the debug design gallery
 * (android-ui-parity #78): the real session wiring around one composer —
 * [rememberSessionComposer] over a fake [SessionCommands], a fake
 * [SendReporter] (the send-failure pill above the composer, 73) and a
 * local [RunwayHooks] stub — hosted like the session screen (a
 * [GlassMenuHost] for the chips and the attach menu, a
 * [sh.roboco.android.composer.MentionSuggestionsHost] for 86's card, a
 * haze-source backdrop for the glass).
 *
 * The chrome is a local `mutableStateOf` driven by the toggles below (no
 * `SessionController`); the chips come straight from [sessionChips].
 * Try it live:
 *
 *  - **running / canSteer / canWrite** flip the chrome fields the
 *    composer renders (the action button's Send/Queue/Stop looks, the
 *    offline grey-out);
 *  - **context** cycles none → 62 % (calm) → 91 % (warning wash);
 *  - **PR** cycles none → open → merged (accent wash) → closed, replacing
 *    the branch chip exactly like a session row;
 *  - **Fail sends** makes the fake send throw, so the text stays in the
 *    composer and the pill reads `Couldn't send: Engine is offline;
 *    reconnecting`;
 *  - the mono log line under the stage shows the last command
 *    (`send(queue, …)`, `interrupt()`) and the last runway call.
 *
 * Under the Roborazzi capture nothing is typed, so the section renders
 * its initial state (idle capsule, `Message Claude Code`).
 *
 * Registered in `Gallery.sections` as "Session composer" (slug
 * `session_composer`, goldens `gallery_session_composer_{light,dark}.png`
 * via ticket 22's parameterized gallery test).
 */
@Composable
fun SessionComposerGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current

    var running by remember { mutableStateOf(false) }
    var canSteer by remember { mutableStateOf(false) }
    var canWrite by remember { mutableStateOf(true) }
    var contextStep by remember { mutableIntStateOf(0) }
    var prStep by remember { mutableIntStateOf(0) }
    var failSends by remember { mutableStateOf(false) }
    var logLine by remember { mutableStateOf("no command yet") }

    val reporter = remember { GallerySendReporter(log = { message -> logLine = message }) }
    val hooks = remember { GalleryRunwayHooks { message -> logLine = message } }
    val commands = remember(failSends) { GalleryCommands(failSends = { failSends }, log = { message -> logLine = message }) }

    val chips = sessionChips(galleryChipSource(contextStep, prStep)) { level ->
        level.replaceFirstChar(Char::uppercase)
    }
    val chrome = SessionChrome(
        title = "Session",
        subtitle = "roboco @ Vu-PC",
        running = running,
        canSteer = canSteer,
        canWrite = canWrite,
        placeholder = "Message Claude Code",
        chips = chips,
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The stage: menu host (chips + attach), haze (the glass) and the
        // mention host, exactly as the session screen stacks them.
        val menuHaze = rememberHazeState()
        GlassMenuHost(hazeState = menuHaze) {
            ProvideHazeState {
                MentionSuggestionsHost(
                    Modifier.fillMaxWidth().height(250.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(palette.background)
                            .hazeSource(LocalHazeState.current!!)
                            .padding(12.dp),
                    ) {
                        StatusPillRow(reporter.banner(), onTap = {})
                        Spacer(Modifier.height(8.dp))
                        // No NavEntry in the gallery: the binding falls
                        // back to the composition's own scope.
                        val binding = rememberSessionComposer(
                            chatId = "gallery-session",
                            chrome = chrome,
                            commands = commands,
                            transcript = hooks,
                            banner = reporter,
                        )
                        SessionComposerSlot(binding, shown = true, Modifier.width(376.dp))
                    }
                }
            }
        }

        // The toggles (foundation rows only — no Material controls).
        GalleryToggleRow("running", onOff(running)) { running = !running }
        GalleryToggleRow("canSteer", onOff(canSteer)) { canSteer = !canSteer }
        GalleryToggleRow("canWrite", onOff(canWrite)) { canWrite = !canWrite }
        GalleryToggleRow("context", listOf("none", "62%", "91%")[contextStep]) {
            contextStep = (contextStep + 1) % 3
        }
        GalleryToggleRow("PR", listOf("none", "open", "merged", "closed")[prStep]) {
            prStep = (prStep + 1) % 4
        }
        GalleryToggleRow("Fail sends", onOff(failSends)) { failSends = !failSends }

        BasicText(
            logLine,
            style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.secondary),
        )
    }
}

private fun onOff(value: Boolean): String = if (value) "on" else "off"

/** The chips' fixture source: the iOS lab row, with the context/PR steps. */
private fun galleryChipSource(contextStep: Int, prStep: Int): ChipSource {
    val tokens = when (contextStep) {
        1 -> 620uL
        2 -> 910uL
        else -> null
    }
    val pr = when (prStep) {
        1 -> 1234uL to ChipTint.PrOpen
        2 -> 1234uL to ChipTint.PrMerged
        3 -> 1234uL to ChipTint.PrClosed
        else -> null
    }
    return ChipSource(
        harness = "claude-code",
        harnessLabel = "Claude Code",
        modelLabel = "Opus 4.5",
        reasoning = "high",
        branch = "ios-rewrite",
        prNumber = pr?.first,
        prTint = pr?.second,
        tokens = tokens,
        window = if (tokens != null) 1000uL else null,
    )
}

/** One gallery toggle row: label left, current value in accent right. */
@Composable
private fun GalleryToggleRow(label: String, value: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        Spacer(Modifier.weight(1f))
        BasicText(value, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent))
    }
}

/**
 * The fake [SessionCommands] (the ticket's step 9): `send` delays 300 ms
 * and then succeeds — or throws `Engine is offline; reconnecting` when
 * "Fail sends" is on; `interrupt` logs; `searchFiles` returns three fixed
 * matches.
 */
private class GalleryCommands(
    private val failSends: () -> Boolean,
    private val log: (String) -> Unit,
) : SessionCommands {
    override suspend fun send(text: String, images: List<StagedImage>, mode: DeliveryMode) {
        log(
            "send(${mode.name.lowercase()}, \"${text.take(24)}\"" +
                (if (images.isEmpty()) "" else ", ${images.size} image(s)") + ")",
        )
        delay(300)
        if (failSends()) throw IllegalStateException("Engine is offline; reconnecting")
    }

    override suspend fun interrupt() {
        log("interrupt()")
    }

    override suspend fun searchFiles(query: String): List<FileMatch> = listOf(
        FileMatch(path = "src/main.rs", isDir = false),
        FileMatch(path = "src/lib.rs", isDir = false),
        FileMatch(path = "crates", isDir = true),
    )
}

/** The fake [SendReporter]: keeps the last failure for the pill above and logs the outcome. */
private class GallerySendReporter(private val log: (String) -> Unit) : SendReporter {
    var lastFailure: String? by mutableStateOf(null)

    fun banner(): SessionChrome.Banner =
        lastFailure?.let { SessionChrome.Banner.Failed("Couldn't send: $it") }
            ?: SessionChrome.Banner.None

    override fun noteSendSucceeded() {
        lastFailure = null
        log("outcome: succeeded")
    }

    override fun noteSendFailed(error: Throwable) {
        lastFailure = error.message
        log("outcome: failed: ${error.message}")
    }
}

/** The local [RunwayHooks] stub: only the calls and the pin are exercised. */
private class GalleryRunwayHooks(private val log: (String) -> Unit) : RunwayHooks {
    override var uploadProgress: Double? by mutableStateOf(null)

    override fun beginOwnTurn() {
        log("runway: beginOwnTurn")
    }

    override fun expectQueuedTurn() {
        log("runway: expectQueuedTurn")
    }
}

// ---------------------------------------------------------------------------
// Previews: fixture B of the ticket's golden block — the card at
// progress 1 with the lab chips.
// ---------------------------------------------------------------------------

@Composable
private fun SessionComposerPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        GlassMenuHost {
            ProvideHazeState {
                MentionSuggestionsHost(Modifier.fillMaxWidth().height(160.dp)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(RobocoTheme.palette.background)
                            .hazeSource(LocalHazeState.current!!),
                    ) {
                        val scope = rememberCoroutineScope()
                        val controller = remember(scope) {
                            ComposerController(scope, NoFeedback).apply {
                                placeholder = "Message Claude Code"
                                chips = sessionChips(galleryChipSource(contextStep = 0, prStep = 0)) { level ->
                                    level.replaceFirstChar(Char::uppercase)
                                }
                            }
                        }
                        ComposerBar(
                            controller,
                            previewMorphState(1f, card = true),
                            Modifier.align(Alignment.Center).width(376.dp),
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Session composer light")
@Composable
private fun SessionComposerLightPreview() {
    SessionComposerPreviewBody(Appearance.Light)
}

@Preview(name = "Session composer dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionComposerDarkPreview() {
    SessionComposerPreviewBody(Appearance.Dark)
}
