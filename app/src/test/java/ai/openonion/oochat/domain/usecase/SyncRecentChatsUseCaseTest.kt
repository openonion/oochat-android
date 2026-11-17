package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.data.protocol.RemoteSessionSummary
import ai.openonion.oochat.data.protocol.SessionSyncError
import ai.openonion.oochat.data.repository.SessionSyncRepository
import ai.openonion.oochat.network.SessionSyncFetchResult
import ai.openonion.oochat.network.SessionSyncSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SyncRecentChatsUseCaseTest {
    private class FakeRepository : SessionSyncRepository {
        var cursor: String? = "cursor-1"
        val activatedIdentities = mutableListOf<String>()
        val reconciled = mutableListOf<Triple<String, String, SessionSyncSnapshot>>()

        override suspend fun activateIdentity(identityAddress: String) {
            activatedIdentities += identityAddress
        }

        override suspend fun cursorFor(identityAddress: String, agentId: String): String? = cursor

        override suspend fun reconcile(
            identityAddress: String,
            agentId: String,
            snapshot: SessionSyncSnapshot
        ) {
            reconciled += Triple(identityAddress, agentId, snapshot)
            cursor = snapshot.cursor
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
        val useCase = useCase(repository) { _, _, cursor ->
            cursors += cursor
            SessionSyncFetchResult.Complete(snapshot)
        }

        val result = useCase.sync("agent-1", "0xagent", null)

        assertSame(RecentChatsSyncResult.Synced, result)
        assertEquals(listOf("0xidentity"), repository.activatedIdentities)
        assertEquals(listOf("cursor-1"), cursors)
        assertEquals(Triple("0xidentity", "agent-1", snapshot), repository.reconciled.single())
    }

    @Test
    fun `preparing the same identity repeatedly only filters Room once`() = runTest {
        val repository = FakeRepository()
        val useCase = useCase(repository) { _, _, _ -> SessionSyncFetchResult.Complete(snapshot) }

        useCase.activateIdentity()
        useCase.activateIdentity()

        assertEquals(listOf("0xidentity"), repository.activatedIdentities)
    }

    @Test
    fun `expired cursor retries once without a cursor`() = runTest {
        val repository = FakeRepository()
        val cursors = mutableListOf<String?>()
        val useCase = useCase(repository) { _, _, cursor ->
            cursors += cursor
            if (cursors.size == 1) {
                SessionSyncFetchResult.CursorExpired(
                    SessionSyncError(code = "cursor_expired", message = "expired")
                )
            } else {
                SessionSyncFetchResult.Complete(snapshot)
            }
        }

        assertSame(RecentChatsSyncResult.Synced, useCase.sync("agent-1", "0xagent", null))
        assertEquals(listOf("cursor-1", null), cursors)
        assertEquals(1, repository.reconciled.size)
    }

    @Test
    fun `unsupported host keeps local history unchanged`() = runTest {
        val repository = FakeRepository()
        val useCase = useCase(repository) { _, _, _ -> SessionSyncFetchResult.Unsupported() }

        assertSame(RecentChatsSyncResult.Unsupported, useCase.sync("agent-1", "0xagent", null))
        assertEquals(emptyList<Any>(), repository.reconciled)
        assertEquals("cursor-1", repository.cursor)
    }

    @Test
    fun `retryable failed fetch retries once and keeps local history unchanged`() = runTest {
        val repository = FakeRepository()
        var calls = 0
        val useCase = useCase(repository) { _, _, _ ->
            calls++
            SessionSyncFetchResult.Failed("offline", retryable = true)
        }

        assertEquals(
            RecentChatsSyncResult.Failed("offline", retryable = true),
            useCase.sync("agent-1", "0xagent", null)
        )
        assertEquals(emptyList<Any>(), repository.reconciled)
        assertEquals("cursor-1", repository.cursor)
        assertEquals(2, calls)
    }

    @Test
    fun `cursor recovery is attempted only once`() = runTest {
        val repository = FakeRepository()
        var calls = 0
        val useCase = useCase(repository) { _, _, _ ->
            calls++
            SessionSyncFetchResult.CursorExpired(
                SessionSyncError(code = "cursor_expired", message = "still expired")
            )
        }

        assertEquals(
            RecentChatsSyncResult.Failed("still expired"),
            useCase.sync("agent-1", "0xagent", null)
        )
        assertEquals(2, calls)
        assertEquals(emptyList<Any>(), repository.reconciled)
    }

    private fun useCase(
        repository: FakeRepository,
        fetch: suspend (String, String?, String?) -> SessionSyncFetchResult
    ) = SyncRecentChatsUseCase(
        repository = repository,
        identityAddress = { "0xidentity" },
        fetch = fetch
    )
}
