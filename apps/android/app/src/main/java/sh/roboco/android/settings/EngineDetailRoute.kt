package sh.roboco.android.settings

import sh.roboco.android.shell.NavRoute

/**
 * The route the Settings → Devices engine row pushes (android-ui-parity
 * #102): the Engine detail page (ticket 140) — the status card, device
 * id, Rename, Pair again, Forget.
 *
 * Whichever of 102 and 140 landed first created this file; the shape is
 * fixed so they cannot diverge (140 replaces the placeholder branch
 * ticket 26's `ShellRouteContent` renders for it in the meantime).
 *
 * `hidesBottomChrome` stays false: Settings pushes keep the tab bar
 * (only the Session screen hides it).
 */
data class EngineDetailRoute(val engineId: String) : NavRoute
