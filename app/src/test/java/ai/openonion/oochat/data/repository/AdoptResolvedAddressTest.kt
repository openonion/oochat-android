package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.ConnectionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A saved direct server with no agent address is given the one its /info
 * names, and nothing else is changed (W16AAPPLE-98).
 */
class AdoptResolvedAddressTest {

    private class FakeConfigRepository(var config: ConnectionConfig?) : ConnectionConfigRepository {
        var saves = 0
        override suspend fun getConfig(): ConnectionConfig? = config
        override fun observeConfig(): Flow<ConnectionConfig?> = flowOf(config)
        override suspend fun saveConfig(config: ConnectionConfig) {
            saves++
            this.config = config
        }
        override suspend fun deleteConfig() { config = null }
        override suspend fun hasConfig(): Boolean = config != null
        override suspend fun updateLastConnected(timestamp: Long) {}
        override suspend fun updateAgentAddress(address: String?) {
            config?.let { saveConfig(it.copy(agentAddress = address)) }
        }
    }

    private val server = "https://agent.example.com"
    private val address = "0x" + "AB12".repeat(16)

    @Test
    fun `a saved direct server with no address is given the one its info names`() = runBlocking {
        val repo = FakeConfigRepository(ConnectionConfig(serverUrl = server, connectionMode = "direct"))

        repo.adoptResolvedAddress(server, address)

        assertEquals("saved in the app's one form, lower case", address.lowercase(), repo.config?.agentAddress)
        assertEquals(server, repo.config?.serverUrl)
        assertEquals("direct", repo.config?.connectionMode)
    }

    @Test
    fun `a blank address counts as none`() = runBlocking {
        val repo = FakeConfigRepository(ConnectionConfig(serverUrl = server, agentAddress = "  "))

        repo.adoptResolvedAddress(server, address)

        assertEquals(address.lowercase(), repo.config?.agentAddress)
    }

    @Test
    fun `a config that already names an agent is left alone`() = runBlocking {
        val saved = "0x" + "cd34".repeat(16)
        val repo = FakeConfigRepository(ConnectionConfig(serverUrl = server, agentAddress = saved))

        repo.adoptResolvedAddress(server, address)

        assertEquals(saved, repo.config?.agentAddress)
        assertEquals(0, repo.saves)
    }

    @Test
    fun `a config for another server is left alone`() = runBlocking {
        val repo = FakeConfigRepository(ConnectionConfig(serverUrl = "https://other.example.com"))

        repo.adoptResolvedAddress(server, address)

        assertNull(repo.config?.agentAddress)
        assertEquals(0, repo.saves)
    }

    @Test
    fun `with no saved config there is nothing to change`() = runBlocking {
        val repo = FakeConfigRepository(null)

        repo.adoptResolvedAddress(server, address)

        assertNull(repo.config)
        assertEquals(0, repo.saves)
    }
}
