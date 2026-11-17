package ai.openonion.oochat.data.local

import ai.openonion.oochat.data.local.db.dao.PendingMessageDao
import ai.openonion.oochat.data.local.db.entity.PendingMessageEntity
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Everything one queued message attached, spilled to [RoomPendingMessageSink]'s own directory. */
@Serializable
private data class SpilledAttachments(
    val images: List<String>? = null,
    val files: List<FileAttachment>? = null
)

/**
 * Room-backed [PendingMessageSink]: a message queued while disconnected
 * survives process death instead of dying with [AgentConnection]'s memory.
 *
 * Attachments do not live in the row. They are base64, and a 10MB file
 * (FileAttachmentStoreImpl.MAX_FILE_BYTES) inflates to ~13.3MB of it — well
 * past SQLite's 2MB CursorWindow, where reading the row throws and takes the
 * whole outbox with it, not just that message. So the row keeps the prompt
 * and the routing, and the payload goes to [spillDir] under the row's own id.
 * That also makes the queue boundable by bytes: the spill files ARE the size.
 *
 * Single instance per process (AppContainer builds one), which is what lets
 * [lock] stand in for the transaction the old count-then-insert used — the
 * bound now spans the database and the filesystem, so no single statement
 * could hold it anyway.
 */
class RoomPendingMessageSink(
    private val dao: PendingMessageDao,
    private val spillDir: File
) : PendingMessageSink {
    // Must stay compatible with how images are encoded elsewhere (see
    // MessageMapper / PersistenceTransaction): a JSON array of strings.
    // files reuses FileAttachment's own @Serializable shape (name + base64
    // data), same as the wire payload — no separate DTO needed.
    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()
    private val clearGenerations = mutableMapOf<String, Long>()

    // Per agent and session tag; see InMemoryPendingMessageSink's own.
    private val conversationClears = mutableMapOf<Pair<String, String>, Long>()

    /** Caller holds [lock]. What a failed send's put-back is checked against. */
    private fun generationOf(agentAddress: String, rowSessionId: String?): Pair<Long, Long> =
        (clearGenerations[agentAddress] ?: 0L) to (rowSessionId?.let { conversationClears[agentAddress to it] } ?: 0L)

    override fun enqueue(agentAddress: String, sessionId: String?, prompt: String, images: List<String>?, files: List<FileAttachment>?): Boolean {
        val payload = attachmentPayload(images, files)
        synchronized(lock) {
            if (!hasRoomFor(agentAddress, payload?.length?.toLong() ?: 0L)) {
                FileLogger.w(LogTags.PENDING_OUTBOX, "Outbox full for ${agentAddress.take(16)}; refusing the new message")
                return false
            }
            val id = dao.insert(
                PendingMessageEntity(
                    agentAddress = agentAddress,
                    sessionId = sessionId,
                    prompt = prompt,
                    imagesJson = null,
                    filesJson = null,
                    createdAt = System.currentTimeMillis()
                )
            )
            writeSpill(id, payload)
            return true
        }
    }

    override fun drain(
        agentAddress: String,
        sessionId: String?,
        conversationId: String?,
        send: (QueuedMessage) -> Boolean
    ) {
        // Snapshot the watermark first: a send racing this flush (an INPUT
        // written while CONNECTED is being processed) enqueues behind it and
        // is left for the next pass rather than being reordered into this one.
        val maxId = synchronized(lock) { dao.newestIdForAgent(agentAddress) } ?: return
        while (true) {
            val claimed = synchronized(lock) {
                dao.claimNextForAgent(agentAddress, sessionId, conversationId, maxId)
                    ?.let { it to generationOf(agentAddress, it.sessionId) }
            } ?: break
            val row = claimed.first
            val drainGeneration = claimed.second
            val spilled = readSpill(row.id)
            val message = QueuedMessage(
                prompt = row.prompt,
                // Columns are the fallback, not the source: rows written
                // by a build before the spill still carry theirs inline.
                images = spilled?.images ?: row.imagesJson?.let { json.decodeFromString<List<String>>(it) },
                files = spilled?.files ?: row.filesJson?.let { json.decodeFromString<List<FileAttachment>>(it) }
            )
            val accepted = runCatching { send(message) }.getOrDefault(false)
            if (!accepted) {
                synchronized(lock) {
                    if (generationOf(agentAddress, row.sessionId) == drainGeneration) {
                        // Restore the claimed row's id. Calling enqueue() would
                        // append the failed message after everything behind it.
                        restore(row, message)
                    }
                }
                break
            }
        }
        sweepOrphanedSpills()
    }

    override fun clear(agentAddress: String) {
        synchronized(lock) {
            clearGenerations[agentAddress] = (clearGenerations[agentAddress] ?: 0L) + 1
            dao.idsForAgent(agentAddress).forEach { spillFile(it).delete() }
            dao.deleteAllForAgent(agentAddress)
        }
    }

    override fun countForConversation(agentAddress: String, sessionId: String?, conversationId: String?): Int =
        synchronized(lock) { dao.countForConversation(agentAddress, sessionId, conversationId) }

    override fun clearConversation(agentAddress: String, sessionId: String?, conversationId: String?) {
        synchronized(lock) {
            // This conversation's only; see InMemoryPendingMessageSink.clearConversation.
            listOfNotNull(sessionId, conversationId).distinct().forEach { tag ->
                conversationClears[agentAddress to tag] = (conversationClears[agentAddress to tag] ?: 0L) + 1
            }
            val ids = dao.idsForConversation(agentAddress, sessionId, conversationId)
            if (ids.isEmpty()) return
            ids.forEach { spillFile(it).delete() }
            dao.deleteByIds(ids)
        }
    }

    /**
     * Caller holds [lock]. Whether a new message of [incomingBytes] fits under
     * both caps. Nothing is evicted to make room: a queued message says it is
     * waiting, so the new one is refused instead (W16AAPPLE-106).
     */
    private fun hasRoomFor(agentAddress: String, incomingBytes: Long): Boolean =
        dao.countForAgent(agentAddress) < AgentConnection.MAX_PENDING_MESSAGES &&
            (incomingBytes == 0L || spilledBytes(agentAddress) + incomingBytes <= AgentConnection.MAX_PENDING_ATTACHMENT_BYTES)

    private fun spilledBytes(agentAddress: String): Long =
        dao.idsForAgent(agentAddress).sumOf { spillFile(it).length() }

    private fun attachmentPayload(images: List<String>?, files: List<FileAttachment>?): String? =
        if (images.isNullOrEmpty() && files.isNullOrEmpty()) null else json.encodeToString(SpilledAttachments(images, files))

    /** Reinsert a rejected message under its original id, ahead of later rows. */
    private fun restore(row: PendingMessageEntity, message: QueuedMessage) {
        val payload = attachmentPayload(message.images, message.files)
        // A newer enqueue may have filled the slot while send() was outside
        // the lock. This one was queued first, so it goes back regardless:
        // over the bound by one beats dropping it (W16AAPPLE-106).
        dao.insert(row.copy(imagesJson = null, filesJson = null))
        writeSpill(row.id, payload)
    }

    private fun writeSpill(id: Long, payload: String?) {
        if (payload == null) return
        runCatching {
            spillDir.mkdirs()
            spillFile(id).writeText(payload)
        }.onFailure { e ->
            // The row stays: text can still send if the spill fails.
            FileLogger.e(
                LogTags.PENDING_OUTBOX,
                "Could not spill attachments for queued message $id — it will send as text only: ${e.message}"
            )
        }
    }

    private fun spillFile(id: Long) = File(spillDir, "$id.json")

    /** Reads and deletes the spill for [id]; the row it belonged to is already gone. */
    private fun readSpill(id: Long): SpilledAttachments? {
        val file = spillFile(id)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<SpilledAttachments>(file.readText()) }
            .onFailure { FileLogger.e(LogTags.PENDING_OUTBOX, "Unreadable spilled attachments for $id: ${it.message}") }
            .also { file.delete() }
            .getOrNull()
    }

    /**
     * Spills whose row is gone — a process death between claiming a row and
     * reading its file would otherwise leave the bytes on disk forever, and
     * they still count against the byte bound of whichever ids get reused.
     */
    private fun sweepOrphanedSpills() {
        val live = synchronized(lock) { dao.allIds() }.toSet()
        spillDir.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".json").toLongOrNull() ?: return@forEach
            if (id !in live) file.delete()
        }
    }
}
