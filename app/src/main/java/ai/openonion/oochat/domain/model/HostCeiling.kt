package ai.openonion.oochat.domain.model

/**
 * The Host permission ceiling control's own state machine (W16AAPPLE-42).
 *
 * The ceiling is the most the agent may do on its host's machine: one of the
 * modes a host-managed Host advertises ([SessionModes.hostManaged]: read only,
 * auto, full access). Moving to any mode goes straight out, except moving into
 * [ApprovalMode.FULL_ACCESS], which lets the agent edit files and run commands
 * without asking: that waits in [ConfirmingFullAccess] until the user confirms,
 * as the reference client's mode-indicator.tsx does. Leaving full access never
 * needs confirming; a lower ceiling is always safe.
 *
 * Deliberately separate from the provider's own permission mode (Claude Code or
 * Codex, W16AAPPLE-43), which gets its own control and state machine. The two
 * limit different things, and sharing state would let one silently change the
 * other.
 *
 * Pure: [on] returns the next state and the mode to send, if any. Whether the
 * Host then accepted it is ConnectionRepository's `modePending`, not this.
 */
sealed interface HostCeilingState {

    /** Nothing waiting on the user. */
    data object Idle : HostCeilingState

    /** Full access was chosen; nothing is sent until [HostCeilingEvent.Confirm]. */
    data object ConfirmingFullAccess : HostCeilingState

    fun on(event: HostCeilingEvent): HostCeilingTransition = when (event) {
        is HostCeilingEvent.Choose ->
            if (event.mode == ApprovalMode.FULL_ACCESS && event.current != ApprovalMode.FULL_ACCESS) {
                HostCeilingTransition(ConfirmingFullAccess)
            } else {
                // Any other pick, including one made while a confirmation is
                // showing, replaces that confirmation rather than waiting on it.
                HostCeilingTransition(Idle, send = event.mode)
            }
        HostCeilingEvent.Confirm ->
            if (this == ConfirmingFullAccess) HostCeilingTransition(Idle, send = ApprovalMode.FULL_ACCESS)
            else HostCeilingTransition(this)
        HostCeilingEvent.Cancel -> HostCeilingTransition(Idle)
    }
}

/** What the user did in the ceiling control. */
sealed interface HostCeilingEvent {
    /** Picked [mode] while the connection is in [current]. */
    data class Choose(val mode: ApprovalMode, val current: ApprovalMode) : HostCeilingEvent

    /** Confirmed full access. Ignored unless a confirmation is showing. */
    data object Confirm : HostCeilingEvent

    /** Backed out of a confirmation. */
    data object Cancel : HostCeilingEvent
}

/** The state after an event, and the mode to send as `mode_change`, if the event sends one. */
data class HostCeilingTransition(
    val state: HostCeilingState,
    val send: ApprovalMode? = null
)
