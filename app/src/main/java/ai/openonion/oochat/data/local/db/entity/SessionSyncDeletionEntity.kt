package ai.openonion.oochat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Remembers a local deletion so a later full Host snapshot cannot restore it. */
@Entity(
    tableName = "session_sync_deletions",
    primaryKeys = ["identity_address", "agent_id", "session_id"],
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["agent_id"])]
)
data class SessionSyncDeletionEntity(
    @ColumnInfo(name = "identity_address")
    val identityAddress: String,

    @ColumnInfo(name = "agent_id")
    val agentId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long
)
