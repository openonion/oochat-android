package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.domain.model.canonicalAgentAddress
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing server connection configuration.
 *
 * Stores the app's current single-server configuration.
 * Interface allows future extension for multiple server profiles
 * without major refactoring.
 *
 * Implementation should use secure storage (EncryptedSharedPreferences)
 * for sensitive data like API keys.
 */
interface ConnectionConfigRepository {

    /**
     * Get the current connection configuration.
     * Returns null if no configuration exists.
     */
    suspend fun getConfig(): ConnectionConfig?

    /**
     * Observe the current connection configuration.
     * Emits null if no configuration exists.
     */
    fun observeConfig(): Flow<ConnectionConfig?>

    /**
     * Save a connection configuration.
     * Overwrites any existing configuration.
     */
    suspend fun saveConfig(config: ConnectionConfig)

    /**
     * Delete the current connection configuration.
     */
    suspend fun deleteConfig()

    /**
     * Check if a connection configuration exists.
     */
    suspend fun hasConfig(): Boolean

    /**
     * Update the last connected timestamp.
     */
    suspend fun updateLastConnected(timestamp: Long = System.currentTimeMillis())

    /**
     * Update the agent address for the current configuration.
     */
    suspend fun updateAgentAddress(address: String?)
}

/**
 * Saves [address] as the saved config's agent when that config is the direct
 * server at [serverUrl] and names no agent (W16AAPPLE-98). Such a config comes
 * from a version of the app that allowed it: the server URL then stood in for
 * the address, which the Host refuses. Once the server's /info has named the
 * agent, saving it lets the chat screen, and every later start, connect to it.
 * A config that already names an agent, or another server, is left as it is.
 */
suspend fun ConnectionConfigRepository.adoptResolvedAddress(serverUrl: String, address: String) {
    val config = getConfig() ?: return
    if (!config.agentAddress.isNullOrBlank()) return
    if (config.serverUrl.trim() != serverUrl.trim()) return
    updateAgentAddress(canonicalAgentAddress(address))
}
