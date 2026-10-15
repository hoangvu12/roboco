package sh.roboco.android.session

import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView

/**
 * Roboco's read-only mode (android-ui-parity #79): writes to an engine
 * that is not `Connected` are **refused, never queued** (android-app#06/
 * #07/#08 — the core fails them at once with
 * `HostUnavailable("Engine is offline; reconnecting")`, the same message
 * as the web client's `client.ts:260`). iOS has no such mode: upstream
 * saved sends into a cloud outbox and kept every control enabled while
 * offline. Roboco cannot save sends, so the controls switch off instead,
 * and the iOS offline banner is deliberately not ported (73's pill copy
 * replaces it).
 *
 * This ticket owns the **derivation** and the plumbing only: the
 * composer's disabled look is 54/58/59, the pill copy is 73, the gated ⋯
 * rows are 80, and the core's refusal is android-app#06.
 */
internal fun canWriteFor(engine: EngineView?): Boolean = engine?.state is EngineState.Connected

/**
 * Fills [SessionChrome.canWrite] (ticket 51's field and hook), registered
 * by `RobocoApplication.onCreate` in ticket 76's [ChromeFillerRegistry]
 * at slot [ChromeFillerRegistry.CAN_WRITE] — 10, the first filler, so
 * later fillers (73's banner at 30) can read `chrome.canWrite`.
 *
 * Pure, no FFI and no local state: [ChromeInput.engine] already holds the
 * owning engine's view (pulled once per refresh on the controller's IO
 * lane), so the factory may return the same `object` every time. No
 * grace and no debounce: `canWrite` follows the core state on the next
 * refresh — `Connecting` is read-only too (the web treats the
 * not-yet-dialed client as offline), and anything that is not
 * `Connected` (the web's `closed` included) is not writable. Ticket 50's
 * 4 s `DegradeGrace` is a display rule for the Sessions-list connection
 * line and deliberately does not apply here.
 *
 * [SessionChrome.canWrite] keeps its default `true` (ticket 51) wherever
 * this filler is not registered — previews and tests.
 */
object CanWriteFiller : ChromeFiller {
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome =
        chrome.copy(canWrite = canWriteFor(input.engine))
}
