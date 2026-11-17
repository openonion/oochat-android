package ai.openonion.oochat.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * The destination that shows Home on a screen of its own. When the window has room for Home
 * beside the chat there is no such screen: it calls [onLeave], once, to go back to the chat, and
 * shows nothing in the meantime. That is a foldable unfolded while it was showing Home on its own
 * screen. [placement] is the window's, unless a test says otherwise.
 */
@Composable
internal fun HomeScreenRoute(
    onLeave: () -> Unit,
    placement: HomePlacement = currentHomePlacement(hasHome = true),
    content: @Composable () -> Unit
) {
    LaunchedEffect(placement) {
        if (placement == HomePlacement.BESIDE_CHAT) onLeave()
    }
    if (placement == HomePlacement.OWN_SCREEN) content()
}
