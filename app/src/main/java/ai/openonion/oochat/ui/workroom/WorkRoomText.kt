package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ApprovalBoundary
import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderControlPhase
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

    /**
     * Where a Claude Code session's control stands, by the phase the Host confirmed
     * (W16AAPPLE-40). A handover under way says so, and says it has not happened yet; only
     * the Host's own `remote_controlling` says a remote client has control. The Host's owner is
     * only `browser` for any remote client, so it cannot say whether that is this app or the web,
     * and the words do not claim it. The owner is not used: it only follows from the phase
     * (claude_station.py `_session_event`).
     */
    fun handover(phase: ProviderControlPhase): String = when (phase) {
        ProviderControlPhase.LOCAL_STARTING -> "Starting in the terminal"
        ProviderControlPhase.LOCAL_OBSERVING -> "Running in the terminal. This app is watching."
        ProviderControlPhase.HANDOVER_TO_REMOTE -> "Handing over to a remote client. Not confirmed yet."
        ProviderControlPhase.REMOTE_CONTROLLING -> "Controlled remotely (this app or the web)"
        ProviderControlPhase.HANDOVER_TO_LOCAL -> "Handing back to the terminal. Not confirmed yet."
        ProviderControlPhase.COMPLETED -> "Session completed"
        ProviderControlPhase.FAILED -> "Session failed"
    }

    /** What the phase does and does not mean, for the Work Room; null when the line says it all. */
    fun handoverDetail(phase: ProviderControlPhase): String? = when (phase) {
        ProviderControlPhase.LOCAL_STARTING -> null
        ProviderControlPhase.LOCAL_OBSERVING -> "The terminal still has control. You can read what happens here."
        ProviderControlPhase.HANDOVER_TO_REMOTE ->
            "Control has not moved yet. The Host will confirm it, or report that the handover failed."
        ProviderControlPhase.REMOTE_CONTROLLING ->
            "The Host confirmed that a remote client has control. It does not say whether that is this app or the web."
        ProviderControlPhase.HANDOVER_TO_LOCAL ->
            "Control has not returned yet. The Host will confirm it when the terminal has the session."
        ProviderControlPhase.COMPLETED -> null
        ProviderControlPhase.FAILED -> "The Host reported that the session did not complete."
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

    // Scoped Stop (W16AAPPLE-37). Always "this run", so it never reads as the
    // chat's Stop, which ends the agent's whole turn.

    fun stopTitle(run: ChatItem.ProviderInvocation): String = "Stop this ${providerName(run)} run?"

    fun stopBody(run: ChatItem.ProviderInvocation): String =
        "Only this Work Room stops. The agent keeps going with the rest of its turn, " +
            "and ${providerName(run)} keeps the changes it has already made."

    /** How long the confirmation stays open. */
    fun stopCountdown(seconds: Int): String = if (seconds == 1) "Closes in 1 second" else "Closes in $seconds seconds"

    const val STOP = "Stop run"

    const val STOP_CONFIRM = "Stop"

    const val STOP_KEEP = "Keep running"

    const val STOP_SENDING = "Stopping…"

    const val STOP_NOT_READY = "This run cannot be stopped from here yet."

    const val STOP_NOT_CONNECTED = "Not connected to the agent, so Stop was not sent."

    const val STOP_NO_ANSWER = "No answer from the agent. Check whether the run is still working before stopping it again."

    /**
     * A refusal, by the Host's reason (connectonion `network/host/ws_router/session.py`,
     * `network/io/websocket.py` `request_provider_interrupt`). Never the code itself.
     */
    fun stopRefused(reason: String?): String = when (reason) {
        "state_changed", "state_unconfirmed", "invalid_revision" ->
            "The run moved on before Stop reached it. Check where it is now, then stop it again."
        "not_active" -> "This run is no longer running, so there was nothing to stop."
        "invalid_request" -> "The agent could not read that Stop."
        else -> "The agent did not stop this run."
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
