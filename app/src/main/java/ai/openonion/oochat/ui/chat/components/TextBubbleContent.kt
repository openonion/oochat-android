package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.chat.markdown.MarkdownRenderer
import ai.openonion.oochat.ui.theme.MessageBubbleToken
import ai.openonion.oochat.ui.theme.bubbleShape
import ai.openonion.oochat.ui.theme.chatBody
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

@Composable
internal fun TextBubbleContent(
    content: String,
    isUser: Boolean,
    renderMarkdown: Boolean = true,
    modifier: Modifier = Modifier
) {
    // The user's message sits in a faintly tinted bubble; the agent's reply is
    // plain text — no border, fill or icon — so it reads as the answer rather
    // than as one more step of the work above it (W16AAPPLE-112).
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier.then(
            if (isUser) {
                val shape = bubbleShape(isUser = true)
                Modifier
                    // Same proportional cap the image/voice bubbles use — see
                    // MessageBubbleToken.
                    .bubbleMaxWidth(MessageBubbleToken.MaxWidthFraction, MessageBubbleToken.MaxWidth)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = MessageBubbleToken.UserFillAlpha))
                    .padding(
                        horizontal = MessageBubbleToken.PaddingHorizontal,
                        vertical = MessageBubbleToken.PaddingVertical
                    )
            } else {
                Modifier
            }
        )
    ) {
        SelectionContainer {
            if (renderMarkdown) {
                MarkdownRenderer.RenderBlocks(
                    text = content,
                    textColor = textColor
                )
            } else {
                Text(
                    text = content,
                    color = textColor,
                    style = MaterialTheme.typography.chatBody
                )
            }
        }
    }
}
