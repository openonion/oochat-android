package ai.openonion.oochat.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The socket a direct agent is dialled on, from its saved server URL
 * (W16AAPPLE-54, W16AAPPLE-55). Setup and the Add/Edit Agent form both accept
 * all four schemes, and an agent saved before the form trimmed its URL can
 * still end in a slash.
 */
class DirectSocketUrlTest {

    private fun dialled(serverUrl: String) = AgentConnection.directSocketUrl(serverUrl)

    @Test
    fun `each scheme the forms accept is dialled at its own host`() {
        assertEquals("wss://agent.example.com/ws", dialled("https://agent.example.com"))
        assertEquals("ws://agent.example.com/ws", dialled("http://agent.example.com"))
        assertEquals("wss://agent.example.com/ws", dialled("wss://agent.example.com"))
        assertEquals("ws://agent.example.com/ws", dialled("ws://agent.example.com"))
    }

    @Test
    fun `a URL with no scheme is dialled plain, as before`() {
        assertEquals("ws://192.168.1.20:8000/ws", dialled("192.168.1.20:8000"))
    }

    @Test
    fun `a trailing slash, or several, is dropped`() {
        assertEquals("wss://agent.example.com/ws", dialled("https://agent.example.com/"))
        assertEquals("ws://agent.example.com/ws", dialled("ws://agent.example.com//"))
    }

    @Test
    fun `a port and a path are kept`() {
        assertEquals("ws://localhost:8000/ws", dialled("http://localhost:8000"))
        assertEquals("wss://example.com/agents/sam/ws", dialled("https://example.com/agents/sam/"))
    }

    @Test
    fun `a URL that already ends in ws, as the Host announces it, is not given a second one`() {
        // W16AAPPLE-118: used to be dialled at /ws/ws, which the Host refuses.
        assertEquals("ws://10.5.27.133:8797/ws", dialled("ws://10.5.27.133:8797/ws"))
        assertEquals("wss://agent.example.com/ws", dialled("https://agent.example.com/ws/"))
        assertEquals("wss://example.com/agents/sam/ws", dialled("wss://example.com/agents/sam/ws"))
    }

    @Test
    fun `a ws that is only part of the last segment is still given its own ws`() {
        assertEquals("wss://example.com/news/ws", dialled("https://example.com/news"))
        assertEquals("ws://ws/ws", dialled("http://ws"))
    }

    @Test
    fun `the scheme is read in any case, and surrounding spaces are ignored`() {
        assertEquals("wss://agent.example.com/ws", dialled("  HTTPS://agent.example.com "))
        assertEquals("wss://agent.example.com/ws", dialled("WSS://agent.example.com"))
    }
}
