package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.local.db.dao.SessionDao
import ai.openonion.oochat.data.local.db.dao.SessionSyncDao
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.data.local.mapper.SessionMapper
import ai.openonion.oochat.domain.model.ChatSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implementation of SessionRepository.
 *
 * Uses Room database for persistence.
 * Follows single source of truth principle.
 */
class SessionRepositoryImpl(
    private val sessionDao: SessionDao,
    private val sessionSyncDao: SessionSyncDao? = null,
    private val identityAddress: suspend () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis
) : SessionRepository {

    override suspend fun activateIdentity(identityAddress: String) {
        require(identityAddress.isNotBlank()) { "identityAddress must not be blank" }
        sessionSyncDao?.showOnlyIdentity(identityAddress)
    }

    override fun getAllSessions(): Flow<List<ChatSession>> {
        return sessionDao.getAllSessions().map { entities ->
            SessionMapper.toDomainList(entities)
        }
    }

    override fun getSessionsByAgent(agentId: String): Flow<List<ChatSession>> {
        return sessionDao.getSessionsByAgent(agentId).map { entities ->
            SessionMapper.toDomainList(entities)
        }
    }

    override suspend fun getSessionById(id: String): ChatSession? {
        return sessionDao.getSessionById(id)?.let { entity ->
            SessionMapper.toDomain(entity)
        }
    }

    override suspend fun createSession(agentId: String, title: String, id: String): ChatSession {
        val now = System.currentTimeMillis()
        val identity = identityAddress()?.takeIf(String::isNotBlank)
        val session = ChatSessionEntity(
            id = id,
            agentId = agentId,
            title = title,
            createdAt = now,
            updatedAt = now,
            messageCount = 0,
            lastMessagePreview = null,
            syncIdentityAddress = identity
        )
        sessionDao.insertSession(session)
        return SessionMapper.toDomain(session)
    }

    override suspend fun deleteSession(sessionId: String) {
        val identity = identityAddress()
        if (sessionSyncDao != null && !identity.isNullOrBlank() &&
            sessionSyncDao.deleteLocally(identity, sessionId, clock())
        ) {
            return
        }
        sessionDao.deleteSessionById(sessionId)
    }

    override suspend fun renameSession(sessionId: String, newTitle: String) {
        sessionDao.updateTitle(sessionId, newTitle, System.currentTimeMillis())
    }

    override suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?) {
        sessionDao.updateMessageInfo(sessionId, count, preview, System.currentTimeMillis())
    }
}
