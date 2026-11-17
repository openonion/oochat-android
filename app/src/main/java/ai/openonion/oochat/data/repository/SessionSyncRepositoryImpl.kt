package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.data.local.db.entity.SessionSyncStateEntity
import ai.openonion.oochat.data.protocol.RemoteSessionSummary
import ai.openonion.oochat.network.SessionSyncSnapshot
import androidx.room.withTransaction
import java.time.Instant

/** Safely merges one complete Host snapshot into the local Recent Chat list. */
class SessionSyncRepositoryImpl(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) : SessionSyncRepository {
    private val dao = database.sessionSyncDao()

    override suspend fun activateIdentity(identityAddress: String) {
        require(identityAddress.isNotBlank()) { "identityAddress must not be blank" }
        dao.showOnlyIdentity(identityAddress)
    }

    override suspend fun cursorFor(identityAddress: String, agentId: String): String? {
        // Never read a cursor without knowing exactly who and which agent it belongs to.
        require(identityAddress.isNotBlank()) { "identityAddress must not be blank" }
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        // An empty cursor is the persisted marker row used by a fresh install
        // that disables Memory before it has ever completed a sync.
        return dao.getCursor(identityAddress, agentId)?.takeIf(String::isNotBlank)
    }

    override suspend fun markMemoryDisabled(identityAddress: String, agentId: String) {
        require(identityAddress.isNotBlank()) { "identityAddress must not be blank" }
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        dao.markMemoryDisabled(identityAddress, agentId, clock())
    }

    override suspend fun isMemoryDisabledSinceSync(identityAddress: String, agentId: String): Boolean {
        require(identityAddress.isNotBlank()) { "identityAddress must not be blank" }
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        return dao.getState(identityAddress, agentId)?.memoryDisabledSinceSync == true
    }

    override suspend fun reconcile(
        identityAddress: String,
        agentId: String,
        snapshot: SessionSyncSnapshot
    ) {
        // Refuse incomplete ownership details or a snapshot that did not reach
        // its final cursor.
        require(identityAddress.isNotBlank()) { "identityAddress must not be blank" }
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        require(snapshot.cursor.isNotBlank()) { "a completed Session Sync cursor must not be blank" }

        // Check all Host data before changing Room, so bad data changes nothing.
        val prepared = prepare(snapshot)
        val removedIds = snapshot.removedSessionIds.toSet()

        // All chat changes and the cursor succeed together or are all rolled back.
        database.withTransaction {
            if (snapshot.isFullSnapshot) {
                val retainedIds = prepared.map(PreparedSummary::sessionId)
                if (retainedIds.isEmpty()) {
                    dao.markAllMissingFromFullSnapshot(agentId, identityAddress)
                } else {
                    dao.markMissingFromFullSnapshot(agentId, identityAddress, retainedIds)
                }
            }

            prepared.forEach { remote ->
                if (dao.wasDeleted(identityAddress, agentId, remote.sessionId)) return@forEach
                val existing = dao.getSession(remote.sessionId)
                if (existing == null) {
                    val inserted = dao.insertSession(
                        ChatSessionEntity(
                            id = remote.sessionId,
                            agentId = agentId,
                            title = remote.title,
                            createdAt = remote.createdAt,
                            updatedAt = remote.updatedAt,
                            messageCount = 0,
                            lastMessagePreview = remote.preview,
                            remoteRevision = remote.revision,
                            syncIdentityAddress = identityAddress,
                            titleIsLocal = false
                        )
                    )
                    // A surprise duplicate means the database changed while
                    // syncing. Stop instead of guessing which row should win.
                    check(inserted != -1L) { "session ${remote.sessionId} appeared during reconciliation" }
                } else {
                    // A chat ID already owned by another agent must never be moved.
                    check(existing.agentId == agentId) {
                        "session ${remote.sessionId} already belongs to another agent"
                    }
                    // Update the existing row in place so its local messages
                    // and message count are preserved.
                    dao.updateRemoteMetadata(
                        sessionId = remote.sessionId,
                        agentId = agentId,
                        title = remote.title,
                        updatedAt = remote.updatedAt,
                        preview = remote.preview,
                        revision = remote.revision,
                        identityAddress = identityAddress
                    )
                }
            }

            removedIds.forEach { sessionId ->
                // Hide Host removals without cascading into the local transcript.
                dao.markRemoteRemoved(sessionId, agentId, identityAddress)
            }

            // Save the cursor last. It must never say a failed sync completed.
            dao.saveState(
                SessionSyncStateEntity(
                    identityAddress = identityAddress,
                    agentId = agentId,
                    cursor = snapshot.cursor,
                    updatedAt = clock(),
                    memoryDisabledSinceSync = false
                )
            )
        }
    }

    override suspend fun advanceCursor(
        identityAddress: String,
        agentId: String,
        cursor: String
    ) {
        require(identityAddress.isNotBlank()) { "identityAddress must not be blank" }
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        require(cursor.isNotBlank()) { "a Session Sync cursor must not be blank" }

        database.withTransaction {
            dao.saveState(
                SessionSyncStateEntity(
                    identityAddress = identityAddress,
                    agentId = agentId,
                    cursor = cursor,
                    updatedAt = clock(),
                    memoryDisabledSinceSync = false
                )
            )
        }
    }

    private fun prepare(snapshot: SessionSyncSnapshot): List<PreparedSummary> {
        val removedIds = snapshot.removedSessionIds.toSet()
        require(removedIds.none(String::isBlank)) { "removed session ids must not be blank" }

        // If pages repeat a chat, keep only its newest revision.
        val newestById = snapshot.sessions
            .groupBy(RemoteSessionSummary::sessionId)
            .mapValues { (_, versions) -> versions.maxBy(RemoteSessionSummary::revision) }

        // Reject unsafe or contradictory instructions before opening the transaction.
        require(newestById.keys.none(String::isBlank)) { "session ids must not be blank" }
        require(newestById.values.all { it.revision >= 1 }) { "session revisions must be positive" }
        require(newestById.keys.intersect(removedIds).isEmpty()) {
            "a snapshot cannot update and remove the same session"
        }

        // Parsing now makes an invalid Host timestamp fail before Room changes.
        return newestById.values.map { summary ->
            PreparedSummary(
                sessionId = summary.sessionId,
                revision = summary.revision,
                title = summary.title,
                createdAt = Instant.parse(summary.createdAt).toEpochMilli(),
                updatedAt = Instant.parse(summary.updatedAt).toEpochMilli(),
                preview = summary.preview
            )
        }
    }

    private data class PreparedSummary(
        val sessionId: String,
        val revision: Long,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val preview: String?
    )
}
