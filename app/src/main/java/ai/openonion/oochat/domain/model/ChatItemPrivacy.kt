package ai.openonion.oochat.domain.model

import ai.openonion.oochat.util.SensitiveText

/**
 * The chat as it may be drawn (W16AAPPLE-50): every piece of text the agent,
 * its tools or the Host sent, through [SensitiveText]. Applied only on the way
 * to a screen; the items the ViewModel keeps, persists and answers from stay
 * as they came, so a reply to the agent never carries a mask.
 *
 * Error text (a failed turn, a failed compaction, a refused tool or invite)
 * goes through [SensitiveText.errorText], which replaces a raw provider payload
 * whole. Everything else is content the user is meant to read, a tool's output
 * included, and is only redacted.
 *
 * The user's own images and files are left alone: they are local paths the
 * app opens, never drawn as text.
 */
object ChatItemPrivacy {

    fun redact(item: ChatItem): ChatItem = when (item) {
        is ChatItem.User -> item.copy(content = r(item.content))
        is ChatItem.Agent -> agent(item)
        is ChatItem.Thinking -> thinking(item)
        is ChatItem.Turn -> item.copy(thinking = item.thinking?.let(::thinking), agent = item.agent?.let(::agent))
        is ChatItem.ToolCall -> item.copy(args = item.args?.let(::args), result = item.result?.let(::r))
        is ChatItem.AskUser -> item.copy(
            text = r(item.text),
            options = item.options.map(::r),
            answer = item.answer?.let(::r)
        )
        is ChatItem.ApprovalNeeded -> item.copy(
            arguments = args(item.arguments),
            description = item.description?.let(::r),
            batchRemaining = item.batchRemaining?.map { it.copy(arguments = r(it.arguments)) },
            decision = item.decision?.let { it.copy(feedback = it.feedback?.let(::r)) }
        )
        is ChatItem.OnboardSuccess -> item.copy(message = r(item.message))
        is ChatItem.OnboardingFailed -> item.copy(reason = SensitiveText.errorText(item.reason))
        is ChatItem.IntentItem -> item.copy(ack = item.ack?.let(::r))
        is ChatItem.EvalItem -> item.copy(
            summary = item.summary?.let(::r),
            expected = item.expected?.let(::r),
            evalPath = item.evalPath?.let(::r)
        )
        is ChatItem.CompactItem -> item.copy(
            message = item.message?.let(::r),
            error = item.error?.let(SensitiveText::errorText)
        )
        is ChatItem.ToolBlockedItem -> item.copy(
            reason = SensitiveText.errorText(item.reason),
            message = SensitiveText.errorText(item.message),
            command = item.command?.let(::r)
        )
        is ChatItem.PlanReviewItem -> item.copy(planContent = r(item.planContent))
        is ChatItem.FilesReceivedItem -> item.copy(files = item.files.map { it.copy(path = r(it.path)) })
        is ChatItem.DiffPreviewItem -> item.copy(path = r(item.path), preview = r(item.preview))
        // A Work Room (W16AAPPLE-33) is where the user follows and steers a
        // coding run, so its steps and files read like tool cards; its error
        // reads like any other error. Its summaries are meant as sentences, so
        // a raw payload in one is replaced, as in an error.
        is ChatItem.ProviderInvocation -> item.copy(
            taskTitle = item.taskTitle?.let(::r),
            currentSummary = item.currentSummary?.let(SensitiveText::summaryText),
            resultSummary = item.resultSummary?.let(SensitiveText::summaryText),
            errorSummary = item.errorSummary?.let(SensitiveText::errorText),
            activities = item.activities.map {
                it.copy(title = r(it.title), summary = r(it.summary), files = it.files.map(::r))
            },
            messages = item.messages.map { it.copy(text = SensitiveText.messageText(it.text)) }
        )
        // Folded into its run by ChatEventReducer, never drawn itself.
        is ChatItem.ProviderActivityUpdate,
        is ChatItem.ProviderMessageUpdate,
        is ChatItem.ProviderArtifactUpdate,
        is ChatItem.ProviderSessionUpdate,
        is ChatItem.OnboardRequired,
        is ChatItem.UlwTurnsReachedItem,
        is ChatItem.ModeChangedItem -> item
    }.let { redacted -> if (redacted == item) item else redacted }

    private fun r(text: String): String = SensitiveText.redact(text)

    private fun agent(item: ChatItem.Agent) = item.copy(content = r(item.content))

    private fun thinking(item: ChatItem.Thinking) = item.copy(
        content = item.content?.let {
            if (item.status == ThinkingStatus.ERROR) SensitiveText.errorText(it) else r(it)
        }
    )

    private fun args(args: Map<String, String>): Map<String, String> = args.mapValues { (_, v) -> r(v) }

    /**
     * Redacts a whole chat, keeping work and identity from one call to the
     * next: the same list back hands back the same result, and an item the
     * last call already saw (the same instance) gets the same redacted
     * instance. A streaming reply then costs one item per frame, not the
     * transcript, and Compose can still skip every bubble that did not change.
     *
     * Not thread-safe; give each flow its own.
     */
    class Cache {
        private var lastIn: List<ChatItem>? = null
        private var lastOut: List<ChatItem> = emptyList()
        private var seen: Map<String, Pair<ChatItem, ChatItem>> = emptyMap()

        fun redact(items: List<ChatItem>): List<ChatItem> {
            if (items === lastIn) return lastOut
            val next = HashMap<String, Pair<ChatItem, ChatItem>>(items.size * 2)
            var changed = false
            val out = items.map { item ->
                val cached = seen[item.id]?.takeIf { it.first === item }?.second
                val redacted = cached ?: redact(item)
                next[item.id] = item to redacted
                if (redacted !== item) changed = true
                redacted
            }
            seen = next
            lastIn = items
            // Nothing masked: the caller's own list, so its identity still means "unchanged".
            lastOut = if (changed) out else items
            return lastOut
        }
    }
}
