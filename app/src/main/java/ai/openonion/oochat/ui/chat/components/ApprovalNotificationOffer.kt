package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The chat's one offer to turn on notifications for approvals (W16AAPPLE-44),
 * shown while the first approval is on screen: the moment the reason is plain,
 * which is when Android asks apps to request the permission. Beside the
 * composer, like [LowBalanceBar], so it never covers the approval card itself
 * the user is about to answer.
 *
 * @param agentName Named in the sentence when known.
 * @param onTurnOn Asks the OS for permission where it has to, then turns them on.
 * @param onNotNow Not offered again; Settings stays the way back.
 */
@Composable
internal fun ApprovalNotificationOffer(
    agentName: String?,
    onTurnOn: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val who = agentName?.takeIf { it.isNotBlank() } ?: "your agent"
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = MaterialTheme.spacing.lg, end = MaterialTheme.spacing.xs2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
        ) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                // "In another app", not "closed": with no background service,
                // a closed app notices nothing (see the user guide, section 9).
                text = "Get a notification when $who needs your approval while you're in another app?",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onNotNow) { Text("Not now", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = onTurnOn) { Text("Turn on", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
