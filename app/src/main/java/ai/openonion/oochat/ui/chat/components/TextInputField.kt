package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.Elevation
import ai.openonion.oochat.ui.theme.InputBarToken
import ai.openonion.oochat.ui.theme.chatBody
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Default action, so enter stays a newline: Send is in the pill in every state,
 * and nothing here has to carry a send. Constant on purpose — CoreTextField
 * restarts the input connection whenever `imeOptions` change on a focused
 * field, and a restart re-shows a keyboard the user just dismissed.
 */
private val ComposerKeyboardOptions = KeyboardOptions.Default

@Composable
fun TextInputField(
    // A TextFieldValue, not a String: the slash palette rewrites the whole
    // field on selection, and the String overload keeps the old caret offset
    // instead of following the text out to the arguments.
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    isConnected: Boolean,
    // Live socket, but the agent is gated on an invite code. Distinct from
    // !isConnected: telling the user to "connect first" when the header says
    // Connected is the kind of contradiction that makes people re-check their
    // network instead of the card sitting right above the field.
    awaitingOnboardCode: Boolean = false,
    // Whether there is an agent to send to at all; see InputBar's hasAgent.
    // Only picks the placeholder: whether the field is locked is [enabled].
    hasAgent: Boolean = true,
    // InputBar's fieldEnabled, the one rule for whether the composer takes
    // input, so the field cannot disagree with Send, the mic and the palette.
    enabled: Boolean,
    // Status only — it no longer locks the field. An INPUT arriving on a
    // running session is pushed to the agent as runtime input, so typing now is
    // an interjection, not a mistake. The placeholder still says the agent is
    // busy, since an empty field is where that fact is worth stating.
    isAgentWorking: Boolean = false,
    attachmentCount: Int,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFileClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Hoisted so InputBar can lock the approval chip while the field has focus
    // — the chip now sits below the field, in the thumb's path to the keyboard.
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    // Lets the screen hand focus over from the empty chat's centred card, so the
    // keyboard stays up through the first send (W16AAPPLE-113).
    focusRequester: FocusRequester? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val hasAttachments = attachmentCount > 0
    val placeholderText = composerPlaceholder(isConnected, awaitingOnboardCode, hasAgent, isAgentWorking, attachmentCount)
    val isFocused by interactionSource.collectIsFocusedAsState()
    val glowAlpha by animateFloatAsState(if (isFocused) 1f else 0f, label = "pillGlow")

    Box(modifier = modifier.fillMaxWidth()) {
        // Glow ring — pill radius plus the xs inset the pill sits inside.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(InputBarToken.BorderRadius + 3.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f * glowAlpha))
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.xs)
                .clip(RoundedCornerShape(InputBarToken.BorderRadius))
                .background(MaterialTheme.colorScheme.surface)
                // Quieter at rest, full weight only once focused: an empty text
                // field held at 1.5dp competed with the messages above it.
                .border(
                    if (isFocused) {
                        BorderStroke(InputBarToken.BorderWidth, MaterialTheme.colorScheme.primary)
                    } else {
                        // A light rule, as drawn: the pill is set off, not boxed in.
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    },
                    RoundedCornerShape(InputBarToken.BorderRadius)
                )
                .padding(start = MaterialTheme.spacing.xs2, top = MaterialTheme.spacing.xs2, end = MaterialTheme.spacing.xs2, bottom = MaterialTheme.spacing.xs2)
        ) {
            AttachmentMenuButton(
                enabled = enabled,
                onCameraClick = onCameraClick,
                onGalleryClick = onGalleryClick,
                onFileClick = onFileClick
            )

            val isEmpty = value.text.isEmpty()

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                maxLines = 5,
                keyboardOptions = ComposerKeyboardOptions,
                textStyle = MaterialTheme.typography.chatBody.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 0.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interactionSource,
                modifier = Modifier
                    .weight(1f)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .heightIn(min = InputBarToken.MinHeight)
                    .padding(vertical = 5.dp)
            ) { innerTextField ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.semantics {
                        if (isEmpty) {
                            this.contentDescription = placeholderText
                        }
                    }
                ) {
                    if (isEmpty) {
                        Text(
                            text = placeholderText,
                            style = MaterialTheme.typography.chatBody.copy(letterSpacing = 0.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }

            if (hasAttachments) AttachmentCountBadge(attachmentCount)

            trailingContent?.invoke()
        }
    }
}

/**
 * What the empty field says. Shared by the bottom pill and the empty chat's
 * centred card, so the two cannot disagree about why the field is locked;
 * only the idle prompt differs between them.
 */
internal fun composerPlaceholder(
    isConnected: Boolean,
    awaitingOnboardCode: Boolean,
    hasAgent: Boolean,
    isAgentWorking: Boolean,
    attachmentCount: Int,
    idle: String = "Message…"
): String {
    val hasAttachments = attachmentCount > 0
    return when {
        // Onboarding runs on a socket that hasn't reached Connected yet, so it
        // is checked inside the disconnected branch, as before.
        !isConnected && awaitingOnboardCode -> "Redeem the invite code above to start"
        !hasAgent -> "Connect to an agent first"
        // Typing is allowed offline; say where the message will wait. With
        // attachments staged, the missing text is the more useful thing to say.
        !isConnected && !hasAttachments -> "Message (sends once reconnected)"
        // Typing mid-run is an interjection the agent picks up when it can.
        isAgentWorking -> "Queue a follow-up…"
        // Send is disabled until there's text — see InputBar's canSend. Say so
        // here rather than leaving a greyed button with no explanation.
        hasAttachments -> "Add a message to send with your attachment"
        else -> idle
    }
}

/**
 * The empty chat's composer (W16AAPPLE-113): a card with the text on top and
 * every control on one row beneath it, so the box reads as the place to start
 * rather than a strip along the bottom edge. Same field as [TextInputField] —
 * same keyboard options, same focus ring rule — laid out the other way up.
 */
@Composable
internal fun CentredTextInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    // Taller on a tablet, where the card is wider and three lines of room
    // would otherwise read as a single-line field.
    minTextHeight: Dp,
    leadingTools: @Composable RowScope.() -> Unit,
    trailingTools: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(CentredCardRadius)
    // White on white in light, as drawn; in dark the card is lifted a step off
    // the page, since a shadow does not read on a near-black background.
    val container = if (colors.background.luminance() < 0.5f) colors.surfaceContainerLow else colors.surface

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The app's hairline, not the design's soft drop shadow: nothing here
            // casts more than 1dp (W16AAPPLE-49, held by ElevationTest).
            .shadow(elevation = Elevation.hairline, shape = shape)
            .clip(shape)
            .background(container)
            .border(
                if (isFocused) {
                    BorderStroke(InputBarToken.BorderWidth, colors.primary)
                } else {
                    BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f))
                },
                shape
            )
    ) {
        val isEmpty = value.text.isEmpty()
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            maxLines = 8,
            keyboardOptions = ComposerKeyboardOptions,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, letterSpacing = 0.sp),
            cursorBrush = SolidColor(colors.primary),
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .padding(start = 18.dp, end = 18.dp, top = 18.dp)
                .heightIn(min = minTextHeight)
        ) { innerTextField ->
            Box(
                contentAlignment = Alignment.TopStart,
                modifier = Modifier.semantics { if (isEmpty) contentDescription = placeholder }
            ) {
                if (isEmpty) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.sp),
                        color = colors.onSurfaceVariant
                    )
                }
                innerTextField()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingTools()
            Spacer(Modifier.weight(1f))
            trailingTools()
        }
    }
}

private val CentredCardRadius = 22.dp

/**
 * Pending-attachment count — inline beside the send controls rather than
 * overlaid on the attach button, since the button no longer represents
 * "images" specifically once it opens the camera/gallery/files menu.
 */
@Composable
internal fun AttachmentCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .padding(horizontal = MaterialTheme.spacing.xs)
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}
