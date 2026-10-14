package sh.roboco.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.composer.ComposerChip
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ParkReason

/**
 * [CanWriteFiller] (android-ui-parity #79): the derivation table —
 * `canWriteFor` is `true` only for a `Connected` owning engine — and the
 * filler's single-field contract (`fill` changes nothing but
 * `canWrite`).
 *
 * Plain JUnit 4, no Robolectric: the UniFFI records are plain Kotlin
 * data classes whose construction touches no JNA (the same property
 * `SessionControllerTest` relies on).
 */
class CanWriteFillerTest {

    @Test
    fun canWriteIsTrueOnlyForAConnectedEngine() {
        assertTrue(canWriteFor(engineView(state = EngineState.Connected)))
    }

    @Test
    fun canWriteIsFalseForNullAndEveryNonConnectedState() {
        // null: the engine was forgotten mid-session, or is not readable yet.
        assertFalse(canWriteFor(null))
        // The fresh, not-yet-dialed client is offline too (web client.ts:254-261).
        assertFalse(canWriteFor(engineView(state = EngineState.Connecting)))
        assertFalse(canWriteFor(engineView(state = EngineState.Reconnecting)))
        assertFalse(canWriteFor(engineView(state = EngineState.Offline)))
        // Parked needs a re-pair or an update — the web's `closed` is
        // covered by "anything not Connected".
        assertFalse(canWriteFor(engineView(state = EngineState.Parked(ParkReason.REVOKED))))
        assertFalse(canWriteFor(engineView(state = EngineState.Parked(ParkReason.IDENTITY_CHANGED))))
        assertFalse(canWriteFor(engineView(state = EngineState.Parked(ParkReason.INCOMPATIBLE))))
    }

    @Test
    fun fillChangesOnlyCanWrite() {
        val chrome = SessionChrome(
            title = "Wrangler deploy hygiene",
            subtitle = "roboco @ Vu-PC",
            running = true,
            canSteer = true,
            canWrite = true,
            placeholder = "Message Claude Code",
            chips = listOf(ComposerChip("model", "Fable 5")),
            banner = SessionChrome.Banner.Reconnecting,
        )
        for (engine in listOf<EngineView?>(
            engineView(state = EngineState.Connected),
            engineView(state = EngineState.Reconnecting),
            engineView(state = EngineState.Parked(ParkReason.REVOKED)),
            null,
        )) {
            val input = ChromeInput(
                chatId = "chat-a",
                composer = composerState(),
                row = sessionRow(),
                engine = engine,
            )
            val filled = CanWriteFiller.fill(chrome, input)
            assertEquals(canWriteFor(engine), filled.canWrite)
            // The ticket's exact rule: no field other than canWrite changes.
            assertEquals(chrome, filled.copy(canWrite = chrome.canWrite))
        }
    }
}
