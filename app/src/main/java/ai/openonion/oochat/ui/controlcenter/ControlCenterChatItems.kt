package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.CompactStatus
import ai.openonion.oochat.domain.model.EvalStatus
import ai.openonion.oochat.domain.model.IntentStatus
import ai.openonion.oochat.domain.model.ProviderActivity
import ai.openonion.oochat.domain.model.ProviderActivityStatus
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.ui.workroom.WorkRoomText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The chat as connectonion-react's `ChatItem` union (`src/connect/types.ts`), which is what
 * the Control Center app's SDK renders.
 *
 * A [ChatItem.Turn] is two items there, its thinking and then its reply, as the React SDK
 * keeps them. Items with no SDK type (onboarding failure, plan review, the ULW turn limit,
 * diff previews, mode changes) are left out, as is anything that only means something on
 * this device: the user's own images and files are local paths the app cannot open.
 */
object ControlCenterChatItems {

    fun of(items: List<ChatItem>): List<JsonObject> = items.flatMap(::normalize)

    private fun normalize(item: ChatItem): List<JsonObject> = when (item) {
        is ChatItem.User -> listOf(item(item.id, "user") { put("content", item.content) })
        is ChatItem.Agent -> listOf(agent(item.id, item))
        is ChatItem.Thinking -> listOf(thinking(item))
        is ChatItem.Turn -> listOfNotNull(
            item.thinking?.let(::thinking),
            // The reducer often gives the reply its turn's id, which the thinking already has.
            item.agent?.let { agent(if (it.id == item.thinking?.id) "${it.id}$REPLY_SUFFIX" else it.id, it) }
        )
        is ChatItem.ToolCall -> listOf(
            item(item.id, "tool_call") {
                put("name", item.name)
                item.args?.let { args -> put("args", buildJsonObject { args.forEach { (k, v) -> put(k, v) } }) }
                put("status", item.status.wire)
                item.result?.let { put("result", it) }
                item.timingMs?.let { put("timing_ms", it) }
            }
        )
        is ChatItem.AskUser -> listOf(
            item(item.id, "ask_user") {
                put("text", item.text)
                put("options", buildJsonArray { item.options.forEach { add(it.json) } })
                put("multi_select", item.multiSelect)
            }
        )
        is ChatItem.ApprovalNeeded -> listOf(
            item(item.id, "approval_needed") {
                put("tool", item.tool)
                put("arguments", buildJsonObject { item.arguments.forEach { (k, v) -> put(k, v) } })
                item.description?.let { put("description", it) }
                item.batchRemaining?.let { batch ->
                    put(
                        "batch_remaining",
                        buildJsonArray {
                            batch.forEach { add(buildJsonObject { put("tool", it.tool); put("arguments", it.arguments) }) }
                        }
                    )
                }
                put("answered", item.decision != null)
            }
        )
        is ChatItem.OnboardRequired -> listOf(
            item(item.id, "onboard_required") {
                put("methods", buildJsonArray { item.methods.forEach { add(it.json) } })
                item.paymentAmount?.let { put("paymentAmount", it) }
                item.paymentAddress?.let { put("paymentAddress", it) }
            }
        )
        is ChatItem.OnboardSuccess -> listOf(
            item(item.id, "onboard_success") { put("level", item.level); put("message", item.message) }
        )
        is ChatItem.IntentItem -> listOf(
            item(item.id, "intent") {
                put("status", if (item.status == IntentStatus.UNDERSTOOD) "understood" else "analyzing")
                item.ack?.let { put("ack", it) }
                item.isBuild?.let { put("is_build", it) }
            }
        )
        is ChatItem.EvalItem -> listOf(
            item(item.id, "eval") {
                put("status", if (item.status == EvalStatus.DONE) "done" else "evaluating")
                item.passed?.let { put("passed", it) }
                item.summary?.let { put("summary", it) }
                item.expected?.let { put("expected", it) }
                item.evalPath?.let { put("eval_path", it) }
            }
        )
        is ChatItem.CompactItem -> listOf(
            item(item.id, "compact") {
                put(
                    "status",
                    when (item.status) {
                        CompactStatus.COMPACTING -> "compacting"
                        CompactStatus.DONE -> "done"
                        CompactStatus.ERROR -> "error"
                    }
                )
                item.contextBefore?.let { put("context_before", it) }
                item.contextAfter?.let { put("context_after", it) }
                item.contextPercent?.let { put("context_percent", it) }
                item.message?.let { put("message", it) }
                item.error?.let { put("error", it) }
            }
        )
        is ChatItem.ToolBlockedItem -> listOf(
            item(item.id, "tool_blocked") {
                put("tool", item.tool)
                put("reason", item.reason)
                put("message", item.message)
                item.command?.let { put("command", it) }
            }
        )
        is ChatItem.FilesReceivedItem -> listOf(
            item(item.id, "files_received") {
                put(
                    "files",
                    buildJsonArray { item.files.forEach { add(buildJsonObject { put("name", it.name); put("path", it.path) }) } }
                )
            }
        )
        // The Host's own nested shape for a run (network/host/session/ui.py), which the SDK renders as a coding-agent card.
        is ChatItem.ProviderInvocation -> listOf(
            item(item.id, "provider_invocation") {
                put("invocationId", item.id)
                item.parentToolCallId?.let { put("parentToolCallId", it) }
                item.workroomId?.let { put("workroomId", it) }
                put("provider", item.provider)
                item.providerDisplayName?.let { put("providerDisplayName", it) }
                item.taskTitle?.let { put("taskTitle", it) }
                put("status", item.status.wire)
                item.currentSummary?.let { put("currentSummary", it) }
                (item.resultSummary ?: WorkRoomText.ENDED_UNSEEN.takeIf { item.status == ProviderInvocationStatus.ENDED })
                    ?.let { put("resultSummary", it) }
                item.errorSummary?.let { put("errorSummary", it) }
                item.elapsedMs?.let { put("elapsedMs", it) }
                item.stateRevision?.let { put("stateRevision", it) }
                put(
                    "activities",
                    buildJsonArray {
                        item.activities.forEach { step ->
                            add(if (step.legacy) legacyStep(step) else nativeStep(step))
                        }
                    }
                )
                if (item.messages.isNotEmpty()) {
                    put(
                        "messages",
                        buildJsonArray {
                            item.messages.forEach { message ->
                                add(
                                    buildJsonObject {
                                        put("id", message.id)
                                        put("role", message.role.name.lowercase())
                                        put("text", message.text)
                                    }
                                )
                            }
                        }
                    )
                }
                // Only a picture of the run as it stands, as ui.py nests it.
                item.artifact?.takeIf { it.stateRevision == item.stateRevision }?.let { artifact ->
                    put(
                        "artifact",
                        buildJsonObject {
                            put("id", artifact.id)
                            put("kind", "screenshot")
                            put("stateRevision", artifact.stateRevision)
                            put("thumbnailDataUrl", artifact.dataUrl)
                            put("alt", artifact.alt)
                        }
                    )
                }
            }
        )
        is ChatItem.OnboardingFailed, is ChatItem.PlanReviewItem, is ChatItem.UlwTurnsReachedItem,
        is ChatItem.DiffPreviewItem, is ChatItem.ModeChangedItem, is ChatItem.ProviderActivityUpdate,
        is ChatItem.ProviderMessageUpdate, is ChatItem.ProviderArtifactUpdate -> emptyList()
    }

    /** A `provider_activity` step as ui.py `_nested_provider_activity` nests it. */
    private fun nativeStep(step: ProviderActivity) = buildJsonObject {
        put("id", step.id)
        put("sequence", step.sequence)
        put("kind", step.kind)
        put("status", step.status.name.lowercase())
        put("title", step.title)
        put("summary", step.summary)
        if (step.files.isNotEmpty()) put("files", buildJsonArray { step.files.forEach { add(it.json) } })
        put("legacy", false)
    }

    /**
     * A compatibility step as ui.py nests it: the tool_call item itself, marked
     * legacy, with the SDK's tool statuses. Its name is the generic title, as on
     * screen: the Host's own is Codex's raw command.
     */
    private fun legacyStep(step: ProviderActivity) = buildJsonObject {
        put("id", step.id)
        put("type", "tool_call")
        put("name", step.title)
        put(
            "status",
            when (step.status) {
                ProviderActivityStatus.RUNNING -> "running"
                ProviderActivityStatus.COMPLETED -> "done"
                ProviderActivityStatus.FAILED -> "error"
            }
        )
        put("legacy", true)
    }

    private fun agent(id: String, item: ChatItem.Agent) = item(id, "agent") {
        put("content", item.content)
        item.images?.takeIf { it.isNotEmpty() }?.let { images -> put("images", buildJsonArray { images.forEach { add(it.json) } }) }
    }

    private fun thinking(item: ChatItem.Thinking) = item(item.id, "thinking") {
        put("status", item.status.wire)
        item.model?.let { put("model", it) }
        item.durationMs?.let { put("duration_ms", it) }
        item.content?.let { put("content", it) }
        item.kind?.let { put("kind", it) }
        item.contextPercent?.let { put("context_percent", it) }
        if (item.tokensTotal != null || item.costUsd != null) {
            put(
                "usage",
                buildJsonObject {
                    item.tokensTotal?.let { put("total_tokens", it) }
                    item.costUsd?.let { put("cost", it) }
                }
            )
        }
    }

    private fun item(id: String, type: String, fields: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", id)
        put("type", type)
        fields()
    }

    private val String.json get() = JsonPrimitive(this)

    private val ThinkingStatus.wire get() = name.lowercase()
    private val ToolStatus.wire get() = name.lowercase()
    // ENDED is this app's own: the Host never sends it, so the SDK's card would not know it. A run
    // that ended unseen is over either way, and "cancelled" is the Host's terminal status that claims
    // neither a result nor an error; the resultSummary written with it says what really happened.
    private val ProviderInvocationStatus.wire
        get() = if (this == ProviderInvocationStatus.ENDED) "cancelled" else name.lowercase()

    /** Added to a [ChatItem.Turn]'s reply when it shares the turn's thinking id. */
    const val REPLY_SUFFIX = ":reply"
}
