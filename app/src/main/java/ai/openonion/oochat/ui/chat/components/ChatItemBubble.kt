package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.PlanReviewReply
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.ui.workroom.WORK_ROOM_DENY_MODE
import ai.openonion.oochat.ui.workroom.WorkRoomApprovalCard
import androidx.compose.runtime.Composable

@Composable
internal fun ChatItemBubble(
    item: ChatItem,
    timestamp: String?,
    onRespond: (optionIndex: Int) -> Unit,
    onApprove: (approved: Boolean, scope: String, mode: String?, feedback: String?) -> Unit,
    onOnboard: (method: String, inviteCode: String?, payment: Double?) -> Unit,
    onPlanReviewResponse: (PlanReviewReply) -> Unit,
    onUlwResponse: (action: String, turns: Int?, mode: String?) -> Unit,
    renderMarkdown: Boolean = true,
    showSender: Boolean = true,
    onRetry: (() -> Unit)? = null,
    hasLiveConnection: Boolean = true,
    onResend: (() -> Unit)? = null,
    // The footer a Turn shows in place of its own when it answers a turn
    // with steps — the whole turn's usage (see groupTurnSteps).
    turnFooter: ChatItem.Thinking? = null,
    // When the turn this item belongs to started, for the "Working…" clock.
    workingSinceMs: Long? = null,
    onOpenWorkRoom: (invocationId: String) -> Unit = {}
) {
    when (item) {
        is ChatItem.User -> UserBubble(item, timestamp, renderMarkdown, showSender, onResend)
        is ChatItem.Agent -> AgentBubble(item, timestamp, renderMarkdown, showSender)
        is ChatItem.Thinking -> {
            // While running, the same "Working…" line a turn's steps sit
            // under; once done/errored, the richer status bubble. Reasoning
            // is something the agent thought, not a status, so it reads as a
            // thought step.
            if (item.isReasoning) {
                ReasoningBubble(item)
            } else if (item.status == ThinkingStatus.RUNNING) {
                WorkingHeader(workingSinceMs)
            } else {
                ThinkingBubble(item, timestamp, onRetry)
            }
        }
        is ChatItem.Turn -> TurnBubble(item, onRetry, renderMarkdown, timestamp, turnFooter, workingSinceMs)
        is ChatItem.ToolCall -> ToolCallDispatch(item)
        is ChatItem.ProviderInvocation -> WorkRoomCard(item, onOpen = { onOpenWorkRoom(item.id) })
        // Folded into its run's card by the reducer; never in the list itself.
        is ChatItem.ProviderActivityUpdate, is ChatItem.ProviderMessageUpdate, is ChatItem.ProviderArtifactUpdate -> Unit
        is ChatItem.AskUser -> AskUserCard(item, onRespond)
        is ChatItem.ApprovalNeeded -> {
            val workRoom = item.workRoom
            if (workRoom != null) {
                WorkRoomApprovalCard(item, workRoom) { approved, scope ->
                    onApprove(approved, scope, if (approved) null else WORK_ROOM_DENY_MODE, null)
                }
            } else {
                ApprovalCard(item, onApprove)
            }
        }
        // Deliberately one call site for both variants. Compose gives each
        // branch its own identity, so two calls would rebuild the card — and
        // drop the keyboard — every time the gate swapped between them.
        is ChatItem.OnboardRequired, is ChatItem.OnboardingFailed -> {
            val rejection = (item as? ChatItem.OnboardingFailed)?.reason
            OnboardGateCard(
                title = if (rejection != null) "Onboarding Rejected" else "Onboarding Required",
                subtitle = if (rejection != null) {
                    "Try again with a different invite code."
                } else {
                    "This agent requires an invite code to activate your session."
                },
                onOnboard = onOnboard,
                errorReason = rejection,
                hasLiveConnection = hasLiveConnection
            )
        }
        is ChatItem.OnboardSuccess -> OnboardSuccessCard(item)
        is ChatItem.IntentItem -> IntentCard(item)
        is ChatItem.EvalItem -> EvalCard(item)
        is ChatItem.CompactItem -> CompactDivider(item)
        is ChatItem.ToolBlockedItem -> ToolBlockedCard(item)
        is ChatItem.FilesReceivedItem -> FilesReceivedCard(item)
        is ChatItem.DiffPreviewItem -> DiffPreviewCard(item)
        is ChatItem.PlanReviewItem -> PlanReviewCard(item, onPlanReviewResponse)
        is ChatItem.UlwTurnsReachedItem -> UlwTurnsReachedCard(item, onUlwResponse)
        is ChatItem.ModeChangedItem -> ModeChangedDivider(item)
    }
}
