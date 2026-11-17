package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.ExecOutcome
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.testing.FakeOipHost.Companion.string
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EXEC's failures, end to end against a simulated Host (W16AAPPLE-101): the app
 * connected through the relay, a direct tool command from the developer tools
 * panel, and each answer the Host can give, in the shapes connectonion sends
 * them (`ws_router/exec.py`, `host/server.py`, `host/http_router.py`,
 * `ws_router/session.py`).
 *
 * Every refusal should read the same on the card: Error, with the Host's own
 * reason, as soon as it arrives. Not "Running..." until the two-minute
 * timeout, and not a "Connection failed" banner on a connection that is fine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecErrorFramesTest : RelayResumeTestBase() {

    private val tool = "bash"
    private val args = """{"command":"co status"}"""

    /** Run [tool] and return the exec_id the Host saw. */
    private suspend fun App.exec(): String {
        val before = relay.execs.size
        vm.executeTool(tool, args)
        pumpUntil("the EXEC to reach the Host") { relay.execs.size > before }
        return relay.execs.last().string("exec_id") ?: error("an EXEC carries its exec_id at the top level")
    }

    private fun App.card(execId: String) = vm.execItems.value.single { it.execId == execId }

    private suspend fun App.settled(execId: String): ExecOutcome {
        pumpUntil("the card for $execId to settle") { card(execId).outcome != null }
        return card(execId).outcome!!
    }

    private fun ChatItem.showsAnError(): Boolean =
        (this is ChatItem.Thinking && status == ThinkingStatus.ERROR) ||
            (this is ChatItem.Turn && thinking?.status == ThinkingStatus.ERROR)

    /** A refusal pinned on the EXEC: shown as refused, and replaced if the EXEC's own answer comes after all. */
    private fun assertRefused(outcome: ExecOutcome, message: String) {
        assertEquals("refused, with the Host's reason", ExecOutcome.Refused(message), outcome)
    }

    private fun assertError(outcome: ExecOutcome, message: String, durationMs: Double? = null) {
        assertTrue("an Error, with the Host's reason: $outcome", outcome is ExecOutcome.Error)
        outcome as ExecOutcome.Error
        assertEquals(message, outcome.message)
        assertEquals(durationMs, outcome.durationMs)
    }

    // -- EXEC_RESULT with status "error" ---------------------------------

    @Test
    fun `a tool the Host does not allow comes back as an error with its reason`() = runBlocking {
        val app = connectedIdle()
        val id = app.exec()
        val reason = "blocked: bash is not in permissions. Allow it by adding a rule to .co/host.yaml permissions."

        relay.reply("""{"type":"EXEC_RESULT","exec_id":"$id","tool":"bash","status":"error","error":"$reason"}""")

        assertError(app.settled(id), reason)
    }

    @Test
    fun `a caller the Host will not run tools for comes back as an error`() = runBlocking {
        val app = connectedIdle()
        val id = app.exec()
        val reason = "forbidden: exec requires a contact or admin, you are stranger"

        relay.reply("""{"type":"EXEC_RESULT","exec_id":"$id","tool":"bash","status":"error","error":"$reason"}""")

        assertError(app.settled(id), reason)
    }

    @Test
    fun `a tool the agent does not have comes back as an error`() = runBlocking {
        val app = connectedIdle()
        val id = app.exec()
        val reason = "unknown tool 'bash' (available: ['read_file', 'write_file'])"

        relay.reply("""{"type":"EXEC_RESULT","exec_id":"$id","tool":"bash","status":"error","error":"$reason"}""")

        assertError(app.settled(id), reason)
    }

    @Test
    fun `a tool that raised comes back as an error with how long it ran`() = runBlocking {
        val app = connectedIdle()
        val id = app.exec()
        val reason = "CalledProcessError: Command 'co status' returned non-zero exit status 2."

        relay.reply(
            """{"type":"EXEC_RESULT","exec_id":"$id","tool":"bash","status":"error","error":"$reason","duration_ms":12}"""
        )

        assertError(app.settled(id), reason, durationMs = 12.0)
    }

    @Test
    fun `an EXEC the Host found no tool name in comes back as an error`() = runBlocking {
        // The one EXEC_RESULT that names no tool.
        val app = connectedIdle()
        val id = app.exec()

        relay.reply("""{"type":"EXEC_RESULT","exec_id":"$id","status":"error","error":"tool required"}""")

        assertError(app.settled(id), "tool required")
    }

    @Test
    fun `an error for one command settles only that command`() = runBlocking {
        val app = connectedIdle()
        val first = app.exec()
        val second = app.exec()

        relay.reply("""{"type":"EXEC_RESULT","exec_id":"$second","tool":"bash","status":"error","error":"tool required"}""")
        assertError(app.settled(second), "tool required")
        assertNull("the other command is still running", app.card(first).outcome)

        relay.reply("""{"type":"EXEC_RESULT","exec_id":"$first","tool":"bash","status":"success","result":"ok"}""")
        assertEquals(ExecOutcome.Success("ok"), app.settled(first))
    }

    // -- a plain ERROR, which names no exec_id ---------------------------

    @Test
    fun `a signed-command refusal of the EXEC just sent settles its card, not the connection`() = runBlocking {
        // session.py checks every signed command before reading the next frame,
        // so with the EXEC the last command sent, the refusal can only be its.
        val app = connectedIdle()
        val id = app.exec()
        val reason = "unauthorized: signed command already used"

        relay.reply("""{"type":"ERROR","message":"$reason"}""")

        assertRefused(app.settled(id), reason)
        pumpFor(1_000L)
        assertTrue("the connection is still up: ${app.state}", app.state is ConnectionState.Connected)
        assertTrue("and no failed item in the chat repeats it: ${app.items}", app.items.none { it.showsAnError() })
    }

    @Test
    fun `an ERROR that is not a command refusal leaves a waiting EXEC alone`() = runBlocking {
        // Only the Host's command gate answers for one command; anything else
        // says nothing about the EXEC.
        val app = connectedIdle()
        val id = app.exec()

        relay.reply("""{"type":"ERROR","message":"Agent crashed while thinking"}""")
        pumpFor(1_000L)

        assertNull("the EXEC is still waiting on its own answer", app.card(id).outcome)
    }

    @Test
    fun `a refusal after a chat message was sent is not pinned on the EXEC before it`() = runBlocking {
        // It may be the message's: the EXEC is left to its own answer or timeout.
        val app = connectedIdle()
        val id = app.exec()
        app.vm.sendMessage("and what about the logs?")
        pumpUntil("the message to reach the Host") { relay.inputs.isNotEmpty() }

        relay.reply("""{"type":"ERROR","message":"unauthorized: signed command already used"}""")
        pumpFor(1_000L)

        assertNull("the EXEC is still waiting on its own answer", app.card(id).outcome)
    }

    @Test
    fun `a refusal while two EXECs wait is pinned on neither`() = runBlocking {
        // The first may be running and the second refused, or the other way round.
        val app = connectedIdle()
        val first = app.exec()
        val second = app.exec()

        relay.reply("""{"type":"ERROR","message":"unauthorized: signed command already used"}""")
        pumpFor(1_000L)

        assertNull(app.card(first).outcome)
        assertNull(app.card(second).outcome)
    }

    @Test
    fun `once the earlier EXEC has answered, a refusal is the later one's`() = runBlocking {
        val app = connectedIdle()
        val first = app.exec()
        val second = app.exec()
        relay.reply("""{"type":"EXEC_RESULT","exec_id":"$first","tool":"bash","status":"success","result":"ok"}""")
        app.settled(first)

        relay.reply("""{"type":"ERROR","message":"forbidden: blacklisted"}""")

        assertRefused(app.settled(second), "forbidden: blacklisted")
        assertEquals(ExecOutcome.Success("ok"), app.card(first).outcome)
    }

    @Test
    fun `a refusal of a chat message sent just before the EXEC reaches the chat, not the card`() = runBlocking {
        // The INPUT went first, so the Host read it first, and its refusal can
        // reach the phone after the EXEC has gone. It is the message's, so the
        // chat must show it; the EXEC waits for its own answer (review of !90).
        val app = connectedIdle()
        app.vm.sendMessage("and what about the logs?")
        pumpUntil("the message to reach the Host") { relay.inputs.isNotEmpty() }
        val id = app.exec()

        relay.reply("""{"type":"ERROR","message":"unauthorized: signed command already used"}""")
        pumpUntil("the refusal to show in the chat") { app.items.any { it.showsAnError() } }

        assertNull("the EXEC is still waiting on its own answer", app.card(id).outcome)
    }

    @Test
    fun `an EXEC's own answer after a refusal was pinned on it replaces the refusal`() = runBlocking {
        // The pin is the client's best reading; the Host's EXEC_RESULT is the truth.
        val app = connectedIdle()
        val id = app.exec()
        relay.reply("""{"type":"ERROR","message":"unauthorized: signed command already used"}""")
        assertRefused(app.settled(id), "unauthorized: signed command already used")

        relay.reply("""{"type":"EXEC_RESULT","exec_id":"$id","tool":"bash","status":"success","result":"ok"}""")

        pumpUntil("the real answer to replace the refusal") { app.card(id).outcome == ExecOutcome.Success("ok") }
    }
}
