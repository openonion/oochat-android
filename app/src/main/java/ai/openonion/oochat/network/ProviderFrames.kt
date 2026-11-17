package ai.openonion.oochat.network

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderActivity
import ai.openonion.oochat.domain.model.ProviderActivityStatus
import ai.openonion.oochat.domain.model.ProviderInputAck
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Claude Code and Codex Work Room frames (W16AAPPLE-33): `provider_invocation`
 * and `provider_activity`, as connectonion's coding-agent plugins send them
 * (`plugins/coding_agents.py`, `useful_tools/codex.py`, and
 * `core/provider_events.py` for the activity copy), and `PROVIDER_INPUT_ACK`,
 * the answer to a message typed into a Work Room (W16AAPPLE-35).
 *
 * Decoded on their own rather than as a [ai.openonion.oochat.data.protocol.ServerEvent]:
 * an activity's `files` is a list of names, where every other frame's `files`
 * is a list of objects, so the shared decode would drop the frame.
 */
internal object ProviderFrames {

    private const val INVOCATION = "provider_invocation"
    private const val ACTIVITY = "provider_activity"
    private const val INPUT_ACK = "PROVIDER_INPUT_ACK"

    /** The Host's own title for a provider tool step (core/provider_events.py `_SAFE_ACTIVITY_TITLES`). */
    const val LEGACY_STEP_TITLE = "Use a provider tool"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    private data class Wire(
        val type: String = "",
        val id: String? = null,
        val invocationId: String? = null,
        val parentToolCallId: String? = null,
        val workroomId: String? = null,
        val provider: String? = null,
        val providerDisplayName: String? = null,
        val taskTitle: String? = null,
        val taskSummary: String? = null,
        val currentSummary: String? = null,
        val resultSummary: String? = null,
        val errorSummary: String? = null,
        val status: String? = null,
        val elapsedMs: Double? = null,
        val stateRevision: Long? = null,
        val activityId: String? = null,
        val sequence: Int? = null,
        val kind: String? = null,
        val title: String? = null,
        val summary: String? = null,
        val files: List<String>? = null,
        val requestId: String? = null,
        val accepted: Boolean? = null,
        val reason: String? = null
    )

    /**
     * The result for a Work Room frame, or null when [text] is some other frame.
     * A malformed one is ignored, but its trace id still moves the replay
     * cursor, as every agent-log frame's does.
     */
    fun parse(text: String): ParseResult? {
        // Cheap screen first: every other frame type goes straight to the shared decode.
        if (!text.contains("\"$INVOCATION\"") && !text.contains("\"$ACTIVITY\"") && !text.contains("\"$INPUT_ACK\"")) {
            return null
        }
        val wire = try {
            json.decodeFromString<Wire>(text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (wire.type == INPUT_ACK) return ParseResult(event = wire.toInputAck())
        val item = when (wire.type) {
            INVOCATION -> wire.toInvocation()
            ACTIVITY -> wire.toActivityUpdate()
            else -> return null
        }
        val msgId = wire.id?.takeIf { it.isNotBlank() }
        if (item == null) {
            FileLogger.w(LogTags.PROTOCOL, "Malformed ${wire.type} frame, ignoring")
            return ParseResult(event = ConnectionEvent.Unknown, msgId = msgId)
        }
        return ParseResult(event = ConnectionEvent.ChatItemUpdated(item), msgId = msgId)
    }

    /**
     * A compatibility `tool_call`/`tool_result` the Host tagged with the run it
     * belongs to: a step of that run under the Host's generic title. Neither
     * its arguments nor its `name` are shown: for Codex the name is the raw
     * command or file path (`useful_tools/codex.py`), which a Work Room never shows.
     */
    fun legacyStep(
        invocationId: String,
        parentToolCallId: String?,
        toolId: String,
        status: String?
    ): ChatItem.ProviderActivityUpdate = ChatItem.ProviderActivityUpdate(
        invocationId = invocationId,
        parentToolCallId = parentToolCallId,
        activity = ProviderActivity(
            id = toolId,
            // The Host has no sequence for these and sorts them last.
            sequence = Int.MAX_VALUE,
            kind = "tool",
            status = ProviderActivityStatus.fromWire(status ?: "running"),
            title = LEGACY_STEP_TITLE,
            summary = "",
            legacy = true
        )
    )

    /**
     * Only `accepted: true` is an acceptance; anything else is a refusal, so
     * a draft is never cleared on a frame that does not say outright that the
     * provider took the text. One that names no request answers nothing.
     */
    private fun Wire.toInputAck(): ConnectionEvent {
        val request = requestId?.takeIf { it.isNotBlank() } ?: run {
            FileLogger.w(LogTags.PROTOCOL, "PROVIDER_INPUT_ACK without a requestId, ignoring")
            return ConnectionEvent.Unknown
        }
        return ConnectionEvent.ProviderInputAcked(
            ProviderInputAck(
                requestId = request,
                invocationId = invocationId?.takeIf { it.isNotBlank() },
                accepted = accepted == true,
                stateRevision = stateRevision?.takeIf { it > 0 },
                reason = reason?.takeIf { it.isNotBlank() }
            )
        )
    }

    private fun Wire.toInvocation(): ChatItem.ProviderInvocation? {
        val invocation = invocationId?.takeIf { it.isNotBlank() } ?: return null
        return ChatItem.ProviderInvocation(
            id = invocation,
            parentToolCallId = parentToolCallId,
            workroomId = workroomId,
            provider = provider.orEmpty(),
            providerDisplayName = providerDisplayName,
            // taskSummary is the field an older Host filled; both are the same safe title.
            taskTitle = taskTitle ?: taskSummary,
            status = ProviderInvocationStatus.fromWire(status),
            currentSummary = currentSummary,
            resultSummary = resultSummary,
            errorSummary = errorSummary,
            elapsedMs = elapsedMs?.toLong(),
            stateRevision = stateRevision?.takeIf { it > 0 }
        )
    }

    private fun Wire.toActivityUpdate(): ChatItem.ProviderActivityUpdate? {
        val invocation = invocationId?.takeIf { it.isNotBlank() } ?: return null
        val activity = activityId?.takeIf { it.isNotBlank() } ?: return null
        val order = sequence?.takeIf { it >= 1 } ?: return null
        val heading = title?.takeIf { it.isNotBlank() } ?: return null
        return ChatItem.ProviderActivityUpdate(
            invocationId = invocation,
            parentToolCallId = parentToolCallId,
            provider = provider.orEmpty(),
            activity = ProviderActivity(
                id = activity,
                sequence = order,
                kind = kind.orEmpty(),
                status = ProviderActivityStatus.fromWire(status),
                title = heading,
                summary = summary.orEmpty(),
                files = files.orEmpty().filter { it.isNotBlank() }
            )
        )
    }
}
