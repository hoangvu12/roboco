package sh.roboco.android.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem

/**
 * [actionButtonLook] and [deliveryMenu] (android-ui-parity #58) — the pure
 * half of the ticket, plain JUnit (the Robolectric behaviour is
 * `ComposerActionButtonTest`, the goldens
 * `ComposerActionButtonScreenshotTest`).
 *
 * `actionButtonLook` is pinned over **every** row of ticket 54's
 * `ComposerController.deriveAction` truth table (the derivation inputs:
 * running × hasContent × canWrite × canSteer × preferred), asserting the
 * ticket's States table: the glyph, point size, fill/ink roles, enabled
 * flag, a11y label and menu presence — including the two offline rows
 * (`!enabled` with a running action), which must render the disabled
 * **send** look with the label `Send message` whatever the action says.
 *
 * `deliveryMenu` is pinned for both `canSteer` values: the title, the
 * rows' titles/subtitles/icons/test tags, the destructive third row and
 * the delivery mode each row's action sends.
 */
class ComposerActionButtonLogicTest {

    // ------------------------------------------------ the States table

    private fun assertLook(
        state: ActionState,
        visual: ComposerAction,
        icon: Any,
        iconPt: Float,
        fill: ActionFill,
        ink: ActionInk,
        enabled: Boolean,
        a11y: String,
        hasMenu: Boolean,
    ): ActionButtonLook {
        val look = actionButtonLook(state)
        assertEquals("visual", visual, look.visual)
        assertSame("icon", icon, look.icon)
        assertEquals("iconPt", iconPt, look.iconPt, 0f)
        assertEquals("fill", fill, look.fill)
        assertEquals("ink", ink, look.ink)
        assertEquals("enabled", enabled, look.enabled)
        assertEquals("a11yLabel", a11y, look.a11yLabel)
        assertEquals("hasMenu", hasMenu, look.hasMenu)
        return look
    }

    @Test
    fun theLookMatchesEveryRowOfTheDeriveActionTruthTable() {
        // 1. Idle, empty, writable: the disabled send.
        assertLook(
            ComposerController.deriveAction(running = false, hasContent = false, canWrite = true, canSteer = false, preferred = DeliveryMode.Queue),
            visual = ComposerAction.Send, icon = RobocoIcons.ArrowUp, iconPt = ActionButtonTokens.SendIconPt,
            fill = ActionFill.ControlFill, ink = ActionInk.Tertiary, enabled = false,
            a11y = "Send message", hasMenu = false,
        )
        // 2. Idle with content: accent send, white arrow.
        assertLook(
            ComposerController.deriveAction(running = false, hasContent = true, canWrite = true, canSteer = false, preferred = DeliveryMode.Queue),
            visual = ComposerAction.Send, icon = RobocoIcons.ArrowUp, iconPt = ActionButtonTokens.SendIconPt,
            fill = ActionFill.Accent, ink = ActionInk.White, enabled = true,
            a11y = "Send message", hasMenu = false,
        )
        // 3–4. Idle offline (canWrite = false), empty or with content: the
        // same disabled send look, label "Send message".
        for (hasContent in listOf(false, true)) {
            assertLook(
                ComposerController.deriveAction(running = false, hasContent = hasContent, canWrite = false, canSteer = false, preferred = DeliveryMode.Queue),
                visual = ComposerAction.Send, icon = RobocoIcons.ArrowUp, iconPt = ActionButtonTokens.SendIconPt,
                fill = ActionFill.ControlFill, ink = ActionInk.Tertiary, enabled = false,
                a11y = "Send message", hasMenu = false,
            )
        }
        // 5. Running, empty: the inverted stop.
        assertLook(
            ComposerController.deriveAction(running = true, hasContent = false, canWrite = true, canSteer = false, preferred = DeliveryMode.Queue),
            visual = ComposerAction.Stop, icon = RobocoIcons.StopFill, iconPt = ActionButtonTokens.StopIconPt,
            fill = ActionFill.Text, ink = ActionInk.Background, enabled = true,
            a11y = "Stop response", hasMenu = false,
        )
        // 6. Running, empty, offline: Stop is disabled by canWrite, so the
        // visual action is Send — the grey arrow, never a grey stop square.
        assertLook(
            ComposerController.deriveAction(running = true, hasContent = false, canWrite = false, canSteer = false, preferred = DeliveryMode.Queue),
            visual = ComposerAction.Send, icon = RobocoIcons.ArrowUp, iconPt = ActionButtonTokens.SendIconPt,
            fill = ActionFill.ControlFill, ink = ActionInk.Tertiary, enabled = false,
            a11y = "Send message", hasMenu = false,
        )
        // 7. Running with content, no steering support: queue look, menu.
        assertLook(
            ComposerController.deriveAction(running = true, hasContent = true, canWrite = true, canSteer = false, preferred = DeliveryMode.Queue),
            visual = ComposerAction.Queue, icon = RobocoIcons.ArrowUp, iconPt = ActionButtonTokens.SendIconPt,
            fill = ActionFill.Accent, ink = ActionInk.White, enabled = true,
            a11y = "Queue message", hasMenu = true,
        )
        // 8. Running with content, steer preferred and supported: the
        // label switches to "Steer"; the look does not.
        assertLook(
            ComposerController.deriveAction(running = true, hasContent = true, canWrite = true, canSteer = true, preferred = DeliveryMode.Steer),
            visual = ComposerAction.Queue, icon = RobocoIcons.ArrowUp, iconPt = ActionButtonTokens.SendIconPt,
            fill = ActionFill.Accent, ink = ActionInk.White, enabled = true,
            a11y = "Steer", hasMenu = true,
        )
        // 9. Steer preferred but unsupported: plain queue.
        assertLook(
            ComposerController.deriveAction(running = true, hasContent = true, canWrite = true, canSteer = false, preferred = DeliveryMode.Steer),
            visual = ComposerAction.Queue, icon = RobocoIcons.ArrowUp, iconPt = ActionButtonTokens.SendIconPt,
            fill = ActionFill.Accent, ink = ActionInk.White, enabled = true,
            a11y = "Queue message", hasMenu = true,
        )
        // 10. Running with content, offline: Queue disabled by canWrite —
        // the disabled send look, no menu.
        assertLook(
            ComposerController.deriveAction(running = true, hasContent = true, canWrite = false, canSteer = true, preferred = DeliveryMode.Steer),
            visual = ComposerAction.Send, icon = RobocoIcons.ArrowUp, iconPt = ActionButtonTokens.SendIconPt,
            fill = ActionFill.ControlFill, ink = ActionInk.Tertiary, enabled = false,
            a11y = "Send message", hasMenu = false,
        )
    }

    @Test
    fun directlyConstructedOfflineStatesAlsoRenderTheDisabledSendLook() {
        // The AC's "!enabled Stop/Queue (offline)" rows, without going
        // through deriveAction: the label must match the drawn arrow.
        for (action in listOf(ComposerAction.Stop, ComposerAction.Queue)) {
            assertLook(
                ActionState(action, enabled = false, steering = true, deliveryMenu = true),
                visual = ComposerAction.Send, icon = RobocoIcons.ArrowUp, iconPt = ActionButtonTokens.SendIconPt,
                fill = ActionFill.ControlFill, ink = ActionInk.Tertiary, enabled = false,
                a11y = "Send message", hasMenu = false,
            )
        }
    }

    @Test
    fun theQueueLabelFollowsSteeringOnly() {
        // steering is the only label input in the queue state; the look is
        // identical either way.
        val plain = actionButtonLook(ActionState(ComposerAction.Queue, enabled = true, steering = false, deliveryMenu = true))
        val steering = actionButtonLook(ActionState(ComposerAction.Queue, enabled = true, steering = true, deliveryMenu = true))
        assertEquals("Queue message", plain.a11yLabel)
        assertEquals("Steer", steering.a11yLabel)
        assertEquals(plain.copy(a11yLabel = ""), steering.copy(a11yLabel = ""))
    }

    @Test
    fun theTokensCarryTheTicketNumbers() {
        assertEquals(34.dp, ActionButtonTokens.Size)
        assertEquals(15f, ActionButtonTokens.SendIconPt, 0f)
        assertEquals(11f, ActionButtonTokens.StopIconPt, 0f)
        assertEquals(0.85f, ActionButtonTokens.BounceFrom, 0f)
        assertEquals(0.75f, ActionButtonTokens.PressedAlpha, 0f)
        assertEquals("While the agent works", ActionButtonTokens.MenuTitle)
    }

    // ------------------------------------------------ the delivery menu

    @Test
    fun theMenuCarriesTheTitleRowsIconsTagsAndModesWithSteer() {
        val modes = mutableListOf<DeliveryMode>()
        val menu = deliveryMenu(canSteer = true, onSend = { modes += it })

        assertEquals("While the agent works", menu.title)
        assertEquals(3, menu.children.size)

        val queue = menu.children[0] as MenuItem
        assertEquals("Queue for next turn", queue.title)
        assertNull(queue.subtitle)
        assertEquals(MenuIcon.Symbol(RobocoIcons.QueueForNextTurn), queue.icon)
        assertFalse(queue.destructive)
        assertEquals("composer-delivery-queue", queue.testTag)
        queue.action()
        assertEquals(listOf(DeliveryMode.Queue), modes)

        val steer = menu.children[1] as MenuItem
        assertEquals("Steer now", steer.title)
        assertEquals("Deliver into the running turn", steer.subtitle)
        assertEquals(MenuIcon.Symbol(RobocoIcons.ArrowTurnDownRight), steer.icon)
        assertFalse(steer.destructive)
        assertEquals("composer-delivery-steer", steer.testTag)
        steer.action()
        assertEquals(listOf(DeliveryMode.Queue, DeliveryMode.Steer), modes)

        val interrupt = menu.children[2] as MenuItem
        assertEquals("Stop and send", interrupt.title)
        assertNull(interrupt.subtitle)
        assertEquals(MenuIcon.Symbol(RobocoIcons.StopCircle), interrupt.icon)
        assertTrue("the third row is destructive", interrupt.destructive)
        assertEquals("composer-delivery-interrupt", interrupt.testTag)
        interrupt.action()
        assertEquals(listOf(DeliveryMode.Queue, DeliveryMode.Steer, DeliveryMode.Interrupt), modes)
    }

    @Test
    fun withoutSteerSupportTheMenuHasExactlyTwoRows() {
        val menu = deliveryMenu(canSteer = false, onSend = {})
        assertEquals(2, menu.children.size)
        assertEquals("Queue for next turn", (menu.children[0] as MenuItem).title)
        assertEquals("Stop and send", (menu.children[1] as MenuItem).title)
        assertTrue(menu.children.none { (it as MenuItem).testTag == "composer-delivery-steer" })
    }

    @Test
    fun everyMenuInstanceIsFreshSoCanSteerIsAlwaysCurrent() {
        val a = deliveryMenu(canSteer = false, onSend = {})
        val b = deliveryMenu(canSteer = true, onSend = {})
        assertEquals(2, a.children.size)
        assertEquals(3, b.children.size)
        assertTrue(a !== b)
    }
}
