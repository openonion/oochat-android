package ai.openonion.oochat.ui.adaptive

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The chat, and the agent's Home beside it when [placement] says so.
 *
 * The chat is always the first child of the same Row, whether or not Home is shown, so folding or
 * unfolding a device adds and removes Home without moving the chat in the composition. A chat that
 * moved would be rebuilt from nothing and lose its typed text and scroll position.
 */
@Composable
internal fun ChatWithHome(
    placement: HomePlacement,
    chat: @Composable (Modifier) -> Unit,
    home: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        chat(Modifier.weight(1f))
        if (placement == HomePlacement.BESIDE_CHAT) {
            VerticalDivider()
            home(Modifier.weight(1f))
        }
    }
}

/** Where Home goes in the window this is composed in; see [homePlacementFor]. */
@Composable
internal fun currentHomePlacement(hasHome: Boolean): HomePlacement =
    homePlacementFor(currentWindowAdaptiveInfo().windowSizeClass, hasHome)
