package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ProviderMessage
import ai.openonion.oochat.domain.model.ProviderMessageRole
import ai.openonion.oochat.ui.chat.markdown.MarkdownRenderer
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics

/**
 * One line of the conversation with Claude Code or Codex (W16AAPPLE-34): who said it, then
 * what they said. The text is the Host's plain text, drawn as Markdown so a reply's code
 * blocks come out in monospace, with its links left inert, since a provider's reply is not
 * trusted to send the user anywhere. The user's own lines sit on a panel, the provider's
 * on the page.
 *
 * Read by TalkBack as one item, "You" or the provider's name and then the text.
 */
@Composable
internal fun ConversationMessage(message: ProviderMessage, providerName: String) {
    val fromUser = message.role == ProviderMessageRole.USER
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (fromUser) {
                    Modifier
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(MaterialTheme.spacing.md)
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xxs)
    ) {
        Text(
            text = if (fromUser) "You" else providerName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MarkdownRenderer.RenderBlocks(text = message.text, textColor = MaterialTheme.colorScheme.onSurface)
    }
}
