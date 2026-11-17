package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderPermissionAck
import ai.openonion.oochat.domain.model.ProviderPermissionChange
import ai.openonion.oochat.domain.model.ProviderPermissionControlState
import ai.openonion.oochat.domain.model.ProviderPermissionEvent
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

/** One Work Room's provider permission control: where its state machine stands, and why the last change failed. */
data class WorkRoomPermissionUi(
    val control: ProviderPermissionControlState = ProviderPermissionControlState.Idle,
    /** Why the last change did not take. The run keeps the mode the Host last confirmed. */
    val error: String? = null
)

/**
 * The provider permission controls of the chat's Work Rooms (W16AAPPLE-43),
 * one per run, and their requests. Every step goes through
 * [ProviderPermissionControlState], the control's own state machine, so an
 * elevated choice is held for confirmation and nothing else is sent while a
 * request waits.
 *
 * Nothing here says which mode is active: that is the run's
 * `providerPermission`, which only the Host's `provider_invocation` changes. A
 * request settles on its `PROVIDER_PERMISSION_ACK`; a refusal, a send that
 * could not go out, or no answer within [ackTimeoutMs] leaves the run's mode
 * as it was and says why.
 *
 * Owned by the chat's ViewModel, like [WorkRoomInputs]. Not thread-safe:
 * called from the ViewModel's scope only.
 */
internal class WorkRoomPermissions(
    private val scope: CoroutineScope,
    private val send: suspend (ProviderPermissionChange) -> Boolean,
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() }
) {
    private val _rooms = MutableStateFlow<Map<String, WorkRoomPermissionUi>>(emptyMap())

    /** Each room's control, by the key supplied by its owner. A room never touched has no entry. */
    val rooms: StateFlow<Map<String, WorkRoomPermissionUi>> = _rooms.asStateFlow()

    /** The request each room is waiting on. */
    private val awaiting = mutableMapOf<String, Awaiting>()

    private class Awaiting(val requestId: String) {
        var timeout: Job? = null
    }

    /** The user picked [optionId] for [run]. */
    fun choose(run: ChatItem.ProviderInvocation, optionId: String, roomKey: String = run.id) {
        val permission = run.providerPermission ?: return
        dispatch(run, roomKey, ProviderPermissionEvent.Choose(optionId, permission))
    }

    /** The user confirmed the elevated choice [run]'s control is holding. */
    fun confirm(run: ChatItem.ProviderInvocation, roomKey: String = run.id) {
        dispatch(run, roomKey, ProviderPermissionEvent.Confirm)
    }

    /** The user backed out of a confirmation. */
    fun cancel(roomKey: String) {
        val current = _rooms.value[roomKey]?.control ?: return
        val next = current.on(ProviderPermissionEvent.Cancel).state
        update(roomKey) { it.copy(control = next) }
    }

    /** Clear the message under a room's control once the user has read it. */
    fun dismissError(roomKey: String) = update(roomKey) { it.copy(error = null) }

    private fun dispatch(run: ChatItem.ProviderInvocation, roomKey: String, event: ProviderPermissionEvent) {
        val current = _rooms.value[roomKey]?.control ?: ProviderPermissionControlState.Idle
        val transition = current.on(event)
        val optionId = transition.send
        if (optionId == null) {
            // A new pick replaces the last failure's message.
            update(roomKey) { WorkRoomPermissionUi(control = transition.state) }
            return
        }
        // The revision the user is looking at: the Host refuses the change if the run has moved on.
        val revision = run.stateRevision ?: run {
            update(roomKey) { WorkRoomPermissionUi(error = WorkRoomText.PERMISSION_NOT_READY) }
            return
        }
        val pending = Awaiting(newRequestId())
        awaiting[roomKey]?.timeout?.cancel()
        awaiting[roomKey] = pending
        update(roomKey) { WorkRoomPermissionUi(control = transition.state) }
        val change = ProviderPermissionChange(
            invocationId = run.id,
            stateRevision = revision,
            optionId = optionId,
            confirmRisk = transition.confirmRisk,
            requestId = pending.requestId
        )
        pending.timeout = scope.launch {
            val wentOut = send(change)
            if (awaiting[roomKey] !== pending) return@launch
            if (!wentOut) {
                awaiting.remove(roomKey)
                settle(roomKey, WorkRoomText.PERMISSION_NOT_CONNECTED)
                return@launch
            }
            delay(ackTimeoutMs)
            if (awaiting[roomKey] === pending) {
                awaiting.remove(roomKey)
                FileLogger.w(
                    LogTags.CHAT_VM,
                    "No PROVIDER_PERMISSION_ACK for ${pending.requestId.take(8)} in ${ackTimeoutMs}ms"
                )
                settle(roomKey, WorkRoomText.PERMISSION_NO_ANSWER)
            }
        }
    }

    /** Settle the control whose request [ack] answers. An answer to no request of ours is ignored. */
    fun onAck(ack: ProviderPermissionAck) {
        val room = awaiting.entries.firstOrNull { it.value.requestId == ack.requestId }?.key ?: run {
            FileLogger.w(LogTags.CHAT_VM, "Ignoring PROVIDER_PERMISSION_ACK for unknown request ${ack.requestId.take(8)}")
            return
        }
        awaiting.remove(room)?.timeout?.cancel()
        settle(room, if (ack.accepted) null else WorkRoomText.permissionRefused(ack.reason))
    }

    /** Forget rooms that belong to a deleted or cleared conversation. */
    fun discardMatching(matches: (String) -> Boolean) {
        awaiting.entries.removeAll { (key, pending) ->
            if (!matches(key)) return@removeAll false
            pending.timeout?.cancel()
            true
        }
        _rooms.update { all -> all.filterKeys { !matches(it) } }
    }

    private fun settle(roomKey: String, error: String?) {
        update(roomKey) { ui ->
            WorkRoomPermissionUi(control = ui.control.on(ProviderPermissionEvent.Settled).state, error = error)
        }
    }

    private fun update(roomKey: String, change: (WorkRoomPermissionUi) -> WorkRoomPermissionUi) {
        _rooms.update { all ->
            val next = change(all[roomKey] ?: WorkRoomPermissionUi())
            if (next == WorkRoomPermissionUi()) all - roomKey else all + (roomKey to next)
        }
    }

    companion object {
        /**
         * How long a change waits for its ACK. The Host commits it with one
         * write to the session (no provider is involved), so this is short.
         */
        const val ACK_TIMEOUT_MS = 15_000L
    }
}
