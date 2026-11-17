package ai.openonion.oochat.domain.model

/**
 * A message typed into a Work Room (W16AAPPLE-35), as `PROVIDER_INPUT` carries
 * it: the run it steers, the [stateRevision] the user last saw it at (the Host
 * refuses it if the run has moved on since), the text, and the id its
 * `PROVIDER_INPUT_ACK` will answer to.
 */
data class ProviderInput(
    val invocationId: String,
    val stateRevision: Long,
    val text: String,
    val requestId: String
)

/**
 * The Host's answer to a [ProviderInput]. [accepted] only once the provider
 * itself took the text (Codex's `turn/steer`, or a resumed run's first turn),
 * never on the Host merely queueing it; a refusal says why in [reason]
 * (connectonion `network/host/ws_router/session.py`, `useful_tools/codex.py`).
 */
data class ProviderInputAck(
    val requestId: String,
    val invocationId: String?,
    val accepted: Boolean,
    val stateRevision: Long? = null,
    val reason: String? = null
)

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

/** Who a [ProviderMessage] is from. The Host sends these two roles and no others. */
enum class ProviderMessageRole { USER, ASSISTANT }

/**
 * One line of the conversation with Claude Code or Codex inside a run
 * (W16AAPPLE-34): plain text the Host bounded, from the user or the provider.
 * The Host never puts command output, local paths or HTML in one
 * (core/provider_events.py `provider_message_event`), so [text] is drawn as text.
 * [continuationOf] is the message this one carries on, when a reply was split.
 */
data class ProviderMessage(
    val id: String,
    val role: ProviderMessageRole,
    val text: String,
    val continuationOf: String? = null
)

/**
 * The latest picture of a run's workspace or browser (W16AAPPLE-34): an inline
 * PNG or JPEG the Host captured itself, never a link to fetch. [dataUrl] has
 * already been checked as one; [alt] is one of the Host's approved descriptions.
 * A newer [stateRevision] replaces an older picture of the same run.
 */
data class ProviderArtifact(
    val id: String,
    val dataUrl: String,
    val alt: String,
    val stateRevision: Long
)

/** Who holds a Claude Code session: the terminal it started in, or the browser it was handed to. */
enum class ProviderControlOwner {
    TERMINAL,
    BROWSER;

    companion object {
        /** Null for a value the Host does not define, so it is never guessed at. */
        fun fromWire(value: String?): ProviderControlOwner? = entries.firstOrNull { it.name.lowercase() == value }
    }
}

/**
 * Where a Claude Code session stands between the terminal and the browser, as the Host
 * confirms it (core/provider_events.py `_CLAUDE_STATION_PHASES`). Only these seven are real.
 */
enum class ProviderControlPhase {
    LOCAL_STARTING,
    LOCAL_OBSERVING,
    HANDOVER_TO_REMOTE,
    REMOTE_CONTROLLING,
    HANDOVER_TO_LOCAL,
    COMPLETED,
    FAILED;

    companion object {
        /** Null for a value the Host does not define, so it is never guessed at. */
        fun fromWire(value: String?): ProviderControlPhase? = entries.firstOrNull { it.name.lowercase() == value }
    }
}

/**
 * The control state the Host last confirmed for a run (W16AAPPLE-40): a `provider_session`
 * frame, or the `controlOwner`/`controlPhase`/`controlRevision` the Host nests on a replayed run.
 * [revision] orders it against the others for the same run; an older one never replaces a newer.
 */
data class ProviderControl(
    val owner: ProviderControlOwner,
    val phase: ProviderControlPhase,
    val revision: Long
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

    /**
     * How much of a run's conversation is kept: the newest messages that fit in this many
     * characters. A run is saved as one database row, which Android cannot read back past
     * about 2 MB, and a long session can say far more than that. At 200,000 characters
     * (3 bytes each at worst) the row stays well under it, with the picture and the steps.
     */
    const val MAX_CONVERSATION_CHARS = 200_000

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
    ): ReduceResult? = foldIntoCard(current, update.invocationId, update.parentToolCallId, update.provider, append) { card ->
        card.copy(activities = upsert(card.activities, update.activity))
    }

    /** Fold a `provider_message` into its run's conversation, starting the card if this arrived first. */
    fun foldMessage(
        current: List<ChatItem>,
        update: ChatItem.ProviderMessageUpdate,
        append: (List<ChatItem>, ChatItem) -> List<ChatItem>
    ): ReduceResult? = foldIntoCard(current, update.invocationId, update.parentToolCallId, update.provider, append) { card ->
        card.copy(messages = upsert(card.messages, update.message))
    }

    /** Fold a `provider_artifact` into its run's card as the latest picture, unless it is older than the one shown. */
    fun foldArtifact(
        current: List<ChatItem>,
        update: ChatItem.ProviderArtifactUpdate,
        append: (List<ChatItem>, ChatItem) -> List<ChatItem>
    ): ReduceResult? = foldIntoCard(current, update.invocationId, update.parentToolCallId, update.provider, append) { card ->
        card.copy(artifact = newer(card.artifact, update.artifact))
    }

    /** Fold a `provider_session` into its run's card as the control state, unless it is older than the one shown. */
    fun foldControl(
        current: List<ChatItem>,
        update: ChatItem.ProviderSessionUpdate,
        append: (List<ChatItem>, ChatItem) -> List<ChatItem>
    ): ReduceResult? = foldIntoCard(current, update.invocationId, update.parentToolCallId, update.provider, append) { card ->
        card.copy(control = newer(card.control, update.control))
    }

    /** [change] applied to the run's card, which a frame that arrives before its run starts. Null when nothing changed. */
    private fun foldIntoCard(
        current: List<ChatItem>,
        invocationId: String,
        parentToolCallId: String?,
        provider: String,
        append: (List<ChatItem>, ChatItem) -> List<ChatItem>,
        change: (ChatItem.ProviderInvocation) -> ChatItem.ProviderInvocation
    ): ReduceResult? {
        val index = current.indexOfFirst { it is ChatItem.ProviderInvocation && it.id == invocationId }
        if (index < 0) {
            val started = change(
                ChatItem.ProviderInvocation(id = invocationId, parentToolCallId = parentToolCallId, provider = provider)
            )
            return foldInvocation(current, started, append)
        }
        val card = current[index] as ChatItem.ProviderInvocation
        val updated = change(card)
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
        return base.copy(
            activities = incoming.activities.fold(stored.activities, ::upsert),
            messages = incoming.messages.fold(stored.messages, ::upsert),
            artifact = newer(stored.artifact, incoming.artifact),
            control = newer(stored.control, incoming.control)
        )
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

    /**
     * A message updated in place by id, so one the Host sends twice (live, then again when the turn
     * commits) or replays after a reconnect is shown once, where it first arrived.
     */
    private fun upsert(messages: List<ProviderMessage>, message: ProviderMessage): List<ProviderMessage> {
        val at = messages.indexOfFirst { it.id == message.id }
        val all = if (at < 0) messages + message else messages.toMutableList().also { it[at] = message }
        return withinBudget(all)
    }

    /** [messages] without the oldest ones that do not fit [MAX_CONVERSATION_CHARS]; the newest is always kept. */
    private fun withinBudget(messages: List<ProviderMessage>): List<ProviderMessage> {
        var size = 0
        var firstKept = messages.size
        while (firstKept > 0 && size + messages[firstKept - 1].text.length <= MAX_CONVERSATION_CHARS) {
            firstKept--
            size += messages[firstKept].text.length
        }
        return if (firstKept == messages.size) messages.takeLast(1) else messages.drop(firstKept)
    }

    /** The picture of the two that is the later view: an older one never replaces a newer. */
    private fun newer(shown: ProviderArtifact?, incoming: ProviderArtifact?): ProviderArtifact? = when {
        incoming == null -> shown
        shown == null -> incoming
        incoming.stateRevision < shown.stateRevision -> shown
        else -> incoming
    }

    /** The control state of the two that is the later: an older revision never replaces a newer, an equal one does (as the Host's). */
    private fun newer(shown: ProviderControl?, incoming: ProviderControl?): ProviderControl? = when {
        incoming == null -> shown
        shown == null -> incoming
        incoming.revision < shown.revision -> shown
        else -> incoming
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
        activities = activities,
        control = newer(control, next.control),
        providerPermission = next.providerPermission ?: providerPermission
    )
}

/** The Host's `scopeClassification`. Only [WORKROOM] may be allowed: the Host declines anything else regardless. */
enum class ApprovalBoundary {
    WORKROOM,
    ELEVATED,
    UNKNOWN
}

data class ProviderApprovalPresentation(
    val action: String,
    val scope: String,
    val reason: String,
    val boundary: ApprovalBoundary,
    val allowOnce: Boolean,
    val allowSession: Boolean,
    val files: List<String> = emptyList()
)

/** A Claude Code or Codex approval from inside a run (W16AAPPLE-36). */
data class WorkRoomApproval(
    val provider: String,
    val invocationId: String,
    val parentToolCallId: String,
    val activityId: String? = null,
    val presentation: ProviderApprovalPresentation? = null
) {
    val canAllowOnce: Boolean
        get() = presentation?.boundary == ApprovalBoundary.WORKROOM && presentation.allowOnce

    val canAllowSession: Boolean
        get() = presentation?.boundary == ApprovalBoundary.WORKROOM && presentation.allowSession
}
