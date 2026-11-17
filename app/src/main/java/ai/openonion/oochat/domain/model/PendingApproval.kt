package ai.openonion.oochat.domain.model

/**
 * Where tapping a notification takes the user (W16AAPPLE-44): an agent's
 * conversation, and the Work Room of one of its runs when the notification is
 * about that run.
 *
 * @param agentAddress The agent the notification came from. A tap only acts on
 *   an agent the user has saved, since anything on the phone can start the
 *   app's activity with these extras.
 * @param sessionId The conversation; null opens the agent's current one.
 * @param invocationId A Claude Code or Codex run in that conversation, whose
 *   Work Room opens over it.
 */
data class NotificationTarget(
    val agentAddress: String,
    val sessionId: String? = null,
    val invocationId: String? = null
)

/**
 * An approval the agent is parked on, as the background notification shows it
 * (W16AAPPLE-44). One notification per approval, keyed by [approvalId], so two
 * waiting approvals stay two, and answering one clears only its own.
 *
 * @param approvalId The [ChatItem.ApprovalNeeded] item's id.
 * @param summary What it asks for, in one line, as the agent sent it; the
 *   notifier redacts it, since a notification is read on the lock screen.
 */
data class PendingApproval(
    val approvalId: String,
    val agentName: String?,
    val summary: String,
    val target: NotificationTarget
)
