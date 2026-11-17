package ai.openonion.oochat.domain.model

/**
 * The user's answer to a plan_review card. The card says which; the wire text
 * is built here, from the plan as the agent sent it, since the card only ever
 * sees the plan redacted for display (W16AAPPLE-50) and the approval quotes it
 * back. Both strings byte-match web's plan-card.tsx handleApprove/handleReject
 * so the agent-side prompt parsing stays intact.
 */
sealed interface PlanReviewReply {
    fun message(planContent: String): String

    data object Approve : PlanReviewReply {
        override fun message(planContent: String) =
            "Plan approved. Implement now. Do NOT re-enter plan mode.\n\n---\n\n$planContent"
    }

    data class Reject(val feedback: String) : PlanReviewReply {
        override fun message(planContent: String) =
            "Plan rejected. Revise with write_plan(). Feedback: $feedback"
    }
}
