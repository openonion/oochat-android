package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.knownIssue
import ai.openonion.oochat.testing.FakeOipHost.Companion.string
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException

/**
 * A message queued offline survives the app being killed and is sent once on
 * the next launch. The kill is modelled as in [ProcessDeathResumeTest]: only
 * the database file is shared with the relaunch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineQueueRestartTest : RelayResumeTestBase() {

    private val queued = "Rerun the failed deploy when you're back"
    private val launches = mutableListOf<App>()

    @After
    fun closeLaunches() {
        launches.forEach { if (it.db.isOpen) it.db.close() }
    }

    private fun cutNetwork() {
        online.tryEmit(false)
        relay.dialFailure = UnknownHostException("Unable to resolve host \"$RELAY_HOST\": No address associated with hostname")
        relay.drop(liveSocket)
    }

    private fun restoreNetwork() {
        relay.dialFailure = null
        online.tryEmit(true)
    }

    private suspend fun App.outboxSize(): Int = withContext(Dispatchers.IO) { db.pendingMessageDao().countForAgent(agent) }

    /** Nothing of this launch runs again; teardown leaves the outbox alone. */
    private suspend fun App.kill() {
        vm.viewModelScope.cancel()
        connection.disconnect(keepOutbox = true)
        db.close()
    }

    /** Connected, then offline, with one message in the outbox. */
    private suspend fun queueWhileOffline(): Pair<App, String> {
        val first = connectedIdle().also { launches += it }
        val conversation = relay.connects.single().string("session_id")
            ?: error("a conversation with an id of its own must CONNECT on it")

        cutNetwork()
        pumpUntil("the app to notice the connection is gone") { !first.connection.isConnected() }

        first.vm.sendMessage(queued)
        pumpUntil("the message to reach the outbox") { runBlocking { first.outboxSize() } == 1 }
        pumpUntil("the message to be saved in the chat") {
            runBlocking { first.rows(conversation) }.any { it.role == Role.USER && it.content == queued }
        }
        assertEquals(
            "the bubble must say it is waiting, not sent",
            UserMessageState.QUEUED,
            first.items.filterIsInstance<ChatItem.User>().single { it.content == queued }.state
        )
        assertTrue("nothing can have gone out with no network", relay.inputs.isEmpty())
        return first to conversation
    }

    private suspend fun relaunch(newChat: Boolean = false): App {
        val next = App().also { launches += it }
        if (newChat) next.vm.markStartNewSessionOnConnect()
        next.vm.connectToAgent(agent)
        return next
    }

    @Test
    fun `a message queued offline survives the kill and goes out once on the next launch`() = runBlocking {
        val (first, conversation) = queueWhileOffline()
        first.kill()

        // Back in a different chat, so a message that lost its conversation
        // would go out here instead of waiting for its own.
        restoreNetwork()
        val second = relaunch(newChat = true)
        pumpUntil("the new chat to connect") {
            relay.connects.size == 2 && second.connection.isConnected() && second.connectCalls.finished.get() == 1
        }
        val otherChat = relay.connects.last().string("session_id")
        assertTrue("the relaunch must be on another conversation", otherChat != null && otherChat != conversation)
        pumpFor(5_000L)
        assertTrue("it must not go out on another conversation: ${relay.inputs}", relay.inputs.isEmpty())
        assertEquals("so the outbox still holds it", 1, second.outboxSize())

        second.vm.switchToSession(conversation)
        pumpUntil("the queued message to reach the Host") { relay.inputs.isNotEmpty() }
        // Time for a duplicate send to show up.
        pumpFor(5_000L)

        assertEquals(
            "the queued message must be sent exactly once",
            listOf(queued),
            relay.inputs.map { it.string("prompt") }
        )
        assertEquals("on the conversation it was written in", listOf(conversation), relay.inputSessions)
        assertEquals("the outbox is empty once it has gone", 0, second.outboxSize())

        val rows = second.rows(conversation).filter { it.role == Role.USER }
        assertEquals("the message is saved once", listOf(queued), rows.map { it.content })
        val bubbles = second.items.filterIsInstance<ChatItem.User>().filter { it.content == queued }
        assertEquals("and shown once", 1, bubbles.size)
        // Not checking the bubble's state: after a restart it shows as sent
        // even before it has gone (W16AAPPLE-106, the test below).
    }

    @Test
    fun `a message still queued after the restart is not shown as failed`() = runBlocking {
        val (first, conversation) = queueWhileOffline()
        first.kill()

        val second = relaunch()
        pumpUntil("the saved conversation to load") {
            second.items.any { it is ChatItem.User && it.content == queued }
        }

        assertEquals("the outbox still holds it", 1, second.outboxSize())
        assertEquals(conversation, second.vm.activeSessionId.value)
        val bubble = second.items.filterIsInstance<ChatItem.User>().single { it.content == queued }
        assertTrue("a message the outbox will still send must not offer Retry", bubble.state != UserMessageState.FAILED)
        knownIssue("W16AAPPLE-106: after a restart, a message still in the outbox shows as sent, not \"Waiting to send\"") {
            assertEquals("it has not gone yet, so it should still say so", UserMessageState.QUEUED, bubble.state)
        }
    }
}
