package ai.openonion.oochat.network

import ai.openonion.oochat.data.protocol.FileAttachment

/** A message [AgentConnection] queued while the connection wasn't ready, as handed to [PendingMessageSink.drain]'s callback. */
data class QueuedMessage(val prompt: String, val images: List<String>?, val files: List<FileAttachment>? = null)

/**
 * Persistence seam for messages queued while the connection isn't ready.
 * [AgentConnection] depends on this plain interface rather than on Room
 * directly, so it stays constructible with no Android dependency in plain
 * JUnit tests; [ai.openonion.oochat.di.AppContainer] injects the
 * Room-backed implementation in production.
 */
interface PendingMessageSink {
    /**
     * Enqueues a message for [agentAddress], tagged with [sessionId] — the
     * server session, or local conversation fallback, it was written in.
     * Implementations bound their own size by both row count and total
     * attachment bytes; see
     * [AgentConnection.MAX_PENDING_MESSAGES] and
     * [AgentConnection.MAX_PENDING_ATTACHMENT_BYTES].
     *
     * @return false when there is no room: nothing is queued, and nothing
     *   already queued is dropped to make room. A queued message's bubble says
     *   it is waiting, so dropping one silently would make that untrue; the
     *   caller fails the new one instead, which offers Retry (W16AAPPLE-106).
     */
    fun enqueue(agentAddress: String, sessionId: String?, prompt: String, images: List<String>?, files: List<FileAttachment>? = null): Boolean

    /**
     * Removes the messages for [agentAddress] that belong on [sessionId] or
     * [conversationId], oldest first — its own, plus any queued before there
     * was a session to name — handing each to [send] as it is read. A message
     * queued for a different conversation stays put: flushing it here would
     * send it as this conversation.
     *
     * The callback returns false when the message was not accepted. The sink
     * keeps that message queued and stops, so a socket replacement cannot
     * lose it.
     */
    fun drain(
        agentAddress: String,
        sessionId: String?,
        conversationId: String?,
        send: (QueuedMessage) -> Boolean
    )

    /** Discards every message queued for [agentAddress] without sending them. */
    fun clear(agentAddress: String)

    /**
     * Discards, without sending them, the messages queued for [agentAddress]
     * on [sessionId] or [conversationId]. Clear chat uses it, so what another
     * conversation with the agent queued stays queued for that conversation.
     * One queued before there was a session to name is kept: nothing says
     * which conversation it was written in (W16AAPPLE-106).
     */
    fun clearConversation(agentAddress: String, sessionId: String?, conversationId: String?)

    /**
     * How many queued messages [drain] would send for the same arguments:
     * those on [sessionId] or [conversationId], plus any with no session
     * named, which go out in whichever conversation connects next. The chat
     * settles "Waiting to send..." bubbles by it: the outbox sends oldest
     * first and never drops one, so any beyond this count have gone. Counting
     * the unnamed ones errs towards waiting, never towards "sent" (W16AAPPLE-106).
     */
    fun countForConversation(agentAddress: String, sessionId: String?, conversationId: String?): Int
}

/** A message the outbox had no room for. The send fails, so its bubble offers Retry (W16AAPPLE-106). */
class OutboxFullException : IllegalStateException("Too many messages are waiting to send")

/**
 * Default sink when no persisted one is injected (plain unit tests
 * constructing [AgentConnection] directly). Matches the pre-outbox
 * behaviour: queued messages don't survive process death.
 */
class InMemoryPendingMessageSink : PendingMessageSink {
    private data class Entry(val agentAddress: String, val sessionId: String?, val message: QueuedMessage)

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private val clearGenerations = mutableMapOf<String, Long>()

    // Bumped by clearConversation, per agent and session tag, so a drain only
    // gives up putting back a message whose own conversation was cleared
    // meanwhile, not one Clear chat in another conversation never touched.
    private val conversationClears = mutableMapOf<Pair<String, String>, Long>()

    /** Caller holds [lock]. What a failed send's put-back is checked against. */
    private fun generationOf(agentAddress: String, rowSessionId: String?): Pair<Long, Long> =
        (clearGenerations[agentAddress] ?: 0L) to (rowSessionId?.let { conversationClears[agentAddress to it] } ?: 0L)

    override fun enqueue(agentAddress: String, sessionId: String?, prompt: String, images: List<String>?, files: List<FileAttachment>?): Boolean {
        val incoming = attachmentBytes(images, files)
        synchronized(lock) {
            // Bytes as well as rows: 50 rows says nothing about size when each
            // one can carry a base64 attachment. Refused rather than making
            // room, since what is already queued says it is waiting.
            if (
                entries.count { it.agentAddress == agentAddress } >= AgentConnection.MAX_PENDING_MESSAGES ||
                (incoming > 0 && queuedBytes(agentAddress) + incoming > AgentConnection.MAX_PENDING_ATTACHMENT_BYTES)
            ) {
                return false
            }
            entries.add(Entry(agentAddress, sessionId, QueuedMessage(prompt, images, files)))
            return true
        }
    }

    override fun drain(
        agentAddress: String,
        sessionId: String?,
        conversationId: String?,
        send: (QueuedMessage) -> Boolean
    ) {
        while (true) {
            val (next, drainGeneration) = synchronized(lock) {
                val entry = entries.firstOrNull {
                    it.agentAddress == agentAddress && (it.sessionId == null ||
                        it.sessionId == sessionId || it.sessionId == conversationId)
                } ?: return
                entries.remove(entry)
                entry to generationOf(agentAddress, entry.sessionId)
            }
            val accepted = runCatching { send(next.message) }.getOrDefault(false)
            if (!accepted) {
                synchronized(lock) {
                    if (generationOf(agentAddress, next.sessionId) == drainGeneration) entries.add(0, next)
                }
                return
            }
        }
    }

    private fun queuedBytes(agentAddress: String): Long =
        entries.filter { it.agentAddress == agentAddress }
            .sumOf { attachmentBytes(it.message.images, it.message.files) }

    private fun attachmentBytes(images: List<String>?, files: List<FileAttachment>?): Long =
        images.orEmpty().sumOf { it.length.toLong() } +
            files.orEmpty().sumOf { it.name.length.toLong() + it.data.length.toLong() }

    override fun clear(agentAddress: String) {
        synchronized(lock) {
            clearGenerations[agentAddress] = (clearGenerations[agentAddress] ?: 0L) + 1
            entries.removeAll { it.agentAddress == agentAddress }
        }
    }

    override fun clearConversation(agentAddress: String, sessionId: String?, conversationId: String?) {
        synchronized(lock) {
            // So a drain of this conversation racing the clear cannot put back
            // a message it had claimed. Only this conversation's: Clear chat
            // may run on one the connection is not on, and the drain in flight
            // is then another's, whose failed send must still go back.
            listOfNotNull(sessionId, conversationId).distinct().forEach { tag ->
                conversationClears[agentAddress to tag] = (conversationClears[agentAddress to tag] ?: 0L) + 1
            }
            entries.removeAll { it.agentAddress == agentAddress && it.belongsTo(sessionId, conversationId) }
        }
    }

    override fun countForConversation(agentAddress: String, sessionId: String?, conversationId: String?): Int =
        synchronized(lock) {
            entries.count { it.agentAddress == agentAddress && (it.sessionId == null || it.belongsTo(sessionId, conversationId)) }
        }

    /** Named on [sessionId] or [conversationId]; one with no session named belongs to neither. */
    private fun Entry.belongsTo(sessionId: String?, conversationId: String?): Boolean =
        this.sessionId != null && (this.sessionId == sessionId || this.sessionId == conversationId)
}
