package ai.openonion.oochat.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints

/** The two tabs of a phone's chat: the conversation, and the agent's Home. */
internal enum class HomeTab { CHAT, HOME }

/**
 * The chat, and the agent's Home beside it when [placement] says so.
 *
 * The chat is always the first child of the same Row, whether or not Home is shown, so folding or
 * unfolding a device adds and removes Home without moving the chat in the composition. A chat that
 * moved would be rebuilt from nothing and lose its typed text and scroll position.
 *
 * With [HomePlacement.TABBED] a row of tabs goes above them and [tab] says which one is showing;
 * [onTabChange] is asked for the other when its tab is chosen, and [homeLabel] is the name on
 * Home's. The tab not showing is shrunk to nothing rather than taken out, so what it holds (the
 * transcript's place, a draft, a page's state) is still there when it is chosen again. Home is
 * only built the first time its tab is opened, so an agent's page is not loaded for someone who
 * never looks at it.
 */
@Composable
internal fun ChatWithHome(
    placement: HomePlacement,
    tab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    homeLabel: String,
    chat: @Composable (Modifier) -> Unit,
    home: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabbed = placement == HomePlacement.TABBED
    val beside = placement == HomePlacement.BESIDE_CHAT
    var homeOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tabbed, tab) {
        if (tabbed && tab == HomeTab.HOME) homeOpened = true
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (tabbed) HomeTabs(tab, homeLabel, onTabChange)
        // The tabs have taken the status bar's inset, so the panes under them must not leave room
        // for it too.
        val belowTabs = if (tabbed) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
        Row(modifier = Modifier.weight(1f).fillMaxWidth().then(belowTabs)) {
            chat(if (tabbed && tab == HomeTab.HOME) OutOfSight else Modifier.weight(1f))
            if (beside) VerticalDivider()
            if (beside || (tabbed && (tab == HomeTab.HOME || homeOpened))) {
                home(if (tabbed && tab == HomeTab.CHAT) OutOfSight else Modifier.weight(1f))
            }
        }
    }
}

/**
 * A pane that takes no room: it is still composed, so it keeps its state, but nothing of it shows
 * or is read out by a screen reader. It is still measured at the size it would have, because a
 * pane squeezed to nothing would let things that work out their place from their size, like the
 * chat's navigation drawer, lose it. The clip comes first so that it is to the empty box the pane
 * takes, not to its content's size: where that content lands depends on which way the row runs,
 * and right to left it lands over the other tab.
 */
private val OutOfSight: Modifier = Modifier
    .clipToBounds()
    .clearAndSetSemantics {}
    .layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight))
        layout(0, 0) { placeable.place(0, 0) }
    }

@Composable
internal fun HomeTabs(tab: HomeTab, homeLabel: String, onTabChange: (HomeTab) -> Unit) {
    // A chat shrunk to nothing keeps the focus, and with it the keyboard, unless it is let go.
    val focusManager = LocalFocusManager.current
    val choose = { chosen: HomeTab ->
        focusManager.clearFocus()
        onTabChange(chosen)
    }
    // The tabs take the status bar's inset, which the chat's own top bar below would take otherwise.
    // The tab not shown is grey and the rule under the row a hairline, so the
    // green of the chosen tab is the only accent up here.
    val unselected = MaterialTheme.colorScheme.onSurfaceVariant
    PrimaryTabRow(
        selectedTabIndex = tab.ordinal,
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)) }
    ) {
        Tab(
            selected = tab == HomeTab.CHAT,
            onClick = { choose(HomeTab.CHAT) },
            text = { Text("Chat", fontWeight = FontWeight.SemiBold) },
            unselectedContentColor = unselected
        )
        Tab(
            selected = tab == HomeTab.HOME,
            onClick = { choose(HomeTab.HOME) },
            text = { Text(homeLabel, fontWeight = FontWeight.SemiBold) },
            unselectedContentColor = unselected
        )
    }
}

/** Where Home goes in the window this is composed in; see [homePlacementFor]. */
@Composable
internal fun currentHomePlacement(hasHome: Boolean): HomePlacement =
    homePlacementFor(currentWindowAdaptiveInfo().windowSizeClass, hasHome)
