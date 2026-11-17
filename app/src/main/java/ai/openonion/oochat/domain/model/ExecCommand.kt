package ai.openonion.oochat.domain.model

data class ExecRequest(
    val execId: String,
    val tool: String,
    val argsJson: String,
    val agentAddress: String
)

sealed interface ExecOutcome {

    data class Success(
        val result: String,
        val durationMs: Double? = null
    ) : ExecOutcome

    data class Error(
        val message: String,
        val durationMs: Double? = null
    ) : ExecOutcome

    data class NotSent(
        val message: String
    ) : ExecOutcome

    data class Unknown(
        val message: String
    ) : ExecOutcome

    /**
     * A refusal from the Host's signed-command gate that names no command, pinned on this
     * EXEC because nothing else could have drawn it (see AgentConnection's
     * execsSinceOtherCommand). It is the client's reading, not the Host's word, so the EXEC's
     * own EXEC_RESULT replaces it if one comes after all (W16AAPPLE-101).
     */
    data class Refused(
        val message: String
    ) : ExecOutcome
}
