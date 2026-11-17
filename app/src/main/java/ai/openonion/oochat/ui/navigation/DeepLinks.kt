package ai.openonion.oochat.ui.navigation

/**
 * The links that open the app on an agent, each ending in the agent's address.
 *
 * The manifest's intent filters decide which links Android offers to the app; the nav graph
 * decides which of those it understands. Both read from here so they cannot drift apart, which
 * is how the website's QR codes came to open in the browser (W16AAPPLE-78).
 */
internal object DeepLinks {

    /** The name the nav graph gives the address in each pattern. */
    const val ADDRESS = "address"

    /** The app's own link: `connectonion://agent/<address>`. */
    const val SCHEME_PATTERN = "connectonion://agent/{$ADDRESS}"

    /** Hosts whose `https://<host>/<address>` link opens the app. */
    val WEB_HOSTS = listOf("chat.openonion.ai", "oo-chat.com")

    /** Every pattern the nav graph accepts. */
    val patterns: List<String> = listOf(SCHEME_PATTERN) + WEB_HOSTS.map { "https://$it/{$ADDRESS}" }
}
