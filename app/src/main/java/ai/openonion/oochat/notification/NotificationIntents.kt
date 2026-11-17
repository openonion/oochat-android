package ai.openonion.oochat.notification

import ai.openonion.oochat.MainActivity
import ai.openonion.oochat.domain.model.NotificationTarget
import ai.openonion.oochat.network.AgentDiscoveryService
import android.content.Context
import android.content.Intent

/**
 * The intent a notification opens the app with, and reading it back
 * (W16AAPPLE-44). Both sides here, so the notifier and
 * [ai.openonion.oochat.MainActivity] cannot disagree on a key.
 */
object NotificationIntents {
    /** Marks an intent as a notification tap, so a launcher start is never read as one. */
    const val ACTION_OPEN = "ai.openonion.oochat.action.OPEN_FROM_NOTIFICATION"

    private const val EXTRA_AGENT_ADDRESS = "ai.openonion.oochat.extra.AGENT_ADDRESS"
    private const val EXTRA_SESSION_ID = "ai.openonion.oochat.extra.SESSION_ID"
    private const val EXTRA_INVOCATION_ID = "ai.openonion.oochat.extra.INVOCATION_ID"

    /**
     * Opens [target] in the running app. SINGLE_TOP with CLEAR_TOP hands it to
     * the existing activity's onNewIntent instead of tearing that activity
     * down and starting the app over.
     */
    fun open(context: Context, target: NotificationTarget): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_AGENT_ADDRESS, target.agentAddress)
            .putExtra(EXTRA_SESSION_ID, target.sessionId)
            .putExtra(EXTRA_INVOCATION_ID, target.invocationId)

    /**
     * The target [intent] asks for, or null when it is not a notification tap
     * or names no well-formed agent address. The activity is exported, so this
     * is only a request: [ai.openonion.oochat.ui.navigation.OpenNotificationTarget]
     * still acts on it only for an agent the user has saved.
     */
    fun targetOf(intent: Intent?): NotificationTarget? {
        if (intent?.action != ACTION_OPEN) return null
        val address = intent.getStringExtra(EXTRA_AGENT_ADDRESS)
            ?.takeIf { AgentDiscoveryService.ADDRESS_REGEX.matches(it) }
            ?: return null
        return NotificationTarget(
            agentAddress = address,
            sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() },
            invocationId = intent.getStringExtra(EXTRA_INVOCATION_ID)?.takeIf { it.isNotBlank() }
        )
    }
}
