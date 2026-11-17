package ai.openonion.oochat.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigTest {
    @Test
    fun defaultConfigUsesConnectOnionRelay() {
        val config = ConnectionConfig.default()

        assertEquals(ConnectionConfig.DEFAULT_RELAY_URL, config.serverUrl)
        assertEquals(ConnectionConfig.DEFAULT_TIMEOUT, config.connectionTimeout)
        assertTrue(config.isValid)
    }

    @Test
    fun defaultConfigHasNoOptionalCredentials() {
        val config = ConnectionConfig.default()

        assertNull(config.apiKey)
        assertNull(config.agentAddress)
        assertNull(config.lastConnected)
    }

    @Test
    fun configWithValidUrlIsValid() {
        val config = ConnectionConfig(serverUrl = "wss://oo.openonion.ai")

        assertTrue(config.isValid)
    }

    @Test
    fun configWithEmptyUrlIsInvalid() {
        val config = ConnectionConfig(serverUrl = "")

        assertFalse(config.isValid)
    }

    @Test
    fun configWithBlankUrlIsInvalid() {
        val config = ConnectionConfig(serverUrl = "   ")

        assertFalse(config.isValid)
    }

    @Test
    fun displayUrlRemovesWssProtocol() {
        val config = ConnectionConfig(serverUrl = "wss://oo.openonion.ai")

        assertEquals("oo.openonion.ai", config.displayUrl)
    }

    @Test
    fun displayUrlRemovesWsProtocol() {
        val config = ConnectionConfig(serverUrl = "ws://localhost:8080")

        assertEquals("localhost:8080", config.displayUrl)
    }

    @Test
    fun displayUrlRemovesHttpProtocol() {
        val config = ConnectionConfig(serverUrl = "http://localhost:3000")

        assertEquals("localhost:3000", config.displayUrl)
    }

    @Test
    fun shortDisplayUrlTruncatesLongValues() {
        val config = ConnectionConfig(serverUrl = "https://very-long-agent-hostname.example.com:8080")

        assertEquals("very-long-agent...ple.com:8080", config.shortDisplayUrl)
    }

    @Test
    fun shortDisplayUrlKeepsShortValues() {
        val config = ConnectionConfig(serverUrl = "https://short.example")

        assertEquals("short.example", config.shortDisplayUrl)
    }

    @Test
    fun configCopyPreservesAndOverridesValues() {
        val config = ConnectionConfig(
            serverUrl = "wss://oo.openonion.ai",
            apiKey = "test-key",
            agentAddress = "test-agent",
            lastConnected = 123L,
            connectionTimeout = 5_000L
        )

        val copy = config.copy(serverUrl = "https://direct.example")

        assertEquals("https://direct.example", copy.serverUrl)
        assertEquals("test-key", copy.apiKey)
        assertEquals("test-agent", copy.agentAddress)
        assertEquals(123L, copy.lastConnected)
        assertEquals(5_000L, copy.connectionTimeout)
    }

    // ── toConnectTarget: which way a saved config is dialled ──────────
    //
    // Direct vs relay is decided here, from the server URL alone: one of the
    // known relays means relay, anything else is an agent's own endpoint.

    private val agent = "0x" + "ab".repeat(32)

    @Test
    fun defaultRelayConfigConnectsThroughTheRelay() {
        val target = ConnectionConfig(serverUrl = "https://oo.openonion.ai", agentAddress = agent).toConnectTarget()

        assertFalse(target.isDirect)
        assertNull("the relay URL is not a direct URL", target.directUrl)
        assertEquals(agent, target.agentAddress)
    }

    @Test
    fun everySpellingOfTheDefaultRelayConnectsThroughTheRelay() {
        for (url in listOf("https://oo.openonion.ai/", "wss://oo.openonion.ai", "ws://oo.openonion.ai", "http://oo.openonion.ai")) {
            assertFalse(url, ConnectionConfig(serverUrl = url, agentAddress = agent).toConnectTarget().isDirect)
        }
    }

    // A config with no recorded mode (written before W16AAPPLE-56) keeps the
    // old rule: any custom server is dialled direct.
    @Test
    fun customServerUrlConnectsDirectToThatUrl() {
        val target = ConnectionConfig(serverUrl = "https://agent.example.com", agentAddress = agent).toConnectTarget()

        assertTrue(target.isDirect)
        assertEquals("https://agent.example.com", target.directUrl)
        assertEquals(agent, target.agentAddress)
    }

    @Test
    fun customServerSavedAsDirectConnectsDirect() {
        val target = ConnectionConfig(
            serverUrl = "https://agent.example.com",
            agentAddress = agent,
            connectionMode = "direct"
        ).toConnectTarget()

        assertTrue(target.isDirect)
        assertEquals("https://agent.example.com", target.directUrl)
        assertNull(target.relayUrl)
    }

    @Test
    fun customServerSavedAsRelayConnectsThroughThatRelay() {
        val target = ConnectionConfig(
            serverUrl = "https://relay.example.com",
            agentAddress = agent,
            connectionMode = "relay"
        ).toConnectTarget()

        assertFalse(target.isDirect)
        assertNull("a self-hosted relay is not a direct URL", target.directUrl)
        assertEquals("https://relay.example.com", target.relayUrl)
        assertEquals(agent, target.agentAddress)
    }

    @Test
    fun selfHostedRelayTypedWithATrailingSlashLosesIt() {
        // The relay's /ws/input is appended, so "https://relay.example.com/" must not become "//ws/input".
        val target = ConnectionConfig(
            serverUrl = "https://relay.example.com/",
            agentAddress = agent,
            connectionMode = "relay"
        ).toConnectTarget()

        assertEquals("https://relay.example.com", target.relayUrl)
    }

    @Test
    fun defaultRelaySavedInEitherModeUsesTheDefaultRelay() {
        for (mode in listOf("relay", "direct", null)) {
            val target = ConnectionConfig(
                serverUrl = ConnectionConfig.DEFAULT_RELAY_URL,
                agentAddress = agent,
                connectionMode = mode
            ).toConnectTarget()

            assertFalse("$mode", target.isDirect)
            assertNull("$mode", target.directUrl)
            assertNull("$mode: the default relay needs no override", target.relayUrl)
        }
    }

    @Test
    fun blankAgentAddressFallsBackToTheServerUrl() {
        val target = ConnectionConfig(serverUrl = "https://agent.example.com", agentAddress = " ").toConnectTarget()

        assertEquals("https://agent.example.com", target.agentAddress)
    }
}
