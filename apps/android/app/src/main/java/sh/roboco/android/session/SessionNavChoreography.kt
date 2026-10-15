package sh.roboco.android.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.shell.NavEntry
import sh.roboco.android.shell.NavKind
import sh.roboco.android.shell.NavStack

/**
 * The push/pop choreography of the Session screen (android-ui-parity #85)
 * — the session half of `SessionViewController.setAccessory(visible:)`
 * (`ios:Session/SessionViewController.swift:191-227`), as **pure functions**
 * of ticket 24's transition: the bottom stack fades 0 → 1 on an animated
 * push and 1 → 0 on an animated pop (tracking the predictive-back
 * gesture), the jump button fades with the pop, a cancel springs both
 * back, and an unanimated transition (the handoff, a `setRoutes`
 * replace) leaves both untouched. The tab-chrome half — the accessory
 * riding under the outgoing screen, its content α = `p` — is ticket 30;
 * the two glasses never overlap because this stack α is `1 − p` where
 * 30's accessory α is `p`.
 *
 * **Derive, don't flip** (iOS `:219-224`): the alphas are pure functions
 * of `stack.transition` / `stack.top`, never toggled flags. A cancelled
 * back ends at α 1 because the progress springs back to 0, not because
 * a flag flipped back.
 */
enum class SessionNavPhase {
    /** This screen is the `to` of an animated-or-not push. */
    Entering,

    /** This screen is the `from` of a pop (button, programmatic or predictive back). */
    Leaving,

    /** Any other transition (this entry as `from` of a push, or `to` of a pop). */
    Bystander,

    /** No transition and this entry is the stack's top. */
    IdleTop,

    /** No transition and this entry is buried or gone (24 does not place it anyway). */
    IdleHidden,
}

/**
 * Pure. The phase of this screen, checked in exactly this order (the
 * ticket's Spec §Phase): `Entering` (push to self) before `Leaving`
 * (pop from self) before `Bystander`, then the idle phases from
 * `isTop`.
 */
fun sessionNavPhase(
    hasTransition: Boolean,
    kind: NavKind?,
    isTo: Boolean,
    isFrom: Boolean,
    isTop: Boolean,
): SessionNavPhase = when {
    hasTransition && kind == NavKind.Push && isTo -> SessionNavPhase.Entering
    hasTransition && kind == NavKind.Pop && isFrom -> SessionNavPhase.Leaving
    hasTransition -> SessionNavPhase.Bystander
    isTop -> SessionNavPhase.IdleTop
    else -> SessionNavPhase.IdleHidden
}

/**
 * The two multipliers of `setAccessory`'s session half: iOS `bottom.alpha`
 * ([stack], the composer glass stack, applied by 74 to the slot layers)
 * and iOS `jump.alpha` ([jump], the jump button, applied by 131 inside
 * its own `graphicsLayer`).
 */
@Immutable
data class SessionNavAlphas(val stack: Float, val jump: Float) {
    companion object {
        /** The resting values: fully shown. */
        val Shown = SessionNavAlphas(1f, 1f)

        /** Off screen (24 does not place the entry anyway; a safety value). */
        val Gone = SessionNavAlphas(0f, 0f)
    }
}

/**
 * Pure port of the session half of `setAccessory(visible:)`
 * (`SessionViewController.swift:191-227`) — the alpha table:
 *
 * | Phase | Stack α | Jump α | iOS |
 * |---|---|---|---|
 * | `IdleTop`, `Bystander` | 1 | 1 | resting / `setAccessory` not called |
 * | `IdleHidden` | 0 | 0 | not on screen |
 * | `Entering`, animated, no reduce | `p` | 1 | `bottom.alpha` 0 → 1 alongside; jump untouched |
 * | `Leaving`, animated, no reduce | `1 − p` | `1 − p` | both 1 → 0 alongside |
 * | `Entering`/`Leaving`, `!animated` | 1 | 1 | no coordinator → untouched (`:196-198`) |
 * | `Entering`/`Leaving`, reduce | 1 | 1 | 24's reduced branch already fades the whole layer |
 *
 * [progress] is clamped to `[0, 1]`. Under reduce motion the multipliers
 * stay 1: ticket 24 turns the push into a 150 ms cross-fade whose layer
 * α is `s`, so multiplying by presence again would square the fade —
 * the same rule 30 applies to the accessory.
 */
fun sessionNavAlphas(
    phase: SessionNavPhase,
    animated: Boolean,
    progress: Float,
    reduceMotion: Boolean,
): SessionNavAlphas {
    val p = progress.coerceIn(0f, 1f)
    return when (phase) {
        SessionNavPhase.IdleTop, SessionNavPhase.Bystander -> SessionNavAlphas.Shown
        SessionNavPhase.IdleHidden -> SessionNavAlphas.Gone
        SessionNavPhase.Entering ->
            if (!animated || reduceMotion) SessionNavAlphas.Shown else SessionNavAlphas(stack = p, jump = 1f)
        SessionNavPhase.Leaving ->
            if (!animated || reduceMotion) SessionNavAlphas.Shown else SessionNavAlphas(stack = 1f - p, jump = 1f - p)
    }
}

/**
 * The two multipliers, read **only** inside graphicsLayer / draw / layout
 * lambdas or `snapshotFlow` (snapshot reads, no recomposition): 74 reads
 * [stackAlpha] in its slot placement, 131 reads [jumpAlpha] in its own
 * layer. Never `Modifier.alpha(nav.stackAlpha())` — that reads in
 * composition and recomposes the screen every frame of a transition.
 */
@Stable
interface SessionNavAlpha {
    /** iOS `bottom.alpha`, the bottom stack's multiplier (74's slot layers only). */
    fun stackAlpha(): Float

    /** iOS `jump.alpha`, the jump button's multiplier (131's own layer). */
    fun jumpAlpha(): Float

    /** Previews, gallery fixtures and tests without a [NavStack]: always 1. */
    object Static : SessionNavAlpha {
        override fun stackAlpha() = 1f
        override fun jumpAlpha() = 1f
    }
}

/**
 * The live [SessionNavAlpha] of one entry in one stack: both getters
 * derive from `stack.transition` / `stack.top` / [reduceMotion] on every
 * read, so a predictive-back gesture frame re-derives them with no
 * recomposition and no animation of their own.
 */
@Stable
class StackSessionNavAlpha internal constructor(
    private val stack: NavStack,
    private val entry: NavEntry,
) : SessionNavAlpha {
    /** Set from [rememberSessionNavAlpha] every composition; snapshot state. */
    internal var reduceMotion by mutableStateOf(false)

    /** The current phase + both multipliers, derived live. */
    fun alphas(): SessionNavAlphas {
        val t = stack.transition
        val phase = sessionNavPhase(
            hasTransition = t != null,
            kind = t?.kind,
            isTo = t?.to === entry,
            isFrom = t?.from === entry,
            isTop = stack.top === entry,
        )
        return sessionNavAlphas(phase, t?.animated ?: false, t?.progress ?: 0f, reduceMotion)
    }

    override fun stackAlpha() = alphas().stack

    override fun jumpAlpha() = alphas().jump
}

/**
 * The ambient [SessionNavAlpha]; [SessionNavAlpha.Static] outside a
 * session (so a directly composed `SessionScreen` in a test keeps the
 * pre-85 behaviour, α = 1).
 */
val LocalSessionNavAlpha = staticCompositionLocalOf<SessionNavAlpha> { SessionNavAlpha.Static }

/**
 * Remembers the entry's [SessionNavAlpha] and keeps its reduce-motion
 * flag fresh. Call where [sh.roboco.android.shell.LocalNavEntry] is
 * guaranteed (76's `SessionScreenRoute.Content()`); the gallery's
 * fixture session calls it inside its own `NavStackHost` content.
 */
@Composable
fun rememberSessionNavAlpha(stack: NavStack, entry: NavEntry): SessionNavAlpha {
    val a = remember(stack, entry) { StackSessionNavAlpha(stack, entry) }
    val reduce = LocalReduceMotion.current
    SideEffect { a.reduceMotion = reduce }
    return a
}
