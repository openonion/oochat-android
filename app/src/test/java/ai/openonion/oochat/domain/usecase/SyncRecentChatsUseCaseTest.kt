package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.data.protocol.RemoteSessionSummary
import ai.openonion.oochat.data.protocol.SessionSyncError
import ai.openonion.oochat.data.repository.SessionSyncRepository
import ai.openonion.oochat.network.SessionSyncFetchResult
import ai.openonion.oochat.network.SessionSyncSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRecentChatsUseCaseTest {
    private class FakeRepository : SessionSyncRepository {
        var cursor: String? = "cursor-1"
        var memoryDisabledSinceSync = false
        val activatedIdentities = mutableListOf<String>()
        val reconciled = mutableListOf<Triple<String, String, SessionSyncSnapshot>>()
        val advanced = mutableListOf<Triple<String, String, String>>()

        override suspend fun activateIdentity(identityAddress: String) {
            activatedIdentities += identityAddress
        }

        override suspend fun cursorFor(identityAddress: String, agentId: String): String? = cursor

        override suspend fun markMemoryDisabled(identityAddress: String, agentId: String) {
            memoryDisabledSinceSync = true
        }

        override suspend fun isMemoryDisabledSinceSync(identityAddress: String, agentId: String): Boolean =
            memoryDisabledSinceSync

        override suspend fun reconcile(
            identityAddress: String,
            agentId: String,
            snapshot: SessionSyncSnapshot
        ) {
            reconciled += Triple(identityAddress, agentId, snapshot)
            cursor = snapshot.cursor
            memoryDisabledSinceSync = false
        }

        override suspend fun advanceCursor(identityAddress: String, agentId: String, cursor: String) {
            advanced += Triple(identityAddress, agentId, cursor)
            this.cursor = cursor
            memoryDisabledSinceSync = false
        }
    }

    private val snapshot = SessionSyncSnapshot(
        sessions = listOf(
            RemoteSessionSummary(
                sessionId = "session-1",
                revision = 1,
                title = "Synced chat",
                activity = "idle",
                createdAt = "2026-10-31T00:00:00Z",
                updatedAt = "2026-10-31T00:00:00Z",
                lastSequence = 1
            )
        ),
        removedSessionIds = emptyList(),
        cursor = "cursor-2"
    )

    @Test
    fun `completed fetch is reconciled for the current identity and agent`() = runTest {
        val repository = FakeRepository()
        val cursors = mutableListOf<String?>()
        val useCase = useCase(repository) { _, _, _, cursor ->
            cursors += cursor
            SessionSyncFetchResult.Complete(snapshot)
        }

        val result = useCase.sync("agent-1", "0xagent", null, null)

        assertSame(RecentChatsSyncResult.Synced, result)
        assertEquals(listOf("0xidentity"), repository.activatedIdentities)
        assertEquals(listOf("cursor-1"), cursors)
        assertEquals(Triple("0xidentity", "agent-1", snapshot), repository.reconciled.single())
    }

    @Test
    fun `an agent on a self-hosted relay is indexed through that relay`() = runTest {
        val routes = mutableListOf<Pair<String?, String?>>()
        val useCase = useCase(FakeRepository()) { _, directUrl, relayUrl, _ ->
            routes += directUrl to relayUrl
            SessionSyncFetchResult.Complete(snapshot)
        }

        useCase.sync("agent-1", "0xagent", null, "https://relay.example.com")

        assertEquals(listOf(null to "https://relay.example.com"), routes)
    }

    @Test
    fun `preparing the same identity repeatedly only filters Room once`() = runTest {
        val repository = FakeRepository()
        val useCase = useCase(repository) { _, _, _, _ -> SessionSyncFetchResult.Complete(snapshot) }

        useCase.activateIdentity()
        useCase.activateIdentity()

        assertEquals(listOf("0xidentity"), repository.activatedIdentities)
    }

    @Test
    fun `memory disabled skips host sync without changing recent chats`() = runTest {
        val repository = FakeRepository()
        var fetches = 0
        val useCase = useCase(repository, syncEnabled = { false }) { _, _, _, _ ->
            fetches++
            SessionSyncFetchResult.Complete(snapshot)
        }

        val result = useCase.sync("agent-1", "0xagent", null, null)

        assertSame(RecentChatsSyncResult.MemoryDisabled, result)
        assertEquals(listOf("0xidentity"), repository.activatedIdentities)
        assertEquals(0, fetches)
        assertEquals(emptyList<Any>(), repository.reconciled)
        assertEquals("cursor-1", repository.cursor)
    }

    @Test
    fun `first sync after memory is reenabled advances past sessions created while it was off`() = runTest {
        val repository = FakeRepository()
        var memoryEnabled = false
        val cursors = mutableListOf<String?>()
        val useCase = useCase(repository, syncEnabled = { memoryEnabled }) { _, _, _, cursor ->
            cursors += cursor
            SessionSyncFetchResult.Complete(snapshot)
        }

        assertSame(RecentChatsSyncResult.MemoryDisabled, useCase.sync("agent-1", "0xagent", null, null))

        memoryEnabled = true
        assertSame(RecentChatsSyncResult.Synced, useCase.sync("agent-1", "0xagent", null, null))

        assertEquals(listOf("cursor-1"), cursors)
        assertEquals(listOf(Triple("0xidentity", "agent-1", "cursor-2")), repository.advanced)
        assertTrue(repository.reconciled.isEmpty())

        // The next incremental sync is normal again; only the first batch was
        // treated as the Memory-off backlog.
        useCase.sync("agent-1", "0xagent", null, null)
        assertEquals(1, repository.reconciled.size)
    }

    @Test
    fun `memory off marker survives a new use case instance`() = runTest {
        val repository = FakeRepository()
        var memoryEnabled = false
        val fetch: suspend (String, String?, String?, String?) -> SessionSyncFetchResult =
            { _, _, _, _ -> SessionSyncFetchResult.Complete(snapshot) }

        assertSame(
            RecentChatsSyncResult.MemoryDisabled,
            useCase(repository, syncEnabled = { memoryEnabled }, fetch = fetch)
                .sync("agent-1", "0xagent", null, null)
        )

        memoryEnabled = true
        assertSame(
            RecentChatsSyncResult.Synced,
            useCase(repository, syncEnabled = { memoryEnabled }, fetch = fetch)
                .sync("agent-1", "0xagent", null, null)
        )

        assertEquals(1, repository.advanced.size)
        assertTrue(repository.reconciled.isEmpty())
    }

    @Test
    fun `fresh install reconciles the first full snapshot after memory was off`() = runTest {
        val repository = FakeRepository().also { it.cursor = null }
        var memoryEnabled = false
        val fullSnapshot = snapshot.copy(isFullSnapshot = true)
        val cursors = mutableListOf<String?>()
        val useCase = useCase(repository, syncEnabled = { memoryEnabled }) { _, _, _, cursor ->
            cursors += cursor
            SessionSyncFetchResult.Complete(fullSnapshot)
        }

        assertSame(RecentChatsSyncResult.MemoryDisabled, useCase.sync("agent-1", "0xagent", null, null))
        memoryEnabled = true
        assertSame(RecentChatsSyncResult.Synced, useCase.sync("agent-1", "0xagent", null, null))

        assertEquals(listOf(null), cursors)
        assertEquals(listOf(fullSnapshot), repository.reconciled.map { it.third })
        assertTrue(repository.advanced.isEmpty())
    }

    @Test
    fun `expired cursor retries once without a cursor`() = runTest {
        val repository = FakeRepository()
        val cursors = mutableListOf<String?>()
        val useCase = useCase(repository) { _, _, _, cursor ->
            cursors += cursor
            if (cursors.size == 1) {
                SessionSyncFetchResult.CursorExpired(
                    SessionSyncError(code = "cursor_expired", message = "expired")
                )
            } else {
                SessionSyncFetchResult.Complete(snapshot)
            }
        }

        assertSame(RecentChatsSyncResult.Synced, useCase.sync("agent-1", "0xagent", null, null))
        assertEquals(listOf("cursor-1", null), cursors)
        assertEquals(1, repository.reconciled.size)
    }

    @Test
    fun `unsupported host keeps local history unchanged`() = runTest {
        val repository = FakeRepository()
        val useCase = useCase(repository) { _, _, _, _ -> SessionSyncFetchResult.Unsupported() }

        assertSame(RecentChatsSyncResult.Unsupported, useCase.sync("agent-1", "0xagent", null, null))
        assertEquals(emptyList<Any>(), repository.reconciled)
        assertEquals("cursor-1", repository.cursor)
    }

    @Test
    fun `retryable failed fetch retries once and keeps local history unchanged`() = runTest {
        val repository = FakeRepository()
        var calls = 0
        val useCase = useCase(repository) { _, _, _, _ ->
            calls++
            SessionSyncFetchResult.Failed("offline", retryable = true)
        }

        assertEquals(
            RecentChatsSyncResult.Failed("offline", retryable = true),
            useCase.sync("agent-1", "0xagent", null, null)
        )
        assertEquals(emptyList<Any>(), repository.reconciled)
        assertEquals("cursor-1", repository.cursor)
        assertEquals(2, calls)
    }

    @Test
    fun `cursor recovery is attempted only once`() = runTest {
        val repository = FakeRepository()
        var calls = 0
        val useCase = useCase(repository) { _, _, _, _ ->
            calls++
            SessionSyncFetchResult.CursorExpired(
                SessionSyncError(code = "cursor_expired", message = "still expired")
            )
        }

        assertEquals(
            RecentChatsSyncResult.Failed("still expired"),
            useCase.sync("agent-1", "0xagent", null, null)
        )
        assertEquals(2, calls)
        assertEquals(emptyList<Any>(), repository.reconciled)
    }

    private fun useCase(
        repository: FakeRepository,
        syncEnabled: suspend () -> Boolean = { true },
        fetch: suspend (String, String?, String?, String?) -> SessionSyncFetchResult
    ) = SyncRecentChatsUseCase(
        repository = repository,
        identityAddress = { "0xidentity" },
        syncEnabled = syncEnabled,
        fetch = fetch
    )
}
