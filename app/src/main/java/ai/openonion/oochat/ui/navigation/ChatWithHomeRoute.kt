package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.ui.adaptive.ChatWithHome
import ai.openonion.oochat.ui.adaptive.HomePlacement
import ai.openonion.oochat.ui.adaptive.HomeTab
import ai.openonion.oochat.ui.adaptive.Pane
import ai.openonion.oochat.ui.adaptive.currentHomePlacement
import ai.openonion.oochat.ui.chat.ChatViewModel
import ai.openonion.oochat.ui.common.appViewModel
import ai.openonion.oochat.ui.controlcenter.ControlCenterScreenWrapper
import ai.openonion.oochat.ui.controlcenter.controlCenterSupported
import ai.openonion.oochat.ui.dashboard.DashboardScreenWrapper
import ai.openonion.oochat.ui.home.AgentHome
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry

/**
 * A chat destination: the chat, and the agent's Home beside it when the window has room and the
 * agent has one (see [ai.openonion.oochat.ui.adaptive.homePlacementFor]).
 *
 * [chat] gets the [ChatViewModel] of [chatEntry], the one the Home pane reads from too, and
 * whether Home is already part of the layout, beside it or as a tab. It is always shown in the
 * same place, so folding or unfolding does not rebuild it; see [ChatWithHome].
 */
@Composable
internal fun ChatWithHomeRoute(
    chatEntry: NavBackStackEntry,
    chat: @Composable (viewModel: ChatViewModel, homeInLayout: Boolean) -> Unit
) {
    ChatWithHomePanes(
        viewModel = appViewModel(),
        hostsApps = ::controlCenterSupported,
        chat = chat,
        home = { opensControlCenter, besideChat, onNavigateBack ->
            if (opensControlCenter) {
                ControlCenterScreenWrapper(chatEntry, onNavigateBack = onNavigateBack, besideChat = besideChat)
            } else {
                DashboardScreenWrapper(chatEntry, onNavigateBack = onNavigateBack, besideChat = besideChat)
            }
        }
    )
}

/**
 * The panes of [ChatWithHomeRoute], with the parts that need a navigation back stack and a WebView
 * passed in. [hostsApps] says whether the device can host a Control Center app; [home] draws the
 * Home pane, as the Control Center when its first argument is true and as the Dashboard
 * otherwise. Its second says whether Home is beside the chat, and its third goes back to the chat
 * from Home when it is a tab of its own.
 */
@Composable
internal fun ChatWithHomePanes(
    viewModel: ChatViewModel,
    hostsApps: () -> Boolean,
    chat: @Composable (viewModel: ChatViewModel, homeInLayout: Boolean) -> Unit,
    home: @Composable (opensControlCenter: Boolean, besideChat: Boolean, onNavigateBack: () -> Unit) -> Unit
) {
    val dashboardHtml by viewModel.dashboardHtml.collectAsState()
    val controlCenterApp by viewModel.controlCenterApp.collectAsState()
    // Lazy: asking loads the WebView, which only an agent with both kinds of Home page needs.
    val webViewHostsApps = remember { lazy { hostsApps() } }
    val agentHome = AgentHome(dashboardHtml != null, controlCenterApp != null) { webViewHostsApps.value }
    val placement = currentHomePlacement(agentHome.canSitBesideChat)
    var tab by rememberSaveable { mutableStateOf(HomeTab.CHAT) }
    // Home can go, as it does when the agent reconnects, and must not bring its tab back with it
    // when it returns.
    LaunchedEffect(placement) {
        if (placement != HomePlacement.TABBED) tab = HomeTab.CHAT
    }
    // Back from a Home that has a tab of its own is back to the chat, as it is from a screen of its own.
    BackHandler(enabled = placement == HomePlacement.TABBED && tab == HomeTab.HOME) { tab = HomeTab.CHAT }

    ChatWithHome(
        placement = placement,
        tab = tab,
        onTabChange = { tab = it },
        // Named for what the agent sent, as the page it opens is.
        homeLabel = if (agentHome.opensControlCenter) "Control Center" else "Dashboard",
        chat = { modifier ->
            Pane(modifier) { chat(viewModel, placement != HomePlacement.OWN_SCREEN) }
        },
        home = { modifier ->
            Pane(modifier) {
                home(agentHome.opensControlCenter, placement == HomePlacement.BESIDE_CHAT) { tab = HomeTab.CHAT }
            }
        }
    )
}
