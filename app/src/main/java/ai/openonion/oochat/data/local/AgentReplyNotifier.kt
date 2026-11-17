package ai.openonion.oochat.data.local

import ai.openonion.oochat.domain.model.PendingApproval

/**
 * Android-free boundary for the app's background system notifications — the
 * agent's reply, and an approval it is waiting on (W16AAPPLE-44).
 * [ui.chat.ChatViewModel][ai.openonion.oochat.ui.chat.ChatViewModel]
 * depends on this instead of `android.app.NotificationManager` directly, the
 * same reason [AppSettings] sits here rather than a concrete DataStore type.
 * The real implementation is [ai.openonion.oochat.notification.AndroidAgentReplyNotifier].
 */
interface AgentReplyNotifier {
    /** Post (or replace) the reply notification. [agentName] null/blank falls back to a generic title. */
    fun notifyAgentReply(agentName: String?, preview: String)

    /** Dismiss it — called once the chat is back in the foreground. */
    fun clearReplyNotifications()

    /**
     * Post the notification for [approval], one per approval id. Default
     * no-op so a fake that only checks replies does not have to say so.
     */
    fun notifyApprovalNeeded(approval: PendingApproval) {}

    /** Dismiss the notification for the approval with this id, if there is one. */
    fun clearApprovalNotification(approvalId: String) {}
}
