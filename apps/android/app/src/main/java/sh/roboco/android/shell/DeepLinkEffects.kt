package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos

/**
 * Wires one shell's [AppRouter] into the app's [DeepLinkHub]
 * (android-ui-parity #36): attach **after the shell's first frame** — the
 * port of iOS's `DispatchQueue.main.async` after `window.makeKeyAndVisible`
 * (`SceneDelegate.swift:53,64-72`) — so a cold-start link never
 * dispatches before the shell has drawn; detach on dispose,
 * identity-checked inside the hub so a stale detach from an old
 * composition (the one-frame overlap of the phone/split switch) cannot
 * clear a newer router.
 *
 * Kept out of `DeepLinkHub.kt` so the hub itself stays plain-JVM
 * (no Compose, no `android.*`), and out of `RobocoAppContent.kt` because
 * ticket 108 carries these lines into its `ShellStateScope`.
 *
 * One instance per shell: the phone slot calls this with the router
 * ticket 32's `MainTabShell` reports through `onRouter`; ticket 106 adds
 * the same call for the tablet split's router.
 */
@Composable
fun DeepLinkRouterEffect(hub: DeepLinkHub, router: AppRouter) {
    LaunchedEffect(router) {
        // One frame is out: the shell is on screen.
        withFrameNanos { }
        hub.attachRouter(router)
    }
    DisposableEffect(router) {
        onDispose { hub.detachRouter(router) }
    }
}
