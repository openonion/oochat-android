package ai.openonion.oochat.domain.model

/**
 * The chat list as rendered once each turn's working steps are folded into
 * one block (W16AAPPLE-112): the agent's thoughts and tool calls ride on a
 * single row, so a finished turn reads as a summary line above its answer
 * rather than a column of intermediate steps.
 *
 * @param visible the rows to render, in order. Each turn with steps keeps its
 *   first step as the row that stands for all of them.
 * @param turns for each such row, keyed by its id, the steps it stands for.
 * @param footers for the answer of each turn with steps, keyed by its id, the
 *   footer it shows: the whole turn's usage rather than its last LLM call's.
 * @param foldedInto for each row folded away, the id of the visible row that
 *   now stands for it — its turn's step block. A saved scroll position names
 *   a row by id, and one taken before a step folded must still find its place.
 */
data class TurnStepGroups(
    val visible: List<ChatItem>,
    val turns: Map<String, TurnSteps>,
    val footers: Map<String, ChatItem.Thinking> = emptyMap(),
    val foldedInto: Map<String, String> = emptyMap()
)

/**
 * The working steps of one turn — everything between two of the user's own
 * messages that the agent did on its way to an answer.
 *
 * @param key stable for the life of the turn — the id of its first step — so
 *   an expand/collapse choice survives the turn growing and finishing.
 * @param steps the turn's thoughts and tool calls, oldest first.
 * @param running whether the turn is still in flight. Only ever the newest turn.
 * @param hasAnswer whether a reply follows the steps. A finished turn without
 *   one stays open, so whatever it last did is still on screen.
 * @param startId the user message that started the turn, null for the head of
 *   a conversation; with [endId], what the turn's duration is measured between.
 * @param endId the turn's last row: its answer, or its newest step.
 * @param workMs the turn's own reported LLM and tool timings, summed — the
 *   fallback duration when no timestamps are known for [startId]/[endId].
 */
data class TurnSteps(
    val key: String,
    val steps: List<ChatItem>,
    val running: Boolean,
    val hasAnswer: Boolean,
    val startId: String?,
    val endId: String,
    val workMs: Long?
) {
    val failedCount: Int get() = steps.count { it is ChatItem.ToolCall && it.status == ToolStatus.ERROR }

    /** Finished turns start collapsed; one that ended without an answer does not, so it can say why. */
    val collapsedByDefault: Boolean get() = !running && hasAnswer
}

/**
 * Something the agent did on the way to its answer: a thought, its read of
 * what was asked, a tool call, or an approval the user has already answered —
 * the last is a record of a step, not a question, so it folds away with the
 * rest. One still waiting on the user is a gate and never does.
 */
internal fun ChatItem.isTurnStep(): Boolean = when (this) {
    is ChatItem.Thinking -> isReasoning
    is ChatItem.ToolCall -> true
    is ChatItem.IntentItem -> true
    // A Work Room approval answers for a whole run, and its card is how that
    // run is reached; it stays where it is.
    is ChatItem.ApprovalNeeded -> decision != null && workRoom == null
    else -> false
}

/** A reply with something to read — what a turn's steps collapse above. */
private fun ChatItem.isAnswer(): Boolean = when (this) {
    is ChatItem.Agent -> content.isNotBlank() || !images.isNullOrEmpty()
    is ChatItem.Turn -> agent?.let { it.content.isNotBlank() || !it.images.isNullOrEmpty() } == true
    else -> false
}

/**
 * The footer of one LLM call in the middle of a turn: a Turn the agent emitted
 * no text from, only tool calls. Its timing and tokens move to the summary and
 * the answer's footer. A failed one stays: it is what says the turn died.
 */
private fun ChatItem.isIntermediateFooter(): Boolean =
    this is ChatItem.Turn && !isAnswer() && thinking?.status != ThinkingStatus.ERROR

/**
 * Folds each turn's steps (see [isTurnStep]) into one row. A turn is
 * everything between two of the user's own messages. Turns with no steps —
 * a plain question and answer — are left exactly as they were, and this same
 * list is returned when no turn has any.
 */
fun List<ChatItem>.groupTurnSteps(): TurnStepGroups {
    val hidden = HashMap<String, String>()
    val turns = HashMap<String, TurnSteps>()
    val footers = HashMap<String, ChatItem.Thinking>()
    // Answers that come before their turn's first step, to the step that has
    // to move up in front of them — see groupTurn.
    val leadIns = HashMap<String, ChatItem>()

    var start = 0
    while (start <= lastIndex) {
        val userIndex = indexOfFirstFrom(start) { it is ChatItem.User }
        val end = if (userIndex < 0) size else userIndex
        if (end > start) groupTurn(start, end, isNewest = userIndex < 0, hidden, turns, footers, leadIns)
        start = end + 1
    }

    if (turns.isEmpty()) return TurnStepGroups(this, emptyMap())
    val moved = leadIns.values.mapTo(HashSet()) { it.id }
    val visible = ArrayList<ChatItem>(size - hidden.size)
    for (item in this) {
        leadIns[item.id]?.let { visible += it }
        if (item.id !in hidden && item.id !in moved) visible += item
    }
    return TurnStepGroups(visible, turns, footers, foldedInto = hidden)
}

/**
 * This grouping, with every turn and footer that came out the same as in
 * [previous] swapped for [previous]'s own instance. The list is regrouped on
 * every arriving message, and Compose compares these by instance: without this
 * every finished turn above would recompose on each frame of the one running.
 */
fun TurnStepGroups.reusing(previous: TurnStepGroups?): TurnStepGroups {
    if (previous == null) return this
    return copy(
        turns = turns.mapValues { (key, turn) -> previous.turns[key]?.takeIf { it == turn } ?: turn },
        footers = footers.mapValues { (key, footer) -> previous.footers[key]?.takeIf { it == footer } ?: footer }
    )
}

private fun List<ChatItem>.groupTurn(
    start: Int,
    end: Int,
    isNewest: Boolean,
    hidden: MutableMap<String, String>,
    turns: MutableMap<String, TurnSteps>,
    footers: MutableMap<String, ChatItem.Thinking>,
    leadIns: MutableMap<String, ChatItem>
) {
    val turn = subList(start, end)
    val steps = turn.filter { it.isTurnStep() }
    if (steps.isEmpty()) return
    val answer = turn.lastOrNull { it.isAnswer() }

    val anchor = steps.first().id
    steps.drop(1).forEach { hidden[it.id] = anchor }
    // A reply is written into the turn's newest pending footer, which can sit
    // above tool cards that came after it. The summary still belongs above
    // the answer, not trailing under it.
    if (answer != null && turn.indexOf(answer) < turn.indexOf(steps.first())) leadIns[answer.id] = steps.first()
    turn.filter { it.isIntermediateFooter() }.forEach { hidden[it.id] = anchor }

    val calls = turn.mapNotNull { (it as? ChatItem.Turn)?.thinking }
    val workMs = calls.sumOf { it.durationMs?.toLong() ?: 0L } +
        steps.sumOf { (it as? ChatItem.ToolCall)?.timingMs ?: 0L }
    turns[steps.first().id] = TurnSteps(
        key = steps.first().id,
        steps = steps,
        // A stale RUNNING row in an older turn is history, not work in flight.
        running = isNewest && turn.any { it.isInProgress() || it.holdsTurnOpen() },
        hasAnswer = answer != null,
        startId = getOrNull(start - 1)?.id,
        endId = (answer ?: turn.last()).id,
        workMs = workMs.takeIf { it > 0 }
    )

    if (answer is ChatItem.Turn) {
        val done = calls.filter { it.status == ThinkingStatus.DONE }
        val own = answer.thinking ?: ChatItem.Thinking(id = answer.id, status = ThinkingStatus.DONE)
        footers[answer.id] = own.copy(
            // The summary above carries how long the turn took.
            durationMs = null,
            tokensTotal = done.mapNotNull { it.tokensTotal }.takeIf { it.isNotEmpty() }?.sum(),
            costUsd = done.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum()
        )
    }
}

/**
 * Not a step in flight, but a reason the turn is not over: a Claude Code or
 * Codex run it started that is still going, or a question still waiting on the
 * user. Summing it up as "Worked for …" then would claim a finish the top bar
 * and the card beside it contradict.
 */
private fun ChatItem.holdsTurnOpen(): Boolean = when (this) {
    is ChatItem.ProviderInvocation -> !status.isTerminal
    is ChatItem.ApprovalNeeded -> decision == null
    is ChatItem.AskUser -> answer == null
    else -> false
}

private inline fun List<ChatItem>.indexOfFirstFrom(from: Int, predicate: (ChatItem) -> Boolean): Int {
    for (i in from..lastIndex) if (predicate(this[i])) return i
    return -1
}
