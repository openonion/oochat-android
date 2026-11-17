package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.ProviderStop
import ai.openonion.oochat.domain.model.ProviderStopAck
import ai.openonion.oochat.domain.model.WorkRoomStopState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W16AAPPLE-37: a Work Room's Stop goes out only from a confirmation inside its 15-second barrier. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkRoomStopsTest {

    private val run = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        provider = "codex",
        status = ProviderInvocationStatus.RUNNING,
        stateRevision = 4
    )

    private val sent = mutableListOf<ProviderStop>()
    private var wentOut = true
    private var ids = 0

    private fun TestScope.stops() = WorkRoomStops(
        scope = backgroundScope,
        send = { sent += it; wentOut },
        newRequestId = { "req-${++ids}" }
    )

    private fun WorkRoomStops.ui() = rooms.value[run.id] ?: WorkRoomStopUi()

    @Test
    fun `Stop then confirm sends a Stop for this run at the revision on screen`() = runTest {
        val stops = stops()

        stops.requestStop(run)
        runCurrent()
        assertTrue("nothing goes out on the first tap", sent.isEmpty())
        stops.confirm(run.copy(stateRevision = 5))
        runCurrent()

        assertEquals(listOf(ProviderStop("codex:call-1", 5, "req-1")), sent)
        assertEquals(WorkRoomStopState.Sending, stops.ui().state)
    }

    @Test
    fun `the barrier closes on its own after 15 seconds, and a confirmation then sends nothing`() = runTest {
        val stops = stops()
        stops.requestStop(run)

        advanceTimeBy(14_999)
        assertEquals("still open just before 15 seconds", WorkRoomStopState.Confirming, stops.ui().state)
        advanceTimeBy(2)
        assertEquals(WorkRoomStopState.Idle, stops.ui().state)

        stops.confirm(run)
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `Keep running closes the barrier without sending`() = runTest {
        val stops = stops()
        stops.requestStop(run)

        stops.cancel(run.id)
        stops.confirm(run)
        runCurrent()

        assertTrue(sent.isEmpty())
        assertEquals(WorkRoomStopUi(), stops.ui())
    }

    @Test
    fun `an accepted Stop waits for the run's own frame`() = runTest {
        val stops = stops()
        stops.requestStop(run)
        stops.confirm(run)
        runCurrent()

        stops.onAck(ProviderStopAck("req-1", run.id, accepted = true, stateRevision = 4))

        assertEquals(WorkRoomStopState.Stopping, stops.ui().state)
    }

    @Test
    fun `a refusal says why, and Stop can be tried again`() = runTest {
        val stops = stops()
        stops.requestStop(run)
        stops.confirm(run)
        runCurrent()

        stops.onAck(ProviderStopAck("req-1", run.id, accepted = false, reason = "state_changed"))

        assertEquals(WorkRoomStopUi(WorkRoomStopState.Idle, WorkRoomText.stopRefused("state_changed")), stops.ui())
        stops.requestStop(run)
        assertEquals(WorkRoomStopState.Confirming, stops.ui().state)
    }

    @Test
    fun `no answer in time, or no connection, says so`() = runTest {
        val stops = stops()
        stops.requestStop(run)
        stops.confirm(run)
        runCurrent()
        advanceTimeBy(WorkRoomStops.ACK_TIMEOUT_MS + 1)
        assertEquals(WorkRoomText.STOP_NO_ANSWER, stops.ui().error)

        wentOut = false
        stops.requestStop(run)
        stops.confirm(run)
        runCurrent()
        assertEquals(WorkRoomText.STOP_NOT_CONNECTED, stops.ui().error)
    }

    @Test
    fun `a run that ended while the confirmation was open is not stopped`() = runTest {
        val stops = stops()
        stops.requestStop(run)

        stops.confirm(run.copy(status = ProviderInvocationStatus.COMPLETED))
        runCurrent()

        assertTrue(sent.isEmpty())
        assertEquals(WorkRoomStopUi(), stops.ui())
    }

    @Test
    fun `a run that has ended opens no confirmation`() = runTest {
        val stops = stops()

        stops.requestStop(run.copy(status = ProviderInvocationStatus.CANCELLED))

        assertEquals(WorkRoomStopUi(), stops.ui())
    }

    @Test
    fun `each room has its own Stop, and an answer to another request is ignored`() = runTest {
        val stops = stops()
        val other = run.copy(id = "claude_code:call-2", provider = "claude_code")
        stops.requestStop(run)
        stops.confirm(run)
        runCurrent()

        stops.onAck(ProviderStopAck("not-ours", run.id, accepted = true))
        assertEquals(WorkRoomStopState.Sending, stops.ui().state)
        assertEquals("the other room is untouched", null, stops.rooms.value[other.id])
    }
}
