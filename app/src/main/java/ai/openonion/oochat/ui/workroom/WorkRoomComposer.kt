package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.ui.chat.components.SendButton
import ai.openonion.oochat.ui.theme.InputBarToken
import ai.openonion.oochat.ui.theme.chatBody
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Work Room's message field (W16AAPPLE-35), styled as the chat's composer
 * without its attachments. While a send waits for the provider the field is
 * locked and Send becomes a spinner; a send that did not go through leaves the
 * text where it was, with the reason above it.
 */
@Composable
internal fun WorkRoomComposer(
    run: ChatItem.ProviderInvocation,
    draft: WorkRoomDraft,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val blocked = WorkRoomInputs.blockedReason(run)
    val canEdit = blocked == null && !draft.sending
    val canSend = canEdit && draft.text.isNotBlank()
    val placeholder = WorkRoomText.inputPlaceholder(run)

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                // The keyboard and the navigation bar, as the chat's composer takes them.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                modifier = Modifier
                    // Capped like the room's own column, for a tablet.
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm)
            ) {
                // Why it cannot send, or why the last send did not go through. Read out as it appears.
                (blocked ?: draft.error)?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (blocked == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    MessageField(
                        value = draft.text,
                        onValueChange = onDraftChange,
                        enabled = canEdit,
                        placeholder = placeholder,
                        modifier = Modifier.weight(1f)
                    )
                    if (draft.sending) {
                        // The 48dp of the Send it stands in for, so the field does not jump.
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .size(24.dp)
                                    .semantics { contentDescription = "Sending" }
                            )
                        }
                    } else {
                        SendButton(enabled = canSend, onClick = onSend)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(InputBarToken.BorderRadius)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        maxLines = 5,
        textStyle = MaterialTheme.typography.chatBody.copy(
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = 0.sp
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        modifier = modifier
            .heightIn(min = InputBarToken.MinHeight)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                if (focused) {
                    BorderStroke(InputBarToken.BorderWidth, MaterialTheme.colorScheme.primary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                shape
            )
            .padding(horizontal = InputBarToken.PaddingHorizontal, vertical = MaterialTheme.spacing.sm)
    ) { innerTextField ->
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.semantics { if (value.isEmpty()) contentDescription = placeholder }
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.chatBody.copy(letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            innerTextField()
        }
    }
}
