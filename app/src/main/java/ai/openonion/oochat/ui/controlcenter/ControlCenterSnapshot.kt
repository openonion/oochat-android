package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.SessionActivity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The chat as the Control Center app sees it: `ControlSnapshot` in connectonion-react's
 * `src/control-center.ts`, which the app's SDK validates before using.
 *
 * [chatItems] are already in the SDK's `ChatItem` shape; see [ControlCenterChatItems].
 */
data class ControlCenterSnapshot(
    val sessionId: String?,
    val agentAddress: String,
    val status: ControlCenterAgentStatus,
    val connectionState: ControlCenterConnection,
    val skills: List<ControlCenterSkill>,
    val chatItems: List<JsonObject> = emptyList(),
    val truncated: Boolean = false
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("sessionId", sessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("agentAddress", agentAddress)
        put("chatItems", JsonArray(chatItems))
        put("status", status.wire)
        put("connectionState", connectionState.wire)
        put(
            "skills",
            buildJsonArray {
                skills.forEach { skill ->
                    add(
                        buildJsonObject {
                            put("name", skill.name)
                            skill.description?.let { put("description", it) }
                        }
                    )
                }
            }
        )
        put("truncated", truncated)
    }

    companion object {
        const val MAX_SKILLS = 2000
        private const val MAX_SKILL_NAME = 64
        private const val MAX_SKILL_DESCRIPTION = 256
        const val MAX_SKILL_BYTES = 512 * 1024
        private const val SKILL_OVERHEAD_BYTES = 32

        /**
         * Skills come from the agent, so they are cut to `boundControlSnapshot`'s limits, and to
         * [MAX_SKILL_BYTES] of JSON so they cannot push the snapshot past the bridge's size limit.
         * As upstream, dropping skills does not set [truncated], which is about chat items. Unlike
         * upstream, an over-long name is dropped, not cut: the cut name would be a different skill.
         */
        fun of(
            sessionId: String?,
            agentAddress: String,
            activity: SessionActivity?,
            connection: ConnectionState,
            skills: List<AgentSkill>,
            chat: ControlCenterChat = ControlCenterChat()
        ) = ControlCenterSnapshot(
            sessionId = sessionId,
            agentAddress = agentAddress,
            status = ControlCenterAgentStatus.of(activity),
            connectionState = ControlCenterConnection.of(connection),
            skills = boundSkills(skills),
            chatItems = chat.items,
            truncated = chat.truncated
        )

        private fun boundSkills(skills: List<AgentSkill>): List<ControlCenterSkill> {
            var bytes = 0
            return skills.filter { it.name.length <= MAX_SKILL_NAME }.take(MAX_SKILLS).asSequence()
                .map { skill ->
                    ControlCenterSkill(
                        name = skill.name,
                        description = skill.description?.takeIf { it.isNotBlank() }?.take(MAX_SKILL_DESCRIPTION)
                    )
                }
                .takeWhile { skill ->
                    bytes += jsonBytes(skill.name) + (skill.description?.let(::jsonBytes) ?: 0) + SKILL_OVERHEAD_BYTES
                    bytes <= MAX_SKILL_BYTES
                }
                .toList()
        }

        private fun jsonBytes(text: String) = JsonPrimitive(text).toString().toByteArray(Charsets.UTF_8).size
    }
}

/**
 * The chat as the snapshot carries it: [items] bounded as `boundControlSnapshot` does, and
 * whether any were left out. Built apart from the snapshot because it costs time in proportion
 * to the conversation, so the chat builds it off the main thread.
 */
data class ControlCenterChat(val items: List<JsonObject> = emptyList(), val truncated: Boolean = false) {
    companion object {
        const val MAX_ITEMS = 2000
        const val MAX_BYTES = 1024 * 1024

        fun of(items: List<ChatItem>): ControlCenterChat = bounded(ControlCenterChatItems.of(items))

        /**
         * Of the latest [MAX_ITEMS], the newest that fit in [MAX_BYTES] of JSON. An item too big
         * for what is left is skipped, not a stop, so older, smaller items can still be kept.
         */
        fun bounded(items: List<JsonObject>): ControlCenterChat {
            var bytes = 0
            val kept = ArrayList<JsonObject>()
            for (item in items.takeLast(MAX_ITEMS).asReversed()) {
                val size = ControlCenterHost.utf8Length(item.toString())
                if (bytes + size > MAX_BYTES) continue
                bytes += size
                kept += item
            }
            return ControlCenterChat(kept.asReversed(), truncated = kept.size != items.size)
        }
    }
}

data class ControlCenterSkill(val name: String, val description: String? = null)

/** The React SDK's `AgentStatus`. */
enum class ControlCenterAgentStatus(val wire: String) {
    IDLE("idle"),
    WORKING("working"),
    WAITING("waiting");

    companion object {
        fun of(activity: SessionActivity?): ControlCenterAgentStatus = when (activity) {
            SessionActivity.RUNNING -> WORKING
            SessionActivity.AWAITING_USER -> WAITING
            SessionActivity.IDLE, SessionActivity.NOT_FOUND, null -> IDLE
        }
    }
}

/** The React SDK's `ConnectionState`, which has no state for a first connect in progress. */
enum class ControlCenterConnection(val wire: String) {
    DISCONNECTED("disconnected"),
    CONNECTED("connected"),
    RECONNECTING("reconnecting");

    companion object {
        fun of(state: ConnectionState): ControlCenterConnection = when (state) {
            is ConnectionState.Connected -> CONNECTED
            is ConnectionState.Reconnecting -> RECONNECTING
            is ConnectionState.Idle, is ConnectionState.Connecting,
            is ConnectionState.Disconnected, is ConnectionState.Error -> DISCONNECTED
        }
    }
}
