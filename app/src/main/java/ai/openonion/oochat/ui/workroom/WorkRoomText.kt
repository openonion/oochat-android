package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderInvocationStatus

/** What the Work Room says about a Claude Code or Codex run (W16AAPPLE-33), in one place for the card and the screen. */
internal object WorkRoomText {

    /** The Host's display name, or a readable one for the providers it ships. */
    fun providerName(run: ChatItem.ProviderInvocation): String =
        run.providerDisplayName?.takeIf { it.isNotBlank() } ?: when (run.provider) {
            "codex" -> "Codex"
            "claude_code" -> "Claude Code"
            "" -> "Coding agent"
            else -> run.provider
        }

    fun status(status: ProviderInvocationStatus): String = when (status) {
        ProviderInvocationStatus.RUNNING -> "Working"
        ProviderInvocationStatus.AWAITING_APPROVAL -> "Waiting for your decision"
        ProviderInvocationStatus.COMPLETED -> "Completed"
        ProviderInvocationStatus.FAILED -> "Failed"
        ProviderInvocationStatus.CANCELLED -> "Stopped"
        ProviderInvocationStatus.ENDED -> "Ended"
    }

    /** Said for a run that ended while this device was not listening, so no result reached it. */
    const val ENDED_UNSEEN = "This run ended while this device was not connected, so its result did not arrive here."

    /** The run's task, as the Host titled it; never the raw prompt. */
    fun title(run: ChatItem.ProviderInvocation): String =
        run.taskTitle?.takeIf { it.isNotBlank() } ?: "${providerName(run)} task"

    /**
     * One line on where the run stands. While it works: its newest step, since
     * the Host's own summary only ever says it is working. Once it has ended:
     * how, never the summary it had while working, which a Host-made Stop
     * frame carries over.
     */
    fun summary(run: ChatItem.ProviderInvocation): String? = when {
        run.status == ProviderInvocationStatus.ENDED -> ENDED_UNSEEN
        run.status.isTerminal -> (run.errorSummary ?: run.resultSummary)?.takeIf { it.isNotBlank() }
        else -> run.activities.lastOrNull()?.title ?: run.currentSummary?.takeIf { it.isNotBlank() }
    }

    /** "42 s" or "3 min 5 s", from the Host's elapsedMs. */
    fun elapsed(ms: Long?): String? {
        val seconds = (ms ?: return null) / 1000
        if (seconds < 1) return null
        return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
    }

    fun steps(count: Int): String = if (count == 1) "1 step" else "$count steps"
}
