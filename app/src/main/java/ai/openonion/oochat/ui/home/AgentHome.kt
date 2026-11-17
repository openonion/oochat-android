package ai.openonion.oochat.ui.home

/**
 * What an agent's Home is on this device: whether there is one, which screen opens it, and
 * whether it can sit beside the chat. The chat screen and the pane beside the chat both ask this,
 * so they cannot disagree.
 *
 * An agent can send a Dashboard page, a Control Center app, or both. [appSupported] says whether
 * the device's WebView can host the app; it loads the WebView, so it is only asked when the answer
 * changes something.
 */
internal class AgentHome(
    private val hasDashboard: Boolean,
    private val hasApp: Boolean,
    private val appSupported: () -> Boolean
) {
    /** The agent sent a Home page of some kind, so there is something for the Home button to open. */
    val hasPage: Boolean get() = hasDashboard || hasApp

    /**
     * Home opens the Control Center rather than the Dashboard: wherever the device can host the
     * app, and also when there is no Dashboard, so the screen can say why it cannot.
     */
    val opensControlCenter: Boolean get() = hasApp && (!hasDashboard || appSupported())

    /**
     * Home can be drawn beside the chat: a Dashboard, or a Control Center the device can host. A
     * Control Center it cannot host stays behind the Home button, where its notice has a screen.
     */
    val canSitBesideChat: Boolean get() = hasDashboard || (hasApp && appSupported())
}
