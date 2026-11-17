package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.data.protocol.ProviderInputMessage
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderInput
import ai.openonion.oochat.domain.model.ProviderInputAck
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

/** One Work Room's composer: what is typed there, and what became of its last send. */
data class WorkRoomDraft(
    val text: String = "",
    /** Sent and waiting for the provider to take it; the field is locked meanwhile. */
    val sending: Boolean = false,
    /** Why the last send did not go through. The text is still in [text]. */
    val error: String? = null
)

/**
 * The drafts typed into Work Rooms (W16AAPPLE-35), one per run, and their
 * sends. A draft clears only on a positive `PROVIDER_INPUT_ACK` for the
 * request that carried it; a refusal, a send that could not go out, or no
 * answer within [ackTimeoutMs] keeps it and says why.
 *
 * Owned by the chat's ViewModel rather than the Work Room screen, so a draft
 * outlives leaving the room and coming back. Not thread-safe: called from the
 * ViewModel's scope only.
 */
internal class WorkRoomInputs(
    private val scope: CoroutineScope,
    private val send: suspend (ProviderInput) -> Boolean,
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() }
) {
    private val _drafts = MutableStateFlow<Map<String, WorkRoomDraft>>(emptyMap())

    /** Each room's draft, by the key supplied by its owner. A room with none typed has no entry. */
    val drafts: StateFlow<Map<String, WorkRoomDraft>> = _drafts.asStateFlow()

    /**
     * The request each room last sent and the text it carried. Kept past the
     * timeout: the Host acknowledges only once the provider has taken the text,
     * which can be slow, and a late acceptance should still clear the draft
     * rather than leave it to be sent twice.
     */
    private val awaiting = mutableMapOf<String, Awaiting>()

    private class Awaiting(val requestId: String, val text: String) {
        var timeout: Job? = null
    }

    fun edit(roomKey: String, text: String) {
        update(roomKey) { draft ->
            // Locked while sending: the text on screen is the text that went out.
            if (draft.sending) draft else draft.copy(text = text.take(ProviderInputMessage.MAX_TEXT_LENGTH))
        }
    }

    /**
     * Send [run]'s draft, at the revision the user is looking at. Does
     * nothing while a send is waiting, or with nothing typed; [blockedReason]
     * says when the run cannot take a message at all.
     */
    fun send(run: ChatItem.ProviderInvocation, roomKey: String = run.id) {
        val draft = _drafts.value[roomKey] ?: return
        if (draft.sending || draft.text.isBlank()) return
        blockedReason(run)?.let { reason ->
            update(roomKey) { it.copy(error = reason) }
            return
        }
        val revision = run.stateRevision ?: return
        val pending = Awaiting(newRequestId(), draft.text)
        awaiting[roomKey]?.timeout?.cancel()
        awaiting[roomKey] = pending
        update(roomKey) { it.copy(sending = true, error = null) }
        pending.timeout = scope.launch {
            val wentOut = send(ProviderInput(run.id, revision, pending.text, pending.requestId))
            if (awaiting[roomKey] !== pending) return@launch
            if (!wentOut) {
                awaiting.remove(roomKey)
                update(roomKey) { it.copy(sending = false, error = WorkRoomText.INPUT_NOT_CONNECTED) }
                return@launch
            }
            delay(ackTimeoutMs)
            if (awaiting[roomKey] === pending && _drafts.value[roomKey]?.sending == true) {
                FileLogger.w(LogTags.CHAT_VM, "No PROVIDER_INPUT_ACK for ${pending.requestId.take(8)} in ${ackTimeoutMs}ms")
                update(roomKey) { it.copy(sending = false, error = WorkRoomText.INPUT_NO_ANSWER) }
            }
        }
    }

    /** Settle the draft whose request [ack] answers. An answer to no request of ours is ignored. */
    fun onAck(ack: ProviderInputAck) {
        val room = awaiting.entries.firstOrNull { it.value.requestId == ack.requestId }?.key ?: run {
            FileLogger.w(LogTags.CHAT_VM, "Ignoring PROVIDER_INPUT_ACK for unknown request ${ack.requestId.take(8)}")
            return
        }
        val pending = awaiting.remove(room) ?: return
        pending.timeout?.cancel()
        update(room) { draft ->
            if (ack.accepted) {
                // Only the text that went out is cleared: after a timeout the
                // user may have started rewriting it, and that is theirs.
                WorkRoomDraft(text = if (draft.text == pending.text) "" else draft.text)
            } else {
                draft.copy(sending = false, error = WorkRoomText.inputRefused(ack.reason))
            }
        }
    }

    /** Forget rooms that belong to a deleted or cleared conversation. */
    fun discardMatching(matches: (String) -> Boolean) {
        awaiting.entries.removeAll { (key, pending) ->
            if (!matches(key)) return@removeAll false
            pending.timeout?.cancel()
            true
        }
        _drafts.update { all -> all.filterKeys { !matches(it) } }
    }

    private fun update(roomKey: String, change: (WorkRoomDraft) -> WorkRoomDraft) {
        _drafts.update { all ->
            val next = change(all[roomKey] ?: WorkRoomDraft())
            if (next == WorkRoomDraft()) all - roomKey else all + (roomKey to next)
        }
    }

    companion object {
        /**
         * How long a send waits for its ACK before saying there was none. The
         * Host answers only once Codex has steered its turn, or a finished run
         * has resumed and started a new one, so this is the EXEC wait rather
         * than Rem's.
         */
        const val ACK_TIMEOUT_MS = 60_000L

        /** Why [run] cannot take a message from this device now, or null when it can. */
        fun blockedReason(run: ChatItem.ProviderInvocation): String? = when {
            run.stateRevision == null -> WorkRoomText.INPUT_NOT_READY
            // The Host steers only a live Codex run; Claude Code takes a message once its run has ended.
            run.provider == CLAUDE_CODE && !run.status.isTerminal -> WorkRoomText.INPUT_CLAUDE_BUSY
            else -> null
        }

        private const val CLAUDE_CODE = "claude_code"
    }
}
