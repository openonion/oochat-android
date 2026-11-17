package ai.openonion.oochat.ui.components

import ai.openonion.oochat.domain.model.AgentStatus
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for status color, shared by the agent list and nav
 * drawer so the two can't drift. Connecting shares the connected green, the
 * palette having no second accent; the pulsing dot and the spinning badge say
 * it is still on the way.
 */
@Composable
fun agentStatusColor(status: AgentStatus): Color = when (status) {
    AgentStatus.Active, AgentStatus.Connected, AgentStatus.Connecting -> MaterialTheme.colorScheme.primary
    is AgentStatus.Error -> MaterialTheme.colorScheme.error
    AgentStatus.Disabled -> MaterialTheme.colorScheme.outline
}
