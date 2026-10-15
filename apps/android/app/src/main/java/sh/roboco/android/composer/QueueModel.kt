package sh.roboco.android.composer

import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.session.ChromeFiller
import sh.roboco.android.session.ChromeInput
import sh.roboco.android.session.SessionChrome
import uniffi.roboco_core.QueueGate

/**
 * The queue panel's pure model (android-ui-parity #82): the action set,
 * the gate labels, the row / footer copy and the ⋯ row menu — the port of
 * `ios:Session/SessionSource.swift:86-92`,
 * `ios:Session/CoreSessionSource.swift:95-103` and
 * `ios:Composer/QuestionPanel.swift:257-298`. Everything in this file is
 * a pure function or a plain value, so [QueueModelTest] pins it on the
 * plain JVM with no Robolectric runner.
 */

/**
 * ios:Session/SessionSource.swift:86-92: `sendNow, edit, moveUp,
 * moveDown, remove`. [QueueAction.Edit] is routed to ticket 83's
 * `QueueEditController.beginEdit(rowId)` by the screen, never by
 * [QueueActions].
 */
enum class QueueAction { SendNow, Edit, MoveUp, MoveDown, Remove }

/**
 * ios:Session/CoreSessionSource.swift:96-100, verbatim:
 * - an `Editing` lease held by **this** device → `Editing`;
 * - an `Editing` lease held by another device → `Being edited`;
 * - `ReviewRequired` → `Needs review`;
 * - no gate + `actionPending` → `Updating`; no gate, not pending → null.
 *
 * A gated row that is also pending keeps its gate label (the `nil` arm
 * is the only one that reads [actionPending]).
 */
fun queueGateLabel(gate: QueueGate?, actionPending: Boolean): String? = when (gate) {
    is QueueGate.Editing -> if (gate.mine) "Editing" else "Being edited"
    is QueueGate.ReviewRequired -> "Needs review"
    null -> if (actionPending) "Updating" else null
}

/**
 * QuestionPanel.swift:279: `"<gate> · <text>"` — U+00B7 with one space on
 * each side — or just the text when there is no gate.
 */
fun queueRowText(item: SessionChrome.QueuedItem): String =
    item.gate?.let { "$it · ${item.text}" } ?: item.text

/** QuestionPanel.swift:257-259 — `"+<count − 3> more queued"`, null when count ≤ 3. */
fun queueFooterText(count: Int): String? = if (count > 3) "+${count - 3} more queued" else null

/**
 * QuestionPanel.swift:292-298 — the ⋯ row menu: no title, `Edit` /
 * `Move Up` / `Move Down` / `Remove` in that order.
 *
 * [index] is the **row** index (0…2) and [count] the **full** queue size,
 * so `Move Up` is disabled only on row 0 and `Move Down` only on the real
 * last item — with 5 queued, no visible row has Move Down disabled.
 * `Remove` is destructive (title and icon in `Palette.danger`).
 */
fun queueRowMenu(index: Int, count: Int, onAction: (QueueAction) -> Unit): Menu = Menu(
    title = null,
    children = listOf(
        MenuItem(
            title = "Edit",
            icon = MenuIcon.Symbol(RobocoIcons.Pencil),
            testTag = "queue-menu-edit",
        ) { onAction(QueueAction.Edit) },
        MenuItem(
            title = "Move Up",
            icon = MenuIcon.Symbol(RobocoIcons.ArrowUp),
            disabled = index == 0,
            testTag = "queue-menu-move-up",
        ) { onAction(QueueAction.MoveUp) },
        MenuItem(
            title = "Move Down",
            icon = MenuIcon.Symbol(RobocoIcons.ArrowDown),
            disabled = index == count - 1,
            testTag = "queue-menu-move-down",
        ) { onAction(QueueAction.MoveDown) },
        MenuItem(
            title = "Remove",
            icon = MenuIcon.Symbol(RobocoIcons.Trash),
            destructive = true,
            testTag = "queue-menu-remove",
        ) { onAction(QueueAction.Remove) },
    ),
)

/**
 * The queue `ChromeFiller` (ticket 51's hook) — the port of
 * `ios:Session/CoreSessionSource.swift:95-102`, verbatim:
 *
 * - the row text is `visibleText` (the trailer / Appshot context
 *   stripped), never the raw `text`;
 * - `thumbnail` is always nil for live sessions
 *   (`CoreSessionSource.swift:101`); only the iOS fixture source ever set
 *   one, so `thumbnailRef` stays null here too;
 * - order is preserved (the core's queue is in delivery order);
 * - no FFI: [fill] only reads [ChromeInput.composer] on the controller's
 *   IO lane (ticket 51's rule).
 *
 * `composer.queueError` is already mapped into [SessionChrome.error] by
 * ticket 51's base fill; this panel never renders it (decision
 * COMPOSER-D2, strict iOS parity — `ios:Session/
 * SessionViewController.swift` maps it into the chrome but never shows
 * it), so there is no error field here.
 *
 * **Registration (76):** `RobocoApplication.onCreate` registers this
 * filler in 76's `ChromeFillerRegistry` QUEUE slot (50), before
 * `installSessionRoutes` — `chromeFillers.register(
 * ChromeFillerRegistry.QUEUE) { QueueChromeFiller() }` — so every
 * opened route gets one fresh instance (the registry's `create` runs
 * once per open, after the banner and chips have filled). The
 * per-route `QueueActions` over the live handle is built by 76's
 * `CoreSessionRouteFactory` and carried on
 * `SessionScreenRoute.queueActions` for the screen (78/83) to wire.
 */
class QueueChromeFiller : ChromeFiller {
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome =
        chrome.copy(
            queue = input.composer.queue.map { q ->
                SessionChrome.QueuedItem(
                    id = q.id,
                    text = q.visibleText,
                    gate = queueGateLabel(q.gate, q.actionPending),
                    thumbnailRef = null,
                )
            },
        )
}
