package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ProviderArtifact
import ai.openonion.oochat.ui.chat.components.InlineImage
import ai.openonion.oochat.ui.theme.hairline
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The latest picture of the run's workspace or browser (W16AAPPLE-34), which the Host captured
 * itself and sent inline. It is described to TalkBack by the Host's own approved words
 * ([ProviderArtifact.alt]), never by text that came with the image.
 */
@Composable
internal fun LatestView(artifact: ProviderArtifact) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Text(
            text = "Latest view",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        InlineImage(
            dataUrl = artifact.dataUrl,
            contentDescription = artifact.alt,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                // A plate to stand on while the picture is decoded, so the room does not jump.
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .heightIn(min = 96.dp)
                .border(MaterialTheme.hairline, MaterialTheme.shapes.medium)
        )
    }
}
