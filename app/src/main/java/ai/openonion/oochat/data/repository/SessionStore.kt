package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.protocol.SessionState

/**
 * Persists the protocol-layer SessionState of one local conversation, so a
 * conversation resumes its own server session across restarts and
 * reconnections.
 *
 * Keyed by [ai.openonion.oochat.domain.model.ChatSession.id] — see
 * [ai.openonion.oochat.data.local.db.entity.SessionStateEntity].
 */
interface SessionStore {

    suspend fun saveSession(conversationId: String, session: SessionState)

    suspend fun getSession(conversationId: String): SessionState?

    /**
     * Returns the session to name in the next CONNECT message.
     *
     * A saved session resumes the conversation. If there is no saved session,
     * using the conversation ID keeps reconnect attempts for that conversation
     * on the same server session.
     */
    suspend fun sessionForConnect(conversationId: String): SessionState? =
        getSession(conversationId) ?: SessionState(sessionId = conversationId)

    /**
     * Named `...ByConversation` (not `deleteSession`) to avoid colliding with
     * [SessionRepository.deleteSession], which deletes the conversation
     * itself. Call sites like `ChatViewModel` hold both repositories.
     */
    suspend fun deleteSessionByConversation(conversationId: String)

    /** Drop rows whose conversation no longer exists; returns how many went. */
    suspend fun deleteOrphanedSessions(): Int
}
