package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag

/**
 * The requests waiting on an answer, above the message field and out of the transcript's scroll,
 * so one is in reach however far down the room is read (W16AAPPLE-41). Answered ones stay in the
 * transcript as a record. Nothing is drawn when none is waiting.
 *
 * At most [WORK_ROOM_DOCK_MAX_HEIGHT_FRACTION] of the height it is given that the [keyboard] does not
 * cover, scrolling inside that, so a run asking for many things at once cannot push the transcript
 * off a short screen, or off one with the keyboard open.
 */
@Composable
internal fun WorkRoomApprovalDock(
    approvals: List<ChatItem.ApprovalNeeded>,
    onDecision: (itemId: String, approved: Boolean, scope: String) -> Unit,
    keyboard: WindowInsets = WindowInsets.ime
) {
    if (approvals.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                modifier = Modifier
                    .widthIn(max = WORK_ROOM_COLUMN_MAX_WIDTH)
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val capped = constraints.copy(
                            maxHeight = workRoomDockMaxHeight(constraints.maxHeight, keyboard.getBottom(this))
                        )
                        val placeable = measurable.measure(capped)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm)
                    .testTag(WORK_ROOM_APPROVAL_DOCK_TAG)
            ) {
                approvals.forEach { item ->
                    val approval = item.workRoom ?: return@forEach
                    key(item.id) {
                        WorkRoomApprovalCard(item, approval) { approved, scope ->
                            onDecision(item.id, approved, scope)
                        }
                    }
                }
            }
        }
    }
}

internal const val WORK_ROOM_APPROVAL_DOCK_TAG = "work-room-approval-dock"

/** The share of the height that is not under the keyboard that the dock may take. */
internal const val WORK_ROOM_DOCK_MAX_HEIGHT_FRACTION = 0.4f

/**
 * The tallest the dock may be, in pixels, in a window [availableHeight] tall with [keyboardHeight]
 * of it under the keyboard. The keyboard is an inset, not a resize, so the height the dock is
 * given still counts it: 40% of that would leave nothing of the transcript above the keyboard.
 */
internal fun workRoomDockMaxHeight(availableHeight: Int, keyboardHeight: Int): Int =
    ((availableHeight - keyboardHeight).coerceAtLeast(0) * WORK_ROOM_DOCK_MAX_HEIGHT_FRACTION).toInt()
