package ai.openonion.oochat.domain.model

/**
 * A scoped Stop sent as `PROVIDER_INTERRUPT` (W16AAPPLE-37): the one run to
 * stop, the [stateRevision] the user confirmed at (the Host refuses a Stop for
 * a run that has moved on), and the id its `PROVIDER_INTERRUPT_ACK` will
 * answer to. Only this run stops; the agent's own turn carries on.
 */
data class ProviderStop(
    val invocationId: String,
    val stateRevision: Long,
    val requestId: String
)

/**
 * The Host's answer to a [ProviderStop]. [accepted] means the Host owns that
 * exact live run and has passed the Stop on, not that it has stopped: the
 * run's own `provider_invocation` with `status: "cancelled"` says that
 * (connectonion `docs/network/websocket-protocol.md`, "Scoped native-provider
 * stop"). A refusal says why in [reason], a code.
 */
data class ProviderStopAck(
    val requestId: String,
    val invocationId: String?,
    val accepted: Boolean,
    val stateRevision: Long? = null,
    val reason: String? = null
)

/**
 * The Work Room Stop's own state machine (W16AAPPLE-37).
 *
 * Stop is scoped to the run in this Work Room and guarded by a confirmation
 * barrier: tapping Stop sends nothing, it opens a confirmation that stays open
 * for [BARRIER_MS] (15 seconds, as the project's functionality mapping
 * specifies). Only a confirmation inside that window sends the Stop; once it
 * closes, the user has to tap Stop again. So a Stop is never sent by a stray
 * tap, nor by a confirmation left open while the user looked elsewhere and
 * the room moved on.
 *
 * Once sent, nothing else is sent until the ACK. An accepted Stop waits in
 * [Stopping] for the run to end, which only the Host says.
 *
 * Pure: [on] returns the next state and whether to send the Stop now.
 */
sealed interface WorkRoomStopState {

    /** Nothing waiting on the user or the Host. */
    data object Idle : WorkRoomStopState

    /** Stop was tapped; the barrier is open. Nothing is sent until [WorkRoomStopEvent.Confirm]. */
    data object Confirming : WorkRoomStopState

    /** The Stop went out and its ACK has not come yet. */
    data object Sending : WorkRoomStopState

    /** The Host took the Stop; the run's own frame will say when it has stopped. */
    data object Stopping : WorkRoomStopState

    fun on(event: WorkRoomStopEvent): WorkRoomStopTransition = when (event) {
        is WorkRoomStopEvent.RequestStop ->
            if (this == Idle && event.runIsLive) WorkRoomStopTransition(Confirming) else WorkRoomStopTransition(this)
        WorkRoomStopEvent.Confirm ->
            if (this == Confirming) WorkRoomStopTransition(Sending, send = true) else WorkRoomStopTransition(this)
        // Backing out, or the barrier closing on its own, sends nothing.
        WorkRoomStopEvent.Cancel, WorkRoomStopEvent.BarrierExpired ->
            if (this == Confirming) WorkRoomStopTransition(Idle) else WorkRoomStopTransition(this)
        WorkRoomStopEvent.Accepted ->
            if (this == Sending) WorkRoomStopTransition(Stopping) else WorkRoomStopTransition(this)
        // Refused, not sent, or no answer: Stop can be tried again.
        WorkRoomStopEvent.Failed ->
            if (this == Sending) WorkRoomStopTransition(Idle) else WorkRoomStopTransition(this)
    }

    companion object {
        /** How long the confirmation stays open (the spec's 15-second barrier). */
        const val BARRIER_MS = 15_000L
    }
}

/** What happened to a Work Room's Stop. */
sealed interface WorkRoomStopEvent {
    /** The user tapped Stop. Ignored unless the run is still live ([runIsLive]). */
    data class RequestStop(val runIsLive: Boolean) : WorkRoomStopEvent

    /** The user confirmed inside the barrier. */
    data object Confirm : WorkRoomStopEvent

    /** The user backed out of the confirmation. */
    data object Cancel : WorkRoomStopEvent

    /** The barrier's 15 seconds ran out with no confirmation. */
    data object BarrierExpired : WorkRoomStopEvent

    /** The Host took the Stop. */
    data object Accepted : WorkRoomStopEvent

    /** The Host refused it, it could not go out, or no answer came. */
    data object Failed : WorkRoomStopEvent
}

/** The state after an event, and whether the event sends the Stop. */
data class WorkRoomStopTransition(
    val state: WorkRoomStopState,
    val send: Boolean = false
)
