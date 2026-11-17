package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderControl
import ai.openonion.oochat.domain.model.ProviderControlPhase
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.ui.theme.statusColors
import ai.openonion.oochat.ui.workroom.WorkRoomText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A Claude Code or Codex run in the chat (W16AAPPLE-33): which provider, the
 * task, where it stands, and the way into its Work Room. It takes the place of
 * the agent's `codex`/`claude_code` tool card, which is the same run.
 */
@Composable
internal fun WorkRoomCard(item: ChatItem.ProviderInvocation, onOpen: () -> Unit) {
    Card(
        // Read as one item, its texts in order; the Open button stays a control of its own.
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md2),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${WorkRoomText.providerName(item)} \u00B7 Work Room",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                WorkRoomStatus(item.status)
            }
            Text(
                text = WorkRoomText.title(item),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            item.control?.let { WorkRoomHandover(it) }
            WorkRoomText.summary(item)?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val facts = listOfNotNull(
                    item.activities.size.takeIf { it > 0 }?.let(WorkRoomText::steps),
                    WorkRoomText.elapsed(item.elapsedMs)
                ).joinToString(" \u00B7 ")
                Text(
                    text = facts,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpen, shape = MaterialTheme.shapes.small) {
                    Text("Open Work Room")
                }
            }
        }
    }
}

/**
 * Who holds a Claude Code session and how the handover stands, in the Host's own confirmed
 * words (W16AAPPLE-40); shared with the Work Room screen, which also gives [showDetail].
 * Failure is said in words as well as colour.
 */
@Composable
internal fun WorkRoomHandover(control: ProviderControl, showDetail: Boolean = false) {
    val color = if (control.phase == ProviderControlPhase.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
        Text(
            text = WorkRoomText.handover(control.phase),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
        if (showDetail) {
            WorkRoomText.handoverDetail(control.phase)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The run's state in words, with a spinner while it works; shared with the Work Room screen. */
@Composable
internal fun WorkRoomStatus(status: ProviderInvocationStatus) {
    val color: Color = when (status) {
        ProviderInvocationStatus.COMPLETED -> MaterialTheme.statusColors.success
        ProviderInvocationStatus.FAILED -> MaterialTheme.colorScheme.error
        // Waiting on the user, so it reads apart from the run simply working.
        ProviderInvocationStatus.AWAITING_APPROVAL -> MaterialTheme.statusColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status == ProviderInvocationStatus.RUNNING) {
            // The word beside it already says so.
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = MaterialTheme.spacing.xs)
                    .size(12.dp)
                    .clearAndSetSemantics {},
                strokeWidth = 2.dp
            )
        }
        Text(text = WorkRoomText.status(status), style = MaterialTheme.typography.labelMedium, color = color)
    }
}
