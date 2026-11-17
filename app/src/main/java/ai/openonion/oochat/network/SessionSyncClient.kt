package ai.openonion.oochat.network

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.ConnectMessage
import ai.openonion.oochat.data.protocol.ConnectPayload
import ai.openonion.oochat.data.protocol.OipProtocol
import ai.openonion.oochat.data.protocol.PongMessage
import ai.openonion.oochat.data.protocol.RemoteSessionSummary
import ai.openonion.oochat.data.protocol.SessionSyncError
import ai.openonion.oochat.data.protocol.SessionSyncProtocol
import ai.openonion.oochat.data.protocol.SessionSyncReply
import ai.openonion.oochat.data.protocol.SessionSyncRequest
import ai.openonion.oochat.data.protocol.SignedCommand
import ai.openonion.oochat.di.sharedHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A complete session index fetched from Session Sync socket. */
data class SessionSyncSnapshot(
    val sessions: List<RemoteSessionSummary>,
    val removedSessionIds: List<String>,
    val cursor: String,
    val isFullSnapshot: Boolean = false
)

/** Outcome of Session Sync fetch. */
sealed interface SessionSyncFetchResult {
    data class Complete(val snapshot: SessionSyncSnapshot) : SessionSyncFetchResult
    data class CursorExpired(val error: SessionSyncError) : SessionSyncFetchResult
    data class Unsupported(val error: SessionSyncError? = null) : SessionSyncFetchResult
    data class Failed(
        val message: String,
        val retryable: Boolean = false
    ) : SessionSyncFetchResult
}

/**
 * Fetches the Host's retained-session index over an index-only WebSocket.
 */
class SessionSyncClient(
    private val keyManager: KeyManager,
    private val relayUrl: String = AgentConnection.DEFAULT_RELAY,
    private val webSocketFactory: WebSocketFactory = OkHttpWebSocketFactory(webSocketClient),
    private val signer: (KeyManager.AddressData, String) -> String = keyManager::sign,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val nonceFactory: () -> String = SignedCommand::newNonce
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Return only a complete index. A timeout, premature close or malformed
     * page is a failure rather than a partial snapshot that Room could mistake
     * for authoritative state.
     */
    suspend fun fetch(
        agentAddress: String,
        directUrl: String? = null,
        cursor: String? = null,
        includeArchived: Boolean = false,
        timeoutMs: Long = FETCH_TIMEOUT_MS,
        // A self-hosted relay the agent is reached through (W16AAPPLE-56); null for this client's own.
        relayUrl: String? = null
    ): SessionSyncFetchResult {
        val keys = keyManager.loadOrGenerate()
        val completion = CompletableDeferred<SessionSyncFetchResult>()
        val hostClock = HostClock()
        val sessions = mutableListOf<RemoteSessionSummary>()
        val removedSessionIds = mutableListOf<String>()
        val stateLock = Any()
        var negotiated = false
        var outstandingRequestId: String? = null
        var socket: WebSocket? = null

        fun complete(result: SessionSyncFetchResult) {
            completion.complete(result)
        }

        fun signedRequest(request: SessionSyncRequest): String {
            val frame = SignedCommand.envelope(
                command = json.encodeToJsonElement(request).jsonObject,
                to = agentAddress,
                timestamp = hostClock.nowSeconds(),
                nonce = nonceFactory(),
                from = keys.address,
                sign = { payload -> signer(keys, keyManager.canonicalJson(payload)) }
            )
            return json.encodeToString(JsonObject.serializer(), frame)
        }

        fun sendPage(webSocket: WebSocket, pageToken: String?) {
            val requestId = requestIdFactory()
            synchronized(stateLock) { outstandingRequestId = requestId }
            val request = SessionSyncRequest(
                requestId = requestId,
                cursor = if (pageToken == null) cursor else null,
                pageToken = pageToken,
                includeArchived = includeArchived
            )
            if (!webSocket.send(signedRequest(request))) {
                complete(SessionSyncFetchResult.Failed("Could not send the Session Sync request"))
            }
        }

        fun handleReply(webSocket: WebSocket, text: String) {
            val fields = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                complete(SessionSyncFetchResult.Failed("The Host sent malformed Session Sync JSON"))
                return
            }
            val type = runCatching { fields["type"]?.jsonPrimitive?.contentOrNull }.getOrNull()

            if (type == "PING") {
                webSocket.send(json.encodeToString(PongMessage()))
                return
            }

            if (!negotiated) {
                if (type == "CONNECTED") {
                    val status = runCatching { fields["status"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    val descriptor = fields["protocol"]
                    if (status != "index" ||
                        !OipProtocol.supports(descriptor) ||
                        !OipProtocol.supportsSessionSync(descriptor)
                    ) {
                        complete(SessionSyncFetchResult.Unsupported())
                        return
                    }
                    negotiated = true
                    sendPage(webSocket, pageToken = null)
                    return
                }
                when (val reply = SessionSyncProtocol.decodeReply(text)) {
                    is SessionSyncReply.Unsupported -> complete(SessionSyncFetchResult.Unsupported(reply.error))
                    is SessionSyncReply.Failure -> complete(
                        SessionSyncFetchResult.Failed(reply.error.message, reply.error.retryable)
                    )
                    else -> complete(SessionSyncFetchResult.Failed("Expected an index CONNECTED response"))
                }
                return
            }

            fun matchesOutstandingRequest(error: SessionSyncError): Boolean {
                val expected = synchronized(stateLock) { outstandingRequestId }
                if (error.requestId == expected) return true
                complete(SessionSyncFetchResult.Failed("Session Sync error did not match its request"))
                return false
            }

            when (val reply = SessionSyncProtocol.decodeReply(text)) {
                is SessionSyncReply.Page -> {
                    val expected = synchronized(stateLock) { outstandingRequestId }
                    if (reply.value.requestId != expected) {
                        complete(SessionSyncFetchResult.Failed("Session Sync response did not match its request"))
                        return
                    }
                    sessions += reply.value.sessions
                    removedSessionIds += reply.value.removedSessionIds
                    val nextPageToken = reply.value.nextPageToken
                    if (nextPageToken != null) {
                        sendPage(webSocket, nextPageToken)
                    } else {
                        complete(
                            SessionSyncFetchResult.Complete(
                                SessionSyncSnapshot(
                                    sessions = sessions.toList(),
                                    removedSessionIds = removedSessionIds.toList(),
                                    cursor = requireNotNull(reply.value.cursor),
                                    isFullSnapshot = cursor == null
                                )
                            )
                        )
                    }
                }
                is SessionSyncReply.CursorExpired -> if (matchesOutstandingRequest(reply.error)) {
                    complete(SessionSyncFetchResult.CursorExpired(reply.error))
                }
                is SessionSyncReply.Unsupported -> if (matchesOutstandingRequest(reply.error)) {
                    complete(SessionSyncFetchResult.Unsupported(reply.error))
                }
                is SessionSyncReply.Failure -> if (matchesOutstandingRequest(reply.error)) {
                    complete(SessionSyncFetchResult.Failed(reply.error.message, reply.error.retryable))
                }
                SessionSyncReply.Malformed -> complete(
                    SessionSyncFetchResult.Failed("The Host sent a malformed Session Sync response")
                )
                is SessionSyncReply.Unknown -> complete(
                    SessionSyncFetchResult.Failed("Unexpected Session Sync response: ${reply.type ?: "missing type"}")
                )
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                hostClock.observe(response)
                val timestamp = hostClock.nowSeconds()
                // The signed recipient is required in both transports. Only
                // the unsigned top-level routing field is relay-specific.
                val routeRecipient = if (directUrl == null) agentAddress else null
                val payload = ConnectPayload(
                    timestamp = timestamp,
                    to = agentAddress,
                    nonce = nonceFactory(),
                    extensions = OipProtocol.SESSION_SYNC_OFFER,
                    sessionSyncOnly = 1
                )
                val signature = signer(
                    keys,
                    keyManager.canonicalJson(json.encodeToJsonElement(payload).jsonObject)
                )
                val connect = ConnectMessage(
                    payload = payload,
                    from = keys.address,
                    signature = signature,
                    timestamp = timestamp,
                    to = routeRecipient
                )
                if (!webSocket.send(json.encodeToString(connect))) {
                    complete(SessionSyncFetchResult.Failed("Could not send the Session Sync CONNECT"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleReply(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                complete(SessionSyncFetchResult.Failed(t.message ?: "Session Sync connection failed"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                complete(SessionSyncFetchResult.Failed("Session Sync connection closed before completion"))
            }
        }

        return try {
            val request = Request.Builder().url(webSocketUrl(directUrl, relayUrl ?: this.relayUrl)).build()
            socket = webSocketFactory.create(request, listener)
            withTimeout(timeoutMs) { completion.await() }
        } catch (_: TimeoutCancellationException) {
            SessionSyncFetchResult.Failed("Session Sync timed out", retryable = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SessionSyncFetchResult.Failed(failure.message ?: "Could not start Session Sync")
        } finally {
            socket?.close(1000, "Session Sync complete")
        }
    }

    private fun webSocketUrl(directUrl: String?, relayUrl: String): String {
        if (directUrl != null) {
            val baseUrl = directUrl.replace(Regex("^https?://"), "")
            val protocol = if (directUrl.startsWith("https")) "wss" else "ws"
            return "$protocol://$baseUrl/ws"
        }
        val relay = when {
            relayUrl.startsWith("https://") -> "wss://${relayUrl.removePrefix("https://")}"
            relayUrl.startsWith("http://") -> "ws://${relayUrl.removePrefix("http://")}"
            else -> relayUrl
        }
        return "$relay/ws/input"
    }

    companion object {
        const val FETCH_TIMEOUT_MS = 30_000L

        private val webSocketClient: OkHttpClient by lazy {
            sharedHttpClient.newBuilder()
                .connectTimeout(AgentConnection.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(AgentConnection.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(AgentConnection.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pingInterval(AgentConnection.PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
