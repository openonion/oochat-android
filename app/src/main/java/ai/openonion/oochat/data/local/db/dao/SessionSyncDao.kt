package ai.openonion.oochat.data.local.db.dao

import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.data.local.db.entity.SessionSyncDeletionEntity
import ai.openonion.oochat.data.local.db.entity.SessionSyncStateEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Database operations used to merge the Host's Recent Chat list into Room. */
@Dao
interface SessionSyncDao {
    /** Returns the cursor for this exact identity and agent. */
    @Query(
        "SELECT cursor FROM session_sync_states " +
            "WHERE identity_address = :identityAddress AND agent_id = :agentId"
    )
    suspend fun getCursor(identityAddress: String, agentId: String): String?

    /** Returns the complete persisted sync state for this identity and agent. */
    @Query(
        "SELECT * FROM session_sync_states " +
            "WHERE identity_address = :identityAddress AND agent_id = :agentId"
    )
    suspend fun getState(identityAddress: String, agentId: String): SessionSyncStateEntity?

    /** Replaces only the small cursor row; it cannot affect chat messages. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: SessionSyncStateEntity)

    /** Marks Memory off while preserving any cursor already saved for the pair. */
    @Transaction
    suspend fun markMemoryDisabled(identityAddress: String, agentId: String, updatedAt: Long) {
        val current = getState(identityAddress, agentId)
        saveState(
            SessionSyncStateEntity(
                identityAddress = identityAddress,
                agentId = agentId,
                cursor = current?.cursor.orEmpty(),
                updatedAt = updatedAt,
                memoryDisabledSinceSync = true
            )
        )
    }

    /** Looks up the owner before an existing chat is changed. */
    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    /** Keeps an existing chat row—and therefore its messages—when the ID already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: ChatSessionEntity): Long

    /** Applies newer Host metadata without overwriting a locally chosen title or preview. */
    @Query(
        """
        UPDATE chat_sessions
        SET title = CASE WHEN title_is_local = 1 THEN title ELSE :title END,
            updated_at = :updatedAt,
            last_message_preview = COALESCE(:preview, last_message_preview),
            remote_revision = :revision,
            sync_identity_address = :identityAddress,
            remote_removed = 0,
            identity_hidden = 0
        WHERE id = :sessionId
          AND agent_id = :agentId
          AND (remote_revision IS NULL OR remote_revision < :revision)
        """
    )
    suspend fun updateRemoteMetadata(
        sessionId: String,
        agentId: String,
        title: String,
        updatedAt: Long,
        preview: String?,
        revision: Long,
        identityAddress: String
    ): Int

    /** A Host removal hides only a remote-only row; local transcripts stay listed. */
    @Query(
        "UPDATE chat_sessions SET remote_removed = CASE WHEN EXISTS (" +
            "SELECT 1 FROM chat_messages WHERE session_id = chat_sessions.id" +
            ") THEN 0 ELSE 1 END " +
            "WHERE id = :sessionId AND agent_id = :agentId AND sync_identity_address = :identityAddress"
    )
    suspend fun markRemoteRemoved(sessionId: String, agentId: String, identityAddress: String): Int

    /** A full snapshot is authoritative: hide prior remote rows that it no longer contains. */
    @Query(
        "UPDATE chat_sessions SET remote_removed = CASE WHEN EXISTS (" +
            "SELECT 1 FROM chat_messages WHERE session_id = chat_sessions.id" +
            ") THEN 0 ELSE 1 END " +
            "WHERE agent_id = :agentId AND sync_identity_address = :identityAddress " +
            "AND id NOT IN (:retainedSessionIds)"
    )
    suspend fun markMissingFromFullSnapshot(
        agentId: String,
        identityAddress: String,
        retainedSessionIds: List<String>
    )

    @Query(
        "UPDATE chat_sessions SET remote_removed = CASE WHEN EXISTS (" +
            "SELECT 1 FROM chat_messages WHERE session_id = chat_sessions.id" +
            ") THEN 0 ELSE 1 END " +
            "WHERE agent_id = :agentId AND sync_identity_address = :identityAddress"
    )
    suspend fun markAllMissingFromFullSnapshot(agentId: String, identityAddress: String)

    /** Only synced rows are identity-specific; local-only conversations remain visible. */
    @Query(
        "UPDATE chat_sessions SET identity_hidden = " +
            "CASE WHEN sync_identity_address = :identityAddress THEN 0 ELSE 1 END " +
            "WHERE sync_identity_address IS NOT NULL"
    )
    suspend fun showOnlyIdentity(identityAddress: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun saveDeletion(deletion: SessionSyncDeletionEntity)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM session_sync_deletions " +
            "WHERE identity_address = :identityAddress AND agent_id = :agentId AND session_id = :sessionId)"
    )
    suspend fun wasDeleted(identityAddress: String, agentId: String, sessionId: String): Boolean

    /** Records the choice before deleting, so a full sync cannot re-add the row. */
    @Transaction
    suspend fun deleteLocally(identityAddress: String, sessionId: String, deletedAt: Long): Boolean {
        val session = getSession(sessionId) ?: return false
        saveDeletion(
            SessionSyncDeletionEntity(identityAddress, session.agentId, sessionId, deletedAt)
        )
        deleteSession(sessionId)
        return true
    }

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
