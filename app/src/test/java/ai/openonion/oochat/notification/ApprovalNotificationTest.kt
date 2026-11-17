package ai.openonion.oochat.notification

import ai.openonion.oochat.domain.model.NotificationTarget
import ai.openonion.oochat.domain.model.PendingApproval
import ai.openonion.oochat.ui.navigation.notificationChatRoute
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * W16AAPPLE-44: the approval notification the system is handed, and that
 * tapping it leads back to the approval's conversation or Work Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalNotificationTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = AndroidAgentReplyNotifier(context)

    private val address = "0x" + "ab".repeat(32)
    private val target = NotificationTarget(address, sessionId = "session-1", invocationId = "codex:inv-1")

    private fun approval(id: String = "ap-1", summary: String = "bash: rm -rf build") =
        PendingApproval(approvalId = id, agentName = "Harbour", summary = summary, target = target)

    private fun posted(): List<Notification> =
        shadowOf(manager).allNotifications.filter { it.channelId == APPROVAL_CHANNEL_ID }

    @Test
    fun `an approval is posted on its own high-importance channel`() {
        notifier.notifyApprovalNeeded(approval())

        val notification = posted().single()
        val extras = notification.extras
        assertEquals("Harbour needs your approval", extras.getString(Notification.EXTRA_TITLE))
        assertEquals("bash: rm -rf build", extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(APPROVAL_CHANNEL_ID).importance)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("Unlock to review it", notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun `tapping it opens the approval's conversation and Work Room`() {
        notifier.notifyApprovalNeeded(approval())

        val tap = shadowOf(posted().single().contentIntent).savedIntent
        assertEquals(target, NotificationIntents.targetOf(tap))
    }

    @Test
    fun `two approvals are two notifications, each tapping through to its own target`() {
        notifier.notifyApprovalNeeded(approval("ap-1"))
        notifier.notifyApprovalNeeded(
            approval("ap-2").copy(target = target.copy(sessionId = "session-2", invocationId = null))
        )

        val targets = posted().map { NotificationIntents.targetOf(shadowOf(it.contentIntent).savedIntent) }
        assertEquals(2, targets.size)
        assertTrue(targets.containsAll(listOf(target, target.copy(sessionId = "session-2", invocationId = null))))
    }

    @Test
    fun `clearing one approval leaves the others`() {
        notifier.notifyApprovalNeeded(approval("ap-1"))
        notifier.notifyApprovalNeeded(approval("ap-2"))

        notifier.clearApprovalNotification("ap-1")

        assertEquals(1, posted().size)
    }

    @Test
    fun `what the approval asks is redacted, since a notification is read anywhere`() {
        notifier.notifyApprovalNeeded(approval(summary = "bash: cat /Users/priya/.env OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuv"))

        val text = posted().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(text, text.contains("sk-proj") || text.contains("priya"))
    }

    @Test
    fun `only a notification tap with a well-formed agent address is read as a target`() {
        assertNull("a launcher start", NotificationIntents.targetOf(Intent(Intent.ACTION_MAIN)))
        assertNull(
            "an address that is not one",
            NotificationIntents.targetOf(NotificationIntents.open(context, target.copy(agentAddress = "0xnope")))
        )
        assertEquals(
            NotificationTarget(address),
            NotificationIntents.targetOf(NotificationIntents.open(context, NotificationTarget(address)))
        )
    }

    @Test
    fun `the chat route names the agent and the conversation, encoded`() {
        assertEquals("chat/$address?sessionId=a%20b", notificationChatRoute(target.copy(sessionId = "a b")))
        assertEquals("chat/$address", notificationChatRoute(NotificationTarget(address)))
    }
}
