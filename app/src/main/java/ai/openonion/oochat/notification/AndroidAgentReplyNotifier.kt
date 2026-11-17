package ai.openonion.oochat.notification

import ai.openonion.oochat.MainActivity
import ai.openonion.oochat.R
import ai.openonion.oochat.data.local.AgentReplyNotifier
import ai.openonion.oochat.domain.model.NotificationTarget
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
const val AGENT_REPLY_CHANNEL_ID = "agent_replies_alerting"

/**
 * The first reply channel, at default importance, which only shows in the shade.
 * Android never lets an app raise a channel's importance, so it is replaced.
 */
private const val OLD_AGENT_REPLY_CHANNEL_ID = "agent_replies"

/**
 * Approvals the agent is waiting on (W16AAPPLE-44). A channel of its own, so
 * the user can silence replies in the system settings and keep these, or the
 * other way round.
 */
const val APPROVAL_CHANNEL_ID = "approval_requests"

private const val NOTIFICATION_ID = 2001

/** One id for every approval notification; each is told apart by its tag, the approval's id. */
private const val APPROVAL_NOTIFICATION_ID = 2002

/** One id for every finished-run notification; each is told apart by its tag, the run's id. */
private const val RUN_FINISHED_NOTIFICATION_ID = 2003

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
    private val postedRuns = mutableSetOf<String>()

    override fun notifyAgentReply(agentName: String?, preview: String, target: NotificationTarget?) {
        ensureChannel()
        val tapIntent = target?.let { NotificationIntents.open(context, it) }
            ?: Intent(context, MainActivity::class.java)
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
        synchronized(postedRuns) {
            postedRuns.forEach { manager.cancel(it, RUN_FINISHED_NOTIFICATION_ID) }
            postedRuns.clear()
        }
    }

    override fun notifyRunFinished(invocationId: String, title: String, summary: String, target: NotificationTarget) {
        ensureChannel()
        // Its own request code, so two runs' taps keep their own extras (see notifyApprovalNeeded).
        val contentIntent = PendingIntent.getActivity(
            context,
            invocationId.hashCode(),
            NotificationIntents.open(context, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = SensitiveText.redactWithoutPaths(summary).take(PREVIEW_MAX_LENGTH)
        val notification = NotificationCompat.Builder(context, AGENT_REPLY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reply)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        synchronized(postedRuns) { postedRuns += invocationId }
        // Gated on POST_NOTIFICATIONS by the caller, as for a reply.
        @Suppress("MissingPermission")
        manager.notify(invocationId, RUN_FINISHED_NOTIFICATION_ID, notification)
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
        // High, so a reply or a finished run pops up: the user left the app waiting for it.
        val channel = NotificationChannel(AGENT_REPLY_CHANNEL_ID, "Agent replies", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When an agent replies or a Work Room run finishes while the app is in the background"
        }
        context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(OLD_AGENT_REPLY_CHANNEL_ID)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelCreated = true
    }

    private fun ensureApprovalChannel() {
        if (approvalChannelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // High: the agent is stopped until this is answered, so it is worth a heads-up.
        val channel = NotificationChannel(APPROVAL_CHANNEL_ID, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When an agent or a Work Room is waiting for you to allow or deny something"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        approvalChannelCreated = true
    }
}
