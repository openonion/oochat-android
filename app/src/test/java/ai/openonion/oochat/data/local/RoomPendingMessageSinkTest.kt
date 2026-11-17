package ai.openonion.oochat.data.local

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.entity.PendingMessageEntity
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * That the persisted outbox stays bounded in bytes, not just in rows, and
 * that a queued attachment never ends up inside the row.
 *
 * A 10MB file (FileAttachmentStoreImpl.MAX_FILE_BYTES) is ~13.3MB of base64.
 * Stored in the row it is far past SQLite's 2MB CursorWindow, where the read
 * throws and takes every other queued message with it — and reaching that is
 * ordinary, not exotic: the first attachment-bearing message in a new
 * conversation is queued by design, because switchConversation drops the
 * ready flag before the INPUT is sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomPendingMessageSinkTest {

    private lateinit var db: AppDatabase
    private lateinit var spillDir: File
    private lateinit var sink: RoomPendingMessageSink

    private val agent = "0xagent"
    private val session = "sess-1"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        spillDir = File(context.cacheDir, "pending-attachments-test-${System.nanoTime()}")
        sink = RoomPendingMessageSink(db.pendingMessageDao(), spillDir)
    }

    @After
    fun tearDown() {
        db.close()
        spillDir.deleteRecursively()
    }

    /** [PendingMessageSink.drain] as a list, for assertions about order and contents. */
    private fun PendingMessageSink.drainAll(
        sessionId: String? = session,
        conversationId: String? = null
    ): List<QueuedMessage> = buildList {
        drain(agent, sessionId, conversationId) { add(it) }
    }

    private fun file(name: String, bytes: Int) =
        FileAttachment(name = name, data = "data:application/pdf;base64," + "A".repeat(bytes))

    @Test
    fun `a queued message round-trips its images and files`() {
        val files = listOf(file("report.pdf", 64))
        sink.enqueue(agent, session, "see attached", listOf("data:image/png;base64,AAAA"), files)

        val drained = sink.drainAll()

        assertEquals(1, drained.size)
        assertEquals("see attached", drained.single().prompt)
        assertEquals(listOf("data:image/png;base64,AAAA"), drained.single().images)
        assertEquals(files, drained.single().files)
    }

    @Test
    fun `the attachment never lands in the row, so a big one cannot break the read`() {
        // The row is what a cursor has to materialize. Keeping a multi-megabyte
        // payload out of it is the whole reason for the spill file.
        sink.enqueue(agent, session, "big one", null, listOf(file("big.pdf", 4 * 1024 * 1024)))

        val row = db.pendingMessageDao().oldestClaimableForAgent(agent, session, null, Long.MAX_VALUE)!!
        assertNull("images stayed in the row", row.imagesJson)
        assertNull("files stayed in the row", row.filesJson)
        assertEquals(listOf("big one"), sink.drainAll().map { it.prompt })
    }

    @Test
    fun `a message past the attachment byte budget is refused, and what is queued stays`() {
        // Well under MAX_PENDING_MESSAGES rows, well over the byte budget:
        // capping rows alone let this grow to hundreds of megabytes. Refused
        // rather than evicting, so a queued message is never dropped behind
        // its bubble's back (W16AAPPLE-106).
        val eachBytes = (AgentConnection.MAX_PENDING_ATTACHMENT_BYTES / 2).toInt() + 1
        assertTrue(sink.enqueue(agent, session, "first", null, listOf(file("a.pdf", eachBytes))))

        assertFalse(sink.enqueue(agent, session, "second", null, listOf(file("b.pdf", eachBytes))))

        assertEquals(listOf("first"), sink.drainAll().map { it.prompt })
    }

    @Test
    fun `a refused message leaves nothing behind, and a full outbox refuses by row count too`() {
        val eachBytes = (AgentConnection.MAX_PENDING_ATTACHMENT_BYTES / 2).toInt() + 1
        sink.enqueue(agent, session, "first", null, listOf(file("a.pdf", eachBytes)))
        sink.enqueue(agent, session, "second", null, listOf(file("b.pdf", eachBytes)))
        assertEquals("only the queued message's spill: ${spillDir.listFiles()?.map { it.name }}", 1, spillDir.listFiles().orEmpty().size)

        repeat(AgentConnection.MAX_PENDING_MESSAGES - 1) { i -> assertTrue(sink.enqueue(agent, session, "text-$i", null, null)) }
        assertFalse("the outbox is full", sink.enqueue(agent, session, "one too many", null, null))
        assertEquals(AgentConnection.MAX_PENDING_MESSAGES, db.pendingMessageDao().countForAgent(agent))
    }

    @Test
    fun `a rejected send is put back even when the outbox filled up meanwhile`() {
        // The message was already queued; refusing it now would drop it.
        sink.enqueue(agent, session, "already queued", null, null)
        var filled = false

        sink.drain(agent, session, null) {
            repeat(AgentConnection.MAX_PENDING_MESSAGES) { i -> sink.enqueue(agent, session, "later-$i", null, null) }
            filled = true
            false
        }

        assertTrue(filled)
        assertEquals("already queued", sink.drainAll().first().prompt)
    }

    @Test
    fun `draining deletes the spill as it goes, leaving nothing behind`() {
        sink.enqueue(agent, session, "one", null, listOf(file("a.pdf", 1024)))
        sink.enqueue(agent, session, "two", null, listOf(file("b.pdf", 1024)))

        assertEquals(listOf("one", "two"), sink.drainAll().map { it.prompt })

        assertTrue(
            "spilled attachments outlived the drain: ${spillDir.listFiles()?.map { it.name }}",
            spillDir.listFiles().orEmpty().isEmpty()
        )
        assertEquals(0, db.pendingMessageDao().countForAgent(agent))
    }

    @Test
    fun `only one message's attachments are resident at a time`() {
        // The point of the callback: the old read-and-clear decoded every
        // queued attachment before sending any of them.
        repeat(3) { i -> sink.enqueue(agent, session, "m-$i", null, listOf(file("f-$i.pdf", 1024))) }

        val remainingWhileDraining = mutableListOf<Int>()
        sink.drain(agent, session, null) {
            remainingWhileDraining += db.pendingMessageDao().countForAgent(agent)
            true
        }

        assertEquals(
            "the queue must shrink one message at a time as each is handed over",
            listOf(2, 1, 0),
            remainingWhileDraining
        )
    }

    @Test
    fun `a rejected send keeps the row queued`() {
        sink.enqueue(agent, session, "retry me", null, null)

        sink.drain(agent, session, null) { false }

        assertEquals(listOf("retry me"), sink.drainAll().map { it.prompt })
    }

    @Test
    fun `a rejected first send stays before later messages`() {
        sink.enqueue(agent, session, "first", null, null)
        sink.enqueue(agent, session, "second", null, null)
        sink.enqueue(agent, session, "third", null, null)

        val attempted = mutableListOf<String>()
        sink.drain(agent, session, null) {
            attempted += it.prompt
            false
        }

        assertEquals(listOf("first"), attempted)
        assertEquals(listOf("first", "second", "third"), sink.drainAll().map { it.prompt })
    }

    @Test
    fun `a message queued in another session stays put`() {
        sink.enqueue(agent, "sess-other", "meant for the other conversation", null, null)
        sink.enqueue(agent, null, "queued before any session existed", null, null)

        assertEquals(
            listOf("queued before any session existed"),
            sink.drainAll(session).map { it.prompt }
        )
        assertEquals(1, db.pendingMessageDao().countForAgent(agent))
    }

    @Test
    fun `a message tagged with the local conversation drains into a fresh Host session`() {
        sink.enqueue(agent, "conversation-1", "typed with Memory off", null, null)

        assertEquals(
            listOf("typed with Memory off"),
            sink.drainAll(sessionId = "fresh-host-session", conversationId = "conversation-1")
                .map { it.prompt }
        )
    }

    @Test
    fun `a row written by an older build still carries its attachments in the columns`() {
        // Rows already in the outbox at upgrade time have no spill file. They
        // have to keep working, or an upgrade silently drops queued sends.
        db.pendingMessageDao().insert(
            PendingMessageEntity(
                agentAddress = agent,
                sessionId = session,
                prompt = "queued before the upgrade",
                imagesJson = """["data:image/png;base64,AAAA"]""",
                filesJson = """[{"name":"legacy.pdf","data":"data:application/pdf;base64,BBBB"}]""",
                createdAt = 0L
            )
        )

        val drained = sink.drainAll().single()

        assertEquals(listOf("data:image/png;base64,AAAA"), drained.images)
        assertEquals("legacy.pdf", drained.files?.single()?.name)
    }

    @Test
    fun `clear discards the rows and their spilled attachments`() {
        sink.enqueue(agent, session, "one", null, listOf(file("a.pdf", 1024)))

        sink.clear(agent)

        assertEquals(0, db.pendingMessageDao().countForAgent(agent))
        assertTrue(spillDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `clearing a conversation discards only its rows and their spills`() {
        // Clear chat in one conversation must not drop what another
        // conversation with the same agent queued offline, nor a message no
        // conversation is named on (W16AAPPLE-106).
        sink.enqueue(agent, session, "for this session", null, listOf(file("a.pdf", 1024)))
        sink.enqueue(agent, "conversation-A", "for this conversation", null, null)
        sink.enqueue(agent, null, "queued before any session existed", null, null)
        sink.enqueue(agent, "conversation-B", "for another conversation", null, listOf(file("b.pdf", 1024)))

        sink.clearConversation(agent, session, "conversation-A")

        assertEquals(2, db.pendingMessageDao().countForAgent(agent))
        assertEquals(1, spillDir.listFiles().orEmpty().size)
        val left = sink.drainAll(sessionId = "conversation-B")
        assertEquals(listOf("queued before any session existed", "for another conversation"), left.map { it.prompt })
        assertEquals("b.pdf", left.last().files?.single()?.name)
    }

    @Test
    fun `the count for a conversation is what would go out in it`() {
        sink.enqueue(agent, session, "on the session", null, null)
        sink.enqueue(agent, "conversation-A", "on the conversation", null, null)
        sink.enqueue(agent, "conversation-B", "elsewhere", null, null)
        sink.enqueue(agent, null, "unnamed", null, null)

        assertEquals(3, sink.countForConversation(agent, session, "conversation-A"))
        assertEquals(1, sink.countForConversation(agent, null, "conversation-C"))
    }

    @Test
    fun `clearing one conversation does not stop another's failed send going back`() {
        sink.enqueue(agent, "conversation-B", "for B", null, null)
        sink.enqueue(agent, "conversation-A", "for A", null, null)

        sink.drain(agent, null, "conversation-B") {
            sink.clearConversation(agent, null, "conversation-A")
            false
        }

        assertEquals(listOf("for B"), sink.drainAll(sessionId = "conversation-B").map { it.prompt })
        assertEquals(0, sink.countForConversation(agent, null, "conversation-A"))
    }

    @Test
    fun `clearing the conversation being flushed does drop its failed send`() {
        sink.enqueue(agent, "conversation-B", "for B", null, null)

        sink.drain(agent, null, "conversation-B") {
            sink.clearConversation(agent, null, "conversation-B")
            false
        }

        assertEquals(0, db.pendingMessageDao().countForAgent(agent))
    }

    @Test
    fun `a spill whose row is gone is swept rather than left on disk forever`() {
        // Process death between claiming a row and reading its file would
        // otherwise strand the bytes, still counting against the byte bound.
        spillDir.mkdirs()
        File(spillDir, "999999.json").writeText("""{"images":["orphan"]}""")
        sink.enqueue(agent, session, "one", null, null)

        sink.drainAll()

        assertTrue(
            "an orphaned spill survived the drain: ${spillDir.listFiles()?.map { it.name }}",
            spillDir.listFiles().orEmpty().isEmpty()
        )
    }
}
