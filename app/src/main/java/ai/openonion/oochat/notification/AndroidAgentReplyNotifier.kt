package ai.openonion.oochat.notification

import ai.openonion.oochat.MainActivity
import ai.openonion.oochat.R
import ai.openonion.oochat.data.local.AgentReplyNotifier
import ai.openonion.oochat.domain.model.PendingApproval
import ai.openonion.oochat.util.SensitiveText
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Shared with SettingsScreen's "Notification sound" row, which opens the OS's own settings for this channel. */
const val AGENT_REPLY_CHANNEL_ID = "agent_replies"

/**
 * Approvals the agent is waiting on (W16AAPPLE-44). A channel of its own, so
 * it can interrupt where a reply only waits in the shade, and so the user can
 * silence replies in the system settings and keep these, or the other way round.
 */
const val APPROVAL_CHANNEL_ID = "approval_requests"

private const val NOTIFICATION_ID = 2001

/** One id for every approval notification; each is told apart by its tag, the approval's id. */
private const val APPROVAL_NOTIFICATION_ID = 2002

/** Longer than this reads as noise in a notification's collapsed line, and BigTextStyle already expands it. */
private const val PREVIEW_MAX_LENGTH = 200

/**
 * [AgentReplyNotifier] backed by the real OS APIs.
 *
 * The channel is created on first use, not from [android.app.Application.onCreate]
 * — this app ships baseline profiles, and most cold starts never post a
 * notification at all, so that work has no business being on the startup path.
 */
class AndroidAgentReplyNotifier(private val context: Context) : AgentReplyNotifier {
    private val manager = NotificationManagerCompat.from(context)
    private var channelCreated = false
    private var approvalChannelCreated = false

    override fun notifyAgentReply(agentName: String?, preview: String) {
        ensureChannel()
        val tapIntent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Shown on the lock screen too (W16AAPPLE-50), so no path at all.
        // Redacted before the cut, so a key the cut would split is still
        // recognised whole.
        val text = SensitiveText.redactWithoutPaths(preview).take(PREVIEW_MAX_LENGTH)
        val notification = NotificationCompat.Builder(context, AGENT_REPLY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reply)
            .setContentTitle(agentName?.takeIf { it.isNotBlank() } ?: "New reply")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // Caller (ChatViewModel) has already checked POST_NOTIFICATIONS before
        // reaching here — see its gating doc — so this can't actually throw
        // the SecurityException the missing-permission lint warning implies.
        @Suppress("MissingPermission")
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun clearReplyNotifications() {
        manager.cancel(NOTIFICATION_ID)
    }

    override fun notifyApprovalNeeded(approval: PendingApproval) {
        ensureApprovalChannel()
        // A request code of its own: PendingIntents with the same code share
        // their extras, so a second approval would retarget the first one's tap.
        val contentIntent = PendingIntent.getActivity(
            context,
            approval.approvalId.hashCode(),
            NotificationIntents.open(context, approval.target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "${approval.agentName?.takeIf { it.isNotBlank() } ?: "Your agent"} needs your approval"
        // On the lock screen too (W16AAPPLE-50), so no path at all, and
        // redacted before the cut, as for a reply.
        val text = SensitiveText.redactWithoutPaths(approval.summary).take(PREVIEW_MAX_LENGTH)
        // What a locked phone shows instead: that something is waiting, not
        // what, since a command can say more than the owner wants read out.
        val public = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reply)
            .setContentTitle(title)
            .setContentText("Unlock to review it")
            .build()
        val notification = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reply)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // Gated on POST_NOTIFICATIONS by the caller, as for a reply.
        @Suppress("MissingPermission")
        manager.notify(approval.approvalId, APPROVAL_NOTIFICATION_ID, notification)
    }

    override fun clearApprovalNotification(approvalId: String) {
        manager.cancel(approvalId, APPROVAL_NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (channelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(AGENT_REPLY_CHANNEL_ID, "Agent replies", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelCreated = true
    }

    private fun ensureApprovalChannel() {
        if (approvalChannelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // High: the agent is stopped until this is answered, so it is worth a
        // heads-up, where a reply can wait in the shade.
        val channel = NotificationChannel(APPROVAL_CHANNEL_ID, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When an agent or a Work Room is waiting for you to allow or deny something"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        approvalChannelCreated = true
    }
}
