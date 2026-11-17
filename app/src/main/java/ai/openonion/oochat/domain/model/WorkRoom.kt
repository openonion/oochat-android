package ai.openonion.oochat.domain.model

/**
 * Where a Claude Code or Codex run stands, from `provider_invocation`'s
 * `status`. The Host's own values (connectonion `plugins/coding_agents.py`,
 * `useful_tools/codex.py`); anything else reads as [RUNNING], since only the
 * Host may say a run has ended. [ENDED] is this app's own: the Host was idle
 * with the run still showing as working, so it ended unseen and its outcome
 * is unknown (see ChatViewModel.onHostIdle).
 */
enum class ProviderInvocationStatus {
    RUNNING,
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    ENDED;

    val isTerminal: Boolean get() = this != RUNNING && this != AWAITING_APPROVAL

    companion object {
        fun fromWire(value: String?): ProviderInvocationStatus = when (value) {
            "awaiting_approval" -> AWAITING_APPROVAL
            "completed" -> COMPLETED
            "failed" -> FAILED
            "cancelled" -> CANCELLED
            else -> RUNNING
        }
    }
}

/** A step's state. The Host sends "running" | "completed" | "failed"; an unknown value is a failure, as it reads it. */
enum class ProviderActivityStatus {
    RUNNING,
    COMPLETED,
    FAILED;

    companion object {
        fun fromWire(value: String?): ProviderActivityStatus = when (value) {
            "running", "in_progress" -> RUNNING
            "completed", "success", "done" -> COMPLETED
            else -> FAILED
        }
    }
}

/**
 * One step of a run, as the Host describes it: a safe [title] and [summary]
 * it chose ("Update workspace files"), never the provider's raw command or
 * output. [files] are bare file names. [legacy] marks a step that came as an
 * ordinary `tool_call` from a Host that sends no `provider_activity` for it.
 */
data class ProviderActivity(
    val id: String,
    val sequence: Int,
    val kind: String,
    val status: ProviderActivityStatus,
    val title: String,
    val summary: String,
    val files: List<String> = emptyList(),
    val legacy: Boolean = false
)

/**
 * Folds Work Room frames into the chat list, as the Host's own
 * `_nest_provider_invocations` (network/host/session/ui.py) builds them for
 * replay: one card per invocation, replaced in place by newer revisions, with
 * its activities upserted by id and kept in [ProviderActivity.sequence] order.
 * The outer `codex`/`claude_code` tool call is the card, so its own tool card
 * is replaced by it, and dropped when it arrives later.
 */
object WorkRooms {

    /** Fold a `provider_invocation`. Returns null when the frame changes nothing. */
    fun foldInvocation(
        current: List<ChatItem>,
        incoming: ChatItem.ProviderInvocation,
        append: (List<ChatItem>, ChatItem) -> List<ChatItem>
    ): ReduceResult? {
        val index = current.indexOfFirst { it is ChatItem.ProviderInvocation && it.id == incoming.id }
        if (index >= 0) {
            val onScreen = current[index] as ChatItem.ProviderInvocation
            if (isStale(onScreen, incoming)) return null
            val merged = onScreen.mergedWith(incoming)
            if (merged == onScreen) return null
            return ReduceResult(current.toMutableList().also { it[index] = merged }, merged)
        }
        // First frame for this run: it takes the place of the tool card it is.
        val parentIndex = incoming.parentToolCallId?.let { parent ->
            current.indexOfFirst { it is ChatItem.ToolCall && it.id == parent }
        } ?: -1
        val newList = if (parentIndex >= 0) {
            current.toMutableList().also { it[parentIndex] = incoming }
        } else {
            append(current, incoming)
        }
        return ReduceResult(newList, incoming)
    }

    /** Fold a `provider_activity` into its run's card, starting the card if this arrived first. */
    fun foldActivity(
        current: List<ChatItem>,
        update: ChatItem.ProviderActivityUpdate,
        append: (List<ChatItem>, ChatItem) -> List<ChatItem>
    ): ReduceResult? {
        val index = current.indexOfFirst { it is ChatItem.ProviderInvocation && it.id == update.invocationId }
        if (index < 0) {
            val started = ChatItem.ProviderInvocation(
                id = update.invocationId,
                parentToolCallId = update.parentToolCallId,
                provider = update.provider,
                activities = listOf(update.activity)
            )
            return foldInvocation(current, started, append)
        }
        val card = current[index] as ChatItem.ProviderInvocation
        val updated = card.copy(activities = upsert(card.activities, update.activity))
        if (updated == card) return null
        return ReduceResult(current.toMutableList().also { it[index] = updated }, updated)
    }

    /**
     * [stored] (the saved row) brought up to date with [incoming], a card folded
     * without it: frames for a conversation that is not on screen are folded
     * on their own, so they carry only what they changed.
     */
    fun mergeStored(stored: ChatItem.ProviderInvocation, incoming: ChatItem.ProviderInvocation): ChatItem.ProviderInvocation {
        val base = if (isStale(stored, incoming)) stored else stored.mergedWith(incoming)
        return base.copy(activities = incoming.activities.fold(stored.activities, ::upsert))
    }

    /**
     * A step updated in place (the Host's `_upsert_provider_activity`), so a
     * finished step keeps its place, then kept in sequence order. A native step
     * and its compatibility tool_call share an id, and the native one wins.
     */
    private fun upsert(steps: List<ProviderActivity>, step: ProviderActivity): List<ProviderActivity> {
        val at = steps.indexOfFirst { it.id == step.id }
        if (at < 0) return (steps + step).sortedBy { it.sequence }
        if (step.legacy && !steps[at].legacy) return steps
        return steps.toMutableList().also { it[at] = step }.sortedBy { it.sequence }
    }

    /** Every frame is sent twice and a reconnect replays some: an older revision is never newer news. */
    private fun isStale(onScreen: ChatItem.ProviderInvocation, incoming: ChatItem.ProviderInvocation) =
        incoming.stateRevision != null && onScreen.stateRevision != null && incoming.stateRevision < onScreen.stateRevision

    /** True for the outer agent's tool card of a run already shown as a Work Room. */
    fun isParentOfShownRun(current: List<ChatItem>, item: ChatItem): Boolean =
        item is ChatItem.ToolCall &&
            current.any { it is ChatItem.ProviderInvocation && it.parentToolCallId == item.id }

    /** The outer tool calls that are runs in [items]: their own cards are never shown. */
    fun parentToolCallIds(items: List<ChatItem>): Set<String> =
        items.mapNotNullTo(mutableSetOf()) { (it as? ChatItem.ProviderInvocation)?.parentToolCallId }

    /**
     * [items] without the tool card of a run that is shown as a Work Room. A saved
     * conversation can hold both: the tool call was saved before its run started,
     * or its result arrived while the conversation was not on screen.
     */
    fun withoutParentToolCards(items: List<ChatItem>): List<ChatItem> {
        val parents = parentToolCallIds(items)
        return if (parents.isEmpty()) items else items.filterNot { it is ChatItem.ToolCall && it.id in parents }
    }

    /**
     * Runs still showing as working when the Host says it is idle (or has no
     * record of the session) ended while this phone was not listening, so no
     * frame will finish them: [ProviderInvocationStatus.ENDED]. Null for anything else.
     */
    fun endedUnseen(item: ChatItem): ChatItem.ProviderInvocation? =
        (item as? ChatItem.ProviderInvocation)?.takeIf { !it.status.isTerminal }
            ?.copy(status = ProviderInvocationStatus.ENDED)

    /**
     * A frame names only what changed; what it leaves out stays as it was. A
     * card a step started has no revision and only a guessed status, so it
     * never overrides the status of one that has.
     */
    private fun ChatItem.ProviderInvocation.mergedWith(next: ChatItem.ProviderInvocation) = copy(
        parentToolCallId = next.parentToolCallId ?: parentToolCallId,
        workroomId = next.workroomId ?: workroomId,
        provider = next.provider.ifBlank { provider },
        providerDisplayName = next.providerDisplayName ?: providerDisplayName,
        taskTitle = next.taskTitle ?: taskTitle,
        status = if (next.stateRevision == null && stateRevision != null) status else next.status,
        currentSummary = next.currentSummary ?: currentSummary,
        resultSummary = next.resultSummary ?: resultSummary,
        errorSummary = next.errorSummary ?: errorSummary,
        elapsedMs = next.elapsedMs ?: elapsedMs,
        stateRevision = next.stateRevision ?: stateRevision,
        activities = activities
    )
}
