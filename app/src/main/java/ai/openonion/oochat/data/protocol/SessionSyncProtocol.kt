package ai.openonion.oochat.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire contract for OIP's experimental `session-sync/0.1` retained-session
 * index.
 */

/** Represents the request sent after the index connection has been established. */
@Serializable
data class SessionSyncRequest(
    val type: String = TYPE,
    @SerialName("request_id") val requestId: String,
    val cursor: String? = null,
    @SerialName("page_token") val pageToken: String? = null,
    val limit: Int = DEFAULT_LIMIT,
    @SerialName("include_archived") val includeArchived: Boolean = false
) {
    init {
        require(requestId.isNotBlank() && requestId.length <= MAX_REQUEST_ID_LENGTH) {
            "request_id must contain 1-$MAX_REQUEST_ID_LENGTH characters"
        }
        require(cursor == null || pageToken == null) {
            "cursor and page_token are mutually exclusive"
        }
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
    }

    companion object {
        const val TYPE = "SESSION_SYNC"
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
        const val MAX_REQUEST_ID_LENGTH = 128
    }
}

/** Host-retained conversation summary returned by [SessionSyncRequest]. */
@Serializable
data class RemoteSessionSummary(
    @SerialName("session_id") val sessionId: String,
    val revision: Long,
    val title: String,
    /** `running`, `waiting`, or `idle`; kept open for future Host values. */
    val activity: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("last_sequence") val lastSequence: Long,
    @SerialName("last_outcome") val lastOutcome: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val preview: String? = null
)

/**
 * Represents a page returned by the Host. A non-final page has [nextPageToken];
 * the final page has [cursor], which becomes the next incremental starting point.
 */
@Serializable
data class SessionSyncResult(
    val type: String = TYPE,
    @SerialName("request_id") val requestId: String,
    val sessions: List<RemoteSessionSummary>,
    @SerialName("removed_session_ids") val removedSessionIds: List<String>,
    val cursor: String? = null,
    @SerialName("next_page_token") val nextPageToken: String? = null
) {
    val isFinalPage: Boolean get() = cursor != null

    companion object {
        const val TYPE = "SESSION_SYNC_RESULT"
    }
}

/** Stable Session Sync ERROR frame. */
@Serializable
data class SessionSyncError(
    val type: String = TYPE,
    @Serializable(with = LenientStringSerializer::class)
    val code: String = "",
    val message: String = "",
    val retryable: Boolean = false,
    @SerialName("request_id") val requestId: String? = null,
    val data: JsonObject? = null
) {
    companion object {
        const val TYPE = "ERROR"
    }
}

/** Stable error codes clients need to branch on rather than message text. */
object SessionSyncErrorCode {
    const val CURSOR_EXPIRED = "cursor_expired"
    const val UNSUPPORTED_EXTENSION = "unsupported_extension"
}

/** Result of decoding frames on a Session Sync index socket. */
sealed interface SessionSyncReply {
    data class Page(val value: SessionSyncResult) : SessionSyncReply
    data class CursorExpired(val error: SessionSyncError) : SessionSyncReply
    data class Unsupported(val error: SessionSyncError) : SessionSyncReply
    data class Failure(val error: SessionSyncError) : SessionSyncReply
    data class Unknown(val type: String?) : SessionSyncReply
    data object Malformed : SessionSyncReply
}

/** Encoding and decoding session sync. */
object SessionSyncProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodeRequest(request: SessionSyncRequest): String =
        json.encodeToString(SessionSyncRequest.serializer(), request)

    fun decodeReply(raw: String): SessionSyncReply = try {
        val element = json.parseToJsonElement(raw)
        val fields = element.jsonObject
        when (val type = fields["type"]?.jsonPrimitive?.content) {
            SessionSyncResult.TYPE -> decodeResult(fields)
            SessionSyncError.TYPE -> decodeError(fields)
            else -> SessionSyncReply.Unknown(type)
        }
    } catch (_: IllegalArgumentException) {
        SessionSyncReply.Malformed
    } catch (_: SerializationException) {
        SessionSyncReply.Malformed
    }

    private fun decodeResult(fields: JsonObject): SessionSyncReply {
        val value = json.decodeFromJsonElement(SessionSyncResult.serializer(), fields)
        val hasExactlyOneContinuation = (value.cursor == null) != (value.nextPageToken == null)
        val valid = value.requestId.isNotBlank() &&
            hasExactlyOneContinuation &&
            value.sessions.all { summary ->
                summary.sessionId.isNotBlank() && summary.revision >= 1 && summary.lastSequence >= 0
            } &&
            value.removedSessionIds.all(String::isNotBlank)
        return if (valid) SessionSyncReply.Page(value) else SessionSyncReply.Malformed
    }

    private fun decodeError(fields: JsonObject): SessionSyncReply {
        val error = json.decodeFromJsonElement(SessionSyncError.serializer(), fields)
        return when (error.code) {
            SessionSyncErrorCode.CURSOR_EXPIRED -> SessionSyncReply.CursorExpired(error)
            SessionSyncErrorCode.UNSUPPORTED_EXTENSION -> SessionSyncReply.Unsupported(error)
            else -> SessionSyncReply.Failure(error)
        }
    }
}
