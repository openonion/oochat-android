package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.WorkRoomApproval
import ai.openonion.oochat.ui.theme.ButtonToken
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal const val WORK_ROOM_DENY_MODE = "reject_soft"

/** [onDecide]'s scope must be "once" or "session": the Host reads any other value as a denial. */
@Composable
internal fun WorkRoomApprovalCard(
    item: ChatItem.ApprovalNeeded,
    approval: WorkRoomApproval,
    onDecide: (approved: Boolean, scope: String) -> Unit
) {
    val presentation = approval.presentation
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = if (item.decision == null) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md2),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Text(
                text = WorkRoomText.providerName(approval.provider),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = WorkRoomText.approvalTitle(approval),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            presentation?.let {
                Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it.scope, style = MaterialTheme.typography.labelMedium)
                if (it.files.isNotEmpty()) {
                    Text(
                        text = it.files.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val decision = item.decision
            if (decision != null) {
                Text(
                    text = WorkRoomText.approvalOutcome(decision),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (decision.refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                Text("Waiting for your decision", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            WorkRoomText.approvalDenyOnly(approval)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (approval.canAllowOnce) {
                Button(
                    onClick = { onDecide(true, "once") },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonToken.Compact)
                ) { Text("Allow once") }
            }
            if (approval.canAllowSession) {
                TextButton(
                    onClick = { onDecide(true, "session") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonToken.Compact)
                ) { Text("Allow for this session") }
            }
            OutlinedButton(
                onClick = { onDecide(false, "once") },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonToken.Compact)
            ) { Text("Deny") }
        }
    }
}
