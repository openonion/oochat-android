package ai.openonion.oochat.network

import ai.openonion.oochat.domain.model.ApprovalBoundary
import ai.openonion.oochat.domain.model.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W16AAPPLE-36: Work Room approval frames, as connectonion's network/io/base.py sends them. */
class ProtocolParserWorkRoomApprovalTest {

    private fun parse(frame: String): ParseResult =
        ProtocolParser.parse(text = frame, outstandingInputIds = emptySet(), isDirect = false)

    private fun approvalOf(frame: String): ChatItem.ApprovalNeeded =
        (parse(frame).event as ConnectionEvent.ChatItemReceived).item as ChatItem.ApprovalNeeded

    private fun frame(providerApproval: String?, provider: String = "\"claude_code\"") =
        """{"type":"approval_needed","id":"req-9","tool":"claude_code",
        "arguments":{"action":"Make workspace file changes","scope":"This Work Room only","reason":"Apply the requested workspace file changes"},
        "provider":$provider,"invocationId":"claude_code:call-1","parentToolCallId":"call-1"
        ${providerApproval?.let { ""","providerApproval":$it""" } ?: ""}}"""

    private val claudeEdit =
        """{"action":"Make workspace file changes","scope":"This Work Room only",
        "reason":"Apply the requested workspace file changes","scopeClassification":"workroom",
        "allowOnce":true,"allowSession":false,"files":["/home/me/project/src/Login.kt"]}"""

    @Test
    fun `a Work Room approval names its run and keeps the Host's copy`() {
        val item = approvalOf(frame(claudeEdit))

        assertEquals("req-9", item.requestId)
        val room = item.workRoom!!
        assertEquals("claude_code", room.provider)
        assertEquals("claude_code:call-1", room.invocationId)
        assertEquals("call-1", room.parentToolCallId)
        val copy = room.presentation!!
        assertEquals("Make workspace file changes", copy.action)
        assertEquals("This Work Room only", copy.scope)
        assertEquals(ApprovalBoundary.WORKROOM, copy.boundary)
        assertEquals("only the file's name ever reaches the screen", listOf("Login.kt"), copy.files)
        assertTrue(room.canAllowOnce)
        assertFalse(room.canAllowSession)
    }

    @Test
    fun `it still blocks the chat waiting for an answer`() {
        assertTrue(parse(frame(claudeEdit)).setWaiting)
    }

    @Test
    fun `an elevated request can only be denied, whatever its flags say`() {
        val elevated = """{"action":"Expand provider permissions","scope":"Outside this Work Room",
            "reason":"Review the requested permission expansion","scopeClassification":"elevated",
            "allowOnce":true,"allowSession":true}"""

        val room = approvalOf(frame(elevated)).workRoom!!

        assertEquals(ApprovalBoundary.ELEVATED, room.presentation!!.boundary)
        assertFalse(room.canAllowOnce)
        assertFalse(room.canAllowSession)
    }

    @Test
    fun `copy the Host does not send is dropped, leaving Deny as the only answer`() {
        listOf(
            claudeEdit.replace("Make workspace file changes", "rm -rf /"),
            claudeEdit.replace("This Work Room only", "Outside this Work Room"),
            claudeEdit.replace("\"allowOnce\":true", "\"allowOnce\":\"yes\""),
            "\"not an object\"",
            "42"
        ).forEach { copy ->
            val room = approvalOf(frame(copy)).workRoom
            assertNotNull("a bad copy must not drop the run: $copy", room)
            assertNull(copy, room!!.presentation)
            assertFalse(copy, room.canAllowOnce)
        }
    }

    @Test
    fun `a Work Room approval with no copy at all can only be denied`() {
        val room = approvalOf(frame(providerApproval = null)).workRoom!!
        assertNull(room.presentation)
        assertFalse(room.canAllowOnce)
    }

    @Test
    fun `an ordinary tool approval has no Work Room`() {
        val item = approvalOf("""{"type":"approval_needed","id":"a1","tool":"bash","arguments":{"command":"ls"}}""")
        assertNull(item.workRoom)
    }

    @Test
    fun `an unknown provider is not a Work Room, and a non-string one does not drop the frame`() {
        assertNull(approvalOf(frame(claudeEdit, provider = "\"someone_else\"")).workRoom)
        assertNull(approvalOf(frame(claudeEdit, provider = """{"name":"codex"}""")).workRoom)
    }

    @Test
    fun `files are bare, distinct names and at most eight`() {
        val paths = (1..12).joinToString(",") { "\"C:\\\\work\\\\f${it % 10}.kt\"" }
        val copy = claudeEdit.replace("""["/home/me/project/src/Login.kt"]""", "[$paths, \"dir/\", \"\"]")

        val files = approvalOf(frame(copy)).workRoom!!.presentation!!.files

        assertEquals((1..8).map { "f$it.kt" }, files)
    }
}
