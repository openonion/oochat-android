package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.domain.model.NotificationTarget
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** [target]'s conversation: that agent's chat, on that conversation when the target names one. */
internal fun notificationChatRoute(target: NotificationTarget): String {
    val session = target.sessionId?.let { "?sessionId=${Uri.encode(it)}" }.orEmpty()
    return "chat/${Uri.encode(target.agentAddress)}$session"
}

/**
 * Takes the user where a tapped notification points (W16AAPPLE-44): the
 * conversation, then the Work Room of the run when the notification was about
 * one, so Back from the room lands in its chat.
 *
 * A tap that starts the app goes through Loading first, which then replaces
 * itself with the chat, so it waits out Loading (up to [LOADING_WAIT_MS]). It
 * acts only if a chat is then on the back stack; Setup, the error screen or a
 * Loading that never finishes drop the tap. The chat that is there is replaced,
 * as when the user picks another agent, so its ViewModel never folds in another
 * conversation's events.
 *
 * @param isSavedAgent Whether the user has this agent saved. The activity is
 *   exported, so any app can hand it a target; one for an agent the user never
 *   added is ignored, rather than connecting to it unasked (a deep link to a new
 *   agent asks first, see DeepLinkConnectSheet).
 * @param onHandled Called once acted on or ignored, so the same tap is not acted on twice.
 */
@Composable
internal fun OpenNotificationTarget(
    navController: NavHostController,
    target: NotificationTarget?,
    isSavedAgent: suspend (String) -> Boolean,
    onHandled: () -> Unit
) {
    LaunchedEffect(target) {
        val tapped = target ?: return@LaunchedEffect
        if (!isSavedAgent(tapped.agentAddress)) {
            FileLogger.w(LogTags.NOTIFICATIONS, "Ignoring a notification target for an agent that is not saved")
            onHandled()
            return@LaunchedEffect
        }
        // Wait only while the app is still loading. If it lands anywhere but a
        // chat (Setup, or the error screen after a failed connection), the tap
        // is dropped: acting on it whenever the user next reached a chat, maybe
        // much later, would be a jump nobody asked for at that point.
        val settled = withTimeoutOrNull(LOADING_WAIT_MS) {
            navController.currentBackStackEntryFlow.first { entry ->
                chatEntryRoute(navController) != null || entry.destination.route != Routes.LOADING
            }
        }
        val chat = chatEntryRoute(navController)
        if (settled == null || chat == null) {
            FileLogger.i(LogTags.NOTIFICATIONS, "Dropping a notification tap: the app did not reach a chat")
            onHandled()
            return@LaunchedEffect
        }
        navController.navigate(notificationChatRoute(tapped)) {
            popUpTo(chat) { inclusive = true }
        }
        tapped.invocationId?.let { invocationId ->
            // The Work Room reads its run from the chat under it, which loads the
            // conversation only once it is on screen: covered at once, it never would.
            navController.currentBackStackEntry?.lifecycle?.currentStateFlow?.let { state ->
                withTimeoutOrNull(CHAT_SHOWN_WAIT_MS) { state.first { it.isAtLeast(Lifecycle.State.RESUMED) } }
            }
            navController.navigate(Routes.workRoom(invocationId))
        }
        onHandled()
    }
}

/**
 * How long a tap waits for Loading to finish. Loading ends on its own, in the
 * chat or on the error screen; this only bounds one that never does.
 */
private const val LOADING_WAIT_MS = 60_000L

/** How long a Work Room tap waits for its chat to show before opening the room anyway. */
private const val CHAT_SHOWN_WAIT_MS = 5_000L

/** The chat route on the back stack, or null while there is none yet (Loading, Onboarding). */
private fun chatEntryRoute(navController: NavHostController): String? =
    CHAT_ROUTES.firstOrNull { route -> runCatching { navController.getBackStackEntry(route) }.isSuccess }
