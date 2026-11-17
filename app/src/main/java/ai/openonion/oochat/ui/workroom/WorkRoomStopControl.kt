package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.WorkRoomStopState
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal const val WORK_ROOM_STOP_TAG = "work-room-stop"

/**
 * A Work Room's scoped Stop (W16AAPPLE-37). Stop sends nothing: it opens a
 * confirmation that says only this run stops, and counts down the 15-second
 * barrier it stays open for ([WorkRoomStopState.BARRIER_MS]). The ViewModel
 * closes it on time; the countdown here only shows it.
 *
 * Shown while the run is live. Once it has ended, its status ("Stopped",
 * "Completed"...) says so and there is nothing left to stop.
 */
@Composable
internal fun WorkRoomStopControl(
    run: ChatItem.ProviderInvocation,
    ui: WorkRoomStopUi,
    onStop: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    if (run.status.isTerminal) return
    Column(
        modifier = Modifier.fillMaxWidth().testTag(WORK_ROOM_STOP_TAG),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        when (ui.state) {
            WorkRoomStopState.Confirming -> StopConfirmation(run, onConfirm, onCancel)
            WorkRoomStopState.Sending, WorkRoomStopState.Stopping ->
                StopButton(label = WorkRoomText.STOP_SENDING, enabled = false, onClick = {})
            WorkRoomStopState.Idle -> StopButton(label = WorkRoomText.STOP, enabled = true, onClick = onStop)
        }
        ui.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@Composable
private fun StopButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        // The theme's shape, as the Work Room approval card's buttons, not Material's pill.
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = MaterialTheme.spacing.xs))
    }
}

@Composable
private fun StopConfirmation(run: ChatItem.ProviderInvocation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var secondsLeft by remember { mutableIntStateOf((WorkRoomStopState.BARRIER_MS / 1000).toInt()) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Text(
                text = WorkRoomText.stopTitle(run),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = WorkRoomText.stopBody(run),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                Text(
                    text = WorkRoomText.stopCountdown(secondsLeft.coerceAtLeast(1)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) { Text(WorkRoomText.STOP_KEEP) }
                Button(
                    onClick = onConfirm,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text(WorkRoomText.STOP_CONFIRM) }
            }
        }
    }
}
