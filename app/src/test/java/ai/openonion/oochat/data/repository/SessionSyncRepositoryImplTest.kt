package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.entity.AgentEntity
import ai.openonion.oochat.data.local.db.entity.ChatMessageEntity
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.data.protocol.RemoteSessionSummary
import ai.openonion.oochat.domain.usecase.RecentChatsSyncResult
import ai.openonion.oochat.domain.usecase.SyncRecentChatsUseCase
import ai.openonion.oochat.network.SessionSyncFetchResult
import ai.openonion.oochat.network.SessionSyncSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionSyncRepositoryImplTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: SessionSyncRepositoryImpl

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = SessionSyncRepositoryImpl(database, clock = { 9_999L })
        seedAgent("agent-1")
        seedAgent("agent-2")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedAgent(id: String) {
        database.agentDao().insertAgent(
            AgentEntity(
                id = id,
                address = "0x$id",
                name = id,
                serverUrl = "https://example.com",
                createdAt = 0L
            )
        )
    }

    private fun summary(
        id: String,
        revision: Long = 1,
        title: String = "Remote $id",
        preview: String? = "preview $id"
    ) = RemoteSessionSummary(
        sessionId = id,
        revision = revision,
        title = title,
        activity = "idle",
        createdAt = "2026-10-01T00:00:00Z",
        updatedAt = "2026-10-02T00:00:00Z",
        lastSequence = 7,
        preview = preview
    )

    private fun snapshot(
        sessions: List<RemoteSessionSummary> = emptyList(),
        removed: List<String> = emptyList(),
        cursor: String = "cursor-1",
        isFull: Boolean = false
    ) = SessionSyncSnapshot(sessions, removed, cursor, isFull)

    @Test
    fun `remote session is inserted and final cursor is scoped to identity and agent`() = runTest {
        repository.reconcile("0xidentity-a", "agent-1", snapshot(sessions = listOf(summary("remote-1"))))

        val stored = database.sessionDao().getSessionById("remote-1")
        assertEquals("agent-1", stored?.agentId)
        assertEquals("Remote remote-1", stored?.title)
        assertEquals("preview remote-1", stored?.lastMessagePreview)
        assertEquals(1L, stored?.remoteRevision)
        assertEquals(0, stored?.messageCount)
        assertEquals("cursor-1", repository.cursorFor("0xidentity-a", "agent-1"))
        assertNull(repository.cursorFor("0xidentity-b", "agent-1"))
        assertNull(repository.cursorFor("0xidentity-a", "agent-2"))
    }

    @Test
    fun `memory off marker persists without losing a cursor and clears after commit`() = runTest {
        repository.reconcile("0xidentity", "agent-1", snapshot(cursor = "saved-cursor"))

        repository.markMemoryDisabled("0xidentity", "agent-1")

        assertTrue(repository.isMemoryDisabledSinceSync("0xidentity", "agent-1"))
        assertEquals("saved-cursor", repository.cursorFor("0xidentity", "agent-1"))

        repository.advanceCursor("0xidentity", "agent-1", "advanced-cursor")

        assertEquals("advanced-cursor", repository.cursorFor("0xidentity", "agent-1"))
        assertTrue(!repository.isMemoryDisabledSinceSync("0xidentity", "agent-1"))
    }

    @Test
    fun `memory off marker on a fresh install has no cursor and is cleared by full reconcile`() = runTest {
        repository.markMemoryDisabled("0xidentity", "agent-1")

        assertNull(repository.cursorFor("0xidentity", "agent-1"))
        assertTrue(repository.isMemoryDisabledSinceSync("0xidentity", "agent-1"))

        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("from-another-device")), isFull = true)
        )

        assertEquals("cursor-1", repository.cursorFor("0xidentity", "agent-1"))
        assertTrue(!repository.isMemoryDisabledSinceSync("0xidentity", "agent-1"))
        assertEquals("from-another-device", database.sessionDao().getSessionById("from-another-device")?.id)
    }

    @Test
    fun `completed sync becomes visible through the Recent Chat session flow`() = runTest {
        val snapshot = snapshot(
            sessions = listOf(summary("remote-visible", title = "From another device")),
            cursor = "cursor-visible"
        )
        val sync = SyncRecentChatsUseCase(
            repository = repository,
            identityAddress = { "0xidentity" },
            fetch = { agentAddress, directUrl, _, cursor ->
                assertEquals("0xagent-1", agentAddress)
                assertEquals("https://agent.example", directUrl)
                assertNull(cursor)
                SessionSyncFetchResult.Complete(snapshot)
            }
        )

        val result = sync.sync("agent-1", "0xagent-1", "https://agent.example", null)
        val visible = SessionRepositoryImpl(database.sessionDao())
            .getSessionsByAgent("agent-1")
            .first()

        assertSame(RecentChatsSyncResult.Synced, result)
        assertEquals(listOf("remote-visible"), visible.map { it.id })
        assertEquals("From another device", visible.single().title)
        assertEquals(1L, visible.single().remoteRevision)
        assertEquals("cursor-visible", repository.cursorFor("0xidentity", "agent-1"))
    }

    @Test
    fun `duplicate summaries create one row using their greatest revision`() = runTest {
        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(
                sessions = listOf(
                    summary("same", revision = 1, title = "Old"),
                    summary("same", revision = 3, title = "Newest"),
                    summary("same", revision = 2, title = "Middle")
                )
            )
        )

        val sessions = database.sessionDao().getSessionsByAgent("agent-1").first()
        assertEquals(listOf("same"), sessions.map { it.id })
        assertEquals("Newest", sessions.single().title)
        assertEquals(3L, sessions.single().remoteRevision)
    }

    @Test
    fun `newer metadata updates in place and preserves local messages and count`() = runTest {
        database.sessionDao().insertSession(
            ChatSessionEntity(
                id = "existing",
                agentId = "agent-1",
                title = "Local title",
                createdAt = 100L,
                updatedAt = 200L,
                messageCount = 4,
                lastMessagePreview = "local preview",
                remoteRevision = 1
            )
        )
        database.messageDao().insertMessage(
            ChatMessageEntity(
                id = "message-1",
                sessionId = "existing",
                role = "USER",
                content = "keep me",
                timestamp = 300L
            )
        )

        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("existing", revision = 2, title = "Remote title")))
        )

        val stored = database.sessionDao().getSessionById("existing")
        assertEquals("Local title", stored?.title)
        assertEquals(2L, stored?.remoteRevision)
        assertEquals(4, stored?.messageCount)
        assertEquals("keep me", database.messageDao().getMessagesListBySession("existing").single().content)
    }

    @Test
    fun `equal or older revision cannot overwrite newer metadata`() = runTest {
        database.sessionDao().insertSession(
            ChatSessionEntity(
                id = "existing",
                agentId = "agent-1",
                title = "Newest",
                createdAt = 100L,
                updatedAt = 500L,
                remoteRevision = 5
            )
        )

        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("existing", revision = 4, title = "Stale")))
        )

        val stored = database.sessionDao().getSessionById("existing")
        assertEquals("Newest", stored?.title)
        assertEquals(5L, stored?.remoteRevision)
    }

    @Test
    fun `remote removal keeps a chat with local messages visible`() = runTest {
        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("remove-me")))
        )
        database.messageDao().insertMessage(
            ChatMessageEntity("kept-message", "remove-me", "USER", "keep me", 1L)
        )
        database.sessionDao().insertSession(
            ChatSessionEntity("other-agent", "agent-2", "two", 0L, 0L)
        )

        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(removed = listOf("remove-me", "other-agent"))
        )

        assertTrue(database.sessionDao().getSessionById("remove-me")?.remoteRemoved == false)
        assertEquals(
            listOf("remove-me"),
            database.sessionDao().getSessionsByAgent("agent-1").first().map { it.id }
        )
        assertEquals("keep me", database.messageDao().getMessagesListBySession("remove-me").single().content)
        assertEquals("agent-2", database.sessionDao().getSessionById("other-agent")?.agentId)
    }

    @Test
    fun `remote removal hides a chat with no local messages`() = runTest {
        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("remote-only")))
        )

        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(removed = listOf("remote-only"))
        )

        assertTrue(database.sessionDao().getSessionById("remote-only")?.remoteRemoved == true)
        assertEquals(emptyList<Any>(), database.sessionDao().getSessionsByAgent("agent-1").first())
    }

    @Test
    fun `local rename and preview survive a newer Host revision`() = runTest {
        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("renamed", preview = "local preview")))
        )
        database.sessionDao().updateTitle("renamed", "My title", 5L)

        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("renamed", revision = 2, title = "Host title", preview = null)))
        )

        val stored = database.sessionDao().getSessionById("renamed")
        assertEquals("My title", stored?.title)
        assertEquals("local preview", stored?.lastMessagePreview)
        assertEquals(2L, stored?.remoteRevision)
    }

    @Test
    fun `switching identity hides only the other identity's synced chats`() = runTest {
        repository.reconcile(
            "0xidentity-a",
            "agent-1",
            snapshot(sessions = listOf(summary("identity-a-chat")))
        )

        repository.activateIdentity("0xidentity-b")
        assertEquals(emptyList<Any>(), database.sessionDao().getSessionsByAgent("agent-1").first())
        assertEquals(emptyList<Any>(), database.sessionDao().getAllSessions().first())

        repository.activateIdentity("0xidentity-a")
        assertEquals(
            listOf("identity-a-chat"),
            database.sessionDao().getSessionsByAgent("agent-1").first().map { it.id }
        )
    }

    @Test
    fun `full sync hides remote chats it does not report and preserves their rows`() = runTest {
        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("present"), summary("missing")))
        )

        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("present", revision = 2)), isFull = true)
        )

        assertEquals(listOf("present"), database.sessionDao().getSessionsByAgent("agent-1").first().map { it.id })
        assertTrue(database.sessionDao().getSessionById("missing")?.remoteRemoved == true)
    }

    @Test
    fun `full sync keeps an omitted chat that has local messages visible`() = runTest {
        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("local-history")))
        )
        database.messageDao().insertMessage(
            ChatMessageEntity("kept-message", "local-history", "USER", "keep me", 1L)
        )

        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(isFull = true)
        )

        assertTrue(database.sessionDao().getSessionById("local-history")?.remoteRemoved == false)
        assertEquals(
            listOf("local-history"),
            database.sessionDao().getSessionsByAgent("agent-1").first().map { it.id }
        )
    }

    @Test
    fun `locally deleted chat is not restored by a full sync`() = runTest {
        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("deleted")))
        )
        val sessions = SessionRepositoryImpl(
            database.sessionDao(),
            database.sessionSyncDao(),
            identityAddress = { "0xidentity" },
            clock = { 10L }
        )
        sessions.deleteSession("deleted")

        repository.reconcile(
            "0xidentity",
            "agent-1",
            snapshot(sessions = listOf(summary("deleted", revision = 2)), isFull = true)
        )

        assertNull(database.sessionDao().getSessionById("deleted"))
        assertEquals(emptyList<Any>(), sessions.getSessionsByAgent("agent-1").first())
    }

    @Test
    fun `failure rolls back inserted sessions and leaves the old cursor`() = runTest {
        repository.reconcile("0xidentity", "agent-1", snapshot(cursor = "old-cursor"))
        database.sessionDao().insertSession(
            ChatSessionEntity("collision", "agent-2", "other", 0L, 0L)
        )

        val failure = runCatching {
            repository.reconcile(
                "0xidentity",
                "agent-1",
                snapshot(
                    sessions = listOf(summary("inserted-first"), summary("collision")),
                    cursor = "new-cursor"
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNull(database.sessionDao().getSessionById("inserted-first"))
        assertEquals("old-cursor", repository.cursorFor("0xidentity", "agent-1"))
    }

    @Test
    fun `snapshot cannot update and remove the same session`() = runTest {
        val failure = runCatching {
            repository.reconcile(
                "0xidentity",
                "agent-1",
                snapshot(sessions = listOf(summary("ambiguous")), removed = listOf("ambiguous"))
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(repository.cursorFor("0xidentity", "agent-1"))
    }
}
