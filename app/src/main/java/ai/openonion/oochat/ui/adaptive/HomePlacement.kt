package ai.openonion.oochat.ui.adaptive

import androidx.window.core.layout.WindowSizeClass

/** Where the agent's Home is shown. */
internal enum class HomePlacement {
    OWN_SCREEN,

    /** Next to the chat, so both can be used at once. */
    BESIDE_CHAT
}

/**
 * Where Home goes in a window of [windowSize]. Beside the chat from the Medium width class up
 * (600dp: a tablet, or a foldable unfolded) as long as the window is tall enough for two panes,
 * which keeps a phone on its side out. On its own screen otherwise, and always when there is no
 * Home to show ([hasHome] false), so no empty pane is ever drawn.
 */
internal fun homePlacementFor(windowSize: WindowSizeClass, hasHome: Boolean): HomePlacement {
    val roomForTwoPanes = windowSize.isAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND
    )
    return if (hasHome && roomForTwoPanes) HomePlacement.BESIDE_CHAT else HomePlacement.OWN_SCREEN
}
