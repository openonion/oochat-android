package ai.openonion.oochat.network

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.crypto.TestIdentity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSyncClientTest {
    private class FakeWebSocket : WebSocket {
        val sent = mutableListOf<String>()
        var closeCode: Int? = null

        override fun request(): Request = Request.Builder().url("wss://relay.example/ws/input").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = sent.add(text)
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }
        override fun cancel() = Unit
    }

    private class FakeWebSocketFactory : WebSocketFactory {
        lateinit var request: Request
        lateinit var listener: WebSocketListener
        val socket = FakeWebSocket()

        override fun create(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            return socket
        }
    }

    private lateinit var keyManager: KeyManager
    private lateinit var factory: FakeWebSocketFactory
    private lateinit var client: SessionSyncClient
    private var nonce = 0
    private var requestId = 0
    private val signedPayloads = mutableListOf<String>()

    @Before
    fun setUp() {
        keyManager = KeyManager(ApplicationProvider.getApplicationContext())
        keyManager.save(TestIdentity.keys())
        factory = FakeWebSocketFactory()
        client = SessionSyncClient(
            keyManager = keyManager,
            relayUrl = "https://relay.example",
            webSocketFactory = factory,
            signer = { _, canonical ->
                signedPayloads += canonical
                "fake-signature"
            },
            requestIdFactory = { "request-${++requestId}" },
            nonceFactory = { "nonce-${++nonce}" }
        )
    }

    private fun response(): Response =
        Response.Builder()
            .request(factory.request)
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

    private fun json(index: Int): JsonObject =
        Json.parseToJsonElement(factory.socket.sent[index]).jsonObject

    private fun command(index: Int): JsonObject = json(index)["payload"]!!.jsonObject

    private fun connected() =
        """{"type":"CONNECTED","status":"index","protocol":{"name":"oip","version":"0.1","min_version":"0.1","max_version":"0.1","extensions":{"session-sync":"0.1"}}}"""

    @Test
    fun `index CONNECT is signed without chat state and every page is collected`() = runTest {
        val result = async { client.fetch(agentAddress = "0xagent", cursor = "old-cursor") }
        runCurrent()

        // OkHttp stores a WebSocket URL as its HTTP upgrade URL.
        assertEquals("https://relay.example/ws/input", factory.request.url.toString())
        factory.listener.onOpen(factory.socket, response())
        val connect = json(0)
        val payload = connect["payload"]!!.jsonObject
        assertEquals("CONNECT", connect["type"]!!.jsonPrimitive.content)
        assertEquals("0xagent", connect["to"]!!.jsonPrimitive.content)
        assertEquals("0xagent", payload["to"]!!.jsonPrimitive.content)
        assertEquals(1, payload["session_sync_only"]!!.jsonPrimitive.int)
        assertEquals(
            listOf("0.1"),
            payload["extensions"]!!.jsonObject["session-sync"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertFalse("index CONNECT must not resume a chat", "session_id" in connect)
        assertFalse("index CONNECT must not send a replay cursor", "last_msg_id" in connect)
        assertFalse("index CONNECT must not carry chat state", "session" in connect)
        assertEquals(keyManager.canonicalJson(payload), signedPayloads.single())

        factory.listener.onMessage(factory.socket, connected())
        val first = command(1)
        assertEquals("SESSION_SYNC", first["type"]!!.jsonPrimitive.content)
        assertEquals("request-1", first["request_id"]!!.jsonPrimitive.content)
        assertEquals("old-cursor", first["cursor"]!!.jsonPrimitive.content)
        assertEquals(50, first["limit"]!!.jsonPrimitive.int)
        assertFalse(first["include_archived"]!!.jsonPrimitive.boolean)
        assertEquals("0xagent", first["to"]!!.jsonPrimitive.content)
        assertEquals(keyManager.canonicalJson(first), signedPayloads[1])

        factory.listener.onMessage(
            factory.socket,
            """{"type":"SESSION_SYNC_RESULT","request_id":"request-1","sessions":[{"session_id":"s1","revision":1,"title":"One","activity":"idle","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-02T00:00:00Z","last_sequence":2}],"removed_session_ids":[],"next_page_token":"page-2"}"""
        )
        val second = command(2)
        assertEquals("request-2", second["request_id"]!!.jsonPrimitive.content)
        assertEquals("page-2", second["page_token"]!!.jsonPrimitive.content)
        assertNull(second["cursor"])

        factory.listener.onMessage(
            factory.socket,
            """{"type":"SESSION_SYNC_RESULT","request_id":"request-2","sessions":[{"session_id":"s2","revision":3,"title":"Two","activity":"waiting","created_at":"2026-10-03T00:00:00Z","updated_at":"2026-10-04T00:00:00Z","last_sequence":7}],"removed_session_ids":["gone"],"cursor":"new-cursor"}"""
        )

        val complete = result.await() as SessionSyncFetchResult.Complete
        assertEquals(listOf("s1", "s2"), complete.snapshot.sessions.map { it.sessionId })
        assertEquals(listOf("gone"), complete.snapshot.removedSessionIds)
        assertEquals("new-cursor", complete.snapshot.cursor)
        assertFalse(complete.snapshot.isFullSnapshot)
        assertEquals(1000, factory.socket.closeCode)
    }

    @Test
    fun `request is not sent until Host selects the exact extension`() = runTest {
        val result = async { client.fetch("0xagent") }
        runCurrent()
        factory.listener.onOpen(factory.socket, response())
        assertEquals(1, factory.socket.sent.size)

        factory.listener.onMessage(
            factory.socket,
            """{"type":"CONNECTED","status":"index","protocol":{"name":"oip","version":"0.1","extensions":{"session-sync":"0.2"}}}"""
        )

        assertTrue(result.await() is SessionSyncFetchResult.Unsupported)
        assertEquals("no command after refused negotiation", 1, factory.socket.sent.size)
        assertEquals(1000, factory.socket.closeCode)
    }

    @Test
    fun `direct index CONNECT signs the Host recipient without a relay route`() = runTest {
        val result = async { client.fetch("0xagent", directUrl = "https://agent.example") }
        runCurrent()
        factory.listener.onOpen(factory.socket, response())

        val connect = json(0)
        assertEquals("0xagent", connect["payload"]!!.jsonObject["to"]!!.jsonPrimitive.content)
        assertFalse("direct CONNECT has no top-level relay recipient", "to" in connect)

        result.cancel()
    }

    @Test
    fun `a direct wss index is dialled at its own host over TLS`() = runTest {
        // W16AAPPLE-54: used to become ws://wss://agent.example/ws, a host called "wss".
        val result = async { client.fetch("0xagent", directUrl = "wss://agent.example") }
        runCurrent()

        // OkHttp reports a wss:// request as https://.
        assertEquals("https://agent.example/ws", factory.request.url.toString())

        result.cancel()
    }

    @Test
    fun `a direct index saved with a trailing slash is dialled without a double slash`() = runTest {
        // W16AAPPLE-55: an agent saved before the form trimmed its URL still has one.
        val result = async { client.fetch("0xagent", directUrl = "https://agent.example/") }
        runCurrent()

        assertEquals("https://agent.example/ws", factory.request.url.toString())

        result.cancel()
    }

    @Test
    fun `a direct index saved already ending in ws is dialled there, not at ws ws`() = runTest {
        // W16AAPPLE-118: the socket the Host announces; it refuses /ws/ws.
        val result = async { client.fetch("0xagent", directUrl = "ws://10.5.27.133:8797/ws") }
        runCurrent()

        assertEquals("http://10.5.27.133:8797/ws", factory.request.url.toString())

        result.cancel()
    }

    @Test
    fun `a self-hosted relay index goes through that relay with the relay route`() = runTest {
        // W16AAPPLE-56: a relay-mode agent on its own relay is indexed through that relay, not the client's.
        val result = async { client.fetch("0xagent", relayUrl = "https://my-relay.example") }
        runCurrent()

        assertEquals("https://my-relay.example/ws/input", factory.request.url.toString())
        factory.listener.onOpen(factory.socket, response())
        assertEquals("0xagent", json(0)["to"]!!.jsonPrimitive.content)

        result.cancel()
    }

    @Test
    fun `ordinary chat CONNECTED after offering the extension is unsupported`() = runTest {
        val result = async { client.fetch("0xagent") }
        runCurrent()
        factory.listener.onOpen(factory.socket, response())

        factory.listener.onMessage(
            factory.socket,
            """{"type":"CONNECTED","status":"connected","protocol":{"name":"oip","version":"0.1","extensions":{"session-sync":"0.1"}}}"""
        )

        assertTrue(result.await() is SessionSyncFetchResult.Unsupported)
        assertEquals("ordinary CONNECTED must not start sync", 1, factory.socket.sent.size)
    }

    @Test
    fun `cursor expiry is returned without silently starting a full sync`() = runTest {
        val result = async { client.fetch("0xagent", cursor = "expired") }
        runCurrent()
        factory.listener.onOpen(factory.socket, response())
        factory.listener.onMessage(factory.socket, connected())
        factory.listener.onMessage(
            factory.socket,
            """{"type":"ERROR","code":"cursor_expired","message":"start over","retryable":false,"request_id":"request-1"}"""
        )

        val expired = result.await() as SessionSyncFetchResult.CursorExpired
        assertEquals("cursor_expired", expired.error.code)
        assertEquals(2, factory.socket.sent.size)
    }

    @Test
    fun `PING receives PONG while the fetch remains active`() = runTest {
        val result = async { client.fetch("0xagent") }
        runCurrent()
        factory.listener.onOpen(factory.socket, response())
        factory.listener.onMessage(factory.socket, """{"type":"PING"}""")

        assertEquals("PONG", json(1)["type"]!!.jsonPrimitive.content)
        assertFalse(result.isCompleted)

        factory.listener.onMessage(factory.socket, connected())
        factory.listener.onMessage(
            factory.socket,
            """{"type":"SESSION_SYNC_RESULT","request_id":"request-1","sessions":[],"removed_session_ids":[],"cursor":"cursor"}"""
        )
        assertTrue(result.await() is SessionSyncFetchResult.Complete)
    }

    @Test
    fun `a mismatched request id cannot become a successful snapshot`() = runTest {
        val result = async { client.fetch("0xagent") }
        runCurrent()
        factory.listener.onOpen(factory.socket, response())
        factory.listener.onMessage(factory.socket, connected())
        factory.listener.onMessage(
            factory.socket,
            """{"type":"SESSION_SYNC_RESULT","request_id":"someone-else","sessions":[],"removed_session_ids":[],"cursor":"cursor"}"""
        )

        val failed = result.await() as SessionSyncFetchResult.Failed
        assertTrue(failed.message.contains("did not match"))
    }

    @Test
    fun `timeout closes the socket and never returns partial state`() = runTest {
        val result = async { client.fetch("0xagent", timeoutMs = 1_000) }
        runCurrent()
        factory.listener.onOpen(factory.socket, response())
        advanceTimeBy(1_001)
        runCurrent()

        val failed = result.await() as SessionSyncFetchResult.Failed
        assertTrue(failed.retryable)
        assertEquals(1000, factory.socket.closeCode)
    }

    @Test
    fun `malformed response fails without returning the pages already received`() = runTest {
        val result = async { client.fetch("0xagent") }
        runCurrent()
        factory.listener.onOpen(factory.socket, response())
        factory.listener.onMessage(factory.socket, connected())
        factory.listener.onMessage(
            factory.socket,
            """{"type":"SESSION_SYNC_RESULT","request_id":"request-1","sessions":[],"removed_session_ids":[],"next_page_token":"page-2"}"""
        )
        factory.listener.onMessage(factory.socket, "not JSON")

        val failed = result.await() as SessionSyncFetchResult.Failed
        assertTrue(failed.message.contains("malformed"))
        assertEquals(1000, factory.socket.closeCode)
    }

    @Test
    fun `retryable Host error remains retryable`() = runTest {
        val result = async { client.fetch("0xagent") }
        runCurrent()
        factory.listener.onOpen(factory.socket, response())
        factory.listener.onMessage(factory.socket, connected())
        factory.listener.onMessage(
            factory.socket,
            """{"type":"ERROR","code":"temporarily_unavailable","message":"try later","retryable":true,"request_id":"request-1"}"""
        )

        val failed = result.await() as SessionSyncFetchResult.Failed
        assertEquals("try later", failed.message)
        assertTrue(failed.retryable)
    }

    @Test
    fun `socket failure cannot return success`() = runTest {
        val result = async { client.fetch("0xagent") }
        runCurrent()
        factory.listener.onFailure(factory.socket, IOException("network down"), null)
        assertEquals("network down", (result.await() as SessionSyncFetchResult.Failed).message)
    }

    @Test
    fun `premature close cannot return success`() = runTest {
        val result = async { client.fetch("0xagent") }
        runCurrent()
        factory.listener.onClosed(factory.socket, 1001, "gone")
        val closed = result.await() as SessionSyncFetchResult.Failed
        assertTrue(closed.message.contains("closed before completion"))
    }

    @Test
    fun `cancelling a fetch closes its index socket`() = runTest {
        val result = async { client.fetch("0xagent") }
        runCurrent()

        result.cancel()
        runCurrent()

        assertTrue(result.isCancelled)
        assertEquals(1000, factory.socket.closeCode)
    }
}
