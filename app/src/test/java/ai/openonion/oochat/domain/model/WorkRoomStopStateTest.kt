package ai.openonion.oochat.domain.model

import ai.openonion.oochat.domain.model.WorkRoomStopState.Confirming
import ai.openonion.oochat.domain.model.WorkRoomStopState.Idle
import ai.openonion.oochat.domain.model.WorkRoomStopState.Sending
import ai.openonion.oochat.domain.model.WorkRoomStopState.Stopping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W16AAPPLE-37: the Work Room Stop's own state machine, with its confirmation barrier. */
class WorkRoomStopStateTest {

    @Test
    fun `tapping Stop opens the barrier and sends nothing`() {
        val transition = Idle.on(WorkRoomStopEvent.RequestStop(runIsLive = true))

        assertEquals(Confirming, transition.state)
        assertFalse(transition.send)
    }

    @Test
    fun `only a confirmation inside the barrier sends the Stop`() {
        val transition = Confirming.on(WorkRoomStopEvent.Confirm)

        assertEquals(Sending, transition.state)
        assertTrue(transition.send)
    }

    @Test
    fun `the barrier closing, or backing out, sends nothing`() {
        assertEquals(WorkRoomStopTransition(Idle), Confirming.on(WorkRoomStopEvent.BarrierExpired))
        assertEquals(WorkRoomStopTransition(Idle), Confirming.on(WorkRoomStopEvent.Cancel))
    }

    @Test
    fun `a confirmation with no barrier open does nothing`() {
        listOf(Idle, Sending, Stopping).forEach { state ->
            assertEquals(state.toString(), WorkRoomStopTransition(state), state.on(WorkRoomStopEvent.Confirm))
        }
    }

    @Test
    fun `a run that has ended cannot be stopped`() {
        assertEquals(WorkRoomStopTransition(Idle), Idle.on(WorkRoomStopEvent.RequestStop(runIsLive = false)))
    }

    @Test
    fun `nothing else goes out while a Stop waits for its answer`() {
        assertEquals(WorkRoomStopTransition(Sending), Sending.on(WorkRoomStopEvent.RequestStop(runIsLive = true)))
        assertEquals(WorkRoomStopTransition(Sending), Sending.on(WorkRoomStopEvent.Cancel))
    }

    @Test
    fun `an accepted Stop waits for the run to end, and a failed one can be tried again`() {
        assertEquals(Stopping, Sending.on(WorkRoomStopEvent.Accepted).state)
        assertEquals(Idle, Sending.on(WorkRoomStopEvent.Failed).state)
        assertEquals("a late barrier timer changes nothing", Stopping, Stopping.on(WorkRoomStopEvent.BarrierExpired).state)
    }

    @Test
    fun `the barrier is the spec's 15 seconds`() {
        assertEquals(15_000L, WorkRoomStopState.BARRIER_MS)
    }
}
