package sh.roboco.android.session

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import sh.roboco.android.shell.LocalAppRouter
import sh.roboco.android.shell.NavEntry
import sh.roboco.android.shell.NavEntryEffect

/**
 * Wires one Session screen's [SessionNavLifecycle] into ticket 24's
 * appear/disappear callbacks (android-ui-parity #85) and into the two
 * composition-level events 24 cannot deliver, because the tab host
 * leaves composition instead of flipping `active` (ticket 26 composes
 * only the selected tab's host, under `key(tab)`):
 *
 * - **24's callbacks** through [NavEntryEffect]: `onWillAppear` →
 *   re-attach after the first appearance; `onDidAppear` →
 *   `router.sessionDidAppear`; `onWillDisappear` (any, **including the
 *   start of a predictive back that may still be cancelled**) →
 *   `leave()` — cancel the queued edit, then save the draft;
 *   `onDidDisappear(removed = true)` — **only after a committed
 *   pop/removal** — → `closeAfterPop()`: `router.sessionDidClose`, then
 *   detach + mark seen + the layout engine. Never the teardown in a
 *   `will*`, in `onDispose` or in a back handler: that is the upstream
 *   #609/#621 trap (a cancelled swipe-back left the session frozen).
 * - **composition enter** with `entry.hasAppeared` (a tab switched back,
 *   an activity recreation): the iOS `viewWillAppear`/`viewDidAppear`
 *   pair on tab return — re-attach and `sessionDidAppear`.
 * - **composition dispose**: `leave()` — the iOS tab-switch
 *   disappearance — **unless** the activity is changing configurations
 *   (rotation, dark mode, font scale): then only the draft is saved, so
 *   a queued edit's lease survives the recreation.
 *
 * Call it where the screen is guaranteed a nav entry (76's
 * `SessionScreenRoute.Content()`); the inner `DisposableEffect` sits
 * **outside** the screen's own effects, so 84's dispose-time safety-net
 * save runs first (inner effects dispose first) and 85's `leave()` after
 * it, as both tickets document.
 */
@Composable
fun SessionNavEffects(lifecycle: SessionNavLifecycle, entry: NavEntry) {
    val router by rememberUpdatedState(LocalAppRouter.current)
    val activity = LocalContext.current.findActivity()

    NavEntryEffect(
        onWillAppear = { _ -> lifecycle.onWillAppear(entry.hasAppeared) },
        onDidAppear = { _ -> lifecycle.onDidAppear(router) },
        onWillDisappear = { _, _ -> lifecycle.leave() },
        onDidDisappear = { removed -> if (removed) lifecycle.closeAfterPop(router) },
    )

    DisposableEffect(lifecycle, entry) {
        // Re-entering composition after the first appearance = iOS
        // viewWillAppear/viewDidAppear on tab return (the first push
        // composes with hasAppeared == false: 75 attached already).
        if (entry.hasAppeared) {
            lifecycle.onWillAppear(hasAppeared = true)
            lifecycle.onDidAppear(router)
        }
        onDispose {
            if (activity?.isChangingConfigurations == true) {
                lifecycle.onConfigurationDispose()
            } else {
                lifecycle.leave()
            }
        }
    }
}

/**
 * The nearest [Activity] behind a context, or null (previews, the
 * application context). A `null` activity makes a dispose read as a
 * plain leave — harmless with the gallery's and the tests' fakes.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
