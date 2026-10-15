package sh.roboco.android.sidechats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.newsession.NewSessionScreenBody
import sh.roboco.android.newsession.SideChatHeadline
import sh.roboco.android.newsession.SideChatParent
import sh.roboco.android.newsession.rememberCoreFileSearch
import sh.roboco.android.newsession.rememberNewSessionModel
import sh.roboco.android.session.SessionHeader
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.LocalRouterHooks
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.OPEN_ERROR_TOAST
import sh.roboco.android.shell.openSessionRoute

/** The draft's bar title and subtitle (upstream #899's `sideOf` title view). */
object SideChatDraftCopy {
    const val Title = "New Side Chat"

    fun subtitle(parentTitle: String): String =
        if (parentTitle.isBlank()) "Side chat" else "Side chat · $parentTitle"
}

/**
 * A new, empty side chat (android-next #06, upstream #899's
 * `NewSessionViewController(sideOf:)`): the New Session canvas pushed over
 * the session, starting on the parent's model. It runs in the parent's
 * checkout on the parent's host, so only the model and effort chips show
 * (any harness, until the first send mints the row). Nothing is written
 * until that send — leaving first leaves nothing; a draft opened from a
 * side chat makes a sibling (the core hangs it off the same root).
 *
 * The first send creates the chat (`createChat` with `parentChatId`),
 * opens it, and swaps it in for this draft, in place (iOS `arrive(at:)`).
 *
 * Identity equality on purpose (a plain class): `NavStack.setRoutes`
 * matches routes by equality, and two drafts are two screens.
 */
class SideChatDraftRoute(val parent: SideChatParent) : NavRoute {
    override val hidesBottomChrome: Boolean get() = true

    override val paneTitle: String get() = SideChatDraftCopy.Title

    @Composable
    fun Content() {
        SideChatDraftScreen(this)
    }
}

@Composable
private fun SideChatDraftScreen(route: SideChatDraftRoute) {
    val nav = LocalNavStack.current
    val entry = LocalNavEntry.current
    val hooks = LocalRouterHooks.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val model = rememberNewSessionModel(prompt = null, side = route.parent)
    Box(Modifier.fillMaxSize()) {
        NewSessionScreenBody(
            ns = model,
            embedded = true,
            onCreated = { chatId, _ ->
                // iOS `arrive(at:)`: open the chat, then swap it in for the
                // draft, in place (no push animation; the draft's own
                // composer already cleared). A draft the user left
                // meanwhile opens nothing — the side chat lists under its
                // parent.
                scope.launch {
                    val session = hooks.openSessionRoute(chatId)
                    when {
                        session == null -> {
                            toaster.show(OPEN_ERROR_TOAST)
                            if (nav.top === entry && nav.transition == null) nav.pop(animated = true)
                        }
                        entry !in nav.entries -> session.discard()
                        else -> nav.setRoutes(
                            nav.entries.map { if (it === entry) session as NavRoute else it.route },
                        )
                    }
                }
            },
            onClose = {},
            focusOnAppear = true,
            fileSearch = rememberCoreFileSearch(),
            headline = SideChatHeadline,
            navBar = false,
        )
        SessionHeader(
            title = SideChatDraftCopy.Title,
            subtitle = SideChatDraftCopy.subtitle(route.parent.title),
            edgeEngaged = false,
            onMore = {},
            showMore = false,
        )
    }
}
