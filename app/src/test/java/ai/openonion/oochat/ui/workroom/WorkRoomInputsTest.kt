package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderInput
import ai.openonion.oochat.domain.model.ProviderInputAck
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W16AAPPLE-35: a Work Room draft clears only on a positive PROVIDER_INPUT_ACK. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkRoomInputsTest {

    private val run = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        provider = "codex",
        status = ProviderInvocationStatus.RUNNING,
        stateRevision = 4
    )

    private val sent = mutableListOf<ProviderInput>()
    private var wentOut = true
    private var ids = 0

    private fun TestScope.inputs() = WorkRoomInputs(
        scope = backgroundScope,
        send = { sent += it; wentOut },
        ackTimeoutMs = 1_000,
        newRequestId = { "req-${++ids}" }
    )

    private fun WorkRoomInputs.draft() = drafts.value[run.id] ?: WorkRoomDraft()

    private fun accepted(requestId: String = "req-1") =
        ProviderInputAck(requestId = requestId, invocationId = run.id, accepted = true, stateRevision = 4)

    private fun refused(reason: String, requestId: String = "req-1") =
        ProviderInputAck(requestId = requestId, invocationId = run.id, accepted = false, reason = reason)

    @Test
    fun `a send carries the run, the revision it was seen at and the text, and clears on acceptance`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "Use the existing auth helper")

        inputs.send(run)
        runCurrent()

        assertEquals(listOf(ProviderInput("codex:call-1", 4, "Use the existing auth helper", "req-1")), sent)
        assertTrue("locked while the provider has not taken it", inputs.draft().sending)

        inputs.onAck(accepted())

        assertEquals(WorkRoomDraft(), inputs.draft())
    }

    @Test
    fun `a refusal keeps the draft and says why`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "Use the existing auth helper")
        inputs.send(run)
        runCurrent()

        inputs.onAck(refused("state_changed"))

        assertEquals("Use the existing auth helper", inputs.draft().text)
        assertFalse(inputs.draft().sending)
        assertEquals(WorkRoomText.inputRefused("state_changed"), inputs.draft().error)
    }

    @Test
    fun `no answer in time keeps the draft and says so`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "Use the existing auth helper")
        inputs.send(run)
        runCurrent()

        advanceTimeBy(1_001)

        assertEquals("Use the existing auth helper", inputs.draft().text)
        assertFalse(inputs.draft().sending)
        assertEquals(WorkRoomText.INPUT_NO_ANSWER, inputs.draft().error)
    }

    @Test
    fun `a late acceptance still clears the draft it sent`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "Use the existing auth helper")
        inputs.send(run)
        runCurrent()
        advanceTimeBy(1_001)

        inputs.onAck(accepted())

        assertEquals(WorkRoomDraft(), inputs.draft())
    }

    @Test
    fun `a late acceptance leaves a draft the user has since rewritten`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "Use the existing auth helper")
        inputs.send(run)
        runCurrent()
        advanceTimeBy(1_001)
        inputs.edit(run.id, "Actually, write a new one")

        inputs.onAck(accepted())

        assertEquals("Actually, write a new one", inputs.draft().text)
        assertNull(inputs.draft().error)
    }

    @Test
    fun `a send that cannot go out keeps the draft`() = runTest {
        wentOut = false
        val inputs = inputs()
        inputs.edit(run.id, "Use the existing auth helper")

        inputs.send(run)
        runCurrent()

        assertEquals("Use the existing auth helper", inputs.draft().text)
        assertFalse(inputs.draft().sending)
        assertEquals(WorkRoomText.INPUT_NOT_CONNECTED, inputs.draft().error)
    }

    @Test
    fun `an answer to another request changes nothing`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "Use the existing auth helper")
        inputs.send(run)
        runCurrent()

        inputs.onAck(accepted(requestId = "someone-else"))

        assertEquals("Use the existing auth helper", inputs.draft().text)
        assertTrue(inputs.draft().sending)
    }

    @Test
    fun `an answer to an earlier send is not taken for the one after it`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "first")
        inputs.send(run)
        runCurrent()
        advanceTimeBy(1_001)
        inputs.edit(run.id, "second")
        inputs.send(run)
        runCurrent()

        inputs.onAck(accepted(requestId = "req-1"))

        assertEquals("second", inputs.draft().text)
        assertTrue("still waiting on req-2", inputs.draft().sending)
    }

    @Test
    fun `the field is locked while sending and a second send waits`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "Use the existing auth helper")
        inputs.send(run)
        runCurrent()

        inputs.edit(run.id, "changed mid-send")
        inputs.send(run)
        runCurrent()

        assertEquals("Use the existing auth helper", inputs.draft().text)
        assertEquals(1, sent.size)
    }

    @Test
    fun `nothing typed sends nothing`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "   ")

        inputs.send(run)
        runCurrent()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `drafts are kept per room`() = runTest {
        val inputs = inputs()
        inputs.edit(run.id, "for codex")
        inputs.edit("claude_code:call-2", "for claude")

        assertEquals("for codex", inputs.drafts.value[run.id]?.text)
        assertEquals("for claude", inputs.drafts.value["claude_code:call-2"]?.text)
    }

    @Test
    fun `a Claude Code run still working takes no message, since the Host steers only Codex`() = runTest {
        val claude = run.copy(id = "claude_code:call-2", provider = "claude_code")
        val inputs = inputs()
        inputs.edit(claude.id, "stop and explain")

        inputs.send(claude)
        runCurrent()

        assertTrue(sent.isEmpty())
        assertEquals(WorkRoomText.INPUT_CLAUDE_BUSY, inputs.drafts.value[claude.id]?.error)
        assertNull(WorkRoomInputs.blockedReason(claude.copy(status = ProviderInvocationStatus.COMPLETED)))
    }

    @Test
    fun `a run without a revision takes no message, since the Host requires one`() = runTest {
        val unrevised = run.copy(stateRevision = null)
        val inputs = inputs()
        inputs.edit(run.id, "hello")

        inputs.send(unrevised)
        runCurrent()

        assertTrue(sent.isEmpty())
        assertEquals(WorkRoomText.INPUT_NOT_READY, inputs.draft().error)
    }

    @Test
    fun `a draft is cut at the Host's limit`() = runTest {
        val inputs = inputs()

        inputs.edit(run.id, "x".repeat(12_001))

        assertEquals(12_000, inputs.draft().text.length)
    }
}
