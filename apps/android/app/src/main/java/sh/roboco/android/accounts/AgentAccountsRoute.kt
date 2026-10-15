package sh.roboco.android.accounts

import sh.roboco.android.shell.NavRoute

/**
 * The route the Settings → Agent Accounts row, the composer usage chip's
 * "Accounts & Usage…" row and the session ⋯ menu's "Account & Usage" push
 * (android-next#05): the Accounts & Usage page.
 *
 * [deviceId] is the computer whose accounts the page shows — the engine
 * device id the opener holds (`null` = the primary device: the page picks
 * the Settings section's rule). [focusHarness] (the session opener's
 * agent) scrolls the page to that harness's section.
 *
 * `hidesBottomChrome` stays false: Settings pushes keep the tab bar
 * (only the Session screen hides it).
 */
data class AgentAccountsRoute(
    val deviceId: String?,
    val focusHarness: String? = null,
) : NavRoute {
    override val paneTitle: String = AccountsCopy.PageTitle
}
