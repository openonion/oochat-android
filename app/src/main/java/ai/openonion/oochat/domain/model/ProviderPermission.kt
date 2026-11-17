package ai.openonion.oochat.domain.model

/**
 * One choice of a Claude Code or Codex run's own permission mode, as the Host
 * offers it (connectonion `core/provider_permissions.py`): Codex's Read Only,
 * Ask for approval, Approve for me and Full Access, or Claude Code's Plan,
 * Default, Accept edits, Auto and Bypass permissions.
 *
 * [label] and [description] are the Host's own words. [selectable] is false
 * for a choice above the Host ceiling, with the Host's [disabledReason].
 * [elevated] (the Host's `risk: "elevated"`) needs its own confirmation, and
 * the Host refuses it without one.
 */
data class ProviderPermissionOption(
    val id: String,
    val label: String,
    val description: String = "",
    val elevated: Boolean = false,
    val selectable: Boolean = false,
    val disabledReason: String? = null
)

/**
 * A run's provider permission mode (W16AAPPLE-43): the `providerPermission` a
 * `provider_invocation` carries. The Host is the authority, both for which
 * choice is active and which are on offer under the current Host ceiling;
 * nothing here is guessed. A run with none (a Host without the feature, or
 * Claude Station, which pins its mode) shows no control.
 *
 * Applies from the provider's next turn (the Host's `appliesTo:
 * "subsequent_turn"`), never to the step in progress.
 */
data class ProviderPermissionState(
    val provider: String,
    val activeOptionId: String,
    val options: List<ProviderPermissionOption>,
    val effectiveRevision: Long? = null
) {
    val active: ProviderPermissionOption? get() = options.firstOrNull { it.id == activeOptionId }

    fun option(id: String): ProviderPermissionOption? = options.firstOrNull { it.id == id }
}

/**
 * A change sent as `PROVIDER_PERMISSION_CHANGE`: the run, the [stateRevision]
 * the user chose at (the Host refuses it if the run has moved on), the choice,
 * whether the user confirmed an elevated one, and the id its
 * `PROVIDER_PERMISSION_ACK` will answer to.
 */
data class ProviderPermissionChange(
    val invocationId: String,
    val stateRevision: Long,
    val optionId: String,
    val confirmRisk: Boolean,
    val requestId: String
)

/**
 * The Host's answer to a [ProviderPermissionChange]. Only [accepted] means the
 * Host committed it; a refusal says why in [reason], a code
 * (`network/host/provider_permissions.py`). The updated run follows as its own
 * `provider_invocation`, so the card is updated from that, not from here.
 */
data class ProviderPermissionAck(
    val requestId: String,
    val invocationId: String?,
    val accepted: Boolean,
    val stateRevision: Long? = null,
    val reason: String? = null
)

/**
 * The provider permission control's own state machine (W16AAPPLE-43).
 *
 * Deliberately separate from [HostCeilingState] (W16AAPPLE-42). The Host
 * ceiling is the most the agent may do on its Host's machine; this is how a
 * Claude Code or Codex run behaves inside it. The Host enforces the ceiling on
 * every choice here, and lowering the ceiling narrows this choice on its own,
 * so neither control ever sets the other.
 *
 * A choice goes straight out, except an elevated one (Codex's Full Access,
 * Claude Code's Bypass permissions), which waits in [ConfirmingElevated] until
 * the user confirms, since the Host asks for that risk to be confirmed
 * separately. Once sent, the control waits in [Pending] for the ACK; nothing
 * else is sent meanwhile, so two changes never race at the same revision.
 *
 * Pure: [on] returns the next state and the change to send, if any.
 */
sealed interface ProviderPermissionControlState {

    /** Nothing waiting on the user or the Host. */
    data object Idle : ProviderPermissionControlState

    /** An elevated choice was made; nothing is sent until [ProviderPermissionEvent.Confirm]. */
    data class ConfirmingElevated(val optionId: String) : ProviderPermissionControlState

    /** [optionId] was sent and its ACK has not come yet. */
    data class Pending(val optionId: String) : ProviderPermissionControlState

    fun on(event: ProviderPermissionEvent): ProviderPermissionTransition = when (event) {
        is ProviderPermissionEvent.Choose -> choose(event)
        ProviderPermissionEvent.Confirm ->
            if (this is ConfirmingElevated) {
                ProviderPermissionTransition(Pending(optionId), send = optionId, confirmRisk = true)
            } else {
                ProviderPermissionTransition(this)
            }
        // A request already sent cannot be taken back; only a confirmation can.
        ProviderPermissionEvent.Cancel ->
            if (this is ConfirmingElevated) ProviderPermissionTransition(Idle) else ProviderPermissionTransition(this)
        ProviderPermissionEvent.Settled -> ProviderPermissionTransition(Idle)
    }

    private fun choose(event: ProviderPermissionEvent.Choose): ProviderPermissionTransition {
        if (this is Pending) return ProviderPermissionTransition(this)
        val option = event.permission.option(event.optionId)
        return when {
            // Not on offer, or above the ceiling: the Host would only refuse it.
            option == null || !option.selectable -> ProviderPermissionTransition(Idle)
            option.id == event.permission.activeOptionId -> ProviderPermissionTransition(Idle)
            option.elevated -> ProviderPermissionTransition(ConfirmingElevated(option.id))
            // Any other pick, including one made while a confirmation is
            // showing, replaces that confirmation rather than waiting on it.
            else -> ProviderPermissionTransition(Pending(option.id), send = option.id)
        }
    }
}

/** What happened to the provider permission control. */
sealed interface ProviderPermissionEvent {
    /** Picked [optionId] from [permission], the run's state as shown. */
    data class Choose(val optionId: String, val permission: ProviderPermissionState) : ProviderPermissionEvent

    /** Confirmed an elevated choice. Ignored unless a confirmation is showing. */
    data object Confirm : ProviderPermissionEvent

    /** Backed out of a confirmation. */
    data object Cancel : ProviderPermissionEvent

    /** The sent change was answered, refused, could not go out, or timed out. */
    data object Settled : ProviderPermissionEvent
}

/** The state after an event, and the choice to send, if the event sends one. */
data class ProviderPermissionTransition(
    val state: ProviderPermissionControlState,
    val send: String? = null,
    val confirmRisk: Boolean = false
)
