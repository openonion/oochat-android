package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderPermissionControlState
import ai.openonion.oochat.domain.model.ProviderPermissionOption
import ai.openonion.oochat.domain.model.ProviderPermissionState
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal const val WORK_ROOM_PERMISSION_TAG = "work-room-permission"

/**
 * A Work Room's provider permission control (W16AAPPLE-43): Claude Code's or
 * Codex's own mode for this run, as the Host reports it, and a sheet to change
 * it.
 *
 * Its own card in the room, never beside the chat's approval chip, and always
 * named after the provider, so it never reads as the Host ceiling: the chip
 * sets how much the whole agent may do, this sets how the provider works
 * inside that. Choices above the ceiling are shown greyed out with the Host's
 * reason, so the link between the two stays visible without either control
 * setting the other. An elevated choice asks to be confirmed first.
 */
@Composable
internal fun WorkRoomPermissionControl(
    run: ChatItem.ProviderInvocation,
    permission: ProviderPermissionState,
    ui: WorkRoomPermissionUi,
    onChoose: (optionId: String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val pending = ui.control as? ProviderPermissionControlState.Pending
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag(WORK_ROOM_PERMISSION_TAG),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = WorkRoomText.permissionTitle(run),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                TextButton(onClick = { sheetOpen = true }, enabled = pending == null) {
                    Text(if (pending != null) WorkRoomText.PERMISSION_CHANGING else "Change")
                }
            }
            Text(
                text = permission.active?.label.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            permission.active?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = WorkRoomText.permissionExplainer(run),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ui.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (sheetOpen) {
        PermissionSheet(
            run = run,
            permission = permission,
            onPick = { optionId ->
                sheetOpen = false
                onChoose(optionId)
            },
            onDismiss = { sheetOpen = false }
        )
    }

    (ui.control as? ProviderPermissionControlState.ConfirmingElevated)?.let { confirming ->
        val option = permission.option(confirming.optionId) ?: return@let
        ElevatedConfirmation(run, option, onConfirm, onCancel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionSheet(
    run: ChatItem.ProviderInvocation,
    permission: ProviderPermissionState,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.spacing.lg)
                .padding(bottom = MaterialTheme.spacing.xl)
        ) {
            Text(
                text = WorkRoomText.permissionTitle(run),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = WorkRoomText.permissionExplainer(run),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.padding(top = MaterialTheme.spacing.sm).selectableGroup()) {
                permission.options.forEach { option ->
                    OptionRow(
                        option = option,
                        selected = option.id == permission.activeOptionId,
                        onClick = { onPick(option.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(option: ProviderPermissionOption, selected: Boolean, onClick: () -> Unit) {
    val enabled = option.selectable
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            // Radio semantics, as the approval mode sheet's rows: TalkBack reads the selected state.
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (option.elevated) "${option.label} · higher risk" else option.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else dim
            )
            if (option.description.isNotBlank()) {
                Text(option.description, style = MaterialTheme.typography.bodySmall, color = dim)
            }
            if (!enabled) {
                Text(
                    text = option.disabledReason ?: "Not available in the agent's current approval mode.",
                    style = MaterialTheme.typography.labelSmall,
                    color = dim
                )
            }
        }
    }
}

@Composable
private fun ElevatedConfirmation(
    run: ChatItem.ProviderInvocation,
    option: ProviderPermissionOption,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(WorkRoomText.permissionConfirmTitle(run, option.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                Text(WorkRoomText.permissionConfirmBody(run))
                if (option.description.isNotBlank()) Text(option.description)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Switch") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
