package ai.openonion.oochat.network

import ai.openonion.oochat.domain.model.ApprovalBoundary
import ai.openonion.oochat.domain.model.ProviderApprovalPresentation
import ai.openonion.oochat.domain.model.WorkRoomApproval
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Reads an `approval_needed`'s Work Room fields as `@connectonion/react` does
 * (`connect/remote-agent.ts`). Fails closed: any unexpected field drops the
 * Host's copy, which leaves Deny as the only answer.
 */
internal object ProviderApprovals {

    private val PROVIDERS = setOf("codex", "claude_code")

    private val ACTIONS = setOf(
        "Run a workspace command",
        "Compile the requested C11 program",
        "Compile the requested C program",
        "Compile and run the requested tests",
        "Run the requested tests",
        "Run the requested program",
        "Inspect the workspace",
        "Make workspace file changes",
        "Expand provider permissions",
        "Perform a provider action"
    )
    private val REASONS = setOf(
        "Codex requested approval to continue",
        "Claude Code requested approval to continue",
        "Compile the requested workspace files before continuing",
        "Verify the requested workspace changes before continuing",
        "Verify the requested program before continuing",
        "Check the requested workspace result before continuing",
        "Apply the requested workspace file changes",
        "Review the requested permission expansion"
    )
    private val SCOPES = mapOf(
        "workroom" to (ApprovalBoundary.WORKROOM to "This Work Room only"),
        "elevated" to (ApprovalBoundary.ELEVATED to "Outside this Work Room"),
        "unknown" to (ApprovalBoundary.UNKNOWN to "Boundary could not be verified")
    )
    private const val MAX_FILES = 8
    private const val MAX_FILE_NAME = 128

    fun fromWire(
        provider: JsonElement?,
        invocationId: String?,
        parentToolCallId: String?,
        activityId: JsonElement?,
        presentation: JsonElement?
    ): WorkRoomApproval? {
        val name = provider.string()?.takeIf { it in PROVIDERS } ?: return null
        val invocation = invocationId?.takeIf { it.isNotEmpty() } ?: return null
        val parent = parentToolCallId?.takeIf { it.isNotEmpty() } ?: return null
        return WorkRoomApproval(
            provider = name,
            invocationId = invocation,
            parentToolCallId = parent,
            activityId = activityId.string()?.takeIf { it.isNotEmpty() },
            presentation = (presentation as? JsonObject)?.toPresentation()
        )
    }

    private fun JsonObject.toPresentation(): ProviderApprovalPresentation? {
        val (boundary, expectedScope) = SCOPES[this["scopeClassification"].string()] ?: return null
        val action = this["action"].string()?.takeIf { it in ACTIONS } ?: return null
        val reason = this["reason"].string()?.takeIf { it in REASONS } ?: return null
        val scope = this["scope"].string()?.takeIf { it == expectedScope } ?: return null
        val allowOnce = this["allowOnce"].boolean() ?: return null
        val allowSession = this["allowSession"].boolean() ?: return null
        val inWorkRoom = boundary == ApprovalBoundary.WORKROOM
        return ProviderApprovalPresentation(
            action = action,
            scope = scope,
            reason = reason,
            boundary = boundary,
            allowOnce = inWorkRoom && allowOnce,
            allowSession = inWorkRoom && allowSession,
            files = files(this["files"])
        )
    }

    private fun files(value: JsonElement?): List<String> =
        (value as? JsonArray).orEmpty()
            .mapNotNull { it.string() }
            .map(::fileName)
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_FILES)

    private fun fileName(path: String): String =
        path.replace('\\', '/').trimEnd('/').substringAfterLast('/').take(MAX_FILE_NAME)

    private fun JsonElement?.string(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.boolean(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
