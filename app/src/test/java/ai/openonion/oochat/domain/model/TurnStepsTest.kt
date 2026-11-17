package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnStepsTest {

    private fun user(id: String) = ChatItem.User(id = id, content = "q $id")
    private fun thought(id: String, status: ThinkingStatus = ThinkingStatus.DONE) =
        ChatItem.Thinking(id = id, status = status, content = "thought $id", kind = "reflect")
    private fun tool(id: String, status: ToolStatus = ToolStatus.DONE, timingMs: Long? = null) =
        ChatItem.ToolCall(id = id, name = "bash", status = status, timingMs = timingMs)
    private fun call(id: String, status: ThinkingStatus = ThinkingStatus.DONE, tokens: Int? = null, cost: Double? = null) =
        ChatItem.Turn(id = id, thinking = ChatItem.Thinking(id = id, status = status, tokensTotal = tokens, costUsd = cost))
    private fun reply(id: String, tokens: Int? = null, cost: Double? = null) = ChatItem.Turn(
        id = id,
        thinking = ChatItem.Thinking(id = id, status = ThinkingStatus.DONE, tokensTotal = tokens, costUsd = cost, durationMs = 900.0),
        agent = ChatItem.Agent(id = id, content = "a $id")
    )

    @Test
    fun `a finished turn's steps fold into its first one, above the answer`() {
        val items = listOf(user("u1"), thought("t1"), call("l1"), tool("c1"), call("l2"), tool("c2"), reply("r1"))

        val grouped = items.groupTurnSteps()

        assertEquals(listOf("u1", "t1", "r1"), grouped.visible.map { it.id })
        val turn = grouped.turns.getValue("t1")
        assertEquals(listOf("t1", "c1", "c2"), turn.steps.map { it.id })
        assertFalse(turn.running)
        assertTrue(turn.collapsedByDefault)
        assertEquals("u1", turn.startId)
        assertEquals("r1", turn.endId)
    }

    @Test
    fun `steps that came after the answer's row still sit above it`() {
        val items = listOf(user("u1"), reply("r1"), tool("c1"), tool("c2"))

        assertEquals(listOf("u1", "c1", "r1"), items.groupTurnSteps().visible.map { it.id })
    }

    @Test
    fun `the newest turn is running while any of it is in flight`() {
        val items = listOf(user("u1"), tool("c1"), call("l1", ThinkingStatus.RUNNING))

        val turn = items.groupTurnSteps().turns.getValue("c1")

        assertTrue(turn.running)
        assertFalse(turn.collapsedByDefault)
    }

    @Test
    fun `a turn waiting on its Work Room or on the user is still running`() {
        val run = ChatItem.ProviderInvocation(id = "w1", status = ProviderInvocationStatus.AWAITING_APPROVAL)
        val question = ChatItem.AskUser(id = "q1", text = "Which one?", options = listOf("a", "b"), multiSelect = false)

        assertTrue(listOf(user("u1"), tool("c1"), run).groupTurnSteps().turns.getValue("c1").running)
        assertTrue(listOf(user("u1"), tool("c1"), question).groupTurnSteps().turns.getValue("c1").running)
        assertFalse(
            listOf(user("u1"), tool("c1"), run.copy(status = ProviderInvocationStatus.COMPLETED), reply("r1"))
                .groupTurnSteps().turns.getValue("c1").running
        )
    }

    @Test
    fun `a stale running row in an older turn is history`() {
        val items = listOf(user("u1"), tool("c1", ToolStatus.RUNNING), reply("r1"), user("u2"), reply("r2"))

        assertFalse(items.groupTurnSteps().turns.getValue("c1").running)
    }

    @Test
    fun `a finished turn with no answer stays open`() {
        val items = listOf(user("u1"), tool("c1"), tool("c2", ToolStatus.ERROR))

        val turn = items.groupTurnSteps().turns.getValue("c1")

        assertFalse(turn.collapsedByDefault)
        assertEquals(1, turn.failedCount)
    }

    @Test
    fun `a failed llm call stays visible to say what went wrong`() {
        val items = listOf(user("u1"), tool("c1"), call("l1", ThinkingStatus.ERROR))

        assertEquals(listOf("u1", "c1", "l1"), items.groupTurnSteps().visible.map { it.id })
    }

    @Test
    fun `the agent's read of the request and answered approvals are steps`() {
        val intent = ChatItem.IntentItem(id = "i1", status = IntentStatus.UNDERSTOOD, ack = "On it")
        val answered = ChatItem.ApprovalNeeded(
            id = "a1",
            tool = "bash",
            arguments = emptyMap(),
            decision = ApprovalDecision(approved = true, scope = "session", mode = null)
        )
        val items = listOf(user("u1"), intent, tool("c1"), answered, reply("r1"))

        val grouped = items.groupTurnSteps()

        assertEquals(listOf("u1", "i1", "r1"), grouped.visible.map { it.id })
        assertEquals(listOf("i1", "c1", "a1"), grouped.turns.getValue("i1").steps.map { it.id })
    }

    @Test
    fun `an answered Work Room approval stays, since it is how that run is reached`() {
        val roomApproval = ChatItem.ApprovalNeeded(
            id = "a1",
            tool = "claude_code",
            arguments = emptyMap(),
            decision = ApprovalDecision(approved = true, scope = "once", mode = null),
            workRoom = WorkRoomApproval(provider = "claude_code", invocationId = "w1", parentToolCallId = "c0")
        )
        val items = listOf(user("u1"), tool("c1"), roomApproval, tool("c2"), reply("r1"))

        val grouped = items.groupTurnSteps()

        assertEquals(listOf("u1", "c1", "a1", "r1"), grouped.visible.map { it.id })
        assertEquals(listOf("c1", "c2"), grouped.turns.getValue("c1").steps.map { it.id })
    }

    @Test
    fun `every folded row names the block that now stands for it`() {
        val items = listOf(user("u1"), tool("c1"), call("l1"), tool("c2"), reply("r1"))

        val folded = items.groupTurnSteps().foldedInto

        assertEquals(mapOf("c2" to "c1", "l1" to "c1"), folded)
    }

    @Test
    fun `gates and work room cards are never folded away`() {
        val approval = ChatItem.ApprovalNeeded(id = "a1", tool = "bash", arguments = emptyMap())
        val room = ChatItem.ProviderInvocation(id = "w1")
        val items = listOf(user("u1"), tool("c1"), room, tool("c2"), approval)

        assertEquals(listOf("u1", "c1", "w1", "a1"), items.groupTurnSteps().visible.map { it.id })
    }

    @Test
    fun `the answer's footer carries the whole turn's usage`() {
        val items = listOf(user("u1"), call("l1", tokens = 100, cost = 0.01), tool("c1"), reply("r1", tokens = 50, cost = 0.005))

        val footer = items.groupTurnSteps().footers.getValue("r1")

        assertEquals(150, footer.tokensTotal)
        assertEquals(0.015, footer.costUsd!!, 1e-9)
        assertNull(footer.durationMs)
    }

    @Test
    fun `reported timings are summed as the fallback duration`() {
        val items = listOf(user("u1"), tool("c1", timingMs = 2_000), tool("c2", timingMs = 3_000), reply("r1"))

        assertEquals(5_900L, items.groupTurnSteps().turns.getValue("c1").workMs)
    }

    @Test
    fun `turns are split at the user's messages`() {
        val items = listOf(user("u1"), tool("c1"), reply("r1"), user("u2"), tool("c2"), tool("c3"), reply("r2"))

        val grouped = items.groupTurnSteps()

        assertEquals(listOf("u1", "c1", "r1", "u2", "c2", "r2"), grouped.visible.map { it.id })
        assertEquals(listOf("c2", "c3"), grouped.turns.getValue("c2").steps.map { it.id })
    }

    @Test
    fun `a conversation without steps is returned as it was`() {
        val items = listOf(user("u1"), reply("r1"), user("u2"), reply("r2"))

        val grouped = items.groupTurnSteps()

        assertSame(items, grouped.visible)
        assertTrue(grouped.turns.isEmpty())
    }
}
