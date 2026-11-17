package ai.openonion.oochat.data.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSyncProtocolTest {

    @Test
    fun `a full index request has the Host's exact wire shape`() {
        val encoded = SessionSyncProtocol.encodeRequest(
            SessionSyncRequest(requestId = "sync-1")
        )

        assertEquals(
            """{"type":"SESSION_SYNC","request_id":"sync-1","limit":50,"include_archived":false}""",
            encoded
        )
    }

    @Test
    fun `an incremental request preserves its opaque cursor`() {
        val encoded = SessionSyncProtocol.encodeRequest(
            SessionSyncRequest(
                requestId = "sync-2",
                cursor = "opaque.payload.signature",
                limit = 100
            )
        )

        assertEquals(
            """{"type":"SESSION_SYNC","request_id":"sync-2","cursor":"opaque.payload.signature","limit":100,"include_archived":false}""",
            encoded
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a request cannot mix an incremental cursor with a page token`() {
        SessionSyncRequest(
            requestId = "sync-3",
            cursor = "cursor",
            pageToken = "page"
        )
    }

    @Test
    fun `a final full-sync result decodes every summary field and ignores additions`() {
        val reply = SessionSyncProtocol.decodeReply(
            """
            {
              "type":"SESSION_SYNC_RESULT",
              "request_id":"sync-1",
              "sessions":[{
                "session_id":"550e8400",
                "revision":7,
                "title":"Translate the report",
                "activity":"idle",
                "created_at":"2026-09-01T08:00:00Z",
                "updated_at":"2026-09-01T08:03:12Z",
                "last_sequence":12,
                "last_outcome":"completed",
                "archived_at":"2026-09-02T08:00:00Z",
                "expires_at":"2026-10-01T08:00:00Z",
                "preview":"The translated report is ready",
                "future_field":{"safe":"to ignore"}
              }],
              "removed_session_ids":["expired-session"],
              "cursor":"opaque-cursor",
              "future_top_level":true
            }
            """.trimIndent()
        )

        assertTrue(reply is SessionSyncReply.Page)
        val page = (reply as SessionSyncReply.Page).value
        assertTrue(page.isFinalPage)
        assertEquals("opaque-cursor", page.cursor)
        assertEquals(listOf("expired-session"), page.removedSessionIds)
        assertEquals(
            RemoteSessionSummary(
                sessionId = "550e8400",
                revision = 7,
                title = "Translate the report",
                activity = "idle",
                createdAt = "2026-09-01T08:00:00Z",
                updatedAt = "2026-09-01T08:03:12Z",
                lastSequence = 12,
                lastOutcome = "completed",
                archivedAt = "2026-09-02T08:00:00Z",
                expiresAt = "2026-10-01T08:00:00Z",
                preview = "The translated report is ready"
            ),
            page.sessions.single()
        )
    }

    @Test
    fun `a non-final page exposes only its opaque page token`() {
        val reply = SessionSyncProtocol.decodeReply(
            """{"type":"SESSION_SYNC_RESULT","request_id":"sync-1","sessions":[],"removed_session_ids":[],"next_page_token":"opaque-page"}"""
        )

        assertTrue(reply is SessionSyncReply.Page)
        val page = (reply as SessionSyncReply.Page).value
        assertFalse(page.isFinalPage)
        assertEquals("opaque-page", page.nextPageToken)
    }

    @Test
    fun `cursor expiry is a typed retry-from-full outcome`() {
        val reply = SessionSyncProtocol.decodeReply(
            """{"type":"ERROR","code":"cursor_expired","message":"session sync cursor expired","retryable":false,"request_id":"sync-1"}"""
        )

        assertTrue(reply is SessionSyncReply.CursorExpired)
        assertEquals("sync-1", (reply as SessionSyncReply.CursorExpired).error.requestId)
    }

    @Test
    fun `an older Host's unsupported extension response is typed`() {
        val reply = SessionSyncProtocol.decodeReply(
            """{"type":"ERROR","code":"unsupported_extension","message":"session-sync/0.1 was not negotiated","retryable":false,"request_id":"sync-1"}"""
        )

        assertTrue(reply is SessionSyncReply.Unsupported)
    }

    @Test
    fun `a result without a cursor or page token fails closed`() {
        val reply = SessionSyncProtocol.decodeReply(
            """{"type":"SESSION_SYNC_RESULT","request_id":"sync-1","sessions":[],"removed_session_ids":[]}"""
        )

        assertEquals(SessionSyncReply.Malformed, reply)
    }

    @Test
    fun `an unrelated frame remains distinguishable from malformed sync data`() {
        val reply = SessionSyncProtocol.decodeReply("""{"type":"PING"}""")

        assertEquals(SessionSyncReply.Unknown("PING"), reply)
    }
}
