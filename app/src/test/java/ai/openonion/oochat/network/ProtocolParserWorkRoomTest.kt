package ai.openonion.oochat.network

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderActivityStatus
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W16AAPPLE-33: Claude Code and Codex Work Room frames, in the shapes
 * connectonion 1.9.3's coding-agent plugins send (plugins/coding_agents.py,
 * useful_tools/codex.py, core/provider_events.py).
 */
class ProtocolParserWorkRoomTest {

    private fun parse(frame: String): ParseResult =
        ProtocolParser.parse(text = frame, outstandingInputIds = emptySet(), isDirect = false)

    private fun itemOf(result: ParseResult): ChatItem = (result.event as ConnectionEvent.ChatItemUpdated).item

    @Test
    fun `a provider_invocation becomes the run's card, and its trace id moves the replay cursor`() {
        val result = parse(
            """{"type":"provider_invocation","id":"trace-7","invocationId":"codex:call-1","parentToolCallId":"call-1",
            "workroomId":"codex:call-1","provider":"codex","providerDisplayName":"Codex",
            "taskTitle":"Add a login page","taskSummary":"Add a login page",
            "currentSummary":"Working in the selected workspace","permissionMode":"auto",
            "status":"running","stateRevision":1}"""
        )

        val run = itemOf(result) as ChatItem.ProviderInvocation
        assertEquals("codex:call-1", run.id)
        assertEquals("call-1", run.parentToolCallId)
        assertEquals("Codex", run.providerDisplayName)
        assertEquals("Add a login page", run.taskTitle)
        assertEquals(ProviderInvocationStatus.RUNNING, run.status)
        assertEquals(1L, run.stateRevision)
        assertEquals("trace-7", result.msgId)
    }

    @Test
    fun `every status the Host sends is read, and an unknown one is still running`() {
        mapOf(
            "awaiting_approval" to ProviderInvocationStatus.AWAITING_APPROVAL,
            "completed" to ProviderInvocationStatus.COMPLETED,
            "failed" to ProviderInvocationStatus.FAILED,
            "cancelled" to ProviderInvocationStatus.CANCELLED,
            "something_new" to ProviderInvocationStatus.RUNNING
        ).forEach { (wire, expected) ->
            val run = itemOf(parse("""{"type":"provider_invocation","invocationId":"codex:c","status":"$wire"}"""))
            assertEquals(wire, expected, (run as ChatItem.ProviderInvocation).status)
        }
    }

    @Test
    fun `a provider_activity with file names is a step of its run`() {
        // files is a list of names here, where other frames' files are objects.
        val update = itemOf(
            parse(
                """{"type":"provider_activity","invocationId":"codex:call-1","parentToolCallId":"call-1",
                "provider":"codex","activityId":"item-3","sequence":2,"kind":"file_change","status":"completed",
                "title":"Update workspace files","summary":"Changed 2 files","files":["Login.kt","login.xml"]}"""
            )
        ) as ChatItem.ProviderActivityUpdate

        assertEquals("codex:call-1", update.invocationId)
        assertEquals("item-3", update.activity.id)
        assertEquals(2, update.activity.sequence)
        assertEquals(ProviderActivityStatus.COMPLETED, update.activity.status)
        assertEquals("Update workspace files", update.activity.title)
        assertEquals(listOf("Login.kt", "login.xml"), update.activity.files)
    }

    @Test
    fun `an activity naming no run, no step or no order is ignored, but still moves the replay cursor`() {
        listOf(
            """{"type":"provider_activity","id":"m9","activityId":"a","sequence":1,"title":"t","status":"running"}""",
            """{"type":"provider_activity","id":"m9","invocationId":"codex:c","sequence":1,"title":"t","status":"running"}""",
            """{"type":"provider_activity","id":"m9","invocationId":"codex:c","activityId":"a","sequence":0,"title":"t","status":"running"}"""
        ).forEach { frame ->
            val result = parse(frame)
            assertEquals(frame, ConnectionEvent.Unknown, result.event)
            assertEquals(frame, "m9", result.msgId)
        }
    }

    @Test
    fun `a compatibility tool_call for a run is a step of it, under the generic title`() {
        // As Codex sends it (useful_tools/codex.py): the name is the raw command itself.
        val update = itemOf(
            parse(
                """{"type":"tool_call","tool_id":"item-9","name":"rm -rf /Users/jo/secret","args":{"command":"rm -rf /Users/jo/secret"},
                "status":"in_progress","invocationId":"codex:call-1","parentToolCallId":"call-1"}"""
            )
        ) as ChatItem.ProviderActivityUpdate

        assertEquals("codex:call-1", update.invocationId)
        assertEquals("item-9", update.activity.id)
        assertEquals("Use a provider tool", update.activity.title)
        assertEquals(ProviderActivityStatus.RUNNING, update.activity.status)
        assertTrue(update.activity.legacy)
        assertTrue("neither the name nor the command is carried", "rm -rf" !in update.activity.title + update.activity.summary)
    }

    @Test
    fun `its tool_result finishes that step`() {
        val update = itemOf(
            parse("""{"type":"tool_result","tool_id":"item-9","name":"shell","status":"success","invocationId":"codex:call-1"}""")
        ) as ChatItem.ProviderActivityUpdate

        assertEquals(ProviderActivityStatus.COMPLETED, update.activity.status)
    }

    @Test
    fun `an ordinary tool_call is still a tool card`() {
        val result = parse("""{"type":"tool_call","tool_id":"t-1","name":"bash","status":"running"}""")

        assertTrue((result.event as ConnectionEvent.ChatItemReceived).item is ChatItem.ToolCall)
    }

    @Test
    fun `a reply that mentions provider_invocation is still a reply`() {
        // Direct, so the OUTPUT is this client's whatever its input_id.
        val result = ProtocolParser.parse(
            // The value is exactly "provider_invocation", so the cheap screen lets it through to the decode.
            text = """{"type":"OUTPUT","result":"provider_invocation","input_id":"i-1"}""",
            outstandingInputIds = emptySet(),
            isDirect = true
        )

        assertTrue(result.event !is ConnectionEvent.ChatItemUpdated)
        assertTrue(result.event != ConnectionEvent.Unknown)
    }
}
