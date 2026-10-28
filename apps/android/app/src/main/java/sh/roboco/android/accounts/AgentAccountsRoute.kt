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
 * agent) scrolls the page to that harness's section. [signIn] (an error
 * card's "Log in", wave-10 35) starts [focusHarness]'s first login as
 * soon as the page has its accounts — the iPhone's `signIn: true`, the
 * desktop's `start_first_login`.
 *
 * `hidesBottomChrome` stays false: Settings pushes keep the tab bar
 * (only the Session screen hides it).
 */
data class AgentAccountsRoute(
    val deviceId: String?,
    val focusHarness: String? = null,
    val signIn: Boolean = false,
) : NavRoute {
    override val paneTitle: String = AccountsCopy.PageTitle
}

/**
 * Where an error card's "Log in" goes (wave-10 35, upstream #967's
 * `SessionViewController.swift:89-99`): the chat's agent on its computer,
 * its first login started at once — unless the chat is Limited. Then the
 * Limited banner owns the recovery (Resume, Resume on another account,
 * Resume at reset; orchestration v2), so Log in only shows the accounts
 * and never kicks off a second, competing sign-in. Pure.
 */
fun errorCardLogInRoute(hostDeviceId: String, harness: String?, limited: Boolean): AgentAccountsRoute =
    AgentAccountsRoute(
        deviceId = hostDeviceId.ifEmpty { null },
        focusHarness = harness,
        signIn = !limited,
    )
