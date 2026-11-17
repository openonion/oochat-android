package ai.openonion.oochat.network

import ai.openonion.oochat.data.protocol.FileAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [InMemoryPendingMessageSink]: per-address isolation, FIFO
 * drain order, [PendingMessageSink.clear], and the
 * [AgentConnection.MAX_PENDING_MESSAGES] eviction cap — none of which had any
 * test coverage despite backing every queued-message send retried on
 * reconnect.
 */
class InMemoryPendingMessageSinkTest {

    private val sink = InMemoryPendingMessageSink()

    /** The session these messages are queued in; see PendingMessageSink. */
    private val SESSION = "sess-1"

    @Test
    fun `enqueue then drain hands back messages oldest first`() {
        sink.enqueue("0xagent", SESSION, "first", null)
        sink.enqueue("0xagent", SESSION, "second", listOf("img.png"))

        val dequeued = sink.drainAll("0xagent", SESSION)

        assertEquals(2, dequeued.size)
        assertEquals("first", dequeued[0].prompt)
        assertEquals(null, dequeued[0].images)
        assertEquals("second", dequeued[1].prompt)
        assertEquals(listOf("img.png"), dequeued[1].images)
    }

    @Test
    fun `drain removes only the matching address, leaving other addresses queued`() {
        sink.enqueue("0xagentA", "for A")
        sink.enqueue("0xagentB", "for B")

        val dequeuedA = sink.drainAll("0xagentA", SESSION)

        assertEquals(listOf("for A"), dequeuedA.map { it.prompt })
        assertEquals(listOf("for B"), sink.drainAll("0xagentB", SESSION).map { it.prompt })
    }

    @Test
    fun `drain on an address with nothing queued hands back nothing`() {
        assertTrue(sink.drainAll("0xnever-queued", SESSION).isEmpty())
    }

    @Test
    fun `drain empties the queue so a second call hands back nothing`() {
        sink.enqueue("0xagent", "only message")

        sink.drainAll("0xagent", SESSION)
        val second = sink.drainAll("0xagent", SESSION)

        assertTrue(second.isEmpty())
    }

    @Test
    fun `a rejected send stays queued`() {
        sink.enqueue("0xagent", SESSION, "retry me", null)

        sink.drain("0xagent", SESSION, null) { false }

        assertEquals(listOf("retry me"), sink.drainAll("0xagent", SESSION).map { it.prompt })
    }

    @Test
    fun `clear discards queued messages for one address without touching others`() {
        sink.enqueue("0xagentA", "for A")
        sink.enqueue("0xagentB", "for B")

        sink.clear("0xagentA")

        assertTrue(sink.drainAll("0xagentA", SESSION).isEmpty())
        assertEquals(listOf("for B"), sink.drainAll("0xagentB", SESSION).map { it.prompt })
    }

    @Test
    fun `clearing a conversation discards only what would go out in it`() {
        // Clear chat in one conversation must not drop what another
        // conversation with the same agent queued offline (W16AAPPLE-106).
        sink.enqueue("0xagent", "for this session", sessionId = SESSION)
        sink.enqueue("0xagent", "for this conversation", sessionId = "conversation-A")
        sink.enqueue("0xagent", "for another conversation", sessionId = "conversation-B")
        sink.enqueue("0xagentB", "for another agent", sessionId = SESSION)

        sink.clearConversation("0xagent", SESSION, "conversation-A")

        assertEquals(listOf("for another conversation"), sink.drainAll("0xagent", "conversation-B").map { it.prompt })
        assertEquals(listOf("for another agent"), sink.drainAll("0xagentB", SESSION).map { it.prompt })
    }

    @Test
    fun `clearing a conversation keeps what was queued before any session existed`() {
        // Nothing says which conversation such a message was written in, so
        // Clear chat in one of them must not be what drops it (W16AAPPLE-106).
        sink.enqueue("0xagent", "queued offline", sessionId = null)

        sink.clearConversation("0xagent", null, "conversation-A")

        assertEquals(listOf("queued offline"), sink.drainAll("0xagent", "conversation-B").map { it.prompt })
    }

    @Test
    fun `the count for a conversation is what would go out in it`() {
        // What the chat settles "Waiting to send..." bubbles by (W16AAPPLE-106).
        // A message no conversation is named on would go out in whichever one
        // connects next, so it counts for each: a bubble waits a little longer
        // rather than being called sent while it is still queued.
        sink.enqueue("0xagent", "on the session", sessionId = SESSION)
        sink.enqueue("0xagent", "on the conversation", sessionId = "conversation-A")
        sink.enqueue("0xagent", "elsewhere", sessionId = "conversation-B")
        sink.enqueue("0xagent", "unnamed", sessionId = null)
        sink.enqueue("0xagentB", "another agent", sessionId = "conversation-A")

        assertEquals(3, sink.countForConversation("0xagent", SESSION, "conversation-A"))
        assertEquals(2, sink.countForConversation("0xagent", null, "conversation-B"))
        assertEquals(1, sink.countForConversation("0xagent", null, "conversation-C"))
    }

    @Test
    fun `clearing one conversation does not stop another's failed send going back`() {
        // B is connected and flushing; Clear chat in A runs meanwhile. B's
        // message has to go back in the outbox, not be lost (W16AAPPLE-106).
        sink.enqueue("0xagent", "for B", sessionId = "conversation-B")
        sink.enqueue("0xagent", "for A", sessionId = "conversation-A")

        sink.drain("0xagent", null, "conversation-B") {
            sink.clearConversation("0xagent", null, "conversation-A")
            false
        }

        assertEquals(listOf("for B"), sink.drainAll("0xagent", "conversation-B").map { it.prompt })
        assertTrue(sink.drainAll("0xagent", "conversation-A").isEmpty())
    }

    @Test
    fun `clearing the conversation being flushed does drop its failed send`() {
        sink.enqueue("0xagent", "for B", sessionId = "conversation-B")

        sink.drain("0xagent", null, "conversation-B") {
            sink.clearConversation("0xagent", null, "conversation-B")
            false
        }

        assertTrue(sink.drainAll("0xagent", "conversation-B").isEmpty())
    }

    @Test
    fun `a full outbox refuses a new message rather than dropping an older one`() {
        // An older one may already say "Waiting to send..." or, after a
        // restart, be about to be marked sent: dropping it silently would make
        // that a lie. The new one is refused instead, and its bubble offers
        // Retry (W16AAPPLE-106).
        repeat(AgentConnection.MAX_PENDING_MESSAGES) { i -> assertTrue(sink.enqueue("0xagent", "msg-$i")) }

        assertFalse("the outbox is full", sink.enqueue("0xagent", "one too many"))
        assertTrue("another agent's outbox has room of its own", sink.enqueue("0xagentB", "for B"))

        val drained = sink.drainAll("0xagent", SESSION).map { it.prompt }
        assertEquals((0 until AgentConnection.MAX_PENDING_MESSAGES).map { "msg-$it" }, drained)
    }

    @Test
    fun `enqueue then drain carries files through alongside images`() {
        // A message sent while disconnected must not drop its files — see
        // AgentConnection.sendMessage / MIGRATION_8_9's files_json column.
        val files = listOf(FileAttachment(name = "report.pdf", data = "data:application/pdf;base64,AAAA"))

        sink.enqueue("0xagent", SESSION, "see attached", listOf("img.png"), files)
        val dequeued = sink.drainAll("0xagent", SESSION)

        assertEquals(1, dequeued.size)
        assertEquals(files, dequeued.single().files)
    }

    @Test
    fun `enqueue without files defaults to null files on drain`() {
        sink.enqueue("0xagent", "no attachments")

        assertEquals(null, sink.drainAll("0xagent", SESSION).single().files)
    }

    private fun PendingMessageSink.enqueue(
        agentAddress: String,
        prompt: String,
        sessionId: String? = SESSION
    ): Boolean = enqueue(agentAddress, sessionId, prompt, images = null)

    /**
     * [PendingMessageSink.drain] as a list, for assertions. The production
     * caller deliberately never does this — see the interface's own doc — but
     * order and contents are what these tests are about.
     */
    private fun PendingMessageSink.drainAll(
        agentAddress: String,
        sessionId: String?,
        conversationId: String? = null
    ): List<QueuedMessage> = buildList {
        drain(agentAddress, sessionId, conversationId) { add(it) }
    }

    @Test
    fun `a message past the attachment byte budget is refused, and what is queued stays`() {
        // 50 rows says nothing about size: each one can carry a base64
        // attachment, so a handful of them can outweigh a full queue of text.
        val big = "x".repeat((AgentConnection.MAX_PENDING_ATTACHMENT_BYTES / 2).toInt() + 1)
        assertTrue(sink.enqueue("0xagent", SESSION, "first big", listOf(big)))

        assertFalse("two attachments over the budget cannot both be queued", sink.enqueue("0xagent", SESSION, "second big", listOf(big)))
        assertTrue("text alone still fits", sink.enqueue("0xagent", SESSION, "plain text", null))

        assertEquals(listOf("first big", "plain text"), sink.drainAll("0xagent", SESSION).map { it.prompt })
    }

    @Test
    fun `a single message over the whole budget is refused`() {
        // It could never fit, and refusing it is what tells the user so: its
        // bubble fails with Retry rather than waiting forever.
        val huge = "x".repeat(AgentConnection.MAX_PENDING_ATTACHMENT_BYTES.toInt() + 1)

        assertFalse(sink.enqueue("0xagent", SESSION, "oversized", listOf(huge)))

        assertTrue(sink.drainAll("0xagent", SESSION).isEmpty())
    }

    @Test
    fun `a message queued in one session is not drained by another`() {
        // Switching conversation before a queued message is acknowledged used
        // to flush it onto the conversation switched to instead.
        sink.enqueue("0xagent", "meant for B", sessionId = "sess-B")

        assertTrue(sink.drainAll("0xagent", "sess-C").isEmpty())
        assertEquals(listOf("meant for B"), sink.drainAll("0xagent", "sess-B").map { it.prompt })
    }

    @Test
    fun `a message queued before any session existed is flushed by the first one`() {
        // Queued while offline, before a CONNECT named a session: it belongs
        // to whichever session the connection comes back on.
        sink.enqueue("0xagent", "queued offline", sessionId = null)

        assertEquals(listOf("queued offline"), sink.drainAll("0xagent", "sess-A").map { it.prompt })
    }

    @Test
    fun `a conversation-tagged message drains into a fresh Host session`() {
        sink.enqueue("0xagent", "conversation-A", "queued after the old session dropped", null)

        assertEquals(
            listOf("queued after the old session dropped"),
            sink.drainAll("0xagent", "fresh-host-session", "conversation-A").map { it.prompt }
        )
    }
}
