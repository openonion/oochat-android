package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.knownIssue
import ai.openonion.oochat.testing.FakeOipHost.Companion.string
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Approval answers reach the socket as the `APPROVAL_RESPONSE` connectonion
 * accepts, and Ultra work ends in Safe when its budget runs out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalResponseTest : RelayResumeTestBase() {

    private val approvalNeeded =
        """{"type":"approval_needed","id":"req-1","tool_call_id":"tool-2","tool":"bash",""" +
            """"arguments":{"command":"npm install"},"description":"Install npm packages"}"""

    private suspend fun awaitingApproval(): App {
        val (app, _) = connectedMidTurn()
        relay.emit(approvalNeeded)
        pumpUntil("the approval card to show") { app.items.any { it is ChatItem.ApprovalNeeded } }
        return app
    }

    private fun App.card(): ChatItem.ApprovalNeeded = items.filterIsInstance<ChatItem.ApprovalNeeded>().single()

    private fun sent(type: String): List<JsonObject> =
        relay.sockets.flatMap { it.sent }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.string("type") == type }

    /** Answers the card and returns the frame that was sent. */
    private suspend fun answer(approved: Boolean, scope: String, mode: String? = null, feedback: String? = null): JsonObject {
        val app = awaitingApproval()
        app.vm.respondToApproval(app.card().id, approved, scope, mode, feedback)
        pumpUntil("the answer to reach the socket") { sent("APPROVAL_RESPONSE").isNotEmpty() }
        return sent("APPROVAL_RESPONSE").single()
    }

    private fun JsonObject.answerFields(): Map<String, Any?> = mapOf(
        "approved" to this["approved"]?.jsonPrimitive?.booleanOrNull,
        "scope" to string("scope"),
        "mode" to string("mode"),
        "feedback" to string("feedback")
    )

    @Test
    fun `Allow once approves this call only`() = runBlocking {
        assertEquals(
            mapOf("approved" to true, "scope" to "once", "mode" to null, "feedback" to null),
            answer(approved = true, scope = "once").answerFields()
        )
    }

    @Test
    fun `Trust for this session approves with session scope`() = runBlocking {
        assertEquals(
            mapOf("approved" to true, "scope" to "session", "mode" to null, "feedback" to null),
            answer(approved = true, scope = "session").answerFields()
        )
    }

    @Test
    fun `Reject is a soft reject carrying the reason`() = runBlocking {
        assertEquals(
            mapOf("approved" to false, "scope" to "once", "mode" to "reject_soft", "feedback" to "use yarn instead"),
            answer(approved = false, scope = "once", mode = "reject_soft", feedback = "use yarn instead").answerFields()
        )
    }

    @Test
    fun `Reject with no reason leaves feedback out of the frame`() = runBlocking {
        val frame = answer(approved = false, scope = "once", mode = "reject_soft")
        assertTrue("an absent reason is not sent at all: $frame", !frame.containsKey("feedback"))
    }

    @Test
    fun `Stop is a hard reject`() = runBlocking {
        assertEquals(
            mapOf("approved" to false, "scope" to "once", "mode" to "reject_hard", "feedback" to null),
            answer(approved = false, scope = "once", mode = "reject_hard").answerFields()
        )
    }

    @Test
    fun `Explain asks for an explanation`() = runBlocking {
        assertEquals(
            mapOf("approved" to false, "scope" to "once", "mode" to "reject_explain", "feedback" to null),
            answer(approved = false, scope = "once", mode = "reject_explain").answerFields()
        )
    }

    @Test
    fun `the answer is signed for the agent`() = runBlocking {
        val frame = answer(approved = true, scope = "once")
        val payload = frame["payload"]?.jsonObject
        assertNotNull("APPROVAL_RESPONSE is a signed command: $frame", payload)
        assertEquals(agent, payload!!.string("to"))
        assertEquals(true, payload["approved"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(frame.containsKey("signature"))
    }

    @Test
    fun `a card answers once, and keeps the answer as its record`() = runBlocking {
        val app = awaitingApproval()
        val id = app.card().id

        app.vm.respondToApproval(id, approved = true, scope = "once")
        app.vm.respondToApproval(id, approved = false, scope = "once", mode = "reject_hard")
        pumpUntil("the answer to reach the socket") { sent("APPROVAL_RESPONSE").isNotEmpty() }
        pumpFor(1_000L)

        assertEquals("a second tap must not send a second answer", 1, sent("APPROVAL_RESPONSE").size)
        assertEquals(true, app.card().decision?.approved)
    }

    @Test
    fun `the answer names the request it answers`() = runBlocking {
        val frame = answer(approved = true, scope = "once")
        knownIssue("W16AAPPLE-107: APPROVAL_RESPONSE has no request_id, so a second device makes the Host refuse it") {
            assertEquals("the id of the approval_needed being answered", "req-1", frame.string("request_id"))
        }
    }

    @Test
    fun `an ask_user answer names the question it answers`() = runBlocking {
        val (app, _) = connectedMidTurn()
        relay.emit("""{"type":"ask_user","id":"ask-1","question":"Which branch?","options":["main","dev"]}""")
        pumpUntil("the question to show") { app.items.any { it is ChatItem.AskUser } }

        app.vm.respond("dev")
        pumpUntil("the answer to reach the socket") { sent("ASK_USER_RESPONSE").isNotEmpty() }
        val frame = sent("ASK_USER_RESPONSE").single()
        assertEquals("dev", frame.string("answer"))
        knownIssue("W16AAPPLE-107: ASK_USER_RESPONSE has no request_id, so a second device makes the Host refuse it") {
            assertEquals("the id of the ask_user being answered", "ask-1", frame.string("request_id"))
        }
    }

    // ── Ultra work's budget ─────────────────────────────────────────────

    @Test
    fun `Ultra work drops back to Safe when its budget runs out`() = runBlocking {
        val app = connectedIdle()
        assertEquals("a legacy Host starts in Safe", ApprovalMode.SAFE, app.vm.approvalMode.value)

        app.vm.setApprovalMode(ApprovalMode.ULW, turns = 3)
        pumpUntil("mode_change to reach the socket") { sent("mode_change").isNotEmpty() }
        val change = sent("mode_change").single()
        assertEquals("ulw", change.string("mode"))
        assertEquals(3, change["turns"]?.jsonPrimitive?.intOrNull)

        relay.emit("""{"type":"mode_changed","id":"m1","mode":"ulw","triggered_by":"user"}""")
        pumpUntil("Ultra work to be confirmed") { app.vm.approvalMode.value == ApprovalMode.ULW && !app.vm.modePending.value }

        // What the legacy ulw plugin sends when a hosted budget runs out.
        relay.emit("""{"type":"ulw_turns_reached","id":"u1","turns_used":3,"max_turns":3}""")
        relay.emit("""{"type":"mode_changed","id":"m2","mode":"safe","triggered_by":"ulw_checkpoint"}""")
        pumpUntil("the chip to drop back to Safe") { app.vm.approvalMode.value == ApprovalMode.SAFE }

        assertTrue(app.items.any { it is ChatItem.UlwTurnsReachedItem && it.turnsUsed == 3 && it.maxTurns == 3 })
        assertEquals("no budget is left showing", null, app.vm.sessionModes.value.turnsLeft)
    }

    @Test
    fun `choosing Safe at the checkpoint sends the switch the Host waits for`() = runBlocking {
        val app = connectedIdle()
        relay.emit("""{"type":"ulw_turns_reached","id":"u1","turns_used":100,"max_turns":100}""")
        pumpUntil("the checkpoint to show") { app.items.any { it is ChatItem.UlwTurnsReachedItem } }

        app.vm.respondToUlwTurnsReached("switch_mode", null, "safe")
        pumpUntil("the choice to reach the socket") { sent("ULW_RESPONSE").isNotEmpty() }

        val frame = sent("ULW_RESPONSE").single()
        assertEquals("switch_mode", frame.string("action"))
        assertEquals("safe", frame.string("mode"))
        assertTrue("turns belongs to continue alone: $frame", frame["turns"] == null || frame["turns"] == JsonNull)
    }
}
