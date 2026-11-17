package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ApprovalBoundary
import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.WorkRoomApproval

/** What the Work Room says about a Claude Code or Codex run (W16AAPPLE-33), in one place for the card and the screen. */
internal object WorkRoomText {

    /** The Host's display name, or a readable one for the providers it ships. */
    fun providerName(run: ChatItem.ProviderInvocation): String =
        run.providerDisplayName?.takeIf { it.isNotBlank() } ?: providerName(run.provider)

    fun providerName(provider: String): String = when (provider) {
        "codex" -> "Codex"
        "claude_code" -> "Claude Code"
        "" -> "Coding agent"
        else -> provider
    }

    fun status(status: ProviderInvocationStatus): String = when (status) {
        ProviderInvocationStatus.RUNNING -> "Working"
        ProviderInvocationStatus.AWAITING_APPROVAL -> "Waiting for your decision"
        ProviderInvocationStatus.COMPLETED -> "Completed"
        ProviderInvocationStatus.FAILED -> "Failed"
        ProviderInvocationStatus.CANCELLED -> "Stopped"
        ProviderInvocationStatus.ENDED -> "Ended"
    }

    /** Said for a run that ended while this device was not listening, so no result reached it. */
    const val ENDED_UNSEEN = "This run ended while this device was not connected, so its result did not arrive here."

    /** The run's task, as the Host titled it; never the raw prompt. */
    fun title(run: ChatItem.ProviderInvocation): String =
        run.taskTitle?.takeIf { it.isNotBlank() } ?: "${providerName(run)} task"

    /**
     * One line on where the run stands. While it works: its newest step, since
     * the Host's own summary only ever says it is working. Once it has ended:
     * how, never the summary it had while working, which a Host-made Stop
     * frame carries over.
     */
    fun summary(run: ChatItem.ProviderInvocation): String? = when {
        run.status == ProviderInvocationStatus.ENDED -> ENDED_UNSEEN
        run.status.isTerminal -> (run.errorSummary ?: run.resultSummary)?.takeIf { it.isNotBlank() }
        else -> run.activities.lastOrNull()?.title ?: run.currentSummary?.takeIf { it.isNotBlank() }
    }

    /** "42 s" or "3 min 5 s", from the Host's elapsedMs. */
    fun elapsed(ms: Long?): String? {
        val seconds = (ms ?: return null) / 1000
        if (seconds < 1) return null
        return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
    }

    fun steps(count: Int): String = if (count == 1) "1 step" else "$count steps"

    /** What the Work Room's message field says while empty. */
    fun inputPlaceholder(run: ChatItem.ProviderInvocation): String = "Message ${providerName(run)}"

    // Why a Work Room message did not go through (W16AAPPLE-35). The draft is
    // kept in every case, so none of them asks the user to type it again.

    const val INPUT_NOT_CONNECTED = "Not connected to the agent. Your message is kept; send it again once connected."

    /** No ACK in time. It may still arrive, which would clear the draft; until then, sending again could send it twice. */
    const val INPUT_NO_ANSWER = "No answer from the agent yet. Your message is kept, but it may still reach the run, so check before sending it again."

    const val INPUT_NOT_READY = "This run cannot take a message yet."

    const val INPUT_CLAUDE_BUSY = "Claude Code takes a message once this run has ended."

    /**
     * A refusal, by the Host's reason (connectonion `ws_router/session.py`,
     * `network/io/websocket.py` `request_provider_input`,
     * `network/host/provider_workroom.py`). Never the reason itself: it is a
     * code, not a sentence.
     */
    fun inputRefused(reason: String?): String = when (reason) {
        "state_changed", "state_unconfirmed", "invalid_revision" ->
            "The run moved on before your message reached it. Check where it is now, then send again."
        "not_active" -> "This run is not taking messages right now. The agent may be busy with another turn."
        "unsupported_provider" -> INPUT_CLAUDE_BUSY
        "terminal_owns_session" -> "This session is being driven from the agent's own terminal, so it cannot take messages from here."
        "provider_start_failed" -> "The coding agent could not start with your message. Try sending it again."
        "invalid_request" -> "The agent could not read that message."
        else -> "The agent did not take your message."
    }

    // The provider's own permission mode (W16AAPPLE-43). Named after the
    // provider every time, so it never reads as the Host ceiling, which the
    // chat's approval chip sets for the whole agent.

    /** The control's heading: "Codex permissions". */
    fun permissionTitle(run: ChatItem.ProviderInvocation): String = "${providerName(run)} permissions"

    /** Under the heading: how this differs from the chat's approval mode. */
    fun permissionExplainer(run: ChatItem.ProviderInvocation): String =
        "How ${providerName(run)} works in this Work Room, inside the agent's approval mode. " +
            "A change applies from its next turn."

    fun permissionConfirmTitle(run: ChatItem.ProviderInvocation, label: String): String =
        "Switch ${providerName(run)} to $label?"

    /** Said before an elevated choice goes out. The Host's own description follows it. */
    fun permissionConfirmBody(run: ChatItem.ProviderInvocation): String =
        "This lets ${providerName(run)} act without asking you first, for its next turn."

    const val PERMISSION_CHANGING = "Changing…"

    const val PERMISSION_NOT_READY = "This run cannot change its permissions yet."

    const val PERMISSION_NOT_CONNECTED = "Not connected to the agent, so the change was not sent."

    const val PERMISSION_NO_ANSWER = "No answer from the agent. Check the mode shown here before trying again."

    /**
     * A refusal, by the Host's reason code (connectonion
     * `network/host/provider_permissions.py`). Never the code itself.
     */
    fun permissionRefused(reason: String?): String = when (reason) {
        "stale_revision", "invalid_revision" -> "The run moved on before the change reached it. Check its mode now, then try again."
        "ceiling_denied" -> "The agent's approval mode does not allow that. Raise it from the chat first."
        "confirmation_required" -> "That choice needs confirming first."
        "not_owner", "operator_required" -> "Only the person who started this session can change its permissions."
        "not_found" -> "This Work Room is no longer available on the agent."
        "unsupported_option" -> "That choice is not available for this run."
        else -> "The agent did not make that change."
    }

    fun approvalTitle(approval: WorkRoomApproval): String =
        approval.presentation?.action ?: "${providerName(approval.provider)} asks for approval"

    /** Null when allowing is offered. */
    fun approvalDenyOnly(approval: WorkRoomApproval): String? = when {
        approval.canAllowOnce || approval.canAllowSession -> null
        approval.presentation?.boundary == ApprovalBoundary.ELEVATED ->
            "This reaches outside this Work Room, so it can only be denied from here."
        else -> "Where this reaches could not be checked, so it can only be denied from here."
    }

    fun approvalOutcome(decision: ApprovalDecision): String = when {
        decision.refused -> "Not applied: the run was no longer waiting for this answer."
        decision.approved && decision.scope == "session" -> "Allowed for this session"
        decision.approved -> "Allowed once"
        else -> "Denied"
    }
}
