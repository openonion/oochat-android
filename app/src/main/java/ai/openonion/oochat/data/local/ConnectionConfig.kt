package ai.openonion.oochat.data.local

import ai.openonion.oochat.Constants
import kotlinx.serialization.Serializable

/**
 * The agent a cold start reconnects to (LoadingScreen). Kept separate from the multi-agent Room model;
 * the chat screen rewrites it whenever it connects, so it follows links and drawer switches too.
 */
@Serializable
data class ConnectionConfig(
    val serverUrl: String,
    val apiKey: String? = null,
    val agentAddress: String? = null,
    val lastConnected: Long? = null,
    val connectionTimeout: Long = 30_000L, // 30 seconds default
    // The agent's saved AgentProfile.connectionMode ("relay" or "direct").
    // Null for configs that never knew it (onboarding, older installs): those
    // keep the old rule, where any non-default serverUrl is dialled direct.
    val connectionMode: String? = null
) {
    val displayUrl: String
        get() = serverUrl
            .removePrefix("wss://")
            .removePrefix("ws://")
            .removePrefix("https://")
            .removePrefix("http://")

    val shortDisplayUrl: String
        get() {
            val url = displayUrl
            return if (url.length > 30) {
                "${url.take(15)}...${url.takeLast(12)}"
            } else {
                url
            }
        }

    val isValid: Boolean
        get() = serverUrl.isNotBlank()

    companion object {
        const val DEFAULT_RELAY_URL = "https://oo.openonion.ai"
        const val DEFAULT_TIMEOUT = 30_000L

        fun default(): ConnectionConfig {
            return ConnectionConfig(
                serverUrl = DEFAULT_RELAY_URL,
                connectionTimeout = DEFAULT_TIMEOUT
            )
        }
    }
}

/**
 * Resolved connect parameters derived from a [ConnectionConfig]: which agent
 * address to dial and, for a custom (non-default-relay) endpoint, whether to
 * reach it direct or through it as a self-hosted relay.
 *
 * @property agentAddress the address the WebSocket should open against.
 * @property directUrl the direct server URL, or `null` when the agent is
 *   reached through a relay (see [isDirect]).
 * @property isDirect whether the agent is dialled direct at [directUrl].
 * @property relayUrl the self-hosted relay to go through, or `null` for the
 *   default relay (and whenever [isDirect]).
 */
data class ConnectTarget(
    val agentAddress: String,
    val directUrl: String?,
    val isDirect: Boolean,
    val relayUrl: String? = null
)

/**
 * Derive the [ConnectTarget] for this config.
 *
 * `serverUrl` is the relay/direct *server* URL, not the agent address, so
 * passing it as the agent address would open the WebSocket at the wrong path.
 * The saved [ConnectionConfig.agentAddress] (falling back to `serverUrl` when
 * blank/absent) is what actually gets dialled; `serverUrl` only says where.
 * See [resolveServerRoute] for the rule.
 */
fun ConnectionConfig.toConnectTarget(): ConnectTarget {
    val route = resolveServerRoute(serverUrl, connectionMode)
    return ConnectTarget(
        agentAddress = agentAddress?.takeIf { it.isNotBlank() } ?: serverUrl,
        directUrl = route.directUrl,
        isDirect = route.directUrl != null,
        relayUrl = route.relayUrl
    )
}

/** Where to reach an agent: direct at [directUrl], or through [relayUrl] (null = default relay). */
data class ServerRoute(val directUrl: String?, val relayUrl: String?)

/**
 * The one rule for how a saved server URL is reached (W16AAPPLE-56):
 * - one of the default relays: through the default relay;
 * - otherwise, [connectionMode] "relay": through that server as a self-hosted
 *   relay (`/ws/input`) — what discovery saves for agents found on a relay
 *   the user typed in;
 * - otherwise ("direct", or unknown/null for legacy rows): direct (`/ws`).
 */
fun resolveServerRoute(serverUrl: String?, connectionMode: String?): ServerRoute = when {
    serverUrl.isNullOrBlank() || Constants.isDefaultServerUrl(serverUrl) -> ServerRoute(null, null)
    // Trimmed: the relay's path is appended, and discovery saves the URL as typed.
    connectionMode == CONNECTION_MODE_RELAY -> ServerRoute(directUrl = null, relayUrl = serverUrl.trimEnd('/'))
    else -> ServerRoute(directUrl = serverUrl, relayUrl = null)
}

const val CONNECTION_MODE_RELAY = "relay"
const val CONNECTION_MODE_DIRECT = "direct"
