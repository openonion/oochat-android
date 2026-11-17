package ai.openonion.oochat.ui.dashboard

import ai.openonion.oochat.ui.chat.ChatViewModel
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Binds [DashboardScreen] to the chat this dashboard belongs to.
 *
 * [chatViewModelStoreOwner] is the chat destination's own back-stack entry, so
 * this resolves the *same* [ChatViewModel] the conversation underneath is
 * using — which is what lets a skill button go out through the ordinary send
 * path, with the agent target and session already resolved, and land as a
 * visible user turn. A ViewModel scoped to this route instead would have no
 * target address and the send would be dropped.
 *
 * The allowlist is the live `AGENT_PROFILE`'s skills. Null until that frame
 * arrives, and [DashboardSkillIntent] refuses everything while it is.
 *
 * @param besideChat The page is next to the chat, not in place of it. It then has no way back to
 *   show, and running a skill leaves it where it is rather than going back to the chat.
 */
@Composable
fun DashboardScreenWrapper(
    chatViewModelStoreOwner: ViewModelStoreOwner?,
    onNavigateBack: () -> Unit,
    besideChat: Boolean = false
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = chatViewModelStoreOwner
            ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" },
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )

    val html by viewModel.dashboardHtml.collectAsState()
    val profile by viewModel.agentProfile.collectAsState()
    val awaitsUser by viewModel.chatAwaitsUser.collectAsState()

    DashboardScreen(
        html = html,
        allowedSkills = profile?.skills?.map { it.name },
        onRunSkill = { message -> runSkillFromHome(message, besideChat, viewModel::sendMessage, onNavigateBack) },
        onNavigateBack = onNavigateBack,
        agentName = profile?.name,
        chatAwaitsUser = awaitsUser,
        besideChat = besideChat
    )
}

/**
 * Sends the message of a skill run from the Home page as the user's own. On a screen of its own
 * that is followed by going back to the conversation, where the turn it started is; beside the chat
 * the conversation is already in view.
 */
internal fun runSkillFromHome(
    message: String,
    besideChat: Boolean,
    send: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    send(message)
    if (!besideChat) onNavigateBack()
}
