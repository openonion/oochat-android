package ai.openonion.oochat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Remembers where a successful Recent Chat sync finished.
 *
 * Each identity-and-agent pair gets its own row. This prevents the app from
 * using one account's cursor while signed in as another account. Deleting an
 * agent also removes its saved cursor.
 */
@Entity(
    tableName = "session_sync_states",
    primaryKeys = ["identity_address", "agent_id"],
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
data class SessionSyncStateEntity(
    /** The signed-in identity that owns the remote chats. */
    @ColumnInfo(name = "identity_address") val identityAddress: String,
    /** The agent whose Recent Chat list was synced. */
    @ColumnInfo(name = "agent_id") val agentId: String,
    /** The Host value to send with the next sync request. */
    val cursor: String,
    /** When this cursor was saved, in Unix milliseconds. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
