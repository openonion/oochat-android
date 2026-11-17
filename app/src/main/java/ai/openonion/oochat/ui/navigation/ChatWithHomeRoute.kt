package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.ui.adaptive.ChatWithHome
import ai.openonion.oochat.ui.adaptive.HomePlacement
import ai.openonion.oochat.ui.adaptive.Pane
import ai.openonion.oochat.ui.adaptive.currentHomePlacement
import ai.openonion.oochat.ui.chat.ChatViewModel
import ai.openonion.oochat.ui.common.appViewModel
import ai.openonion.oochat.ui.controlcenter.ControlCenterScreenWrapper
import ai.openonion.oochat.ui.controlcenter.controlCenterSupported
import ai.openonion.oochat.ui.dashboard.DashboardScreenWrapper
import ai.openonion.oochat.ui.home.AgentHome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry

/**
 * A chat destination: the chat, and the agent's Home beside it when the window has room and the
 * agent has one (see [ai.openonion.oochat.ui.adaptive.homePlacementFor]).
 *
 * [chat] gets the [ChatViewModel] of [chatEntry], the one the Home pane reads from too, and
 * whether Home is beside it. It is always shown in the same place, so folding or unfolding does
 * not rebuild it; see [ChatWithHome].
 */
@Composable
internal fun ChatWithHomeRoute(
    chatEntry: NavBackStackEntry,
    chat: @Composable (viewModel: ChatViewModel, homeBeside: Boolean) -> Unit
) {
    ChatWithHomePanes(
        viewModel = appViewModel(),
        hostsApps = ::controlCenterSupported,
        chat = chat,
        home = { opensControlCenter ->
            if (opensControlCenter) {
                ControlCenterScreenWrapper(chatViewModelStoreOwner = chatEntry, onNavigateBack = {}, besideChat = true)
            } else {
                DashboardScreenWrapper(chatViewModelStoreOwner = chatEntry, onNavigateBack = {}, besideChat = true)
            }
        }
    )
}

/**
 * The panes of [ChatWithHomeRoute], with the parts that need a navigation back stack and a WebView
 * passed in. [hostsApps] says whether the device can host a Control Center app; [home] draws the
 * Home pane, as the Control Center when its argument is true and as the Dashboard otherwise.
 */
@Composable
internal fun ChatWithHomePanes(
    viewModel: ChatViewModel,
    hostsApps: () -> Boolean,
    chat: @Composable (viewModel: ChatViewModel, homeBeside: Boolean) -> Unit,
    home: @Composable (opensControlCenter: Boolean) -> Unit
) {
    val dashboardHtml by viewModel.dashboardHtml.collectAsState()
    val controlCenterApp by viewModel.controlCenterApp.collectAsState()
    // Lazy: asking loads the WebView, which only an agent with both kinds of Home page needs.
    val webViewHostsApps = remember { lazy { hostsApps() } }
    val agentHome = AgentHome(dashboardHtml != null, controlCenterApp != null) { webViewHostsApps.value }
    val placement = currentHomePlacement(agentHome.canSitBesideChat)

    ChatWithHome(
        placement = placement,
        chat = { modifier ->
            Pane(modifier) { chat(viewModel, placement == HomePlacement.BESIDE_CHAT) }
        },
        home = { modifier ->
            Pane(modifier) { home(agentHome.opensControlCenter) }
        }
    )
}
