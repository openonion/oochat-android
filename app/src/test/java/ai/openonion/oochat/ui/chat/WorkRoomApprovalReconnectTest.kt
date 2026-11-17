package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.testing.FakeOipHost.Companion.string
import ai.openonion.oochat.ui.workroom.workRoomApprovals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** W16AAPPLE-36: Work Room approvals across a dropped connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkRoomApprovalReconnectTest : RelayResumeTestBase() {

    private val runId = "claude_code:tool-1"

    private val awaiting =
        """{"type":"provider_invocation","id":"pi-1","invocationId":"$runId","parentToolCallId":"tool-1",""" +
            """"provider":"claude_code","providerDisplayName":"Claude Code","status":"awaiting_approval","stateRevision":2}"""
    private val approvalNeeded =
        """{"type":"approval_needed","id":"req-7","tool":"claude_code",""" +
            """"arguments":{"action":"Make workspace file changes","scope":"This Work Room only","reason":"Apply the requested workspace file changes"},""" +
            """"provider":"claude_code","invocationId":"$runId","parentToolCallId":"tool-1",""" +
            """"providerApproval":{"action":"Make workspace file changes","scope":"This Work Room only",""" +
            """"reason":"Apply the requested workspace file changes","scopeClassification":"workroom",""" +
            """"allowOnce":true,"allowSession":false,"files":["Login.kt"]}}"""

    private fun App.roomApprovals() = items.workRoomApprovals(runId)

    private fun sent(type: String): List<JsonObject> =
        relay.sockets.flatMap { it.sent }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.string("type") == type }

    private suspend fun waitingInRoom(): App {
        val (app, _) = connectedMidTurn()
        relay.emit(awaiting)
        relay.emit(approvalNeeded)
        pumpUntil("the approval to show in its Work Room") { app.roomApprovals().size == 1 }
        return app
    }

    private suspend fun reconnect(app: App) {
        val before = relay.connects.size
        relay.fail(liveSocket, IOException("Software caused connection abort"))
        pumpUntil("the app to reconnect") { relay.connects.size > before && app.connection.isConnected() }
    }

    @Test
    fun `a pending approval is still open in its Work Room after a reconnect`() = runBlocking {
        val app = waitingInRoom()

        reconnect(app)
        relay.emit(approvalNeeded)
        pumpFor(1_000)

        val pending = app.roomApprovals().single()
        assertNull("the request must still be waiting for an answer", pending.decision)
        assertTrue(pending.workRoom!!.canAllowOnce)
    }

    @Test
    fun `the answer goes out on the new connection naming the request`() = runBlocking {
        val app = waitingInRoom()
        reconnect(app)
        val socketsBefore = relay.sockets.size

        app.vm.respondToApproval(app.roomApprovals().single().id, approved = true, scope = "once")
        pumpUntil("the answer to reach the Host") { sent("APPROVAL_RESPONSE").isNotEmpty() }

        val answer = sent("APPROVAL_RESPONSE").single()
        assertEquals(true, answer["approved"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("once", answer.string("scope"))
        assertEquals("req-7", answer.string("request_id"))
        assertTrue("sent on the socket the app reconnected on", relay.sockets.last { !it.dead }.sent.any { "APPROVAL_RESPONSE" in it })
        assertEquals(socketsBefore, relay.sockets.size)
    }

    @Test
    fun `an answered approval replayed after a reconnect stays answered`() = runBlocking {
        val app = waitingInRoom()
        app.vm.respondToApproval(app.roomApprovals().single().id, approved = false, scope = "once", mode = "reject_soft")
        pumpUntil("the answer to reach the Host") { sent("APPROVAL_RESPONSE").isNotEmpty() }

        reconnect(app)
        relay.emit(approvalNeeded)
        pumpFor(1_000)

        val card: ChatItem.ApprovalNeeded = app.roomApprovals().single()
        assertEquals(false, card.decision?.approved)
        assertEquals("a replay must not ask a settled question again", 1, sent("APPROVAL_RESPONSE").size)
    }
}
