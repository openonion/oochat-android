package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderStop
import ai.openonion.oochat.domain.model.ProviderStopAck
import ai.openonion.oochat.domain.model.WorkRoomStopEvent
import ai.openonion.oochat.domain.model.WorkRoomStopState
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** One Work Room's Stop: where its state machine stands, and why the last Stop did not go through. */
data class WorkRoomStopUi(
    val state: WorkRoomStopState = WorkRoomStopState.Idle,
    val error: String? = null
)

/**
 * The Work Rooms' scoped Stops (W16AAPPLE-37), one per run. Every step goes
 * through [WorkRoomStopState], so Stop is sent only from a confirmation made
 * inside its 15-second barrier, which this closes on time.
 *
 * Owned by the chat's ViewModel, like [WorkRoomInputs], so a Stop already
 * sent is still settled if the user leaves the room. Not thread-safe: called
 * from the ViewModel's scope only.
 */
internal class WorkRoomStops(
    private val scope: CoroutineScope,
    private val send: suspend (ProviderStop) -> Boolean,
    private val barrierMs: Long = WorkRoomStopState.BARRIER_MS,
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() }
) {
    private val _rooms = MutableStateFlow<Map<String, WorkRoomStopUi>>(emptyMap())

    /** Each room's Stop, by the key supplied by its owner. A room never touched has no entry. */
    val rooms: StateFlow<Map<String, WorkRoomStopUi>> = _rooms.asStateFlow()

    /** The barrier timer, or the request waiting on its ACK, per room. */
    private val timers = mutableMapOf<String, Job>()
    private val awaiting = mutableMapOf<String, String>()

    /** The user tapped Stop in [run]'s room: open the barrier. */
    fun requestStop(run: ChatItem.ProviderInvocation, roomKey: String = run.id) {
        val transition = state(roomKey).on(WorkRoomStopEvent.RequestStop(runIsLive = !run.status.isTerminal))
        if (transition.state != WorkRoomStopState.Confirming) return
        set(roomKey, WorkRoomStopUi(WorkRoomStopState.Confirming))
        timers.remove(roomKey)?.cancel()
        timers[roomKey] = scope.launch {
            delay(barrierMs)
            if (state(roomKey) == WorkRoomStopState.Confirming) {
                timers.remove(roomKey)
                set(roomKey, WorkRoomStopUi(state(roomKey).on(WorkRoomStopEvent.BarrierExpired).state))
            }
        }
    }

    /** The user backed out of the confirmation. */
    fun cancel(roomKey: String) {
        if (state(roomKey) != WorkRoomStopState.Confirming) return
        timers.remove(roomKey)?.cancel()
        set(roomKey, WorkRoomStopUi(state(roomKey).on(WorkRoomStopEvent.Cancel).state))
    }

    /** The user confirmed: send the Stop for [run], at the revision on screen now. */
    fun confirm(run: ChatItem.ProviderInvocation, roomKey: String = run.id) {
        val transition = state(roomKey).on(WorkRoomStopEvent.Confirm)
        if (!transition.send) return
        timers.remove(roomKey)?.cancel()
        // It may have ended while the confirmation was open: then there is nothing to stop.
        if (run.status.isTerminal) {
            set(roomKey, WorkRoomStopUi())
            return
        }
        val revision = run.stateRevision ?: run {
            set(roomKey, WorkRoomStopUi(error = WorkRoomText.STOP_NOT_READY))
            return
        }
        val requestId = newRequestId()
        awaiting[roomKey] = requestId
        set(roomKey, WorkRoomStopUi(transition.state))
        timers[roomKey] = scope.launch {
            val wentOut = send(ProviderStop(run.id, revision, requestId))
            if (awaiting[roomKey] != requestId) return@launch
            if (!wentOut) {
                fail(roomKey, WorkRoomText.STOP_NOT_CONNECTED)
                return@launch
            }
            delay(ackTimeoutMs)
            if (awaiting[roomKey] == requestId) {
                FileLogger.w(LogTags.CHAT_VM, "No PROVIDER_INTERRUPT_ACK for ${requestId.take(8)} in ${ackTimeoutMs}ms")
                fail(roomKey, WorkRoomText.STOP_NO_ANSWER)
            }
        }
    }

    /** Settle the Stop whose request [ack] answers. An answer to no request of ours is ignored. */
    fun onAck(ack: ProviderStopAck) {
        val room = awaiting.entries.firstOrNull { it.value == ack.requestId }?.key ?: run {
            FileLogger.w(LogTags.CHAT_VM, "Ignoring PROVIDER_INTERRUPT_ACK for unknown request ${ack.requestId.take(8)}")
            return
        }
        if (ack.accepted) {
            awaiting.remove(room)
            timers.remove(room)?.cancel()
            set(room, WorkRoomStopUi(state(room).on(WorkRoomStopEvent.Accepted).state))
        } else {
            fail(room, WorkRoomText.stopRefused(ack.reason))
        }
    }

    /** Forget rooms that belong to a deleted or cleared conversation. */
    fun discardMatching(matches: (String) -> Boolean) {
        (timers.keys + awaiting.keys).filter(matches).forEach { key ->
            timers.remove(key)?.cancel()
            awaiting.remove(key)
        }
        _rooms.update { all -> all.filterKeys { !matches(it) } }
    }

    private fun fail(roomKey: String, error: String) {
        awaiting.remove(roomKey)
        timers.remove(roomKey)?.cancel()
        set(roomKey, WorkRoomStopUi(state(roomKey).on(WorkRoomStopEvent.Failed).state, error))
    }

    private fun state(roomKey: String): WorkRoomStopState = _rooms.value[roomKey]?.state ?: WorkRoomStopState.Idle

    private fun set(roomKey: String, ui: WorkRoomStopUi) {
        _rooms.update { all -> if (ui == WorkRoomStopUi()) all - roomKey else all + (roomKey to ui) }
    }

    companion object {
        /** How long a Stop waits for its ACK. The Host answers as soon as it has looked the run up. */
        const val ACK_TIMEOUT_MS = 15_000L
    }
}
