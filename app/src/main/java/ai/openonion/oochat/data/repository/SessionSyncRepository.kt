package ai.openonion.oochat.data.repository

import ai.openonion.oochat.network.SessionSyncSnapshot

/** Persists complete retained-session snapshots and their incremental cursor. */
interface SessionSyncRepository {
    suspend fun activateIdentity(identityAddress: String)

    suspend fun cursorFor(identityAddress: String, agentId: String): String?

    suspend fun reconcile(
        identityAddress: String,
        agentId: String,
        snapshot: SessionSyncSnapshot
    )
}
