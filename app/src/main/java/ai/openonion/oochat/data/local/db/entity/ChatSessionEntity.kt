package ai.openonion.oochat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_sessions",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["agent_id"]),
        Index(value = ["updated_at"])
    ]
)
data class ChatSessionEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "agent_id")
    val agentId: String,

    val title: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0,

    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String? = null,

    /** Newest Host index revision applied to this row */
    @ColumnInfo(name = "remote_revision")
    val remoteRevision: Long? = null,

    /** Identity whose Host index supplied this row; null means local-only. */
    @ColumnInfo(name = "sync_identity_address")
    val syncIdentityAddress: String? = null,

    /** Hidden because the Host removed it, without deleting its local transcript. */
    @ColumnInfo(name = "remote_removed")
    val remoteRemoved: Boolean = false,

    /** Hidden while a different local identity is active. */
    @ColumnInfo(name = "identity_hidden")
    val identityHidden: Boolean = false,

    /** Prevents later Host revisions from undoing a title chosen on this device. */
    @ColumnInfo(name = "title_is_local")
    val titleIsLocal: Boolean = true
)
